package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 内部契约预检 {@code params}（{@code AI_SERVICE_CONTRACT.md §3.1}）。
 *
 * <p>预检只做数据质量分级，只消费统计窗口与最少有效天数——与 §3.1 请求体示例
 * （{@code "params": {"window_days": 28, "min_days": 7}}）逐字一致，<b>不</b>多发其余参数。
 *
 * @param windowDays 统计窗口 N
 * @param minDays    最少有效天数（判定 {@code INSUFFICIENT} 的阈值）
 */
public record AiValidateParams(
        @JsonProperty("window_days") int windowDays,
        @JsonProperty("min_days") int minDays) {
}
