package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;
import lombok.Data;

/**
 * {@code PUT /api/v1/import/batches/{id}/mapping} 的请求体（{@code API_CONTRACT §11.3} 步骤 2
 * + {@code IMPORT_WORKFLOW §9.2}）。
 *
 * <pre>
 * {
 *   "field_mapping_json": { "sku_code": "SKU编码", "qty": "净销量" },
 *   "parse_options_json": { "sheet_name": "Sheet1", "header_row": 1,
 *                           "encoding": "UTF-8", "date_format": "yyyy-MM-dd" }
 * }
 * </pre>
 *
 * <p><b>{@code field_mapping_json} 的键 = 目标字段名</b>（与 {@code DATA_DICTIONARY.md} 严格一致），
 * 值 = 源列名（§9.2 原文）。未知键不在模板列内 → {@code 400 IMP-4008}。
 *
 * <p>{@code parse_options_json} 可选：若其中 {@code sheet_name} 与当前不同，按 §8.3
 * 「重新选择 Sheet」语义重新解析并刷新表头与 {@code total_rows}（状态仍为 {@code PARSED}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PutMappingRequest {

    /** 字段映射（目标字段 → 源列名）；为 null 或空 → {@code 400 IMP-4008}。 */
    private Map<String, String> fieldMappingJson;

    /** 解析选项（可选）。 */
    private Map<String, Object> parseOptionsJson;
}
