package com.shoppilot.importdata.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodeHttpStatus;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.importdata.application.ImportConfirmService;
import com.shoppilot.importdata.application.ImportMappingService;
import com.shoppilot.importdata.application.ImportParseService;
import com.shoppilot.importdata.application.ImportQueryService;
import com.shoppilot.importdata.application.ImportUploadService;
import com.shoppilot.importdata.application.ImportUploadService.UploadOutcome;
import com.shoppilot.importdata.application.ImportValidationService;
import com.shoppilot.importdata.application.dto.ConfirmRequest;
import com.shoppilot.importdata.application.dto.ImportBatchUploadView;
import com.shoppilot.importdata.application.dto.ImportDuplicateView;
import com.shoppilot.importdata.application.dto.ImportErrorRowView;
import com.shoppilot.importdata.application.dto.ImportPreviewView;
import com.shoppilot.importdata.application.dto.MappingResultView;
import com.shoppilot.importdata.application.dto.PutMappingRequest;
import com.shoppilot.importdata.application.dto.ValidationResultView;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportRowErrorEntity;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportRowErrorMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 导入批次端点（M3-T02：上传 + M3-T03/T04/T05：预览/映射/校验/错误报告）。
 *
 * <h2>鉴权</h2>
 * {@code @RequireRole({"OWNER","ADMIN","OPERATOR","APPROVER"})}（契约「OPERATOR+」）；
 * {@code teamIdParam=""}——路径无 {@code {teamId}}，团队来自 JWT。
 *
 * <h2>★ 状态机（IMPORT_WORKFLOW §13）</h2>
 * 上传 {@code 201 UPLOADED} → {@code GET /{id}/preview}（首次 {@code UPLOADED→PARSED}，后续幂等）
 * → {@code PUT /{id}/mapping}（{@code PARSED→MAPPED}）→ {@code POST /{id}/validate}
 * （{@code MAPPED→VALIDATED}，全失败 {@code FAILED}）。错误报告 {@code GET /{id}/errors} 为只读。
 *
 * <h2>★ 裁定 B：{@code IMP-4004} / {@code IMP-4010} 的 {@code data} 由本控制器直接组装</h2>
 * {@code GlobalExceptionHandler.handleBiz} 返回 {@code ApiResponse<Void>}、{@code data} 恒为 null，
 * 故两个需回带数据的失败响应（重复导入 / 全部校验失败）都在此直接 {@code ResponseEntity} 返回，
 * <b>不动</b> {@code GlobalExceptionHandler} / {@code BizException}（R13 已闭环的响应包络）。
 *
 * <h2>错误出口一览</h2>
 * <ul>
 *   <li>非 multipart → {@code 415 COM-0006}；</li>
 *   <li>{@code file} / {@code bizType} 缺失或非法 → {@code 400 COM-0001} + details；</li>
 *   <li>扩展名 / 大小 / 空文件 → {@code 400 IMP-4001} + details[field=file]；</li>
 *   <li>Spring 侧大小超限 → 控制器本地 {@code @ExceptionHandler} 转 {@code 400 IMP-4001}；</li>
 *   <li>命中幂等 → {@code 409 IMP-4004} + data；</li>
 *   <li>状态不允许（预览/映射/校验）→ {@code 422 IMP-4005}（解析服务统一出口）；</li>
 *   <li>批次不存在 / 跨团队 → {@code 404 IMP-4007}（{@code loadOwned}）；</li>
 *   <li>全部数据行校验失败 → {@code 422 IMP-4010} + data。</li>
 * </ul>
 *
 * <p><b>M3-T02 范围</b>：仅上传端点；列表/详情/映射/校验/确认/重导属 M3-T03 起（本批一并补齐）。</p>
 */
@RestController
@RequestMapping("/api/v1/import/batches")
public class ImportBatchController {

    private static final Logger log = LoggerFactory.getLogger(ImportBatchController.class);

