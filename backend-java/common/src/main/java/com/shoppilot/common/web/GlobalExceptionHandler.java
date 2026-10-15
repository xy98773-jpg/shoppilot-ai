package com.shoppilot.common.web;

import com.shoppilot.common.error.ErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（基础设施，@RestControllerAdvice）。
 *
 * <p>约定：已知业务异常 / 参数校验异常统一返回 HTTP 200，失败态由响应体 {@code code}（来自 ErrorCodes）表达；
 * 仅未预期异常返回 HTTP 500。前端以 {@code code} 为权威判据，不以 HTTP 状态区分业务成败。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：HTTP 200，失败态由 body 内 code 表达（code 来自 ErrorCodes / §12.3）。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        return ResponseEntity.ok(ApiResponse.fail(ex.getErrorCode(), ex.getMessage()));
    }

    /** 参数校验失败：COM-0002（DESIGN_BASIS §12.3）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.ok(ApiResponse.fail(ErrorCodes.COM_0002, msg));
    }

    /** 未预期异常：COM-0009（DESIGN_BASIS §12.3），HTTP 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCodes.COM_0009, "系统异常"));
    }
}
