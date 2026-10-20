package com.shoppilot.replenishment.domain;

/**
 * 补货建议状态（{@code replenishment_items.status}；权威定义 {@code DESIGN_BASIS.md §9.11}）。
 *
 * <p>合法转换（单向，终态不可变）：
 * <pre>
 * SUGGESTED      → PENDING_REVIEW | SUPERSEDED | CANCELLED   （★ 不允许直接 REJECTED）
 * PENDING_REVIEW → APPROVED | REJECTED | SUGGESTED(撤回) | SUPERSEDED
 * APPROVED       → CONVERTED | CANCELLED                      （★ 不允许 → SUPERSEDED）
 * CONVERTED      → 终态
 * REJECTED       → 终态
 * SUPERSEDED     → 终态
 * CANCELLED      → 终态
 * </pre>
 *
 * <p>★ 本枚举在 M5-T01 只承载取值与终态判定；<b>不</b>包含状态机流转实现
 * （属 M5-T02+，且转换合法性由服务层按 {@code §9.11} 校验）。
 *
 * <p>★ 与迁移 {@code V1.0.10__replenishment.sql} 的 {@code ck_repl_status} /
 * {@code ck_rr_from_status} / {@code ck_rr_to_status} <b>取值集合必须逐字一致</b>；
 * 改枚举必须同时改迁移（但已执行的迁移不得修改，只能新增）。
 */
public enum ReplenishmentItemStatus {

    /** 系统生成，待运营处理。 */
    SUGGESTED,

    /** 运营已提交，待审批。 */
    PENDING_REVIEW,

    /** 已审批通过，可转采购。 */
    APPROVED,

    /** 已生成采购明细。终态。 */
    CONVERTED,

    /** 已驳回（必须是 PENDING_REVIEW → REJECTED，且填驳回原因）。终态。 */
    REJECTED,

    /** 被更新一轮的分析结果取代（只能作用于 SUGGESTED / PENDING_REVIEW）。终态。 */
    SUPERSEDED,

    /** 运营主动作废。终态。 */
    CANCELLED;

    /** 是否终态（{@code §9.11}：终态不可再变）。 */
    public boolean isTerminal() {
        return this == CONVERTED || this == REJECTED || this == SUPERSEDED || this == CANCELLED;
    }
}
