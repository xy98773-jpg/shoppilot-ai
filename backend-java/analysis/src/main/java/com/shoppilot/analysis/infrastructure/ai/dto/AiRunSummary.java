package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 结果汇总（{@code AI_SERVICE_CONTRACT.md §3.5} 的 {@code summary}）。
 *
 * <p>★ {@code risk_sku_count} / {@code suggestion_count} <b>不被 Java 直接采用</b>：
 * 裁定 #19 要求 {@code suggestion_count} 由 Java <b>自算</b>（{@code q_suggest != null && > 0}
 * 的条数），以免与「去重后实际落库条数」不一致。
 * {@code risk_sku_count} 同样按落库后 items 自算（{@code risk_level != NONE} 的条数）。
 *
 * @param totalSkus        总 SKU 数
 * @param riskSkuCount     有风险 SKU 数
 * @param suggestionCount  建议数（Java 落库时自算，此处仅作交叉参考）
 * @param truncatedCount   被预算截断的条数
 * @param byRiskLevel      各风险等级的 SKU 数（可能为 null）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRunSummary(
        Integer totalSkus,
        Integer riskSkuCount,
        Integer suggestionCount,
        Integer truncatedCount,
        java.util.Map<String, Integer> byRiskLevel) {
}