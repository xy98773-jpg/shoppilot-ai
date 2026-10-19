package com.shoppilot.analysis.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 分析运行参数值对象（{@code analysis_runs.params_json} 与内部契约 {@code params}）。
 *
 * <p>权威依据 {@code ANALYSIS_CONTRACT.md §1.1}（逐字段类型/必填/约束/默认）与
 * {@code DESIGN_BASIS.md §10.6}（默认值总表）：
 *
 * <table>
 *   <caption>参数口径（原文照抄）</caption>
 *   <tr><th>字段</th><th>约束</th><th>默认</th></tr>
 *   <tr><td>{@code window_days}</td><td>int 1..365</td><td>28</td></tr>
 *   <tr><td>{@code min_days}</td><td>int 1..window_days</td><td>7</td></tr>
 *   <tr><td>{@code coverage_days}</td><td>int 0..365</td><td>7</td></tr>
 *   <tr><td>{@code lead_time_default}</td><td>int 0..365</td><td>7</td></tr>
 *   <tr><td>{@code ss_ratio}</td><td>decimal4 ≥0</td><td>0.5</td></tr>
 *   <tr><td>{@code cv_high}</td><td>decimal4 ≥0</td><td>1.0</td></tr>
 *   <tr><td>{@code budget_limit}</td><td>decimal3 ≥0 或 null</td><td>null（不限制）</td></tr>
 *   <tr><td>{@code enable_llm}</td><td>bool</td><td>true</td></tr>
 * </table>
 *
 * <p>越界/类型错一律 {@code ANL-7009}（{@code ERROR_CODES.md §2.8}：分析参数非法），
 * <b>不</b>降级为 {@code COM-0001}——这是任务书 §三.5 的明文口径（{@code window_days} /
 * {@code min_days} / {@code coverage_days} 越界 → {@code ANL-7009}；其余数值边界同族归并）。
 *
 * <p>缺省即补默认值并<b>显式落库</b>（{@code params_json} 存全量八字段）——快照自包含，
 * 历史 run 不随后续默认值调整而变化（{@code ANALYSIS_CONTRACT.md §3.2}「默认值变更也算变更」）。
 *
 * @param windowDays      统计窗口 N
 * @param minDays         最少有效天数 MIN_DAYS
 * @param coverageDays    覆盖天数 R
 * @param leadTimeDefault 全局默认交期
 * @param ssRatio         SAFETY_RATIO（4 位小数精度）
 * @param cvHigh          CV_HIGH（4 位小数精度）
 * @param budgetLimit     预算上限（3 位小数精度；{@code null} = 不限制）
 * @param enableLlm       是否启用 LLM 解释
 */
