package com.shoppilot.importdata.infrastructure.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * 「物理行 → 表头 / 数据行」的分发器（CSV 与 Excel 共用，保证两者口径一致）。
 *
 * <h2>行号口径（{@code IMPORT_WORKFLOW §11.1}）</h2>
 * {@code import_row_errors.row_no} 是<b>数据行号，1 起，不含表头</b>；
 * 本类据此把 {@code header_row}（1 起）之前的行丢弃，之后第一行记为 {@code row_no = 1}。
 *
 * <h2>列数不一致的处理（§8.4）</h2>
 * 「列数不一致（某行多/少列）→ 该行记 {@code ERROR COM-0001}，其余行继续」——
 * 即<b>不是解析失败</b>。本类把单元格数统一对齐到表头长度（缺补空串、多余丢弃），
 * 保证解析不被单行脏数据打断；「是否不一致」的判定交给校验层按 §8.4 记 ERROR。
 *
 * <p><b>非线程安全</b>：一次解析一个实例（方法内局部使用）。
 */
final class RowDispatcher {

    private final int headerRow;
    private final ImportFileParser.ParseSink sink;
    private final boolean reportColumnMismatch;

    private int lineNo;
    private List<String> header;
    private long dataRowNo;

    /**
     * @param reportColumnMismatch 是否上报「原始列数 ≠ 表头列数」。
     *                             CSV 为 {@code true}（§8.4 明确该行记 ERROR）；
     *                             Excel 为 {@code false}——Excel 行是稀疏存储，尾部空单元格不落记录，
     *                             行短于表头是正常现象，照 CSV 口径会产生大量误报。
     */
    RowDispatcher(ParseOptions options, ImportFileParser.ParseSink sink, boolean reportColumnMismatch) {
        this.headerRow = options.effectiveHeaderRow();
        this.sink = sink;
        this.reportColumnMismatch = reportColumnMismatch;
    }

    /** 接收一个物理行（按文件顺序）。 */
    void accept(List<String> cells) {
        lineNo++;
        if (lineNo < headerRow) {
            return;
        }
        if (lineNo == headerRow) {
            header = ParserRows.normalizeHeader(cells);
            sink.header(header);
            return;
        }
        dataRowNo++;
        int width = header == null ? 0 : header.size();
        int raw = cells == null ? 0 : cells.size();
        sink.row((int) dataRowNo, ParserRows.fit(cells, width),
                reportColumnMismatch ? raw : width);
    }

    /** 收尾：确保即使文件没有表头行也能暴露问题（由调用方在解析结束后调用）。 */
    boolean headerResolved() {
        return header != null;
    }

    long totalRows() {
        return dataRowNo;
    }

    // =====================================================================

    /** 取首行之后用于填充的空单元格列表（避免 {@code fit} 在 header 为空时 NPE）。 */
    static List<String> emptyRow(int size) {
        List<String> row = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            row.add("");
        }
        return row;
    }
}
