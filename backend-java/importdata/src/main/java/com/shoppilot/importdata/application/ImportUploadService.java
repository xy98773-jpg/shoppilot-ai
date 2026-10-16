package com.shoppilot.importdata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.importdata.domain.model.ImportBatchStatus;
import com.shoppilot.importdata.domain.model.ImportBizType;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import com.shoppilot.importdata.infrastructure.persistence.mapper.ImportBatchMapper;
import com.shoppilot.importdata.infrastructure.storage.LocalImportFileStorage;
import com.shoppilot.importdata.infrastructure.storage.LocalImportFileStorage.StoredFile;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 导入「步骤 1-3」应用服务：文件上传 → 文件安全检查 → 哈希与文件级幂等（M3-T02）。
 *
 * <p><b>严格对齐 {@code IMPORT_WORKFLOW.md §6}</b> 步骤 1/2/3 与
 * {@code API_CONTRACT.md §11.3} 步骤 1、{@code DATABASE_DESIGN.md §6-E}、
 * {@code DESIGN_BASIS.md §9.5 / §9.15 / §6 / §15.1}。
 *
 * <h2>本批边界</h2>
 * <ul>
 *   <li>✅ 落盘 + 流式 SHA-256 + 幂等槽位判定 + 建批次 + 审计（{@code IMPORT_BATCH_CREATE}）；</li>
 *   <li>❌ <b>不做</b>解析 / 字段映射 / 行级校验 / 业务落库 / 强制重导（M3-T03~T06、T11）。</li>
 * </ul>
 *
 * <h2>★ 幂等槽位（{@code §6-E.4}；优先于 API_CONTRACT 的"成功批次"表述）</h2>
 * <ul>
 *   <li><b>占用</b>：{@code UPLOADED..PARTIAL_SUCCESS}（见 {@link ImportBatchStatus#occupiesDedupeSlot()}）；</li>
 *   <li><b>释放</b>：{@code FAILED} / {@code CANCELLED} / {@code DUPLICATE_REJECTED} / 被取代。</li>
 * </ul>
 * 命中重复 → 仍<b>插入</b>一条 {@code DUPLICATE_REJECTED} 记录
 * （{@code dedupe_hash=NULL} 才能绕过唯一键，{@code attempt_no=原+1}，指向原批次）并回 {@code 409 IMP-4004}。
 *
 * <h2>团队隔离（{@code DESIGN_BASIS §6} 规则 3）</h2>
 * {@code teamId} <b>只</b>来自 {@code TeamContext}；跨团队同 {@code file_hash} 允许共存
 * （幂等只在团队内，{@code M3-T01 ImportSchemaConstraintTest} 已证物理可行）。
 */
@Service
public class ImportUploadService {

    /** 文件大小上限 20MB（{@code API_CONTRACT §10.6} / {@code IMPORT_WORKFLOW §8.1}）。 */
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    /** 扩展名白名单（小写比较；{@code IMPORT_WORKFLOW §8.1}）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "xlsx", "xls");

    /** 契约原文的 IMP-4001 提示语。 */
    private static final String IMP_4001_MESSAGE = "仅支持 CSV 或 Excel 文件，且不超过 20MB";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ImportBatchMapper importBatchMapper;
    private final LocalImportFileStorage fileStorage;
    private final AuditWriteService auditWriteService;

    public ImportUploadService(ImportBatchMapper importBatchMapper,
                               LocalImportFileStorage fileStorage,
                               AuditWriteService auditWriteService) {
        this.importBatchMapper = importBatchMapper;
        this.fileStorage = fileStorage;
        this.auditWriteService = auditWriteService;
    }

    /**
     * 上传并建批次（步骤 1-3）。
     *
     * <p>事务边界：{@code IMPORT_WORKFLOW §6} 步骤 3 要求「幂等判定 + 幂等记录写入」<b>单事务</b>；
     * 本方法整体处在同一事务内（含落盘），保证「判定 → 插入」之间不出现并发窗口。
     *
     * @param file             multipart 文件（非空）
     * @param bizTypeText      业务类型文本（6 值之一）
     * @param templateVersion  模板版本（可选）
     * @return 上传结果；{@link UploadOutcome#duplicate()} 为真表示命中幂等
     */
    @Transactional
    public UploadOutcome upload(MultipartFile file, String bizTypeText, String templateVersion) {
        TeamContext.Snapshot snapshot = TeamContext.require();
        long teamId = snapshot.teamId();

        // ---- 步骤 2：文件安全检查（任一不通过 → 400 IMP-4001）----
        assertFileAllowed(file);

        // bizType：缺失由 Spring 的 MissingServletRequestParameter 出口（400 COM-0001）处理，
        // 此处只处理「在 6 值之外」的非法枚举值。
        ImportBizType bizType = ImportBizType.parseOrNull(bizTypeText);
        if (bizType == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("biz_type", "bizType 取值非法，应为 PRODUCT、SKU、SUPPLIER、"
                            + "WAREHOUSE、INVENTORY_SNAPSHOT、SALES_DAILY 之一")));
        }

        // ---- 步骤 3（前半）：批次号 → 流式落盘 + SHA-256 ----
        String batchNo = nextBatchNo(teamId);
        StoredFile stored;
        try (InputStream in = file.getInputStream()) {
            stored = fileStorage.store(in, teamId, batchNo, file.getOriginalFilename());
        } catch (IOException ex) {
            throw new IllegalStateException("导入文件读取失败", ex);
        }

        // ---- 步骤 3（后半）：幂等判定 + 插入（单事务）----
        ImportBatchEntity existing = findOccupyingBatch(teamId, stored.sha256Hex(), bizType);
        ImportBatchEntity entity = buildBatch(snapshot, teamId, batchNo, bizType, templateVersion, file, stored,
                existing, existing == null ? 1 : existing.getAttemptNo() + 1);
        importBatchMapper.insert(entity);

        if (existing != null) {
            // 命中重复：不埋审计（无专属 AuditAction；❌ 不自造枚举值），回 409 由 Controller 组装
            return UploadOutcome.duplicate(entity, existing);
        }

        // 新建批次：回读 DB 生成的 created_at（不臆造时间戳），再埋审计
        ImportBatchEntity saved = importBatchMapper.selectById(entity.getId());
        recordAudit(snapshot, teamId, saved == null ? entity : saved, bizType);
        return UploadOutcome.created(saved == null ? entity : saved);
    }

    // =====================================================================
    // 步骤 2：文件安全检查
    // =====================================================================

    /**
     * 文件安全检查：存在且非空、扩展名白名单、大小 ≤ 20MB；任一不通过 → {@code 400 IMP-4001}。
     *
     * <p>Spring 侧的 {@code max-file-size} 超限（{@code MaxUploadSizeExceededException}）由
     * Controller 的本地 {@code @ExceptionHandler} 转译（不让 Spring 裸抛 413），见控制器 javadoc。
     */
    private void assertFileAllowed(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidFile("文件内容为空");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw invalidFile("文件名缺失");
        }
        if (!ALLOWED_EXTENSIONS.contains(extensionOf(original))) {
            throw invalidFile("仅支持 .csv / .xlsx / .xls 文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw invalidFile("文件大小超过上限 20MB");
        }
    }

    private static BizException invalidFile(String reason) {
        return BizException.withDetails(ErrorCodes.IMP_4001, IMP_4001_MESSAGE,
                List.of(new ApiErrorDetail("file", reason, null)));
    }

    /** 取小写扩展名（无点则返回空串）。 */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // =====================================================================
    // 步骤 3：批次号 / 幂等 / 落库 / 审计
    // =====================================================================

    /**
     * 生成批次号 {@code IMP-{yyyyMMdd}-{6 位序号}}（同团队当日最大序号 + 1）。
     *
     * <p><b>已知弱项（R33 登记）</b>：DDL 未给 {@code batch_no} 唯一键，并发同序号时会撞号；
     * 本批按任务书口径「重试一次即可」，不再加迁移 / 唯一键。
     */
    private String nextBatchNo(long teamId) {
        String day = LocalDate.now(ZoneOffset.UTC).format(DAY);
        Long max = importBatchMapper.selectMaxDailySeq(teamId, day);
        long seq = (max == null ? 0L : max) + 1L;
        return String.format("IMP-%s-%06d", day, seq);
    }

    /**
     * 查占用槽位的既有批次（同团队 + 同 {@code file_hash} + 同 {@code biz_type} + 状态占用）。
     *
     * <p>Wrapper 已显式带 {@code team_id}（服务端来源），租户线再兜底追加一次。
     */
    private ImportBatchEntity findOccupyingBatch(long teamId, String fileHash, ImportBizType bizType) {
        List<ImportBatchEntity> rows = importBatchMapper.selectList(new LambdaQueryWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getTeamId, teamId)
                .eq(ImportBatchEntity::getDedupeHash, fileHash)
                .eq(ImportBatchEntity::getBizType, bizType.name())
                .in(ImportBatchEntity::getStatus, ImportBatchStatus.occupyingNames())
                .orderByDesc(ImportBatchEntity::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 组装待插入的批次。
     *
     * <p>命中重复时：{@code status=DUPLICATE_REJECTED}、{@code dedupeHash=NULL}
     * （唯一键建在可空槽位列上，置 NULL 才能插入）、{@code duplicateOfBatchId=原批次 id}、
     * {@code attemptNo=原+1}；未命中时 {@code status=UPLOADED}、{@code dedupeHash=fileHash}（占用槽位）。
     */
    private ImportBatchEntity buildBatch(TeamContext.Snapshot snapshot, long teamId, String batchNo,
                                         ImportBizType bizType, String templateVersion, MultipartFile file,
                                         StoredFile stored, ImportBatchEntity existing, int attemptNo) {
        ImportBatchEntity e = new ImportBatchEntity();
        e.setTeamId(teamId);
        e.setBatchNo(batchNo);
        e.setBizType(bizType.name());
        e.setFileName(file.getOriginalFilename());
        e.setFileHash(stored.sha256Hex());
        e.setFileSize(stored.size());
        e.setStoragePath(stored.storagePath());
        e.setTemplateVersion(templateVersion == null || templateVersion.isBlank() ? null : templateVersion.trim());
        e.setStatus(existing == null
                ? ImportBatchStatus.UPLOADED.name()
                : ImportBatchStatus.DUPLICATE_REJECTED.name());
        e.setDedupeHash(existing == null ? stored.sha256Hex() : null);
        e.setDuplicateOfBatchId(existing == null ? null : existing.getId());
        e.setAttemptNo(attemptNo);
        e.setTotalRows(0);
        e.setSuccessRows(0);
        e.setFailedRows(0);
        Long uid = snapshot.userId();
        e.setCreatedBy(uid);
        e.setUpdatedBy(uid);
        return e;
    }

    /**
     * 审计 #{@code 8 IMPORT_BATCH_CREATE}（{@code DESIGN_BASIS §9.17}，枚举已存在，无需新增）。
     *
     * <p>{@code biz_type=IMPORT_BATCH}、{@code result=SUCCESS}；
     * 走 {@link AuditWriteService}（REQUIRES_NEW 独立事务，失败不影响业务）。
     */
    private void recordAudit(TeamContext.Snapshot snapshot, long teamId, ImportBatchEntity batch,
                             ImportBizType bizType) {
        auditWriteService.record(AuditEvent.builder()
                .teamId(teamId)
                .action(AuditAction.IMPORT_BATCH_CREATE)
                .bizType("IMPORT_BATCH")
                .bizId(batch.getId())
                .bizNo(batch.getBatchNo())
                .operatorId(snapshot.userId())
                .operatorName(snapshot.username())
                .operatorRole(snapshot.roles().isEmpty() ? null : snapshot.roles().get(0))
                .result(AuditResult.SUCCESS)
                .changeSummary("创建导入批次，业务类型=" + bizType.name())
                .build());
    }

    /**
     * 上传结果。
     *
     * @param batch            本次插入的批次（命中重复时即 {@code DUPLICATE_REJECTED} 记录）
     * @param duplicate        是否命中文件级幂等
     * @param existingBatchId  命中时指向的已存在批次 id（未命中为 {@code null}）
     * @param existingBatchNo  命中时指向的已存在批次号（未命中为 {@code null}）
     */
    public record UploadOutcome(ImportBatchEntity batch, boolean duplicate,
                                Long existingBatchId, String existingBatchNo) {

        static UploadOutcome created(ImportBatchEntity batch) {
            return new UploadOutcome(batch, false, null, null);
        }

        static UploadOutcome duplicate(ImportBatchEntity batch, ImportBatchEntity existing) {
            return new UploadOutcome(batch, true, existing.getId(), existing.getBatchNo());
        }
    }
}
