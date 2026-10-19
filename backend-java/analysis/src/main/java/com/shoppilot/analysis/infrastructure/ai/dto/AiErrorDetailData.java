package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Python 返回的条目级错误结构（{@code AI_SERVICE_CONTRACT.md §3.4} 的 {@code error}）。
 *
 * <p>★ 注意与出站方向的 {@link AiErrorEnvelope} 区分：本记录嵌在 <b>成功</b> 响应体里
 * （{@code execution_status=FAILED} 时才有值），只有 {@code code}/{@code message}/{@code detail}
 * 三个字段，没有 {@code request_id}。
 *
 *
 * <p><b>★ {@code @JsonProperty} 逐字段绑定（R137，P1 端到端阻断）</b>：Python 回包键名是
 * snake_case，而本记录是 Java record（组件名 camelCase）。二者<b>不会</b>自动互转，
 * 且 Jackson2ObjectMapperBuilder 默认关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * ⇒ 未标注时 snake_case 键会<b>静默</b>绑定为 {@code null}（不报错），
 * 表现为「状态永远停在 RUNNING、结果端点永不调用」。
 * 因此<b>逐字段</b>标注；<b>禁止</b>改全局 {@code @JsonNaming} 或 ObjectMapper 配置
 * （那是跨模块共享基建）。

 * @param code    Python 侧错误码（如 {@code AI-3001}）；<b>只用于映射与日志</b>，
 *                绝不透传前端（{@code AI_SERVICE_CONTRACT §12} 不变量）
 * @param message 错误文案（Python 侧英文/中文原文）
 * @param detail  结构化补充信息（如 {@code stage} / {@code sku_id}）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiErrorDetailData(
        @JsonProperty("code") String code,
        @JsonProperty("message") String message,
        @JsonProperty("detail") Object detail) {
}