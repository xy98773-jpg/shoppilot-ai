package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET /internal/v1/analysis/runs/{id}} 的 {@code data}（{@code AI_SERVICE_CONTRACT.md §3.4}）。
 *
 * <p>字段与契约示例逐项对齐；时间字段为 ISO-8601 字符串（Python 侧 {@code datetime} 序列化，
 * 带时区），此处用 {@link String} 承载后由应用层解析——<b>不引入</b> {@code java.time} 反序列化配置，
 * 避免 34 个 {@code @SpringBootTest} 的 Jackson 配置口径被本模块改写。
 *
 * <p><b>fail-closed</b>（令 A3）：枚举字段一律 {@link String}，由应用层用
 * {@code AnalysisRunStatus} / {@code LlmStatus} 严格解析；Python 未来新增取值时
 * Java 侧判为「未知状态」而不猜测。
 *
 * <p>{@code extra = "ignore"}：Python 可能新增非必填字段，Java 不应因此整包解析失败。
 *
 * @param analysisRunId    运行 ID（Java 生成并回显）
 * @param executionStatus  执行状态（ACCEPTED/RUNNING/COMPLETED/FAILED/CANCELLED，<b>非</b> Java 的 AnalysisRunStatus）
 * @param progress         进度档位（5/20/40/60/80/100，{@code R119}；Java 不自算）
 * @param currentStep      当前步骤（VALIDATING/STATS/RISK_RULES/REPLENISHMENT/LLM_EXPLAIN/DONE）
 * @param totalSkus        总 SKU 数
 * @param processedSkus    已完成 SKU 数
 * @param llmStatus        run 级 LLM 状态（7 值）
 * @param startedAt        开始时间（ISO-8601，可空）
 * @param finishedAt       结束时间（ISO-8601，可空）
 * @param error            失败信息（可空）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRunStatusData(
        Long analysisRunId,
        String executionStatus,
        Integer progress,
        String currentStep,
        Integer totalSkus,
        Integer processedSkus,
        String llmStatus,
        String startedAt,
        String finishedAt,
        AiErrorDetailData error) {
}