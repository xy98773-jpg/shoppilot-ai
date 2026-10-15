package com.shoppilot.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.time.Instant;

/**
 * 统一响应体（成功/失败包装）。
 *
 * <p>形态对齐 API_CONTRACT §统一响应结构；字段：{@code code} / {@code message} / {@code data}。
 * 成功以 {@link #SUCCESS_CODE}（"0"）表示，失败以 DESIGN_BASIS §12.3 错误码（见 {@code ErrorCodes}）表示。
 *
 * <p>注意：{@code code="0"} 仅为传输层成功约定，<b>并非</b> DESIGN_BASIS §12 的错误码
 * （§12 仅定义失败码）。本类不重新定义任何错误码语义。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@lombok.Data
public class ApiResponse<T> implements Serializable {

    public static final String SUCCESS_CODE = "0";

    private String code;
    private String message;
    private T data;
    private Long timestamp;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(SUCCESS_CODE);
        r.setMessage("ok");
        r.setData(data);
        r.setTimestamp(Instant.now().toEpochMilli());
        return r;
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(code);
        r.setMessage(message);
        r.setTimestamp(Instant.now().toEpochMilli());
        return r;
    }
}
