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
 * <p>★ <b>items 的装配</b>（M4-B20.1，解除 {@code R120}）：逐 SKU 输入由
 * {@code analysis.infrastructure.sampling.AnalysisItemAssembler} 只读装配（库存快照 / 销量序列 /
 * 交期与采购参数 / 上架日），不再恒传空数组——空数组会让 Python 侧 {@code assess} 判
 * {@code analyzable=false} 并把 run 置 {@code AI-2002}。
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
 * @param items           逐 SKU 计算数据（B20.1 由 {@code AnalysisItemAssembler} 装配，见类注释）
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
        List<AiRunItem> items) {
}
