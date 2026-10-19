package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Python 返回的条目级错误结构（{@code AI_SERVICE_CONTRACT.md §3.4} 的 {@code error}）。
 *
 * <p>★ 注意与出站方向的 {@link AiErrorEnvelope} 区分：本记录嵌在 <b>成功</b> 响应体里
 * （{@code execution_status=FAILED} 时才有值），只有 {@code code}/{@code message}/{@code detail}
 * 三个字段，没有 {@code request_id}。
 *
 * @param code    Python 侧错误码（如 {@code AI-3001}）；<b>只用于映射与日志</b>，
 *                绝不透传前端（{@code AI_SERVICE_CONTRACT §12} 不变量）
 * @param message 错误文案（Python 侧英文/中文原文）
 * @param detail  结构化补充信息（如 {@code stage} / {@code sku_id}）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiErrorDetailData(
        String code,
        String message,
        Object detail) {
}