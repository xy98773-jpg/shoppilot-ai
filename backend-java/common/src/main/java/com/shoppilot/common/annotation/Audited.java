package com.shoppilot.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计埋点注解（定义于 common；实现切面 {@code AuditAspect} 在 audit 模块）。
 *
 * <p>{@code action} 为 {@code AuditAction} 代码（35 条权威清单见 audit 模块 /
 * {@code DESIGN_BASIS §9.17}），{@code bizType} 为领域/表名（见 {@code audit_logs.biz_type}，
 * 如 {@code WAREHOUSE}/{@code TEAM_MEMBER}/{@code USER}）。
 * 本注解仅承载字符串属性，<b>不定义任何业务枚举值</b>（{@code MODULE_BOUNDARIES §3.2}）。
 *
 * <h2>★ 仅支持方法级标注（M2-T11 收窄）</h2>
 * {@code @Target} 已收窄为 {@link ElementType#METHOD}：切面以
 * {@code @annotation(...)} 匹配<b>方法级</b>注解，若允许类级标注则类上标注会
 * <b>静默失效</b>（编译通过、运行不拦、审计丢失——最坏的一类缺陷，同 M2-T07
 * {@code RequireRole} 的坑）。故从声明上禁止类级，让误用<b>编译期即失败</b>。
 *
 * <h2>bizIdParam</h2>
 * 声明"从哪个方法参数取业务对象 ID"（参数名）。为空表示本埋点不记 {@code biz_id}
 * （如创建类端点没有 id 参数）；<b>显式非空却解析不到</b>同名 Number 参数时，
 * 切面抛 {@link IllegalStateException}（fail-fast，不得静默跳过）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** 动作常量（{@code AuditAction} 枚举名，35 条之一）。 */
    String action() default "";

    /** 业务对象类型（{@code audit_logs.biz_type}）。 */
    String bizType() default "";

    /** 人类可读描述（落入 {@code change_summary}）。 */
    String desc() default "";

    /** 取业务对象 ID 的方法参数名；为空则不记 {@code biz_id}。 */
    String bizIdParam() default "";
}
