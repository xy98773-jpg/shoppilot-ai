package com.shoppilot.purchasing.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code goods_receipt_items} 表映射（{@code DATABASE_DESIGN.md §5.x}；迁移 {@code V1.0.11__purchasing.sql}）。
 *
 * <p>到货单行项目：逐条对应采购明细并记录<b>本次</b>到货数量。★ <b>仅 INSERT</b>：到货单 {@code CONFIRMED} 后不可变，永不 UPDATE / DELETE。
 *
 * <p>★ <b>本表无 {@code version}、无 {@code updated_at}/{@code updated_by}</b>（审计集合 B，{@code DATA_DICTIONARY §4.1}）：到货单 {@code CONFIRMED} 后一切字段冻结（只能走冲销），本表是<b>仅追加</b>表——不要「好心补齐」乐观锁列，那会把只追加表变成可更新表。
 *
 * <p>★ <b>{@code team_id} 不接受客户端传入</b>：由租户插件从 {@code TeamContext} 注入；
 * 本表<b>不在</b> {@code TenantTables.WITHOUT_TEAM_ID} 黑名单。
 *
 * <p>★ 访问器<b>手写</b>：本模块 pom 未声明 Lombok（M6-T01 G4 禁改 pom），与 {@code replenishment} 同范式。
 */
@TableName("goods_receipt_items")
public class GoodsReceiptItemEntity {

    /** 主键（自增，不可变）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID（不可变） */
    private Long teamId;
    /** 所属到货单（不可变） */
    private Long goodsReceiptId;
    /** 对应采购明细（不可变） */
    private Long purchaseOrderItemId;
    /** 冗余 SKU ID（入账免 JOIN；不可变） */
    private Long skuId;
    /** ★ 本次到货数量（仅到货单 DRAFT 时可改） */
    private BigDecimal receivedQty;
    /** 本次到货单价（仅 DRAFT 可改） */
    private BigDecimal unitPrice;
    /** 本行金额（仅 DRAFT 可改） */
    private BigDecimal amount;
    /** 供应方批号（仅记录） */
    private String batchNo;
    /** 备注（仅 DRAFT 可改） */
    private String remark;
    /** 创建时间（UTC；集合 B） */
    private LocalDateTime createdAt;
    /** 创建人（集合 B） */
    private Long createdBy;

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }
    public Long getGoodsReceiptId() {
        return goodsReceiptId;
    }

    public void setGoodsReceiptId(Long goodsReceiptId) {
        this.goodsReceiptId = goodsReceiptId;
    }
    public Long getPurchaseOrderItemId() {
        return purchaseOrderItemId;
    }

    public void setPurchaseOrderItemId(Long purchaseOrderItemId) {
        this.purchaseOrderItemId = purchaseOrderItemId;
    }
    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
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
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

}
