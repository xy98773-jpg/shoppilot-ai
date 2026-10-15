package com.shoppilot.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 过滤器链骨架（Spring Security 6.5.x，Lambda DSL）。
 *
 * <p><b>本期范围（M1-T04）</b>：仅建立"白名单放行 + 其余一律要求认证 + 未认证返回 JSON 401"的骨架，
 * 并把默认 HTML 登录页/Basic 质询关闭。**不挂载任何 JWT 过滤器、不实现签发与校验**
 * （{@code DEVELOPMENT_PLAN.md §3.8} No-Go；正式认证属 M2-T02）。
 *
 * <p><b>为何无状态 + 关闭 CSRF</b>：{@code D-01} 已裁定采用无状态 JWT，服务端不保存会话；
 * 请求凭据只在 {@code Authorization} 头（非 Cookie），不存在浏览器自动携带凭据的 CSRF 面。
 *
 * <p><b>边界</b>：本类属 common（基础设施），只依赖框架，不 import 任何业务模块（尤其 identity）。
 * JWT 认证过滤器落地时须由 {@code app}（组合根）装配或由 identity 提供 SPI，common 不得反向依赖。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 免认证路径白名单。
     *
     * <p>权威口径：{@code API_CONTRACT.md §5.1} 规定仅 {@code POST /api/v1/auth/login}、
     * {@code POST /api/v1/auth/refresh}、{@code GET /api/v1/health} 三个对外接口免认证，
     * 其余一律要求 {@code Authorization}。
     *
     * <p><b>★ follow-up（防联调遗漏，勿删）</b>：按“端点先落地、再放行”的顺序推进，
     * 每落地一个免认证端点**必须**同步加入本数组，否则会被 {@code anyRequest().authenticated()} 拦成 401：
     * <ul>
     *   <li>✅ {@code GET /api/v1/health} —— 已于 M1-T06 落地并放行。</li>
     *   <li>⬜ {@code POST /api/v1/auth/login}、{@code POST /api/v1/auth/refresh}
     *       —— 属 M2-T02（{@code identity} 的 {@code AuthController}），落地时必须加入本数组。</li>
     * </ul>
     */
    public static final String[] PERMIT_ALL_PATHS = {
            "/actuator/**",        // M1-T06：Actuator 健康与指标端点（暴露面由 management.endpoints 收敛为 health,info）
            "/api/v1/health",      // M1-T06：存活探针（API_CONTRACT.md §10.11 system，§5.1 免认证）
            "/internal/v1/health", // 预留的探活路径（真正的 /internal/v1/health 属 service-ai，见 M1-T07）
            "/error"               // Servlet 容器错误转发路径
    };

    private final JsonAuthenticationEntryPoint authenticationEntryPoint;

    public SecurityConfig(JsonAuthenticationEntryPoint authenticationEntryPoint) {
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 无状态 JWT（D-01）：不建会话、不存 Cookie，故关闭 CSRF
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 若存在 CorsConfigurationSource（dev profile 的 CorsConfig），则由其接管跨域
                .cors(Customizer.withDefaults())
                // 关闭默认 HTML 登录页 / Basic 质询 / 默认登出端点，避免任何 HTML 响应
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PERMIT_ALL_PATHS).permitAll()
                        .anyRequest().authenticated())
                // 未认证统一走 JSON 出口（AUTH-1001）
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint));
        return http.build();
    }
}
