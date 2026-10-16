package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 预览响应里的<b>单列描述</b>（{@code IMPORT_WORKFLOW §9.3} 要求「未映射列在预览中标识」）。
 *
 * @param index        源列序号（0 起，与 {@code preview} 行的取值顺序一致）
 * @param sourceName   源列名（文件表头原样）
 * @param mappedField  已映射的目标字段名；<b>未映射为 {@code null}</b>（§9.3 的「未映射」标识）
 */
@Data
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ImportColumnView {

    private int index;
    private String sourceName;
    private String mappedField;
}
