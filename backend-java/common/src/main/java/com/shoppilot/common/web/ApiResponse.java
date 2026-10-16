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
 *   <li>§2.3 失败：{@code code} 为错误码常量、{@code data} <b>通常</b>为 {@code null}、
 *       {@code details[]} 仅用于字段级校验错误（无细节则省略）；
 *       个别错误码需回带数据（如 {@code IMP-4004} 的 {@code existing_batch_id}）时，
 *       用 {@link #fail(String, String, java.util.List, Object)} 重载，见 {@code DESIGN_REVIEW R33}。</li>
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
        return fail(code, message, details, null);
    }

    /**
     * 失败响应（<b>可携带 {@code data}</b>）——M3-T02 新增重载（{@code DESIGN_REVIEW R33}）。
     *
     * <h2>★ 为什么需要这个重载</h2>
     * 契约 {@code API_CONTRACT §2.3} 通常规定「失败 {@code data} 恒为 null」，但个别错误码
     * <b>必须</b>回带数据才能满足业务语义：典型如 {@code IMP-4004}（文件重复导入）要求
     * {@code data:{existing_batch_id, existing_batch_no}}，前端据此跳原批次或发起强制重导。
     *
     * <p>而 {@code GlobalExceptionHandler.handleBiz} 返回 {@code ApiResponse<Void>}、
     * {@code data} 恒为 null（R13 已闭环的全局包络，<b>不为其改动</b>）。故采用「本重载 +
     * 控制器直接 {@code ResponseEntity.status(409).body(...)}」的最小改动方案：
     * 全局出口零改动、零回归。
     *
     * <p>{@code data} 为 {@code null} 时输出与三参重载<b>完全一致</b>
     * （类上 {@code @JsonInclude(ALWAYS)}，data 字段照常输出为 null），故既有断言不受影响。
     *
     * @param code    错误码常量
     * @param message 中文提示
     * @param details 字段级细节（无则 {@code null}）
     * @param data    随错误返回的数据（通常仅个别错误码需要）
     */
    public static <T> ApiResponse<T> fail(String code, String message, List<ApiErrorDetail> details, T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(code);
        r.setMessage(message);
        r.setDetails(details == null || details.isEmpty() ? null : details);
        r.setData(data);
        r.fillEnvelope();
        return r;
    }

    /** 填充包络字段（requestId 与响应头同源、timestamp 由服务端生成）。 */
    private void fillEnvelope() {
        this.requestId = RequestIdContext.getOrGenerate();
        this.timestamp = now();
    }
}
