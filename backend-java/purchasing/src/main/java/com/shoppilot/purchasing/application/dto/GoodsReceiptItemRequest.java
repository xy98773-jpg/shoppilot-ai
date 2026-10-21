package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * 到货明细行（登记与编辑共用；M6-T04）。
 *
 * <h2>★ 字段口径</h2>
 * <ul>
 *   <li>{@code purchase_order_item_id}：<b>必填</b>，必须属于该到货单关联的采购单
 *       （否则 {@code COM-0001}）；</li>
 *   <li>{@code received_qty}：<b>必填</b>，本次到货数量（标度 3，{@code DECIMAL(18,3)}）；
 *       {@code <= 0} ⇒ {@code PO-9012}；</li>
 *   <li>{@code unit_price}：<b>必填</b>，本次到货单价（标度 4，{@code DECIMAL(18,4)}）；
 *       {@code < 0} 或小数位 &gt; 4 ⇒ {@code PO-9011}；</li>
 *   <li>{@code batch_no}：供应方批号（可选，≤ 64 字符，列宽 {@code VARCHAR(64)}）；</li>
 *   <li>{@code remark}：行备注（可选，≤ 255 字符，列宽 {@code VARCHAR(255)}）。</li>
 * </ul>
 *
 * <p>★ <b>{@code sku_id} 与 {@code amount} 不在请求体里</b>：{@code sku_id} 冗余取自<b>采购明细</b>
 * （服务端从采购单详情取，防客户端张冠李戴）；{@code amount = received_qty × unit_price} 由服务端算。
 */
public class GoodsReceiptItemRequest {

    @JsonProperty("purchase_order_item_id")
    private Long purchaseOrderItemId;

    @JsonProperty("received_qty")
    private BigDecimal receivedQty;

    @JsonProperty("unit_price")
    private BigDecimal unitPrice;

    @JsonProperty("batch_no")
    private String batchNo;

    @JsonProperty("remark")
    private String remark;

    public Long getPurchaseOrderItemId() {
        return purchaseOrderItemId;
    }

    public void setPurchaseOrderItemId(Long purchaseOrderItemId) {
        this.purchaseOrderItemId = purchaseOrderItemId;
    }

    public BigDecimal getReceivedQty() {
        return receivedQty;
    }

    public void setReceivedQty(BigDecimal receivedQty) {
        this.receivedQty = receivedQty;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
