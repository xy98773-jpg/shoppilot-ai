package com.shoppilot.importdata.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportRowErrorEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * {@code GET /api/v1/import/batches/{id}/errors} 分页列表的元素（{@code API_CONTRACT §10.6}）。
 *
 * <p><b>{@code raw_data} 原样返回</b>（JSON 节点），保留<b>全部原始列含未映射列</b>
 * （§6-E.2 / §9.3），供用户对照修正。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ImportErrorRowView {

    private Long id;
    private Long batchId;
    private int rowNo;
    private String errorLevel;
    private String errorCode;
    private String errorField;
    private String errorMessage;

    /** 原始行数据（JSON；仅失败行写入，含未映射列）。 */
    private Object rawData;

    public static ImportErrorRowView of(ImportRowErrorEntity e, Object rawData) {
        ImportErrorRowView v = new ImportErrorRowView();
        v.setId(e.getId());
        v.setBatchId(e.getBatchId());
        v.setRowNo(e.getRowNo());
        v.setErrorLevel(e.getErrorLevel());
        v.setErrorCode(e.getErrorCode());
        v.setErrorField(e.getErrorField());
        v.setErrorMessage(e.getErrorMessage());
        v.setRawData(rawData);
        return v;
    }

    /**
     * 把 {@code raw_data} 的 JSON 字符串解析为 Jackson 树节点。
     *
     * <p>解析失败或为空时返回 {@code null}：<b>不因单行脏数据让整个列表接口失败</b>
     * （§5.13 无唯一约束，rawData 由历史数据写入，容错优先）。
     */
    public static Object parseRawData(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        } catch (Exception ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("_unparsable", raw);
            return fallback;
        }
    }
}
