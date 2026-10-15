package com.shoppilot.identity.api;

import com.shoppilot.common.tenant.TeamScopeValidator;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.identity.application.AuthService;
import com.shoppilot.identity.application.dto.AuthTokenResponse;
import com.shoppilot.identity.application.dto.LoginRequest;
import com.shoppilot.identity.application.dto.LogoutRequest;
import com.shoppilot.identity.application.dto.MeResponse;
import com.shoppilot.identity.application.dto.RefreshRequest;
import com.shoppilot.identity.infrastructure.security.JwtProperties;
import com.shoppilot.identity.infrastructure.security.RefreshCookieSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点（M2-T02）——严格对齐 {@code API_CONTRACT.md §10.1} 与 {@code §11.1}。
 *
 * <table>
 *   <caption>端点与认证要求</caption>
 *   <tr><th>方法</th><th>路径</th><th>认证</th></tr>
 *   <tr><td>POST</td><td>{@code /login}</td><td>匿名（§5.1 白名单，R12）</td></tr>
 *   <tr><td>POST</td><td>{@code /refresh}</td><td>匿名（§5.1 白名单，R12）</td></tr>
 *   <tr><td>POST</td><td>{@code /logout}</td><td>需认证</td></tr>
 *   <tr><td>GET</td><td>{@code /me}</td><td>任何已登录成员</td></tr>
 * </table>
 *
 * <p>本类<b>不</b>接收任何 {@code team_id} 参数（契约 §5.3：Controller 签名中出现
 * {@code teamId} 视为违规）；团队上下文一律由认证过滤器装配的 {@code TeamContext} 提供。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService, JwtProperties jwtProperties) {
        this.authService = authService;
        this.jwtProperties = jwtProperties;
    }

    /** 登录（匿名）。成功 200；凭证错误 401 {@code AUTH-1002}；账号禁用 403 {@code AUTH-1003}。 */
    @PostMapping("/login")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        AuthTokenResponse result = authService.login(
                request.username(), request.password(), clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        RefreshCookieSupport.write(httpResponse, result.refreshToken(), jwtProperties.getRefreshTtlSeconds());
        return ApiResponse.success(result);
    }

    /**
     * 刷新令牌（匿名）。
     *
     * <p>Refresh Token 可来自请求体 {@code refreshToken} 或 Cookie（二者取先非空者）。
     * 成功即<b>轮换</b>：返回新 Access + 新 Refresh，旧 Refresh 立即失效。
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthTokenResponse> refresh(@RequestBody(required = false) RefreshRequest request,
                                                  HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        String rawRefreshToken = firstNonBlank(
                request == null ? null : request.refreshToken(),
                RefreshCookieSupport.read(httpRequest));
        AuthTokenResponse result = authService.refresh(
                rawRefreshToken, clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        RefreshCookieSupport.write(httpResponse, result.refreshToken(), jwtProperties.getRefreshTtlSeconds());
        return ApiResponse.success(result);
    }

    /** 登出（需认证）。使 Refresh Token 失效；幂等（重复登出不报错）。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) LogoutRequest request,
                                    HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        Long currentUserId = TeamScopeValidator.requireContext().userId();
        authService.logout(currentUserId, firstNonBlank(
                request == null ? null : request.refreshToken(),
                RefreshCookieSupport.read(httpRequest)));
        RefreshCookieSupport.clear(httpResponse);
        return ApiResponse.success();
    }

    /** 当前登录态（任何已登录成员）：用户 + 团队 + 角色 + 权限位。 */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.success(authService.me());
    }

    /** 客户端 IP：优先 {@code X-Forwarded-For} 首段（反向代理场景），否则 {@code remoteAddr}。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
