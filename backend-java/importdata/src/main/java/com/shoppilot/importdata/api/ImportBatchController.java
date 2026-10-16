package com.shoppilot.importdata.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.error.ErrorCodeHttpStatus;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.importdata.application.ImportUploadService;
import com.shoppilot.importdata.application.ImportUploadService.UploadOutcome;
import com.shoppilot.importdata.application.dto.ImportBatchUploadView;
import com.shoppilot.importdata.application.dto.ImportDuplicateView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 导入批次端点（M3-T02：{@code API_CONTRACT §11.3} 步骤 1，
 * 对应 {@code IMPORT_WORKFLOW §6} 步骤 1 / 2 / 3）。
 *
 * <h2>鉴权</h2>
 * {@code @RequireRole({"OWNER","ADMIN","OPERATOR","APPROVER"})}（契约「OPERATOR+」，四个角色名
 * 显式列举，漏掉 APPROVER 是最典型错误，{@code USER_ROLES §2.2} #2）；{@code teamIdParam=""}
 * ——路径无 {@code {teamId}}，团队来自 JWT。
 *
 * <h2>★ 裁定 A：响应只返回「文件级」字段</h2>
 * 契约 201 示例含 {@code status=PARSED} / {@code total_rows} / {@code columns} / {@code preview}，
 * 那是<b>全流程终态</b>示例（解析产物属 M3-T03）。本批 201 只返回文件级字段且
 * {@code status=UPLOADED}；<b>严禁为凑示例伪造解析字段</b>（铁律⑦ 禁虚构，见 {@code DESIGN_REVIEW R33}）。
 *
 * <h2>★ 裁定 B：{@code IMP-4004} 的 {@code data} 由本控制器直接组装</h2>
 * {@code GlobalExceptionHandler.handleBiz} 返回 {@code ApiResponse<Void>}、{@code data} 恒为 null，
 * 而契约要求失败响应带 {@code data:{existing_batch_id, existing_batch_no}}。
 * 采用「{@link ApiResponse} 新增带 data 的 {@code fail} 重载 + 本控制器直接返回 409」，
 * <b>不动</b> {@code GlobalExceptionHandler} / {@code BizException}（R13 已闭环的响应包络）。
 *
 * <h2>错误出口一览</h2>
 * <ul>
 *   <li>非 multipart → {@code 415 COM-0006}（{@code consumes} 声明 + 全局出口）；</li>
 *   <li>{@code file} / {@code bizType} 缺失 → {@code 400 COM-0001} + details（全局参数校验出口）；</li>
 *   <li>{@code bizType} 非法 → {@code 400 COM-0001} + details（服务层显式抛出）；</li>
 *   <li>扩展名 / 大小 / 空文件 → {@code 400 IMP-4001} + details[field=file]；</li>
 *   <li>Spring 侧大小超限（{@link MaxUploadSizeExceededException}）→ 本控制器本地
 *       {@code @ExceptionHandler} 转 {@code 400 IMP-4001}（<b>不许</b>让 Spring 裸抛 413）；</li>
 *   <li>命中幂等 → {@code 409 IMP-4004} + data。</li>
 * </ul>
 *
 * <p><b>M3-T02 范围</b>：仅本端点；列表/详情/映射/校验/确认/重导属 M3-T03 起。
 */
@RestController
@RequestMapping("/api/v1/import/batches")
public class ImportBatchController {

    private static final Logger log = LoggerFactory.getLogger(ImportBatchController.class);

    /** 契约原文的 IMP-4001 / IMP-4004 提示语。 */
    private static final String IMP_4001_MESSAGE = "仅支持 CSV 或 Excel 文件，且不超过 20MB";
    private static final String IMP_4004_MESSAGE = "该文件已导入过，如需重新导入请使用强制重导";

    private final ImportUploadService importUploadService;

    public ImportBatchController(ImportUploadService importUploadService) {
        this.importUploadService = importUploadService;
    }

    /**
     * 上传文件并创建导入批次（步骤 1-3）→ {@code 201}；命中文件级幂等 → {@code 409 IMP-4004}。
     *
     * @param file            multipart 文件字段（必填）
     * @param bizType         业务类型（必填，6 值之一）
     * @param templateVersion 模板版本（可选）
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bizType") String bizType,
            @RequestParam(name = "templateVersion", required = false) String templateVersion) {

        UploadOutcome outcome = importUploadService.upload(file, bizType, templateVersion);

        if (outcome.duplicate()) {
            // 裁定 B：不走 BizException（全局出口 data 恒 null），此处直接组装带 data 的 409
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
     * Spring 侧 multipart 解析失败 → {@code 400 IMP-4001}（不许裸抛 {@code 413 COM-0008}）。
     *
     * <p>控制器级 {@code @ExceptionHandler} 优先于 {@code @RestControllerAdvice}，
     * 因此可以在<b>不动全局出口</b>的前提下把超限翻译为导入域错误码。
     * 前提：{@code spring.servlet.multipart.resolve-lazily=true}——让解析延后到参数绑定阶段，
     * 异常才发生在 handler 内（否则在 {@code DispatcherServlet} 阶段抛出，控制器级处理器接不到）。
     *
     * <p>{@link MaxUploadSizeExceededException} 是 {@link MultipartException} 子类，
     * 故此处一并覆盖「容器包装后再抛」的两种形态。
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
