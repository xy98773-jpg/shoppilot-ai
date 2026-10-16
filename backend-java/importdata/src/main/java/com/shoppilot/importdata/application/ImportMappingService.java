package com.shoppilot.importdata.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.importdata.application.dto.MappingResultView;
import com.shoppilot.importdata.application.dto.PutMappingRequest;
import com.shoppilot.importdata.domain.model.ImportBatchStatus;
import com.shoppilot.importdata.domain.model.ImportBizType;
import com.shoppilot.importdata.domain.model.ImportFieldSpec;
import com.shoppilot.importdata.domain.model.ImportTemplateRegistry;
import com.shoppilot.importdata.infrastructure.parser.ImportFileParser;
import com.shoppilot.importdata.infrastructure.parser.ImportParserFactory;
import com.shoppilot.importdata.infrastructure.parser.ParseOptions;
import com.shoppilot.importdata.infrastructure.parser.ParsedSheet;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportBatchMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>步骤 5 模板识别与字段映射</b>（{@code IMPORT_WORKFLOW §6} 步骤 5 + {@code §9}）。
 *
 * <h2>状态守卫</h2>
 * 期望 {@code PARSED}，否则 {@code 422 IMP-4005} + details[{@code status}]（裁定 D，契约 §11.3 失败响应 2 形态）。
 *
 * <h2>两级校验顺序（裁定 D）</h2>
 * <ol>
 *   <li><b>合法性</b>（{@code IMP-4008}）：空 mapping / 目标字段不在模板列 / 源列不在文件表头；</li>
 *   <li><b>完整性</b>（{@code IMP-4003}）：模板必填列未被映射。</li>
 * </ol>
 * 先合法性后完整性——因为「空 mapping」既非法也不完整，裁定 D 明确空 mapping 归 {@code IMP-4008}。
 *
 * <h2>工作表切换（§8.3）</h2>
 * 「用户确认后随 {@code parse_options} 提交」：若请求里的 {@code sheet_name} 与当前不同，
 * 则重新解析并刷新表头 / {@code total_rows}（状态仍为 {@code PARSED}）。
 */
@Service
public class ImportMappingService {

    private static final Logger log = LoggerFactory.getLogger(ImportMappingService.class);

    /** {@code parse_options_json} 允许的键（§9.2）；出现其它键 → {@code IMP-4008}。 */
    private static final Set<String> ALLOWED_OPTION_KEYS =
            Set.of("sheet_name", "header_row", "encoding", "date_format");

    private final ImportBatchMapper importBatchMapper;
    private final ImportParseService parseService;
    private final ImportParserFactory parserFactory;
    private final ObjectMapper objectMapper;

    public ImportMappingService(ImportBatchMapper importBatchMapper,
                                ImportParseService parseService,
                                ImportParserFactory parserFactory,
                                ObjectMapper objectMapper) {
        this.importBatchMapper = importBatchMapper;
        this.parseService = parseService;
        this.parserFactory = parserFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交字段映射（{@code PARSED → MAPPED}）。
     *
     * @return 回显结果（含已提交的 mapping）
     */
    @Transactional
    public MappingResultView putMapping(Long batchId, PutMappingRequest request) {
        TeamContext.Snapshot snapshot = TeamContext.require();
        long teamId = snapshot.teamId();
        ImportBatchEntity batch = parseService.loadOwned(batchId, teamId);

        if (!ImportBatchStatus.PARSED.name().equals(batch.getStatus())) {
            throw ImportParseService.statusMismatch(ImportBatchStatus.PARSED.name(),
                    batch.getStatus(), "提交字段映射");
        }

        ImportBizType bizType = ImportBizType.parseOrNull(batch.getBizType());
        if (bizType == null) {
            throw new BizException(ErrorCodes.IMP_4008, "字段映射不完整或存在非法项");
        }

        Map<String, String> mapping = request.getFieldMappingJson();
        Map<String, Object> options = mergeOptions(batch, request.getParseOptionsJson());

        ParsedSheet sheet = switchSheetIfRequested(batch, options);
        List<String> header = sheet.header();

        validateMapping(bizType, mapping, header);

        String mappingJson = writeJson(mapping);
        String optionsJson = writeJson(options);

        // 状态守卫：条件 UPDATE（UPDATE ... WHERE status='PARSED'）
        int updated = importBatchMapper.update(null, new LambdaUpdateWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getId, batchId)
                .eq(ImportBatchEntity::getTeamId, teamId)
                .eq(ImportBatchEntity::getStatus, ImportBatchStatus.PARSED.name())
                .set(ImportBatchEntity::getStatus, ImportBatchStatus.MAPPED.name())
                .set(ImportBatchEntity::getFieldMappingJson, mappingJson)
                .set(ImportBatchEntity::getParseOptionsJson, optionsJson)
                .set(ImportBatchEntity::getUpdatedBy, snapshot.userId()));
        if (updated == 0) {
            throw ImportParseService.statusMismatch(ImportBatchStatus.PARSED.name(),
                    parseService.loadOwned(batchId, teamId).getStatus(), "提交字段映射");
        }

        MappingResultView view = new MappingResultView();
        view.setBatchId(batchId);
        view.setStatus(ImportBatchStatus.MAPPED.name());
        view.setFieldMappingJson(mapping);
        return view;
    }

