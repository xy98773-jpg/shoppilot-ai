package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * 内部契约「创建分析任务」请求体（{@code AI_SERVICE_CONTRACT.md §3.2}，逐字段对齐）。
 *
 * <p><b>幂等键</b>：{@code analysis_run_id} 由 Java 生成（{@code analysis_runs.id}），
 * Python 只使用不回写；同一 id 重复创建返回首次 202（{@code §6.2}）。
 *
 * <p>★ <b>B15 数据装配边界</b>：{@code items[]} 恒传<b>空数组</b>。逐 SKU 的输入装配
 * （{@code sales_daily} 销量序列 / {@code inventory_balances} 库存 / {@code skus} 属性 /
 * 在途合并，见 {@code AI_SERVICE_CONTRACT.md §3.3}）<b>不属 M4-T03/T04 范围</b>——
 * 本批只交付运行创建与 client 骨架。Python 骨架期无端点（任何调用只会 404，{@code R107}），
 * 完整链路在 B16~B19 后由后续批接入。
 *
 * @param analysisRunId   Java 生成的业务 ID（幂等键）
 * @param teamId          团队 ID（仅日志分组，<b>非权限依据</b>）
 * @param warehouseId     分析范围仓库（可空 = 默认仓）
 * @param scope           范围对象
 * @param dateFrom        数据窗口起（{@code YYYY-MM-DD}）
 * @param dateTo          数据窗口止
 * @param params          运行参数（数值按 §4.1 字符串化）
 * @param algorithmVersion 算法版本
 * @param promptVersion   Prompt 版本
 * @param modelName       LLM 模型名（可空）
 * @param items           逐 SKU 计算数据（B15 恒空数组，见类注释）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiRunCreateRequest(
        @JsonProperty("analysis_run_id") Long analysisRunId,
        @JsonProperty("team_id") Long teamId,
        @JsonProperty("warehouse_id") Long warehouseId,
        AiScope scope,
        @JsonProperty("date_from") LocalDate dateFrom,
        @JsonProperty("date_to") LocalDate dateTo,
        AiParams params,
        @JsonProperty("algorithm_version") String algorithmVersion,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("model_name") String modelName,
        List<Object> items) {
}
