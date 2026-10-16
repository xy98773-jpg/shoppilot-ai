package com.shoppilot.importdata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.SkuMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.SupplierMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.WarehouseMapper;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.importdata.application.dto.RowErrorView;
import com.shoppilot.importdata.application.dto.ValidationResultView;
import com.shoppilot.importdata.domain.model.ImportBizType;
import com.shoppilot.importdata.domain.model.ImportBatchStatus;
import com.shoppilot.importdata.domain.model.ImportFieldSpec;
import com.shoppilot.importdata.domain.model.ImportFieldType;
import com.shoppilot.importdata.domain.model.ImportTemplateRegistry;
import com.shoppilot.importdata.infrastructure.parser.ImportFileParser;
import com.shoppilot.importdata.infrastructure.parser.ImportParserFactory;
import com.shoppilot.importdata.infrastructure.parser.ParseFailureException;
import com.shoppilot.importdata.infrastructure.parser.ParseOptions;
import com.shoppilot.importdata.infrastructure.parser.ParsedSheet;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportRowErrorEntity;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportBatchMapper;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportRowErrorMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
 * <b>步骤 6 全量校验</b>（{@code IMPORT_WORKFLOW §6} 步骤 6 + §10）。
 *
 * <h2>状态守卫</h2>
 * 期望 {@code MAPPED}，否则 {@code 422 IMP-4005}（裁定 D，复用解析服务的统一出口）。
 *
 * <h2>全量重解析（裁定 H 的必然推论）</h2>
 * 预览只缓存前 {@code PREVIEW_HARD_CAP} 行（{@code ImportParseService} 的 JVM 缓存），
 * 校验必须遍历<b>全部数据行</b>，故按 {@code parse_options_json} 重新解析并收集全量行
 * （{@link ImportFileParser#parse} 的 {@code previewCap = Integer.MAX_VALUE}）。
 *
 * <h2>计数口径（裁定 H + {@link ValidationResultView} javadoc）</h2>
 * {@code import_batches} <b>无</b> {@code error_rows/warning_rows} 列（迁移 {@code V1.0.5}），
 * 红线禁止新增迁移，故计数在内存从 {@code import_row_errors} 计算：
 * <ul>
 *   <li>{@code error_rows} = 存在 ≥1 条 {@code ERROR} 的不同行数；</li>
 *   <li>{@code warning_rows} = 存在 {@code WARNING} 且无 {@code ERROR} 的不同行数。</li>
 * </ul>
 *
 * <h2>状态推进</h2>
 * <ul>
 *   <li>全部数据行均有 ERROR → {@code FAILED}（释放幂等槽位 {@code dedupe_hash=NULL}）+ {@code 422 IMP-4010}
 *       （data 含 {@code batch_id/status/total_rows/error_rows}，由控制器组装）；</li>
 *   <li>否则 → {@code VALIDATED}（部分错误不阻断，留待步骤 9 确认/导入时处理）。</li>
 * </ul>
 *
 * <h2>★ R38 订正（裁定 M，P1）</h2>
 * 本类<b>不再</b>把主数据（{@code PRODUCT}/{@code SKU}/{@code SUPPLIER}/{@code WAREHOUSE}）的
 * 「库内已存在」判为 {@code ERROR CAT-3002}；依据 {@code IMPORT_WORKFLOW §10.2} 澄清栏 +
 * {@code §7.1}–{@code §7.4}+{@code §8.6}：主数据命中 {@code uk_*_team_code} 一律
 * <b>upsert 更新、不报错</b>，<b>只有「文件内重复」记后行 {@code ERROR}</b>。
 * 原判定会堵死 upsert 更新路径（{@code R38} 缺陷根因）。
 *
 * <h2>★ 裁定 F：依赖未建表的检查登记跳过</h2>
 * {@code INVENTORY_SNAPSHOT}/{@code SALES_DAILY} 的「命中库内已有行 → {@code IMP-4006}（WARNING）」
 * 依赖 {@code inventory_balances}（V1.0.6）/ {@code sales_daily}（V1.0.7，均未建），
 * 本批<b>登记并跳过</b>（见 {@code DESIGN_REVIEW} 待办）。{@code SALES_DAILY} 的「日期缺口 >30%
 * → {@code IMP-4014}（WARNING）」亦为 WARNING 且仅依赖文件内启发式，本批同样登记跳过，
 * 待 M3-T12 随销售表落地一并实现。
 */
@Service
public class ImportValidationService {

    private static final Logger log = LoggerFactory.getLogger(ImportValidationService.class);

    /** 校验成功响应 / 错误报告里带回的「前 N 条明细」上限（与预览默认行数一致）。 */
    private static final int DETAIL_PREVIEW_LIMIT = ImportParseService.PREVIEW_DEFAULT;

    private final ImportParseService parseService;
    private final ImportBatchMapper batchMapper;
    private final ImportRowErrorMapper rowErrorMapper;
    private final ImportParserFactory parserFactory;
    private final ObjectMapper objectMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final SupplierMapper supplierMapper;
    private final WarehouseMapper warehouseMapper;

    public ImportValidationService(ImportParseService parseService,
                                  ImportBatchMapper batchMapper,
                                  ImportRowErrorMapper rowErrorMapper,
                                  ImportParserFactory parserFactory,
                                  ObjectMapper objectMapper,
                                  ProductMapper productMapper,
                                  SkuMapper skuMapper,
                                  SupplierMapper supplierMapper,
                                  WarehouseMapper warehouseMapper) {
        this.parseService = parseService;
        this.batchMapper = batchMapper;
        this.rowErrorMapper = rowErrorMapper;
        this.parserFactory = parserFactory;
        this.objectMapper = objectMapper;
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
    }

    /**
     * 执行全量校验。
     *
     * @return 校验结果视图（{@code 200} 终态由控制器读取 {@code errorRows==totalRows} 决定是否改 {@code 422}）
     */
    @Transactional
    public ValidationResultView validate(Long batchId) {
        TeamContext.Snapshot snapshot = TeamContext.require();
        long teamId = snapshot.teamId();
        ImportBatchEntity batch = parseService.loadOwned(batchId, teamId);

        if (!ImportBatchStatus.MAPPED.name().equals(batch.getStatus())) {
            throw ImportParseService.statusMismatch(ImportBatchStatus.MAPPED.name(),
                    batch.getStatus(), "校验");
        }

        ImportBizType bizType = ImportBizType.parseOrNull(batch.getBizType());
        if (bizType == null) {
            throw new BizException(ErrorCodes.IMP_4008, "字段映射不完整或存在非法项");
        }

        Map<String, String> mapping = ImportParseService.readMapping(batch.getFieldMappingJson());
        ParseOptions options = ImportParseService.readOptions(batch.getParseOptionsJson());

        // ---- 全量重解析 ----
        Path file = resolveStoredFile(batch);
        ImportFileParser parser = parserFactory.of(batch.getFileName());
        ParsedSheet sheet;
        try {
            sheet = parser.parse(file, options, Integer.MAX_VALUE);
        } catch (ParseFailureException ex) {
            markFailed(batch, teamId, ex.code(), ex.getMessage());
            throw new BizException(ex.code(), ex.getMessage());
        }
        if (sheet.totalRows() <= 0) {
            markFailed(batch, teamId, ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码");
            throw new BizException(ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码");
        }

        List<String> header = sheet.header();
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            colIndex.putIfAbsent(header.get(i), i);
        }
        // 目标字段 → 源列索引（源列不在表头则跳过该校验字段）
        Map<String, Integer> targetCol = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            Integer idx = colIndex.get(entry.getValue());
            if (idx != null) {
                targetCol.put(entry.getKey(), idx);
            }
        }

        List<List<String>> rows = sheet.previewRows();

        // 引用 / 自身编码的团队归属缓存（避免同编码跨行重复查询）
        Map<ImportFieldSpec.ImportReference, Map<String, Long>> teamCache = new EnumMap<>(ImportFieldSpec.ImportReference.class);

        // ---- 第一遍：收集引用编码用于批量探测 ----
        // 文件内重复探测集合（跨行累积，不按行重置）
        // ★ R38 订正：这些集合只服务「文件内重复 → 后行 ERROR」，不再用于「库内已存在」判定
        SeenKeys seen = new SeenKeys(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
        // SALES_DAILY 文件内唯一键 (warehouse_code|sku_code|sale_date)
        Set<String> salesDailyKeys = new HashSet<>();

        for (List<String> cells : rows) {
            for (Map.Entry<String, Integer> entry : targetCol.entrySet()) {
                String target = entry.getKey();
                int idx = entry.getValue();
                String value = cell(cells, idx);
                ImportFieldSpec spec = ImportTemplateRegistry.specOf(bizType, target);
                if (spec == null || spec.reference() == null) {
                    continue;
                }
                if (value.isBlank()) {
                    continue;
                }
                teamOf(spec.reference(), value, teamCache); // 预热缓存
            }
        }

        // ---- 第二遍：逐行校验 ----
        Map<Integer, List<Issue>> issuesByRow = new LinkedHashMap<>();
        for (int r = 0; r < rows.size(); r++) {
            int rowNo = r + 1;
            List<String> cells = rows.get(r);
            List<Issue> issues = new ArrayList<>();

            for (Map.Entry<String, Integer> entry : targetCol.entrySet()) {
                String target = entry.getKey();
                int idx = entry.getValue();
                String value = cell(cells, idx);
                ImportFieldSpec spec = ImportTemplateRegistry.specOf(bizType, target);
                if (spec == null) {
                    continue;
                }
                validateField(bizType, target, spec, value, teamId, teamCache, issues);
            }

            // §10.2 各业务类型特有：文件内唯一
            // ★ R38：库内命中一律 upsert 更新、不报 ERROR（原「库内已存在」判定已删除）
            applyBusinessUnique(bizType, targetCol, cells, seen, issues);
            if (bizType == ImportBizType.SALES_DAILY) {
                String wh = valueOf(targetCol, cells, "warehouse_code");
                String sk = valueOf(targetCol, cells, "sku_code");
                String dt = valueOf(targetCol, cells, "sale_date");
                if (!wh.isBlank() && !sk.isBlank() && !dt.isBlank()) {
                    String key = wh + "|" + sk + "|" + dt;
                    if (!salesDailyKeys.add(key)) {
                        issues.add(new Issue("ERROR", ErrorCodes.SAL_6004, "warehouse_code",
                                "同一仓库、同一 SKU、同一销售日期出现重复记录"));
                    }
                }
            }

            if (!issues.isEmpty()) {
                issuesByRow.put(rowNo, issues);
            }
        }

        // ---- 计数（内存，裁定 H）----
        long errorRows = 0;
        long warningRows = 0;
        for (List<Issue> issues : issuesByRow.values()) {
            boolean hasError = issues.stream().anyMatch(i -> "ERROR".equals(i.level));
            if (hasError) {
                errorRows++;
            } else {
                warningRows++;
            }
        }

        long totalRows = sheet.totalRows();

        // ---- 写 import_row_errors（先清旧，再追加；仅追加、不更新/删除，见 §5.13）----
        rowErrorMapper.delete(new LambdaQueryWrapper<ImportRowErrorEntity>()
                .eq(ImportRowErrorEntity::getBatchId, batchId));

        List<RowErrorView> detail = new ArrayList<>();
        for (Map.Entry<Integer, List<Issue>> e : issuesByRow.entrySet()) {
            int rowNo = e.getKey();
            List<Issue> issues = e.getValue();
            boolean hasError = issues.stream().anyMatch(i -> "ERROR".equals(i.level));
            // raw_data 仅失败（ERROR）行写入
            String rawData = hasError ? toRawData(header, rows.get(rowNo - 1)) : null;
            for (Issue issue : issues) {
                ImportRowErrorEntity entity = new ImportRowErrorEntity();
                entity.setTeamId(teamId);
                entity.setBatchId(batchId);
                entity.setRowNo(rowNo);
                entity.setErrorLevel(issue.level);
                entity.setErrorCode(issue.code);
                entity.setErrorField(issue.field);
                entity.setErrorMessage(issue.message);
                entity.setRawData(rawData);
                rowErrorMapper.insert(entity);

                if (detail.size() < DETAIL_PREVIEW_LIMIT) {
                    detail.add(new RowErrorView(rowNo, issue.level, issue.code, issue.field, issue.message));
                }
            }
        }

        // ---- 状态推进 ----
        String finalStatus;
        if (totalRows > 0 && errorRows == totalRows) {
            markFailed(batch, teamId, ErrorCodes.IMP_4010, "所有数据行均未通过校验，请检查模板与数据");
            finalStatus = ImportBatchStatus.FAILED.name();
        } else {
            int updated = batchMapper.update(null, new LambdaUpdateWrapper<ImportBatchEntity>()
                    .eq(ImportBatchEntity::getId, batchId)
                    .eq(ImportBatchEntity::getTeamId, teamId)
                    .eq(ImportBatchEntity::getStatus, ImportBatchStatus.MAPPED.name())
                    .set(ImportBatchEntity::getStatus, ImportBatchStatus.VALIDATED.name())
                    .set(ImportBatchEntity::getUpdatedBy, snapshot.userId()));
            if (updated == 0) {
                ImportBatchEntity fresh = parseService.loadOwned(batchId, teamId);
                finalStatus = fresh.getStatus();
            } else {
                finalStatus = ImportBatchStatus.VALIDATED.name();
            }
        }

        ValidationResultView view = new ValidationResultView();
        view.setBatchId(batchId);
        view.setStatus(finalStatus);
        view.setTotalRows(totalRows);
        view.setErrorRows(errorRows);
        view.setWarningRows(warningRows);
        view.setErrors(detail);
        return view;
    }

    // =====================================================================
    // 字段级校验（§10.1 通用 + §10.2 范围特例）
    // =====================================================================

    private void validateField(ImportBizType bizType, String target, ImportFieldSpec spec,
                               String value, long teamId,
                               Map<ImportFieldSpec.ImportReference, Map<String, Long>> teamCache,
                               List<Issue> issues) {
        boolean blank = value.isBlank();

        // 非空（必填）
        if (spec.required() && blank) {
            issues.add(new Issue("ERROR", ErrorCodes.COM_0001, target,
                    spec.label() + "为必填项，不能为空"));
            return;
        }
        if (blank) {
            // 非必填且为空：跳过其余类型/范围/引用校验
            return;
        }

        // 引用字段：存在性 + 同团队（§10.1 团队隔离 / §10.2 各类型）
        if (spec.reference() != null) {
            Long ownerTeam = teamOf(spec.reference(), value, teamCache);
            if (ownerTeam == null) {
                String code = spec.reference() == ImportFieldSpec.ImportReference.WAREHOUSE
                        ? ErrorCodes.CAT_3003 : ErrorCodes.CAT_3001;
                issues.add(new Issue("ERROR", code, target,
                        "引用的" + spec.label() + "（" + value + "）不存在"));
            } else if (!ownerTeam.equals(teamId)) {
                issues.add(new Issue("ERROR", ErrorCodes.CAT_3005, target,
                        "引用的" + spec.label() + "（" + value + "）不属于当前团队"));
            }
            return; // 引用字段不做类型/范围校验
        }

        switch (spec.type()) {
            case STRING -> {
                if (spec.maxLength() != null && value.length() > spec.maxLength()) {
                    issues.add(new Issue("ERROR", ErrorCodes.COM_0001, target,
                            spec.label() + "长度超过上限 " + spec.maxLength() + " 字符"));
                }
            }
            case INT, DECIMAL_3, DECIMAL_4, AMOUNT_2 -> validateNumber(bizType, target, spec, value, issues);
            case DATE -> validateDate(value, issues, target);
            case BOOL -> {
                if (!isBoolean(value)) {
                    issues.add(new Issue("ERROR", ErrorCodes.COM_0001, target,
                            spec.label() + "取值非法（应为 是/否、true/false、1/0）"));
                }
            }
            case JSON -> {
                try {
                    objectMapper.readTree(value);
                } catch (Exception ex) {
                    issues.add(new Issue("ERROR", ErrorCodes.COM_0001, target,
                            spec.label() + "不是合法 JSON"));
                }
            }
            case ENUM -> {
                boolean ok = spec.allowedValues() != null
                        && spec.allowedValues().contains(value.trim().toUpperCase());
                if (!ok) {
                    issues.add(new Issue("ERROR", ErrorCodes.COM_0001, target,
                            spec.label() + "取值必须是 " + spec.allowedValues() + " 之一"));
                }
            }
        }
    }

    private void validateNumber(ImportBizType bizType, String target, ImportFieldSpec spec,
                                String value, List<Issue> issues) {
        BigDecimal num;
        try {
            num = new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            issues.add(new Issue("ERROR", ErrorCodes.COM_0001, target,
                    spec.label() + "不是合法数值"));
            return;
        }
        // 范围
        if (spec.min() != null) {
            int cmp = num.compareTo(spec.min());
            boolean violates = spec.exclusiveMin() ? cmp <= 0 : cmp < 0;
            if (violates) {
                String code = resolveRangeCode(bizType, target, spec);
                issues.add(new Issue("ERROR", code, target,
                        spec.label() + "数值非法（" + (spec.exclusiveMin() ? "须大于 " : "不得小于 ")
                                + spec.min() + "）"));
            }
        }
        // 精度（小数位数）
        int scale = num.stripTrailingZeros().scale();
        int maxScale = switch (spec.type()) {
            case INT -> 0;
            case DECIMAL_3 -> 3;
            case DECIMAL_4 -> 4;
            case AMOUNT_2 -> 2;
            default -> 0;
        };
        if (scale > maxScale) {
            issues.add(new Issue("ERROR", ErrorCodes.COM_0001, target,
                    spec.label() + "小数位数超过上限（最多 " + maxScale + " 位）"));
        }
    }

    /**
     * §10.2 范围类错误的<b>特定错误码</b>（裁定：不用通用 COM-0001 兜底语义明确的场景）。
     */
    private String resolveRangeCode(ImportBizType bizType, String target, ImportFieldSpec spec) {
        if (bizType == ImportBizType.SUPPLIER
                && ("order_multiple".equals(target) || "lead_time_days".equals(target))) {
            return ErrorCodes.CAT_3011; // 供应商交期/倍数配置非法
        }
        if (bizType == ImportBizType.SALES_DAILY && "qty".equals(target)) {
            return ErrorCodes.SAL_6002; // 销售数量不能为负
        }
        return ErrorCodes.COM_0001;
    }

    private void validateDate(String value, List<Issue> issues, String target) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(value.trim(), fmt);
        } catch (DateTimeParseException ex) {
            issues.add(new Issue("ERROR", ErrorCodes.COM_0001, target,
                    "日期格式非法，应为 YYYY-MM-DD"));
            return;
        }
        if (parsed.isAfter(LocalDate.now())) {
            issues.add(new Issue("ERROR", ErrorCodes.SAL_6003, target,
                    "业务日期不能晚于今天"));
        }
    }

    /** 布尔可取值（大小写不敏感）。 */
    private static boolean isBoolean(String value) {
        return switch (value.trim().toLowerCase()) {
            case "1", "0", "true", "false", "是", "否", "yes", "no", "y", "n" -> true;
            default -> false;
        };
    }

    // =====================================================================
    // §10.2 唯一性（★ R38 订正后：仅「文件内重复」记 ERROR）
    // =====================================================================

    /**
     * 四类主数据的业务键<b>文件内重复</b>检查（{@code IMPORT_WORKFLOW §8.6} + {@code §10.2}）。
     *
     * <h2>★ R38 订正（2026-10-17，裁定 M1 + M3）</h2>
     * <ul>
     *   <li><b>M1</b> 删除 {@code PRODUCT} / {@code SKU} 的「库内已存在 → {@code ERROR}」判定：
     *       依据 {@code §10.2} 澄清栏 + {@code §7.1}/{@code §7.2} + {@code §8.6}，主数据命中
     *       {@code uk_*_team_code} 一律 <b>upsert 更新、不报错</b>，只有「文件内重复」记 ERROR。</li>
     *   <li><b>M3</b> 为 {@code SUPPLIER} / {@code WAREHOUSE} 补同等「文件内重复 → 后行
     *       {@code ERROR CAT-3002}」检查（键为 {@code code}）。</li>
     * </ul>
     *
     * <p><b>跨团队归属探测（{@code selectTeamIdByCode} + {@code CAT-3005}）不受本订正影响</b>：
     * 它属于团队隔离校验而非重复校验，由 {@link #validateField} 在<b>引用字段</b>路径完成
     * （{@code default_supplier_code} / {@code product_code} / {@code supplier_code} /
     * {@code sku_code} / {@code warehouse_code}）。主数据<b>自身业务键</b>不做跨团队报错——
     * {@code §7.1} 明确「跨团队同编码互不影响」（同编码在别的团队存在属正常，本团队需新建自己的行）。
     */
    private void applyBusinessUnique(ImportBizType bizType, Map<String, Integer> targetCol,
                                     List<String> cells, SeenKeys seen, List<Issue> issues) {
        switch (bizType) {
            case PRODUCT -> duplicateCode(targetCol, cells, "product_code", "商品编码", seen.product(), issues);
            case SKU -> duplicateCode(targetCol, cells, "sku_code", "SKU 编码", seen.sku(), issues);
            case SUPPLIER -> duplicateCode(targetCol, cells, "code", "供应商编码", seen.supplier(), issues);
            case WAREHOUSE -> duplicateCode(targetCol, cells, "code", "仓库编码", seen.warehouse(), issues);
            default -> {
                // 其余类型（INVENTORY_SNAPSHOT / SALES_DAILY）的重复检查在调用处单列
            }
        }
    }

    /**
     * 同一业务键在文件内<b>第二次及以后</b>出现 → 该行记 {@code ERROR CAT-3002}
     * （{@code §8.6}「文件内重复」语义：后行 ERROR，要求用户先自行去重）。
     */
    private void duplicateCode(Map<String, Integer> targetCol, List<String> cells,
                               String codeColumn, String label, Set<String> seen, List<Issue> issues) {
        String code = valueOf(targetCol, cells, codeColumn);
        if (code.isBlank()) {
            return;
        }
        if (!seen.add(code)) {
            issues.add(new Issue("ERROR", ErrorCodes.CAT_3002, codeColumn,
                    label + " " + code + " 在当前文件内重复"));
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private String valueOf(Map<String, Integer> targetCol, List<String> cells, String target) {
        Integer idx = targetCol.get(target);
        return idx == null ? "" : cell(cells, idx);
    }

    private static String cell(List<String> cells, int idx) {
        if (idx < 0 || idx >= cells.size()) {
            return "";
        }
        String v = cells.get(idx);
        return v == null ? "" : v;
    }

    private Long teamOf(ImportFieldSpec.ImportReference ref, String code,
                        Map<ImportFieldSpec.ImportReference, Map<String, Long>> cache) {
        Map<String, Long> m = cache.computeIfAbsent(ref, k -> new HashMap<>());
        Long cached = m.get(code);
        if (cached != null || m.containsKey(code)) {
            return cached;
        }
        Long team = resolveRefTeam(ref, code);
        m.put(code, team);
        return team;
    }

    private Long resolveRefTeam(ImportFieldSpec.ImportReference ref, String code) {
        return switch (ref) {
            case PRODUCT -> productMapper.selectTeamIdByCode(code);
            case SKU -> skuMapper.selectTeamIdByCode(code);
            case SUPPLIER -> supplierMapper.selectTeamIdByCode(code);
            case WAREHOUSE -> warehouseMapper.selectTeamIdByCode(code);
        };
    }

    private String toRawData(List<String> header, List<String> cells) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            map.put(header.get(i), i < cells.size() ? cells.get(i) : "");
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (IOException ex) {
            return null;
        }
    }

    private Path resolveStoredFile(ImportBatchEntity batch) {
        if (batch.getStoragePath() == null || batch.getStoragePath().isBlank()) {
            throw new BizException(ErrorCodes.IMP_4002, "文件无法解析，请检查格式与编码");
        }
        Path file = Path.of(batch.getStoragePath());
        if (!Files.isRegularFile(file)) {
            throw new BizException(ErrorCodes.IMP_4011, "原始文件已不可用，请重新上传");
        }
        return file;
    }

    private void markFailed(ImportBatchEntity batch, long teamId, String code, String message) {
        batchMapper.update(null, new LambdaUpdateWrapper<ImportBatchEntity>()
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
    }

    /** 单行问题（ERROR / WARNING）。 */
    private record Issue(String level, String code, String field, String message) {
    }

    /**
     * 主数据业务键的「文件内已出现」集合（★ R38 订正：只服务「文件内重复」判定，
     * 不再用于「库内已存在」判定）。
     */
    private record SeenKeys(Set<String> product, Set<String> sku,
                            Set<String> supplier, Set<String> warehouse) {
    }
}
