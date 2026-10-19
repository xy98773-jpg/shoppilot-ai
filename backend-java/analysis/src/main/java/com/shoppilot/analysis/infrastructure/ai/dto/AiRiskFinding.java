package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * 单条规则判定明细（{@code AI_SERVICE_CONTRACT.md §3.5} 的 {@code risk_findings[]} 一项）。
 *
 * <p>★ 与 {@code RuleCode}（{@code R-LOWSTOCK} 等）是<b>同一张规则表</b>的标识，
 * 与 {@link com.shoppilot.analysis.domain.RiskType}（{@code LOW_STOCK}）<b>不是</b>一套值空间
 * （{@code R115}）。本记录用 {@link String} 承载 {@code rule_code} 与 {@code result}，
 * 不在 Java 侧建枚举——规则表会随 M5/M6 扩展，Java 落库只需原样保存。
 *
 * <p>落库时进 {@code analysis_items.risk_detail_json}（{@code §8} 细化规则 2：
 * <b>不得</b>与 {@code llm_detail_json} / {@code data_quality_json} 混装）。
 *
 * @param ruleCode  规则编码（如 {@code R-LOWSTOCK}）
 * @param inputs    判定输入（数值一律字符串 / 整数）
 * @param threshold 阈值描述（<b>必须含实际数值</b>，供前端展示「为何命中」）
 * @param result    判定结果（TRIGGERED / NOT_TRIGGERED / UNKNOWN）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRiskFinding(
        String ruleCode,
        Map<String, Object> inputs,
        String threshold,
        String result) {
}