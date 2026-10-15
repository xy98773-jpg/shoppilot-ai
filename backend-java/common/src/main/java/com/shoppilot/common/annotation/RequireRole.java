package com.shoppilot.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式角色校验注解（配置在 {@code api} 层 Controller 方法上）。
 *
 * <p>依据 {@code MODULE_BOUNDARIES.md §6}：权限矩阵（{@code USER_ROLES.md §2.2}）以声明式元数据形式
 * 标注在接口方法上，由统一鉴权切面在执行前校验。
 *
 * <p><b>为何值是 {@code String} 而非角色枚举</b>：角色枚举的权威定义属 identity 模块
 * （{@code DESIGN_BASIS §9.3}，拥有 {@code team_members} 表），而 common
 * <b>禁止依赖任何业务模块</b>（{@code MODULE_BOUNDARIES.md §2.1}）；且 common 不得承载具体业务枚举值
 * （{@code §3.2} 黑名单）。故此处只承载角色代码字符串，由 M2-T07 的切面结合 identity
 * 提供的角色解析结果完成匹配。
 *
 * <p><b>本期仅注解定义，不含任何逻辑</b>（切面属 M2-T07）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {

    /** 允许访问的角色代码集合（取值空间见 {@code DESIGN_BASIS §5}；满足其一即通过）。 */
    String[] value();
}