public record AnalysisParams(Integer windowDays, Integer minDays, Integer coverageDays,
                             Integer leadTimeDefault, BigDecimal ssRatio, BigDecimal cvHigh,
                             BigDecimal budgetLimit, Boolean enableLlm) {

    /** {@code DESIGN_BASIS.md §10.6}：统计窗口默认 28 天。 */
    public static final int DEFAULT_WINDOW_DAYS = 28;
    /** {@code DESIGN_BASIS.md §10.6}：最少有效天数默认 7 天。 */
    public static final int DEFAULT_MIN_DAYS = 7;
    /** {@code DESIGN_BASIS.md §10.6}：覆盖天数默认 7 天。 */
    public static final int DEFAULT_COVERAGE_DAYS = 7;
    /** {@code DESIGN_BASIS.md §10.6}：默认交期 7 天。 */
    public static final int DEFAULT_LEAD_TIME_DAYS = 7;
    /** {@code DESIGN_BASIS.md §10.3}：SAFETY_RATIO 默认 0.5。 */
    public static final BigDecimal DEFAULT_SS_RATIO = new BigDecimal("0.5");
    /** {@code DESIGN_BASIS.md §10.6}：CV_HIGH 默认 1.0。 */
    public static final BigDecimal DEFAULT_CV_HIGH = new BigDecimal("1.0");

    /** 内部契约比率字段的小数位（{@code AI_SERVICE_CONTRACT.md §4.1}：比率 4 位，字符串传输）。 */
    static final int RATIO_SCALE = 4;
    /** 内部契约预算字段的小数位（{@code ANALYSIS_CONTRACT.md §1.1}：{@code budget_limit} decimal3）。 */
    static final int BUDGET_SCALE = 3;

    /**
     * 从外部请求的 {@code params} 解析并套用默认值。
     *
     * @param paramsJson 请求体 {@code params}（可为 {@code null}——全部取默认）
     * @throws BizException {@code ANL-7009}（任一字段越界/类型错）
     */
    public static AnalysisParams resolve(JsonNode paramsJson) {
        if (paramsJson == null || paramsJson.isNull()) {
            return new AnalysisParams(DEFAULT_WINDOW_DAYS, DEFAULT_MIN_DAYS, DEFAULT_COVERAGE_DAYS,
                    DEFAULT_LEAD_TIME_DAYS, DEFAULT_SS_RATIO, DEFAULT_CV_HIGH, null, Boolean.TRUE);
        }
        if (!paramsJson.isObject()) {
            throw paramError("params", "params 必须是 JSON 对象");
        }
        int windowDays = intField(paramsJson, "window_days", DEFAULT_WINDOW_DAYS, 1, 365);
        int minDays = intField(paramsJson, "min_days", DEFAULT_MIN_DAYS, 1, windowDays);
        int coverageDays = intField(paramsJson, "coverage_days", DEFAULT_COVERAGE_DAYS, 0, 365);
        int leadTimeDefault = intField(paramsJson, "lead_time_default", DEFAULT_LEAD_TIME_DAYS, 0, 365);
        BigDecimal ssRatio = decimalField(paramsJson, "ss_ratio", DEFAULT_SS_RATIO, true);
        BigDecimal cvHigh = decimalField(paramsJson, "cv_high", DEFAULT_CV_HIGH, true);
        BigDecimal budgetLimit = decimalField(paramsJson, "budget_limit", null, false);
        Boolean enableLlm = boolField(paramsJson, "enable_llm", Boolean.TRUE);
        return new AnalysisParams(windowDays, minDays, coverageDays, leadTimeDefault,
                ssRatio, cvHigh, budgetLimit, enableLlm);
    }

    /**
     * 入库用 {@code params_json} 规范化文本（全量八字段、键序字典序、数值去尾零）。
     *
     * <p>与 {@link CanonicalJson#canonicalize} 同构：手工拼装而非回环 Jackson，
     * 保证「落库文本 == 幂等比对基准」零偏差。
     */
    public String canonicalJson() {
        return "{\"budget_limit\":" + numberOrNull(budgetLimit)
                + ",\"coverage_days\":" + coverageDays
                + ",\"cv_high\":" + cvHigh.stripTrailingZeros().toPlainString()
                + ",\"enable_llm\":" + enableLlm
                + ",\"lead_time_default\":" + leadTimeDefault
                + ",\"min_days\":" + minDays
                + ",\"ss_ratio\":" + ssRatio.stripTrailingZeros().toPlainString()
                + ",\"window_days\":" + windowDays + "}";
    }

    /** {@code ss_ratio}/{@code cv_high} 的内部契约字符串形态（固定 4 位小数）。 */
    public String ssRatioAsContract() {
        return ssRatio.setScale(RATIO_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code cv_high} 的内部契约字符串形态（固定 4 位小数）。 */
    public String cvHighAsContract() {
        return cvHigh.setScale(RATIO_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code budget_limit} 的内部契约字符串形态（固定 3 位小数；不限制时为 {@code null}）。 */
    public String budgetLimitAsContract() {
        return budgetLimit == null
                ? null
                : budgetLimit.setScale(BUDGET_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String numberOrNull(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    // =====================================================================
    // 内部：逐字段解析（越界/类型错 → ANL-7009 + details[field=params.xxx]）
    // =====================================================================

    private static int intField(JsonNode params, String field, int defaultValue, int min, int max) {
        JsonNode node = params.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isIntegralNumber()) {
            throw paramError(field, "必须为整数");
        }
        int value = node.intValue();
        if (value < min || value > max) {
            throw paramError(field, "必须在 " + min + ".." + max + " 之间");
        }
        return value;
    }

    private static BigDecimal decimalField(JsonNode params, String field,
                                           BigDecimal defaultValue, boolean required) {
        JsonNode node = params.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                return defaultValue;
            }
            return null; // budget_limit：缺省即不限制
        }
        if (!node.isNumber()) {
            throw paramError(field, "必须为数值");
        }
        BigDecimal value;
        try {
            value = node.decimalValue();
        } catch (RuntimeException ex) {
            throw paramError(field, "必须为数值");
        }
        if (value.signum() < 0) {
            throw paramError(field, "必须 ≥ 0");
        }
        return value;
    }

    private static Boolean boolField(JsonNode params, String field, Boolean defaultValue) {
        JsonNode node = params.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw paramError(field, "必须为布尔值");
        }
        return node.booleanValue();
    }

    private static BizException paramError(String field, String reason) {
        List<ApiErrorDetail> details = new ArrayList<>(1);
        details.add(ApiErrorDetail.of("params." + field, reason));
        return BizException.withDetails(ErrorCodes.ANL_7009, "分析参数不合法", details);
    }
}
