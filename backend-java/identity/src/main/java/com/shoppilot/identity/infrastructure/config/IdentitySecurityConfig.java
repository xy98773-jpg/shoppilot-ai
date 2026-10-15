package com.shoppilot.identity.infrastructure.config;

import com.shoppilot.common.security.SecurityFilterContributor;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMemberMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.UserMapper;
import com.shoppilot.identity.infrastructure.security.JwtAuthenticationFilter;
import com.shoppilot.identity.infrastructure.security.JwtTokenProvider;
import com.shoppilot.identity.infrastructure.security.SecurityErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * identity 安全装配（M2-T02）。
 *
 * <h2>为什么过滤器在这里装配，而不是直接在过滤器类上打 {@code @Component}</h2>
 * Spring Boot 会把容器中的任何 {@code jakarta.servlet.Filter} Bean <b>自动注册进 Servlet 容器</b>，
 * 那样 {@link JwtAuthenticationFilter} 会在「Servlet 过滤器链」与「Security 过滤器链」各跑一次。
 * 故此处用 {@code @Bean} 方法<b>返回 {@link SecurityFilterContributor}</b>（内部持有非 Bean 的
 * 过滤器实例）——既避开自动注册，又把过滤器交回 {@code common.SecurityConfig} 的链上，
 * 且依赖方向保持 {@code identity → common}（{@code MODULE_BOUNDARIES §2.1}，F-40）。
 */
@Configuration
public class IdentitySecurityConfig {

    /** 口令编码器：BCrypt（{@code API_CONTRACT §11.1}：服务端 BCrypt 校验）。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 把 JWT 认证过滤器贡献给 {@code common} 的 Security 过滤器链。 */
    @Bean
    public SecurityFilterContributor jwtAuthenticationFilterContributor(
            JwtTokenProvider jwtTokenProvider,
            UserMapper userMapper,
            TeamMemberMapper teamMemberMapper,
            SecurityErrorWriter securityErrorWriter) {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtTokenProvider, userMapper, teamMemberMapper, securityErrorWriter);
        return () -> filter;
    }
}