    // =====================================================================
    // 校验
    // =====================================================================

    /**
     * 两级校验：先合法性（{@code IMP-4008}），再必填完整性（{@code IMP-4003}）。
     */
    private void validateMapping(ImportBizType bizType, Map<String, String> mapping, List<String> header) {
        // ---- 第 1 级：合法性 ----
        if (mapping == null || mapping.isEmpty()) {
            throw new BizException(ErrorCodes.IMP_4008, "字段映射不完整或存在非法项");
        }
        List<ApiErrorDetail> illegal = new ArrayList<>();
        Set<String> templateFields = ImportTemplateRegistry.fieldNamesOf(bizType);
        Set<String> headerSet = new LinkedHashSet<>(header);

        List<String> keys = new ArrayList<>(mapping.keySet());
        for (String key : keys) {
            String source = mapping.get(key);
            if (!templateFields.contains(key)) {
                illegal.add(new ApiErrorDetail("field_mapping_json." + key,
                        "目标字段 " + key + " 不在 " + bizType.name() + " 模板的列清单内", key));
            } else if (source == null || source.isBlank()) {
                illegal.add(new ApiErrorDetail("field_mapping_json." + key,
                        "目标字段 " + key + " 的源列名为空", null));
            } else if (!headerSet.contains(source)) {
                illegal.add(new ApiErrorDetail("field_mapping_json." + key,
                        "源列 " + source + " 不在文件表头中", source));
            }
        }
        if (!illegal.isEmpty()) {
            throw BizException.withDetails(ErrorCodes.IMP_4008, "字段映射不完整或存在非法项", illegal);
        }

        // ---- 第 2 级：必填完整性（§9.4） ----
        List<ApiErrorDetail> missing = new ArrayList<>();
        for (ImportFieldSpec spec : ImportTemplateRegistry.requiredFieldsOf(bizType)) {
            if (mapping.get(spec.name()) == null) {
                missing.add(new ApiErrorDetail("field_mapping_json." + spec.name(),
                        bizType.name() + " 导入必须映射" + spec.label() + "列", null));
            }
        }
        if (!missing.isEmpty()) {
            throw BizException.withDetails(ErrorCodes.IMP_4003, "缺少必填列的字段映射", missing);
        }
    }

    // =====================================================================
    // 解析选项
    // =====================================================================

    private Map<String, Object> mergeOptions(ImportBatchEntity batch, Map<String, Object> requested) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, Object> existing = readObject(batch.getParseOptionsJson());
        merged.putAll(existing);
        if (requested != null) {
            requested.forEach((key, value) -> {
                if (!ALLOWED_OPTION_KEYS.contains(key)) {
                    throw BizException.withDetails(ErrorCodes.IMP_4008, "字段映射不完整或存在非法项",
                            List.of(new ApiErrorDetail("parse_options_json." + key,
                                    "未知的解析选项 " + key + "，允许：" + ALLOWED_OPTION_KEYS, key)));
                }
                if (value != null) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    /**
     * 若 {@code parse_options_json.sheet_name} 与当前不同 → 重新解析（§8.3）。
     *
     * @return 解析结果（表头可能被刷新）
     */
    private ParsedSheet switchSheetIfRequested(ImportBatchEntity batch, Map<String, Object> options) {
        ParsedSheet current = parseService.sheetOf(batch);
        Object requested = options.get("sheet_name");
        if (requested == null || !current.sheetNames().contains(String.valueOf(requested))) {
            return current;
        }
        if (String.valueOf(requested).equals(current.sheetName())) {
            return current;
        }
        ParseOptions parseOptions = new ParseOptions(String.valueOf(requested),
                intOrNull(options.get("header_row")), strOrNull(options.get("encoding")),
                strOrNull(options.get("date_format")));
        ImportFileParser parser = parserFactory.of(batch.getFileName());
        try {
            ParsedSheet sheet = parser.parse(java.nio.file.Path.of(batch.getStoragePath()),
                    parseOptions, ImportParseService.PREVIEW_HARD_CAP);
            // 刷新 total_rows（表头与数据行可能因换表而不同）；
            // 新的 parse_options_json 由 putMapping 的条件 UPDATE 一并落库
            importBatchMapper.update(null, new LambdaUpdateWrapper<ImportBatchEntity>()
                    .eq(ImportBatchEntity::getId, batch.getId())
                    .set(ImportBatchEntity::getTotalRows, (int) sheet.totalRows()));
            batch.setTotalRows((int) sheet.totalRows());
            // 直接替换缓存：让后续预览/校验按新工作表重放，而不是用旧结果
            parseService.cache(batch.getId(), sheet);
            return sheet;
        } catch (Exception ex) {
            log.warn("批次 {} 切换工作表 {} 失败", batch.getId(), requested, ex);
            throw new BizException(ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码");
        }
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String strOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // =====================================================================

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            throw new IllegalStateException("序列化失败", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }
}
