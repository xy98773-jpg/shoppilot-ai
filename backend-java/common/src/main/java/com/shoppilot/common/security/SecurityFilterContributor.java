package com.shoppilot.common.security;

import jakarta.servlet.Filter;

/**
 * Security 过滤器链贡献点（基础设施 SPI）。
 *
 * <h2>为什么需要这个接口</h2>
 * {@code MODULE_BOUNDARIES.md §2.1}（F-40）规定 <b>{@code common} 不得依赖任何业务模块</b>，
 * 而 JWT 认证过滤器必须放在 {@code identity} 模块（它拥有 {@code users}/{@code team_members} 表与
 * jwt 依赖）。若 {@link SecurityConfig} 直接 {@code new} 或 {@code @Autowired} 该过滤器，
 * 就会形成 {@code common → identity} 的反向依赖，破坏 DAG。
 *
 * <p>故 {@link SecurityConfig} 只依赖本接口（定义在 common），由 {@code identity} 实现并通过
 * Spring 容器注入——依赖方向仍是 {@code identity → common}，DAG 保持无环。
 *
 * <h2>插入位置</h2>
 * 贡献的过滤器统一插入到 {@code UsernamePasswordAuthenticationFilter} <b>之前</b>，
 * 即在授权判定（{@code AuthorizationFilter}）之前完成认证与上下文装配。
 *
 * <h2>实现约束（重要）</h2>
 * 实现方<b>不得</b>把 {@link Filter} 本身注册为 Spring Bean（例如在过滤器类上加 {@code @Component}）：
 * Spring Boot 会把容器中的 {@code Filter} Bean 自动注册进 Servlet 容器，
 * 导致同一过滤器在「Servlet 链」与「Security 链」各执行一次。
 * 正确做法见 {@code identity} 的 {@code IdentitySecurityConfig}：用 {@code @Bean} 方法
 * <b>返回本接口</b>（内部持有非 Bean 的过滤器实例）。
 */
@FunctionalInterface
public interface SecurityFilterContributor {

    /** 待加入 Spring Security 过滤器链的过滤器（插入在 {@code UsernamePasswordAuthenticationFilter} 之前）。 */
    Filter filter();
}
