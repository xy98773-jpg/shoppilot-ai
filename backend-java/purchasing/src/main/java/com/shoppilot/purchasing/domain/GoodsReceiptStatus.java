package com.shoppilot.purchasing.domain;

/**
 * 到货单状态（{@code goods_receipts.status}）。
 *
 * <p>权威定义：{@code DESIGN_BASIS.md §9.14}；取值与迁移
 * {@code V1.0.11__purchasing.sql} 的 CHECK 约束<b>逐字一致</b>，改枚举必须同步改迁移
 * （但已执行的迁移不得修改，只能新增）。
 */
public enum GoodsReceiptStatus {

    /** 已录入未确认（不产生库存流水） */
    DRAFT,

    /** 已确认（已产生 PURCHASE_INBOUND 流水与库存变更） */
    CONFIRMED,

    /** 已冲销（库存已回退，采购单状态已重算） */
    REVERSED,

    /** 已作废（仅 DRAFT 可直接作废） */
    CANCELLED;
}
