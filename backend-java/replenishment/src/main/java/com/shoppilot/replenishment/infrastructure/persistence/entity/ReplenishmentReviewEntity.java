package com.shoppilot.replenishment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code replenishment_reviews} 表映射（{@code DATABASE_DESIGN.md §5.17}；迁移
 * {@code V1.0.10__replenishment.sql}）。
 *
 * <p><b>★ 仅 INSERT，永不 UPDATE / DELETE</b>（{@code DATABASE_DESIGN} P4 硬规则）：本表是补货建议
 * 状态流转的<b>追加式</b>轨迹（提交/撤回/通过/驳回/作废），每一步都必须留痕。
 * 因此审计字段为<b>集合 B</b>（{@code DATA_DICTIONARY.md §4.1}）——<b>只有</b>
 * {@code created_at} + {@code created_by}，<b>没有</b> {@code updated_at} / {@code updated_by} /
 * {@code version}（表就没有，代码也不得补）。
 *
 * <p>★ {@code reviewer_role} 存的是<b>操作当时的角色快照</b>，不是实时 JOIN
 * {@code team_members}：角色可能变更，审计需要「当时的权限依据」。
 *
 * <p>★ {@code occurred_at} 与 {@code created_at} 同值写入，但语义不同：
 * 前者是「业务动作发生时间」，后者是「记录落库时间」。
 *
 * <p>★ <b>无唯一约束</b>：同一条建议会被多次操作（提交 → 驳回 → 修改 → 再提交 → 通过），
 * 每一步都必须留痕，唯一约束会摧毁轨迹（{@code §5.17}）。
 *
 * <p>★ 访问器手写：本模块 pom <b>未声明 Lombok</b>（M5-T01 G4 禁改 pom），故不使用 {@code @Data}。
 */
@TableName("replenishment_reviews")
public class ReplenishmentReviewEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;

    /** 被操作的建议（{@code fk_rr_item}，RESTRICT）。 */
    private Long replenishmentItemId;

    /** 动作（{@code ReviewAction}，§9.12）。 */
    private String action;

    /** 变更前状态（{@code ReplenishmentItemStatus}，§9.11）。 */
    private String fromStatus;

    /** 变更后状态（{@code ReplenishmentItemStatus}，§9.11）。 */
    private String toStatus;

    /** 意见；{@code REJECT} 时必填且不得为纯空白（DB CHECK 用 {@code TRIM}）。 */
    private String comment;

    private BigDecimal qtyBefore;

    private BigDecimal qtyAfter;

    /** 操作人（逻辑关联 {@code users.id}，不建外键）。 */
    private Long reviewerId;

    /** 操作时角色快照（{@code TeamRole}，§9.3）。 */
    private String reviewerRole;

    private String requestId;

    /** 业务动作发生时间（UTC）。 */
    private LocalDateTime occurredAt;

    // ---- 审计字段（集合 B：仅 created_at / created_by；无 updated_* / version） ----
    private LocalDateTime createdAt;
    private Long createdBy;

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

    public Long getReplenishmentItemId() {
        return replenishmentItemId;
    }

    public void setReplenishmentItemId(Long replenishmentItemId) {
        this.replenishmentItemId = replenishmentItemId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public BigDecimal getQtyBefore() {
        return qtyBefore;
    }

    public void setQtyBefore(BigDecimal qtyBefore) {
        this.qtyBefore = qtyBefore;
    }

    public BigDecimal getQtyAfter() {
        return qtyAfter;
    }

    public void setQtyAfter(BigDecimal qtyAfter) {
        this.qtyAfter = qtyAfter;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getReviewerRole() {
        return reviewerRole;
    }

    public void setReviewerRole(String reviewerRole) {
        this.reviewerRole = reviewerRole;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
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
}
