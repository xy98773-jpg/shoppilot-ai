package com.shoppilot.common.web;

import java.util.List;

/**
 * 业务异常基类（基础设施）。
 *
 * <p>持有错误码 {@code errorCode}（值来自 {@code ErrorCodes}，权威定义见 {@code ERROR_CODES.md §2}）
 * 与可选的 {@code details}（仅字段级校验类错误需要，契约 §2.3）。
 * HTTP 状态码<b>不在此决定</b>：由 {@code GlobalExceptionHandler} 按 {@code ErrorCodeHttpStatus} 统一映射。
 *
 * <p>本类不定义错误码语义——错误码常量集中在 {@code ErrorCodes}。
 * 需要携带字段级细节时用 {@link #withDetails(String, String, List)}（避免与 {@code cause} 构造器歧义）。
 */
@lombok.Getter
public class BizException extends RuntimeException {

    private final String errorCode;

    /** 字段级细节；无细节时为 {@code null}（契约 §2.3：无细节则省略）。 */
    private final transient List<ApiErrorDetail> details;

    public BizException(String errorCode) {
        this(errorCode, errorCode, null, null);
    }

    public BizException(String errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public BizException(String errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, null);
    }

    private BizException(String errorCode, String message, Throwable cause, List<ApiErrorDetail> details) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details;
    }

    /** 带字段级细节的业务异常（HTTP 状态仍由错误码映射决定，契约 §2.3）。 */
    public static BizException withDetails(String errorCode, String message, List<ApiErrorDetail> details) {
        return new BizException(errorCode, message, null, details);
    }
}
