package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单条行错误/警告明细（{@code API_CONTRACT §11.3} 步骤 3 的 {@code errors[]} 元素）。
 *
 * <pre>
 * { "row_no": 45, "error_level": "ERROR", "error_code": "CAT-3001",
 *   "error_field": "sku_code", "error_message": "SKU 编码 SKU-XXX 不存在" }
 * </pre>
 *
 * <p>字段与 {@code import_row_errors} 同名（{@code DATABASE_DESIGN §5.13}），
 * 未含 {@code raw_data}（体积大，由 {@code GET /{id}/errors} 按需返回）。
 */
@Data
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RowErrorView {

    private int rowNo;
    private String errorLevel;
    private String errorCode;
    private String errorField;
    private String errorMessage;
}
