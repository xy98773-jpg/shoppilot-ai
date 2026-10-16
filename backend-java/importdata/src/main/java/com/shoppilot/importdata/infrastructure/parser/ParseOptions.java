package com.shoppilot.importdata.infrastructure.parser;

/**
 * 解析选项（{@code import_batches.parse_options_json}，权威见 {@code IMPORT_WORKFLOW.md §9.2}）。
 *
 * <pre>
 * {
 *   "sheet_name": "Sheet1",
 *   "header_row": 1,
 *   "encoding": "UTF-8",
 *   "date_format": "yyyy-MM-dd"
 * }
 * </pre>
 *
 * <p><b>持久化与再解析</b>：解析成功后本对象会被写回 {@code parse_options_json}，
 * 后续预览/校验均按同一选项重放，保证「同一批次每次解析结果一致」（§8.5 文件是唯一真相）。
 *
 * @param sheetName  指定工作表（{@code .xlsx/.xls} 多表场景，§8.3）；{@code null} = 取第一个可见表
 * @param headerRow  表头所在行号（1 起）；{@code null} = 1
 * @param encoding   指定编码（{@code UTF-8} / {@code GB18030}）；{@code null} = 自动探测（§8.2）
 * @param dateFormat 日期格式；{@code null} = {@code yyyy-MM-dd}
 */
public record ParseOptions(String sheetName, Integer headerRow, String encoding, String dateFormat) {

    /** 缺省解析选项（自动探测编码、第一个可见表、第 1 行为表头）。 */
    public static final ParseOptions DEFAULTS = new ParseOptions(null, null, null, null);

    /** 表头行号（1 起），未指定时取 1。 */
    public int effectiveHeaderRow() {
        return headerRow == null || headerRow < 1 ? 1 : headerRow;
    }

    /** 日期格式，未指定时取 {@code yyyy-MM-dd}。 */
    public String effectiveDateFormat() {
        return dateFormat == null || dateFormat.isBlank() ? "yyyy-MM-dd" : dateFormat;
    }
}
