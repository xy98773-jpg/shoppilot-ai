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
 * <p>★ <b>更新口径：默认 {@code updateById(entity)} + 判返回值；{@code update(null, wrapper)}
 * 默认禁用</b>（那类调用无实体形态，{@code OptimisticLockerInnerInterceptor} 不触发 ⇒
 * {@code @Version} 被绕过）。权威口径见 {@code common/MultiTenantConfig}。
 *
 * <p><b>逐处列明的例外</b>：{@code PurchaseOrderAppService#updateDraft}（M6-T03 A4 草稿编辑）
 * 逐行改 {@code ordered_qty}/{@code unit_price} 时用 {@code update(null, lambdaUpdate)}：
 * <ul>
 *   <li><b>为什么破例</b>：需把 {@code id} + {@code purchase_order_id} 两个定位条件并进同一条
 *       UPDATE（防跨单篡改），且本方法只改<b>部分列</b>（不动 {@code sku_id}/{@code line_no}/
 *       {@code product_id}/{@code replenishment_item_id} 等不可变列），实体形态更新无法表达
 *       「只 set 这几列且其余列保持原值」；</li>
 *   <li><b>破例后如何保证正确性</b>：手动 {@code set(version, version + 1)}（插件对
 *       {@code entity == null} 不触发，故无重复自增/漏自增风险），语义与插件等价；</li>
 *   <li><b>边界</b>：本例外<b>不</b>改 {@code received_qty}（只增不减，由 M6-T05 到货确认写入），
 *       故 DRAFT 态无「收货并发累加」冲突；本表<b>不设</b>行级 {@code status}，故本表
 *       <b>无需</b>同语句状态守卫（状态守卫只发生在 {@code purchase_orders} 主体表）。</li>
 * </ul>
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
    /**
     * 乐观锁版本（★ {@code received_qty} 累加并发）。
     *
     * <p>★ <b>自增口径</b>：实体形态更新（{@code updateById(entity)}）由插件自动
     * {@code SET version = version + 1}；<b>wrapper 形态</b>（{@code update(null, wrapper)}）
     * 插件<b>不触发</b>，须<b>手动自增</b>。
     */
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
