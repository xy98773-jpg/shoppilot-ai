package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shoppilot.analysis.domain.AnalysisScope;
import com.shoppilot.analysis.domain.AnalysisParams;
import java.time.LocalDate;
import java.util.List;

/**
 * 内部契约「数据质量预检」请求体（{@code AI_SERVICE_CONTRACT.md §3.1}）。
 *
 * <p>轻量预检：只带范围、窗口与（后续批的）逐 SKU 数据，用于在创建前让用户立即知道
 * 数据是否满足分析条件。{@code params} 只含示例中的两个字段（见 {@link AiValidateParams}）。
 *
 * @param teamId     团队 ID（日志分组用）
 * @param warehouseId 仓库
 * @param scope      范围对象
 * @param dateFrom   数据窗口起
 * @param dateTo     数据窗口止
 * @param params     预检参数（{@code window_days} / {@code min_days}）
 * @param items      逐 SKU 数据（M4-B20.1 由 {@code AnalysisItemAssembler} 与 runs 同源装配；
 *                   恒空会让 Python 判 {@code analyzable=false}，预检永远返回「不可分析」，{@code R123}）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiValidateRequest(
        @JsonProperty("team_id") Long teamId,
        @JsonProperty("warehouse_id") Long warehouseId,
        AiScope scope,
        @JsonProperty("date_from") LocalDate dateFrom,
        @JsonProperty("date_to") LocalDate dateTo,
        AiValidateParams params,
        List<AiRunItem> items) {

    /**
     * 从域值对象 + 已解析参数 + <b>已装配的 items</b>构造（{@code §5.36} Q7）。
     *
     * <p>★ {@code items} 由调用方（{@code AnalysisAppService.buildValidateRequest}）用与 runs
     * <b>同一个</b> {@code AnalysisItemAssembler} 填充；本工厂<b>不再</b>提供空数组重载——
     * 留一个「恒空」的入口就是 {@code R123} 的复发点。
     */
    public static AiValidateRequest of(Long teamId, Long warehouseId, AnalysisScope scope,
                                       LocalDate dateFrom, LocalDate dateTo,
                                       AnalysisParams params, List<AiRunItem> items) {
        return new AiValidateRequest(teamId, warehouseId, AiScope.from(scope), dateFrom, dateTo,
                new AiValidateParams(params.windowDays(), params.minDays()), items);
    }
}
