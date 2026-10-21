package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购单列表元素（{@code GET /api/v1/purchasing/orders} 的 {@code list[]}，M6-T03）。
 *
 * <p>与 {@link PurchaseOrderDetail} 的区别：列表<b>不含</b> {@code items[]} 明细（列表态不需要），
 * 其余列口径一致（数量/金额字符串、scale 与 §9.4 对齐）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurchaseOrderSummary(
        @JsonProperty("purchase_order_id") Long purchaseOrderId,
        @JsonProperty("po_no") String poNo,
        @JsonProperty("status") String status,
        @JsonProperty("source") String source,
        @JsonProperty("supplier_id") Long supplierId,
        @JsonProperty("warehouse_id") Long warehouseId,
        @JsonProperty("expected_arrival_date") LocalDate expectedArrivalDate,
        @JsonProperty("total_qty") String totalQty,
        @JsonProperty("received_qty") String receivedQty,
        @JsonProperty("total_amount") String totalAmount,
        @JsonProperty("remark") String remark,
        @JsonProperty("version") Integer version,
        @JsonProperty("created_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime createdAt) {
}
