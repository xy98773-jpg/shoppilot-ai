package com.shoppilot.purchasing.application.dto;

import java.time.LocalDateTime;

/**
 * 采购单列表过滤条件（M6-T03 {@code GET /api/v1/purchasing/orders}）。
 *
 * <p>所有条件可空（空 = 不参与过滤）；{@code poNoLike} 为<b>关键字</b>（模糊匹配）。
 * 时间范围按 {@code created_at} 闭区间过滤（UTC）。
 */
public class PurchaseOrderFilter {

    /** 采购单状态白名单（{@code DESIGN_BASIS §9.13} 六值）。 */
    private String status;
    private Long supplierId;
    private Long warehouseId;
    private String poNoLike;
    private LocalDateTime createdFrom;
    private LocalDateTime createdTo;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getPoNoLike() {
        return poNoLike;
    }

    public void setPoNoLike(String poNoLike) {
        this.poNoLike = poNoLike;
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
