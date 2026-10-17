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
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryTransactionEntity;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryBalanceMapper;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryTransactionMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
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
 *       缺团队即跳过，<b>不造无主审计行</b>。</li>
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

    private final InventoryBalanceMapper balanceMapper;
    private final InventoryTransactionMapper transactionMapper;
    private final InventoryBalanceUpdater balanceUpdater;
    private final AuditWriteService auditWriteService;
    private final TransactionTemplate attemptTemplate;

    public InventoryWriteService(InventoryBalanceMapper balanceMapper,
                                 InventoryTransactionMapper transactionMapper,
                                 InventoryBalanceUpdater balanceUpdater,
                                 AuditWriteService auditWriteService,
                                 PlatformTransactionManager transactionManager) {
        this.balanceMapper = balanceMapper;
        this.transactionMapper = transactionMapper;
        this.balanceUpdater = balanceUpdater;
        this.auditWriteService = auditWriteService;
        this.attemptTemplate = new TransactionTemplate(transactionManager);
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
     * @param qty            导入数量（&gt; 0，精度 ≤ 3 位）
     * @param bizRefId       导入批次 ID（可空）
     * @param idempotencyKey 幂等键（调用方按 {@code §8.3} 构造，长度 ≤ 128）
     * @throws BizException {@code INV-5003} / {@code INV-5002}
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public StockChangeResult applyInitialImport(long skuId, long warehouseId, BigDecimal qty,
                                                Long bizRefId, String idempotencyKey) {
        requirePositiveQuantity(qty, "qty");
        String key = requireIdempotencyKey(idempotencyKey);
        return runWithRetry(() -> attemptInflow(skuId, warehouseId, InventoryTxnType.INIT_IMPORT,
                InventoryBizType.IMPORT, qty, bizRefId, null, key));
    }

    /**
     * 采购到货入库（{@code §5.3}；业务触发方是 {@code purchasing}，方向不可反）。
     *
     * <p>{@code txn_type = PURCHASE_INBOUND}；余额行不存在时自动建行（首期允许「先到货后建账」）。
     *
     * @param bizType        业务来源（须为 {@link InventoryBizType} 成员，到货通常为 {@code GOODS_RECEIPT}）
     * @param bizRefId       业务单据 ID（到货单 ID）
     * @param idempotencyKey 幂等键（{@code §5.3}：{@code PURCHASE_INBOUND:GOODS_RECEIPT:<receiptId>:<skuId>}）
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

    /** 冲销：三态校验（不存在 / 本身是 REVERSAL / 已被冲销）后再写反向流水。 */
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
        if (transactionMapper.countByReversedTxnId(originalTxnId) > 0) {
            throw new BizException(ErrorCodes.INV_5009, "该笔流水已被冲销");
        }
        BalanceRow balance = requireBalance(original.getWarehouseId(), original.getSkuId());
        BigDecimal delta = original.getQtyDelta().negate();
        StockChangeResult replay = replayIfIdempotent(InventoryTxnType.REVERSAL, InventoryBizType.REVERSAL,
                originalTxnId, original.getSkuId(), original.getWarehouseId(), delta, false, key, balance);
        if (replay != null) {
            return new ReversalOutcome(replay, original.getSkuId(), original.getWarehouseId());
        }
        StockChangeResult result = writeChange(teamId, balance, InventoryTxnType.REVERSAL,
                InventoryBizType.REVERSAL, delta, originalTxnId, originalTxnId, reason, key, operatorId,
                ErrorCodes.INV_5006);
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
        if (existing == null) {
            return null;
        }
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
     * 统一写审计。
     *
     * <p>★ 不包 {@code AuditBufferScope}、不 try/catch：{@code AuditWriteService.record} 自带
     * 「失败只记 ERROR、不抛」兜底（{@code AuditWriteService:90-104}）；{@code AuditBufferScope}
     * 是导入批量场景的优化（{@code ADR-004}），本批是请求级单条。
     *
     * <p>{@code team_id} / 操作人一律取自 {@link TeamContext}，缺团队即跳过（<b>禁止造无主审计行</b>）。
     */
    private void recordAudit(AuditAction action, String bizType, Long bizId, String summary,
                             String before, String after) {
        TeamContext.Snapshot snap = TeamContext.get();
        if (snap == null || snap.teamId() == null) {
            // 无团队上下文（理论上 REST 路径不会发生）则不写审计，避免造无主审计行
            return;
        }
        auditWriteService.record(AuditEvent.builder()
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
