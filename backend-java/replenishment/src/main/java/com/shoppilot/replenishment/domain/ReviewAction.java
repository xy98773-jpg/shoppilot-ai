package com.shoppilot.replenishment.domain;

/**
 * 建议审核动作（{@code replenishment_reviews.action}；权威定义 {@code DESIGN_BASIS.md §9.12}）。
 *
 * <p>与状态的对应关系（由 M5-T02+ 的服务层按 {@code §9.11} 校验，<b>本批不实现</b>）：
 * <ul>
 *   <li>{@link #SUBMIT}：{@code SUGGESTED → PENDING_REVIEW}</li>
 *   <li>{@link #WITHDRAW}：{@code PENDING_REVIEW → SUGGESTED}</li>
 *   <li>{@link #APPROVE}：{@code PENDING_REVIEW → APPROVED}（须 {@code APPROVER}）</li>
 *   <li>{@link #REJECT}：{@code PENDING_REVIEW → REJECTED}（须 {@code APPROVER}，且 {@code comment} 必填）</li>
 *   <li>{@link #CANCEL}：{@code SUGGESTED/APPROVED → CANCELLED}</li>
 * </ul>
 *
 * <p>★ 与迁移 {@code ck_rr_action} 取值集合逐字一致。
 */
public enum ReviewAction {

    /** 提交审核。 */
    SUBMIT,

    /** 撤回（回到 {@code SUGGESTED}）。 */
    WITHDRAW,

    /** 审批通过。 */
    APPROVE,

    /** 驳回（{@code comment} 必填）。 */
    REJECT,

    /** 作废。 */
    CANCEL
}
