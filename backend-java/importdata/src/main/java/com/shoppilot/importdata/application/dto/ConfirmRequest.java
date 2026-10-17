package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * {@code POST /api/v1/import/batches/{id}/confirm} 的请求体
 * （{@code API_CONTRACT §11.3} 步骤 4）。
 *
 * <pre>
 * { "skip_warning_rows": false, "expected_total_rows": 3200 }
 * </pre>
 *
 * <h2>★ {@code skip_warning_rows} 为「兼容保留」（M3-B4 裁定 F）</h2>
 * 本批支持落库的 4 类主数据（{@code PRODUCT}/{@code SKU}/{@code SUPPLIER}/{@code WAREHOUSE}）
 * <b>不产生 {@code WARNING} 行</b>——{@code IMP-4006}/{@code IMP-4014} 属 {@code SALES_DAILY} /
 * {@code INVENTORY_SNAPSHOT}（{@code IMPORT_WORKFLOW §10.2}）。故本字段<b>接受但不改变任何行为</b>：
 * 不得据此跳过任何行，否则会破坏裁定 D 的终态不变式
 * （{@code success_rows + failed_rows = total_rows}）。
 * <p><b>M3-B10.5 / R78 更新</b>：{@code INVENTORY_SNAPSHOT}/{@code SALES_DAILY} 的落库能力已补挂，
 * 这两类在校验阶段<b>会</b>产生 {@code IMP-4006} 的 {@code WARNING} 行；但
 * <b>本字段仍然不改变任何行为</b>——任务书未授权「按 WARNING 跳过」，且跳过会破坏终态不变式
 * （{@code success_rows + failed_rows = total_rows}）。语义留待后续里程碑裁定。
 *
 * <p>{@code expected_total_rows} 为客户端自证行数（裁定 E3）：提供且与本批次 {@code total_rows}
 * 不符 → {@code 409 COM-0009}。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ConfirmRequest {

    /** 兼容保留字段（本批不改变行为，见类 javadoc）。 */
    private Boolean skipWarningRows;

    /** 客户端期望的数据行总数（可空）；与实际不符 → {@code 409 COM-0009}。 */
    private Integer expectedTotalRows;
}
