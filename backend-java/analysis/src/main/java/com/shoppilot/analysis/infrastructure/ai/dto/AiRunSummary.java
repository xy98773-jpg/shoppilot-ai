package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 结果汇总（{@code AI_SERVICE_CONTRACT.md §3.5} 的 {@code summary}）。
 *
 * <p>★ {@code risk_sku_count} / {@code suggestion_count} <b>不被 Java 直接采用</b>：
 * 裁定 #19 要求 {@code suggestion_count} 由 Java <b>自算</b>（{@code q_suggest != null && > 0}
 * 的条数），以免与「去重后实际落库条数」不一致。
 * {@code risk_sku_count} 同样按落库后 items 自算（{@code risk_level != NONE} 的条数）。
 *
 *
 * <p><b>★ {@code @JsonProperty} 逐字段绑定（R137，P1 端到端阻断）</b>：Python 回包键名是
 * snake_case，而本记录是 Java record（组件名 camelCase）。二者<b>不会</b>自动互转，
 * 且 Jackson2ObjectMapperBuilder 默认关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * ⇒ 未标注时 snake_case 键会<b>静默</b>绑定为 {@code null}（不报错），
 * 表现为「状态永远停在 RUNNING、结果端点永不调用」。
 * 因此<b>逐字段</b>标注；<b>禁止</b>改全局 {@code @JsonNaming} 或 ObjectMapper 配置
 * （那是跨模块共享基建）。

 * @param totalSkus        总 SKU 数
 * @param riskSkuCount     有风险 SKU 数
 * @param suggestionCount  建议数（Java 落库时自算，此处仅作交叉参考）
 * @param truncatedCount   被预算截断的条数
 * @param byRiskLevel      各风险等级的 SKU 数（可能为 null）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRunSummary(
        @JsonProperty("total_skus") Integer totalSkus,
        @JsonProperty("risk_sku_count") Integer riskSkuCount,
        @JsonProperty("suggestion_count") Integer suggestionCount,
        @JsonProperty("truncated_count") Integer truncatedCount,
        @JsonProperty("by_risk_level") java.util.Map<String, Integer> byRiskLevel) {
}