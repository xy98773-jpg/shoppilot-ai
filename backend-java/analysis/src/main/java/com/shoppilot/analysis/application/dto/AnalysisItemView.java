package com.shoppilot.analysis.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.shoppilot.analysis.domain.CanonicalJson;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisItemEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * {@code GET /api/v1/analysis/runs/{id}/items} 的单项响应（{@code API_CONTRACT.md §11.6}）。
 *
 * <p>字段与 {@code §11.6} 成功响应示例<b>逐项对齐</b>（34 字段）：
 * <ul>
 *   <li>标量列直取 {@link AnalysisItemEntity}；</li>
 *   <li>数量列一律<b>字符串</b>（{@code setScale(3).toPlainString()}），{@code cv} 4 位；</li>
 *   <li>{@code risk_types} / {@code risk_findings} / {@code data_quality_json} 解析为 JSON
 *       数组/对象返回，<b>不</b>把 JSON 字符串原样塞回 {@code data}；</li>
 *   <li>{@code llm_evidence} / {@code llm_status} / {@code warnings} 从 {@code llm_detail_json}
 *       拆出返回（{@code R139/R141}：{@code warnings} 落 {@code llm_detail_json}）；</li>
 *   <li>{@code llm_explanation} 直取 {@code TEXT} 列。</li>
 * </ul>
 *
 * <p>★ <b>null 显式输出</b>：本类<b>不加</b> {@code @JsonInclude(NON_NULL)}——终态快照里
 * {@code avg_daily_sales=null}（数据不足）与 {@code qty_base=null}（未产出建议）是<b>有意义的 null</b>，
 * 契约示例即以 {@code "avg_daily_sales": null} 显式呈现，省略会与「字段不存在」混淆。
 *
 * <p>★ {@code sku_code} 非 {@code analysis_items} 列：由装配器 {@code loadSkuRefs} 回填后传入
 * （{@code M4-T13} / {@code R132} 同源，禁止 application 直连 catalog Mapper）。
 */
public record AnalysisItemView(
        @JsonProperty("id") Long id,
        @JsonProperty("run_id") Long runId,
        @JsonProperty("sku_id") Long skuId,
        @JsonProperty("sku_code") String skuCode,
        @JsonProperty("warehouse_id") Long warehouseId,
        @JsonProperty("on_hand_qty") String onHandQty,
        @JsonProperty("in_transit_qty") String inTransitQty,
        @JsonProperty("effective_in_transit_qty") String effectiveInTransitQty,
        @JsonProperty("committed_qty") String committedQty,
        @JsonProperty("inventory_position_qty") String inventoryPositionQty,
        @JsonProperty("available_qty") String availableQty,
        @JsonProperty("avg_daily_sales") String avgDailySales,
        @JsonProperty("sales_window_days") Integer salesWindowDays,
        @JsonProperty("valid_days") Integer validDays,
        @JsonProperty("total_sales_qty") String totalSalesQty,
        @JsonProperty("cv") String cv,
        @JsonProperty("lead_time_days") Integer leadTimeDays,
        @JsonProperty("lead_time_source") String leadTimeSource,
        @JsonProperty("coverage_days") Integer coverageDays,
        @JsonProperty("safety_stock_qty") String safetyStockQty,
        @JsonProperty("volatility_adjusted") Boolean volatilityAdjusted,
        @JsonProperty("qty_base") String qtyBase,
        @JsonProperty("qty_suggest") String qtySuggest,
        @JsonProperty("moq_applied") String moqApplied,
        @JsonProperty("order_multiple_applied") String orderMultipleApplied,
        @JsonProperty("truncated_by_budget") Boolean truncatedByBudget,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("risk_types") JsonNode riskTypes,
        @JsonProperty("risk_findings") JsonNode riskFindings,
        @JsonProperty("data_quality_json") JsonNode dataQualityJson,
        @JsonProperty("llm_explanation") String llmExplanation,
        @JsonProperty("llm_evidence") JsonNode llmEvidence,
        @JsonProperty("llm_status") String llmStatus,
        @JsonProperty("warnings") JsonNode warnings) {

    /** 数量列 3 位小数（{@code DECIMAL(18,3)}，与落库时 {@code UNNECESSARY} 同口径）。 */
    private static final int QTY_SCALE = 3;

    /** {@code cv} 4 位小数（{@code DECIMAL(18,4)}，★ 与其余数量列不同）。 */
    private static final int CV_SCALE = 4;

    /** 从实体 + 回填的 {@code sku_code} 组装（{@code null} 列直出 {@code null}）。 */
    public static AnalysisItemView from(AnalysisItemEntity e, String skuCode) {
        JsonNode riskDetail = CanonicalJson.parseOrNull(e.getRiskDetailJson());
        JsonNode llmDetail = CanonicalJson.parseOrNull(e.getLlmDetailJson());
        return new AnalysisItemView(
                e.getId(),
                e.getRunId(),
                e.getSkuId(),
                skuCode,
                e.getWarehouseId(),
                decimal(e.getOnHandQty(), QTY_SCALE),
                decimal(e.getInTransitQty(), QTY_SCALE),
                decimal(e.getEffectiveInTransitQty(), QTY_SCALE),
                decimal(e.getCommittedQty(), QTY_SCALE),
                decimal(e.getInventoryPositionQty(), QTY_SCALE),
                decimal(e.getAvailableQty(), QTY_SCALE),
                decimal(e.getAvgDailySales(), QTY_SCALE),
                e.getSalesWindowDays(),
                e.getValidDays(),
                decimal(e.getTotalSalesQty(), QTY_SCALE),
                decimal(e.getCv(), CV_SCALE),
                e.getLeadTimeDays(),
                e.getLeadTimeSource(),
                e.getCoverageDays(),
                decimal(e.getSafetyStockQty(), QTY_SCALE),
                e.getVolatilityAdjusted(),
                decimal(e.getQtyBase(), QTY_SCALE),
                decimal(e.getQtySuggest(), QTY_SCALE),
                decimal(e.getMoqApplied(), QTY_SCALE),
                decimal(e.getOrderMultipleApplied(), QTY_SCALE),
                e.getTruncatedByBudget(),
                e.getRiskLevel(),
                CanonicalJson.parseOrNull(e.getRiskTypes()),
                // risk_findings = risk_detail_json.findings（R139：外层是 {"findings":[...]}）
                findings(riskDetail),
                // data_quality_json 直接解析为对象返回（{level, valid_days, missing_days, outlier_days}）
                CanonicalJson.parseOrNull(e.getDataQualityJson()),
                e.getLlmExplanation(),
                llmArray(llmDetail, "evidence"),
                llmText(llmDetail, "status"),
                llmArray(llmDetail, "warnings"));
    }

    /** 定点字符串；{@code null} 透传。 */
    private static String decimal(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** {@code risk_detail_json.findings}；缺列/非对象时 {@code null}。 */
    private static JsonNode findings(JsonNode riskDetail) {
        return riskDetail == null || !riskDetail.isObject() ? null : riskDetail.get("findings");
    }

    /** {@code llm_detail_json.<key>}（数组）；缺列/非对象时 {@code null}。 */
    private static JsonNode llmArray(JsonNode llmDetail, String key) {
        return llmDetail == null || !llmDetail.isObject() ? null : llmDetail.get(key);
    }

    /** {@code llm_detail_json.<key>}（文本）；缺列/非对象时 {@code null}。 */
    private static String llmText(JsonNode llmDetail, String key) {
        JsonNode node = llmArray(llmDetail, key);
        return node == null || node.isNull() ? null : node.asText();
    }
}
