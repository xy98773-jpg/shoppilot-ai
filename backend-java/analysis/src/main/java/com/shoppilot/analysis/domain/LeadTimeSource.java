package com.shoppilot.analysis.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 交期来源（{@code analysis_items.lead_time_source}；权威定义
 * {@code DESIGN_BASIS.md §10.2} / {@code ANALYSIS_CONTRACT.md §1.1}）。
 *
 * <p>与迁移 {@code V1.0.9} 的 {@code ck_ai_lead_time_source} CHECK 取值集合完全一致
 * （4 值），与 Python 侧 {@code analysis_response.LeadTimeSourceValue} Literal 一致。
 *
 * <p>取值优先级由 Java 侧装配器决定（{@code §5.36} 裁定 #7）：
 * {@code POLICY(策略) → SKU(商品默认) → SUPPLIER(供应商) → DEFAULT(7 天兜底)}，
 * 取第一个非空；本枚举只承载<b>取值集合</b>与 fail-closed 解析。
 *
 * <p>★ 数据库 CHECK 允许 {@code NULL}（未采用交期时的历史/异常行），但契约
 * {@code AI_SERVICE_CONTRACT §3.5} 的 {@code lead_time_source} 声明为可空——
 * 因此 {@link #fromCode(null)} 返回 {@code null}，调用方<b>不得</b>据此判校验失败。
 */
public enum LeadTimeSource {

    /** 来自 {@code replenishment_policies.lead_time_days}。 */
    POLICY,

    /** 来自 {@code skus.default_lead_time_days}。 */
    SKU,

    /** 来自 {@code suppliers.lead_time_days}。 */
    SUPPLIER,

    /** 兜底值 {@code DESIGN_BASIS §10.6} 的 {@code DEFAULT_LEAD_TIME_DAYS = 7}。 */
    DEFAULT;

    /** 契约校验 / CHECK 对齐用的取值集合。 */
    public static Set<String> codes() {
        return new LinkedHashSet<>(Arrays.stream(values()).map(Enum::name).toList());
    }

    /** 严格解析：{@code null} 或未知取值返回 {@code null}。 */
    public static LeadTimeSource fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (String source : codes()) {
            if (source.equals(code)) {
                return LeadTimeSource.valueOf(source);
            }
        }
        return null;
    }
}