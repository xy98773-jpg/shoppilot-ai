package com.shoppilot.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计埋点注解（定义于 common；具体实现切面在 audit 模块）。
 *
 * <p>{@code action} 为 AuditAction 代码（见 audit 模块 / DESIGN_BASIS §9.17），
 * {@code bizType} 为领域/表名（见 audit_logs.biz_type，DESIGN_BASIS §9）。
 * 本注解仅承载字符串属性，<b>不定义任何业务枚举值</b>。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    String action() default "";

    String bizType() default "";

    String desc() default "";
}
