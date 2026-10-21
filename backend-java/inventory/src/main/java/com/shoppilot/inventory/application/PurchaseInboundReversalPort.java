package com.shoppilot.inventory.application;

import java.math.BigDecimal;

/**
 * 采购到货入库冲销的采购侧回滚端口（M8-T05 / {@code R185}）。
 *
 * <h2>为什么是端口（口径归属方声明、实现方在 purchasing）</h2>
 * {@code inventory} 是库存余额表的<b>唯一写入口</b>（{@code MODULE_BOUNDARIES §2.5}），
 * 但采购到货入库（{@code PURCHASE_INBOUND}）写反向流水扣 {@code on_hand} 时，<b>必须</b>同事务回退
 * {@code purchase_order_items.received_qty} 并重算 {@code purchase_orders.status}——
 * 否则派生在途（{@code ordered_qty − received_qty}）会<b>少计</b>（{@code R185}，{@code PURCHASING_WORKFLOW §8.3}）。
 *
 * <p>回退 {@code received_qty} 属 <b>purchasing</b> 的写职责，{@code inventory} 不得直写采购表
 * （与 {@link InTransitFactProvider} 同型：声明在 {@code inventory}、实现在 {@code purchasing}，
 * 由 {@code app} 组合根经组件扫描自动装配；依赖方向 {@code purchasing → inventory} 本就合法）。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>仅用于 {@code PURCHASE_INBOUND} 冲销；调用方（{@code InventoryWriteService#attemptReversal}）
 *       只在原流水 {@code txn_type = PURCHASE_INBOUND} 时调用；</li>
 *   <li>在原流水的<b>同一事务</b>内调用（{@code REQUIRED} 加入），整体原子（失败即整笔冲销回滚）；</li>
 *   <li>{@code qty} 为<b>回退量</b>（{@code > 0}，等于原 {@code PURCHASE_INBOUND.qty_delta} 的绝对值）；</li>
 *   <li>{@code idempotencyKey} 即原 {@code PURCHASE_INBOUND} 幂等键，末段 {@code :<purchase_order_item_id>}
 *       （{@code R180}），实现方据此定位采购明细行；</li>
 *   <li>租户隔离由实现方保证（{@code team_id} 取自 {@link com.shoppilot.common.context.TeamContext}）。</li>
 * </ul>
 */
public interface PurchaseInboundReversalPort {

    /**
     * 回退一笔采购到货入库在采购侧的累计影响（明细 {@code received_qty −= qty} + 重算 {@code purchase_orders}）。
     *
     * @param goodsReceiptId 到货单 ID（= 原流水 {@code biz_ref_id}）
     * @param idempotencyKey 原 {@code PURCHASE_INBOUND} 幂等键（末段为 {@code purchase_order_item_id}）
     * @param qty           回退量（{@code > 0}）
     */
    void reversePurchaseInbound(long goodsReceiptId, String idempotencyKey, BigDecimal qty);
}
