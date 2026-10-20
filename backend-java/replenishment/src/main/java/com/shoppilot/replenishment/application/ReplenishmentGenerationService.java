package com.shoppilot.replenishment.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shoppilot.analysis.application.AnalysisSnapshotQueryService;
import com.shoppilot.catalog.application.SkuQueryService;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentItemEntity;
import com.shoppilot.replenishment.infrastructure.persistence.mapper.ReplenishmentItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 补货建议生成服务（M5-T02）：由分析明细派生 {@code SUGGESTED} 建议。
 *
 * <h2>边界（{@code MODULE_BOUNDARIES §8.2 E-2}）</h2>
 * <p>入口归属 {@code replenishment}，<b>禁止</b> {@code analysis → replenishment} 反向依赖。
 * 本服务<b>不直接注入</b> {@code AnalysisItemMapper} / {@code SkuMapper} / {@code ProductMapper}
 * ——那是 {@code R126} 的同型债。跨模块数据一律经对方 application 层的只读入口获取：
 * <ul>
 *   <li>{@link AnalysisSnapshotQueryService#listGenerationSource(Long)} —— run 元信息 + 待生成快照；</li>
 *   <li>{@link SkuQueryService#listProcurementInfo(java.util.Collection)} —— 供应商/单价的批量只读视图。</li>
 * </ul>
 *
 * <h2>本批<b>不做</b>什么</h2>
 * <ul>
 *   <li><b>不写审计</b>：{@code DESIGN_BASIS §9.17} 的 35 条 {@code AuditAction} 里没有「生成建议」，
 *       生成是派生动作而非人工操作；擅增会被 {@code AuditAction} 一致性测试打红。</li>
 *   <li><b>不置 {@code SUPERSEDED}</b>：旧建议取代属 M5-T06。</li>
 *   <li><b>不做状态机流转、不开 API</b>：M5-T03 起。</li>
 * </ul>
 *
 * <h2>幂等与事务</h2>
 * <p>先查「该 run 是否已有建议」，有则<b>直接返回既有结果</b>（不插入、不部分插入）——
 * {@code uk_repl_run_sku_wh} 的语义是「一次运行对每 SKU×仓库至多一条建议」，
 * 重复调用应幂等而非报错。整批插入在同一事务内，任一失败全回滚（不落半份建议）。
 *
 * <p>★ 本服务只 INSERT：{@code ReplenishmentItemEntity} 带 {@code @Version}，
 * 更新必须走 {@code updateById(entity)} 并判返回值（M5-T03 起）；
 * <b>禁止</b> {@code update(null, wrapper)}（绕过乐观锁，{@code R62}）。
 */
@Service
public class ReplenishmentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentGenerationService.class);

    /** {@code replenishment_items.reason} 列宽（{@code VARCHAR(500)}，按字符截断）。 */
    private static final int REASON_MAX_LENGTH = 500;

    /** 允许生成建议的运行状态（{@code DESIGN_BASIS §9.11} / {@code AI_SERVICE_CONTRACT}：DEGRADED 业务可用）。 */
    private static final Set<String> GENERATABLE_RUN_STATUS = Set.of("SUCCEEDED", "DEGRADED");

    /** 初始状态常量（{@code ANALYSIS_CONTRACT §9.2}）。 */
    private static final String STATUS_SUGGESTED = "SUGGESTED";

    /** 可被新一轮分析取代的旧状态（{@code §9.11} 补充规则 2：只有这两个）。 */
    private static final Set<String> SUPERSEDABLE_STATUS = Set.of("SUGGESTED", "PENDING_REVIEW");

    private static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private final AnalysisSnapshotQueryService snapshotQueryService;
    private final SkuQueryService skuQueryService;
    private final ReplenishmentItemMapper itemMapper;

    public ReplenishmentGenerationService(AnalysisSnapshotQueryService snapshotQueryService,
                                          SkuQueryService skuQueryService,
                                          ReplenishmentItemMapper itemMapper) {
        this.snapshotQueryService = snapshotQueryService;
        this.skuQueryService = skuQueryService;
        this.itemMapper = itemMapper;
    }

    /**
     * 由 {@code runId} 派生补货建议（幂等）。
     *
     * <p>前置校验顺序（固定）：
     * <ol>
     *   <li>{@code runId} 非空 ⇒ 否则 {@code COM-0001}（400，details.field=run_id）；</li>
     *   <li>run 存在且属于当前团队 ⇒ 否则 {@code ANL-7005}（404，与 {@code GET /runs/{id}} 同码，
     *       租户线使「不存在」与「跨团队」同表现，不泄漏他团队是否存在）；</li>
     *   <li>{@code run.status ∈ {SUCCEEDED, DEGRADED}} ⇒ 否则 {@code RPL-8001}（422，
     *       「当前状态不允许该操作」，{@code ERROR_CODES §2.9}）。</li>
     * </ol>
     *
     * @param runId 分析运行 ID
     * @return 生成结果（幂等命中时 {@code idempotent=true}）
     * @throws BizException 见上；插入失败时由事务回滚并原样上抛
     */
    @Transactional(rollbackFor = Exception.class)
    public ReplenishmentGenerationResult generateFromRun(Long runId) {
        if (runId == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("run_id", "run_id 必填")));
        }

        AnalysisSnapshotQueryService.GenerationSource source =
                snapshotQueryService.listGenerationSource(runId);
        if (source == null) {
            throw new BizException(ErrorCodes.ANL_7005, "分析任务不存在");
        }
        if (!GENERATABLE_RUN_STATUS.contains(source.status())) {
            throw new BizException(ErrorCodes.RPL_8001, "当前状态不允许该操作");
        }

        // A2 幂等：先查后插（不以唯一键撞键兜底，避免半份数据）
        Long existing = itemMapper.selectCount(
                Wrappers.<ReplenishmentItemEntity>lambdaQuery()
                        .eq(ReplenishmentItemEntity::getRunId, runId));
        if (existing != null && existing > 0) {
            log.info("补货建议已存在，跳过生成：runId={} existing={}", runId, existing);
            return new ReplenishmentGenerationResult(runId, 0, existing.intValue(), true);
        }

        // A3：快照已由 analysis 侧按 qty_suggest > 0 过滤；空结果合法（返回 0，不报错）
        if (source.items().isEmpty()) {
            log.info("本次运行无 qty_suggest>0 的快照，未生成建议：runId={}", runId);
            return new ReplenishmentGenerationResult(runId, 0, 0, false);
        }

        Set<Long> skuIds = source.items().stream()
                .map(AnalysisSnapshotQueryService.GenerationItem::skuId)
                .collect(Collectors.toSet());
        Map<Long, SkuQueryService.ProcurementInfo> procurement = skuQueryService.listProcurementInfo(skuIds);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long operatorId = currentUserId();
        List<ReplenishmentItemEntity> inserted = new ArrayList<>();
        for (AnalysisSnapshotQueryService.GenerationItem item : source.items()) {
            ReplenishmentItemEntity entity = toEntity(source, item, procurement.get(item.skuId()),
                    today, operatorId);
            itemMapper.insert(entity);
            inserted.add(entity);
        }
        // ★ M5-T06：新建议全部插入之后，同一事务内把「同 SKU×仓库」的未审批旧建议置 SUPERSEDED
        supersedeOldSuggestions(source.runId(), inserted, now, operatorId);
        log.info("补货建议生成完成：runId={} created={}", runId, inserted.size());
        return new ReplenishmentGenerationResult(runId, inserted.size(), 0, false);
    }

    /**
     * 新 run 取代旧建议（M5-T06，{@code §5.59} 裁定 3–5）。
     *
     * <p>对每条<b>新建议</b>，把同 {@code (team_id, sku_id, warehouse_id)} 下<b>其它</b>且状态 ∈
     * {@code {SUGGESTED, PENDING_REVIEW}} 的建议置 {@code SUPERSEDED} + {@code superseded_by_item_id=新建议 id}
     * + {@code closed_at=now(UTC)}。
     *
     * <h2>三条硬口径</h2>
     * <ul>
     *   <li><b>{@code APPROVED}/{@code CONVERTED}/{@code REJECTED} 一律不动</b>：
     *       {@code §9.11} 补充规则 2/3——已产生业务后果的状态不得被数据更新覆盖；</li>
     *   <li><b>不写 {@code replenishment_reviews}</b>：{@code ReviewAction} 只有 5 值、无 {@code SUPERSEDE}，
     *       系统派生动作不是人工审核记录（自造第 6 值会撞 {@code ck_rr_action}）；</li>
     *   <li><b>不写审计</b>：{@code §9.17} 的 35 条 {@code AuditAction} 无「取代」动作，
     *       也不得用 {@code REPLENISHMENT_CANCEL} 顶替（语义不同）。</li>
     * </ul>
     *
     * <p>★ 写路径仍走 {@code updateById(entity)}（{@code @Version} CAS），<b>不用</b>
     * {@code update(null, wrapper)}。若某条旧建议刚被他人审批/撤回导致版本不匹配（影响 0 行），
     * <b>跳过该条</b>而不是让整批生成失败——取代是派生动作，不该阻塞新建议落库。
     *
     * <p>候选集用一次 {@code IN} 查询（sku × warehouse 的笛卡尔近似）后在内存按
     * {@code (skuId, warehouseId)} 精确配对，避免 N+1。
     */
    private void supersedeOldSuggestions(Long runId, List<ReplenishmentItemEntity> inserted,
                                         LocalDateTime now, Long operatorId) {
        if (inserted.isEmpty()) {
            return;
        }
        Map<String, Long> newIdByKey = new HashMap<>();
        Set<Long> skuIds = new HashSet<>();
        Set<Long> warehouseIds = new HashSet<>();
        for (ReplenishmentItemEntity item : inserted) {
            newIdByKey.put(keyOf(item.getSkuId(), item.getWarehouseId()), item.getId());
            skuIds.add(item.getSkuId());
            warehouseIds.add(item.getWarehouseId());
        }

        List<ReplenishmentItemEntity> candidates = itemMapper.selectList(
                Wrappers.<ReplenishmentItemEntity>lambdaQuery()
                        .in(ReplenishmentItemEntity::getSkuId, skuIds)
                        .in(ReplenishmentItemEntity::getWarehouseId, warehouseIds)
                        .in(ReplenishmentItemEntity::getStatus, SUPERSEDABLE_STATUS)
                        .ne(ReplenishmentItemEntity::getRunId, runId));

        int superseded = 0;
        for (ReplenishmentItemEntity old : candidates) {
            Long newId = newIdByKey.get(keyOf(old.getSkuId(), old.getWarehouseId()));
            if (newId == null) {
                continue;
            }
            old.setStatus(STATUS_SUPERSEDED);
            old.setSupersededByItemId(newId);
            old.setClosedAt(now);
            old.setUpdatedBy(operatorId);
            int affected = itemMapper.updateById(old);
            if (affected == 0) {
                log.info("旧建议版本已变（并发审批/撤回），跳过取代：id={}", old.getId());
                continue;
            }
            superseded++;
        }
        log.info("旧建议取代完成：runId={} superseded={}", runId, superseded);
    }

    private static String keyOf(Long skuId, Long warehouseId) {
        return skuId + ":" + warehouseId;
    }

    /**
     * 快照 → 建议实体（{@code ANALYSIS_CONTRACT §9.2} 逐列映射）。
     *
     * <p>★ {@code suggested_qty} 与 {@code qty} 都要写：前者是不可变原值（用于对比人工改量幅度），
     * 后者是运营可改的当前生效量，初始二者相等。
     */
    private ReplenishmentItemEntity toEntity(AnalysisSnapshotQueryService.GenerationSource source,
                                             AnalysisSnapshotQueryService.GenerationItem item,
                                             SkuQueryService.ProcurementInfo procurement,
                                             LocalDate today,
                                             Long operatorId) {
        ReplenishmentItemEntity entity = new ReplenishmentItemEntity();
        entity.setTeamId(source.teamId());
        entity.setSourceAnalysisItemId(item.id());
        entity.setRunId(source.runId());
        entity.setSkuId(item.skuId());
        entity.setProductId(item.productId());
        entity.setWarehouseId(item.warehouseId());
        entity.setStatus(STATUS_SUGGESTED);
        entity.setSuggestedQty(item.qtySuggest());
        entity.setQty(item.qtySuggest());
        entity.setRiskLevel(item.riskLevel() == null ? "NONE" : item.riskLevel());
        entity.setReason(buildReason(item));
        entity.setSupplierId(resolveSupplierId(procurement));
        entity.setPurchasePrice(procurement == null ? null : procurement.purchasePrice());
        entity.setExpectedArrivalDate(item.leadTimeDays() == null
                ? null : today.plusDays(item.leadTimeDays()));
        // remark 不填（A4：保持 NULL）
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        return entity;
    }

    /**
     * 供应商回退链（{@code ANALYSIS_CONTRACT §9.2}）：{@code skus.supplier_id} →
     * {@code products.default_supplier_id} → {@code null}。
     */
    private static Long resolveSupplierId(SkuQueryService.ProcurementInfo procurement) {
        if (procurement == null) {
            return null;
        }
        if (procurement.supplierId() != null) {
            return procurement.supplierId();
        }
        return procurement.productDefaultSupplierId();
    }

    /**
     * 建议理由摘要（{@code VARCHAR(500)}）：LLM 解释优先并按<b>字符</b>截断；为空时用规则模板。
     *
     * <p>★ 规则模板固定、可测：{@code "{风险}风险，建议补货 {数量} 件"}（{@code §9.2} 示例口径）。
     * 数量用 {@code stripTrailingZeros().toPlainString()}，{@code 110.000 → "110"}。
     */
    private static String buildReason(AnalysisSnapshotQueryService.GenerationItem item) {
        String llm = item.llmExplanation();
        if (llm != null && !llm.isBlank()) {
            return llm.length() > REASON_MAX_LENGTH ? llm.substring(0, REASON_MAX_LENGTH) : llm;
        }
        return riskLabel(item.riskLevel()) + "风险，建议补货 " + plain(item.qtySuggest()) + " 件";
    }

    private static String riskLabel(String riskLevel) {
        if (riskLevel == null) {
            return "未知";
        }
        return switch (riskLevel) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            case "LOW" -> "低";
            case "NONE" -> "无";
            default -> "未知";
        };
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static Long currentUserId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        return snapshot == null ? null : snapshot.userId();
    }
}
