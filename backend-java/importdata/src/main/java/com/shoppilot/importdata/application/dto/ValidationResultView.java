package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import lombok.Data;

/**
 * {@code POST /api/v1/import/batches/{id}/validate} 的成功响应体
 * （{@code API_CONTRACT §11.3} 步骤 3 的 200）。
 *
 * <h2>★ 计数口径（本批明确，写入 {@code DESIGN_REVIEW R35}）</h2>
 * <ul>
 *   <li>{@code error_rows} = <b>存在至少一条 {@code ERROR} 的<b>不同行</b></b>的数量；</li>
 *   <li>{@code warning_rows} = <b>存在 {@code WARNING} 且无 {@code ERROR}</b> 的不同行数量。</li>
 * </ul>
 * 这样 {@code import_row_errors} 的<b>物理行数 == error_rows + warning_rows</b>，
 * 与 §11.1「同一行可命中多条规则」不矛盾（同一行的多条 ERROR 仍只计 1 个 error_row）。
 *
 * <p>{@code errors} 只回传<b>前若干条</b>明细（上限与预览一致的 20 条），
 * 完整明细走 {@code GET /{id}/errors}（分页）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ValidationResultView {

    private Long batchId;
    private String status;
    private long totalRows;
    private long errorRows;
    private long warningRows;

    /** 错误/警告明细（前若干条，含 ERROR 与 WARNING）。 */
    private List<RowErrorView> errors;
}
