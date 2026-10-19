package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 状态查询的内部契约信封（{@code AI_SERVICE_CONTRACT.md §3.4 响应 200} / {@code §5}）。
 *
 * <p>与 {@link AiRunCreateEnvelope} 同构：成功与失败共用 {@code code}/{@code data}/{@code request_id}。
 * {@code extra = "ignore"}：Python 未来在信封层新增字段（如 {@code timestamp}）不应导致解析失败。
 *
 * @param data      状态数据（{@code code=AI-xxxx} 时为 {@code null}）
 * @param requestId 链路追踪 ID（回显 Java 出站头）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRunStatusEnvelope(String code, AiRunStatusData data,
                                  @JsonProperty("request_id") String requestId) {
}