    /** 契约原文的 IMP-4001 / IMP-4004 / IMP-4009 提示语。 */
    private static final String IMP_4001_MESSAGE = "仅支持 CSV 或 Excel 文件，且不超过 20MB";
    private static final String IMP_4004_MESSAGE = "该文件已导入过，如需重新导入请使用强制重导";
    private static final String IMP_4009_MESSAGE = "批次存在错误行，请先修正或跳过错误行后再导入";

    /** CSV 导出的 Content-Type（{@code §9.2} / 裁定 J3）。 */
    private static final String CSV_CONTENT_TYPE = "text/csv;charset=UTF-8";

    private final ImportUploadService importUploadService;
    private final ImportParseService parseService;
    private final ImportMappingService mappingService;
    private final ImportValidationService validationService;
    private final ImportConfirmService confirmService;
    private final ImportQueryService queryService;
    private final ImportRowErrorMapper rowErrorMapper;

    public ImportBatchController(ImportUploadService importUploadService,
                                ImportParseService parseService,
                                ImportMappingService mappingService,
                                ImportValidationService validationService,
                                ImportConfirmService confirmService,
                                ImportQueryService queryService,
                                ImportRowErrorMapper rowErrorMapper) {
        this.importUploadService = importUploadService;
        this.parseService = parseService;
        this.mappingService = mappingService;
        this.validationService = validationService;
        this.confirmService = confirmService;
        this.queryService = queryService;
        this.rowErrorMapper = rowErrorMapper;
    }

