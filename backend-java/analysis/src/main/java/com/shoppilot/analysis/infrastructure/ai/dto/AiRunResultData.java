package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET /internal/v1/analysis/runs/{id}/result} 的 {@code data}（{@code AI_SERVICE_CONTRACT.md §3.5}）。
 *
 * <p>★ {@code execution_status=COMPLETED} <b>不代表业务成功</b>：业务成败由 Java 依据
 * {@code llm_status} 与 18 项二次校验判定（{@code DESIGN_BASIS §13.2}）。
 *
 * <p>本模型只承载 Python 的回传结构，<b>不</b>做业务判定；校验与落库在
 * {@code AnalysisResultWriteService}。
 *
 * @param analysisRunId    运行 ID（必须等于请求值——{@code §7} 第 1 项校验）
 * @param executionStatus  执行状态（取结果时恒为 COMPLETED）
 * @param algorithmVersion 算法版本（必须等于请求值——{@code §7} 第 11 项）
 * @param promptVersion    Prompt 模板版本
 * @param modelName        模型名
 * @param llmStatus        run 级 LLM 状态（{@code §8} 映射到 Java 终态的唯一依据）
 * @param dataStartDate    实际销售数据最早日（ISO {@code yyyy-MM-dd}）
 * @param dataEndDate      实际销售数据最晚日
 * @param summary          汇总统计（{@link AiRunSummary}）
 * @param items            逐 SKU 结果（{@link AiResultItem}，≤2000 条）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRunResultData(
        Long analysisRunId,
        String executionStatus,
        String algorithmVersion,
        String promptVersion,
        String modelName,
        String llmStatus,
        String dataStartDate,
        String dataEndDate,
        AiRunSummary summary,
        java.util.List<AiResultItem> items) {

    /** {@code items} 永不为 {@code null}（Python 保证给出数组），缺失时按空列表处理。 */
    public java.util.List<AiResultItem> itemsOrEmpty() {
        return items == null ? java.util.List.of() : items;
    }
}