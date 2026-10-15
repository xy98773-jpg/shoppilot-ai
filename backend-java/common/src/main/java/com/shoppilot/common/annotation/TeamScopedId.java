package com.shoppilot.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 公共校验注解：标记某字段/参数为"团队作用域 ID"。
 *
 * <p>被标记项必须在执行业务操作前结合 {@code TeamContext} 校验同团队
 * （DESIGN_BASIS §6 第 4 条：不能只因参数带了 team_id 就认为隔离完成）。
 * 本注解仅为标记，校验逻辑由各业务模块实现。
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TeamScopedId {

    String value() default "";
}
