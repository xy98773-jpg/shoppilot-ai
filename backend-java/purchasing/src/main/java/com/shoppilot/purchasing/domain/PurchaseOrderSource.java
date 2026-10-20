package com.shoppilot.purchasing.domain;

/**
 * 采购单来源（{@code purchase_orders.source}）。
 *
 * <p>权威定义：{@code DESIGN_BASIS.md §9.17}；取值与迁移
 * {@code V1.0.11__purchasing.sql} 的 CHECK 约束<b>逐字一致</b>，改枚举必须同步改迁移
 * （但已执行的迁移不得修改，只能新增）。
 */
public enum PurchaseOrderSource {

    /** 手工创建 */
    MANUAL,

    /** 由补货建议转采购 */
    REPLENISHMENT;
}
