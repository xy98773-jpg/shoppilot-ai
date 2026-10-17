package com.shoppilot.importdata.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

    /**
     * 可强制重导的批次状态（<b>终态</b>）。
     *
     * <p>反向定义更安全：{@code UPLOADED}/{@code PARSED}/{@code MAPPED}/{@code VALIDATED}/
     * {@code IMPORTING} 五个<b>非终态</b>一律拒（{@code IMP-4005}）——批次还在流程中时重导，
     * 会产生两条并行推进、共用同一份原始文件的批次，终态写回时互相覆盖。
     *
     * <p>{@code CANCELLED} 一并允许：它已释放槽位（{@code §6-E.4}），重导等价于「重新开始」，
     * 与 {@code FAILED} 同构。
     */
    private static final Set<ImportBatchStatus> REIMPORTABLE_STATUSES = Set.of(
            ImportBatchStatus.COMPLETED, ImportBatchStatus.PARTIAL_SUCCESS,
            ImportBatchStatus.FAILED, ImportBatchStatus.CANCELLED,
            ImportBatchStatus.DUPLICATE_REJECTED);

    /** 契约原文的 IMP-4001 提示语。 */
    private static final String IMP_4001_MESSAGE = "仅支持 CSV 或 Excel 文件，且不超过 20MB";

    /** {@code IMP-4011} 的契约原文提示语（{@code ERROR_CODES §2.5}）。 */
    private static final String IMP_4011_MESSAGE = "原始文件已不可用，请重新上传";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ImportBatchMapper importBatchMapper;
    private final LocalImportFileStorage fileStorage;
    private final AuditWriteService auditWriteService;

    /**
     * 强制重导的显式事务模板。
     *
     * <p>为什么<b>不</b>用 {@code @Transactional}：审计必须在<b>业务事务提交之后</b>才写。
     * {@code AuditWriteService#record} 走 {@code REQUIRES_NEW}，若在事务内调用而业务随后回滚，
     * 会留下一条「说成功了但没成功」的孤儿审计（{@code R45} 同类问题）。用 {@link TransactionTemplate}
     * 把「三步槽位操作」收在一个事务里、提交后再埋点，顺序显式可见。
     */
    private final TransactionTemplate txTemplate;

    public ImportUploadService(ImportBatchMapper importBatchMapper,
                               LocalImportFileStorage fileStorage,
                               AuditWriteService auditWriteService,
                               PlatformTransactionManager transactionManager) {
        this.importBatchMapper = importBatchMapper;
        this.fileStorage = fileStorage;
        this.auditWriteService = auditWriteService;
        this.txTemplate = new TransactionTemplate(transactionManager);
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
    // 强制重导（FORCE_REIMPORT，R84 / M3-B10.7）
    // =====================================================================

    /**
     * 强制重导（{@code POST /api/v1/import/batches/{id}/reimport}）。
     *
     * <h2>为什么需要它（{@code R84}）</h2>
     * 文件级幂等的依据是 {@code (team_id, file_hash, biz_type)}（{@code §9.3}/{@code §8.9}），
     * 而「改名绕不过哈希」（{@code §8.9} 要点 3）——同一份文件一旦导入成功，若没有本端点，
     * 用户<b>永远</b>无法重导（除非改内容，那正是幂等要防的绕过方式），导错数据后只能等运维改库。
     *
     * <h2>★ 三步槽位操作（{@code DATABASE_DESIGN §6-E.4} / {@code IMPORT_WORKFLOW §8.10}）</h2>
     * <ol>
     *   <li><b>先插入</b>新批次：{@code dedupe_hash = NULL}（暂不占槽位）、{@code status = UPLOADED}、
     *       {@code attempt_no = 原 + 1}，取得 {@code new_id}；</li>
     *   <li><b>释放旧槽位</b>：{@code UPDATE ... SET dedupe_hash = NULL,
     *       superseded_by_batch_id = :new_id WHERE id = :old_id}；</li>
     *   <li><b>占用新槽位</b>：{@code UPDATE ... SET dedupe_hash = :file_hash WHERE id = :new_id}。</li>
     * </ol>
     * 三步在<b>同一事务</b>内。顺序不能颠倒：若先让新批次占槽位，会直接撞
     * {@code uk_imp_team_hash_biz}（旧批次此刻仍占着同一个 {@code (team, hash, biz)}）。
     * 中间态（旧已释放、新未占位）只存在于本事务内，不会被外部查询看到（{@code §6-E.4}
     * 要求的「避免中间态被并发判定为可用槽位」）。
     *
     * <h2>副本字段</h2>
     * 复制 {@code storage_path} / {@code file_hash} / {@code file_size} / {@code file_name} /
     * {@code biz_type} / {@code template_version}；<b>不</b>复制 {@code field_mapping_json} /
     * {@code parse_options_json}（新批次从 {@code UPLOADED} 重新走流程，映射由用户当次确认）。
     * {@code duplicate_of_batch_id} 保持 {@code NULL}——它不是「被拒」，是用户主动重导。
     *
     * <h2>错误出口</h2>
     * <ul>
     *   <li>批次不存在 / 跨团队 → {@code 404 IMP-4007}（<b>不</b>用 {@code IMP-4004}：
     *       那是「文件重复」，会误导用户去改文件）；</li>
     *   <li>原批未终结（{@code UPLOADED}–{@code IMPORTING}）→ {@code 422 IMP-4005}；</li>
     *   <li>原批已被取代（{@code superseded_by_batch_id} 非空）→ {@code 422 IMP-4005}，避免链条分叉；</li>
     *   <li>原始文件丢失/不可读 → {@code 410 IMP-4011}；</li>
     *   <li>并发重导同一原批（守卫更新影响 0 行）或新槽位被占（唯一键冲突）→ {@code 409 IMP-4004}。
     *       两种情况下整个事务回滚，<b>不会</b>留下两个都占槽位的新批次。</li>
     * </ul>
     *
     * @param originBatchId 原批次 ID
     * @return 新建的批次（{@code status = UPLOADED}，与 upload 的 201 同一视图）
     */
    public ImportBatchEntity reimport(long originBatchId) {
        TeamContext.Snapshot snapshot = TeamContext.require();
        long teamId = snapshot.teamId();

        ImportBatchEntity created = txTemplate.execute(status -> doReimport(originBatchId, snapshot, teamId));
        if (created == null) {
            // 不可达（execute 有返回值）；仅为空安全，不吞任何异常
            throw new IllegalStateException("强制重导未返回批次");
        }
        // ★ 提交之后才埋点：审计随之生效，业务回滚则一条不留（不留「假成功」的审计）
        recordReimportAudit(snapshot, teamId, created, originBatchId);
        return created;
    }

    /** 三步槽位操作的具体实现（全程在同一事务内，由 {@link #reimport} 的模板包裹）。 */
    private ImportBatchEntity doReimport(long originBatchId, TeamContext.Snapshot snapshot, long teamId) {
        ImportBatchEntity origin = importBatchMapper.selectOne(new LambdaQueryWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getId, originBatchId)
                .eq(ImportBatchEntity::getTeamId, teamId));
        if (origin == null) {
            throw new BizException(ErrorCodes.IMP_4007, "导入批次不存在");
        }

        ImportBatchStatus status = parseStatus(origin.getStatus());
        if (status == null || !REIMPORTABLE_STATUSES.contains(status)) {
            throw BizException.withDetails(ErrorCodes.IMP_4005, "当前批次状态不允许强制重导",
                    List.of(new ApiErrorDetail("status",
                            "仅终态批次可强制重导（COMPLETED / PARTIAL_SUCCESS / FAILED / CANCELLED / "
                                    + "DUPLICATE_REJECTED），当前状态 " + origin.getStatus(),
                            origin.getStatus())));
        }
        if (origin.getSupersededByBatchId() != null) {
            throw BizException.withDetails(ErrorCodes.IMP_4005, "当前批次状态不允许强制重导",
                    List.of(new ApiErrorDetail("superseded_by_batch_id",
                            "该批次已被批次 " + origin.getSupersededByBatchId()
                                    + " 取代，不允许再次重导（避免重导链条分叉）",
                            String.valueOf(origin.getSupersededByBatchId()))));
        }
        assertOriginalFileReadable(origin);

        // ① 插入新批次（dedupe_hash = NULL，先不占槽位）
        ImportBatchEntity created = copyForReimport(origin, snapshot, teamId, nextBatchNo(teamId));
        importBatchMapper.insert(created);

        // ② 释放旧槽位（守卫：只有「尚未被取代」时才释放——这是并发重导的串行化点）
        int released = importBatchMapper.update(null, new LambdaUpdateWrapper<ImportBatchEntity>()
                .eq(ImportBatchEntity::getId, originBatchId)
                .eq(ImportBatchEntity::getTeamId, teamId)
                .isNull(ImportBatchEntity::getSupersededByBatchId)
                .set(ImportBatchEntity::getSupersededByBatchId, created.getId())
                .set(ImportBatchEntity::getDedupeHash, null)
                .set(ImportBatchEntity::getUpdatedBy, snapshot.userId()));
        if (released == 0) {
            // 并发的另一方已完成重导（其事务先提交）⇒ 本事务整体回滚，不留下占槽位的孤儿批次
            throw new BizException(ErrorCodes.IMP_4004, "该批次已被并发重导，请刷新后查看最新批次");
        }

        // ③ 占用新槽位
        try {
            importBatchMapper.update(null, new LambdaUpdateWrapper<ImportBatchEntity>()
                    .eq(ImportBatchEntity::getId, created.getId())
                    .eq(ImportBatchEntity::getTeamId, teamId)
                    .set(ImportBatchEntity::getDedupeHash, origin.getFileHash())
                    .set(ImportBatchEntity::getUpdatedBy, snapshot.userId()));
        } catch (DuplicateKeyException ex) {
            // 同一 (team, hash, biz) 已有别的批次占着槽位（例如并发上传）⇒ 409，不是 500
            throw new BizException(ErrorCodes.IMP_4004, "该文件已有占用中的导入批次，请刷新后查看");
        }

        ImportBatchEntity saved = importBatchMapper.selectById(created.getId());
        return saved == null ? created : saved;
    }

    /**
     * 组装重导产生的新批次（{@code status = UPLOADED}、{@code dedupe_hash = NULL}）。
     *
     * <p>其余计数列一律归零：新批次尚未解析/落库，{@code total_rows/success_rows/failed_rows}
     * 必须从 0 开始（复制原批计数会让「不变式 {@code success + failed = total}」在解析前就失真）。
     */
    private ImportBatchEntity copyForReimport(ImportBatchEntity origin, TeamContext.Snapshot snapshot,
                                              long teamId, String batchNo) {
        ImportBatchEntity e = new ImportBatchEntity();
        e.setTeamId(teamId);
        e.setBatchNo(batchNo);
        e.setBizType(origin.getBizType());
        e.setFileName(origin.getFileName());
        e.setFileHash(origin.getFileHash());
        e.setFileSize(origin.getFileSize());
        e.setStoragePath(origin.getStoragePath());
        e.setTemplateVersion(origin.getTemplateVersion());
        e.setStatus(ImportBatchStatus.UPLOADED.name());
        e.setDedupeHash(null);
        e.setDuplicateOfBatchId(null);
        e.setAttemptNo(origin.getAttemptNo() == null ? 2 : origin.getAttemptNo() + 1);
        e.setTotalRows(0);
        e.setSuccessRows(0);
        e.setFailedRows(0);
        Long uid = snapshot.userId();
        e.setCreatedBy(uid);
        e.setUpdatedBy(uid);
        return e;
    }

    /** 原文件必须仍可读（{@code IMPORT_WORKFLOW §8.9} / {@code IMP-4011}：文件是全量原文的唯一真相）。 */
    private void assertOriginalFileReadable(ImportBatchEntity origin) {
        String storagePath = origin.getStoragePath();
        if (storagePath == null || storagePath.isBlank()) {
            throw new BizException(ErrorCodes.IMP_4011, IMP_4011_MESSAGE);
        }
        try {
            Path file = Path.of(storagePath);
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new BizException(ErrorCodes.IMP_4011, IMP_4011_MESSAGE);
            }
        } catch (InvalidPathException ex) {
            // 路径非法与「文件丢了」对用户是同一件事（都无法重导），不暴露技术细节
            throw new BizException(ErrorCodes.IMP_4011, IMP_4011_MESSAGE);
        }
    }

    /** 状态文本 → 枚举；非法值返回 {@code null}（DB 有 CHECK，理论上不可达）。 */
    private static ImportBatchStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        for (ImportBatchStatus candidate : ImportBatchStatus.values()) {
            if (candidate.name().equals(status)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 审计 #10 {@code IMPORT_BATCH_FORCE_REIMPORT}（{@code DESIGN_BASIS §9.17} / {@code DATABASE_DESIGN §6-J.1}；
     * 枚举早已存在，本批<b>首次使用</b>，❌ 不新增动作码）。
     *
     * <p>{@code biz_type = IMPORT_BATCH}、{@code biz_id = 新批次 id}；原批次 id 记在
     * {@code change_summary}（人读）与 {@code after_json}（机读）两处——「哪一批被哪一批取代」
     * 事后无法从业务数据反推，只能靠这条留痕。
     */
    private void recordReimportAudit(TeamContext.Snapshot snapshot, long teamId,
                                     ImportBatchEntity created, long originBatchId) {
        auditWriteService.record(AuditEvent.builder()
                .teamId(teamId)
                .action(AuditAction.IMPORT_BATCH_FORCE_REIMPORT)
                .bizType("IMPORT_BATCH")
                .bizId(created.getId())
                .bizNo(created.getBatchNo())
                .operatorId(snapshot.userId())
                .operatorName(snapshot.username())
                .operatorRole(snapshot.roles().isEmpty() ? null : snapshot.roles().get(0))
                .result(AuditResult.SUCCESS)
                .changeSummary("强制重导：原批次 id=" + originBatchId + "，新批次 " + created.getBatchNo()
                        + "（第 " + created.getAttemptNo() + " 次尝试）")
                .after(Map.of("original_batch_id", originBatchId,
                        "new_batch_id", created.getId(),
                        "attempt_no", created.getAttemptNo()))
                .build());
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
