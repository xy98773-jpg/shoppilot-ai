package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shoppilot.analysis.domain.AnalysisParams;

/**
 * 内部契约 {@code params} 对象（{@code AI_SERVICE_CONTRACT.md §3.2}）。
 *
 * <p>★ <b>数值一致性（{@code §4.1/§4.2}）</b>：比率与预算字段<b>用字符串传输</b>——
 * {@code ss_ratio}/{@code cv_high} 固定 4 位小数，{@code budget_limit} 固定 3 位小数；
 * 整数字段（窗口/天数）用 JSON 整数。禁 IEEE-754 裸传，禁科学计数法。
 *
 * <p>{@code budget_limit} 为 {@code null} 时整体省略（{@code §4.5}：可空 decimal 区分「无值」与「0」，
 * 由 Python 侧 Optional 缺省语义承接）。
 *
 * @param windowDays      统计窗口 N
 * @param minDays         最少有效天数
 * @param coverageDays    覆盖天数 R
 * @param leadTimeDefault 默认交期
 * @param ssRatio         SAFETY_RATIO（4 位小数字符串）
 * @param cvHigh          CV_HIGH（4 位小数字符串）
 * @param budgetLimit     预算上限（3 位小数字符串；{@code null} = 不限制）
 * @param enableLlm       是否启用 LLM 解释
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiParams(
        @JsonProperty("window_days") int windowDays,
        @JsonProperty("min_days") int minDays,
        @JsonProperty("coverage_days") int coverageDays,
        @JsonProperty("lead_time_default") int leadTimeDefault,
        @JsonProperty("ss_ratio") String ssRatio,
        @JsonProperty("cv_high") String cvHigh,
        @JsonProperty("budget_limit") String budgetLimit,
        @JsonProperty("enable_llm") boolean enableLlm) {

    /** 从域值对象转换（精度收敛到契约位数）。 */
    public static AiParams from(AnalysisParams params) {
        return new AiParams(
                params.windowDays(),
                params.minDays(),
                params.coverageDays(),
                params.leadTimeDefault(),
                params.ssRatioAsContract(),
                params.cvHighAsContract(),
                params.budgetLimitAsContract(),
                params.enableLlm());
    }
}
