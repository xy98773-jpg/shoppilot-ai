package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购单详情（{@code GET /api/v1/purchasing/orders/{id}} 的 {@code data}，M6-T03）。
 *
 * <p>★ <b>M6-T04 依赖本结构</b>：到货单草稿用 {@code id}/{@code warehouse_id}/{@code status}
 * 与 {@code items[]}（每项 {@code id}/{@code sku_id}/{@code ordered_qty}/{@code received_qty}/
 * {@code unit_price}）计算「待收数量」。字段名/语义由 {@code DESIGN_REVIEW §5.70.2} 写死，
 * <b>改动须先报项目负责人</b>。
 *
 * <p>★ 数量 scale=3、单价 scale=4、金额 scale=2，一律<b>字符串</b>输出（{@code §9.4}）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurchaseOrderDetail(
        @JsonProperty("purchase_order_id") Long purchaseOrderId,
        @JsonProperty("po_no") String poNo,
        @JsonProperty("status") String status,
        @JsonProperty("source") String source,
        @JsonProperty("supplier_id") Long supplierId,
        @JsonProperty("warehouse_id") Long warehouseId,
        @JsonProperty("expected_arrival_date") LocalDate expectedArrivalDate,
        @JsonProperty("total_qty") String totalQty,
        @JsonProperty("total_amount") String totalAmount,
        @JsonProperty("received_qty") String receivedQty,
        @JsonProperty("remark") String remark,
        @JsonProperty("confirmed_by") Long confirmedBy,
        @JsonProperty("confirmed_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime confirmedAt,
        @JsonProperty("ordered_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime orderedAt,
        @JsonProperty("cancelled_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime cancelledAt,
        @JsonProperty("cancel_reason") String cancelReason,
        @JsonProperty("closed_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime closedAt,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("version") Integer version,
        @JsonProperty("created_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime createdAt,
        @JsonProperty("items") List<LineItem> items) {

    /**
     * 采购明细行（含到货进度）。
     *
     * @param id                  明细行主键（= {@code purchase_order_item_id}，M6-T04 按它登记到货）
     * @param receivedQty         该行已收数量（到货进度；派生口径 = Σ 已确认到货单）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LineItem(
            @JsonProperty("id") Long id,
            @JsonProperty("line_no") Integer lineNo,
            @JsonProperty("sku_id") Long skuId,
            @JsonProperty("product_id") Long productId,
            @JsonProperty("replenishment_item_id") Long replenishmentItemId,
            @JsonProperty("ordered_qty") String orderedQty,
            @JsonProperty("received_qty") String receivedQty,
            @JsonProperty("unit_price") String unitPrice,
            @JsonProperty("amount") String amount,
            @JsonProperty("expected_arrival_date") LocalDate expectedArrivalDate) {
    }

    public static String qtyText(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    public static String amountText(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String priceText(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
