package com.shoppilot.purchasing.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code goods_receipts} 表映射（{@code DATABASE_DESIGN.md §5.x}；迁移 {@code V1.0.11__purchasing.sql}）。
 *
 * <p>到货登记凭证：确认后触发出库入库（{@code PURCHASE_INBOUND} 流水与余额变更，方向不可反）。★ {@code CONFIRMED} 后一切字段冻结，只能走冲销（{@code REVERSED}）。
 *
 * <p>★ 本表带 {@code version}（乐观锁）：{@code purchase_order_items.received_qty} 累加是典型丢失更新场景（{@code §6-I}），实体必须带 {@code @Version}。
 *
 * <p>★ <b>更新口径：默认 {@code updateById(entity)} + 判返回值；{@code update(null, wrapper)}
 * 默认禁用</b>（那类调用无实体形态，{@code OptimisticLockerInnerInterceptor} 不触发 ⇒
 * {@code @Version} 被绕过）。权威口径见 {@code common/MultiTenantConfig}（「不覆盖
 * {@code update(null, wrapper)}——那类调用必须手写 CAS 条件」）。
 *
 * <p><b>逐处列明的例外</b>：{@code GoodsReceiptAppService} 的状态流转与草稿编辑
 * （M6-T04 A2 编辑 / A3 作废）使用 {@code update(null, lambdaUpdate)}：
 * <ul>
 *   <li><b>为什么破例</b>：需把 {@code id} + {@code status=源态} + {@code version} 三个前提并进
 *       <b>同一条 UPDATE</b> 做同语句状态守卫，而 MyBatis-Plus 的 {@code updateById} 只生成
 *       {@code WHERE id=? AND version=?}（缺 {@code status} 条件）⇒ 无法在一条语句里
 *       同时表达「状态仍为源态」与「版本未变」两个前提；</li>
 *   <li><b>破例后如何保证正确性</b>：显式 {@code .eq(version, 读到的值)} + 手动
 *       {@code set(version, version + 1)}（插件对 {@code entity == null} 不触发，故无重复自增/
 *       漏自增风险），与插件的 {@code WHERE version=? SET version=version+1} 语义逐字等价
 *       ⇒ 仍是真 CAS；</li>
 *   <li><b>0 行语义是 {@code PO-9008}</b>（到货单已确认不可修改 / 乐观锁冲突），由调用方抛出。</li>
 * </ul>
 *
 * <p>★ <b>{@code team_id} 不接受客户端传入</b>：由租户插件从 {@code TeamContext} 注入；
 * 本表<b>不在</b> {@code TenantTables.WITHOUT_TEAM_ID} 黑名单。
 *
 * <p>★ 访问器<b>手写</b>：本模块 pom 未声明 Lombok（M6-T01 G4 禁改 pom），与 {@code replenishment} 同范式。
 */
@TableName("goods_receipts")
public class GoodsReceiptEntity {

    /** 主键（自增，不可变）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID（不可变） */
    private Long teamId;
    /** 到货单号（团队内唯一；不可变） */
    private String receiptNo;
    /** 关联采购单（不可变） */
    private Long purchaseOrderId;
    /** 入库仓库（不可变） */
    private Long warehouseId;
    /** 到货状态（GoodsReceiptStatus，§9.14） */
    private String status;
    /** 到货业务日期（不可变） */
    private LocalDate receiptDate;
    /** 到货总数量（系统重算） */
    private BigDecimal totalQty;
    /** 到货总金额（系统重算） */
    private BigDecimal totalAmount;
    /** ★ 幂等键 NOT NULL（收货不可逆，必须强制幂等保护；不可变） */
    private String idempotencyKey;
    /** 备注（仅 DRAFT 可更新） */
    private String remark;
    /** 录入人（逻辑关联 users.id；不可变） */
    private Long operatorId;
    /** 确认时间（CONFIRMED 时写入） */
    private LocalDateTime confirmedAt;
    /** 确认人（CONFIRMED 时写入） */
    private Long confirmedBy;
    /** 作废时间（CANCELLED 时写入） */
    private LocalDateTime cancelledAt;
    /** 冲销时间（CONFIRMED→REVERSED） */
    private LocalDateTime reversedAt;
    /** 冲销人（逻辑关联 users.id） */
    private Long reversedBy;
    /** 创建时间（UTC；集合 A） */
    private LocalDateTime createdAt;
    /** 更新时间（UTC；集合 A） */
    private LocalDateTime updatedAt;
    /** 创建人（集合 A） */
    private Long createdBy;
    /** 更新人（集合 A） */
    private Long updatedBy;
    /**
     * 乐观锁版本。
     *
     * <p>★ <b>自增口径</b>：实体形态更新（{@code updateById(entity)}）由插件自动
     * {@code SET version = version + 1}；<b>wrapper 形态</b>（{@code update(null, wrapper)}，
     * 见类 Javadoc 的「逐处列明例外」）插件<b>不触发</b>，须<b>手动自增</b>。
     */
    @Version
    private Integer version;

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }
    public String getReceiptNo() {
        return receiptNo;
    }

    public void setReceiptNo(String receiptNo) {
        this.receiptNo = receiptNo;
    }
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
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
    public LocalDate getReceiptDate() {
        return receiptDate;
    }

    public void setReceiptDate(LocalDate receiptDate) {
        this.receiptDate = receiptDate;
    }
    public BigDecimal getTotalQty() {
        return totalQty;
    }

    public void setTotalQty(BigDecimal totalQty) {
        this.totalQty = totalQty;
    }
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }
    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
    public Long getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(Long confirmedBy) {
        this.confirmedBy = confirmedBy;
    }
    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
    public LocalDateTime getReversedAt() {
        return reversedAt;
    }

    public void setReversedAt(LocalDateTime reversedAt) {
        this.reversedAt = reversedAt;
    }
    public Long getReversedBy() {
        return reversedBy;
    }

    public void setReversedBy(Long reversedBy) {
        this.reversedBy = reversedBy;
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
