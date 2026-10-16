package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 上传成功响应体（{@code API_CONTRACT §11.3} 步骤 1 的 201，<b>按 M3-T02 阶段裁剪</b>）。
 *
 * <h2>★ 为什么不含 {@code total_rows} / {@code columns} / {@code preview}</h2>
 * 契约的 201 示例是<b>全流程终态</b>示例（含解析产物），而解析属 M3-T03。
 * 本批只回填「文件级」字段，{@code status} 恒为 {@code UPLOADED}。
 * <b>严禁为凑示例伪造 {@code columns} / {@code preview} / {@code total_rows}</b>
 * （铁律⑦：禁虚构；详见 {@code DESIGN_REVIEW.md R33}）。
 *
 * <p>{@code data} 段字段名用 snake_case（契约 §2.1）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ImportBatchUploadView {

    private Long batchId;
    private String batchNo;
    private String bizType;
    private String fileName;
    private String fileHash;
    private Long fileSize;
    private String status;

    /** 创建时间（ISO-8601 UTC，毫秒 + {@code Z}；取 DB 默认值，非应用层臆造）。 */
    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    public static ImportBatchUploadView of(ImportBatchEntity e) {
        ImportBatchUploadView v = new ImportBatchUploadView();
        v.setBatchId(e.getId());
        v.setBatchNo(e.getBatchNo());
        v.setBizType(e.getBizType());
        v.setFileName(e.getFileName());
        v.setFileHash(e.getFileHash());
        v.setFileSize(e.getFileSize());
        v.setStatus(e.getStatus());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }
}
