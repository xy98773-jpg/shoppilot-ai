package com.shoppilot.importdata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.importdata.application.dto.ImportColumnView;
import com.shoppilot.importdata.application.dto.ImportPreviewView;
import com.shoppilot.importdata.domain.model.ImportBatchStatus;
import com.shoppilot.importdata.domain.model.ImportBizType;
import com.shoppilot.importdata.domain.model.ImportTemplateRegistry;
import com.shoppilot.importdata.infrastructure.parser.ImportFileParser;
import com.shoppilot.importdata.infrastructure.parser.ImportParserFactory;
import com.shoppilot.importdata.infrastructure.parser.ParseFailureException;
import com.shoppilot.importdata.infrastructure.parser.ParseOptions;
import com.shoppilot.importdata.infrastructure.parser.ParsedSheet;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportBatchMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>步骤 4 解析与预览</b>（{@code IMPORT_WORKFLOW §6} 步骤 4 + 项目负责人裁定 A）。
 *
 * <h2>★ 裁定 A：惰性解析语义</h2>
 * <ul>
 *   <li>解析<b>不在上传时执行</b>（M3-T02 的 201 只返回文件级字段）；</li>
 *   <li>{@code GET /{id}/preview} 首次调用（{@code status=UPLOADED}）时执行解析，
 *       成功 → 推进 {@code PARSED} 并回解析结果；</li>
 *   <li>已 {@code PARSED} 及之后：<b>直接返回已缓存结果，不重复解析</b>；</li>
 *   <li>解析失败 → 批次置 {@code FAILED} + {@code IMP-4002}
 *       （编码不支持 → {@code IMP-4012}），<b>不回退 {@code UPLOADED}</b>；</li>
 *   <li>解析读<b>已落盘的 {@code storage_path}</b> 文件，不要求客户端重传。</li>
 * </ul>
 *
 * <h2>★ 裁定 B：预览行数</h2>
 * 已自查 {@code API_CONTRACT §10.6}（第 556 行）与 {@code IMPORT_WORKFLOW §6} 步骤 4，
 * 二者<b>均无明文 N</b>，故按裁定：<b>默认 20 行</b>，支持 {@code ?limit=} 覆盖，<b>硬上限 100</b>。
 *
 * <h2>缓存说明（本批新增，记入 {@code DESIGN_REVIEW R35}）</h2>
 * 解析结果（表头 + 前 100 行预览 + 总行数）缓存在 <b>JVM 本地</b> {@link ConcurrentHashMap}：
 * ① 大小受预览硬上限约束（每批次 ≤ 100 行，不随文件行数增长）；
 * ② 缓存未命中（如服务重启 / 多实例）时<b>按 {@code parse_options_json} 重放解析</b>，
 * 由于输入文件与选项都固定，重放结果与首次<b>完全一致</b>（幂等）；
 * ③ 不引入 Redis（{@code D-07} 首期不引入）与任何分布式缓存。
 *
 * <h2>审计（裁定 E）</h2>
 * {@code AuditAction} 35 条中与导入相关的只有 #8 {@code IMPORT_BATCH_CREATE}（M3-T02 已埋）、
 * #9 {@code IMPORT_BATCH_COMPLETE}（落库完成，M3-T06）、#10 {@code IMPORT_BATCH_FORCE_REIMPORT}
 * （M3-T11）。<b>解析 / 映射 / 校验无对应 action，故本批不埋审计，严禁自造枚举值。</b>
 */
@Service
public class ImportParseService {

    private static final Logger log = LoggerFactory.getLogger(ImportParseService.class);

    /** 预览默认行数（裁定 B：契约无明文时的默认值）。 */
    public static final int PREVIEW_DEFAULT = 20;

    /** 预览硬上限（裁定 B）。 */
    public static final int PREVIEW_HARD_CAP = 100;

    private final ImportBatchMapper importBatchMapper;
    private final ImportParserFactory parserFactory;
    private final ObjectMapper objectMapper;

    /** 批次 ID → 解析结果（JVM 本地缓存；未命中时按文件重放，见类 javadoc）。 */
    private final ConcurrentHashMap<Long, ParsedSheet> cache = new ConcurrentHashMap<>();

