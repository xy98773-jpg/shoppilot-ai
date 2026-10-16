package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;
import lombok.Data;

/**
 * {@code PUT /api/v1/import/batches/{id}/mapping} 的成功响应体（{@code API_CONTRACT §11.3} 步骤 2 的 200）。
 *
 * <pre>
 * { "batch_id": 9001, "status": "MAPPED",
 *   "field_mapping_json": { "sku_code": "SKU编码", ... } }
 * </pre>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class MappingResultView {

    private Long batchId;
    private String status;

    /** 已提交的字段映射（回显，供前端核对；{@code MAPPED} 后不可变，§11.3 业务行为）。 */
    private Map<String, String> fieldMappingJson;
}
