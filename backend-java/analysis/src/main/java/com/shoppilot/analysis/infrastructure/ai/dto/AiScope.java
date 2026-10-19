package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shoppilot.analysis.domain.AnalysisScope;
import java.util.List;

/**
 * 内部契约 {@code scope} 对象（{@code AI_SERVICE_CONTRACT.md §3.1/§3.2}）。
 *
 * <p>{@code SELECTED_SKUS} 时 {@code sku_ids} 必填、{@code CATEGORY} 时 {@code category} 必填、
 * {@code ALL_ACTIVE} 时两者皆空（{@code ANALYSIS_CONTRACT.md §1.1}）。
 * {@code @JsonInclude(NON_NULL)}：无值字段整体省略（Python 侧 Optional 字段缺省即 None）。
 *
 * @param scopeType 范围类型（{@code AnalysisScopeType} 名）
 * @param skuIds    SKU 列表（保序）
 * @param category  类目名
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiScope(
        @JsonProperty("scope_type") String scopeType,
        @JsonProperty("sku_ids") List<Long> skuIds,
        String category) {

    /** 从域值对象转换。 */
    public static AiScope from(AnalysisScope scope) {
        return new AiScope(scope.scopeType().name(), scope.skuIds(), scope.category());
    }
}
