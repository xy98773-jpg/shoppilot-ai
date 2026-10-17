package com.shoppilot.importdata.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.audit.application.AuditBufferScope;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.catalog.application.CatalogWriteService;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.importdata.application.dto.ConfirmRequest;
import com.shoppilot.importdata.application.dto.ConfirmResultView;
import com.shoppilot.importdata.domain.model.ImportBatchStatus;
import com.shoppilot.importdata.domain.model.ImportBizType;
import com.shoppilot.importdata.domain.model.ImportFieldSpec;
import com.shoppilot.importdata.domain.model.ImportFieldType;
import com.shoppilot.importdata.domain.model.ImportTemplateRegistry;
import com.shoppilot.importdata.infrastructure.parser.ImportFileParser;
import com.shoppilot.importdata.infrastructure.parser.ImportParserFactory;
import com.shoppilot.importdata.infrastructure.parser.ParseFailureException;
import com.shoppilot.importdata.infrastructure.parser.ParseOptions;
import com.shoppilot.importdata.infrastructure.parser.ParsedSheet;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportRowErrorEntity;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportBatchMapper;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportRowErrorMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>步骤 9-11 确认导入 / 分批落库</b>（{@code IMPORT_WORKFLOW §6} 步骤 9-11 + §12 事务边界；
 * {@code API_CONTRACT §11.3} 步骤 4）。
 *
 * <h2>三阶段事务边界（裁定 B，逐条对应 {@code §12}）</h2>
 * <ol>
 *   <li><b>状态推进【单事务】</b>：条件 {@code UPDATE ... WHERE id=? AND team_id=? AND status='VALIDATED'}，
 *       影响行数 = 0 → {@code 422 IMP-4005}；同事务写 {@code started_at}；</li>
 *   <li><b>落库【分批事务，每批 {@value #ROW_BATCH_SIZE} 行，批间独立提交】</b>：
 *       批内计数与数据同一事务累加（{@code setSql("success_rows = success_rows + n")}）；</li>
 *   <li><b>终态【单事务】</b>：写 {@code finished_at} + 终态 + 审计 {@code IMPORT_BATCH_COMPLETE}；
 *       并<b>断言</b> {@code success_rows + failed_rows = total_rows}，不成立直接抛
 *       {@code IllegalStateException}（按实现缺陷处理，不静默放过）。</li>
 * </ol>
 * 本服务<b>不</b>在类上标 {@code @Transactional}——否则三个阶段会并入一个事务，违反 §12。
 * 事务通过 {@link TransactionTemplate} 显式划段。
 *
 * <h2>★ 行级错误与批内原子（裁定 C1 / C1b / C2）</h2>
 * <ul>
 *   <li><b>C1</b>：行内业务异常（{@link BizException} 及子类，如 {@code CAT-3001}/{@code CAT-3002}/
 *       {@code CAT-3005}）→ 该行回滚到 {@code SAVEPOINT} + 写 {@code import_row_errors}({@code ERROR})
 *       + {@code failed_rows++} + <b>继续处理批内其余行</b>；</li>
 *   <li><b>C1b</b>：{@link DuplicateKeyException}（唯一键冲突）亦按行级失败处理，
 *       {@code error_code = CAT-3002}（「编码被占用」）。<b>分类代码位置</b>＝
 *       {@link #isRowLevelFailure(RuntimeException)}；</li>
 *   <li><b>C2</b>：其它非业务异常（非预期 {@code DataAccessException} / {@code Error}）→
 *       整批 {@code ROLLBACK}（批内原子）+ 该批<b>全部行</b>记 {@code ERROR}
 *       + {@code failed_rows += 该批行数} + 继续下一批。</li>
 * </ul>
 *
 * <h2>★ 两遍式落库（{@code ADR-004 D5}，{@code B4-PERF} 轮 1）：去逐行 {@code SAVEPOINT}</h2>
 * 旧实现（M3-B4）批内逐行调用 {@code upsertXxxByCode}（{@code NESTED}），每行两次
 * {@code SAVEPOINT}/{@code RELEASE} 往返，1 万行 = 2 万次额外往返。现改为：
 * <ul>
 *   <li><b>Pass 1</b>（正常路径）：整批一个事务、调 {@code catalog.upsertXxxsByCode}({@code REQUIRED})、
 *       <b>零</b> {@code SAVEPOINT}、循环内<b>零</b> catch；</li>
 *   <li><b>Pass 2</b>（仅当 Pass 1 抛<b>行级</b>异常时）：逐行重放，{@code NESTED} 提供行级
 *       {@code SAVEPOINT}，失败行隔离、成功行照常提交；</li>
 *   <li>非行级异常（{@code C2}）不进 Pass 2，沿用「整批回滚 + 整批行记失败」。</li>
 * </ul>
 * 详见 {@link #writeRowBatch}。不变式 {@code success_rows + failed_rows = total_rows} 不变。
 *
 * <h2>幂等（裁定 E1/E2/E3）</h2>
 * <ul>
 *   <li><b>E1</b>：缺失 {@code Idempotency-Key} → {@code 400 COM-0001} + {@code details};</li>
 *   <li><b>E2</b>：批次已 {@code IMPORTING}/{@code COMPLETED}/{@code PARTIAL_SUCCESS}/{@code FAILED}
 *       → <b>重放分支</b>：回当前结果 + 由控制器补 {@code Idempotent-Replayed: true}，<b>严禁重复落库</b>；</li>
 *   <li><b>E3</b>：{@code expected_total_rows} 提供且 ≠ 当前 {@code total_rows} → {@code 409 COM-0009}。</li>
 *   <li><b>E4（本批不实现，已登记）</b>：「同 {@code Idempotency-Key} + 不同请求体 → {@code COM-0009}」
 *       需要按 {@code (team_id, idempotency_key)} 持久化首次请求体，而 {@code import_batches}
 *       无该列、新增须迁移（超出本批零迁移红线）→ 留待后续批次。</li>
 * </ul>
 *
 * <h2>裁定 A2：未支持类型 + 裁定 K1：行数上限</h2>
 * <ul>
 *   <li>{@code INVENTORY_SNAPSHOT} / {@code SALES_DAILY}（依赖 {@code inventory_balances} V1.0.6 /
 *       {@code sales_daily} V1.0.7，均未建）→ {@code 400 COM-0001} + {@code details[bizType]}，
 *       且批次<b>保持 {@code VALIDATED}</b>（先判定、后推进）；</li>
 *   <li>{@code total_rows > }{@value #MAX_TOTAL_ROWS} → {@code 400 IMP-4013}。本批<b>至少保证 confirm
 *       不会绕过</b>该上限（上传/校验入口的补齐已登记待办）。</li>
 * </ul>
 *
 * <h2>审计：条数不减、提交粒度降到「每批一次」、无孤儿（{@code ADR-004} D1/D2/D3）</h2>
 * {@code catalog} 的写服务对每个对象写一行 {@code MASTER_DATA_STATUS_CHANGE}（新建与
 * <b>导入更新</b>都写，{@code R48}），故上万行导入仍产生上万行审计——<b>条数不抑制</b>
 * （{@code ADR-004 D3} 红线）。改变的只是<b>提交粒度</b>：每个批次事务外各开一个
 * {@link AuditBufferScope}，审计先入线程缓冲，业务事务<b>提交后</b>由 {@code afterCommit}
 * 一次性 {@code recordAll}（一个独立事务、一次提交）；整批回滚则缓冲丢弃（{@code R45} 孤儿审计消除）。
 * 旧实现为「每行一次 {@code REQUIRES_NEW} 提交」（1 万行 = 1 万次提交，约 85 s，
 * 见 {@code DESIGN_REVIEW §5.1}）。
 */
@Service
public class ImportConfirmService {

    private static final Logger log = LoggerFactory.getLogger(ImportConfirmService.class);

    /**
     * 分批落库的固定批大小（{@code IMPORT_WORKFLOW §12.1} 的「平衡点」，裁定 B 明令不得改为其它值）。
     *
     * <p>位置：{@code ImportConfirmService.ROW_BATCH_SIZE}（本常量）。
     */
    public static final int ROW_BATCH_SIZE = 500;

    /** 单批数据行上限（{@code §8.5}：20 万行/批；{@code IMP-4013}）。 */
    private static final int MAX_TOTAL_ROWS = 200_000;

    /** 本批支持落库的业务类型（裁定 A1）。 */
    private static final Set<String> SUPPORTED_BIZ_TYPES =
            Set.of(ImportBizType.PRODUCT.name(), ImportBizType.SKU.name(),
                    ImportBizType.SUPPLIER.name(), ImportBizType.WAREHOUSE.name());

    /** 走「重放分支」的状态（裁定 E2）：已进入导入中或已是终态。 */
    private static final Set<String> REPLAY_STATUSES = Set.of(
            ImportBatchStatus.IMPORTING.name(), ImportBatchStatus.COMPLETED.name(),
            ImportBatchStatus.PARTIAL_SUCCESS.name(), ImportBatchStatus.FAILED.name());

    private static final String LEVEL_ERROR = "ERROR";

    private final ImportBatchMapper batchMapper;
    private final ImportRowErrorMapper rowErrorMapper;
    private final ImportParseService parseService;
    private final ImportParserFactory parserFactory;
    private final ObjectMapper objectMapper;
    private final CatalogWriteService catalogWriteService;
    private final AuditWriteService auditWriteService;
    private final TransactionTemplate txTemplate;

    public ImportConfirmService(ImportBatchMapper batchMapper,
                                ImportRowErrorMapper rowErrorMapper,
                                ImportParseService parseService,
                                ImportParserFactory parserFactory,
                                ObjectMapper objectMapper,
                                CatalogWriteService catalogWriteService,
                                AuditWriteService auditWriteService,
                                PlatformTransactionManager transactionManager) {
        this.batchMapper = batchMapper;
        this.rowErrorMapper = rowErrorMapper;
        this.parseService = parseService;
        this.parserFactory = parserFactory;
        this.objectMapper = objectMapper;
        this.catalogWriteService = catalogWriteService;
        this.auditWriteService = auditWriteService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // =====================================================================
    // 对外入口
    // =====================================================================

    /**
     * 确认导入（{@code POST /{id}/confirm}）。
     *
     * @param batchId        批次 ID
     * @param idempotencyKey 请求头 {@code Idempotency-Key}（必填，裁定 E1）
     * @param request        请求体（可空 → 等价于空对象）
     * @return 结果；{@link ConfirmOutcome#isBlocked()} 为真表示命中 {@code IMP-4009} 前置门
     *         （需由控制器用 {@code data} 回带 {@code batch_id/status/error_rows}，契约明文）
     */
    public ConfirmOutcome confirm(Long batchId, String idempotencyKey, ConfirmRequest request) {
        TeamContext.Snapshot snapshot = TeamContext.require();
        long teamId = snapshot.teamId();

        // ---- E1：幂等键必填 ----
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("Idempotency-Key", "确认导入必须携带幂等键", null)));
        }

        ImportBatchEntity batch = parseService.loadOwned(batchId, teamId);
        String status = batch.getStatus();

        // ---- E2：重放分支（严禁重复落库）----
        if (REPLAY_STATUSES.contains(status)) {
            log.info("确认导入重放：batchId={} status={}（不重复落库）", batchId, status);
            return ConfirmOutcome.replayed(ConfirmResultView.of(batch));
        }
        // ---- 状态守卫 ----
        if (!ImportBatchStatus.VALIDATED.name().equals(status)) {
            throw ImportParseService.statusMismatch(ImportBatchStatus.VALIDATED.name(), status, "确认导入");
        }

        // ---- A2：未支持类型（先判定、后推进；批次保持 VALIDATED）----
        if (!SUPPORTED_BIZ_TYPES.contains(batch.getBizType())) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("bizType", "该业务类型落库尚未支持（依赖表未建）",
                            batch.getBizType())));
        }
        ImportBizType bizType = ImportBizType.valueOf(batch.getBizType());

        int totalRows = batch.getTotalRows() == null ? 0 : batch.getTotalRows();

        // ---- E3：expected_total_rows ----
        // 文案订正（DESIGN_REVIEW §5.2 L3-1）：E3 是「批次行数与期望不符」，与幂等键无关——
        // 旧文案「幂等键已被使用且本次请求内容与原请求不一致」会把排障引向幂等冲突（E4 场景）。
        Integer expected = request == null ? null : request.getExpectedTotalRows();
        if (expected != null && expected != totalRows) {
            throw BizException.withDetails(ErrorCodes.COM_0009, "批次总行数与期望不一致",
                    List.of(new ApiErrorDetail("expected_total_rows",
                            "当前批次 total_rows 为 " + totalRows, expected)));
        }

        // ---- K1：行数上限（confirm 不绕过）----
        if (totalRows > MAX_TOTAL_ROWS) {
            throw new BizException(ErrorCodes.IMP_4013, "单次导入行数过多，请拆分文件");
        }

        // ---- D1：前置门（存在 ERROR 行 → 422 IMP-4009 + data）----
        long errorRows = rowErrorMapper.countByBatch(batchId, LEVEL_ERROR, null);
        if (errorRows > 0) {
            return ConfirmOutcome.blocked(batchId, status, errorRows);
        }

        // ---- 全量重解析（与校验阶段同源：输入文件与选项都固定，结果一致）----
        List<String> header;
        List<List<String>> rows;
        Map<String, Integer> targetCol;
        try {
            Path file = resolveStoredFile(batch);
            ParseOptions options = ImportParseService.readOptions(batch.getParseOptionsJson());
            ImportFileParser parser = parserFactory.of(batch.getFileName());
            ParsedSheet sheet = parser.parse(file, options, Integer.MAX_VALUE);
            header = sheet.header();
            rows = sheet.previewRows();
            targetCol = resolveTargetColumns(bizType, header,
                    ImportParseService.readMapping(batch.getFieldMappingJson()));
        } catch (ParseFailureException ex) {
            throw new BizException(ex.code(), ex.getMessage());
        }

        // ---- 阶段 1：状态推进 VALIDATED → IMPORTING【单事务】----
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);
        Boolean advanced = txTemplate.execute(tx -> batchMapper.update(null,
                new LambdaUpdateWrapper<ImportBatchEntity>()
                        .eq(ImportBatchEntity::getId, batchId)
                        .eq(ImportBatchEntity::getTeamId, teamId)
                        .eq(ImportBatchEntity::getStatus, ImportBatchStatus.VALIDATED.name())
                        .set(ImportBatchEntity::getStatus, ImportBatchStatus.IMPORTING.name())
                        .set(ImportBatchEntity::getStartedAt, startedAt)
                        .set(ImportBatchEntity::getUpdatedBy, snapshot.userId())) > 0);
        if (!Boolean.TRUE.equals(advanced)) {
            // 并发：另一请求已推进 → 按 E2 重放语义返回（或状态不允许）
            ImportBatchEntity fresh = parseService.loadOwned(batchId, teamId);
            if (REPLAY_STATUSES.contains(fresh.getStatus())) {
                return ConfirmOutcome.replayed(ConfirmResultView.of(fresh));
            }
            throw ImportParseService.statusMismatch(ImportBatchStatus.VALIDATED.name(),
                    fresh.getStatus(), "确认导入");
        }

        // ---- 阶段 2：分批落库【每批独立事务】----
        for (int from = 0; from < rows.size(); from += ROW_BATCH_SIZE) {
            int to = Math.min(from + ROW_BATCH_SIZE, rows.size());
            writeRowBatch(batch, teamId, bizType, header, rows, targetCol, from, to, snapshot);
        }

        // ---- 阶段 3：终态【单事务】----
        return txTemplate.execute(tx -> complete(batch, teamId, totalRows, snapshot));
    }

    // =====================================================================
    // 阶段 2：单批落库
    // =====================================================================

    /**
     * 落库一个批次区间 {@code [from, to)}（一个事务，批间独立提交）——<b>两遍式</b>（{@code ADR-004 D5}）。
     *
     * <h2>Pass 1（正常路径）：整批一个事务、零 {@code SAVEPOINT}、零行级 {@code catch}</h2>
     * 批内一次性把全部行交给 {@code catalog} 的批量入口 {@code upsertXxxsByCode}({@code REQUIRED})：
     * <ul>
     *   <li>省掉每行 {@code SAVEPOINT}/{@code RELEASE} 两次往返（{@code DESIGN_REVIEW §5.1} 归因中
     *       业务侧残差的主要成分之一）；</li>
     *   <li>循环内<b>不</b> catch：任一行抛 {@code BizException}/{@code DuplicateKeyException} →
     *       异常穿出 → 批次事务整体回滚 → 转 Pass 2。<b>不得</b>在此吞异常后继续
     *       （参与方吞异常会让 {@code rollback-only} 在提交时炸成 {@code UnexpectedRollbackException}）。</li>
     * </ul>
     *
     * <h2>Pass 2（失败路径）：逐行重放、行级 {@code NESTED} 隔离</h2>
     * 仅当 Pass 1 的异常属<b>行级失败</b>（{@link #isRowLevelFailure}）时进入，逐行调用
     * {@code upsertXxxByCode}（{@code NESTED} = 行级 {@code SAVEPOINT}）：失败行写
     * {@code import_row_errors} 并继续，成功行照常提交。语义与单遍逐行实现<b>完全一致</b>
     * （裁定 {@code C1}/{@code C1b}），只是把它降级成「只在出问题时才走」的慢路径。
     *
     * <p>非行级异常（裁定 {@code C2}）<b>不进入</b> Pass 2：整批回滚 + 该批全部行记 {@code ERROR}
     * （见 {@link #recordWholeBatchFailure}）。
     *
     * <h2>审计（{@code ADR-004} D2 / {@code R45}）</h2>
     * 每个批次事务（Pass 1 与 Pass 2 各一次）都用 {@link AuditBufferScope} 包住：
     * <ul>
     *   <li><b>提交成功</b> → {@code afterCommit} 一次性 {@code recordAll}（每批<b>一次</b>提交，
     *       审计条数不变）；</li>
     *   <li><b>整批回滚</b> → 缓冲随事务一起丢弃 ⇒ 该批审计 <b>0 条</b>（{@code R45} 孤儿审计消除）；</li>
     *   <li>Pass 1 的缓冲随回滚丢弃、Pass 2 重新收集 ⇒ <b>不会</b>重复写审计。</li>
     * </ul>
     */
    private void writeRowBatch(ImportBatchEntity batch, long teamId, ImportBizType bizType,
                               List<String> header, List<List<String>> rows,
                               Map<String, Integer> targetCol, int from, int to,
                               TeamContext.Snapshot snapshot) {
        // ---------- Pass 1：整批一个事务，无 SAVEPOINT ----------
        try {
            try (AuditBufferScope auditScope = new AuditBufferScope(auditWriteService)) {
                txTemplate.executeWithoutResult(tx -> {
                    List<JsonNode> bodies = new ArrayList<>(to - from);
                    for (int i = from; i < to; i++) {
                        bodies.add(buildRowBody(bizType, targetCol, rows.get(i)));
                    }
                    upsertRowsByCode(bizType, bodies);
                    accumulateRows(batch, teamId, bodies.size(), 0);
                });
            }
            return; // Pass 1 全部成功
        } catch (RuntimeException | Error ex) {
            boolean rowLevel = ex instanceof RuntimeException runtime && isRowLevelFailure(runtime);
            if (!rowLevel) {
                // ---- C2：非业务异常 → 整批回滚（缓冲同时丢弃）+ 该批全部行记 ERROR + 继续下一批 ----
                log.error("批次 {} 行区间 [{}, {}) 发生非业务异常，整批回滚", batch.getId(), from, to, ex);
                txTemplate.executeWithoutResult(tx -> recordWholeBatchFailure(batch, teamId, header, rows,
                        from, to, snapshot));
                return;
            }
            log.info("批次 {} 行区间 [{}, {}) 的 Pass 1 命中行级失败（{}），改走 Pass 2 逐行重放",
                    batch.getId(), from, to, ex.toString());
        }

        // ---------- Pass 2：逐行重放，行级 NESTED 隔离 ----------
        try {
            try (AuditBufferScope auditScope = new AuditBufferScope(auditWriteService)) {
                txTemplate.executeWithoutResult(tx -> {
                    int success = 0;
                    int failed = 0;
                    for (int i = from; i < to; i++) {
                        int rowNo = i + 1;
                        try {
                            JsonNode body = buildRowBody(bizType, targetCol, rows.get(i));
                            writeRow(bizType, body);
                            success++;
                        } catch (RuntimeException ex) {
                            if (!isRowLevelFailure(ex)) {
                                throw ex; // C2：非业务异常 → 交外层整批回滚
                            }
                            recordRowFailure(batch, teamId, rowNo, ex, header, rows.get(i), snapshot);
                            failed++;
                        }
                    }
                    accumulateRows(batch, teamId, success, failed);
                });
            }
        } catch (RuntimeException | Error ex) {
            log.error("批次 {} 行区间 [{}, {}) 的 Pass 2 发生非业务异常，整批回滚", batch.getId(), from, to, ex);
            txTemplate.executeWithoutResult(tx -> recordWholeBatchFailure(batch, teamId, header, rows,
                    from, to, snapshot));
        }
    }

    /**
     * 把一批 body 交给 {@code catalog} 的批量入口（{@code ADR-004 D5} Pass 1）。
     *
     * <p>{@code MODULE_BOUNDARIES §2.4}/{@code §2.3}：经 catalog 的 application 服务，
     * <b>不</b>注入其 Mapper。<b>不得</b>在此 catch 任何异常。
     */
    private void upsertRowsByCode(ImportBizType bizType, List<JsonNode> bodies) {
        switch (bizType) {
            case PRODUCT -> catalogWriteService.upsertProductsByCode(bodies);
            case SKU -> catalogWriteService.upsertSkusByCode(bodies);
            case SUPPLIER -> catalogWriteService.upsertSuppliersByCode(bodies);
            case WAREHOUSE -> catalogWriteService.upsertWarehousesByCode(bodies);
            default -> throw new BizException(ErrorCodes.COM_0001, "该业务类型落库尚未支持（依赖表未建）");
        }
    }

    /**
     * 批次计数累加（与数据同一事务，裁定 B-2）。
     *
     * <p>用 {@code setSql("col = col + n")} 做<b>数据库侧自增</b>而非「读-改-写」：
     * 批间并发时不会互相覆盖（各批只加自己的增量）。
     */
    private void accumulateRows(ImportBatchEntity batch, long teamId, int success, int failed) {
        if (success + failed == 0) {
            return;
        }
        batchMapper.update(null, new LambdaUpdateWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getId, batch.getId())
                .eq(ImportBatchEntity::getTeamId, teamId)
                .setSql("success_rows = success_rows + " + success)
                .setSql("failed_rows = failed_rows + " + failed));
    }

    /**
     * 行级失败判定（裁定 C1 / C1b 的<b>分类代码位置</b>）。
     *
     * <p>可定位到具体行、且行回滚不破坏批内其余行原子性的两类异常：
     * <ul>
     *   <li>{@link BizException}（业务规则拒绝，如 {@code CAT-3001} 引用不存在、{@code CAT-3002} 编码被占用、
     *       {@code CAT-3005} 跨团队、{@code COM-0001} 字段非法）；</li>
     *   <li>{@link DuplicateKeyException}（唯一键冲突的兜底：并发下两个批次同时插入同编码时
     *       {@code selectIdByCode} 都查不到，后插入者撞 {@code uk_*_team_code}。语义＝「编码被占用」，
     *       取 {@code CAT-3002}；若按整批回滚处理，会把同批其余好数据一并丢弃、迫使运营重导）。</li>
     * </ul>
     * 其余异常（如连接中断等非预期 {@code DataAccessException}）不属于行级失败。
     */
    private static boolean isRowLevelFailure(RuntimeException ex) {
        return ex instanceof BizException || ex instanceof DuplicateKeyException;
    }

    /** 落库单行（经 catalog 的 application 服务；{@code MODULE_BOUNDARIES §2.4} / 裁定 G1）。 */
    private void writeRow(ImportBizType bizType, JsonNode body) {
        switch (bizType) {
            case PRODUCT -> catalogWriteService.upsertProductByCode(body);
            case SKU -> catalogWriteService.upsertSkuByCode(body);
            case SUPPLIER -> catalogWriteService.upsertSupplierByCode(body);
            case WAREHOUSE -> catalogWriteService.upsertWarehouseByCode(body);
            default -> throw new BizException(ErrorCodes.COM_0001, "该业务类型落库尚未支持（依赖表未建）");
        }
    }

    /**
     * 把一行单元格构造为 catalog 写服务所需的 body（裁定 H2）。
     *
     * <ul>
     *   <li>只放<b>已映射</b>且<b>该行有值</b>的字段：未映射列不放入；空单元格（非必填）不放入
     *       ⇒ 不覆盖库内原值（不得把库内已有值清空）；</li>
     *   <li>键字段（{@code product_code}/{@code sku_code}/{@code code}）必被放入（模板必填 ⇒ 校验阶段已拦空值）；</li>
     *   <li>类型归一：{@code JSON} 列解析为 JSON 节点（避免存成 JSON 字符串）、{@code BOOL} 解析为布尔、
     *       {@code ENUM}（{@code status}）大写归一，其余以文本传递（catalog 侧按列类型解析）。</li>
     * </ul>
     */
    private JsonNode buildRowBody(ImportBizType bizType, Map<String, Integer> targetCol, List<String> cells) {
        ObjectNode node = objectMapper.createObjectNode();
        for (Map.Entry<String, Integer> entry : targetCol.entrySet()) {
            String target = entry.getKey();
            ImportFieldSpec spec = ImportTemplateRegistry.specOf(bizType, target);
            if (spec == null) {
                continue;
            }
            String value = cell(cells, entry.getValue());
            if (value.isBlank()) {
                continue; // H2：空值不放入 body
            }
            if (spec.type() == ImportFieldType.JSON) {
                node.set(target, readJson(value));
            } else if (spec.type() == ImportFieldType.BOOL) {
                node.put(target, truthy(value));
            } else if (spec.type() == ImportFieldType.ENUM) {
                node.put(target, value.trim().toUpperCase(Locale.ROOT));
            } else {
                node.put(target, value.trim());
            }
        }
        return node;
    }

    /** 解析 JSON 单元格；失败时退化为文本（校验阶段已拦截非法 JSON，此处仅为兜底不抛）。 */
    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            return objectMapper.getNodeFactory().textNode(value);
        }
    }

    /** 布尔单元格归一（{@code §10.1} 枚举/布尔取值：是/否、true/false、1/0、yes/no、y/n）。 */
    private static boolean truthy(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "是", "yes", "y" -> true;
            default -> false;
        };
    }

    // =====================================================================
    // 错误行写入
    // =====================================================================

    /** 写一条行级失败（{@code error_level='ERROR'}，含 {@code raw_data} 原始全列）。 */
    private void recordRowFailure(ImportBatchEntity batch, long teamId, int rowNo, RuntimeException ex,
                                  List<String> header, List<String> cells, TeamContext.Snapshot snapshot) {
        String code;
        String field = null;
        String message;
        if (ex instanceof BizException be) {
            code = be.getErrorCode();
            message = be.getMessage();
            List<ApiErrorDetail> details = be.getDetails();
            if (details != null && !details.isEmpty() && details.get(0).field() != null) {
                field = details.get(0).field();
            }
        } else if (ex instanceof DuplicateKeyException) {
            // C1b：唯一键冲突 ⇒ 语义为「编码被占用」（§11.2 #15）
            code = ErrorCodes.CAT_3002;
            message = "该编码已存在，请更换";
        } else {
            code = ErrorCodes.COM_0002;
            message = "系统内部错误";
        }
        insertRowError(batch, teamId, rowNo, code, field, message, rawData(header, cells), snapshot);
    }

    /**
     * C2：把整批区间 {@code [from, to)} 的全部行记为失败（在新的独立事务中执行）。
     *
     * <h2>★ 为何是「整批」而不是「该批剩余（含当前行）」</h2>
     * 裁定 C2 的字面表述是「整批 ROLLBACK + 该批<b>剩余（含当前行）</b>全部记 ERROR +
     * {@code failed_rows += 剩余行数}」。但批次事务已整体回滚 ⇒ <b>该批当前行之前的行，其成功写入
     * 也已被撤销</b>（它们从未提交，{@code success_rows} 的累加同样被回滚）。
     * 若只按「剩余行数」计失败，这些<b>已被回滚的行既不算成功也不算失败</b>，
     * 会直接破坏裁定 B-3 强制的断言 {@code success_rows + failed_rows = total_rows}
     * （该断言不成立须按实现缺陷处理，不得静默放过）。
     * 故此处按「整批全部行失败」落地——它同时满足 C2「整批回滚」的字面要求与不变式；
     * 已在汇报中作为偏差登记。
     */
    private void recordWholeBatchFailure(ImportBatchEntity batch, long teamId, List<String> header,
                                         List<List<String>> rows, int from, int to,
                                         TeamContext.Snapshot snapshot) {
        for (int i = from; i < to; i++) {
            insertRowError(batch, teamId, i + 1, ErrorCodes.COM_0002, null,
                    "该批数据处理失败（系统异常），请重试或联系管理员", rawData(header, rows.get(i)), snapshot);
        }
        int rowsInBatch = to - from;
        batchMapper.update(null, new LambdaUpdateWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getId, batch.getId())
                .eq(ImportBatchEntity::getTeamId, teamId)
                .setSql("failed_rows = failed_rows + " + rowsInBatch));
    }

    private void insertRowError(ImportBatchEntity batch, long teamId, int rowNo, String code,
                                String field, String message, String rawData,
                                TeamContext.Snapshot snapshot) {
        ImportRowErrorEntity entity = new ImportRowErrorEntity();
        entity.setTeamId(teamId);
        entity.setBatchId(batch.getId());
        entity.setRowNo(rowNo);
        entity.setErrorLevel(LEVEL_ERROR);
        entity.setErrorCode(code);
        entity.setErrorField(field);
        entity.setErrorMessage(truncate(message, 500));
        entity.setRawData(rawData);
        entity.setCreatedBy(snapshot == null ? null : snapshot.userId());
        rowErrorMapper.insert(entity);
    }

    // =====================================================================
    // 阶段 3：终态
    // =====================================================================

    /** 写终态 + 断言不变式 + 审计（{@code IMPORT_BATCH_COMPLETE}，裁定 D2 / I1 / I3）。 */
    private ConfirmOutcome complete(ImportBatchEntity batch, long teamId, int totalRows,
                                    TeamContext.Snapshot snapshot) {
        ImportBatchEntity fresh = batchMapper.selectById(batch.getId());
        int success = fresh == null || fresh.getSuccessRows() == null ? 0 : fresh.getSuccessRows();
        int failed = fresh == null || fresh.getFailedRows() == null ? 0 : fresh.getFailedRows();

        // ★ 不变式断言（裁定 B-3）：不成立即实现缺陷，绝不静默放过
        if (success + failed != totalRows) {
            throw new IllegalStateException("落库计数不变式被破坏：success_rows(" + success + ") + failed_rows("
                    + failed + ") != total_rows(" + totalRows + ")，batchId=" + batch.getId());
        }

        String finalStatus = failed == 0
                ? ImportBatchStatus.COMPLETED.name()
                : (success > 0 ? ImportBatchStatus.PARTIAL_SUCCESS.name() : ImportBatchStatus.FAILED.name());

        LambdaUpdateWrapper<ImportBatchEntity> w = new LambdaUpdateWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getId, batch.getId())
                .eq(ImportBatchEntity::getTeamId, teamId)
                .set(ImportBatchEntity::getStatus, finalStatus)
                .set(ImportBatchEntity::getFinishedAt, LocalDateTime.now(ZoneOffset.UTC))
                .set(ImportBatchEntity::getUpdatedBy, snapshot.userId());
        if (ImportBatchStatus.FAILED.name().equals(finalStatus)) {
            // 终态 FAILED 释放幂等槽位（§9.5 / §6-E.4），便于修正后重传
            w.set(ImportBatchEntity::getDedupeHash, null);
            w.set(ImportBatchEntity::getErrorMessage, "全部数据行落库失败");
        }
        batchMapper.update(null, w);

        recordCompleteAudit(batch, teamId, finalStatus, snapshot);
        ImportBatchEntity done = batchMapper.selectById(batch.getId());
        return ConfirmOutcome.completed(ConfirmResultView.of(done == null ? batch : done), false);
    }

    /**
     * 审计 #9 {@code IMPORT_BATCH_COMPLETE}（{@code DESIGN_BASIS §9.17} 35 条内，直接使用不新增枚举）。
     *
     * <p>{@code result} 按 {@code §6-J.1}：{@code COMPLETED}/{@code PARTIAL_SUCCESS} → {@code SUCCESS}，
     * {@code FAILED} → {@code FAILURE}。
     *
     * <p><b>与裁定 I3 的偏差</b>：{@code AuditWriteService} 按 {@code MODULE_BOUNDARIES §2.10}（M2-T11 裁定）
     * 恒以 {@code REQUIRES_NEW} 独立事务写入，无法并入本阶段的业务事务；本批<b>不修改 audit 模块</b>
     * （红线），故这里在终态事务内发起调用，实际由 audit 的独立事务落库。
     */
    private void recordCompleteAudit(ImportBatchEntity batch, long teamId, String finalStatus,
                                     TeamContext.Snapshot snapshot) {
        boolean ok = ImportBatchStatus.COMPLETED.name().equals(finalStatus)
                || ImportBatchStatus.PARTIAL_SUCCESS.name().equals(finalStatus);
        auditWriteService.record(AuditEvent.builder()
                .teamId(teamId)
                .action(AuditAction.IMPORT_BATCH_COMPLETE)
                .bizType("IMPORT_BATCH")
                .bizId(batch.getId())
                .bizNo(batch.getBatchNo())
                .operatorId(snapshot.userId())
                .operatorName(snapshot.username())
                .operatorRole(snapshot.roles().isEmpty() ? null : snapshot.roles().get(0))
                .result(ok ? AuditResult.SUCCESS : AuditResult.FAILURE)
                .changeSummary("导入落库完成：" + batch.getBizType() + " → " + finalStatus)
                .build());
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 目标字段 → 源列下标（仅保留出现在表头中的映射，与校验阶段同口径）。 */
    private static Map<String, Integer> resolveTargetColumns(ImportBizType bizType, List<String> header,
                                                             Map<String, String> mapping) {
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            colIndex.putIfAbsent(header.get(i), i);
        }
        Map<String, Integer> targetCol = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (ImportTemplateRegistry.specOf(bizType, entry.getKey()) == null) {
                continue;
            }
            Integer idx = colIndex.get(entry.getValue());
            if (idx != null) {
                targetCol.put(entry.getKey(), idx);
            }
        }
        return targetCol;
    }

    private Path resolveStoredFile(ImportBatchEntity batch) {
        if (batch.getStoragePath() == null || batch.getStoragePath().isBlank()) {
            throw new BizException(ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码");
        }
        Path file = Path.of(batch.getStoragePath());
        if (!Files.isRegularFile(file)) {
            throw new BizException(ErrorCodes.IMP_4011, "原始文件已不可用，请重新上传");
        }
        return file;
    }

    /** {@code raw_data}：失败行的原始<b>全列</b>（含未映射列，{@code §6-E.2} / §9.3）。 */
    private String rawData(List<String> header, List<String> cells) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            map.put(header.get(i), i < cells.size() ? cells.get(i) : "");
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (IOException ex) {
            return null;
        }
    }

    private static String cell(List<String> cells, int idx) {
        if (idx < 0 || idx >= cells.size()) {
            return "";
        }
        String v = cells.get(idx);
        return v == null ? "" : v;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 确认导入结果。
     *
     * @param view      成功/重放时的响应体
     * @param replayed  是否为重复 confirm 的重放分支（控制器据此补 {@code Idempotent-Replayed: true}）
     * @param errorGate 非空表示命中前置门（{@code 422 IMP-4009}），控制器须用其组装 {@code data}
     */
    public record ConfirmOutcome(ConfirmResultView view, boolean replayed, ErrorGate errorGate) {

        static ConfirmOutcome completed(ConfirmResultView view, boolean replayed) {
            return new ConfirmOutcome(view, replayed, null);
        }

        static ConfirmOutcome replayed(ConfirmResultView view) {
            return new ConfirmOutcome(view, true, null);
        }

        static ConfirmOutcome blocked(long batchId, String status, long errorRows) {
            return new ConfirmOutcome(null, false, new ErrorGate(batchId, status, errorRows));
        }

        /** 是否命中 {@code IMP-4009} 前置门。 */
        public boolean isBlocked() {
            return errorGate != null;
        }

        /** {@code IMP-4009} 的 {@code data}（契约 §11.3 失败响应 2 明文：{@code batch_id/status/error_rows}）。 */
        public record ErrorGate(long batchId, String status, long errorRows) {
        }
    }
}
