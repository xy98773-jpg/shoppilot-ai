package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 单 SKU 结果条目（{@code AI_SERVICE_CONTRACT.md §3.5} 的 {@code items[]} 一项；28 字段）。
 *
 * <h2>★ 数量字段一律 {@link String}</h2>
 * <p>{@code §4.1} 数值一致性约定：数量/比率以<b>定点字符串</b>传输（{@code "8.000"}），
 * 禁止科学计数法、禁止浮点。Java 侧用 {@code new BigDecimal(String)} 解析
 * （{@code D-12}），{@code null} 表示「该值在本轮不存在」（数据不足 / 未产出建议）。
 *
 * <p>★ {@code inventoryPosition} <b>允许负值</b>（超卖，{@code R118}）：
 * 表列 {@code analysis_items.inventory_position_qty DECIMAL(18,3)} 是有符号的，
 * 且契约 §2.1 对它只要求「与三项一致」、未要求 {@code >= 0}。
 *
 * <p>★ Python <b>不</b>回显的三个值由 Java 回读主数据补齐（{@code R132}）：
 * {@code product_id}（← {@code skus.product_id}）、{@code in_transit_qty}
 * （← {@code inventory_balances.in_transit_qty}，与 {@code R124} 同源）、
 * {@code sales_window_days}（← 请求 {@code params.window_days}）。
 *
 * @param skuId                  SKU ID
 * @param warehouseId            仓库 ID
 * @param avgDailySales          日均销量 D（数据不足为 null）
 * @param salesQtyTotal          窗口总销量
 * @param validDays              有效天数
 * @param dataQuality            数据质量档（4 值）
 * @param riskLevel              风险等级（5 值）
 * @param riskTypes              风险类型数组（6 值，可能为空数组）
 * @param riskFindings           规则判定明细（已判定规则，含 NOT_TRIGGERED）
 * @param onHandQty              在手库存
 * @param effectiveInTransitQty  有效在途
 * @param committedQty           已承诺
 * @param inventoryPosition      IP = 在手 + 有效在途 − 已承诺（<b>可为负</b>）
 * @param safetyStock            安全库存 SS
 * @param leadTimeDays           采用交期 L
 * @param leadTimeSource         交期来源（4 值）
 * @param coverageDays           覆盖天数 R
 * @param cv                     波动率（<b>4 位小数</b>；mean=0 时为 null）
 * @param volatilityAdjusted     是否因高波动上浮 50%
 * @param qBase                  Q_base（可为 null）
 * @param moqApplied             生效 MOQ
 * @param orderMultipleApplied   生效倍数
 * @param qSuggest               Q_suggest（可为 null）
 * @param truncatedByBudget      是否因预算截断
 * @param llmExplanation         LLM 解释（降级为 null；<b>落库前截断 2000 字符</b>）
 * @param llmEvidence            解释引用的输入字段名
 * @param llmStatus              条目级 LLM 状态
 * @param warnings               该条目的提示
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiResultItem(
        Long skuId,
        Long warehouseId,
        String avgDailySales,
        String salesQtyTotal,
        Integer validDays,
        String dataQuality,
        String riskLevel,
        List<String> riskTypes,
        List<AiRiskFinding> riskFindings,
        String onHandQty,
        String effectiveInTransitQty,
        String committedQty,
        String inventoryPosition,
        String safetyStock,
        Integer leadTimeDays,
        String leadTimeSource,
        Integer coverageDays,
        String cv,
        Boolean volatilityAdjusted,
        String qBase,
        String moqApplied,
        String orderMultipleApplied,
        String qSuggest,
        Boolean truncatedByBudget,
        String llmExplanation,
        List<String> llmEvidence,
        String llmStatus,
        List<String> warnings) {

    /** {@code risk_types} 缺省按空数组（契约声明该字段非空，可为空数组）。 */
    public List<String> riskTypesOrEmpty() {
        return riskTypes == null ? List.of() : riskTypes;
    }

    /** {@code llm_evidence} 缺省按空数组。 */
    public List<String> llmEvidenceOrEmpty() {
        return llmEvidence == null ? List.of() : llmEvidence;
    }

    /** {@code risk_findings} 缺省按空数组（INSUFFICIENT 条目仍会有 1 条 DATAINSUFFICIENT）。 */
    public List<AiRiskFinding> riskFindingsOrEmpty() {
        return riskFindings == null ? List.of() : riskFindings;
    }
}