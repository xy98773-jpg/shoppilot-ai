package com.shoppilot.common.web;

/**
 * 业务异常基类（基础设施）。
 *
 * <p>持有错误码 {@code errorCode}（值来自 {@code ErrorCodes} / DESIGN_BASIS §12.3）。
 * 本类不定义错误码语义——错误码常量集中在 {@code ErrorCodes}。
 */
@lombok.Getter
public class BizException extends RuntimeException {

    private final String errorCode;

    public BizException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public BizException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
