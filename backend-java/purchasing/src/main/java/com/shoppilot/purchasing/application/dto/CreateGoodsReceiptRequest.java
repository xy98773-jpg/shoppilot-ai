package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code POST /api/v1/purchasing/receipts} 请求体（{@code API_CONTRACT §11.9} 请求 A；M6-T04）。
 *
 * <h2>★ 不可改字段不在本 DTO 内</h2>
 * <p>本请求体<b>只承载「登记到货」可填的字段</b>：{@code purchase_order_id} / {@code warehouse_id} /
 * {@code receipt_date} / {@code remark} / {@code items[]}。
 * 服务端派生的字段（{@code status} / {@code total_qty} / {@code total_amount} / {@code receipt_no} /
 * {@code idempotency_key} / {@code sku_id}）<b>一律不出现在请求体里</b>——
 * 客户端无法设置它们，也就不会出现「客户端自称的汇总数与服务端重算不一致」的裂缝。
 *
 * <h2>★ {@code unit_price} 必填的原因</h2>
 * <p>到货单价是<b>本次到货的实际单价</b>（可能与采购单价不同），只能由客户端填报；
 * 服务端<b>不</b>用采购单价兜底（否则「采购价 ≠ 到货价」这一真实业务场景被静默抹平）。
 * 非法值（{@code < 0} 或小数位 &gt; 4）⇒ {@code PO-9011}。
 *
 * <h2>★ 数量 / 单价的标度（{@code DESIGN_BASIS §9.4}）</h2>
 * <p>{@code received_qty} 标度 3（{@code DECIMAL(18,3)}）、{@code unit_price} 标度 4（{@code DECIMAL(18,4)}）。
 * 客户端以<b>字符串</b>或数字给出均可（Jackson 解析为 {@link BigDecimal} 后由服务端按标度归一）。
 *
 * @param purchaseOrderId 采购单 ID（必填；不存在 / 跨团队 ⇒ {@code PO-9004}）
 * @param warehouseId     入库仓库（必填；必须<b>等于</b>采购单的 {@code warehouse_id}，否则 {@code COM-0001}）
 * @param receiptDate     到货业务日期（{@code DATE}，必填；缺失 ⇒ {@code COM-0001}）
 * @param remark          备注（可选，≤ 500 字符）
 * @param items           到货明细（必填非空；空 ⇒ {@code PO-9006}）
 */
public class CreateGoodsReceiptRequest {

    @JsonProperty("purchase_order_id")
    private Long purchaseOrderId;

    @JsonProperty("warehouse_id")
    private Long warehouseId;

    @JsonProperty("receipt_date")
    private LocalDate receiptDate;

    @JsonProperty("remark")
    private String remark;

    @JsonProperty("items")
    private List<GoodsReceiptItemRequest> items;

    public Long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public void setPurchaseOrderId(Long purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public LocalDate getReceiptDate() {
        return receiptDate;
    }

    public void setReceiptDate(LocalDate receiptDate) {
        this.receiptDate = receiptDate;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public List<GoodsReceiptItemRequest> getItems() {
        return items;
    }

    public void setItems(List<GoodsReceiptItemRequest> items) {
        this.items = items;
    }
}
