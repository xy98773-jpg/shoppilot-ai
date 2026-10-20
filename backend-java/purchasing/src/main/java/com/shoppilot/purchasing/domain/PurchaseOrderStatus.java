package com.shoppilot.purchasing.domain;

/**
 * 采购单状态（{@code purchase_orders.status}）。
 *
 * <p>权威定义：{@code DESIGN_BASIS.md §9.13}；取值与迁移
 * {@code V1.0.11__purchasing.sql} 的 CHECK 约束<b>逐字一致</b>，改枚举必须同步改迁移
 * （但已执行的迁移不得修改，只能新增）。
 */
public enum PurchaseOrderStatus {

    /** 草稿（含由补货建议生成但未确认） */
    DRAFT,

    /** 待采购（已确认，未向供应商下单） */
    PENDING,

    /** 采购中（已下单，等到货） */
    ORDERED,

    /** 部分到货 */
    PARTIALLY_RECEIVED,

    /** 全部到货（终态） */
    RECEIVED,

    /** 已取消（终态） */
    CANCELLED;
}
