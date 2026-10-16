package com.shoppilot.importdata.infrastructure.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ImportFileParser.ParseSink} 的收集实现：把「表头 + 前 {@code previewCap} 行 + 总行数」
 * 组装为 {@link ParsedSheet}，<b>其余行只累加计数不驻留</b>（§8.5 流式保证）。
 */
public final class ParseCollector implements ImportFileParser.ParseSink {

    private final int previewCap;

    private List<String> header = List.of();
    private final List<List<String>> preview = new ArrayList<>();
    private long totalRows;
    private String encoding;
    private List<String> sheetNames = List.of();
    private String sheetName;

    /**
     * @param previewCap 预览行数上限（≥0；0 表示不收集预览行，仅计数）
     */
    public ParseCollector(int previewCap) {
        this.previewCap = Math.max(0, previewCap);
    }

    @Override
    public void header(List<String> header) {
        this.header = header == null ? List.of() : List.copyOf(header);
    }

    @Override
    public void row(int rowNo, List<String> cells, int rawColumnCount) {
        totalRows = rowNo;
        if (rowNo <= previewCap) {
            preview.add(List.copyOf(cells));
        }
    }

    @Override
    public void meta(String encoding, List<String> sheetNames, String sheetName) {
        this.encoding = encoding;
        this.sheetNames = sheetNames == null ? List.of() : List.copyOf(sheetNames);
        this.sheetName = sheetName;
    }

    /** 清空全部已收集内容（UTF-8 → GB18030 编码回退重放前调用）。 */
    @Override
    public void reset() {
        header = List.of();
        preview.clear();
        totalRows = 0L;
        encoding = null;
        sheetNames = List.of();
        sheetName = null;
    }

    /** 组装解析结果。 */
    public ParsedSheet toSheet() {
        return new ParsedSheet(header, List.copyOf(preview), totalRows, encoding, sheetNames, sheetName);
    }
}
