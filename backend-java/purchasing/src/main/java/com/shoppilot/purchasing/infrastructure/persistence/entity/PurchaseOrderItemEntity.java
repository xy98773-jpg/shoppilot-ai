package com.shoppilot.purchasing.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code purchase_order_items} 表映射（{@code DATABASE_DESIGN.md §5.x}；迁移 {@code V1.0.11__purchasing.sql}）。
 *
 * <p>采购单行项目：承载 {@code ordered_qty}/{@code received_qty} 的累加与部分到货能力。★ <b>不设行级 {@code status}、不设 {@code pending_qty}</b>（均为派生值，§5.19 设计要点 1/2）。
 *
 * <p>★ 本表带 {@code version}（乐观锁）：{@code purchase_order_items.received_qty} 累加是典型丢失更新场景（{@code §6-I}），实体必须带 {@code @Version}。
 *
 * <p>★ <b>{@code team_id} 不接受客户端传入</b>：由租户插件从 {@code TeamContext} 注入；
 * 本表<b>不在</b> {@code TenantTables.WITHOUT_TEAM_ID} 黑名单。
 *
 * <p>★ 访问器<b>手写</b>：本模块 pom 未声明 Lombok（M6-T01 G4 禁改 pom），与 {@code replenishment} 同范式。
 */
@TableName("purchase_order_items")
public class PurchaseOrderItemEntity {

    /** 主键（自增，不可变）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID（不可变） */
    private Long teamId;
    /** 所属采购单（不可变） */
    private Long purchaseOrderId;
    /** 行号（1 起；单内唯一；不可变） */
    private Integer lineNo;
    /** 采购 SKU（仅 DRAFT 可更新） */
    private Long skuId;
    /** 冗余商品 ID（逻辑关联；不可变） */
    private Long productId;
    /** 来源补货建议（★ 跨模块逻辑关联，§6-G，不建外键；不可变） */
    private Long replenishmentItemId;
    /** 下单数量（仅 DRAFT 可更新） */
    private BigDecimal orderedQty;
    /** 累计已收数量（★ 只增不减，除冲销） */
    private BigDecimal receivedQty;
    /** 单价 */
    private BigDecimal unitPrice;
    /** 行金额（与数量/单价同事务重算） */
    private BigDecimal amount;
    /** 行级期望到货日（覆盖单头） */
    private LocalDate expectedArrivalDate;
    /** 备注（仅 DRAFT 可更新） */
    private String remark;
    /** 创建时间（UTC；集合 A） */
    private LocalDateTime createdAt;
    /** 更新时间（UTC；集合 A） */
    private LocalDateTime updatedAt;
    /** 创建人（集合 A） */
    private Long createdBy;
    /** 更新人（集合 A） */
    private Long updatedBy;
    /** 乐观锁版本（★ received_qty 累加并发） */
    @Version
    private Integer version;

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }
    public Long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public void setPurchaseOrderId(Long purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }
    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }
    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }
    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }
    public Long getReplenishmentItemId() {
        return replenishmentItemId;
    }

    public void setReplenishmentItemId(Long replenishmentItemId) {
        this.replenishmentItemId = replenishmentItemId;
    }
    public BigDecimal getOrderedQty() {
        return orderedQty;
    }

    public void setOrderedQty(BigDecimal orderedQty) {
        this.orderedQty = orderedQty;
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
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
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
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

}
