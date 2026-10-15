package com.shoppilot.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Refresh Token 的 Cookie 传输支持（{@code D-01}：{@code Set-Cookie}（httpOnly + Secure）+ 响应体回显）。
 *
 * <p>两道传输路径并存，以兼容不同客户端：
 * <ul>
 *   <li><b>浏览器</b>：{@code Set-Cookie}（{@code HttpOnly} 防 XSS 窃取、{@code Secure} 仅 HTTPS、
 *       {@code SameSite=Strict} 防 CSRF），{@code Path} 收敛到 {@code /api/v1/auth}，
 *       仅登录/刷新/登出可见；</li>
 *   <li><b>非浏览器客户端</b>：响应体 {@code data.refreshToken} 回显，由客户端自行保管。</li>
 * </ul>
 *
 * <p>{@code Max-Age} 与 Refresh Token TTL 一致（{@code DESIGN_BASIS §15 D-01}：7d）。
 */
public final class RefreshCookieSupport {

    /** Cookie 名（前端与契约文档统一使用该名）。 */
    public static final String COOKIE_NAME = "refresh_token";

    /** Cookie 作用路径：仅认证端点收发，避免每个请求都携带刷新凭据。 */
    public static final String COOKIE_PATH = "/api/v1/auth";

    private RefreshCookieSupport() {
    }

    /** 下发 Refresh Cookie。 */
    public static void write(HttpServletResponse response, String refreshToken, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(true)
                .path(COOKIE_PATH)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** 清除 Refresh Cookie（登出）。 */
    public static void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** 从请求 Cookie 读取 Refresh Token（无则返回 {@code null}）。 */
    public static String read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
