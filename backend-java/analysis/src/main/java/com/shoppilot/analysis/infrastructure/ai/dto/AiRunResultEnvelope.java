package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 结果查询的内部契约信封（{@code AI_SERVICE_CONTRACT.md §3.5 响应 200} / {@code §5}）。
 *
 * <p>与 {@link AiRunCreateEnvelope} / {@link AiRunStatusEnvelope} 同构；
 * {@code extra = "ignore"} 同理。
 *
 * @param data      结果数据（{@code code=AI-xxxx} 时为 {@code null}）
 * @param requestId 链路追踪 ID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRunResultEnvelope(String code, AiRunResultData data,
                                  @JsonProperty("request_id") String requestId) {
}