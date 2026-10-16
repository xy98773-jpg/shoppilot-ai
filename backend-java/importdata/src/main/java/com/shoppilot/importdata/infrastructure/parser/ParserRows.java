package com.shoppilot.importdata.infrastructure.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级工具（CSV / Excel 解析器共用，包内可见）。
 */
final class ParserRows {

    private ParserRows() {
    }

    /**
     * 表头规范化：去首尾空白；空表头列补 {@code column_{序号}}。
     *
     * <p>补名的原因：后续按列名做映射，多个空字符串列名会产生歧义且无法定位。
     */
    static List<String> normalizeHeader(List<String> header) {
        List<String> result = new ArrayList<>(header == null ? 0 : header.size());
        if (header == null) {
            return result;
        }
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i) == null ? "" : header.get(i).trim();
            result.add(name.isEmpty() ? "column_" + (i + 1) : name);
        }
        return result;
    }

    /**
     * 把行长度对齐到 {@code size}：缺失补空串、多余丢弃。
     *
     * <p>列数不一致本身不是解析失败（§8.4 该行记 {@code ERROR COM-0001}），
     * 故在此静默对齐，由校验层按业务规则判定，避免单行脏数据中断整批解析。
     */
    static List<String> fit(List<String> row, int size) {
        if (row == null) {
            row = List.of();
        }
        if (row.size() == size) {
            return row;
        }
        List<String> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(i < row.size() && row.get(i) != null ? row.get(i) : "");
        }
        return result;
    }

    /**
     * 解析单元格引用（如 {@code "AB12"}）中的列序号（0 起）。
     *
     * <p>{@code XSSFSheetXMLHandler} 只给 {@code cellRef}，需自行换算列号以定位到表头列。
     */
    static int columnIndexOf(String cellRef) {
        if (cellRef == null) {
            return 0;
        }
        int index = 0;
        for (int i = 0; i < cellRef.length(); i++) {
            char c = cellRef.charAt(i);
            if (c < 'A' || c > 'Z') {
                break;
            }
            index = index * 26 + (c - 'A' + 1);
        }
        return index == 0 ? 0 : index - 1;
    }
}
