package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code POST /api/v1/import/batches/{id}/confirm} 的成功响应体
 * （{@code API_CONTRACT §11.3} 步骤 4 的 {@code 200}）。
 *
 * <pre>
 * { "batch_id": 9001, "status": "PARTIAL_SUCCESS", "total_rows": 3200,
 *   "success_rows": 3188, "failed_rows": 12,
 *   "started_at": "2026-10-14T08:00:01.000Z", "finished_at": "2026-10-14T08:00:04.500Z" }
 * </pre>
 *
 * <p><b>重复 confirm 的重放分支</b>（裁定 E2）返回<b>同一结构</b>，另由控制器补
 * 响应头 {@code Idempotent-Replayed: true}（{@code IMPORT_WORKFLOW §8.9}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ConfirmResultView {

    private Long batchId;
    private String status;
    private long totalRows;
    private long successRows;
    private long failedRows;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime startedAt;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime finishedAt;

    public static ConfirmResultView of(ImportBatchEntity e) {
        ConfirmResultView v = new ConfirmResultView();
        v.setBatchId(e.getId());
        v.setStatus(e.getStatus());
        v.setTotalRows(e.getTotalRows() == null ? 0 : e.getTotalRows());
        v.setSuccessRows(e.getSuccessRows() == null ? 0 : e.getSuccessRows());
        v.setFailedRows(e.getFailedRows() == null ? 0 : e.getFailedRows());
        v.setStartedAt(e.getStartedAt());
        v.setFinishedAt(e.getFinishedAt());
        return v;
    }
}
