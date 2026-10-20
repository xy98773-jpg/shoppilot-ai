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
 * {@code POST /api/v1/purchasing/orders/from-replenishment} 成功响应 {@code data}
 * （{@code API_CONTRACT §11.8} 成功响应，逐字段对齐）。
 *
 * <p>★ 数量与金额一律<b>字符串</b>（{@code §9.4}）：{@code total_qty} scale=3、
 * {@code total_amount} scale=2、明细 {@code ordered_qty} scale=3、{@code unit_price} scale=4。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurchaseOrderFromReplenishmentResponse(
        @JsonProperty("purchase_order_id") Long purchaseOrderId,
        @JsonProperty("po_no") String poNo,
        @JsonProperty("status") String status,
        @JsonProperty("source") String source,
        @JsonProperty("supplier_id") Long supplierId,
        @JsonProperty("warehouse_id") Long warehouseId,
        @JsonProperty("expected_arrival_date") LocalDate expectedArrivalDate,
        @JsonProperty("total_qty") String totalQty,
        @JsonProperty("total_amount") String totalAmount,
        @JsonProperty("items") List<LineItem> items,
        @JsonProperty("created_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime createdAt) {

    /** 采购明细行。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LineItem(
            @JsonProperty("line_no") Integer lineNo,
            @JsonProperty("sku_id") Long skuId,
            @JsonProperty("replenishment_item_id") Long replenishmentItemId,
            @JsonProperty("ordered_qty") String orderedQty,
            @JsonProperty("unit_price") String unitPrice,
            @JsonProperty("amount") String amount) {
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
