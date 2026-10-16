package com.shoppilot.importdata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.importdata.application.dto.ImportBatchDetailView;
import com.shoppilot.importdata.application.dto.ImportBatchListView;
import com.shoppilot.importdata.application.dto.ImportTemplateView;
import com.shoppilot.importdata.domain.model.ImportBizType;
import com.shoppilot.importdata.domain.model.ImportFieldSpec;
import com.shoppilot.importdata.domain.model.ImportTemplateRegistry;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportRowErrorEntity;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportBatchMapper;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportRowErrorMapper;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>批次查询 / 详情 / 错误报告导出 / 模板清单</b>（M3-T07；{@code API_CONTRACT §10.6} F-IMP-02/F-IMP-04）。
 *
 * <h2>团队隔离（裁定 J1）</h2>
 * {@code team_id} <b>只</b>来自 {@link TeamContext}；所有查询显式带 {@code team_id}
 * （租户线再兜底追加一次，双保险）。跨团队 / 不存在的批次 → {@code 404 IMP-4007}
 * （{@code ERROR_CODES §2.5} 明列 {@code IMP-4007} = 404，并含「跨团队」语义；从严取 404）。
 *
 * <h2>★ 错误计数的实时口径（裁定 J2）</h2>
 * {@code import_batches} 无 {@code error_rows}/{@code warning_rows} 列，故详情页的错误统计
 * <b>实时</b>从 {@code import_row_errors} 统计（不同行号口径，与 {@code POST /{id}/validate} 一致）。
 *
 * <h2>★ CSV 导出流式写出（裁定 J3）</h2>
 * 按 {@value #EXPORT_CHUNK_SIZE} 条一页从数据库拉取并立即写出，<b>不把全部错误行读入内存</b>；
 * 筛选语义与 {@code GET /{id}/errors} 完全一致（复用同一个 {@code selectPageByBatch}）。
 */
@Service
public class ImportQueryService {

    private static final Logger log = LoggerFactory.getLogger(ImportQueryService.class);

    /** 分页上限（契约 §2.2；与 M3 既有实现口径一致，裁定 J4）。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** CSV 导出的分页拉取块大小（流式，避免全量入内存）。 */
    private static final int EXPORT_CHUNK_SIZE = 500;

    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_WARNING = "WARNING";

    private final ImportBatchMapper batchMapper;
    private final ImportRowErrorMapper rowErrorMapper;
    private final ImportParseService parseService;

    public ImportQueryService(ImportBatchMapper batchMapper,
                              ImportRowErrorMapper rowErrorMapper,
                              ImportParseService parseService) {
        this.batchMapper = batchMapper;
        this.rowErrorMapper = rowErrorMapper;
        this.parseService = parseService;
    }

    // =====================================================================
    // J1：批次列表
    // =====================================================================

    /**
     * 批次列表（{@code GET /api/v1/import/batches}）。
     *
     * <p>支持按 {@code status} / {@code bizType} / 时间范围（{@code from}~{@code to}，含当日）筛选；
     * 固定排序 {@code created_at DESC, id DESC}（稳定排序，同毫秒也不乱序）。
     *
     * @param status  批次状态筛选（可空；非法值不报错而是查不到，避免把枚举校验塞进查询）
     * @param bizType 业务类型筛选（可空）
     * @param from    起始日期 {@code yyyy-MM-dd}（UTC，含当日 00:00:00），可空
     * @param to      结束日期 {@code yyyy-MM-dd}（UTC，含当日 23:59:59.999），可空
     */
    @Transactional(readOnly = true)
    public PageResult<ImportBatchListView> listBatches(int page, int pageSize, String status, String bizType,
                                                       String from, String to) {
        validatePage(page, pageSize);
        long teamId = TeamContext.require().teamId();

        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");

        LambdaQueryWrapper<ImportBatchEntity> w = new LambdaQueryWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getTeamId, teamId);
        if (status != null && !status.isBlank()) {
            w.eq(ImportBatchEntity::getStatus, status.trim().toUpperCase());
        }
        if (bizType != null && !bizType.isBlank()) {
            w.eq(ImportBatchEntity::getBizType, bizType.trim().toUpperCase());
        }
        if (fromDate != null) {
            w.ge(ImportBatchEntity::getCreatedAt, fromDate.atStartOfDay());
        }
        if (toDate != null) {
            w.lt(ImportBatchEntity::getCreatedAt, toDate.plusDays(1).atStartOfDay());
        }
        w.orderByDesc(ImportBatchEntity::getCreatedAt).orderByDesc(ImportBatchEntity::getId);

        Page<ImportBatchEntity> result = batchMapper.selectPage(new Page<>(page, pageSize), w);
        List<ImportBatchListView> list = result.getRecords().stream().map(ImportBatchListView::of).toList();
        return PageResult.of(result.getTotal(), page, pageSize, list);
    }

    // =====================================================================
    // J2：批次详情
    // =====================================================================

    /** 批次详情（{@code GET /api/v1/import/batches/{id}}）。不存在 / 跨团队 → {@code 404 IMP-4007}。 */
    @Transactional(readOnly = true)
    public ImportBatchDetailView detail(Long batchId) {
        long teamId = TeamContext.require().teamId();
        ImportBatchEntity batch = parseService.loadOwned(batchId, teamId);
        ErrorCounts counts = countErrorRows(batchId);
        return ImportBatchDetailView.of(batch, counts.errorRows(), counts.warningRows());
    }

    /**
     * 实时统计错误/警告行数（不同行号口径，同 {@code ValidationResultView}）。
     *
     * <p>{@code error_rows} = 存在 ≥1 条 {@code ERROR} 的不同行数；
     * {@code warning_rows} = 存在 {@code WARNING} 且<b>无</b> {@code ERROR} 的不同行数。
     */
    private ErrorCounts countErrorRows(Long batchId) {
        Set<Integer> errorRows = new HashSet<>(rowErrorMapper.selectDistinctRowNosByLevel(batchId, LEVEL_ERROR));
        Set<Integer> warningRows = new HashSet<>(rowErrorMapper.selectDistinctRowNosByLevel(batchId, LEVEL_WARNING));
        long warnings = warningRows.stream().filter(no -> !errorRows.contains(no)).count();
        return new ErrorCounts(errorRows.size(), warnings);
    }

    // =====================================================================
    // J3：错误报告导出（CSV）
    // =====================================================================

    /**
     * 取批次（导出的权限与存在性判定入口）：不存在 / 跨团队 → {@code 404 IMP-4007}。
     */
    @Transactional(readOnly = true)
    public ImportBatchEntity loadOwnedForExport(Long batchId) {
        return parseService.loadOwned(batchId, TeamContext.require().teamId());
    }

    /**
     * 流式写出错误报告 CSV（{@code GET /{id}/errors/export}）。
     *
     * <p>列与 {@code IMPORT_WORKFLOW §11.3} 一致：
     * {@code row_no,error_level,error_code,error_field,error_message,原始值}；
     * {@code 原始值} 为 {@code raw_data} 的 JSON 文本（含未映射列，§6-E.2）。
     *
     * <p><b>UTF-8 BOM</b> 由调用方（控制器）在流首写入（兼容 Excel 打开中文）。
     * 本方法只负责「分块拉取 → 立即写出」，不缓存全量。
     *
     * @param errorLevel 错误级别筛选（{@code ERROR}/{@code WARNING}），{@code null} 不过滤
     * @param rowNo      数据行号筛选，{@code null} 不过滤
     * @return 写出的数据行数（不含表头），供调用方/测试断言与 {@code import_row_errors} 记录数一致
     */
    @Transactional(readOnly = true)
    public long writeErrorsCsv(Long batchId, String errorLevel, Integer rowNo, Writer writer) throws IOException {
        writer.write("row_no,error_level,error_code,error_field,error_message,原始值\n");
        long written = 0L;
        long offset = 0L;
        while (true) {
            List<ImportRowErrorEntity> chunk =
                    rowErrorMapper.selectPageByBatch(batchId, errorLevel, rowNo, offset, EXPORT_CHUNK_SIZE);
            if (chunk.isEmpty()) {
                break;
            }
            for (ImportRowErrorEntity e : chunk) {
                writer.write(csvLine(e));
                written++;
            }
            if (chunk.size() < EXPORT_CHUNK_SIZE) {
                break;
            }
            offset += chunk.size();
        }
        writer.flush();
        log.info("导出错误报告 batchId={} rows={}", batchId, written);
        return written;
    }

    /** 单条错误行的 CSV 行（严格 CSV 转义：含分隔符/引号/换行的字段加引号并双写引号）。 */
    private static String csvLine(ImportRowErrorEntity e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getRowNo() == null ? "" : e.getRowNo()).append(',');
        sb.append(csv(e.getErrorLevel())).append(',');
        sb.append(csv(e.getErrorCode())).append(',');
        sb.append(csv(e.getErrorField())).append(',');
        sb.append(csv(e.getErrorMessage())).append(',');
        sb.append(csv(e.getRawData())).append('\n');
        return sb.toString();
    }

    /** CSV 字段转义（RFC 4180）。 */
    private static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** 导出附件名（{@code §9.2} 形态：{@code <资源>-<yyyyMMddHHmmss>.csv}，含 {@code batch_no}）。 */
    public static String exportFileName(ImportBatchEntity batch, LocalDateTime now) {
        String batchNo = batch.getBatchNo() == null ? String.valueOf(batch.getId()) : batch.getBatchNo();
        return "import-errors-" + batchNo + "-"
                + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
    }

    // =====================================================================
    // A5：模板清单
    // =====================================================================

    /**
     * 6 类模板的字段 / 必填 / 别名清单（{@code GET /api/v1/import/templates}，裁定 A5）。
     *
     * <p>数据源为 {@link ImportTemplateRegistry}（{@code §9.1}「别名表在单一模块维护」）；
     * 输出顺序与 {@code ImportBizType} 声明顺序一致，便于前端稳定渲染。
     */
    @Transactional(readOnly = true)
    public List<ImportTemplateView> templates() {
        List<ImportTemplateView> list = new ArrayList<>();
        for (ImportBizType bizType : ImportBizType.values()) {
            List<ImportFieldSpec> specs = ImportTemplateRegistry.fieldsOf(bizType);
            list.add(ImportTemplateView.of(bizType.name(), ImportTemplateRegistry.TEMPLATE_VERSION, specs));
        }
        return list;
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 分页参数校验（裁定 J4：越界 → {@code 400 COM-0007}，与 M3 既有口径一致）。 */
    private static void validatePage(int page, int pageSize) {
        List<ApiErrorDetail> details = new ArrayList<>();
        if (page < 1) {
            details.add(new ApiErrorDetail("page", "page 必须 ≥ 1", page));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            details.add(new ApiErrorDetail("pageSize", "pageSize 必须在 1.." + MAX_PAGE_SIZE + " 之间", pageSize));
        }
        if (!details.isEmpty()) {
            throw BizException.withDetails(ErrorCodes.COM_0007, "分页参数不合法", details);
        }
    }

    /** 解析 {@code yyyy-MM-dd} 日期参数；非法 → {@code 400 COM-0001} + details。 */
    private static LocalDate parseDate(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException ex) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail(field, "日期格式应为 yyyy-MM-dd", text)));
        }
    }

    /** 错误/警告行数（内存小值对象）。 */
    private record ErrorCounts(long errorRows, long warningRows) {
    }
}
