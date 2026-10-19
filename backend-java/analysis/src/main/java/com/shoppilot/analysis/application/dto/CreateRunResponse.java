package com.shoppilot.analysis.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDateTime;

/**
 * {@code POST /api/v1/analysis/runs} 成功响应 {@code data}（{@code API_CONTRACT.md §11.5}）。
 *
 * <p>字段与契约示例逐项对齐（snake_case）；时间字段按 {@code §14.1} 输出
 * ISO-8601 UTC 毫秒（{@link UtcDateTimeSerializer}）。
 *
 * <p>{@code total_skus} 口径（裁定⑧）：仅 {@code SELECTED_SKUS} 取 {@code sku_ids} 长度，
 * 其余类型为 {@code 0}——真实数量由 Python 执行端回填（本批不装配 items）。
 *
 * @param runId        运行 ID（{@code analysis_runs.id}）
 * @param runNo        运行编号（{@code RUN-yyyyMMdd-NNNN}，团队内唯一）
 * @param status       运行状态（创建成功时为 {@code RUNNING}）
 * @param scopeType    范围类型
 * @param totalSkus    计划分析 SKU 数
 * @param processedSkus 已完成 SKU 数
 * @param progress     进度（0–100）
 * @param currentStep  当前步骤
 * @param llmStatus    LLM 阶段状态
 * @param timeoutAt    超时截止
 * @param createdAt    创建时间
 */
public record CreateRunResponse(
        @JsonProperty("run_id") Long runId,
        @JsonProperty("run_no") String runNo,
        @JsonProperty("status") String status,
        @JsonProperty("scope_type") String scopeType,
        @JsonProperty("total_skus") Integer totalSkus,
        @JsonProperty("processed_skus") Integer processedSkus,
        @JsonProperty("progress") Integer progress,
        @JsonProperty("current_step") String currentStep,
        @JsonProperty("llm_status") String llmStatus,
        @JsonProperty("timeout_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime timeoutAt,
        @JsonProperty("created_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime createdAt) {

    /** 从实体快照转换（{@code null} 计数按 0 输出，避免前端拿到 null 后自行兜底）。 */
    public static CreateRunResponse from(AnalysisRunEntity entity) {
        return new CreateRunResponse(
                entity.getId(),
                entity.getRunNo(),
                entity.getStatus(),
                entity.getScopeType(),
                entity.getTotalSkus() == null ? 0 : entity.getTotalSkus(),
                entity.getProcessedSkus() == null ? 0 : entity.getProcessedSkus(),
                entity.getProgress() == null ? 0 : entity.getProgress(),
                entity.getCurrentStep(),
                entity.getLlmStatus(),
                entity.getTimeoutAt(),
                entity.getCreatedAt());
    }
}
