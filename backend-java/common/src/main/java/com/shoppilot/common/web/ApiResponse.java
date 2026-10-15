package com.shoppilot.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 统一响应体（成功/失败包装）。
 *
 * <p><b>严格对齐 {@code API_CONTRACT.md §2}</b>：
 * <ul>
 *   <li>§2.1 成功（单对象）：{@code code="OK"}、{@code message="success"}、{@code data}、
 *       {@code requestId}（== 响应头 {@code X-Request-Id}）、{@code timestamp}（ISO-8601 UTC，形如
 *       {@code 2026-10-14T08:00:00.000Z}）；</li>
 *   <li>§2.2 成功（分页）：分页元信息置于 {@code data} 内，形态见 {@link PageResult}；</li>
 *   <li>§2.3 失败：{@code code} 为错误码常量、{@code data} 恒为 {@code null}、
 *       {@code details[]} 仅用于字段级校验错误（无细节则省略）。</li>
 * </ul>
 *
 * <p>注意：{@code code} 同时服务成功与失败——成功恒为 {@code "OK"}（前端只需判断
 * {@code code === "OK"}）。错误码语义不在本类定义，取值见 {@code ErrorCodes}。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@lombok.Data
public class ApiResponse<T> implements Serializable {

    /** 成功码（契约 §2.1）。 */
    public static final String SUCCESS_CODE = "OK";
    /** 成功提示（契约 §2.1）。 */
    public static final String SUCCESS_MESSAGE = "success";

    /** ISO-8601 UTC，毫秒精度，带 {@code Z} 后缀（契约 §2.1 / DESIGN_BASIS §14.1）。 */
    private static final DateTimeFormatter ISO_UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private String code;
    private String message;
    private T data;

    /** 仅字段级校验错误（如 {@code COM-0001}）时非空；无细节时序列化省略（契约 §2.3）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ApiErrorDetail> details;

    /** 链路追踪 ID，等同响应头 {@code X-Request-Id}（契约 §2.1 / §7）。 */
    private String requestId;

    /** 服务端响应时间，ISO-8601 UTC（契约 §2.1）。 */
    private String timestamp;

    /** 当前时间戳字面量。 */
    public static String now() {
        return ISO_UTC_MILLIS.format(Instant.now());
    }

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(SUCCESS_CODE);
        r.setMessage(SUCCESS_MESSAGE);
        r.setData(data);
        r.fillEnvelope();
        return r;
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return fail(code, message, null);
    }

    public static <T> ApiResponse<T> fail(String code, String message, List<ApiErrorDetail> details) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(null);
        r.setDetails(details == null || details.isEmpty() ? null : details);
        r.fillEnvelope();
        return r;
    }

    /** 填充包络字段（requestId 与响应头同源、timestamp 由服务端生成）。 */
    private void fillEnvelope() {
        this.requestId = RequestIdContext.getOrGenerate();
        this.timestamp = now();
    }
}
