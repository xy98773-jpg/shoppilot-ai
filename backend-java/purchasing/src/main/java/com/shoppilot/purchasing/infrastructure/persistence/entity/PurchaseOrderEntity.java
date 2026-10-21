package com.shoppilot.purchasing.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code purchase_orders} 表映射（{@code DATABASE_DESIGN.md §5.x}；迁移 {@code V1.0.11__purchasing.sql}）。
 *
 * <p>采购单主体：承载采购状态机（{@code §9.13}）与汇总数量。★ {@code total_qty}/{@code received_qty}/{@code total_amount} 只由系统在同事务内重算。
 *
 * <p>★ <b>更新口径：默认 {@code updateById(entity)} + 判返回值；{@code update(null, wrapper)}
 * 默认禁用</b>（那类调用无实体形态，{@code OptimisticLockerInnerInterceptor} 不触发 ⇒ @Version
 * 被绕过）。权威口径见 {@code common/MultiTenantConfig}（「不覆盖 {@code update(null, wrapper)}
 * ——那类调用必须手写 CAS 条件」）。
 *
 * <p><b>逐处列明的例外</b>：{@code PurchaseOrderAppService} 的状态流转（{@code confirm} /
 * {@code order} / {@code cancel}，M6-T03）与草稿编辑主动使用 {@code update(null, lambdaUpdate)}：
 * <ul>
 *   <li><b>为什么破例</b>：需把 {@code id} + {@code status=源态} + {@code version} 三个前提并进
 *       <b>同一条 UPDATE</b> 做同语句状态守卫，而 MyBatis-Plus 的 {@code updateById} 只生成
 *       {@code WHERE id=? AND version=?}（缺 {@code status} 条件）⇒ 「读完到更新之间」的
 *       TOCTOU 窗口无法闭合；</li>
 *   <li><b>破例后如何保证正确性</b>：显式 {@code .eq(version, 读到的值)} + 手动
 *       {@code set(version, version + 1)}（插件对 {@code entity == null} 不触发，故无重复自增/
 *       漏自增风险），与插件的 {@code WHERE version=? SET version=version+1} 语义逐字等价
 *       ⇒ 仍是真 CAS；</li>
 *   <li><b>0 行语义是 {@code PO-9003}</b>（状态不允许该操作 / 乐观锁冲突），由调用方抛出；
 *       <b>不是</b> {@code COM-0015}（那个码只走 {@code catalog} 的 {@code applyVersionCas}
 *       路径，本模块零命中）。</li>
 * </ul>
 *
 * <p>★ 本表带 {@code version}（乐观锁）：{@code purchase_order_items.received_qty} 累加是典型丢失更新场景（{@code §6-I}），实体必须带 {@code @Version}。
 * 自增口径：实体形态更新由插件自动 {@code SET version = version + 1}；wrapper 形态（见上述例外）须<b>手动自增</b>。
 *
 * <p>★ <b>{@code team_id} 不接受客户端传入</b>：由租户插件从 {@code TeamContext} 注入；
 * 本表<b>不在</b> {@code TenantTables.WITHOUT_TEAM_ID} 黑名单。
 *
 * <p>★ 访问器<b>手写</b>：本模块 pom 未声明 Lombok（M6-T01 G4 禁改 pom），与 {@code replenishment} 同范式。
 */
@TableName("purchase_orders")
public class PurchaseOrderEntity {

    /** 主键（自增，不可变）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID（不可变） */
    private Long teamId;
    /** 采购单号（团队内唯一；不可变） */
    private String poNo;
    /** 供应商（仅 DRAFT 可更新） */
    private Long supplierId;
    /** 到货仓库（仅 DRAFT 可更新） */
    private Long warehouseId;
    /** 采购状态（PurchaseOrderStatus，§9.13） */
    private String status;
    /** 来源：MANUAL/REPLENISHMENT（不可变） */
    private String source;
    /** 期望到货日（仅 DRAFT/PENDING 可更新） */
    private LocalDate expectedArrivalDate;
    /** 下单总数量（系统重算，不接受客户端直接改） */
    private BigDecimal totalQty;
    /** 已到货总数量（确认到货时同事务累加） */
    private BigDecimal receivedQty;
    /** 总金额（系统重算） */
    private BigDecimal totalAmount;
    /** 备注（仅 DRAFT 可更新） */
    private String remark;
    /** 创建幂等键（★ 可空；不可变） */
    private String idempotencyKey;
    /** DRAFT→PENDING 时间（视为审批，D-06） */
    private LocalDateTime confirmedAt;
    /** 确认人（逻辑关联 users.id） */
    private Long confirmedBy;
    /** PENDING→ORDERED 时间 */
    private LocalDateTime orderedAt;
    /** 取消时间 */
    private LocalDateTime cancelledAt;
    /** 取消原因 */
    private String cancelReason;
    /** 终态时间 */
    private LocalDateTime closedAt;
    /** 创建时间（UTC；集合 A） */
    private LocalDateTime createdAt;
    /** 更新时间（UTC；集合 A） */
    private LocalDateTime updatedAt;
    /** 创建人（集合 A） */
    private Long createdBy;
    /** 更新人（集合 A） */
    private Long updatedBy;
    /** 乐观锁版本（§7.4 表 A） */
    @Version
    private Integer version;

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }
    public String getPoNo() {
        return poNo;
    }

    public void setPoNo(String poNo) {
        this.poNo = poNo;
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
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
    public LocalDate getExpectedArrivalDate() {
        return expectedArrivalDate;
    }

    public void setExpectedArrivalDate(LocalDate expectedArrivalDate) {
        this.expectedArrivalDate = expectedArrivalDate;
    }
    public BigDecimal getTotalQty() {
        return totalQty;
    }

    public void setTotalQty(BigDecimal totalQty) {
        this.totalQty = totalQty;
    }
    public BigDecimal getReceivedQty() {
        return receivedQty;
    }

    public void setReceivedQty(BigDecimal receivedQty) {
        this.receivedQty = receivedQty;
    }
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
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
    public LocalDateTime getOrderedAt() {
        return orderedAt;
    }

    public void setOrderedAt(LocalDateTime orderedAt) {
        this.orderedAt = orderedAt;
    }
    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }
    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
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
