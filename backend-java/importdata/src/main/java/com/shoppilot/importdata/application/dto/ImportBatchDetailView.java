package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 批次详情（{@code GET /api/v1/import/batches/{id}}，裁定 J2）。
 *
 * <p>除列表字段外，额外回带：文件级信息（{@code file_hash}/{@code file_size}/{@code attempt_no}）、
 * 幂等关联（{@code duplicate_of_batch_id}/{@code superseded_by_batch_id}）与
 * <b>错误统计</b>（{@code error_rows}/{@code warning_rows}）。
 *
 * <h2>★ 错误计数的口径与来源</h2>
 * {@code import_batches} <b>无</b> {@code error_rows}/{@code warning_rows} 列
 * （{@code DATABASE_DESIGN §5.12}；本批零新增迁移），故计数<b>实时</b>从 {@code import_row_errors}
 * 按 {@code (ERROR ⇒ error_rows / 仅 WARNING ⇒ warning_rows) 的不同行号} 统计，
 * 与 {@code POST /{id}/validate} 的 {@code ValidationResultView} 口径<b>完全一致</b>：
 * 同一行多条 ERROR 仍只计 1 个 {@code error_row}。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ImportBatchDetailView {

    private Long batchId;
    private String batchNo;
    private String bizType;
    private String fileName;
    private String status;
    private int totalRows;
    private int successRows;
    private int failedRows;
    private String errorCode;
    private String errorMessage;

    /** 存在 {@code ERROR} 的不同行数（实时统计）。 */
    private long errorRows;

    /** 存在 {@code WARNING} 且无 {@code ERROR} 的不同行数（实时统计）。 */
    private long warningRows;

    private String fileHash;
    private Long fileSize;
    private int attemptNo;
    private Long duplicateOfBatchId;
    private Long supersededByBatchId;
    private String templateVersion;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime startedAt;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime finishedAt;

    public static ImportBatchDetailView of(ImportBatchEntity e, long errorRows, long warningRows) {
        ImportBatchDetailView v = new ImportBatchDetailView();
        v.setBatchId(e.getId());
        v.setBatchNo(e.getBatchNo());
        v.setBizType(e.getBizType());
        v.setFileName(e.getFileName());
        v.setStatus(e.getStatus());
        v.setTotalRows(e.getTotalRows() == null ? 0 : e.getTotalRows());
        v.setSuccessRows(e.getSuccessRows() == null ? 0 : e.getSuccessRows());
        v.setFailedRows(e.getFailedRows() == null ? 0 : e.getFailedRows());
        v.setErrorCode(e.getErrorCode());
        v.setErrorMessage(e.getErrorMessage());
        v.setErrorRows(errorRows);
        v.setWarningRows(warningRows);
        v.setFileHash(e.getFileHash());
        v.setFileSize(e.getFileSize());
        v.setAttemptNo(e.getAttemptNo() == null ? 1 : e.getAttemptNo());
        v.setDuplicateOfBatchId(e.getDuplicateOfBatchId());
        v.setSupersededByBatchId(e.getSupersededByBatchId());
        v.setTemplateVersion(e.getTemplateVersion());
        v.setCreatedAt(e.getCreatedAt());
        v.setStartedAt(e.getStartedAt());
        v.setFinishedAt(e.getFinishedAt());
        return v;
    }
}
