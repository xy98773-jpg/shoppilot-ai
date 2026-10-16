package com.shoppilot.importdata.infrastructure.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.apache.poi.hssf.eventusermodel.FormatTrackingHSSFListener;
import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFListener;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.eventusermodel.MissingRecordAwareHSSFListener;
import org.apache.poi.hssf.record.BlankRecord;
import org.apache.poi.hssf.record.BoolErrRecord;
import org.apache.poi.hssf.record.BoundSheetRecord;
import org.apache.poi.hssf.record.CellValueRecordInterface;
import org.apache.poi.hssf.record.EOFRecord;
import org.apache.poi.hssf.record.FormulaRecord;
import org.apache.poi.hssf.record.LabelSSTRecord;
import org.apache.poi.hssf.record.NumberRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.record.SSTRecord;
import org.apache.poi.hssf.record.StringRecord;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.filesystem.NotOLE2FileException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.Styles;
import org.apache.poi.xssf.model.StylesTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Excel 解析器（{@code IMPORT_WORKFLOW §8.3 多工作表选择} + {@code §8.4 单元格含公式} +
 * {@code §8.5 流式}），依赖 Apache POI 5.3.0（项目负责人裁定 C）。
 *
 * <h2>★ 为什么不用 {@code XSSFWorkbook} / {@code HSSFWorkbook}</h2>
 * 二者属于<b>用户模型（usermodel）</b>，会把整个工作簿（含全部 sheet 的单元格对象）读进堆内存：
 * 20MB 上限下 N 个并发解析即可能 OOM，直接违反 §8.5「<b>流式解析（不将整文件读入内存）</b>」。
 * 本类改用<b>事件模型</b>：
 * <ul>
 *   <li>{@code .xlsx}：{@link XSSFReader} + {@link XSSFSheetXMLHandler}（SAX 逐元素解析，只驻留一行）；</li>
 *   <li>{@code .xls}：{@link HSSFEventFactory} + {@link HSSFListener}（记录流逐条推送）。</li>
 * </ul>
 * 唯一常驻内存的是 {@link ReadOnlySharedStringsTable}（共享字符串池），
 * 其规模与<b>去重后的字符串</b>成正比而非单元格数，是事件模型的固有且可接受开销。
 *
 * <h2>工作表选择（§8.3）</h2>
 * 「默认取第一个<b>可见</b> Sheet；{@code parse_options_json.sheet_name} 可指定；
 * 若指定 Sheet 不存在 → 解析失败」。
 * 可见性来源：{@code .xlsx} 读 {@code workbook.xml} 的 {@code <sheet state="hidden">}；
 * {@code .xls} 读 {@link BoundSheetRecord#isHidden()} / {@link #isVeryHidden()}。
 *
 * <h2>公式（§8.4）</h2>
 * 「读取<b>计算值</b>（cached value）；无法取值时按空处理」：
 * {@code .xlsx} 由 {@link XSSFSheetXMLHandler} 的 {@code formulasNotResults=false} 提供；
 * {@code .xls} 由 {@link FormulaRecord} 的缓存结果 + 后续 {@link StringRecord} 提供。
 */
@Component
public class ExcelImportParser implements ImportFileParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelImportParser.class);

    /**
     * {@link DataFormatter} 固定用 {@link Locale#US}。
     *
     * <p>理由：日期/数值的输出主要由单元格<b>格式串</b>决定（模板列 {@code yyyy-MM-dd} 时
     * 输出即 {@code 2026-10-13}），而 locale 只影响「General 格式下的默认呈现」。
     * 固定 locale 可让服务端结果<b>可控可复现</b>，不受 JVM 默认区域影响。
     */
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.US);

    @Override
    public boolean supports(String extension) {
        return "xlsx".equals(extension) || "xls".equals(extension);
    }

    @Override
    public void stream(Path file, ParseOptions options, ParseSink sink) throws ParseFailureException {
        if (file == null || !Files.isRegularFile(file)) {
            throw ParseFailureException.unparsable();
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".xlsx")) {
                parseXssf(file, options, sink);
            } else {
                parseXls(file, options, sink);
            }
        } catch (ParseFailureException ex) {
            throw ex;
        } catch (NotOLE2FileException ex) {
            log.warn("导入文件不是合法的 OLE2 工作簿: {}", name);
            throw ParseFailureException.unparsable();
        } catch (Exception ex) {
            // 任何解析异常一律归为 IMP-4002；技术细节只进日志（ERROR_CODES §4.3 不泄露堆栈）
            log.warn("导入 Excel 解析失败: {}", name, ex);
            throw ParseFailureException.unparsable();
        }
    }

    // =====================================================================
    // .xlsx —— XSSFReader + SAX（事件模型）
    // =====================================================================

    private void parseXssf(Path file, ParseOptions options, ParseSink sink) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);

            List<SheetMeta> sheets = readSheetMeta(reader);
            SheetMeta target = pickSheet(sheets, options.sheetName());
            if (target == null) {
                log.warn("导入 Excel 找不到可用工作表: 指定={}", options.sheetName());
                throw ParseFailureException.unparsable();
            }

            Styles styles = reader.getStylesTable();
            SharedStrings strings = new ReadOnlySharedStringsTable(pkg);
            boolean parsed = false;

            // ★ 顺序铁律（R36 根因）：XSSFReader.SheetIterator.getSheetName() 依赖 next() 才填充的
            //   内部字段 xssfSheetRef —— 在 next() 之前调用必抛 NPE，被上方 catch(Exception) 吞为
            //   IMP-4002，导致任何 .xlsx 都解析失败。此处改为「用 readSheetMeta 的按序名单 + 下标比对」
            //   定位目标表，完全不依赖 getSheetName()，从根本上消除对调用时机的依赖
            //   （项目负责人回修单 §3① 推荐方案；workbook.xml 的 <sheets> 顺序与 SheetIterator 顺序一致）。
            XSSFReader.SheetIterator sheetIt = (XSSFReader.SheetIterator) reader.getSheetsData();
            int index = 0;
            while (sheetIt.hasNext()) {
                try (InputStream sheetStream = sheetIt.next()) {
                    String current = index < sheets.size() ? sheets.get(index).name() : null;
                    index++;
                    if (current == null || !target.name().equals(current)) {
                        continue;
                    }
                    parseSheet(sheetStream, styles, strings, options, sink);
                    parsed = true;
                    break;
                }
            }
            if (!parsed) {
                throw ParseFailureException.unparsable();
            }
            sink.meta("UTF-8", visibleNames(sheets), target.name);
        }
    }

    private void parseSheet(InputStream sheetStream, Styles styles, SharedStrings strings,
                            ParseOptions options, ParseSink sink) throws Exception {
        RowDispatcher dispatcher = new RowDispatcher(options, sink, false);
        XSSFSheetXMLHandler.SheetContentsHandler handler =
                new XSSFSheetXMLHandler.SheetContentsHandler() {

                    private final TreeMap<Integer, String> cells = new TreeMap<>();

                    @Override
                    public void startRow(int rowNum) {
                        cells.clear();
                    }

                    @Override
                    public void endRow(int rowNum) {
                        List<String> row = new ArrayList<>();
                        int max = cells.isEmpty() ? -1 : cells.lastKey();
                        for (int c = 0; c <= max; c++) {
                            row.add(cells.getOrDefault(c, ""));
                        }
                        dispatcher.accept(row);
                    }

                    @Override
                    public void cell(String cellReference, String formattedValue,
                                     org.apache.poi.xssf.usermodel.XSSFComment comment) {
                        cells.put(ParserRows.columnIndexOf(cellReference),
                                formattedValue == null ? "" : formattedValue);
                    }
                };

        XMLReader parser = XMLHelper.newXMLReader();
        parser.setContentHandler(new XSSFSheetXMLHandler(styles, strings, handler, FORMATTER, false));
        parser.parse(new InputSource(sheetStream));

        if (!dispatcher.headerResolved()) {
            // 空工作簿（连一行都没有）→ 无法识别表头，按 §8.4 视为无可解析数据
            throw ParseFailureException.unparsable();
        }
    }

    /** 从 {@code workbook.xml} 读取工作表名单与可见性（该 XML 很小，全量解析不违背流式）。 */
    private List<SheetMeta> readSheetMeta(XSSFReader reader) {
        List<SheetMeta> sheets = new ArrayList<>();
        try (InputStream workbookXml = reader.getWorkbookData()) {
            XMLReader parser = XMLHelper.newXMLReader();
            parser.setContentHandler(new DefaultHandler() {

                private boolean inSheets;

                @Override
                public void startElement(String uri, String localName, String qName, Attributes atts) {
                    String tag = tagOf(localName, qName);
                    if ("sheets".equals(tag)) {
                        inSheets = true;
                    } else if (inSheets && "sheet".equals(tag)) {
                        String sheetName = atts.getValue("name");
                        if (sheetName != null) {
                            String state = atts.getValue("state");
                            sheets.add(new SheetMeta(sheetName,
                                    "hidden".equals(state) || "veryHidden".equals(state)));
                        }
                    }
                }

                @Override
                public void endElement(String uri, String localName, String qName) {
                    if ("sheets".equals(tagOf(localName, qName))) {
                        inSheets = false;
                    }
                }
            });
            parser.parse(new InputSource(workbookXml));
        } catch (Exception ex) {
            // workbook.xml 缺失/损坏：退化为「全部可见」，让后续按名字匹配仍能工作
            log.warn("导入 Excel 的 workbook.xml 解析失败，退化为按名称顺序匹配", ex);
            sheets.clear();
        }
        return sheets;
    }

    private static String tagOf(String localName, String qName) {
        return localName == null || localName.isEmpty() ? qName : localName;
    }

    // =====================================================================
    // .xls —— HSSFEventFactory（记录流事件模型）
    // =====================================================================

    private void parseXls(Path file, ParseOptions options, ParseSink sink) throws Exception {
        // ★ R36 顺带自查发现的 .xls 缺陷：.xls 是 OLE2/POIFS 容器，BIFF 记录位于 "Workbook" 流内。
        //   B3 误用 `processEvents(HSSFRequest, InputStream)`——该重载读的是【裸 Workbook 流】，
        //   把 OLE2 头（D0CF11E0…）当作 BIFF 记录解析 → 必然失败（表现为批次 FAILED + IMP-4002），
        //   任何真实 .xls 都不可导入。正确做法：用 POIFSFileSystem 打开容器，再 processWorkbookEvents。
        try (POIFSFileSystem fs = new POIFSFileSystem(file.toFile(), true)) {
            HssfCollector collector = new HssfCollector(options, sink);
            FormatTrackingHSSFListener formatListener =
                    new FormatTrackingHSSFListener(new MissingRecordAwareHSSFListener(collector));
            collector.formatListener = formatListener;

            HSSFRequest request = new HSSFRequest();
            request.addListenerForAllRecords(formatListener);
            new HSSFEventFactory().processWorkbookEvents(request, fs);
            collector.finish();
        }
    }

    /** {@code .xls} 的逐记录收集器（只驻留一行，随行切换即时下推）。 */
    private static final class HssfCollector implements HSSFListener {

        private final RowDispatcher dispatcher;
        private final ParseOptions options;
        private final ImportFileParser.ParseSink sink;
        private final List<SheetMeta> sheets = new ArrayList<>();

        private FormatTrackingHSSFListener formatListener;

        /** 当前处于第几个 sheet（-1 = 工作簿全局段，尚无 sheet）。 */
        private int sheetIndex = -1;
        private int targetIndex = -2;

        private SSTRecord sst;
        private int currentRow = -1;
        private final TreeMap<Integer, String> cells = new TreeMap<>();
        private FormulaRecord pendingFormula;

        private HssfCollector(ParseOptions options, ImportFileParser.ParseSink sink) {
            this.options = options;
            this.sink = sink;
            this.dispatcher = new RowDispatcher(options, sink, false);
        }

        @Override
        public void processRecord(Record record) {
            if (record instanceof BoundSheetRecord bound) {
                sheets.add(new SheetMeta(bound.getSheetname(), bound.isHidden() || bound.isVeryHidden()));
                return;
            }
            if (record instanceof SSTRecord sstRecord) {
                sst = sstRecord;
                return;
            }
            if (record instanceof EOFRecord) {
                // 全局段结束，或某个 sheet 结束
                if (sheetIndex >= 0 && sheetIndex == targetIndex) {
                    flushRow();
                }
                sheetIndex++;
                if (targetIndex == -2) {
                    SheetMeta target = pickSheet(sheets, options.sheetName());
                    if (target == null) {
                        targetIndex = -1;   // 找不到：后续不处理任何 sheet，由 finish() 报错
                    } else {
                        targetIndex = indexOf(sheets, target.name());
                        sink.meta("UTF-8", visibleNames(sheets), target.name());
                    }
                }
                return;
            }
            if (sheetIndex < 0 || sheetIndex != targetIndex) {
                return;   // 非目标工作表：直接丢弃（不进内存）
            }
            handleCellRecord(record);
        }

        private void handleCellRecord(Record record) {
            if (record instanceof StringRecord stringRecord) {
                if (pendingFormula != null) {
                    cell(pendingFormula.getRow(), pendingFormula.getColumn(), stringRecord.getString());
                    pendingFormula = null;
                }
                return;
            }
            if (record instanceof BlankRecord blank) {
                cell(blank.getRow(), blank.getColumn(), "");
                return;
            }
            if (record instanceof LabelSSTRecord label) {
                String value = sst == null ? "" : sst.getString(label.getSSTIndex()).getString();
                cell(label.getRow(), label.getColumn(), value);
                return;
            }
            if (record instanceof NumberRecord number) {
                cell(number.getRow(), number.getColumn(), format(number));
                return;
            }
            if (record instanceof BoolErrRecord boolErr) {
                cell(boolErr.getRow(), boolErr.getColumn(),
                        boolErr.isBoolean() ? String.valueOf(boolErr.getBooleanValue()) : "");
                return;
            }
            if (record instanceof FormulaRecord formula) {
                // §8.4：取「计算值」。数值/布尔直接取缓存；字符串结果由紧随的 StringRecord 提供
                if (formula.getCachedResultTypeEnum() == CellType.STRING) {
                    pendingFormula = formula;
                    return;
                }
                cell(formula.getRow(), formula.getColumn(), format(formula));
            }
        }

        private String format(CellValueRecordInterface record) {
            try {
                return formatListener == null ? "" : formatListener.formatNumberDateCell(record);
            } catch (RuntimeException ex) {
                return "";
            }
        }

        private void cell(int row, int column, String value) {
            if (row != currentRow) {
                flushRow();
                currentRow = row;
            }
            cells.put(column, value == null ? "" : value);
        }

        private void flushRow() {
            if (currentRow < 0) {
                return;
            }
            List<String> row = new ArrayList<>();
            int max = cells.isEmpty() ? -1 : cells.lastKey();
            for (int c = 0; c <= max; c++) {
                row.add(cells.getOrDefault(c, ""));
            }
            cells.clear();
            currentRow = -1;
            dispatcher.accept(row);
        }

        /** 解析收尾：目标工作表缺失 / 表头缺失都按 §8.4 视为无可解析数据。 */
        private void finish() throws ParseFailureException {
            if (targetIndex < 0 || !dispatcher.headerResolved()) {
                throw ParseFailureException.unparsable();
            }
        }
    }

    // =====================================================================
    // 工作表选择（§8.3）
    // =====================================================================

    /** 工作表元信息。 */
    private record SheetMeta(String name, boolean hidden) {
    }

    /**
     * 选目标工作表：指定了名字就按名字找（找不到 → {@code null}，调用方转 {@code IMP-4002}）；
     * 否则取第一个<b>可见</b>表；全隐藏时也返回第一个（避免直接失败，交由业务判定）。
     */
    private static SheetMeta pickSheet(List<SheetMeta> sheets, String requested) {
        if (sheets.isEmpty()) {
            return null;
        }
        if (requested != null && !requested.isBlank()) {
            for (SheetMeta sheet : sheets) {
                if (sheet.name().equals(requested.trim())) {
                    return sheet;
                }
            }
            return null;
        }
        for (SheetMeta sheet : sheets) {
            if (!sheet.hidden()) {
                return sheet;
            }
        }
        return sheets.get(0);
    }

    private static int indexOf(List<SheetMeta> sheets, String name) {
        for (int i = 0; i < sheets.size(); i++) {
            if (sheets.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** 可见工作表名单（返回给前端供切换，§8.3 交互点）。 */
    private static List<String> visibleNames(List<SheetMeta> sheets) {
        List<String> names = new ArrayList<>();
        sheets.forEach(sheet -> {
            if (!sheet.hidden()) {
                names.add(sheet.name());
            }
        });
        return names;
    }
}
