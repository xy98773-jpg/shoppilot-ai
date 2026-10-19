package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 内部契约统一信封（{@code AI_SERVICE_CONTRACT.md §3.2 响应 202} / {@code §5} 错误体同构）。
 *
 * <p>成功与失败<b>同一信封</b>：{@code code="OK"} 为成功；{@code code="AI-xxxx"} 为内部错误
 * （{@code ERROR_CODES.md §2.11}）。{@code request_id} 由 Python 回显 {@code X-Request-Id}。
 *
 * @param data      {@code code=OK} 时的业务数据（{@code code=AI-xxxx} 时为 {@code null}）
 * @param requestId 链路追踪 ID（回显 Java 出站头）
 */
public record AiRunCreateEnvelope(String code, AiRunAccepted data,
                                  @JsonProperty("request_id") String requestId) {

    /**
     * 创建受理信息（{@code §3.2 响应体 202}）。
     *
     * @param analysisRunId   回显的 run id（必须与请求一致，{@code §13.2}：Java 生成、Python 只使用）
     * @param executionStatus 执行状态（首期恒 {@code ACCEPTED}；<b>不是</b> {@code AnalysisRunStatus}）
     * @param acceptedAt      受理时间（ISO-8601）
     */
    public record AiRunAccepted(
            @JsonProperty("analysis_run_id") Long analysisRunId,
            @JsonProperty("execution_status") String executionStatus,
            @JsonProperty("accepted_at") String acceptedAt) {
    }
}
