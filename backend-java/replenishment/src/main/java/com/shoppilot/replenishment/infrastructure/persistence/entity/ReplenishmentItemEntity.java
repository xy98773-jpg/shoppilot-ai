package com.shoppilot.replenishment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code replenishment_items} 表映射（{@code DATABASE_DESIGN.md §5.16}；迁移
 * {@code V1.0.10__replenishment.sql}）。
 *
 * <p>「分析快照 → 有状态、可审核、可采购」的业务对象。审计字段为<b>集合 A + {@code version}</b>
 * （{@code DATA_DICTIONARY.md §4.1}、{@code DATABASE_DESIGN §7.4} 表 A）：
 * 人工改量 + 状态机 + 审批并发并存，必须有乐观锁。
 *
 * <p>★ <b>{@code version} 必须带 {@link Version}</b>（{@code R62} 教训：只建列不加注解 ⇒ 列恒 0、
 * 并发静默丢更新）。插件 {@code OptimisticLockerInnerInterceptor} 已在
 * {@code common/MultiTenantConfig} 注册（B23）。
 *
 * <p>★ <b>本表禁止 {@code update(null, wrapper)}</b>：那会绕过 {@code @Version}
 * （插件只改写实体形态的 {@code updateById}/{@code update(entity, wrapper)}）。
 * M5 后续批次一律用 {@code updateById(entity)} 并<b>判返回值</b>（0 行 ⇒ {@code COM-0015}）。
 *
 * <p><b>JSON 列以 {@link String} 承载</b>（与 {@code AnalysisRunEntity} 同范式）：
 * {@code converted_po_item_ids} 是「已生成采购明细 ID 摘要」，仅展示（{@code §6-G}）。
 *
 * <p>★ <b>{@code team_id} 不接受客户端传入</b>：由租户插件从 {@code TeamContext} 注入；
 * 本表<b>不在</b> {@code TenantTables.WITHOUT_TEAM_ID} 黑名单。
 *
 * <p>★ 访问器手写：本模块 pom <b>未声明 Lombok</b>（M5-T01 G4 禁改 pom），
 * 故不使用 {@code @Data}；与其它模块实体的语义一致，仅实现方式不同。
 */
@TableName("replenishment_items")
public class ReplenishmentItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;

    /** 来源分析快照（追溯链核心；不可变）。 */
    private Long sourceAnalysisItemId;

    /** 冗余：来源运行（不可变）。 */
    private Long runId;

    private Long skuId;

    /** 冗余商品 ID（逻辑关联，不建外键）。 */
    private Long productId;

    private Long warehouseId;

    /** 建议状态（{@code ReplenishmentItemStatus}，§9.11）。 */
    private String status;

    /** 系统原始建议量（= 快照 {@code qty_suggest}；不可变）。 */
    private BigDecimal suggestedQty;

    /** 当前生效数量（运营可改，仅前两个状态可改）。 */
    private BigDecimal qty;

    /** 冗余快照：风险等级（{@code RiskLevel}，§9.9）。 */
    private String riskLevel;

    private String reason;

    /** 指定供应商（逻辑关联 {@code suppliers.id}）。 */
    private Long supplierId;

    /** 参考采购单价（{@code DECIMAL(18,4)}）。 */
    private BigDecimal purchasePrice;

    private LocalDate expectedArrivalDate;

    /** 已生成的采购明细 ID 摘要（JSON 文本；仅展示）。 */
    private String convertedPoItemIds;

    private LocalDateTime convertedAt;

    /** 被哪条新建议取代（逻辑自关联，不建外键）。 */
    private Long supersededByItemId;

    private LocalDateTime submittedAt;

    private LocalDateTime approvedAt;

    /** 审批人（逻辑关联 {@code users.id}）。 */
    private Long approvedBy;

    private LocalDateTime closedAt;

    private String remark;

    // ---- 审计字段（集合 A）+ version ----
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;

    /** 乐观锁版本（§7.4 表 A）；由插件在 {@code updateById} 时自增，调用方必须判返回值。 */
    @Version
    private Integer version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }

    public Long getSourceAnalysisItemId() {
        return sourceAnalysisItemId;
    }

    public void setSourceAnalysisItemId(Long sourceAnalysisItemId) {
        this.sourceAnalysisItemId = sourceAnalysisItemId;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
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

    public BigDecimal getSuggestedQty() {
        return suggestedQty;
    }

    public void setSuggestedQty(BigDecimal suggestedQty) {
        this.suggestedQty = suggestedQty;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(Long supplierId) {
        this.supplierId = supplierId;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public LocalDate getExpectedArrivalDate() {
        return expectedArrivalDate;
    }

    public void setExpectedArrivalDate(LocalDate expectedArrivalDate) {
        this.expectedArrivalDate = expectedArrivalDate;
    }

    public String getConvertedPoItemIds() {
        return convertedPoItemIds;
    }

    public void setConvertedPoItemIds(String convertedPoItemIds) {
        this.convertedPoItemIds = convertedPoItemIds;
    }

    public LocalDateTime getConvertedAt() {
        return convertedAt;
    }

    public void setConvertedAt(LocalDateTime convertedAt) {
        this.convertedAt = convertedAt;
    }

    public Long getSupersededByItemId() {
        return supersededByItemId;
    }

    public void setSupersededByItemId(Long supersededByItemId) {
        this.supersededByItemId = supersededByItemId;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(Long approvedBy) {
        this.approvedBy = approvedBy;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
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
}
