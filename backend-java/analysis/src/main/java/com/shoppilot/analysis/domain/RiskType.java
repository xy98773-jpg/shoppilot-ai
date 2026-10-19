package com.shoppilot.analysis.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 风险类型（{@code analysis_items.risk_types} JSON 数组元素；权威定义
 * {@code DESIGN_BASIS.md §9.10}）。
 *
 * <p>与 Python 侧 {@code analysis_response.RiskType} Literal <b>逐字一致</b>（6 值）。
 *
 * <p><b>★ 事实更正（B16.1 / R115 已订正 B16 的误判）</b>：{@code §3.5} 示例写的是
 * {@code LOW_STOCK} / {@code SLOW_MOVING} / {@code SALES_ANOMALY}；
 * 而 {@code LOWSTOCK} 是 <b>{@code RuleCode}</b>（{@code R-LOWSTOCK}），
 * 与 {@code RiskType} 是<b>两套值空间</b>，不可混用。
 * {@code AnalysisRunStatus} / {@code LlmStatus} 同理都是各自独立的枚举。
 *
 * <p>{@code analysis_items.risk_types} 是 JSON 列，<b>无</b>数据库 CHECK；
 * 合法性由 {@code AI_SERVICE_CONTRACT §7} 第 5 项（枚举内 + 去重）在 Java 侧保证。
 */
public enum RiskType {

    /** 缺货风险。 */
    STOCKOUT,

    /** 低库存风险。 */
    LOW_STOCK,

    /** 库存积压风险。 */
    OVERSTOCK,

    /** 滞销风险。 */
    SLOW_MOVING,

    /** 销量异常风险。 */
    SALES_ANOMALY,

    /** 数据不足（无法判定，不代表真的有风险）。 */
    DATA_INSUFFICIENT;

    /** 契约校验用的取值集合。 */
    public static Set<String> codes() {
        return new LinkedHashSet<>(Arrays.stream(values()).map(Enum::name).toList());
    }

    /** 严格解析：{@code null} 或未知取值返回 {@code null}。 */
    public static RiskType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (String type : codes()) {
            if (type.equals(code)) {
                return RiskType.valueOf(type);
            }
        }
        return null;
    }
}