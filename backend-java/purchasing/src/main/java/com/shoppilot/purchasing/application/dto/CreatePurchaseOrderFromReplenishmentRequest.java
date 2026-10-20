package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code POST /api/v1/purchasing/orders/from-replenishment} 请求体（{@code API_CONTRACT §11.8} 请求 A）。
 *
 * <p>★ <b>数量 / 单价 / 供应商 / 商品 ID 一律不由客户端传入</b>：它们全部取自被转的补货建议
 * （{@code ordered_qty ← 建议 qty}、{@code unit_price ← 建议 purchase_price}、
 * {@code supplier_id ← 建议 supplier_id}、{@code product_id ← 建议 product_id}）——
 * 契约请求体<b>没有</b>这三项，正是为了杜绝「客户端改量/改价/换供应商」。
 *
 * @param replenishmentItemIds   被转采购的建议 ID（必填非空；空 ⇒ {@code PO-9006}）
 * @param warehouseId            到货仓库（必填；不存在或跨团队 ⇒ {@code CAT-3003}）
 * @param expectedArrivalDate    期望到货日（可选）
 * @param remark                 备注（可选，≤500 字符）
 * @param conflictAcknowledged   是否已显式确认同 SKU×仓库的其它已审批建议（默认 {@code false}）
 * @param acknowledgedItemIds    被确认的冲突建议 ID 集合（默认空；须等于服务端当前冲突集，忽略顺序）
 */
public class CreatePurchaseOrderFromReplenishmentRequest {

    @JsonProperty("replenishment_item_ids")
    private List<Long> replenishmentItemIds;

    @JsonProperty("warehouse_id")
    private Long warehouseId;

    @JsonProperty("expected_arrival_date")
    private LocalDate expectedArrivalDate;

    @JsonProperty("remark")
    private String remark;

    @JsonProperty("conflict_acknowledged")
    private Boolean conflictAcknowledged;

    @JsonProperty("acknowledged_item_ids")
    private List<Long> acknowledgedItemIds;

    public List<Long> getReplenishmentItemIds() {
        return replenishmentItemIds;
    }

    public void setReplenishmentItemIds(List<Long> replenishmentItemIds) {
        this.replenishmentItemIds = replenishmentItemIds;
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

    public Boolean getConflictAcknowledged() {
        return conflictAcknowledged;
    }

    public void setConflictAcknowledged(Boolean conflictAcknowledged) {
        this.conflictAcknowledged = conflictAcknowledged;
    }

    public List<Long> getAcknowledgedItemIds() {
        return acknowledgedItemIds;
    }

    public void setAcknowledgedItemIds(List<Long> acknowledgedItemIds) {
        this.acknowledgedItemIds = acknowledgedItemIds;
    }

    /** 是否已显式确认（默认 {@code false}）。 */
    public boolean isConflictAcknowledged() {
        return Boolean.TRUE.equals(conflictAcknowledged);
    }

    /** 被确认的建议 ID 集合（默认空集）。 */
    public List<Long> acknowledgedIds() {
        return acknowledgedItemIds == null ? List.of() : acknowledgedItemIds;
    }
}
