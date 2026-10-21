package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 草稿编辑请求（{@code PATCH /api/v1/purchasing/orders/{id}}，M6-T03 A4）。
 *
 * <p>★ <b>部分更新语义</b>：未传 / 显式 {@code null} 的字段<b>不修改</b>；文本清空（{@code remark}）
 * 传<b>空串 {@code ""}</b> ⇒ 落库 {@code ""}（不得置 NULL，与 M5-T03 补货改量同口径）。
 *
 * <p>★ <b>明细行口径（本批）</b>：{@code items} 为<b>要修改的行清单</b>（partial）——
 * 每项必须带 {@code purchase_order_item_id}（须属本采购单），只改该行的
 * {@code ordered_qty}/{@code unit_price}/{@code expected_arrival_date}。
 * <b>不提供</b>新增行 / 删除行 / 改 {@code sku_id} 的能力。
 *
 * <p>★ <b>理由（SSOT 不可变规则，非接口缺失）</b>：{@code DATABASE_DESIGN.md:808} 明文
 * {@code purchase_order_id / line_no / sku_id / product_id / replenishment_item_id} <b>不可变</b>，
 * 仅 {@code ordered_qty / unit_price} 在 {@code DRAFT} 可改、{@code received_qty} 只增不减、
 * 不物理删除 ⇒ 增删行/改 SKU 自始不在可改集合内（{@code catalog} 的
 * {@code SkuQueryService#requireInTeam} 存在但本批无需调用——{@code sku_id} 不可变）。
 *
 * <p>★ <b>行作废本批不做</b>（R174，P2）：SSOT 的行作废口径是「把 {@code ordered_qty} 改成
 * 该行 {@code received_qty}」，但 {@code DRAFT} 阶段 {@code received_qty} 恒为 0
 * （到货确认才累加，而到货要求采购单已 {@code ORDERED}）⇒ 作废即 {@code ordered_qty = 0}，
 * 会撞 {@code ck_poi_ordered_positive CHECK (ordered_qty > 0)}（{@code V1.0.11:155}）
 * ⇒ 需独立迁移/口径批次（V1.0.13），本批登记 R174 后不做。
 *
 * <p>★ <b>不可改</b>：{@code status}、{@code source}、{@code po_no}、{@code idempotency_key}、
 * {@code total_qty}/{@code total_amount}/{@code received_qty}（由服务端同事务重算）。
 */
public class UpdatePurchaseOrderRequest {

    /** 期望版本（乐观锁令牌；必填，缺失 ⇒ {@code COM-0001}）。 */
    @JsonProperty("expected_version")
    private Integer expectedVersion;

    @JsonProperty("supplier_id")
    private Long supplierId;

    @JsonProperty("warehouse_id")
    private Long warehouseId;

    @JsonProperty("expected_arrival_date")
    private LocalDate expectedArrivalDate;

    @JsonProperty("remark")
    private String remark;

    @JsonProperty("items")
    private List<LineInput> items;

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public Long getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(Long supplierId) {
        this.supplierId = supplierId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public LocalDate getExpectedArrivalDate() {
        return expectedArrivalDate;
    }

    public void setExpectedArrivalDate(LocalDate expectedArrivalDate) {
        this.expectedArrivalDate = expectedArrivalDate;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public List<LineInput> getItems() {
        return items;
    }

    public void setItems(List<LineInput> items) {
        this.items = items;
    }

    /** 明细行修改项。 */
    public static class LineInput {

        /** 要修改的明细行 ID（必填；须属本采购单）。 */
        @JsonProperty("purchase_order_item_id")
        private Long purchaseOrderItemId;

        @JsonProperty("ordered_qty")
        private BigDecimal orderedQty;

        @JsonProperty("unit_price")
        private BigDecimal unitPrice;

        @JsonProperty("expected_arrival_date")
        private LocalDate expectedArrivalDate;

        @JsonProperty("remark")
        private String remark;

        public Long getPurchaseOrderItemId() {
            return purchaseOrderItemId;
        }

        public void setPurchaseOrderItemId(Long purchaseOrderItemId) {
            this.purchaseOrderItemId = purchaseOrderItemId;
        }

        public BigDecimal getOrderedQty() {
            return orderedQty;
        }

        public void setOrderedQty(BigDecimal orderedQty) {
            this.orderedQty = orderedQty;
        }

        public BigDecimal getUnitPrice() {
            return unitPrice;
        }

        public void setUnitPrice(BigDecimal unitPrice) {
            this.unitPrice = unitPrice;
        }

        public LocalDate getExpectedArrivalDate() {
            return expectedArrivalDate;
        }

        public void setExpectedArrivalDate(LocalDate expectedArrivalDate) {
            this.expectedArrivalDate = expectedArrivalDate;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }
}
