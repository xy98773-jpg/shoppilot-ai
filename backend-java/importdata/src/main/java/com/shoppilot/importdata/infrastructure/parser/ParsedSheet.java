package com.shoppilot.importdata.infrastructure.parser;

import java.util.List;

/**
 * 解析结果（{@code IMPORT_WORKFLOW §6} 步骤 4 的输出：表头、列清单、前 N 行预览、{@code total_rows}）。
 *
 * <p><b>流式保证（§8.5）</b>：{@link #previewRows()} 只保留前 {@code previewCap} 行，
 * 其余行在解析时被<b>逐行丢弃</b>（只累加计数），因此内存占用不随文件行数线性增长。
 *
 * @param header      源列头（原样保留，未规范化；规范化只在自动映射时做，§9.1）
 * @param previewRows 前 N 行预览（每行 = 单元格字符串列表）
 * @param totalRows   数据行总数（不含表头；{@code PARSED} 后写入 {@code import_batches.total_rows}）
 * @param encoding    实际使用的编码（写入 {@code parse_options_json.encoding}，§8.2 原文要求记录）
 * @param sheetNames  <b>可见</b>工作表名单（§8.3；CSV 为空列表）
 * @param sheetName   实际使用的工作表（CSV 为 {@code null}）
 */
public record ParsedSheet(List<String> header,
                          List<List<String>> previewRows,
                          long totalRows,
                          String encoding,
                          List<String> sheetNames,
                          String sheetName) {

    /** 源列数（表头长度）。 */
    public int columnCount() {
        return header == null ? 0 : header.size();
    }
}
