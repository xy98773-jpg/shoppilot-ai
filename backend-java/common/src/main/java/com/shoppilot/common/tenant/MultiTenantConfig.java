package com.shoppilot.common.tenant;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置：<b>分页</b>（M2-T09 起）+ <b>多租户 team_id</b>。
 *
 * <p>{@link PaginationInnerInterceptor}：为 {@code mapper.selectPage(Page, Wrapper)} 生成
 * {@code LIMIT} 与配套 {@code COUNT}（分页列表端点依赖它，见 {@code API_CONTRACT §2.2}）。
 *
 * <p>{@link TenantLineInnerInterceptor}：从 {@code TeamContext} 注入 team_id。由 {@code application.yml}
 * 的 {@code shoppilot.multitenancy.enabled: true} 显式开启（默认不开启，便于无租户上下文的纯框架测试
 * 独立装配）。
 *
 * <p><b>顺序</b>：按 MyBatis-Plus 官方建议，<b>分页插件在前、多租户插件在后</b>——多租户需作用于
 * 分页生成的 COUNT 与数据 SQL 的最终形态。二者装配在<b>同一个</b> {@link MybatisPlusInterceptor}
 * 内（MyBatis-Plus 只允许一个该类型 Bean）。
 *
 * <p>本配置类位于 common（被所有模块依赖）；{@code shoppilot.multitenancy.enabled} 不成立时不创建
 * 拦截器 Bean。{@link ShopPilotTenantLineHandler} 的强校验（上下文缺失即拒绝）见其 javadoc。
 */
@Configuration
@ConditionalOnProperty(name = "shoppilot.multitenancy.enabled", havingValue = "true", matchIfMissing = false)
public class MultiTenantConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(ShopPilotTenantLineHandler handler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页（M2-T09）：为 selectPage 生成 LIMIT + COUNT；MySQL 方言
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 多租户（M2-T06）：必须最后装配，保证对分页 COUNT / 数据 SQL 均生效
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(handler));
        return interceptor;
    }

    @Bean
    public ShopPilotTenantLineHandler tenantLineHandler() {
        return new ShopPilotTenantLineHandler();
    }
}
