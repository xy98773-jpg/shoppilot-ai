package com.shoppilot.importdata.domain.model;

/**
 * 导入模板字段的<b>值类型</b>（决定 §10.1 通用校验的「类型 / 范围 / 精度」三档规则）。
 *
 * <p>依据 {@code IMPORT_WORKFLOW.md §7.1–§7.6} 各业务类型表格的「类型」列，
 * 与 §10.1「类型 / 范围 / 精度」三行一一对应。
 */
public enum ImportFieldType {

    /** 字符串：只校验非空与长度上限。 */
    STRING,
    /** 整数：必须是可解析整数；{@code min} / {@code max} 生效。 */
    INT,
    /** 小数（18,3）：数量类，≤ 3 位小数（§10.1 精度行）。 */
    DECIMAL_3,
    /** 小数（18,4）：单价类，≤ 4 位小数（§10.1 精度行）。 */
    DECIMAL_4,
    /** 金额（18,2）：≤ 2 位小数（§10.1 精度行）。 */
    AMOUNT_2,
    /** 日期：格式 {@code YYYY-MM-DD}（{@code parse_options_json.date_format}，缺省即为该格式）。 */
    DATE,
    /** 布尔：{@code 0/1}、{@code true/false}、{@code 是/否}（§7.4 {@code is_default}）。 */
    BOOL,
    /** JSON 对象：必须是合法 JSON（§7.2 {@code spec_json}）。 */
    JSON,
    /** 枚举：取值必须在 {@link ImportFieldSpec#allowedValues()} 内（如 {@code MasterDataStatus}）。 */
    ENUM
}
