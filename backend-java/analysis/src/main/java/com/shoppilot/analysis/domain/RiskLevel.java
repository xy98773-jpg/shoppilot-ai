package com.shoppilot.analysis.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 风险等级（{@code analysis_items.risk_level}；权威定义 {@code DESIGN_BASIS.md §9.9}）。
 *
 * <p>与 {@code AI_SERVICE_CONTRACT.md §7} 校验清单第 4 项、Python 侧
 * {@code analysis_response.RiskLevel} Literal <b>逐字一致</b>（5 值），并与迁移
 * {@code V1.0.9} 的 {@code ck_ai_risk_level} CHECK 取值集合完全相等
 * （{@code AnalysisSchemaConstraintTest#enumValuesMatchJavaEnums} 断言）。
 *
 * <p>★ 两套值空间不要混淆：本枚举是 <b>业务风险等级</b>；{@code RuleCode}
 * （{@code R-LOWSTOCK} 等）是<b>规则编码</b>，两者不可互换（{@code R115} 已订正过 B16 的误判）。
 *
 * <p>fail-closed：{@link #fromCode} 对未知取值返回 {@code null}，由调用方按「校验失败」
 * 处理（{@code §7} 第 4/5/6 项）——Java 侧<b>绝不</b>把非法值「洗」成一个合法枚举。
 */
public enum RiskLevel {

    /** 无风险（所有已判定规则均未命中）。 */
    NONE,

    /** 低风险（仅命中 LOW 级规则）。 */
    LOW,

    /** 中风险。 */
    MEDIUM,

    /** 高风险。 */
    HIGH,

    /** 未知（数据不足，无法判定；由 {@code R-DATAINSUFFICIENT} 产出）。 */
    UNKNOWN;

    /** 契约 §7 校验用的取值集合（保持声明顺序，便于断言逐项对应）。 */
    public static Set<String> codes() {
        return new LinkedHashSet<>(Arrays.stream(values()).map(Enum::name).toList());
    }

    /** 严格解析：未知取值返回 {@code null}（调用方按校验失败处理，不做「就近映射」）。 */
    public static RiskLevel fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (String level : codes()) {
            if (level.equals(code)) {
                return RiskLevel.valueOf(level);
            }
        }
        return null;
    }
}