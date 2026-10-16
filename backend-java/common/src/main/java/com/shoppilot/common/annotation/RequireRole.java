package com.shoppilot.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式角色校验注解（配置在 {@code api} 层 Controller 方法 / 类上）。
 *
 * <p>依据 {@code MODULE_BOUNDARIES.md §6}：权限矩阵（{@code USER_ROLES.md §2.2}）以声明式元数据形式
 * 标注在接口方法上，由统一鉴权切面（M2-T07 {@code RequireRoleAspect}）在执行前校验。
 *
 * <p><b>为何值是 {@code String} 而非角色枚举</b>：角色枚举的权威定义属 identity 模块
 * （{@code DESIGN_BASIS §9.3}，拥有 {@code team_members} 表），而 common
 * <b>禁止依赖任何业务模块</b>（{@code MODULE_BOUNDARIES.md §2.1}）；且 common 不得承载具体业务枚举值
 * （{@code §3.2} 黑名单）。故此处只承载角色代码字符串，由切面结合 {@code TeamContext} 提供的角色解析结果
 * 完成<b>按名称</b>匹配。
 *
 * <p><b>M2-T07 扩展</b>：新增 {@link #teamIdParam()}，声明「从哪个方法参数取路径 {@code {teamId}}」，
 * 使切面在角色判定之前先做<b>团队归属</b>校验（{@code TEAM-2001}），保证判定顺序
 * （{@code USER_ROLES §4.1}：团队归属先于角色授权）不被打乱。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {

    /** 允许访问的角色代码集合（取值空间见 {@code DESIGN_BASIS §5}；满足其一即通过）。 */
    String[] value();

    /**
     * 指定从哪个方法参数取路径 {@code {teamId}}（资源定位符），用于先做团队归属校验（{@code TEAM-2001}）。
     *
     * <p>为空时默认取名为 {@code "teamId"} 的参数。若方法无该参数（如 {@code GET /teams} 列表），
     * 留空即可——切面会跳过归属步、只做角色授权（{@code TEAM-2003}）。
     */
    String teamIdParam() default "";
}
