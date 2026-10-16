package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 命中文件级幂等时 {@code IMP-4004} 的 {@code data}（{@code API_CONTRACT §11.3} 失败响应 1）。
 *
 * <p>指向"已存在的那个批次"，供前端直接跳到原批次或发起强制重导
 * （{@code POST /api/v1/import/batches/{id}/reimport}，M3-T11）。
 *
 * <p>字段名 snake_case（契约 §2.3 的 {@code data} 段同样按 §2.1 命名口径）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ImportDuplicateView {

    /** 已存在批次 id。 */
    private Long existingBatchId;

    /** 已存在批次号（编号快照；对象可能已进终态，故记快照而非只给 id）。 */
    private String existingBatchNo;

    public static ImportDuplicateView of(Long existingBatchId, String existingBatchNo) {
        ImportDuplicateView v = new ImportDuplicateView();
        v.setExistingBatchId(existingBatchId);
        v.setExistingBatchNo(existingBatchNo);
        return v;
    }
}