    /**
     * 上传文件并创建导入批次（步骤 1-3）→ {@code 201}；命中文件级幂等 → {@code 409 IMP-4004}。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bizType") String bizType,
            @RequestParam(name = "templateVersion", required = false) String templateVersion) {

        UploadOutcome outcome = importUploadService.upload(file, bizType, templateVersion);

        if (outcome.duplicate()) {
            ImportDuplicateView data = ImportDuplicateView.of(
                    outcome.existingBatchId(), outcome.existingBatchNo());
            return ResponseEntity.status(ErrorCodeHttpStatus.resolve(ErrorCodes.IMP_4004))
                    .<ApiResponse<?>>body(ApiResponse.fail(
                            ErrorCodes.IMP_4004, IMP_4004_MESSAGE, null, data));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .<ApiResponse<?>>body(ApiResponse.success(ImportBatchUploadView.of(outcome.batch())));
    }

    /**
     * 解析与预览（步骤 4，裁定 A 惰性解析）。
     *
     * <p>首次（{@code UPLOADED}）触发解析并推进 {@code PARSED}；已解析及之后返回缓存/重放结果（幂等）。
     * 终态（{@code FAILED} 等）返回 {@code 422 IMP-4005}；解析失败返回 {@code 400 IMP-4002/4012}
     * （由全局出口按错误码映射）。
     */
    @GetMapping("/{id}/preview")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> preview(
            @PathVariable("id") Long id,
            @RequestParam(value = "limit", required = false) Integer limit) {
        ImportParseService.ParseOutcome outcome = parseService.preview(id, limit);
        if (outcome.failed()) {
            throw new BizException(outcome.errorCode(), outcome.errorMessage());
        }
        int effectiveLimit = ImportParseService.resolveLimit(limit);
        ImportPreviewView view = parseService.toView(outcome.batch(), outcome.sheet(), effectiveLimit);
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    /**
     * 提交字段映射（步骤 5，{@code PARSED → MAPPED}）。
     *
     * <p>两级校验失败 → {@code 400 IMP-4008}（合法性）/ {@code 400 IMP-4003}（完整性）；
     * 状态不允许 → {@code 422 IMP-4005}（解析服务统一出口）。
     */
    @PutMapping("/{id}/mapping")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> mapping(
            @PathVariable("id") Long id,
            @RequestBody PutMappingRequest request) {
        MappingResultView view = mappingService.putMapping(id, request);
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    /**
     * 全量校验（步骤 6，{@code MAPPED → VALIDATED}）。
     *
     * <p>全部数据行校验失败 → {@code 422 IMP-4010} + data（{@code batch_id/status/total_rows/error_rows}）；
     * 否则 {@code 200} 回校验结果（含前 N 条明细）。状态不允许 → {@code 422 IMP-4005}。
     */
    @PostMapping("/{id}/validate")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> validate(@PathVariable("id") Long id) {
        ValidationResultView view = validationService.validate(id);
        if (view.getTotalRows() > 0 && view.getErrorRows() == view.getTotalRows()) {
            return ResponseEntity.status(ErrorCodeHttpStatus.resolve(ErrorCodes.IMP_4010))
                    .<ApiResponse<?>>body(ApiResponse.fail(ErrorCodes.IMP_4010,
                            "所有数据行均未通过校验，请检查模板与数据", null, view));
        }
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    /**
     * 错误/警告明细分页（只读，{@code API_CONTRACT §10.6}）。
     *
     * <p>可按 {@code error_level}（ERROR/WARNING）/ {@code row_no} 过滤；分页元信息置于
     * {@code data}（{@code list/page/pageSize/total}）。批次不存在/跨团队 → {@code 404 IMP-4007}。
     */
    @GetMapping("/{id}/errors")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> errors(
            @PathVariable("id") Long id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "errorLevel", required = false) String errorLevel,
            @RequestParam(value = "rowNo", required = false) Integer rowNo) {
        if (page < 1 || pageSize < 1 || pageSize > 200) {
            throw new BizException(ErrorCodes.COM_0007, "分页参数不合法（page≥1，pageSize∈[1,200]）");
        }
        TeamContext.Snapshot snapshot = TeamContext.require();
        // 批次不存在 / 跨团队 → 404 IMP-4007（错误行查询本身也受租户线约束，双重保险）
        parseService.loadOwned(id, snapshot.teamId());

        long offset = (long) (page - 1) * pageSize;
        List<ImportRowErrorEntity> entities =
                rowErrorMapper.selectPageByBatch(id, errorLevel, rowNo, offset, pageSize);
        long total = rowErrorMapper.countByBatch(id, errorLevel, rowNo);
        List<ImportErrorRowView> list = entities.stream()
                .map(e -> ImportErrorRowView.of(e, ImportErrorRowView.parseRawData(e.getRawData())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(PageResult.of(total, page, pageSize, list)));
    }

    /**
     * 批次列表（{@code API_CONTRACT §10.6} F-IMP-04，裁定 J1）。
     *
     * <p>分页字段名 {@code list}；支持 {@code status}/{@code bizType}/{@code from}/{@code to} 筛选；
     * 只返回本团队；固定排序 {@code created_at DESC, id DESC}。
     */
    @GetMapping
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return ResponseEntity.ok(ApiResponse.success(
                queryService.listBatches(page, pageSize, status, bizType, from, to)));
    }

    /**
     * 批次详情（裁定 J2）：状态、行数统计、错误统计（ERROR/WARNING 计数）、
     * {@code started_at}/{@code finished_at}、{@code error_code}/{@code error_message}。
     *
     * <p>不存在 / 不属于当前团队 → {@code 404 IMP-4007}
     * （{@code ERROR_CODES §2.5} 的 {@code IMP-4007} HTTP 列即 404，且其触发场景原文含「跨团队」）。
     */
    @GetMapping("/{id}")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> detail(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success(queryService.detail(id)));
    }

    /**
     * 确认导入落库（步骤 9-11，{@code VALIDATED → IMPORTING → COMPLETED/PARTIAL_SUCCESS/FAILED}）。
     *
     * <h2>三个出口</h2>
     * <ul>
     *   <li>{@code 200}：成功（含重放分支，另补响应头 {@code Idempotent-Replayed: true}，裁定 E2）；</li>
     *   <li>{@code 422 IMP-4009}：存在 {@code ERROR} 行（前置门，裁定 D1）——{@code data} 回带
     *       {@code batch_id/status/error_rows}（契约 §11.3 失败响应 2 明文，由本控制器组装）；</li>
     *   <li>其余错误码（{@code COM-0001} 缺幂等键 / {@code COM-0009} / {@code IMP-4005} /
     *       {@code IMP-4013}）按 {@code ErrorCodeHttpStatus} 由全局出口映射。</li>
     * </ul>
     */
    @PostMapping("/{id}/confirm")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> confirm(
            @PathVariable("id") Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ConfirmRequest request) {
        ImportConfirmService.ConfirmOutcome outcome = confirmService.confirm(id, idempotencyKey, request);
        if (outcome.isBlocked()) {
            ImportConfirmService.ConfirmOutcome.ErrorGate gate = outcome.errorGate();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("batch_id", gate.batchId());
            data.put("status", gate.status());
            data.put("error_rows", gate.errorRows());
            return ResponseEntity.status(ErrorCodeHttpStatus.resolve(ErrorCodes.IMP_4009))
                    .<ApiResponse<?>>body(ApiResponse.fail(ErrorCodes.IMP_4009,
                            IMP_4009_MESSAGE, null, data));
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.<ApiResponse<?>>body(ApiResponse.success(outcome.view()));
    }

    /**
     * 错误报告 CSV 导出（裁定 J3）。
     *
     * <p>{@code Content-Type: text/csv; charset=UTF-8}；<b>UTF-8 BOM 开头</b>（兼容 Excel 打开中文）；
     * 附件名含 {@code batch_no}；行数与 {@code import_row_errors} 该批次记录数一致
     * （筛选语义与 {@code GET /{id}/errors} 相同）。
     *
     * <p><b>同步流式写出</b>：先在请求线程完成归属判定（不存在/跨团队 → {@code 404 IMP-4007}），
     * 再按 500 条一页拉取并立即写出——既不把全部错误行读入内存，也避免
     * {@code StreamingResponseBody} 的异步线程丢失 {@code TeamContext}（租户线依赖它）。
     */
    @GetMapping("/{id}/errors/export")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public void exportErrors(
            @PathVariable("id") Long id,
            @RequestParam(value = "errorLevel", required = false) String errorLevel,
            @RequestParam(value = "rowNo", required = false) Integer rowNo,
            HttpServletResponse response) throws IOException {
        ImportBatchEntity batch = queryService.loadOwnedForExport(id);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(CSV_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                + ImportQueryService.exportFileName(batch, LocalDateTime.now(ZoneOffset.UTC)) + "\"");
        try (Writer writer = response.getWriter()) {
            writer.write('\uFEFF'); // UTF-8 BOM（EF BB BF）
            queryService.writeErrorsCsv(id, errorLevel, rowNo, writer);
        }
    }

    /**
     * Spring 侧 multipart 解析失败 → {@code 400 IMP-4001}（不许裸抛 {@code 413 COM-0008}）。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException ex) {
        String reason = isSizeExceeded(ex) ? "文件大小超过上限 20MB" : "文件上传内容解析失败";
        log.warn("导入文件上传被拒: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCodeHttpStatus.resolve(ErrorCodes.IMP_4001))
                .body(ApiResponse.fail(ErrorCodes.IMP_4001, IMP_4001_MESSAGE,
                        List.of(new ApiErrorDetail("file", reason, null))));
    }

    /** 是否「体积超限」：识别 {@code MaxUploadSizeExceeded} 与容器侧 {@code SizeLimitExceeded}。 */
    private static boolean isSizeExceeded(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof MaxUploadSizeExceededException) {
                return true;
            }
            if (t.getClass().getSimpleName().contains("SizeLimitExceeded")) {
                return true;
            }
        }
        return false;
    }
}
