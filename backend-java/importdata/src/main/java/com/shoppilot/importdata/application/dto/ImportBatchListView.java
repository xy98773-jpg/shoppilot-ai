package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 批次列表项（{@code GET /api/v1/import/batches}，{@code API_CONTRACT §10.6} F-IMP-04）。
 *
 * <p>字段名 snake_case（契约 §2.1）；分页元信息由 {@code PageResult} 承载，
 * 列表字段名为 {@code list}（裁定 J1）。
 *
 * <p>{@code status} 供前端按 {@code ImportBatchStatus} 上色（{@code IMPORT_WORKFLOW §14}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ImportBatchListView {

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

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime startedAt;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime finishedAt;

    public static ImportBatchListView of(ImportBatchEntity e) {
        ImportBatchListView v = new ImportBatchListView();
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
        v.setCreatedAt(e.getCreatedAt());
        v.setStartedAt(e.getStartedAt());
        v.setFinishedAt(e.getFinishedAt());
        return v;
    }
}
