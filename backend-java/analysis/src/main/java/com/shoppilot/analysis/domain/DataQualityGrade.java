package com.shoppilot.analysis.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 数据质量档位（Python {@code ResultItem.data_quality}；权威定义
 * {@code ANALYSIS_CONTRACT.md §4.2}）。
 *
 * <p>与 Python 侧 {@code analysis_response.DataQuality} Literal <b>逐字一致</b>（4 值）：
 * 分级算法在 Python 侧（B16 的 {@code validation/data_quality.py}），
 * Java 只做「取值是否在枚举内」的 fail-closed 校验（{@code AI_SERVICE_CONTRACT §7} 第 6 项）
 * 并落进 {@code analysis_items.data_quality_json.grade}。
 *
 * <p>★ 本表<b>无</b>对应的数据库 CHECK（{@code analysis_items} 未对数据质量建约束），
 * 因此它与 {@code ck_ai_risk_level} 不同类：非法值的最后一道防线是 Java 的 18 项校验，
 * 不是 MySQL。
 */
public enum DataQualityGrade {

    /** 覆盖与异常值均达标。 */
    GOOD,

    /** 覆盖或异常值其中之一处于中间档。 */
    FAIR,

    /** 覆盖或异常值其中之一处于最差档。 */
    POOR,

    /** 有效天数不足（{@code valid_days < min_days}）。 */
    INSUFFICIENT;

    /** 契约校验用的取值集合。 */
    public static Set<String> codes() {
        return new LinkedHashSet<>(Arrays.stream(values()).map(Enum::name).toList());
    }

    /** 严格解析：未知取值返回 {@code null}。 */
    public static DataQualityGrade fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (String grade : codes()) {
            if (grade.equals(code)) {
                return DataQualityGrade.valueOf(grade);
            }
        }
        return null;
    }
}