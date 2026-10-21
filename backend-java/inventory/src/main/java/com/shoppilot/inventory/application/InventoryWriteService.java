package com.shoppilot.inventory.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.util.QtyUtils;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.inventory.application.dto.AnchorLockResult;
import com.shoppilot.inventory.application.dto.StockChangeResult;
import com.shoppilot.inventory.domain.InventoryBizType;
import com.shoppilot.inventory.domain.InventoryTxnType;
import com.shoppilot.inventory.infrastructure.persistence.InventoryBalanceUpdater;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryIdempotencyKeyEntity;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryTransactionEntity;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryBalanceMapper;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryIdempotencyKeyMapper;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryBalanceMapper.BalanceDelta;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryBalanceMapper.BalanceKey;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryTransactionMapper;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryTransactionMapper.TxnInsertRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 库存写入服务 —— {@code inventory_balances} / {@code inventory_transactions} 的<b>唯一写入口</b>
 * （{@code MODULE_BOUNDARIES §2.5}）。除本类外，任何模块都不得直接改余额
 * （{@code DESIGN_BASIS §7.1}：{@code purchasing} 只能通过本服务写入，方向不可反）。
 *
 * <h2>单笔变更的固定步骤（{@code DATABASE_DESIGN §6-C.2} / {@code INVENTORY_WORKFLOW §7.2}）</h2>
 * <ol>
 *   <li>定位余额行（{@code team_id + warehouse_id + sku_id}）；</li>
 *   <li>读 {@code version}（乐观锁）；</li>
 *   <li>校验 {@code qty_after = qty_before + qty_delta >= 0}；</li>
 *   <li><b>先写流水</b>（{@code qty_before = 余额当前 on_hand_qty}、{@code qty_after = qty_before + qty_delta}）；</li>
 *   <li>再 {@code UPDATE ... WHERE id = ? AND version = ?} 推进余额（{@code version + 1}）；</li>
 *   <li>影响行数 ≠ 1 → 回滚到<b>本次尝试的 SAVEPOINT</b> 后重试，最多 3 次重试（退避 20/50/120 ms），
 *       仍失败 → {@code INV-5002}。</li>
 * </ol>
 * 顺序不可颠倒：先改余额再写流水会让 {@code qty_before} 失真，破坏「流水自证 + 余额可复算」。
 *
 * <h2>★ 为什么重试单元是 {@code NESTED} 而不是 {@code REQUIRED}</h2>
 * {@code REQUIRED} 会加入<b>外层</b>事务（如 {@code GoodsReceiptAppService.confirm()}）；一旦参与方抛异常，
 * 外层事务被标记 {@code rollback-only}，最终在提交处炸出 {@code UnexpectedRollbackException}
 * 而不是本批期望的 {@code INV-5002}（M3-B4-PERF 已实测踩过）。
 * {@code NESTED} 在外层事务内建 <b>SAVEPOINT</b>：失败只回滚到本次尝试开始点——
 * 正是这一步保证了「流水已插入、余额乐观锁失败」时<b>不会留下孤儿流水</b>；
 * 无外层事务时 {@code NESTED} 等价于 {@code REQUIRED}（Spring 语义）。
 *
 * <h2>★ 为什么隔离级别显式声明 {@code READ COMMITTED}</h2>
 * {@code DATABASE_DESIGN §7.1} 要求 {@code READ COMMITTED}，但工程内<b>未</b>全局配置，MySQL 默认
 * {@code REPEATABLE READ}。在 RR 下，同一事务内重试时的 {@code SELECT} 仍读<b>旧快照</b>
 * （快照在事务首次读时固定，SAVEPOINT 回滚不会刷新），乐观锁重试将永远读到旧 {@code version}
 * 而必然 3 次全败。故在此显式声明（参与调用方事务时由 Spring 忽略，见 {@code §7.1}「跨模块传播」）。
 *
 * <h2>错误码分流（★ 不得混用）</h2>
 * <ul>
 *   <li>{@code INV-5001}（409）—— <b>出库</b>导致负库存（业务上「库存不足」）；</li>
 *   <li>{@code INV-5006}（409）—— <b>调整 / 冲销</b>导致负库存；</li>
 *   <li>{@code INV-5005}（400）—— 调整数量为 0 或非法（含 {@code null}、精度超 3 位）；</li>
 *   <li>{@code INV-5004}（404）—— 调整 / 出库 / 冲销时余额行不存在；</li>
 *   <li>{@code INV-5008}（404）/ {@code INV-5010}（422）/ {@code INV-5009}（409）—— 冲销三态；</li>
 *   <li>{@code INV-5011}（400）—— 调整原因为空；</li>
 *   <li>{@code INV-5002}（409）/ {@code INV-5003}（409）—— 乐观锁重试耗尽 / 幂等键内容冲突。</li>
 * </ul>
 *
 * <h2>审计埋点（{@code M3-T17} / {@code B9}，{@code DESIGN_BASIS §9.17} / {@code DATABASE_DESIGN §6-J.1}）</h2>
 * <ul>
 *   <li><b>覆盖三条</b>：{@code #11 INVENTORY_MANUAL_ADJUST}（{@code biz_type=INVENTORY_BALANCE}）、
 *       {@code #12 INVENTORY_COUNT_ADJUST}（{@code INVENTORY_BALANCE}）、
 *       {@code #13 INVENTORY_REVERSE}（{@code INVENTORY_TRANSACTION}）。</li>
 *   <li><b>只写成功</b>：审计写在 {@link #runWithRetry} <b>之外</b>的最终成功点——写进重试单元会
 *       「重试重复写」或「随 {@code SAVEPOINT} 回滚丢失」。失败路径（{@code INV-5003/5005/5006/…}）
 *       直接抛异常，不产生审计行。</li>
 *   <li><b>不写「无变更」</b>：幂等重放（{@code idempotentHit=true}，含并发首次 upsert 的竞态重判）
 *       与盘点差额为 0（{@code §5.5} 步骤 G 不写流水）一律<b>不写审计</b>——重放的语义是「上次已记过」，
 *       再写一条会把同一次变更计成两次，且 {@code occurred_at}/{@code 前后值} 都不是本次事实。</li>
 *   <li><b>{@code biz_id} 口径</b>：调整 → 本次新增流水 id（余额行 id 未在 {@code StockChangeResult}
 *       暴露，流水 id 是唯一可追溯键）；冲销 → <b>被冲销的原流水 id</b>，新流水 id 写进
 *       {@code change_summary}。</li>
 *   <li><b>无团队上下文不写</b>：{@code team_id} / 操作人一律取 {@link TeamContext}，
 *       缺团队即跳过并记 <b>WARN</b>（不静默），<b>不造无主审计行</b>。</li>
 *   <li><b>★ 同事务（{@code M3-B9.5} / {@code R72}）</b>：三条动作走
 *       {@code AuditWriteService.recordInBusinessTx}——审计与业务<b>同生共死</b>
 *       （{@code MODULE_BOUNDARIES §2.10}「高风险操作审计与业务同事务」）。理由见 {@link #recordAudit}。
 *       <b>与本模块 {@code InventoryPolicyService}（#7 策略变更）刻意不同</b>：§2.10 的高风险清单是
 *       「库存调整、审批、确认到货、取消采购单」，<b>不含</b>补货策略变更；策略仍走解耦通道
 *       {@code record}（审计失败不阻塞业务）。两者语义不同是<b>有意的</b>，不要统一。</li>
 * </ul>
 *
 * <h2>本批<b>刻意不做</b>的事（边界，勿在本类补）</h2>
 * <ul>
 *   <li><b>不做角色校验</b>：{@code INV-5007}（{@code MANUAL_ADJUST} 需 {@code ADMIN}+）属 API 层
 *       {@code M3-T11}（{@code INVENTORY_WORKFLOW §5.5}「角色校验」步骤）；服务层读角色会与
 *       {@code @RequireRole} 切面重复且难以在跨模块调用时判定。</li>
 *   <li><b>不构造幂等键</b>：{@code idempotency_key} 由调用方按 {@code §8.3}
 *       {@code <txn_type>:<biz_type>:<biz_ref_id>:<sku_id>[:<suffix>]} 构造后传入，本类只校验
 *       「非空 + 长度 ≤ 128」。</li>
 *   <li><b>不校验 sku / warehouse 是否同团队</b>：本模块不注入 {@code catalog} 的 Mapper
 *       （{@code MODULE_BOUNDARIES §2.4} 禁止跨模块直接用对方 Mapper，参见风险 {@code R46}）；
 *       调用方（{@code importdata} / {@code purchasing}）已在自身租户过滤下解析出 id。</li>
 * </ul>
 */
@Service
public class InventoryWriteService {

    private static final Logger log = LoggerFactory.getLogger(InventoryWriteService.class);

    /** 尝试次数上限：1 次初试 + 3 次重试（{@code §8.1}「重试 3 次，退避 20/50/120 ms」）。 */
    static final int MAX_ATTEMPTS = 4;

    /** 每次尝试失败后的退避（毫秒），长度 = {@code MAX_ATTEMPTS - 1}。 */
    private static final long[] RETRY_BACKOFF_MILLIS = {20L, 50L, 120L};

    /** {@code idempotency_key} 列宽（{@code VARCHAR(128)}，{@code DATABASE_DESIGN §5.10}）。 */
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    /**
     * 批量路径的分片大小（M8-T01 / {@code R194}）。
     *
     * <p>500 与 {@code SALES_DAILY} 的导入分批同值，也是为了压住单条语句的体积：
     * 流水一行 13 列、幂等键长约 50 字符，500 行 ≈ 25 KB，远低于 {@code max_allowed_packet} 默认值，
     * 又不至于让一条 SQL 的解析/优化开销失控。1 万行 ⇒ 每类语句 20 条。
     */
    private static final int CHUNK = 500;

    private final InventoryBalanceMapper balanceMapper;
    private final InventoryTransactionMapper transactionMapper;
    /** M8-T04（ADR-008 / R76）：差额 0 的幂等键占位表。 */
    private final InventoryIdempotencyKeyMapper idempotencyKeyMapper;
    private final InventoryBalanceUpdater balanceUpdater;
    private final AuditWriteService auditWriteService;
    private final TransactionTemplate attemptTemplate;
    /** M8-T05（{@code R185}）：冲销 {@code PURCHASE_INBOUND} 时同事务回退采购侧 {@code received_qty} 的端口。 */
    private final PurchaseInboundReversalPort purchaseInboundReversalPort;

    public InventoryWriteService(InventoryBalanceMapper balanceMapper,
                                 InventoryTransactionMapper transactionMapper,
                                 InventoryBalanceUpdater balanceUpdater,
                                 AuditWriteService auditWriteService,
                                 PlatformTransactionManager transactionManager,
                                 InventoryIdempotencyKeyMapper idempotencyKeyMapper,
                                 PurchaseInboundReversalPort purchaseInboundReversalPort) {
        this.balanceMapper = balanceMapper;
        this.transactionMapper = transactionMapper;
        this.idempotencyKeyMapper = idempotencyKeyMapper;
        this.balanceUpdater = balanceUpdater;
        this.auditWriteService = auditWriteService;
        this.attemptTemplate = new TransactionTemplate(transactionManager);
        this.purchaseInboundReversalPort = purchaseInboundReversalPort;
        // ★ 重试单元 = SAVEPOINT（见类注释）；无外层事务时 Spring 退化为 REQUIRED。
        this.attemptTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_NESTED);
    }

    // =====================================================================
    // 6 个写方法（MODULE_BOUNDARIES §2.5；applyCountAdjust 的 reason 按 R55 增加）
    // =====================================================================

    /**
     * 初始库存导入（{@code INVENTORY_WORKFLOW §5.1}）：{@code txn_type = INIT_IMPORT}、
     * {@code biz_type = IMPORT}、{@code biz_ref_id = 导入批次 ID}，<b>按增量累加</b>（不覆盖既有余额）。
     *
     * <p>余额行不存在时<b>自动建行</b>（初值 0 再累加，{@code §6-C.6} 并发首次建行）；
     * {@code INIT_IMPORT} 只增不减，不会触发 {@code INV-5006}。
     *
     * <h2>★ 为什么传播是 {@code NESTED} 而不是 {@code REQUIRED}（{@code R79}，{@code M3-B10.6}）</h2>
     * 本方法的<b>唯一</b>批量调用方是 {@code ImportConfirmService} 的 Pass 2（逐行重放）。
     * 在那里，每一行必须是一个可独立回滚的单元：若行内的 {@code BizException}（如 {@code INV-5003}）
     * 参与并「污染」了外层批事务，Spring 的 {@code globalRollbackOnParticipationFailure=true}
     * 会把<b>共享事务</b>标 {@code rollback-only} ⇒ 外层 {@code TransactionTemplate.commit()} 抛
     * {@code UnexpectedRollbackException} ⇒ 该异常既不是 {@code BizException} 也不是
     * {@code DuplicateKeyException}，被判为「非行级失败」⇒ 整批回滚 + 全行记 {@code COM-0002}，
     * 也就是说 {@code PARTIAL_SUCCESS} 实际不可达、行级业务错误码被替换成「系统内部错误」。
     * 改 {@code NESTED} 后，行内失败只回滚到本行的 {@code SAVEPOINT}，外层批事务继续，
     * 与主数据单行入口 {@code CatalogWriteService#upsertSkuByCode} 完全同构。
     *
     * <p><b>HTTP 直调语义不变</b>：无外层事务时 Spring 将 {@code NESTED} 退化为「新建事务」，
     * 与外层存在时的 {@code REQUIRED} 表现一致（唯一差别是无 {@code SAVEPOINT} 往返）。
     * <p><b>隔离级别</b>：{@code SAVEPOINT} 无法单独设定隔离级别——存在外层事务时本注解的
     * {@code READ_COMMITTED} 由 Spring 静默忽略（沿用外层级别），无外层事务时按 {@code READ_COMMITTED} 新建。
     *
     * @param qty            导入数量（&gt; 0，精度 ≤ 3 位）
     * @param bizRefId       导入批次 ID（可空）
     * @param idempotencyKey 幂等键（调用方按 {@code §5.10} 构造：{@code INIT_IMPORT:IMPORT:<batchId>:<skuId>:<warehouseId>}，长度 ≤ 128）
     * @throws BizException {@code INV-5003} / {@code INV-5002}
     */
    @Transactional(propagation = Propagation.NESTED, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public StockChangeResult applyInitialImport(long skuId, long warehouseId, BigDecimal qty,
                                                Long bizRefId, String idempotencyKey) {
        requirePositiveQuantity(qty, "qty");
        String key = requireIdempotencyKey(idempotencyKey);
        return runWithRetry(() -> attemptInflow(skuId, warehouseId, InventoryTxnType.INIT_IMPORT,
                InventoryBizType.IMPORT, qty, bizRefId, null, key));
    }

    /**
     * 批量初始库存导入（M8-T01 / {@code R194}）：{@code INVENTORY_SNAPSHOT} 确认期的高吞吐路径。
     *
     * <h2>为什么要有这个方法（{@code R194} 的根因）</h2>
     * 逐行 {@link #applyInitialImport} 在 1 万行规模下要付 ~6 万次语句往返
     * （每行：{@code NESTED} SAVEPOINT/RELEASE 两次 + 建行/读余额/幂等回查三次查询 + 流水 INSERT
     * + 余额 UPDATE + 审计），真栈实测 confirm 服务端 **59.5s**，贴住 {@code NFR-PERF-02} 的 60s 门槛
     * （5 轮 55.0/59.5/59.7/60.6/60.8s，分布横跨门槛两侧）。本方法把往返压到
     * <b>约 5×N/500 条语句</b>（N = 行数），1 万行约 100 条。
     *
     * <h2>★ 与单行版的语义差异（必须知情的取舍）</h2>
     * <ol>
     *   <li><b>行级失败隔离降级</b>：单行版靠 {@code NESTED} 让某行失败只回滚该行；
     *       批量版是「先筛后写」——参数非法 / 幂等键非法 / 同批同键重复在<b>写库前</b>拦掉，
     *       真正不可预期的 DB 异常会让<b>整片</b>回滚（{@code IMPORT_WORKFLOW} 的
     *       {@code PARTIAL_SUCCESS} 语义由「确认期行级失败」退到「片级失败」）。
     *       {@code INIT_IMPORT} 是纯正增量，唯一键冲突已被「文件内重复探测 + 幂等回查」双重覆盖，
     *       残留风险可控（已登记 {@code R196}）。</li>
     *   <li><b>不用 version CAS</b>：批量更新是 {@code on_hand_qty = on_hand_qty + delta} 的纯增量，
     *       不存在读-改-写丢失窗口；{@code version} 仍 {@code +1} 保持可观测语义。</li>
     *   <li><b>同批同键</b>（文件内重复，正常应由 {@code importdata} 先行拦掉）：本服务按
     *       「首行写入、后续行判重放」处理，与单行版连续两次调用的行为一致。</li>
     * </ol>
     *
     * <h2>不变量保持清单（回归红线）</h2>
     * <ul>
     *   <li>同批次 + 同 SKU + <b>同仓库</b>重复确认 ⇒ 纯重放，余额与流水都不增；</li>
     *   <li>幂等键比对字段<b>不含 {@code reason}</b>（{@code R75}）；</li>
     *   <li>幂等键后缀必填 {@code :<warehouse_id>}（{@code R180}，由调用方构造）；</li>
     *   <li>{@code last_txn_id} 必须回填真实流水 ID（查回得到，<b>不</b>用 {@code LAST_INSERT_ID()} 推断）；</li>
     *   <li>租户线：所有查询/更新均受约束，跨团队行既读不到也改不了。</li>
     * </ul>
     *
     * @param rows     导入行（幂等键由调用方按 {@code §5.10} 要点 4 构造，含 {@code :<warehouse_id>} 后缀）
     * @param bizRefId 业务单据 ID（导入批次 ID）
     * @return 实际写入行数 与 判为重放的行数
     * @throws BizException {@code COM-0001}（参数非法）/ {@code INV-5004}（余额行缺失）/
     *                      {@code INV-5003}（同键内容不一致）
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public InitialImportBatchResult applyInitialImportBatch(List<InitialImportRow> rows, Long bizRefId) {
        if (rows == null || rows.isEmpty()) {
            return new InitialImportBatchResult(0, 0);
        }
        long teamId = currentTeamId();
        Long operatorId = currentOperatorId();
        LocalDateTime occurredAt = LocalDateTime.now(ZoneOffset.UTC);

        // ---- 1) 入库前筛除确定性错误；同键只留首行（后续行判重放）----
        List<InitialImportRow> accepted = new ArrayList<>(rows.size());
        Set<String> seenKeys = new HashSet<>();
        int preRejected = 0;
        for (InitialImportRow row : rows) {
            if (row == null) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("rows[]", "行不能为空")));
            }
            String key = requireIdempotencyKey(row.idempotencyKey());
            if (row.qty() == null || row.qty().signum() <= 0) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("qty", "qty 必须为正数")));
            }
            if (!seenKeys.add(key)) {
                preRejected++;
                continue;
            }
            accepted.add(new InitialImportRow(row.skuId(), row.warehouseId(), row.qty(), key));
        }
        if (accepted.isEmpty()) {
            return new InitialImportBatchResult(0, preRejected);
        }

        // ---- 2) 批量建行（ODKU 吸收并发首次建行）----
        Set<BalanceKey> distinctKeys = new LinkedHashSet<>();
        for (InitialImportRow row : accepted) {
            distinctKeys.add(new BalanceKey(row.warehouseId(), row.skuId()));
        }
        List<BalanceKey> keyList = new ArrayList<>(distinctKeys);
        for (int from = 0; from < keyList.size(); from += CHUNK) {
            balanceMapper.insertIfAbsentBatch(teamId,
                    keyList.subList(from, Math.min(from + CHUNK, keyList.size())), operatorId);
        }

        // ---- 3) 按仓库分组批量读余额 ----
        Map<BalanceKey, InventoryBalanceEntity> balances = new HashMap<>();
        Map<Long, List<Long>> skuIdsByWarehouse = new LinkedHashMap<>();
        for (BalanceKey key : distinctKeys) {
            skuIdsByWarehouse.computeIfAbsent(key.warehouseId(), k -> new ArrayList<>()).add(key.skuId());
        }
        for (Map.Entry<Long, List<Long>> entry : skuIdsByWarehouse.entrySet()) {
            for (int from = 0; from < entry.getValue().size(); from += CHUNK) {
                List<Long> chunk = entry.getValue().subList(from,
                        Math.min(from + CHUNK, entry.getValue().size()));
                for (InventoryBalanceEntity entity : balanceMapper.selectByWarehouseSkuKeys(entry.getKey(), chunk)) {
                    balances.put(new BalanceKey(entity.getWarehouseId(), entity.getSkuId()), entity);
                }
            }
        }

        // ---- 4) 批量幂等回查：命中且内容一致 ⇒ 重放；内容不一致 ⇒ INV-5003 ----
        List<InitialImportRow> toWrite = new ArrayList<>(accepted.size());
        int replayed = preRejected;
        List<String> allKeys = new ArrayList<>(accepted.size());
        Map<String, BigDecimal> qtyByKey = new HashMap<>();
        for (InitialImportRow row : accepted) {
            allKeys.add(row.idempotencyKey());
            qtyByKey.put(row.idempotencyKey(), row.qty());
        }
        Set<String> existingKeys = new HashSet<>();
        for (int from = 0; from < allKeys.size(); from += CHUNK) {
            for (InventoryTransactionEntity existing : transactionMapper.selectByIdempotencyKeys(
                    allKeys.subList(from, Math.min(from + CHUNK, allKeys.size())))) {
                existingKeys.add(existing.getIdempotencyKey());
                assertSameReplayContent(existing, teamId, bizRefId,
                        qtyByKey.get(existing.getIdempotencyKey()));
            }
        }
        for (InitialImportRow row : accepted) {
            if (existingKeys.contains(row.idempotencyKey())) {
                replayed++;
            } else {
                toWrite.add(row);
            }
        }
        if (toWrite.isEmpty()) {
            return new InitialImportBatchResult(0, replayed);
        }

        // ---- 5) 内存推算 qty_before / qty_after（同 (仓库, SKU) 多行按序累加）----
        Map<BalanceKey, BigDecimal> runningOnHand = new HashMap<>();
        List<TxnInsertRow> txnRows = new ArrayList<>(toWrite.size());
        for (InitialImportRow row : toWrite) {
            BalanceKey key = new BalanceKey(row.warehouseId(), row.skuId());
            InventoryBalanceEntity balance = balances.get(key);
            if (balance == null) {
                // 第 2 步刚建过行：仍读不到只能是租户线异常，绝不静默跳过
                throw new BizException(ErrorCodes.INV_5004, "未找到对应库存记录");
            }
            BigDecimal before = runningOnHand.computeIfAbsent(key, k -> balance.getOnHandQty());
            BigDecimal delta = QtyUtils.of(row.qty());
            BigDecimal after = QtyUtils.add(before, delta);
            runningOnHand.put(key, after);
            txnRows.add(new TxnInsertRow(teamId, row.warehouseId(), row.skuId(),
                    InventoryTxnType.INIT_IMPORT.name(), delta, before, after,
                    InventoryBizType.IMPORT.name(), bizRefId, row.idempotencyKey(), operatorId,
                    occurredAt, operatorId));
        }

        // ---- 6) 分片多值 INSERT 流水 ----
        for (int from = 0; from < txnRows.size(); from += CHUNK) {
            transactionMapper.insertBatch(
                    txnRows.subList(from, Math.min(from + CHUNK, txnRows.size())));
        }

        // ---- 7) 查回流水 ID（回填 last_txn_id 用；不用 LAST_INSERT_ID 推断，并发会错位）----
        Map<String, Long> txnIdByKey = new HashMap<>();
        List<String> writeKeys = new ArrayList<>(toWrite.size());
        for (InitialImportRow row : toWrite) {
            writeKeys.add(row.idempotencyKey());
        }
        for (int from = 0; from < writeKeys.size(); from += CHUNK) {
            for (Map<String, Object> row : transactionMapper.selectIdsByKeys(
                    writeKeys.subList(from, Math.min(from + CHUNK, writeKeys.size())))) {
                Object id = row.get("id");
                Object key = row.get("idempotency_key");
                if (id instanceof Number number && key instanceof String k) {
                    txnIdByKey.put(k, number.longValue());
                }
            }
        }

        // ---- 8) 分片增量更新余额 ----
        Map<BalanceKey, BalanceDelta> deltaByBalanceId = new LinkedHashMap<>();
        for (TxnInsertRow txn : txnRows) {
            BalanceKey key = new BalanceKey(txn.warehouseId(), txn.skuId());
            InventoryBalanceEntity balance = balances.get(key);
            Long txnId = txnIdByKey.get(txn.idempotencyKey());
            if (txnId == null) {
                // 刚插入的流水查不回 ID ⇒ 上游插入没生效或租户线异常，绝不写半截状态
                throw new BizException(ErrorCodes.INV_5002, "库存流水写入后未能读回，已回滚");
            }
            deltaByBalanceId.put(key, new BalanceDelta(balance.getId(), txn.qtyDelta(), txnId));
        }
        List<BalanceDelta> deltas = new ArrayList<>(deltaByBalanceId.values());
        int updated = 0;
        for (int from = 0; from < deltas.size(); from += CHUNK) {
            updated += balanceMapper.addOnHandByIds(
                    deltas.subList(from, Math.min(from + CHUNK, deltas.size())), occurredAt, operatorId);
        }
        if (updated != deltas.size()) {
            throw new BizException(ErrorCodes.INV_5002, "库存余额更新行数与预期不符，已回滚");
        }
        return new InitialImportBatchResult(toWrite.size(), replayed);
    }

    /**
     * 批量重放时的内容校验（{@code R75} 口径：比对不含 {@code reason}）。
     *
     * <p>与单行版 {@link #replayIfIdempotent} 的判定字段一字不差：
     * {@code txn_type=INIT_IMPORT} + {@code biz_type=IMPORT} + {@code biz_ref_id} 一致 +
     * {@code sku_id}/{@code warehouse_id} 一致 + {@code qty_delta} 一致。
     * （{@code sku_id}/{@code warehouse_id} 由幂等键本身隐含——键构造式含这两段，
     * 同键必然同 SKU 同仓库，故无需再比。）
     *
     * <p>★ {@code qty_delta} 用 {@link BigDecimal#compareTo} 而非 {@code equals}：
     * 数量列 {@code DECIMAL(18,3)}，{@code 6.000} 与 {@code 6.0} 数值相等而标度不同，
     * {@code equals} 会把合法重放误判成内容不一致。
     */
    private void assertSameReplayContent(InventoryTransactionEntity existing, long teamId, Long bizRefId,
                                         BigDecimal expectedQty) {
        boolean sameTeam = existing.getTeamId() == teamId;
        boolean sameType = InventoryTxnType.INIT_IMPORT.name().equals(existing.getTxnType())
                && InventoryBizType.IMPORT.name().equals(existing.getBizType());
        boolean sameRef = Objects.equals(existing.getBizRefId(), bizRefId);
        boolean sameQty = expectedQty != null
                && QtyUtils.of(expectedQty).compareTo(existing.getQtyDelta()) == 0;
        if (!(sameTeam && sameType && sameRef && sameQty)) {
            throw new BizException(ErrorCodes.INV_5003, "该库存操作已提交，但内容不一致，请勿重复提交");
        }
    }

    /** 批量初始导入的一行载荷。 */
    public record InitialImportRow(long skuId, long warehouseId, BigDecimal qty, String idempotencyKey) {
    }

    /** 批量初始导入的结果：{@code written} 实际写入行数，{@code replayed} 判为重放（含同批同键）的行数。 */
    public record InitialImportBatchResult(int written, int replayed) {
    }

    /**
     * 采购到货入库（{@code §5.3}；业务触发方是 {@code purchasing}，方向不可反）。
     *
     * <p>{@code txn_type = PURCHASE_INBOUND}；余额行不存在时自动建行（首期允许「先到货后建账」）。
     *
     * @param bizType        业务来源（须为 {@link InventoryBizType} 成员，到货通常为 {@code GOODS_RECEIPT}）
     * @param bizRefId       业务单据 ID（到货单 ID）
     * @param idempotencyKey 幂等键（{@code §5.3}：{@code <txn_type>:<biz_type>:<biz_ref_id>:<sku_id>[:<suffix>]}；
     *                       {@code PURCHASE_INBOUND} 的后缀 {@code :<purchaseOrderItemId>} 必填，见 {@code R180}）
     * @throws BizException {@code INV-5003} / {@code INV-5002}
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public StockChangeResult applyInbound(long skuId, long warehouseId, BigDecimal qty, String bizType,
                                          Long bizRefId, String idempotencyKey) {
        requirePositiveQuantity(qty, "qty");
        InventoryBizType parsedBizType = InventoryBizType.parse(bizType);
        String key = requireIdempotencyKey(idempotencyKey);
        return runWithRetry(() -> attemptInflow(skuId, warehouseId, InventoryTxnType.PURCHASE_INBOUND,
                parsedBizType, qty, bizRefId, null, key));
    }

    /**
     * 销售出库（{@code §5.4}；首期预留，仅内部 / 测试路径）。
     *
     * <p>{@code txn_type = SALE_OUTBOUND}、{@code qty_delta = -qty}；余额不足 → {@code INV-5001}（409），
     * 与调整 / 冲销的 {@code INV-5006} <b>严格分开</b>（{@code ADR-005 §2.2}）。
     * 余额行不存在 → {@code INV-5004}（{@code W10}）。
     *
     * @throws BizException {@code INV-5001} / {@code INV-5004} / {@code INV-5003} / {@code INV-5002}
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public StockChangeResult applyOutbound(long skuId, long warehouseId, BigDecimal qty, String bizType,
                                           Long bizRefId, String idempotencyKey) {
        requirePositiveQuantity(qty, "qty");
        InventoryBizType parsedBizType = InventoryBizType.parse(bizType);
        String key = requireIdempotencyKey(idempotencyKey);
        return runWithRetry(() -> attemptOutbound(skuId, warehouseId, parsedBizType, qty, bizRefId, key));
    }

    /**
     * 盘点调整（{@code §5.5}）：写入<b>目标值</b>，由本方法换算差额。
     *
     * <p>差额为 0 → 按 {@code §5.5} 步骤 G「<b>不写流水</b>，直接返回成功」，
     * 结果为 {@code idempotentHit = true} 且 {@code txnId = null}。
     * 差额由 {@code targetQty − on_hand_qty} 得出，故不会为「非法 0」。
     *
     * <p>★ {@code reason} 参数是 {@code R55} 的裁定结果（{@code §2.5} 原签名缺 {@code reason}，
     * 与 {@code §5.5}「{@code reason} 必填 + {@code INV-5011}」冲突，以功能需求为准）。
     *
     * @throws BizException {@code INV-5004} / {@code INV-5005} / {@code INV-5006} / {@code INV-5011}
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public StockChangeResult applyCountAdjust(long skuId, long warehouseId, BigDecimal targetQty,
                                              String reason, Long bizRefId, String idempotencyKey) {
        requireAdjustQuantity(targetQty, "target_qty");
        requireReason(reason);
        String key = requireIdempotencyKey(idempotencyKey);
        // ★ 审计必须在 runWithRetry 之外（见类注释「审计埋点」第 2 条）
        StockChangeResult result = runWithRetry(
                () -> attemptCountAdjust(skuId, warehouseId, targetQty, reason, bizRefId, key));
        recordAdjustAudit(AuditAction.INVENTORY_COUNT_ADJUST, InventoryTxnType.COUNT_ADJUST.name(),
                skuId, warehouseId, reason, result);
        return result;
    }

    /**
     * 手工调整（{@code §5.5}）：直接写带符号增量。
     *
     * <p>{@code qtyDelta = 0} → {@code INV-5005}（400，请求不合法）；调整后为负 → {@code INV-5006}（409）。
     * 角色校验（{@code INV-5007}）在 API 层（{@code InventoryController}）；审计在本方法
     * {@code runWithRetry} <b>之外</b>的最终成功点写入（{@code #11}，{@code B9}）。
     *
     * @throws BizException {@code INV-5004} / {@code INV-5005} / {@code INV-5006} / {@code INV-5011}
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public StockChangeResult applyManualAdjust(long skuId, long warehouseId, BigDecimal qtyDelta,
                                               String reason, String idempotencyKey) {
        requireAdjustQuantity(qtyDelta, "qty_delta");
        if (QtyUtils.of(qtyDelta).signum() == 0) {
            throw new BizException(ErrorCodes.INV_5005, "调整数量不能为 0");
        }
        requireReason(reason);
        String key = requireIdempotencyKey(idempotencyKey);
        StockChangeResult result = runWithRetry(
                () -> attemptManualAdjust(skuId, warehouseId, qtyDelta, reason, key));
        recordAdjustAudit(AuditAction.INVENTORY_MANUAL_ADJUST, InventoryTxnType.MANUAL_ADJUST.name(),
                skuId, warehouseId, reason, result);
        return result;
    }

    /**
     * 冲销（{@code §5.6}）：写一条反向流水，原流水<b>保持不动</b>。
     *
     * <p>新流水：{@code txn_type = biz_type = REVERSAL}、{@code qty_delta = -原 qty_delta}、
     * {@code reversed_txn_id = biz_ref_id = 原流水 ID}。
     * 一笔流水只能冲销一次（业务侧前置校验 + {@code uk_invtxn_team_reversed} 兜底）。
     *
     * @throws BizException {@code INV-5008}（不存在 / 异团队）、{@code INV-5010}（不可冲销）、
     *                      {@code INV-5009}（已被冲销）、{@code INV-5006}（冲销后为负）、
     *                      {@code INV-5004}（余额行缺失）、{@code INV-5003} / {@code INV-5002}
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public StockChangeResult applyReversal(long txnId, String reason, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        // 冲销需要原流水的 sku/warehouse 才能拼出可追溯摘要，故让尝试单元带回上下文（不改对外返回类型）
        ReversalOutcome outcome = runWithRetry(() -> attemptReversal(txnId, reason, key));
        recordReversalAudit(txnId, reason, outcome);
        return outcome.result();
    }

    // =====================================================================
    // 同口径锚点行锁（F-03，供 purchasing）
    // =====================================================================

    /**
     * 对 {@code (team_id, warehouse_id, skuIds)} 的余额行加<b>排他行锁</b>并返回锁定情况
     * （{@code MODULE_BOUNDARIES §2.5}「同口径锚点行锁」/ {@code DATABASE_DESIGN §7.2} #8 ②）。
     *
     * <p>★ 传入的 {@code teamId} <b>只用于一致性校验</b>（{@code W13}/{@code W14}）：真正的租户条件一律
     * 取自 {@link TeamContext}，与之不一致即抛 {@code TEAM-2001}，<b>绝不静默返回空列表</b>
     * （静默返回会让调用方误以为「无并发冲突」而继续写入，是最危险的一类失败）。
     *
     * <p>★ 加锁顺序：入参去重后<b>再按 {@code sku_id} 升序</b>排序（防御性，不依赖调用方已排序），
     * 保证进程内所有调用方以同一全局顺序取锁，避免死锁。
     *
     * <p>{@code FOR UPDATE} 的锁持有到<b>外层事务</b>结束（本方法 {@code REQUIRED} 加入调用方事务），
     * 这正是「锚点锁」的目的：{@code purchasing} 在创建采购单期间串行化同 SKU×仓库的口径。
     *
     * @param teamId          调用方认为的当前团队（仅校验）
     * @param warehouseId     仓库
     * @param skuIdsAscending SKU 集合（按升序传入；本方法仍会去重排序）
     * @return {@code lockedSkuIds}（真实锁到的行）与 {@code missingSkuIds}（无余额行、无行可锁）
     * @throws BizException {@code TEAM-2001}（{@code teamId} 与 {@link TeamContext} 不一致）
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public AnchorLockResult lockBalanceAnchors(long teamId, long warehouseId, List<Long> skuIdsAscending) {
        long currentTeamId = currentTeamId();
        if (teamId != currentTeamId) {
            throw new BizException(ErrorCodes.TEAM_2001, "跨团队越权");
        }
        if (skuIdsAscending == null || skuIdsAscending.isEmpty()) {
            return new AnchorLockResult(List.of(), List.of());
        }
        List<Long> ordered = skuIdsAscending.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (ordered.isEmpty()) {
            return new AnchorLockResult(List.of(), List.of());
        }
        List<Long> locked = balanceMapper.lockAnchorSkuIds(warehouseId, ordered);
        Set<Long> lockedSet = new HashSet<>(locked);
        List<Long> missing = ordered.stream().filter(skuId -> !lockedSet.contains(skuId)).toList();
        return new AnchorLockResult(List.copyOf(locked), missing);
    }

    // =====================================================================
    // 单次尝试（在 NESTED 事务单元内执行）
    // =====================================================================

    /** 入库 / 初始导入：余额行不存在时自动建行。 */
    private StockChangeResult attemptInflow(long skuId, long warehouseId, InventoryTxnType txnType,
                                            InventoryBizType bizType, BigDecimal qty, Long bizRefId,
                                            String reason, String key) {
        long teamId = currentTeamId();
        Long operatorId = currentOperatorId();
        // W5：并发首次建行由 uk_invbal_team_wh_sku + ON DUPLICATE KEY UPDATE 吸收，不用异常控制流
        balanceMapper.insertIfAbsent(teamId, warehouseId, skuId, operatorId);
        BalanceRow balance = requireBalance(warehouseId, skuId);
        BigDecimal delta = QtyUtils.of(qty);
        StockChangeResult replay = replayIfIdempotent(txnType, bizType, bizRefId, skuId, warehouseId,
                delta, false, key, balance);
        if (replay != null) {
            return replay;
        }
        return writeChange(teamId, balance, txnType, bizType, delta, bizRefId, null, reason, key,
                operatorId, ErrorCodes.INV_5006);
    }

    /** 出库：余额行不存在 → {@code INV-5004}（W10）。 */
    private StockChangeResult attemptOutbound(long skuId, long warehouseId, InventoryBizType bizType,
                                              BigDecimal qty, Long bizRefId, String key) {
        long teamId = currentTeamId();
        Long operatorId = currentOperatorId();
        BalanceRow balance = requireBalance(warehouseId, skuId);
        BigDecimal delta = QtyUtils.of(qty).negate();
        StockChangeResult replay = replayIfIdempotent(InventoryTxnType.SALE_OUTBOUND, bizType, bizRefId,
                skuId, warehouseId, delta, false, key, balance);
        if (replay != null) {
            return replay;
        }
        return writeChange(teamId, balance, InventoryTxnType.SALE_OUTBOUND, bizType, delta, bizRefId, null,
                null, key, operatorId, ErrorCodes.INV_5001);
    }

    /** 盘点调整：目标值 → 差额；差额 0 → 不写流水直接成功（§5.5 步骤 G）。 */
    private StockChangeResult attemptCountAdjust(long skuId, long warehouseId, BigDecimal targetQty,
                                                 String reason, Long bizRefId, String key) {
        long teamId = currentTeamId();
        Long operatorId = currentOperatorId();
        BalanceRow balance = requireBalance(warehouseId, skuId);
        // ★ 目标值口径的幂等判定比较「存量流水的 qty_after 是否等于目标值」：
        //   目标值本身不落库，qty_delta 是「当时的差额」，重放时用当前余额重算会得到 0，不能用于比较。
        StockChangeResult replay = replayIfIdempotent(InventoryTxnType.COUNT_ADJUST, InventoryBizType.COUNT,
                bizRefId, skuId, warehouseId, targetQty, true, key, balance);
        if (replay != null) {
            return replay;
        }
        BigDecimal delta = QtyUtils.subtract(targetQty, balance.onHandQty());
        if (delta.signum() == 0) {
            // ★ M8-T04（ADR-008 / R76）：差额 0 时【登记幂等键占位】再返回。
            //   §5.5 步骤 G 的「不写流水」保持不变（流水表结构上也无法记 0 变化，
            //   ck_invtxn_delta_non_zero 会拒）；但幂等键必须有载体——否则同 key 重试
            //   在余额被外部改动后会「真落账」，把余额拉回盘点目标值（R76 实测）。
            //   数量用 targetQty（与上面 replayIfIdempotent 传入的 targetBased 值同源）
            recordNoChangeIdempotency(InventoryTxnType.COUNT_ADJUST, InventoryBizType.COUNT, bizRefId,
                    balance, targetQty, key);
            return new StockChangeResult(null, InventoryTxnType.COUNT_ADJUST.name(),
                    InventoryBizType.COUNT.name(), BigDecimal.ZERO.setScale(QtyUtils.SCALE),
                    balance.onHandQty(), balance.onHandQty(), balance.version(),
                    LocalDateTime.now(ZoneOffset.UTC), true);
        }
        return writeChange(teamId, balance, InventoryTxnType.COUNT_ADJUST, InventoryBizType.COUNT, delta,
                bizRefId, null, reason, key, operatorId, ErrorCodes.INV_5006);
    }

    /** 手工调整：直接写带符号增量。 */
    private StockChangeResult attemptManualAdjust(long skuId, long warehouseId, BigDecimal qtyDelta,
                                                  String reason, String key) {
        long teamId = currentTeamId();
        Long operatorId = currentOperatorId();
        BalanceRow balance = requireBalance(warehouseId, skuId);
        BigDecimal delta = QtyUtils.of(qtyDelta);
        StockChangeResult replay = replayIfIdempotent(InventoryTxnType.MANUAL_ADJUST, InventoryBizType.MANUAL,
                null, skuId, warehouseId, delta, false, key, balance);
        if (replay != null) {
            return replay;
        }
        return writeChange(teamId, balance, InventoryTxnType.MANUAL_ADJUST, InventoryBizType.MANUAL, delta,
                null, null, reason, key, operatorId, ErrorCodes.INV_5006);
    }

    /**
     * 冲销的死状态校验后再写反向流水。
     *
     * <p>★ <b>校验顺序（{@code R73} 裁定，{@code M3-B9.5}）</b>：
     * <ol>
     *   <li>{@code 不存在} → {@code INV-5008}（404）</li>
     *   <li>{@code 本身是 REVERSAL} → {@code INV-5010}（422）—— 与幂等无关，任何情况下都不可冲销</li>
     *   <li>{@code requireBalance}（{@code replayIfIdempotent} 需要余额快照）</li>
     *   <li><b>{@code replayIfIdempotent}</b> —— 同幂等键命中：内容一致 → 返回首次结果；
     *       内容不一致 → {@code INV-5003}</li>
     *   <li>{@code 已被冲销} → {@code INV-5009}（409）</li>
     *   <li>写反向流水</li>
     * </ol>
     *
     * <p>★ 为什么幂等重放必须排在「已被冲销」之前：{@code API_CONTRACT §6.3} 规定
     * 「同幂等键 + 请求体一致 ⇒ 返回首次结果，<b>不重复执行</b>」。首次冲销成功后，该笔流水当然
     * 处于「已被冲销」状态；若先判 {@code INV-5009}，客户端因超时重试同一请求就会拿到 409，
     * 把一次成功的操作报成失败（{@code R73} 的实证：旧顺序下重放得 409 且无 {@code transaction_id}）。
     * 顺序调换后：<b>同键 → 重放 200</b>；<b>不同键 → 仍 409 {@code INV-5009}</b>（回归点）。
     */
    private ReversalOutcome attemptReversal(long originalTxnId, String reason, String key) {
        long teamId = currentTeamId();
        Long operatorId = currentOperatorId();
        // 租户线自动追加 team_id：异团队流水查不到 → 按「不存在」处理（§5.6 步骤 C，INV-5008）
        InventoryTransactionEntity original = transactionMapper.selectById(originalTxnId);
        if (original == null) {
            throw new BizException(ErrorCodes.INV_5008, "库存流水不存在");
        }
        if (InventoryTxnType.REVERSAL.name().equals(original.getTxnType())) {
            throw new BizException(ErrorCodes.INV_5010, "该笔流水不可冲销");
        }
        // 幂等重放需要余额快照（返回首次结果时带 balance.version），故 requireBalance 随之前移
        BalanceRow balance = requireBalance(original.getWarehouseId(), original.getSkuId());
        BigDecimal delta = original.getQtyDelta().negate();
        // ★ R73：先判重放，再判「已被冲销」——否则同键重试会被 409 误杀
        StockChangeResult replay = replayIfIdempotent(InventoryTxnType.REVERSAL, InventoryBizType.REVERSAL,
                originalTxnId, original.getSkuId(), original.getWarehouseId(), delta, false, key, balance);
        if (replay != null) {
            return new ReversalOutcome(replay, original.getSkuId(), original.getWarehouseId());
        }
        if (transactionMapper.countByReversedTxnId(originalTxnId) > 0) {
            throw new BizException(ErrorCodes.INV_5009, "该笔流水已被冲销");
        }
        StockChangeResult result = writeChange(teamId, balance, InventoryTxnType.REVERSAL,
                InventoryBizType.REVERSAL, delta, originalTxnId, originalTxnId, reason, key, operatorId,
                ErrorCodes.INV_5006);
        // ★ R185（M8-T05）：冲销 {@code PURCHASE_INBOUND} 必须同事务回退采购侧 {@code received_qty}，
        //   否则派生在途（{@code ordered_qty − received_qty}）会少计（{@code PURCHASING_WORKFLOW §8.3}）。
        //   本调用 {@code REQUIRED} 加入当前 NESTED 单元（{@code runWithRetry} 的 SAVEPOINT），
        //   随冲销写流水一同成功/回滚（原子）。★ 重放路径（{@code replay != null}）不会到达这里
        //   ⇒ 幂等重放不重复回退（{@code R73} 同键同体 ⇒ 返回首次结果，不重复执行）。
        if (InventoryTxnType.PURCHASE_INBOUND.name().equals(original.getTxnType())) {
            purchaseInboundReversalPort.reversePurchaseInbound(
                    original.getBizRefId() == null ? 0L : original.getBizRefId(),
                    original.getIdempotencyKey(),
                    original.getQtyDelta());
        }
        return new ReversalOutcome(result, original.getSkuId(), original.getWarehouseId());
    }

    /**
     * 写流水 → 推进余额（{@code §6-C.2} 步骤 4–6）。
     *
     * <p>乐观锁失败（影响行数 0）抛 {@link OptimisticConflict}，由 {@link #runWithRetry} 回滚到本次尝试
     * 的 SAVEPOINT 后重试——<b>这一步同时抹掉了本次尝试插入的流水</b>，从而不留孤儿流水。
     */
    private StockChangeResult writeChange(long teamId, BalanceRow balance, InventoryTxnType txnType,
                                          InventoryBizType bizType, BigDecimal delta, Long bizRefId,
                                          Long reversedTxnId, String reason, String key, Long operatorId,
                                          String negativeErrorCode) {
        BigDecimal qtyBefore = balance.onHandQty();
        BigDecimal qtyAfter = QtyUtils.add(qtyBefore, delta);
        if (qtyAfter.signum() < 0) {
            throw new BizException(negativeErrorCode, ErrorCodes.INV_5001.equals(negativeErrorCode)
                    ? "库存不足，无法完成出库"
                    : "调整后库存不能小于 0");
        }
        LocalDateTime occurredAt = LocalDateTime.now(ZoneOffset.UTC);

        InventoryTransactionEntity txn = new InventoryTransactionEntity();
        txn.setTeamId(teamId);
        txn.setWarehouseId(balance.warehouseId());
        txn.setSkuId(balance.skuId());
        txn.setTxnType(txnType.name());
        txn.setQtyDelta(delta);
        txn.setQtyBefore(qtyBefore);
        txn.setQtyAfter(qtyAfter);
        txn.setBizType(bizType.name());
        txn.setBizRefId(bizRefId);
        txn.setIdempotencyKey(key);
        txn.setReason(reason);
        txn.setReversedTxnId(reversedTxnId);
        txn.setOperatorId(operatorId);
        txn.setOccurredAt(occurredAt);
        txn.setCreatedBy(operatorId);

        try {
            transactionMapper.insert(txn);
        } catch (DuplicateKeyException ex) {
            // 只有「两个请求同时通过上面的预检」才会落到这里（真实竞态，非正常幂等路径）。
            // 内容判定仍走 SELECT，不靠异常反推；另一条唯一键（uk_invtxn_team_reversed）冲突 → INV-5009。
            log.debug("库存流水唯一键冲突，按竞态重判：txnType={}, key={}", txnType, key);
            StockChangeResult replay = replayIfIdempotent(txnType, bizType, bizRefId, balance.skuId(),
                    balance.warehouseId(), delta, false, key, balance);
            if (replay != null) {
                return replay;
            }
            if (reversedTxnId != null) {
                throw new BizException(ErrorCodes.INV_5009, "该笔流水已被冲销");
            }
            throw new BizException(ErrorCodes.INV_5003, "该库存操作已提交，请勿重复提交");
        }

        int updated = balanceUpdater.apply(teamId, balance.id(), balance.version(), qtyAfter, txn.getId(),
                occurredAt, operatorId);
        if (updated != 1) {
            throw new OptimisticConflict();
        }
        return new StockChangeResult(txn.getId(), txnType.name(), bizType.name(), delta, qtyBefore, qtyAfter,
                balance.version() + 1, occurredAt, false);
    }

    /**
     * 幂等预检（{@code §8.3}）：同团队内命中 {@code uk_invtxn_team_idem} 时——
     * 内容一致 → 返回既有结果（{@code idempotentHit = true}）；内容不一致 → {@code INV-5003}。
     *
     * @return {@code null} 表示未命中，可继续写入
     */
    private StockChangeResult replayIfIdempotent(InventoryTxnType txnType, InventoryBizType bizType,
                                                 Long bizRefId, Long skuId, Long warehouseId,
                                                 BigDecimal deltaOrTarget, boolean targetBased,
                                                 String key, BalanceRow balance) {
        InventoryTransactionEntity existing = transactionMapper.selectByIdempotencyKey(key);
        if (existing != null) {
            return assertSameContentAndReplay(existing, txnType, bizType, bizRefId, skuId, warehouseId,
                    deltaOrTarget, targetBased, key, balance);
        }
        // ★ M8-T04 第 ② 步（ADR-008）：查流水没命中 ⇒ 可能是「差额 0 已占位」。
        //   这【只新增一种可能命中】，不改变任何既有分支：未命中则照旧返回 null 继续写。
        InventoryIdempotencyKeyEntity placeholder = idempotencyKeyMapper.selectByKey(key);
        if (placeholder == null) {
            return null;
        }
        if (!contentHashOf(txnType, bizType, bizRefId, skuId, warehouseId, deltaOrTarget)
                .equals(placeholder.getContentHash())) {
            // 同 key 不同内容（含【数量不同】）⇒ 与流水侧的 INV-5003 同口径
            throw new BizException(ErrorCodes.INV_5003, "该库存操作已提交，请勿重复提交");
        }
        log.debug("幂等键命中无变更占位（ADR-008）：key={}", key);
        return new StockChangeResult(null, txnType.name(), bizType.name(),
                BigDecimal.ZERO.setScale(QtyUtils.SCALE), balance == null ? null : balance.onHandQty(),
                balance == null ? null : balance.onHandQty(),
                balance == null ? null : balance.version(), LocalDateTime.now(ZoneOffset.UTC), true);
    }

    /**
     * 登记「差额 0」的幂等键占位（M8-T04 / ADR-008 §4）。
     *
     * <p><b>不写流水</b>（§5.5 步骤 G 不变），只在 {@code inventory_idempotency_keys} 留一行。
     * 与流水表<b>互斥</b>：差额 ≠ 0 的路径根本不调本方法。
     *
     * <p>并发：两个请求同时发现差额 0 ⇒ 双方都 INSERT ⇒
     * {@code uk_invidem_team_key} 让其一失败。其一捕获后<b>按重放处理</b>（返回成功），
     * 而不是把「并发重复」暴露成错误——这与流水侧 {@code writeChange} 的
     * {@code DuplicateKeyException} → 重放 是同一口径。
     */
    private void recordNoChangeIdempotency(InventoryTxnType txnType, InventoryBizType bizType,
                                           Long bizRefId, BalanceRow balance,
                                           BigDecimal quantity, String key) {
        InventoryIdempotencyKeyEntity row = new InventoryIdempotencyKeyEntity();
        row.setTeamId(currentTeamId());
        row.setIdempotencyKey(key);
        row.setTxnType(txnType.name());
        row.setBizType(bizType.name());
        row.setBizRefId(bizRefId);
        row.setSkuId(balance.skuId());
        row.setWarehouseId(balance.warehouseId());
        row.setContentHash(contentHashOf(txnType, bizType, bizRefId, balance.skuId(),
                balance.warehouseId(), quantity));
        row.setResultKind("NO_CHANGE");
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setCreatedBy(currentOperatorId());
        try {
            idempotencyKeyMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            log.debug("幂等键占位并发冲突，按重放处理：key={}", key);
        }
    }

    /**
     * 内容哈希（M8-T04 / ADR-008 §5.3）：{@code sha256(txn_type|biz_type|biz_ref_id|sku|wh|qty)}。
     *
     * <p>★ 必含<b>数量</b>：否则「同 key 换 target」会被误判为同内容而当成纯重放
     * ——那等于把 {@code R76} 的危害从「静默落账」换成「静默忽略」，问题没解决。
     *
     * <p>★ 不含 {@code reason}（{@code R75} 口径）：{@code reason} 相同与否不影响库存效果。
     *
     * <p>字段用 {@code |} 分隔且各字段先做 {@code null → ""} 归一，避免
     * 「{@code null} 与空串拼接后相同」造成的哈希碰撞。
     */
    private static String contentHashOf(InventoryTxnType txnType, InventoryBizType bizType,
                                        Long bizRefId, Long skuId, Long warehouseId,
                                        BigDecimal quantity) {
        String raw = txnType.name() + "|" + bizType.name() + "|" + (bizRefId == null ? "" : bizRefId)
                + "|" + (skuId == null ? "" : skuId) + "|" + (warehouseId == null ? "" : warehouseId)
                + "|" + (quantity == null ? "" : QtyUtils.of(quantity).toPlainString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法；真缺了说明运行环境异常，宁可炸也不能悄悄放行幂等
            throw new BizException(ErrorCodes.COM_0002, "系统繁忙，请稍后重试");
        }
    }

    /** 流水侧命中的既有内容判定（ADR-008 后从 {@code replayIfIdempotent} 原样抽出）。 */
    private StockChangeResult assertSameContentAndReplay(InventoryTransactionEntity existing,
                                                         InventoryTxnType txnType, InventoryBizType bizType,
                                                         Long bizRefId, Long skuId, Long warehouseId,
                                                         BigDecimal deltaOrTarget, boolean targetBased,
                                                         String key, BalanceRow balance) {
        boolean sameContent = txnType.name().equals(existing.getTxnType())
                && bizType.name().equals(existing.getBizType())
                && Objects.equals(existing.getBizRefId(), bizRefId)
                && Objects.equals(existing.getSkuId(), skuId)
                && Objects.equals(existing.getWarehouseId(), warehouseId)
                && matchesQuantity(existing, deltaOrTarget, targetBased);
        if (!sameContent) {
            throw new BizException(ErrorCodes.INV_5003, "该库存操作已提交，请勿重复提交");
        }
        return new StockChangeResult(existing.getId(), existing.getTxnType(), existing.getBizType(),
                existing.getQtyDelta(), existing.getQtyBefore(), existing.getQtyAfter(),
                balance == null ? null : balance.version(), existing.getOccurredAt(), true);
    }

    /** 内容比对：目标值口径比 {@code qty_after}，增量口径比 {@code qty_delta}（{@code compareTo} 忽略标度）。 */
    private static boolean matchesQuantity(InventoryTransactionEntity existing, BigDecimal deltaOrTarget,
                                           boolean targetBased) {
        if (deltaOrTarget == null) {
            return false;
        }
        BigDecimal stored = targetBased ? existing.getQtyAfter() : existing.getQtyDelta();
        return stored != null && stored.compareTo(deltaOrTarget) == 0;
    }

    // =====================================================================
    // 重试骨架
    // =====================================================================

    /**
     * 以「一次尝试 = 一个 {@code NESTED} 事务单元（SAVEPOINT）」的方式执行写操作。
     *
     * <p>只对 {@link OptimisticConflict} 重试；其余异常（含业务异常、数据库约束错误）立即上抛，
     * 由外层事务整体回滚——<b>不把「参数错」「库存不足」这类确定性失败当冲突重试</b>。
     *
     * <p>返回类型泛型化仅为让冲销的尝试单元带回 {@code sku/warehouse}（拼审计摘要用），
     * 语义与重试骨架无关；其余调用方仍是 {@code StockChangeResult}。
     */
    private <T> T runWithRetry(Supplier<T> attempt) {
        for (int attemptNo = 1; attemptNo <= MAX_ATTEMPTS; attemptNo++) {
            try {
                return attemptTemplate.execute(status -> attempt.get());
            } catch (OptimisticConflict conflict) {
                if (attemptNo >= MAX_ATTEMPTS) {
                    log.warn("库存乐观锁连续 {} 次冲突，放弃并返回 INV-5002", MAX_ATTEMPTS);
                    break;
                }
                long backoff = RETRY_BACKOFF_MILLIS[attemptNo - 1];
                log.debug("库存乐观锁冲突（第 {} 次尝试），{} ms 后重试", attemptNo, backoff);
                sleep(backoff);
            }
        }
        throw new BizException(ErrorCodes.INV_5002, "库存数据已被同时更新，请刷新后重试");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCodes.COM_0002, "库存写入重试被中断", ex);
        }
    }

    // =====================================================================
    // 审计埋点（M3-T17 / B9）
    // =====================================================================

    /**
     * 调整类审计（{@code #11 MANUAL_ADJUST} / {@code #12 COUNT_ADJUST}）：{@code biz_id} = 本次新增流水 id。
     *
     * <p>两类「无变更」的成功一律不写：幂等重放（{@code idempotentHit}）与盘点差额为 0
     * （{@code txnId == null}，{@code §5.5} 步骤 G 不写流水）——详见类注释「审计埋点」第 3 条。
     */
    private void recordAdjustAudit(AuditAction action, String txnTypeLabel, long skuId, long warehouseId,
                                   String reason, StockChangeResult result) {
        if (result == null || result.idempotentHit() || result.txnId() == null) {
            return;
        }
        String before = qtyText(result.qtyBefore());
        String after = qtyText(result.qtyAfter());
        String summary = "数量调整 sku=" + skuId + " 仓库=" + warehouseId + " " + before + " → " + after
                + "（" + txnTypeLabel + "，原因：" + reason + "，流水 " + result.txnId() + "）";
        recordAudit(action, "INVENTORY_BALANCE", result.txnId(), summary, before, after);
    }

    /**
     * 冲销审计（{@code #13 INVENTORY_REVERSE}）：{@code biz_id} = <b>被冲销的原流水 id</b>
     * （{@code DATABASE_DESIGN §6-J.1 #13} 的可追溯键），新生成的 {@code REVERSAL} 流水 id 写进摘要。
     */
    private void recordReversalAudit(long originalTxnId, String reason, ReversalOutcome outcome) {
        if (outcome == null || outcome.result() == null || outcome.result().idempotentHit()) {
            return;
        }
        StockChangeResult result = outcome.result();
        String before = qtyText(result.qtyBefore());
        String after = qtyText(result.qtyAfter());
        String summary = "冲销流水 " + originalTxnId + " → 新流水 " + result.txnId()
                + "，sku=" + outcome.skuId() + " 仓库=" + outcome.warehouseId()
                + " " + before + " → " + after + "（原因：" + reason + "）";
        recordAudit(AuditAction.INVENTORY_REVERSE, "INVENTORY_TRANSACTION", originalTxnId, summary, before, after);
    }

    /**
     * 统一写审计 —— <b>与业务同事务</b>（{@code AuditWriteService.recordInBusinessTx}，
     * {@code MODULE_BOUNDARIES §2.10} 高风险操作，{@code M3-B9.5}）。
     *
     * <p>★ 为什么是「同事务」而不是 {@code record}（{@code REQUIRES_NEW} 解耦）：
     * 本类三条动作（{@code #11}/{@code #12}/{@code #13}）都在 {@code @Transactional(REQUIRED)} 内，
     * 若走 {@code REQUIRES_NEW}，审计会<b>先于业务提交</b>独立落库 ——
     * 一旦业务随后回滚（或本事务在提交时失败），就会出现「审计说调了、余额没动」的谎报，
     * 而库存调整<b>无法用业务数据反推</b>是否发生过，这条痕迹是唯一证据。
     * {@code §2.10} 与 {@code PURCHASING_WORKFLOW:263} 明文要求同事务（回滚时一并撤销）。
     *
     * <p>★ 失败即回滚：{@code recordInBusinessTx} <b>不</b>做异常兜底，
     * 审计写不进 ⇒ 异常冒泡 ⇒ 整个库存变更回滚。这是 §2.10 的取舍：
     * <b>宁可业务失败，不可留痕缺失</b>。
     *
     * <p>{@code team_id} / 操作人一律取自 {@link TeamContext}；缺团队时<b>不写</b>并记 WARN
     * （{@code REST} 路径必有上下文，静默会掩盖未来批处理路径的缺陷）。
     */
    private void recordAudit(AuditAction action, String bizType, Long bizId, String summary,
                             String before, String after) {
        TeamContext.Snapshot snap = TeamContext.get();
        if (snap == null || snap.teamId() == null) {
            // 无团队上下文则不写审计（避免造无主审计行）；但必须留痕——
            // REST 路径走到这里说明过滤链/上下文注入出了故障，静默返回会把缺陷藏起来
            log.warn("缺少 TeamContext，跳过审计写入（禁止造无主审计行）：action={} bizType={} bizId={}",
                    action, bizType, bizId);
            return;
        }
        auditWriteService.recordInBusinessTx(AuditEvent.builder()
                .teamId(snap.teamId())
                .action(action)
                .bizType(bizType)
                .bizId(bizId)
                .operatorId(snap.userId())
                .operatorName(snap.username())
                .operatorRole(snap.roles().isEmpty() ? null : snap.roles().get(0))
                .result(AuditResult.SUCCESS)
                .changeSummary(summary)
                .before(before == null ? null : Map.of("on_hand_qty", before))
                .after(after == null ? null : Map.of("on_hand_qty", after))
                .build());
    }

    private static String qtyText(BigDecimal value) {
        return value == null ? "-" : value.toPlainString();
    }

    // =====================================================================
    // 校验 / 上下文 / 读取
    // =====================================================================

    private long currentTeamId() {
        return TeamContext.require().teamId();
    }

    private Long currentOperatorId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        return snapshot == null ? null : snapshot.userId();
    }

    private BalanceRow requireBalance(long warehouseId, long skuId) {
        InventoryBalanceEntity entity = balanceMapper.selectOne(
                new LambdaQueryWrapper<InventoryBalanceEntity>()
                        .eq(InventoryBalanceEntity::getWarehouseId, warehouseId)
                        .eq(InventoryBalanceEntity::getSkuId, skuId));
        if (entity == null) {
            throw new BizException(ErrorCodes.INV_5004, "未找到对应库存记录");
        }
        return new BalanceRow(entity.getId(), entity.getWarehouseId(), entity.getSkuId(),
                entity.getOnHandQty(), entity.getVersion());
    }

    /**
     * 入库 / 出库数量：必须为正且精度 ≤ 3 位，否则 {@code COM-0001}（400 参数非法）。
     *
     * <p>这里<b>刻意不用</b> {@code INV-5005}：其权威语义是「<b>调整</b>数量为 0 或非法」
     * （{@code ERROR_CODES §2.6}），扩用到入库/出库会稀释语义
     * （{@code ADR-005 §2.2} 明令 {@code INV-5005} / {@code INV-5006} 不得混用）。
     */
    private static void requirePositiveQuantity(BigDecimal qty, String field) {
        if (qty == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of(field, "数量不能为空")));
        }
        requireScale(qty, field, ErrorCodes.COM_0001);
        if (qty.signum() <= 0) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of(field, "数量必须大于 0")));
        }
    }

    /** 调整类数量：非空且精度 ≤ 3 位，否则 {@code INV-5005}（400）。 */
    private static void requireAdjustQuantity(BigDecimal value, String field) {
        if (value == null) {
            throw BizException.withDetails(ErrorCodes.INV_5005, "调整数量不能为 0",
                    List.of(ApiErrorDetail.of(field, "数量不能为空")));
        }
        requireScale(value, field, ErrorCodes.INV_5005);
    }

    /** 精度校验：小数位 &gt; 3 视为非法（{@code DECIMAL(18,3)}，见 {@code QtyUtils.SCALE}）。 */
    private static void requireScale(BigDecimal value, String field, String errorCode) {
        if (value.stripTrailingZeros().scale() > QtyUtils.SCALE) {
            throw BizException.withDetails(errorCode, errorCode.equals(ErrorCodes.INV_5005)
                            ? "调整数量不能为 0" : "请求参数校验失败",
                    List.of(ApiErrorDetail.of(field,
                            "数量精度不得超过 " + QtyUtils.SCALE + " 位小数，实测：" + value.toPlainString())));
        }
    }

    /** 调整原因必填（{@code §5.5} / {@code INV-5011}）。 */
    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw BizException.withDetails(ErrorCodes.INV_5011, "请填写调整原因",
                    List.of(ApiErrorDetail.of("reason", "调整原因必填")));
        }
        if (reason.length() > 255) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("reason", "调整原因不得超过 255 字符")));
        }
    }

    /** 幂等键：非空、≤ 128（本服务只校验，不构造，见类注释）。 */
    private static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("idempotency_key", "幂等键必填")));
        }
        String trimmed = key.trim();
        if (trimmed.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("idempotency_key",
                            "幂等键长度不得超过 " + IDEMPOTENCY_KEY_MAX_LENGTH)));
        }
        return trimmed;
    }

    /** 余额行的只读快照（{@code version} 是乐观锁比较基准）。 */
    private record BalanceRow(long id, long warehouseId, long skuId, BigDecimal onHandQty, int version) {
    }

    /**
     * 冲销尝试单元的结果 + 原流水的 {@code sku/warehouse}（仅供审计摘要拼接，不对外暴露）。
     *
     * <p>不带出来就得在审计阶段再查一次原流水；{@code StockChangeResult} 又是跨模块契约
     * （{@code MODULE_BOUNDARIES §2.5}），不因审计需求扩字段（{@code R64} 同理）。
     */
    private record ReversalOutcome(StockChangeResult result, Long skuId, Long warehouseId) {
    }

    /**
     * 乐观锁冲突的内部信号（不对外暴露）：只用于在 {@link #runWithRetry} 中触发「回滚到 SAVEPOINT + 重试」。
     *
     * <p>刻意<b>不</b>复用 {@code BizException(INV-5002)} 作为重试信号：业务异常会被
     * {@code @Transactional} 直接判为需要回滚整个事务，无法用于「同事务内重试」。
     */
    private static final class OptimisticConflict extends RuntimeException {

        private static final long serialVersionUID = 1L;

        OptimisticConflict() {
            super("inventory balance optimistic lock conflict", null, false, false);
        }
    }

}
