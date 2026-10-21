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
 * 到货单详情（{@code GET /api/v1/purchasing/receipts/{id}} 与登记/编辑响应；M6-T04）。
 *
 * <p>★ 数量 / 单价 / 金额一律<b>字符串</b>输出（{@code DESIGN_BASIS §9.4}）：
 * 数量标度 3、单价标度 4、金额标度 2。
 *
 * <p>★ 字段名对齐契约 §11.9 成功响应（{@code goods_receipt_id} / {@code receipt_no} /
 * {@code purchase_order_id} / {@code status} / {@code receipt_date} / {@code total_qty} /
 * {@code items[]} / {@code created_at}），另补契约未穷举但列表/详情需要的
 * {@code warehouse_id} / {@code total_amount} / {@code remark} / {@code version} /
 * {@code updated_at} / {@code cancelled_at}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoodsReceiptDetail(
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
        @JsonProperty("items") List<Item> items,
        @JsonProperty("created_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime createdAt,
        @JsonProperty("updated_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime updatedAt,
        @JsonProperty("cancelled_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime cancelledAt,
        @JsonProperty("confirmed_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime confirmedAt,
        @JsonProperty("confirmed_by") Long confirmedBy) {

    /**
     * 到货明细行。
     *
     * @param goodsReceiptItemId  到货明细行 ID
     * @param purchaseOrderItemId 对应采购明细 ID
     * @param skuId              冗余 SKU ID（取自采购明细）
     * @param receivedQty        本次到货数量（标度 3）
     * @param unitPrice          本次到货单价（标度 4）
     * @param amount             本行金额（= received_qty × unit_price，标度 2）
     * @param batchNo            供应方批号
     * @param remark             行备注
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            @JsonProperty("goods_receipt_item_id") Long goodsReceiptItemId,
            @JsonProperty("purchase_order_item_id") Long purchaseOrderItemId,
            @JsonProperty("sku_id") Long skuId,
            @JsonProperty("received_qty") String receivedQty,
            @JsonProperty("unit_price") String unitPrice,
            @JsonProperty("amount") String amount,
            @JsonProperty("batch_no") String batchNo,
            @JsonProperty("remark") String remark) {
    }

    /** 数量文本（标度 3，{@code DECIMAL(18,3)}）。 */
    public static String qtyText(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    /** 单价文本（标度 4，{@code DECIMAL(18,4)}）。 */
    public static String priceText(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /** 金额文本（标度 2，{@code DECIMAL(18,2)}）。 */
    public static String amountText(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
