package com.shoppilot.replenishment.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentItemEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 补货建议详情（{@code GET /api/v1/replenishment/items/{id}}、{@code PATCH} 的 {@code data}）。
 *
 * <p>契约依据：{@code API_CONTRACT §10.8}（详情含来源分析快照追溯）。
 *
 * <h2>字段口径</h2>
 * <ul>
 *   <li><b>表列全集</b>：{@code replenishment_items} 的业务列全部返回（本批需要 {@code version}
 *       作为下一次 {@code PATCH} 的并发令牌）；<b>不返回</b> {@code team_id}——团队是服务端上下文，
 *       与其它模块的只读视图口径一致（不把隔离维度暴露给客户端）。</li>
 *   <li>★ <b>数量列一律字符串</b>（{@code §9.4}）：{@code suggested_qty}/{@code qty} 固定
 *       {@code scale=3}、{@code purchase_price} 固定 {@code scale=4}，用
 *       {@code setScale(n).toPlainString()} 输出，避免 JSON number 的浮点误差。</li>
 *   <li>★ <b>{@code version} 是 number</b>（不是字符串）：它是并发令牌，客户端原样回传即可。</li>
 *   <li>★ 本批<b>不含</b> {@code sku_code}/{@code sku_name}/{@code warehouse_name}（列表批次再补，
 *       避免本批扩边界）。</li>
 *   <li>时间列统一 {@link UtcDateTimeSerializer}（ISO-8601 UTC），与其它模块一致。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplenishmentItemResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("source_analysis_item_id") Long sourceAnalysisItemId,
        @JsonProperty("run_id") Long runId,
        @JsonProperty("sku_id") Long skuId,
        @JsonProperty("product_id") Long productId,
        @JsonProperty("warehouse_id") Long warehouseId,
        @JsonProperty("status") String status,
        @JsonProperty("suggested_qty") String suggestedQty,
        @JsonProperty("qty") String qty,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("reason") String reason,
        @JsonProperty("supplier_id") Long supplierId,
        @JsonProperty("purchase_price") String purchasePrice,
        @JsonProperty("expected_arrival_date") LocalDate expectedArrivalDate,
        @JsonProperty("converted_po_item_ids") String convertedPoItemIds,
        @JsonProperty("converted_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime convertedAt,
        @JsonProperty("superseded_by_item_id") Long supersededByItemId,
        @JsonProperty("submitted_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime submittedAt,
        @JsonProperty("approved_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime approvedAt,
        @JsonProperty("approved_by") Long approvedBy,
        @JsonProperty("closed_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime closedAt,
        @JsonProperty("remark") String remark,
        @JsonProperty("created_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime createdAt,
        @JsonProperty("updated_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime updatedAt,
        @JsonProperty("created_by") Long createdBy,
        @JsonProperty("updated_by") Long updatedBy,
        @JsonProperty("version") Integer version) {

    private static final int QTY_SCALE = 3;
    private static final int PRICE_SCALE = 4;

    public static ReplenishmentItemResponse of(ReplenishmentItemEntity entity) {
        return new ReplenishmentItemResponse(
                entity.getId(),
                entity.getSourceAnalysisItemId(),
                entity.getRunId(),
                entity.getSkuId(),
                entity.getProductId(),
                entity.getWarehouseId(),
                entity.getStatus(),
                qtyText(entity.getSuggestedQty()),
                qtyText(entity.getQty()),
                entity.getRiskLevel(),
                entity.getReason(),
                entity.getSupplierId(),
                priceText(entity.getPurchasePrice()),
                entity.getExpectedArrivalDate(),
                entity.getConvertedPoItemIds(),
                entity.getConvertedAt(),
                entity.getSupersededByItemId(),
                entity.getSubmittedAt(),
                entity.getApprovedAt(),
                entity.getApprovedBy(),
                entity.getClosedAt(),
                entity.getRemark(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getVersion());
    }

    /** 数量 → 固定 3 位小数字符串（{@code 110.000}）。 */
    public static String qtyText(BigDecimal value) {
        return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 单价 → 固定 4 位小数字符串（{@code 12.3456}）。 */
    public static String priceText(BigDecimal value) {
        return value == null ? null : value.setScale(PRICE_SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
