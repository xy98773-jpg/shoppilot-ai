package com.shoppilot.identity.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 过滤器链中的统一 JSON 错误出口。
 *
 * <p>Servlet 过滤器发生在 Spring MVC 之前，无法享受 {@code @RestControllerAdvice}（
 * {@code GlobalExceptionHandler}）的处理；但对外响应<b>必须</b>与 MVC 侧同构
 * （{@code API_CONTRACT §2} 统一包络：{@code code}/{@code message}/{@code data}/{@code details}/
 * {@code requestId}/{@code timestamp}）。故本类复用 {@link ApiResponse#fail} 生成响应体。
 *
 * <p>{@code requestId} 由 {@code RequestIdContext} 提供，与 {@code RequestIdFilter} 写入的
 * 响应头 {@code X-Request-Id} <b>同源</b>（契约 §2.1 / §7：二者必须一致）。
 */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写出错误响应。
     *
     * @param response HTTP 响应
     * @param status   HTTP 状态码（与错误码语义一致，{@code API_CONTRACT §1.3}）
     * @param code     错误码常量（{@code ERROR_CODES.md §2}）
     * @param message  用户提示文案
     */
    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(code, message));
    }
}