    public ImportParseService(ImportBatchMapper importBatchMapper,
                              ImportParserFactory parserFactory,
                              ObjectMapper objectMapper) {
        this.importBatchMapper = importBatchMapper;
        this.parserFactory = parserFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析结果（成功/失败二态）。
     *
     * @param sheet        成功时的解析结果；失败为 {@code null}
     * @param errorCode    失败时的错误码（{@code IMP-4002} / {@code IMP-4012} / {@code IMP-4005}）
     * @param errorMessage 失败时的用户可见提示
     * @param batch        当前批次（失败时状态已置 {@code FAILED}）
     */
    public record ParseOutcome(ParsedSheet sheet, String errorCode, String errorMessage,
                               ImportBatchEntity batch) {

        static ParseOutcome ok(ParsedSheet sheet, ImportBatchEntity batch) {
            return new ParseOutcome(sheet, null, null, batch);
        }

        static ParseOutcome fail(String code, String message, ImportBatchEntity batch) {
            return new ParseOutcome(null, code, message, batch);
        }

        public boolean failed() {
            return errorCode != null;
        }
    }

    /**
     * 取预览（惰性解析 + 状态推进）。
     *
     * @param batchId 批次 ID
     * @param limit   预览行数（{@code null} 用默认 20；超过 100 截断到 100）
     */
    @Transactional
    public ParseOutcome preview(Long batchId, Integer limit) {
        TeamContext.Snapshot snapshot = TeamContext.require();
        long teamId = snapshot.teamId();
        ImportBatchEntity batch = loadOwned(batchId, teamId);

        ImportBatchStatus status = ImportBatchStatus.valueOf(batch.getStatus());
        if (!status.occupiesDedupeSlot()) {
            // FAILED / CANCELLED / DUPLICATE_REJECTED 均为终态：不再解析，直接状态不允许
            return ParseOutcome.fail(ErrorCodes.IMP_4005, "当前批次状态不允许该操作", batch);
        }

        int previewLimit = resolveLimit(limit);

        if (status == ImportBatchStatus.UPLOADED) {
            return parseAndAdvance(batch, teamId, previewLimit);
        }

        // 已 PARSED 及之后：优先读缓存；未命中则按已存选项重放（结果一致，幂等）
        ParsedSheet cached = cache.get(batchId);
        if (cached == null) {
            cached = replay(batch, ImportBatchStatus.PARSED);
            cache.put(batchId, cached);
        }
        return ParseOutcome.ok(cached, batch);
    }

    // =====================================================================
    // 首次解析（UPLOADED → PARSED / FAILED）
    // =====================================================================

    private ParseOutcome parseAndAdvance(ImportBatchEntity batch, long teamId, int previewLimit) {
        Path file = resolveStoredFile(batch);
        ImportFileParser parser = parserFactory.of(batch.getFileName());
        ParseOptions options = ParseOptions.DEFAULTS;

        ParsedSheet sheet;
        try {
            // 恒定按「硬上限」收集：后续任意 limit ≤ 100 都能从缓存切片取出，不必重复解析
            sheet = parser.parse(file, options, PREVIEW_HARD_CAP);
        } catch (ParseFailureException ex) {
            ImportBatchEntity failed = markFailed(batch, teamId, ex.code(), ex.getMessage());
            return ParseOutcome.fail(ex.code(), ex.getMessage(), failed);
        }

        if (sheet.totalRows() <= 0) {
            // §8.4：0 行（仅表头）视为无可解析数据 → FAILED + IMP-4002
            ImportBatchEntity failed = markFailed(batch, teamId, ErrorCodes.IMP_4002,
                    "文件无法解析，请检查格式与编码");
            return ParseOutcome.fail(ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码", failed);
        }

        // 状态守卫：条件 UPDATE（UPDATE ... WHERE status='UPLOADED'），影响行数 0 表示并发下已被推进
        String optionsJson = toOptionsJson(sheet);
        int updated = importBatchMapper.update(null,
                new LambdaUpdateWrapper<ImportBatchEntity>()
                        .eq(ImportBatchEntity::getId, batch.getId())
                        .eq(ImportBatchEntity::getTeamId, teamId)
                        .eq(ImportBatchEntity::getStatus, ImportBatchStatus.UPLOADED.name())
                        .set(ImportBatchEntity::getStatus, ImportBatchStatus.PARSED.name())
                        .set(ImportBatchEntity::getTotalRows, (int) sheet.totalRows())
                        .set(ImportBatchEntity::getParseOptionsJson, optionsJson)
                        .set(ImportBatchEntity::getTemplateVersion,
                                batch.getTemplateVersion() == null || batch.getTemplateVersion().isBlank()
                                        ? ImportTemplateRegistry.TEMPLATE_VERSION
                                        : batch.getTemplateVersion()));
        if (updated == 0) {
            // 并发：另一请求已推进过状态，回读最新状态再按既有语义处理
            ImportBatchEntity fresh = loadOwned(batch.getId(), teamId);
            return ParseOutcome.ok(cache.getOrDefault(batch.getId(), sheet), fresh);
        }

        batch.setStatus(ImportBatchStatus.PARSED.name());
        batch.setTotalRows((int) sheet.totalRows());
        batch.setParseOptionsJson(optionsJson);
        cache.put(batch.getId(), sheet);
        return ParseOutcome.ok(sheet, batch);
    }

    /** 缓存未命中时按已存选项重放解析（输入固定 ⇒ 结果与首次一致）。 */
    private ParsedSheet replay(ImportBatchEntity batch, ImportBatchStatus expected) {
        Path file = resolveStoredFile(batch);
        ImportFileParser parser = parserFactory.of(batch.getFileName());
        ParseOptions options = readOptions(batch.getParseOptionsJson());
        try {
            return parser.parse(file, options, PREVIEW_HARD_CAP);
        } catch (ParseFailureException ex) {
            // 批次已进入 PARSED 之后，理论上可重放；失败说明原文件已不可用（§11.2 #12 IMP-4011 语义）
            log.warn("批次 {} 已解析但重放解析失败（原文件可能已不可用）", batch.getId());
            throw new BizException(ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码");
        }
    }

    // =====================================================================
    // 视图组装
    // =====================================================================

    /**
     * 组装预览响应。
     *
     * <p>{@code columns} 含「已映射目标字段 / 未映射」标识（§9.3）；
     * {@code suggested_mapping} 为 §9.1 自动映射建议。
     */
    public ImportPreviewView toView(ImportBatchEntity batch, ParsedSheet sheet, int limit) {
        ImportPreviewView view = new ImportPreviewView();
        view.setBatchId(batch.getId());
        view.setStatus(batch.getStatus());
        view.setBizType(batch.getBizType());
        view.setTemplateVersion(batch.getTemplateVersion());
        view.setTotalRows(sheet.totalRows());
        view.setSheets(sheet.sheetNames());
        view.setSheetName(sheet.sheetName());
        view.setEncoding(sheet.encoding());

        Map<String, String> mapping = readMapping(batch.getFieldMappingJson());

        List<ImportColumnView> columns = new ArrayList<>();
        List<String> header = sheet.header();
        for (int i = 0; i < header.size(); i++) {
            String source = header.get(i);
            String target = null;
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                if (source.equals(entry.getValue())) {
                    target = entry.getKey();
                    break;
                }
            }
            columns.add(new ImportColumnView(i, source, target));
        }
        view.setColumns(columns);

        List<Map<String, String>> preview = new ArrayList<>();
        int rows = Math.min(limit, sheet.previewRows().size());
        for (int r = 0; r < rows; r++) {
            List<String> cells = sheet.previewRows().get(r);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("row_no", String.valueOf(r + 1));
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), c < cells.size() ? cells.get(c) : "");
            }
            preview.add(row);
        }
        view.setPreview(preview);
        view.setPreviewRows(rows);

