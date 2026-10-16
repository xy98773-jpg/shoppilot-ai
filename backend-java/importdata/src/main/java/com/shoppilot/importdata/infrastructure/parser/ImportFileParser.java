package com.shoppilot.importdata.infrastructure.parser;

import java.nio.file.Path;
import java.util.List;

/**
 * 导入文件解析器（{@code IMPORT_WORKFLOW §8.2 CSV 编码问题} / {@code §8.3 Excel 多工作表选择}）。
 *
 * <h2>★ 流式是硬要求（§8.5 大文件处理）</h2>
 * 「系统行为」列原文：<b>流式解析（不将整文件读入内存）</b>。因此本接口只提供
 * <b>逐行推送</b>的 {@link #stream}，不提供「返回全部行的 List」。
 * 预览只保留前 N 行（由 {@link ParseCollector} 丢弃其余行，仅累加计数）。
 *
 * <h2>两种消费方式</h2>
 * <ul>
 *   <li>{@link #parse}（默认方法）：预览 / 解析推进，收集表头 + 前 {@code previewCap} 行 + 总行数；</li>
 *   <li>{@link #stream}：全量校验，逐行消费且不在内存累积。</li>
 * </ul>
 */
public interface ImportFileParser {

    /** 是否支持该扩展名（小写）。 */
    boolean supports(String extension);

    /**
     * 逐行推送解析结果。
     *
     * @param file    已落盘的原始文件（{@code import_batches.storage_path}）
     * @param options 解析选项
     * @param sink    行接收器（先 {@link ParseSink#header}，再逐行 {@link ParseSink#row}）
     * @throws ParseFailureException 解析失败（{@code IMP-4002} / {@code IMP-4012}）
     */
    void stream(Path file, ParseOptions options, ParseSink sink) throws ParseFailureException;

    /**
     * 解析并收集「表头 + 前 {@code previewCap} 行 + 总行数」（默认实现，基于 {@link #stream}）。
     *
     * @param previewCap 预览行数上限（硬上限见 {@code ImportParseService.PREVIEW_HARD_CAP}）
     */
    default ParsedSheet parse(Path file, ParseOptions options, int previewCap)
            throws ParseFailureException {
        ParseCollector collector = new ParseCollector(previewCap);
        stream(file, options, collector);
        return collector.toSheet();
    }

    /**
     * 解析接收器。
     *
     * <p>{@link #reset()} 用于「先按 UTF-8 试解析、失败后换 GB18030 重放」的编码回退
     * （§8.2）：重放前必须清空已收到的部分行，否则会与上一次的残留行叠加。
     */
    interface ParseSink {

        /** 表头（每行解析器只调用一次，在 {@link #row} 之前）。 */
        void header(List<String> header);

        /**
         * 一个数据行。
         *
         * @param rowNo 行号（<b>1 起，不含表头</b>，与 {@code import_row_errors.row_no} 口径一致，§11.1）
         * @param cells 单元格值（已按表头长度对齐：缺失补空串、多余丢弃）
         * @param rawColumnCount 该行的<b>原始列数</b>（对齐前）；用于 §8.4
         *                       「列数不一致（某行多/少列）→ 该行记 {@code ERROR COM-0001}」的判定。
         *                       Excel 恒等于 {@code cells.size()}（无物理列数概念，不产生误报）。
         */
        void row(int rowNo, List<String> cells, int rawColumnCount);

        /** 实际使用的编码与工作表信息（在 {@link #header} 之前或之后调用均可）。 */
        void meta(String encoding, List<String> sheetNames, String sheetName);

        /** 清空已收集内容（编码回退重放前调用；默认空实现）。 */
        default void reset() {
            // 默认不做任何事：全量校验等一次性消费场景无需重放
        }
    }
}
