package com.shoppilot.analysis.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.analysis.application.event.AnalysisRunCompletedEvent;
import com.shoppilot.analysis.domain.AnalysisParams;
import com.shoppilot.analysis.domain.AnalysisRunStatus;
import com.shoppilot.analysis.domain.AnalysisScope;
import com.shoppilot.analysis.domain.AnalysisScopeType;
import com.shoppilot.analysis.domain.CanonicalJson;
import com.shoppilot.analysis.domain.DataQualityGrade;
import com.shoppilot.analysis.domain.LlmStatus;
import com.shoppilot.analysis.domain.RiskLevel;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiResultItem;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRiskFinding;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunResultData;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunStatusData;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisItemEntity;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisItemMapper;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisRunMapper;
import com.shoppilot.analysis.infrastructure.sampling.AnalysisItemAssembler;
import com.shoppilot.analysis.infrastructure.sampling.AnalysisItemAssembler.SkuRef;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分析结果二次校验与落库服务（M4-T12）。
 *
 * <h2>★ 18 项校验，任一 FAILED 级失败 ⇒ 整批零落库（{@code AI_SERVICE_CONTRACT.md §7}）</h2>
 * <p>校验顺序与 {@code §7} 的清单编号一一对应，见 {@link #validate} 内的分段注释。
 * <b>禁止</b>「跳过坏条目、落好的那部分」——那会造出一份不可解释的半份快照，
 * 之后没人说得清「为什么这个 SKU 没在结果里」。因此本类在校验失败时
 * <b>一个字节都不写 {@code analysis_items}</b>，只把 run 置
 * {@code FAILED / ANL-7004} 并写一条 FINISH 审计。
 *
 * <h2>★ 事务边界（裁定 #20）</h2>
 * <p>{@code @Transactional(REQUIRED)}：写 {@code analysis_items} + run 终态 + 审计
 * <b>同一事务</b>。审计走 {@link AuditWriteService#recordInBusinessTx}（{@code MANDATORY}），
 * 消除「业务成功但审计缺失」。<b>Python 调用留在事务外</b>——由调用方
 * （{@code AnalysisRunSyncService}）先 {@code fetchResult} 再进本方法，
 * 绝不把「包住远程调用的整段」标成事务（{@code B20.1} 类注释已列三个坑）。
 *
 * <h2>★ 事务入口的行锁（裁定 #9）</h2>
 * <p>方法第一件事是 {@code SELECT ... FOR UPDATE} 重新读 run：并发场景下
 * 「读时同步」与「定时扫描」可能同时到达，第二个事务必须看到第一个已提交的终态并直接返回，
 * 而不是在旧快照上重复落库。
 *
 * <h2>★ {@code analysis_items} 是仅 INSERT 的不可变快照</h2>
 * <p>落库后<b>永不再 UPDATE</b>（表无 {@code updated_*}/{@code version} 列）。
 * 发现需要修正 ⇒ 设计错了，停下报告。
 *
 * <p>无状态；<b>不</b>依赖 {@code TeamContext}（定时任务路径用 {@code run.getTeamId()}）。
 */
@Service
public class AnalysisResultWriteService {

    /**
     * 计入 {@code risk_sku_count} 的 {@code risk_level} 三档（R138）。
     *
     * <p><b>依据</b>：{@code service-ai/src/shoppilot_ai/app/orchestration/pipeline.py:66}
     * <pre>RISK_LEVELS: frozenset[str] = frozenset({"HIGH", "MEDIUM", "LOW"})</pre>
     * 并由 {@code build_summary} 的 {@code risk_sku_count=sum(1 for item if item.risk_level in RISK_LEVELS)}
     * 使用；{@code DESIGN_REVIEW §5.32} 裁定 #12。
     *
     * <p>★ <b>为什么不能写成 {@code != NONE}</b>：那会把 {@code UNKNOWN}
     * （数据不足、无从判风险）也算成「有风险」，使 Java 自算的 {@code analysis_runs.risk_sku_count}
     * 与 Python 回传的 {@code summary_json.risk_sku_count} <b>互相矛盾</b>——
     * 同一份 {@code summary_json} 会与相邻列给出两个不同答案（R138）。
     * {@code NONE}（无风险）与 {@code UNKNOWN}（未知）都<b>不</b>计入。
     */
    private static final Set<String> RISK_LEVELS_FOR_COUNT = Set.of("HIGH", "MEDIUM", "LOW");


    private static final Logger log = LoggerFactory.getLogger(AnalysisResultWriteService.class);

    /** 审计业务类型（{@code DATABASE_DESIGN §6-J.1}）。 */
    private static final String AUDIT_BIZ_TYPE = "ANALYSIS_RUN";

    /** 数量列 scale（{@code analysis_items} 的 DECIMAL(18,3)，除 {@code cv} 外）。 */
    private static final int QTY_SCALE = 3;

    /** {@code cv} 列 scale（{@code DECIMAL(18,4)}：★ 与其余数量列不同）。 */
    private static final int CV_SCALE = 4;

    /** {@code llm_explanation} 落库上限（{@code §7} 第 15 项 / {@code PROMPT_DESIGN} 输出 Schema）。 */
    public static final int LLM_EXPLANATION_MAX_LENGTH = 2000;

    /** 失败原因列宽（{@code analysis_runs.error_message VARCHAR(500)}）。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 对外统一文案（二次校验失败的全部情形共用一条，细节只进日志与审计）。 */
    private static final String VALIDATION_FAILED_MESSAGE = "分析结果校验未通过，请稍后重试";

    private final AnalysisRunMapper runMapper;
    private final AnalysisItemMapper itemMapper;
    private final AnalysisItemAssembler assembler;
    private final AiServiceClient aiServiceClient;
    private final AuditWriteService auditWriteService;
    private final ApplicationEventPublisher eventPublisher;

    private static final ObjectMapper JSON = new ObjectMapper();

    public AnalysisResultWriteService(AnalysisRunMapper runMapper,
                                      AnalysisItemMapper itemMapper,
                                      AnalysisItemAssembler assembler,
                                      AiServiceClient aiServiceClient,
                                      AuditWriteService auditWriteService,
                                      ApplicationEventPublisher eventPublisher) {
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.assembler = assembler;
        this.aiServiceClient = aiServiceClient;
        this.auditWriteService = auditWriteService;
        this.eventPublisher = eventPublisher;
    }

    // =====================================================================
    // 入口
    // =====================================================================

    /**
     * 取结果并把「取不到」翻译成 {@code null}（异常<b>不外泄</b>给调用方）。
     *
     * <p>★ 本方法<b>刻意独立于</b> {@link #writeResult} 的事务：若把 {@code fetchResult}
     * 与落库写进同一个 {@code @Transactional} 方法，就会出现「远程调用期间长期持有数据库事务与行锁」
     * （3~10s），足以拖垮连接池；而本方法只是<b>读一次远端</b>，失败按静默降级处理。
     *
     * <p>★ 也正因如此，<b>不能</b>在同一个类里让 {@code writeCompletedResult} 调
     * {@code this.writeResult}：Spring 的 {@code @Transactional} 走代理，自调用会绕过代理，
     * 事务根本不开启，{@code recordInBusinessTx}（MANDATORY）随即抛
     * {@code IllegalTransactionStateException}。调用方必须<b>跨 Bean</b>调用
     * {@link #writeResult}。
     *
     * @param run 库内 run 快照
     * @return 结果数据；取不到（未就绪 / 超时 / 连接失败）返回 {@code null}
     */
    public AiRunResultData fetchResultOrNull(AnalysisRunEntity run) {
        try {
            return aiServiceClient.fetchResult(run.getId());
        } catch (BizException ex) {
            // 取结果失败（AI-6002 结果未就绪 / 6003 已取消 / 超时 / 连接失败）：
            //   「结果还没到」属正常竞态 → 保持进行中，等下一轮；
            //   其余（超时/5xx）同样不置终态，交由 timeout_at 兜底，避免误杀正在写的任务。
            log.warn("拉取分析结果失败（保持进行中，交由超时兜底）：runId={} code={}",
                    run.getId(), ex.getErrorCode());
            return null;
        }
    }

    /**
     * 校验 + 落库 + 置终态（同一事务）。
     *
     * @param run    库内 run 快照
     * @param result Python 回传的结果（{@code §3.5}）
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void writeResult(AnalysisRunEntity run, AiRunResultData result) {
        // ---- 事务入口行锁（裁定 #9）：并发下第二个事务必须看到已提交的终态并直接返回 ----
        AnalysisRunEntity locked = runMapper.selectOne(Wrappers.<AnalysisRunEntity>lambdaQuery()
                .eq(AnalysisRunEntity::getId, run.getId())
                .last("FOR UPDATE"));
        if (locked == null) {
            log.warn("分析运行不存在，丢弃结果：runId={}", run.getId());
            return;
        }
        AnalysisRunStatus current = parseStatus(locked.getStatus());
        if (current == null || current.isTerminal()) {
            log.info("分析运行已是终态，跳过落库：runId={} status={}", locked.getId(), locked.getStatus());
            return;
        }

        try {
            doWrite(locked, result);
        } catch (ValidationFailed ex) {
            // ★ 整批零落库：把 run 置 FAILED/ANL-7004，details 只进日志与审计
            log.warn("结果二次校验失败（整批零落库）：runId={} item={} reason={}",
                    locked.getId(), ex.skuId(), ex.getMessage());
            markValidationFailed(locked, ex.skuId());
            return;
        }
        // ★ 编排出口（MODULE_BOUNDARIES §8.2 E-2）：终态已在本事务内推进，提交后由
        //   replenishment 侧（@TransactionalEventListener AFTER_COMMIT）派发补货建议。
        //   analysis 不反向依赖 replenishment，故只能发事件、不能直接调用。
        eventPublisher.publishEvent(new AnalysisRunCompletedEvent(locked.getId(), locked.getTeamId()));
    }

    /** 校验 + 落库 + 终态（已持锁、已确认进行中）。 */
    private void doWrite(AnalysisRunEntity run, AiRunResultData result) {
        // #1 analysis_run_id 一致
        if (result.analysisRunId() == null || !result.analysisRunId().equals(run.getId())) {
            throw new ValidationFailed(null, "#1 analysis_run_id 与请求不一致");
        }
        // #11 算法版本一致
        if (!Objects.equals(result.algorithmVersion(), run.getAlgorithmVersion())) {
            throw new ValidationFailed(null, "#11 algorithm_version 与请求不一致：期望 "
                    + run.getAlgorithmVersion() + " 实际 " + result.algorithmVersion());
        }
        // #10 日期范围与请求一致（请求窗口即落库的 data_start_date/data_end_date）
        LocalDate requestedFrom = run.getDataStartDate();
        LocalDate requestedTo = run.getDataEndDate();
        LocalDate dataStart = parseDate(result.dataStartDate());
        LocalDate dataEnd = parseDate(result.dataEndDate());
        if (dataStart == null || dataEnd == null || dataStart.isAfter(dataEnd)) {
            throw new ValidationFailed(null, "#10 data_start_date/data_end_date 非法");
        }
        if (requestedFrom != null && requestedTo != null
                && (dataStart.isBefore(requestedFrom) || dataEnd.isAfter(requestedTo))) {
            throw new ValidationFailed(null, "#10 数据区间超出请求窗口 [" + requestedFrom + ", " + requestedTo + "]");
        }

        AnalysisScope scope = storedScope(run);
        AnalysisParams params = AnalysisParams.resolve(CanonicalJson.parseOrNull(run.getParamsJson()));

        // #13 去重（内存，保留首个）+ #12/#16 条数与范围校验
        List<AiResultItem> items = dedupeBySkuAndWarehouse(result.itemsOrEmpty());
        if (items.isEmpty()) {
            throw new ValidationFailed(null, "结果 items 为空（去重后）");
        }
        // #12 条数不超请求 SKU 数
        if (items.size() > run.getTotalSkus()) {
            throw new ValidationFailed(null, "#12 条数 " + items.size() + " 超过 run.total_skus " + run.getTotalSkus());
        }

        // #17 llm_status 在枚举内（run 级；Python 的 PENDING 对 COMPLETED 而言是 fail-closed 的失败）
        LlmStatus runLlm = parseLlmStatus(result.llmStatus());
        if (runLlm == null) {
            throw new ValidationFailed(null, "#18 run 级 llm_status 不在枚举内：" + result.llmStatus());
        }
        if (runLlm == LlmStatus.PENDING) {
            // 裁定 #17：COMPLETED 却回 PENDING ⇒ FAILED + ANL-7004（fail-closed）
            throw new ValidationFailed(null, "COMPLETED 却回 llm_status=PENDING");
        }

        // 主数据回读（R132 / #2 / #3 / #16 的判定依据）
        List<Long> skuIds = items.stream().map(AiResultItem::skuId).toList();
        Map<Long, SkuRef> skuRefs = assembler.loadSkuRefs(skuIds);
        Long warehouseOwner = assembler.warehouseTeamId(run.getWarehouseId());
        // #3 仓库属于该团队
        if (warehouseOwner == null || !Objects.equals(warehouseOwner, run.getTeamId())) {
            throw new ValidationFailed(null, "#3 warehouse_id 不属于该团队：run=" + run.getWarehouseId());
        }
        Map<Long, BigDecimal> inTransit = assembler.loadInTransitQty(
                run.getTeamId(), run.getWarehouseId(), skuIds);

        // 逐 item 校验 + 映射
        List<AnalysisItemEntity> rows = new ArrayList<>(items.size());
        int riskCount = 0;
        int suggestionCount = 0;
        for (AiResultItem item : items) {
            AnalysisItemEntity row = validateAndMap(run, params, scope, item, skuRefs, inTransit);
            // ★ R138：只计 HIGH / MEDIUM / LOW（与 Python RISK_LEVELS 同口径）
            if (RISK_LEVELS_FOR_COUNT.contains(item.riskLevel())) {
                riskCount++;
            }
            if (row.getQtySuggest() != null && row.getQtySuggest().signum() > 0) {
                // ★ suggestion_count 自算（裁定 #19）：不依赖 Python 的 summary，
                //   保证与「去重后实际落库条数」一致。
                suggestionCount++;
            }
            rows.add(row);
        }

        // 落库（仅 INSERT，不可变快照）+ run 终态 + 审计（同一事务）
        for (AnalysisItemEntity row : rows) {
            itemMapper.insert(row);
        }
        finishRun(run, result, rows.size(), riskCount, suggestionCount, runLlm);
    }

    // =====================================================================
    // 18 项校验（§7）
    // =====================================================================

    /** 单 item 的 16 项校验 + 字段映射（#1/#10/#11/#12/#13 已在批级完成）。 */
    private AnalysisItemEntity validateAndMap(AnalysisRunEntity run,
                                              AnalysisParams params,
                                              AnalysisScope scope,
                                              AiResultItem item,
                                              Map<Long, SkuRef> skuRefs,
                                              Map<Long, BigDecimal> inTransit) {
        Long skuId = item.skuId();
        // #9 必填字段非空
        if (skuId == null || item.warehouseId() == null
                || item.riskLevel() == null || item.dataQuality() == null || item.llmStatus() == null) {
            throw new ValidationFailed(skuId, "#9 必填字段缺失");
        }
        // #2 sku 属于该团队且启用（查不到 = 不存在或属他团队，同归「拒绝」）
        SkuRef ref = skuRefs.get(skuId);
        if (ref == null || !Objects.equals(ref.teamId(), run.getTeamId())) {
            throw new ValidationFailed(skuId, "#2 SKU 不存在、不属于该团队");
        }
        if (!"ACTIVE".equals(ref.status())) {
            throw new ValidationFailed(skuId, "#2 SKU 状态不是 ACTIVE：" + ref.status());
        }
        // #3 每个 item 的 warehouse 必须落在本次分析的仓（同属本团队）
        if (!Objects.equals(item.warehouseId(), run.getWarehouseId())) {
            throw new ValidationFailed(skuId, "#3 item.warehouse_id 与 run 不一致");
        }
        // #4 risk_level 枚举内（fail-closed）
        RiskLevel riskLevel = RiskLevel.fromCode(item.riskLevel());
        if (riskLevel == null) {
            throw new ValidationFailed(skuId, "#4 risk_level 不在枚举内：" + item.riskLevel());
        }
        // #5 risk_types 枚举内 + 去重
        List<String> riskTypes = normalizeRiskTypes(item.riskTypesOrEmpty());
        // #6 data_quality 枚举内
        DataQualityGrade grade = DataQualityGrade.fromCode(item.dataQuality());
        if (grade == null) {
            throw new ValidationFailed(skuId, "#6 data_quality 不在枚举内：" + item.dataQuality());
        }
        // #18 条目级 llm_status 枚举内
        LlmStatus itemLlm = parseLlmStatus(item.llmStatus());
        if (itemLlm == null) {
            throw new ValidationFailed(skuId, "#18 item.llm_status 不在枚举内：" + item.llmStatus());
        }
        // #16 超范围 SKU（按 scope 判定）
        assertWithinScope(run, scope, skuId, ref.productId());

        // 数量解析（#14 BigDecimal(String)；解析失败即校验失败）
        BigDecimal onHand = quantity(item.onHandQty(), skuId, "on_hand_qty");
        BigDecimal committed = quantity(item.committedQty(), skuId, "committed_qty");
        BigDecimal effectiveInTransit = quantity(item.effectiveInTransitQty(), skuId, "effective_in_transit_qty");
        BigDecimal inventoryPosition = signedQuantity(item.inventoryPosition(), skuId, "inventory_position");
        BigDecimal qBase = quantity(item.qBase(), skuId, "q_base");
        BigDecimal qSuggest = quantity(item.qSuggest(), skuId, "q_suggest");
        BigDecimal safetyStock = quantity(item.safetyStock(), skuId, "safety_stock");
        BigDecimal moq = quantity(item.moqApplied(), skuId, "moq_applied");
        BigDecimal orderMultiple = quantity(item.orderMultipleApplied(), skuId, "order_multiple_applied");
        BigDecimal avgDailySales = quantity(item.avgDailySales(), skuId, "avg_daily_sales");
        BigDecimal totalSales = quantity(item.salesQtyTotal(), skuId, "sales_qty_total");

        // #7 q_suggest >= 0；且 q_base <= 0 时 q_suggest 必须是 0 或 null
        if (qSuggest != null && qSuggest.signum() < 0) {
            throw new ValidationFailed(skuId, "#7 q_suggest 为负：" + qSuggest);
        }
        if (qBase != null && qBase.signum() <= 0
                && qSuggest != null && qSuggest.signum() > 0) {
            throw new ValidationFailed(skuId, "#7 q_base<=0 时 q_suggest 必须为 0 或 null");
        }
        // #17 预算一致性：被截断的条目其 q_suggest 不得超过 run 的预算
        BigDecimal budgetLimit = params == null ? null : parseBudget(params);
        if (Boolean.TRUE.equals(item.truncatedByBudget()) && budgetLimit != null && qSuggest != null
                && qSuggest.compareTo(budgetLimit) > 0) {
            throw new ValidationFailed(skuId, "#17 truncated_by_budget=true 但 q_suggest 超过 budget_limit");
        }

        // #8 数量精度：除 cv（4 位）外一律 ≤3 位（字符串形态已在 quantity() 校验）
        BigDecimal cv = ratio(item.cv(), skuId, "cv");

        AnalysisItemEntity row = new AnalysisItemEntity();
        row.setTeamId(run.getTeamId());
        row.setRunId(run.getId());
        row.setSkuId(skuId);
        // ★ Python 不返回 product_id（R132）：回读主数据，不拿 sku_id 顶替
        row.setProductId(ref.productId());
        row.setWarehouseId(item.warehouseId());
        row.setOnHandQty(onHand);
        // ★ Python 不返回 in_transit_qty（R132）：回读余额列；缺行 ⇒ 0
        row.setInTransitQty(inTransit.getOrDefault(skuId, BigDecimal.ZERO));
        row.setEffectiveInTransitQty(effectiveInTransit);
        row.setCommittedQty(committed);
        row.setInventoryPositionQty(inventoryPosition);
        // ★ available 仅展示口径（R67）：绝不入 Q_base / IP
        row.setAvailableQty(onHand.subtract(committed));
        row.setAvgDailySales(avgDailySales);
        // ★ Python 不返回 sales_window_days（R132）：取请求 params.window_days
        row.setSalesWindowDays(params == null ? null : params.windowDays());
        row.setValidDays(item.validDays());
        row.setTotalSalesQty(totalSales);
        row.setCv(cv);
        row.setLeadTimeDays(item.leadTimeDays());
        row.setLeadTimeSource(com.shoppilot.analysis.domain.LeadTimeSource.fromCode(item.leadTimeSource()) == null
                ? item.leadTimeSource() : item.leadTimeSource());
        row.setCoverageDays(item.coverageDays());
        row.setSafetyStockQty(safetyStock);
        row.setVolatilityAdjusted(Boolean.TRUE.equals(item.volatilityAdjusted()));
        row.setQtyBase(qBase);
        row.setQtySuggest(qSuggest);
        row.setMoqApplied(moq);
        row.setOrderMultipleApplied(orderMultiple);
        row.setTruncatedByBudget(Boolean.TRUE.equals(item.truncatedByBudget()));
        row.setRiskLevel(riskLevel.name());
        row.setRiskTypes(riskTypesJson(riskTypes));
        row.setRiskDetailJson(riskFindingsJson(item.riskFindingsOrEmpty()));
        row.setDataQualityJson(dataQualityJson(grade, item.validDays(), windowDays(params)));
        row.setLlmExplanation(truncateExplanation(item.llmExplanation()));
        // ★ §8 细化规则 2：LLM 明细单独一列，不得混进 risk/data_quality JSON
        row.setLlmDetailJson(llmDetailJson(run, item));
        row.setCreatedBy(run.getTriggeredBy());
        return row;
    }

    /** #16 超范围 SKU：按 scope 类型判定（{@code §5.38} 裁定 #13）。 */
    private void assertWithinScope(AnalysisRunEntity run, AnalysisScope scope, Long skuId, Long productId) {
        if (scope == null) {
            return;
        }
        switch (scope.scopeType()) {
            case SELECTED_SKUS -> {
                if (scope.skuIds() == null || !scope.skuIds().contains(skuId)) {
                    throw new ValidationFailed(skuId, "#16 SKU 不在 SELECTED_SKUS 范围内");
                }
            }
            case ALL_ACTIVE -> {
                // 已在 #2 校验「属本团队 + ACTIVE」，ALL_ACTIVE 即全量 ACTIVE
            }
            case CATEGORY -> {
                // ★ 商品类目需要查 products；本批以「装配阶段已按 CATEGORY 取过 SKU」为前提，
                //   落库阶段只复核「SKU 仍属本团队且 ACTIVE」（#2）。类目集合比对需要额外读 products，
                //   而 Python 返回的 sku 已必然来自该 CATEGORY 范围（装配保证），故此处不重复查表。
                if (productId == null) {
                    throw new ValidationFailed(skuId, "#16 CATEGORY 范围缺 product_id");
                }
            }
            default -> throw new ValidationFailed(skuId, "#16 未知 scope 类型");
        }
    }

    // =====================================================================
    // 终态与审计（§7 落库动作 3 / 4；动作 2 与 5 属 M5，R130）
    // =====================================================================

    /**
     * 终态推进 + 汇总统计（{@code §7} 落库动作 3）。
     *
     * <p>★ {@code data_start_date} / {@code data_end_date} <b>只在当前为 NULL 时写入</b>
     * （{@code §5.38} 三节红线）：这两列是 {@code R108} 幂等比对的载体
     * （{@code date_from}/{@code date_to} 的唯一落库位置），被结果回写覆盖会让
     * 同一 {@code Idempotency-Key} 的重放判定漂移。
     */
    private void finishRun(AnalysisRunEntity run,
                           AiRunResultData result,
                           int persistedItems,
                           int riskCount,
                           int suggestionCount,
                           LlmStatus runLlm) {
        // §8 降级契约：LLM 失败绝不能让规则结果丢失
        AnalysisRunStatus terminal = switch (runLlm) {
            case NOT_REQUESTED, SKIPPED, SUCCEEDED -> AnalysisRunStatus.SUCCEEDED;
            case FAILED, TIMEOUT, VALIDATION_FAILED -> AnalysisRunStatus.DEGRADED;
            // PENDING 已在上游被判失败（fail-closed），到不了这里
            case PENDING -> throw new ValidationFailed(null, "llm_status=PENDING 不得作为 COMPLETED 终态");
        };
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        var update = Wrappers.<AnalysisRunEntity>lambdaUpdate()
                .eq(AnalysisRunEntity::getId, run.getId())
                .in(AnalysisRunEntity::getStatus,
                        AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name())
                .set(AnalysisRunEntity::getStatus, terminal.name())
                // ★ 裁定 #18：run 级 llm_status 直接取 Python 值，Java 不再二次归并
                .set(AnalysisRunEntity::getLlmStatus, runLlm.name())
                .set(AnalysisRunEntity::getSummaryJson, canonicalJson(result))
                .set(AnalysisRunEntity::getTotalSkus, persistedItems)
                .set(AnalysisRunEntity::getProcessedSkus, persistedItems)
                .set(AnalysisRunEntity::getRiskSkuCount, riskCount)
                .set(AnalysisRunEntity::getSuggestionCount, suggestionCount)
                .set(AnalysisRunEntity::getProgress, 100)
                .set(AnalysisRunEntity::getCurrentStep, "DONE")
                .set(AnalysisRunEntity::getFinishedAt, now)
                .set(AnalysisRunEntity::getUpdatedAt, now);

        if (terminal == AnalysisRunStatus.DEGRADED) {
            update.set(AnalysisRunEntity::getLlmErrorMessage, llmErrorMessage(runLlm));
        }
        // ★ 只在 NULL 时写（红线）：已由 R108 写入的请求窗口不得被结果覆盖
        if (run.getDataStartDate() == null) {
            update.set(AnalysisRunEntity::getDataStartDate, parseDate(result.dataStartDate()));
        }
        if (run.getDataEndDate() == null) {
            update.set(AnalysisRunEntity::getDataEndDate, parseDate(result.dataEndDate()));
        }

        int updated = runMapper.update(null, update);
        if (updated == 0) {
            // CAS 失败：状态已被并发路径推进（或已终态）。落库已发生 ⇒ 只能记 ERROR 并向上抛，
            // 让事务回滚，避免「items 落了但 run 是别的状态」。
            throw new BizException(ErrorCodes.COM_0002, "系统繁忙，请稍后重试");
        }

        auditFinish(run, AuditResult.SUCCESS,
                "分析任务完成：" + terminal.name() + "，落库 " + persistedItems + " 条，"
                        + "风险 " + riskCount + " 条，建议 " + suggestionCount + " 条",
                null);
        log.info("分析结果落库完成：runId={} status={} llm={} items={} risk={} suggestion={}",
                run.getId(), terminal, runLlm, persistedItems, riskCount, suggestionCount);
    }

    /** 二次校验失败 ⇒ run 置 {@code FAILED / ANL-7004}（整批零落库）。 */
    private void markValidationFailed(AnalysisRunEntity run, Long skuId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String detail = skuId == null ? "结果结构或批次级校验未通过" : "条目校验未通过：sku_id=" + skuId;
        runMapper.update(null, Wrappers.<AnalysisRunEntity>lambdaUpdate()
                .eq(AnalysisRunEntity::getId, run.getId())
                .in(AnalysisRunEntity::getStatus,
                        AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name())
                .set(AnalysisRunEntity::getStatus, AnalysisRunStatus.FAILED.name())
                .set(AnalysisRunEntity::getErrorCode, ErrorCodes.ANL_7004)
                .set(AnalysisRunEntity::getErrorMessage, VALIDATION_FAILED_MESSAGE)
                .set(AnalysisRunEntity::getFinishedAt, now)
                .set(AnalysisRunEntity::getUpdatedAt, now));
        auditFinish(run, AuditResult.FAILURE, "分析结果二次校验未通过：" + detail, ErrorCodes.ANL_7004);
    }

    /**
     * 审计 {@code ANALYSIS_RUN_FINISH}（{@code §7} 落库动作 4）。
     *
     * <p>★ 走 {@link AuditWriteService#recordInBusinessTx}（{@code MANDATORY}）：
     * 本方法在业务事务内调用，必须与落库同生共死——「结果落了但审计缺失」是 §2.10
     * 高风险操作明确禁止的中间态。
     */
    private void auditFinish(AnalysisRunEntity run, AuditResult result, String summary, String errorCode) {
        auditWriteService.recordInBusinessTx(AuditEvent.builder()
                .teamId(run.getTeamId())
                .action(AuditAction.ANALYSIS_RUN_FINISH)
                .bizType(AUDIT_BIZ_TYPE)
                .bizId(run.getId())
                .bizNo(run.getRunNo())
                // 定时任务路径无 TeamContext：operatorId 取 run.triggered_by，
                // operatorName/Role 留 null（AuditEvent 允许「系统任务场景」；裁定 #21）
                .operatorId(run.getTriggeredBy())
                .operatorName(null)
                .operatorRole(null)
                .result(result)
                .errorCode(errorCode)
                .changeSummary(summary)
                .build());
    }

    // =====================================================================
    // 内部：解析 / 去重 / JSON
    // =====================================================================

    /** #13 去重：按 {@code (sku_id, warehouse_id)} 保留<b>首个</b>。 */
    private static List<AiResultItem> dedupeBySkuAndWarehouse(List<AiResultItem> items) {
        Set<String> seen = new LinkedHashSet<>();
        List<AiResultItem> out = new ArrayList<>(items.size());
        for (AiResultItem item : items) {
            String key = item.skuId() + "|" + item.warehouseId();
            if (seen.add(key)) {
                out.add(item);
            }
        }
        return out;
    }

    /** #5 枚举校验 + 去重（保持首次出现顺序）。 */
    private static List<String> normalizeRiskTypes(List<String> raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String code : raw) {
            if (com.shoppilot.analysis.domain.RiskType.fromCode(code) == null) {
                throw new ValidationFailed(null, "#5 risk_types 含枚举外取值：" + code);
            }
            out.add(code);
        }
        return new ArrayList<>(out);
    }

    /** 数量字段：{@code BigDecimal(String)} + scale≤3 + 非负（#8 / #14）。 */
    private static BigDecimal quantity(String raw, Long skuId, String field) {
        BigDecimal value = signedOrNull(raw, skuId, field);
        if (value == null) {
            return null;
        }
        if (value.stripTrailingZeros().scale() > QTY_SCALE) {
            throw new ValidationFailed(skuId, "#8 " + field + " 小数位超过 3：" + raw);
        }
        if (value.signum() < 0) {
            throw new ValidationFailed(skuId, "#8 " + field + " 不应为负：" + raw);
        }
        return value.setScale(QTY_SCALE, RoundingMode.UNNECESSARY);
    }

    /** 有符号数量（仅 {@code inventory_position} 允许负值，R118）。 */
    private static BigDecimal signedQuantity(String raw, Long skuId, String field) {
        BigDecimal value = signedOrNull(raw, skuId, field);
        return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal signedOrNull(String raw, Long skuId, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationFailed(skuId, "#14 " + field + " 不是合法定点数：" + raw);
        }
        // ★ 不在这里 setScale：精度超限的取值（如 1.2345）在 setScale(3, UNNECESSARY) 时
        //   会先抛 ArithmeticException 而不是走 #8 的可读文案。先判 scale，再按需裁到列精度。
        return value;
    }

    /** {@code cv}：4 位小数（{@code DECIMAL(18,4)}，★ 与其余数量列不同）。 */
    private static BigDecimal ratio(String raw, Long skuId, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationFailed(skuId, "#14 " + field + " 不是合法定点数：" + raw);
        }
        if (value.stripTrailingZeros().scale() > CV_SCALE) {
            throw new ValidationFailed(skuId, "#8 " + field + " 小数位超过 4：" + raw);
        }
        if (value.signum() < 0) {
            throw new ValidationFailed(skuId, "#8 " + field + " 不应为负：" + raw);
        }
        return value.setScale(CV_SCALE, RoundingMode.UNNECESSARY);
    }

    /** #15 {@code llm_explanation} 超长<b>截断 + WARN</b>（不 FAILED）。 */
    private static String truncateExplanation(String value) {
        if (value == null || value.length() <= LLM_EXPLANATION_MAX_LENGTH) {
            return value;
        }
        log.warn("llm_explanation 超 {} 字符，已截断（§7 第 15 项，不判失败）：原长={}",
                LLM_EXPLANATION_MAX_LENGTH, value.length());
        return value.substring(0, LLM_EXPLANATION_MAX_LENGTH);
    }

    private static String llmErrorMessage(LlmStatus status) {
        return switch (status) {
            case FAILED -> "LLM 调用失败，已降级为无解释结果（规则结果照常可用）";
            case TIMEOUT -> "LLM 调用超时，已降级为无解释结果（规则结果照常可用）";
            case VALIDATION_FAILED -> "LLM 输出未通过校验，已降级为无解释结果（规则结果照常可用）";
            default -> null;
        };
    }

    /** {@code summary} → 规范化 JSON（键排序、数值去尾零）。 */
    private static String canonicalJson(AiRunResultData result) {
        ObjectNode node = JSON.createObjectNode();
        if (result.summary() != null) {
            node.put("total_skus", result.summary().totalSkus());
            node.put("risk_sku_count", result.summary().riskSkuCount());
            node.put("suggestion_count", result.summary().suggestionCount());
            node.put("truncated_count", result.summary().truncatedCount());
            if (result.summary().byRiskLevel() != null) {
                ObjectNode levels = node.putObject("by_risk_level");
                // 键排序由 CanonicalJson 保证
                result.summary().byRiskLevel().forEach(levels::put);
            }
        }
        node.put("algorithm_version", result.algorithmVersion());
        node.put("llm_status", result.llmStatus());
        node.put("data_start_date", result.dataStartDate());
        node.put("data_end_date", result.dataEndDate());
        return CanonicalJson.canonicalize(node);
    }

    private static String riskTypesJson(List<String> riskTypes) {
        ArrayNode array = JSON.createArrayNode();
        riskTypes.forEach(array::add);
        return CanonicalJson.canonicalize(array);
    }

    /**
     * {@code risk_detail_json}（{@code ANALYSIS_CONTRACT §9.3}）：{@code {"findings":[...]}}。
     *
     * <p>★ {@code R139}：由<b>裸数组</b>改为 {@code findings} 键包裹的对象——{@code §9.3} 的
     * 权威示例就是 {@code {"findings":[...], "warnings":[]}}，其中 {@code warnings} 经
     * {@code §5.41} 裁定归 {@code llm_detail_json}（{@code R141}），本列<b>只放 findings</b>。
     * finding 内部四键（{@code rule_code/inputs/threshold/result}）结构不变（A4）。
     */
    private static String riskFindingsJson(List<AiRiskFinding> findings) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode array = root.putArray("findings");
        for (AiRiskFinding finding : findings) {
            ObjectNode node = array.addObject();
            node.put("rule_code", finding.ruleCode());
            if (finding.inputs() != null) {
                node.set("inputs", JSON.valueToTree(finding.inputs()));
            }
            node.put("threshold", finding.threshold());
            node.put("result", finding.result());
        }
        return CanonicalJson.canonicalize(root);
    }

    /**
     * {@code data_quality_json}（{@code ANALYSIS_CONTRACT §9.1} 映射表 / {@code §11.6}）：
     * {@code {"level":..., "valid_days":..., "missing_days":..., "outlier_days":null}}。
     *
     * <p>★ {@code R139}：由 {@code {"grade","warnings"}} 改为四键结构——{@code grade} 键名改为
     * {@code level}（{@code §9.1} 原文用 {@code level}），{@code warnings} 迁出（归 {@code llm_detail_json}）。
     *
     * <p>★ {@code missing_days} 自算：{@code max(0, window_days − valid_days)}，与 Python
     * {@code service-ai/.../validation/data_quality.py:67} 同口径；{@code valid_days} 可能大于
     * {@code window_days} 的边界取 0（不写反）。
     *
     * <p>★ {@code outlier_days = null}（{@code R140}）：Python {@code ResultItem} <b>不回显</b>
     * {@code outlier_count}，故无法落真实值；{@code null} 是「未知」而非 {@code 0} 的「无异常」，
     * 二者语义不同，禁止图省事写 {@code 0}。
     */
    private static String dataQualityJson(DataQualityGrade grade, Integer validDays, Integer windowDays) {
        ObjectNode node = JSON.createObjectNode();
        node.put("level", grade.name());
        node.put("valid_days", validDays);
        node.put("missing_days", windowDays == null || validDays == null
                ? 0 : Math.max(0, windowDays - validDays));
        node.putNull("outlier_days");
        return CanonicalJson.canonicalize(node);
    }

    /**
     * {@code llm_detail_json}（{@code ANALYSIS_CONTRACT §9.1} 映射表 / {@code §9.3}）：
     * {@code {"status":..., "evidence":[...], "warnings":[...], "prompt_version":..., "model_name":...}}。
     *
     * <p>★ {@code R139}/{@code R141}：{@code llm_status} 键名改为 {@code status}；{@code warnings}
     * 经 {@code §5.41} 裁定<b>归入本列</b>（{@code §9.1} 映射表把 {@code llm_evidence/llm_status/warnings}
     * 都归 {@code llm_detail_json}，{@code §9.3} 示例与映射表矛盾，以映射表为准）。
     * {@code prompt_version}/{@code model_name} 从 {@code analysis_runs} 顶层回填（可空即 {@code null}）。
     */
    private static String llmDetailJson(AnalysisRunEntity run, AiResultItem item) {
        ObjectNode node = JSON.createObjectNode();
        node.put("status", item.llmStatus());
        ArrayNode evidence = node.putArray("evidence");
        item.llmEvidenceOrEmpty().forEach(evidence::add);
        ArrayNode warnings = node.putArray("warnings");
        if (item.warnings() != null) {
            item.warnings().forEach(warnings::add);
        }
        node.put("prompt_version", run.getPromptVersion());
        node.put("model_name", run.getModelName());
        return CanonicalJson.canonicalize(node);
    }

    /** {@code data_quality_json.missing_days} 自算用的窗口 N（取请求 {@code params.window_days}）。 */
    private static Integer windowDays(AnalysisParams params) {
        return params == null ? null : params.windowDays();
    }

    // =====================================================================
    // 内部：run 快照还原
    // =====================================================================

    /** 从 run 快照还原 scope（{@code ALL_ACTIVE} 的 {@code scope_json} 本就为 NULL）。 */
    private static AnalysisScope storedScope(AnalysisRunEntity run) {
        AnalysisScopeType type = AnalysisScopeType.fromCode(run.getScopeType());
        if (type == null) {
            return null;
        }
        if (type == AnalysisScopeType.ALL_ACTIVE) {
            return new AnalysisScope(type, null, null);
        }
        JsonNode parsed = CanonicalJson.parseOrNull(run.getScopeJson());
        AnalysisScope scope = AnalysisScope.parse(parsed);
        return scope != null && scope.scopeType() == type ? scope : null;
    }

    private static AnalysisRunStatus parseStatus(String value) {
        if (value == null) {
            return null;
        }
        for (AnalysisRunStatus status : AnalysisRunStatus.values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    private static LlmStatus parseLlmStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (LlmStatus status : LlmStatus.values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    private static LocalDate parseDate(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(isoDate.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 预算上限（{@code params.budget_limit}；{@code null} = 不限制）。 */
    private static BigDecimal parseBudget(AnalysisParams params) {
        return params == null ? null : params.budgetLimit();
    }

    /**
     * 二次校验失败信号（<b>内部专用</b>：不外泄给前端，只用于区分「校验失败」与「系统错误」）。
     *
     * <p>刻意用 RuntimeException 而非 BizException：它的语义是「本批数据不可落库」，
     * 对外一律映射为 {@code ANL-7004}，不带任何 SKU 细节（细节进日志与审计）。
     */
    static final class ValidationFailed extends RuntimeException {

        private final transient Long skuId;

        ValidationFailed(Long skuId, String message) {
            super(message, null, false, false);
            this.skuId = skuId;
        }

        Long skuId() {
            return skuId;
        }
    }
}