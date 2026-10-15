package com.shoppilot.common.tenant;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多租户（team_id）插件配置骨架。
 *
 * <p>使用 MyBatis-Plus 的 {@link TenantLineInnerInterceptor}，从 {@code TeamContext} 注入 team_id。
 * 实际生效在 M2-T06（identity 模块解析 JWT 并填充 TeamContext 后，将
 * {@code shoppilot.multitenancy.enabled} 置为 true）。
 *
 * <p>默认关闭（{@code matchIfMissing = false}）：骨架期不向 SQL 注入 team_id，避免误伤与上下文缺失崩溃。
 * 本配置类位于 common（被所有模块依赖），但条件不成立时不会创建拦截器 Bean。
 */
@Configuration
@ConditionalOnProperty(name = "shoppilot.multitenancy.enabled", havingValue = "true", matchIfMissing = false)
public class MultiTenantConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(ShopPilotTenantLineHandler handler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(handler));
        return interceptor;
    }

    @Bean
    public ShopPilotTenantLineHandler tenantLineHandler() {
        return new ShopPilotTenantLineHandler();
    }
}
