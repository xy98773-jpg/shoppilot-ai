package com.shoppilot.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 未认证请求的统一出口（把默认 HTML 401 替换为 JSON 响应体）。
 *
 * <p>形态：HTTP 401 + {@link ApiResponse} 失败态，{@code code = AUTH-1001}
 * （"未登录或 Token 失效"，见 {@code ERROR_CODES.md §2} / {@code DESIGN_BASIS §12.3}）。
 * 用户提示文案取 {@code ERROR_CODES.md} 中 {@code AUTH-1001} 的"用户提示"列，不在此另行定义语义。
 *
 * <p><b>本期为骨架</b>：真正的 JWT 认证过滤器属 M2-T02；当前过滤器链中不存在任何认证机制，
 * 故所有受保护请求都会走到本出口（这正是 M1 期望的"未认证一律被拒"行为）。
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.fail(ErrorCodes.AUTH_1001, "登录状态已失效，请重新登录");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
