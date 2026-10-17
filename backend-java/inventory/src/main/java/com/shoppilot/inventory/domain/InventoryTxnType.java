package com.shoppilot.inventory.domain;

/**
 * 库存动作类型（{@code DESIGN_BASIS §9.6} / {@code DATABASE_DESIGN §5.10}）。
 *
 * <p>取值必须与 {@code V1.0.6__inventory.sql} 的
 * {@code ck_invtxn_type CHECK (txn_type IN (...))} <b>逐字一致</b>——否则写库会触发
 * MySQL {@code ERROR 3819}（Check constraint violated）。
 *
 * <p>{@code txn_type} 描述<b>库存动作</b>，与描述<b>业务单据来源</b>的
 * {@link InventoryBizType} 正交（{@code DATABASE_DESIGN §5.10} 设计要点 3）。
 */
public enum InventoryTxnType {

    /** 初始库存导入（{@code INVENTORY_SNAPSHOT} 落库）。 */
    INIT_IMPORT,
    /** 采购到货入库（业务触发方为 {@code purchasing}）。 */
    PURCHASE_INBOUND,
    /** 销售出库（首期预留，仅内部/测试路径使用）。 */
    SALE_OUTBOUND,
    /** 盘点调整（写目标值 → 落差额）。 */
    COUNT_ADJUST,
    /** 手工调整（直接写带符号增量）。 */
    MANUAL_ADJUST,
    /** 冲销（只能冲销非 {@code REVERSAL} 的流水，一笔仅一次）。 */
    REVERSAL
}
