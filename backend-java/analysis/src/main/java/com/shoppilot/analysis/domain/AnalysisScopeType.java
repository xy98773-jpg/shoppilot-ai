package com.shoppilot.analysis.domain;

/**
 * 分析范围类型（{@code analysis_runs.scope_type}；权威定义 {@code DESIGN_BASIS.md §9.16}）。
 *
 * <ul>
 *   <li>{@link #ALL_ACTIVE}：全部启用 SKU；</li>
 *   <li>{@link #SELECTED_SKUS}：指定 SKU 列表（{@code scope_json.sku_ids}）；</li>
 *   <li>{@link #CATEGORY}：按商品类目（{@code scope_json.category}）。</li>
 * </ul>
 *
 * <p>与 {@code ck_run_scope_type} CHECK 的取值集合一一对应（V1.0.9 迁移）。
 */
public enum AnalysisScopeType {

    /** 全部启用 SKU（无附加范围明细，{@code scope_json} 为 {@code NULL}）。 */
    ALL_ACTIVE,

    /** 指定 SKU 列表（{@code scope_json = {"sku_ids":[...]}}，长度 1..2000）。 */
    SELECTED_SKUS,

    /** 按商品类目（{@code scope_json = {"category":"..."}}）。 */
    CATEGORY;

    /** 从请求值解析；非法值返回 {@code null}（由调用方转 {@code COM-0001}）。 */
    public static AnalysisScopeType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