        ImportBizType bizType = ImportBizType.parseOrNull(batch.getBizType());
        view.setSuggestedMapping(bizType == null
                ? Map.of()
                : ImportTemplateRegistry.autoMapping(bizType, header));
        return view;
    }

    /** 取解析出的表头（供映射/校验使用；优先缓存，未命中则重放）。 */
    public ParsedSheet sheetOf(ImportBatchEntity batch) {
        ParsedSheet cached = cache.get(batch.getId());
        if (cached != null) {
            return cached;
        }
        ParsedSheet replayed = replay(batch, ImportBatchStatus.valueOf(batch.getStatus()));
        cache.put(batch.getId(), replayed);
        return replayed;
    }

    /** 让缓存失效（映射变更工作表后需要重新解析时调用）。 */
    public void evict(Long batchId) {
        cache.remove(batchId);
    }

    /** 直接写入缓存（切换工作表后替换旧结果，避免后续按旧选项重放）。 */
    public void cache(Long batchId, ParsedSheet sheet) {
        cache.put(batchId, sheet);
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 取本团队的批次；跨团队或不存在 → {@code 404 IMP-4007}（ERROR_CODES §2.5 #7 原文含「跨团队」）。 */
    public ImportBatchEntity loadOwned(Long batchId, long teamId) {
        ImportBatchEntity batch = importBatchMapper.selectOne(new LambdaQueryWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getId, batchId)
                .eq(ImportBatchEntity::getTeamId, teamId));
        if (batch == null) {
            throw new BizException(ErrorCodes.IMP_4007, "导入批次不存在");
        }
        return batch;
    }

    /** 解析失败：置 {@code FAILED} 并<b>释放幂等槽位</b>（{@code §6-E.4}：释放态 {@code dedupe_hash=NULL}）。 */
    private ImportBatchEntity markFailed(ImportBatchEntity batch, long teamId, String code, String message) {
        importBatchMapper.update(null, new LambdaUpdateWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getId, batch.getId())
                .eq(ImportBatchEntity::getTeamId, teamId)
                .set(ImportBatchEntity::getStatus, ImportBatchStatus.FAILED.name())
                .set(ImportBatchEntity::getDedupeHash, null)
                .set(ImportBatchEntity::getErrorCode, code)
                .set(ImportBatchEntity::getErrorMessage, message));
        batch.setStatus(ImportBatchStatus.FAILED.name());
        batch.setDedupeHash(null);
        batch.setErrorCode(code);
        batch.setErrorMessage(message);
        return batch;
    }

    private Path resolveStoredFile(ImportBatchEntity batch) {
        if (batch.getStoragePath() == null || batch.getStoragePath().isBlank()) {
            log.warn("批次 {} 无落盘文件（storage_path 为空）", batch.getId());
            throw new BizException(ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码");
        }
        Path file = Path.of(batch.getStoragePath());
        if (!Files.isRegularFile(file)) {
            log.warn("批次 {} 的落盘文件已不存在: {}", batch.getId(), batch.getStoragePath());
            throw new BizException(ErrorCodes.IMP_4011, "原始文件已不可用，请重新上传");
        }
        return file;
    }

    public static int resolveLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return PREVIEW_DEFAULT;
        }
        return Math.min(limit, PREVIEW_HARD_CAP);
    }

    /** 解析结果 → {@code parse_options_json}（§9.2 结构）。 */
    private String toOptionsJson(ParsedSheet sheet) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (sheet.sheetName() != null) {
            options.put("sheet_name", sheet.sheetName());
        }
        options.put("header_row", 1);
        options.put("encoding", sheet.encoding());
        options.put("date_format", "yyyy-MM-dd");
        try {
            return objectMapper.writeValueAsString(options);
        } catch (IOException ex) {
            throw new IllegalStateException("解析选项序列化失败", ex);
        }
    }

    /** {@code parse_options_json} → {@link ParseOptions}（缺字段走缺省）。 */
    public static ParseOptions readOptions(String json) {
        if (json == null || json.isBlank()) {
            return ParseOptions.DEFAULTS;
        }
        try {
            Map<?, ?> map = new ObjectMapper().readValue(json, Map.class);
            String sheetName = map.get("sheet_name") == null ? null : String.valueOf(map.get("sheet_name"));
            String encoding = map.get("encoding") == null ? null : String.valueOf(map.get("encoding"));
            String dateFormat = map.get("date_format") == null ? null : String.valueOf(map.get("date_format"));
            Integer headerRow = null;
            Object raw = map.get("header_row");
            if (raw instanceof Number number) {
                headerRow = number.intValue();
            } else if (raw != null) {
                try {
                    headerRow = Integer.valueOf(String.valueOf(raw));
                } catch (NumberFormatException ignored) {
                    headerRow = null;
                }
            }
            return new ParseOptions(sheetName, headerRow, encoding, dateFormat);
        } catch (Exception ex) {
            return ParseOptions.DEFAULTS;
        }
    }

    /** {@code field_mapping_json} → Map（目标字段 → 源列名）。 */
    public static Map<String, String> readMapping(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            Map<?, ?> raw = new ObjectMapper().readValue(json, Map.class);
            raw.forEach((k, v) -> result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        } catch (Exception ex) {
            return result;
        }
        return result;
    }

    /** 状态守卫失败的统一出口：{@code 422 IMP-4005} + details（契约 §11.3 失败响应形态）。 */
    public static BizException statusMismatch(String expected, String actual, String operation) {
        return BizException.withDetails(ErrorCodes.IMP_4005, "当前批次状态不允许" + operation,
                List.of(new ApiErrorDetail("status",
                        "期望状态 " + expected + "，当前状态 " + actual, actual)));
    }
}
