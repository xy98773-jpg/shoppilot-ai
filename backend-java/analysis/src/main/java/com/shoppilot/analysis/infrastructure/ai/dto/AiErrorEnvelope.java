package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 内部统一错误响应体（{@code AI_SERVICE_CONTRACT.md §5}）。
 *
 * <p>★ 适用范围 = {@code service-ai} 的<b>全部</b> HTTP 响应，无例外（M4-B13.1 / {@code R95}）：
 * 未匹配路径（404）与方法不匹配（405）也统一为四字段，故 Java 侧<b>任何</b>错误响应都能拿到
 * {@code code}。本 DTO 的 {@code code} 即 {@code ERROR_CODES.md §2.11} 的 19 条 {@code AI-xxxx} 之一
 * （{@code AI-1003} 只表「路由不存在」，业务 404 必须用 {@code AI-6001}——{@code R101} 裁定④）。
 *
 * @param code      内部错误码（可能为 {@code null}：老版本/非本契约的错误体）
 * @param message   英文技术描述（面向日志，<b>不</b>直接对用户展示）
 * @param detail    结构化上下文（字段、阶段、SKU 等）
 * @param requestId 回显的 {@code X-Request-Id}
 */
public record AiErrorEnvelope(String code, String message, Object detail,
                              @JsonProperty("request_id") String requestId) {
}
