package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * {@code GET /api/v1/import/batches/{id}/preview} 的响应体（{@code API_CONTRACT §10.6}）。
 *
 * <h2>★ 预览行数口径（项目负责人裁定 B）</h2>
 * 已自查 {@code API_CONTRACT §11.3 / §10.6} 与 {@code IMPORT_WORKFLOW §6} 步骤 4，
 * 二者均<b>只有「前 N 行」表述、无明文 N</b>（§10.6 第 556 行原文「预览解析结果（前 N 行 + 列清单）」）。
 * 故按裁定取：<b>默认 20 行</b>，支持 {@code ?limit=} 覆盖，<b>硬上限 100</b>。
 *
 * <h2>★ {@code preview} 的键口径（本批明确，避免歧义）</h2>
 * 契约 §11.3 步骤 1 的 201 示例里 {@code preview[]} 以<b>目标字段名</b>为键，但那是
 * 「全流程终态 + 已完成映射」的示例。本端点是<b>解析结果预览</b>（{@code PARSED} 时尚无映射），
 * 故统一以<b>源列名</b>为键并附 {@code row_no}，映射关系由 {@link ImportColumnView#getMappedField()}
 * 单独表达。这样 {@code PARSED → MAPPED → VALIDATED} 各阶段返回结构恒定，前端无需分支处理。
 *
 * <p>{@code suggested_mapping} 为 §9.1 的自动映射建议（键 = 目标字段名，值 = 源列名）；
 * 契约无对应端点，随预览一并返回，避免新增契约外端点（红线）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ImportPreviewView {

    private Long batchId;
    private String status;
    private String bizType;
    private String templateVersion;

    /** 数据行总数（不含表头）。 */
    private long totalRows;

    /** 本次实际返回的预览行数（受 {@code limit} 与硬上限约束）。 */
    private int previewRows;

    /** 全部源列 + 每列的映射状态（§9.3 未映射列须标识）。 */
    private List<ImportColumnView> columns;

    /**
     * 前 N 行预览。
     *
     * <p>每行一个 {@code Map}：固定含 {@code row_no}（1 起，与 {@code import_row_errors.row_no}
     * 口径一致），其余键为源列名。
     */
    private List<Map<String, String>> preview;

    /** 自动映射建议（§9.1）。 */
    private Map<String, String> suggestedMapping;

    /** 可见工作表名单（§8.3；CSV 为空列表）。 */
    private List<String> sheets;

    /** 当前使用的工作表（CSV 为 {@code null}）。 */
    private String sheetName;

    /** 实际使用的编码（§8.2 要求记录于 {@code parse_options_json.encoding}）。 */
    private String encoding;
}
