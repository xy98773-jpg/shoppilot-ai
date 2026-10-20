package com.shoppilot.common.tenant;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置：<b>分页</b> + <b>多租户 team_id</b> + <b>乐观锁 version</b>。
 *
 * <p>{@link PaginationInnerInterceptor}：为 {@code mapper.selectPage(Page, Wrapper)} 生成
 * {@code LIMIT} 与配套 {@code COUNT}（分页列表端点依赖它，见 {@code API_CONTRACT §2.2}）。
 *
 * <p>{@link TenantLineInnerInterceptor}：从 {@code TeamContext} 注入 team_id。由 {@code application.yml}
 * 的 {@code shoppilot.multitenancy.enabled: true} 显式开启（默认不开启，便于无租户上下文的纯框架测试
 * 独立装配）。
 *
 * <p>{@link OptimisticLockerInnerInterceptor}（{@code R62}，M4-B23）：为<b>实体形态</b>的
 * {@code updateById(entity)} / {@code update(entity, wrapper)} 自动追加
 * {@code WHERE version = ?} 与 {@code SET version = version + 1}。
 * ★ <b>不覆盖</b> {@code update(null, wrapper)}——那类调用（{@code CatalogWriteService}）
 * 必须<b>手写</b> CAS 条件，见其 {@code applyVersionCas}。
 * ★ 插件<b>不抛异常</b>：版本不匹配时 UPDATE 影响 0 行，调用方必须判返回值。
 *
 * <p><b>顺序</b>（三层缺一不可，顺序错会生成异常 SQL 形态）：
 * <ol>
 *   <li><b>分页</b>在最前：多租户需作用于分页生成的 COUNT 与数据 SQL 的最终形态；</li>
 *   <li><b>租户线</b>居中：给每条语句追加 {@code team_id = ?}；</li>
 *   <li><b>乐观锁</b>在最后：它只改写 UPDATE 的 SET/WHERE，必须在租户线之后，
 *       否则 {@code team_id} 与 {@code version} 的拼接顺序会和预期不一致。</li>
 * </ol>
 * 三者装配在<b>同一个</b> {@link MybatisPlusInterceptor} 内（MyBatis-Plus 只允许一个该类型 Bean）。
 *
 * <p>★ <b>生命周期裁定（B23-A1）</b>：乐观锁与租户线<b>同生命周期</b>——本配置类整体受
 * {@code shoppilot.multitenancy.enabled=true} 条件约束，乐观锁不单独开关。
 * 理由：{@code version} 列与 {@code team_id} 同属「业务库独占」的并发/隔离设施，
 * 而纯框架测试（无租户上下文）本就不该写业务表；拆成两个开关会造出「有租户线但无乐观锁」
 * 这一现实不存在的半装配态，反而增加误判面。
 *
 * <p>本配置类位于 common（被所有模块依赖）；条件不成立时不创建拦截器 Bean。
 * {@link ShopPilotTenantLineHandler} 的强校验（上下文缺失即拒绝）见其 javadoc。
 */
@Configuration
@ConditionalOnProperty(name = "shoppilot.multitenancy.enabled", havingValue = "true", matchIfMissing = false)
public class MultiTenantConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(ShopPilotTenantLineHandler handler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // ① 分页（M2-T09）：为 selectPage 生成 LIMIT + COUNT；MySQL 方言
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // ② 多租户（M2-T06）：必须晚于分页，保证对分页 COUNT / 数据 SQL 均生效
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(handler));
        // ③ 乐观锁（R62，B23）：只改写 UPDATE 的 SET/WHERE，必须在租户线之后
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public ShopPilotTenantLineHandler tenantLineHandler() {
        return new ShopPilotTenantLineHandler();
    }
}
