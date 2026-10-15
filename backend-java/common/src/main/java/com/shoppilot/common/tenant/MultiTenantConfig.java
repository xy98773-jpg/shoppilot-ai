package com.shoppilot.common.tenant;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多租户（team_id）插件配置。
 *
 * <p>使用 MyBatis-Plus 的 {@link TenantLineInnerInterceptor}，从 {@code TeamContext} 注入 team_id。
 * M2-T06 起由 {@code application.yml} 的 {@code shoppilot.multitenancy.enabled: true} 显式开启
 * （默认不开启，便于无租户上下文的纯框架测试独立装配）。
 *
 * <p>本配置类位于 common（被所有模块依赖），但条件不成立时不会创建拦截器 Bean。
 * {@link ShopPilotTenantLineHandler} 的强校验（上下文缺失即拒绝）见其 javadoc。
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
