package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 到货单列表元素（{@code GET /api/v1/purchasing/receipts} 的 {@code list[]}；M6-T04）。
 *
 * <p>★ 与 {@link GoodsReceiptDetail} 的区别：列表<b>不含</b> {@code items[]}（列表态不需要，
 * 且明细表有 {@code idx_gri_team_receipt} 索引，展开会让列表 N+1 查询），
 * 其余列口径一致（数量/金额字符串、标度与 §9.4 对齐）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoodsReceiptSummary(
        @JsonProperty("goods_receipt_id") Long goodsReceiptId,
        @JsonProperty("receipt_no") String receiptNo,
        @JsonProperty("purchase_order_id") Long purchaseOrderId,
        @JsonProperty("warehouse_id") Long warehouseId,
        @JsonProperty("status") String status,
        @JsonProperty("receipt_date") LocalDate receiptDate,
        @JsonProperty("total_qty") String totalQty,
        @JsonProperty("total_amount") String totalAmount,
        @JsonProperty("remark") String remark,
        @JsonProperty("version") Integer version,
        @JsonProperty("created_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime createdAt) {
}
