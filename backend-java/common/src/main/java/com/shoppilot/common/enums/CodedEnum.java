package com.shoppilot.common.enums;

/**
 * 通用枚举基接口：所有"带编码的枚举"可实现此接口以获得统一 {@code getCode()}。
 *
 * <p><b>不含任何具体业务枚举值。</b>业务枚举值定义在 DESIGN_BASIS §9，并归属拥有该状态的模块
 * （MODULE_BOUNDARIES §3.2 黑名单：common 不得定义具体业务枚举值）。
 */
public interface CodedEnum {

    String getCode();
}
