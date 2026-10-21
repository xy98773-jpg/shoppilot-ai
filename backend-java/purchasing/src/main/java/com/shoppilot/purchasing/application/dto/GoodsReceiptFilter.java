package com.shoppilot.purchasing.application.dto;

import java.time.LocalDateTime;

/**
 * 到货单列表过滤条件（{@code GET /api/v1/purchasing/receipts}；M6-T04）。
 *
 * <p>所有条件可空（空 = 不参与过滤）；{@code receiptNoLike} 为<b>关键字</b>（模糊匹配）。
 * 时间范围按 {@code created_at} 闭区间过滤（UTC）。
 */
public class GoodsReceiptFilter {

    /** 采购单 ID（精确）。 */
    private Long purchaseOrderId;
    /** 到货状态（精确；取值见 {@code GoodsReceiptStatus}）。 */
    private String status;
    /** 入库仓库（精确）。 */
    private Long warehouseId;
    /** 到货单号关键字（模糊）。 */
    private String receiptNoLike;
    /** {@code created_at} 下界（含）。 */
    private LocalDateTime createdFrom;
    /** {@code created_at} 上界（含）。 */
    private LocalDateTime createdTo;

    public Long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public void setPurchaseOrderId(Long purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getReceiptNoLike() {
        return receiptNoLike;
    }

    public void setReceiptNoLike(String receiptNoLike) {
        this.receiptNoLike = receiptNoLike;
    }

    public LocalDateTime getCreatedFrom() {
        return createdFrom;
    }

    public void setCreatedFrom(LocalDateTime createdFrom) {
        this.createdFrom = createdFrom;
    }

    public LocalDateTime getCreatedTo() {
        return createdTo;
    }

    public void setCreatedTo(LocalDateTime createdTo) {
        this.createdTo = createdTo;
    }
}
