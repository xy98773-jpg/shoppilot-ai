package com.shoppilot.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 在途事实端口（{@code DESIGN_BASIS §10.4.1}，{@code MODULE_BOUNDARIES §8.2 E-1}，{@code §5.78} 方案 B′）。
 *
 * <h2>为什么是「端口反转」而不是直接聚合</h2>
 * <p>在途数据（{@code purchase_order_items.ordered_qty − received_qty}）物理上属于 <b>purchasing</b>，
 * 而口径消费者是 <b>analysis</b>（补货公式的 {@code IP}）与 <b>inventory</b>（余额展示）。
 * 依赖方向 {@code identity→catalog→{inventory,sales}→analysis→replenishment→purchasing}
 * 既禁止 {@code inventory → purchasing}，也禁止 {@code analysis → purchasing}。
 *
 * <p>因此把「取数」抽象成本端口，<b>声明在 {@code inventory}</b>（口径归属方），
 * <b>由 {@code purchasing} 实现</b>（{@code purchasing → inventory} 本就是合法方向），
 * 由 {@code app}（组合根）完成装配。窗口规则（{@code §10.4}）<b>只在</b>
 * {@link InventoryInTransitQueryService} 内实现一次，本端口<b>不做任何过滤</b>。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>只返回<b>事实</b>：每个（采购明细行 × SKU）一条剩余在途量与到期日；</li>
 *   <li><b>不</b>按到货窗口过滤（窗口是口径，不是事实）；</li>
 *   <li><b>不</b>做 {@code skuIds} 之外的数据聚合（调用方按需 {@code merge}）；</li>
 *   <li>租户隔离由实现方保证（{@code team_id} 显式条件 + 租户线双保险）。</li>
 * </ul>
 */
public interface InTransitFactProvider {

    /**
     * 加载在途事实。
     *
     * @param teamId      当前团队（铁律⑤：来自 JWT 上下文，不接受客户端传入）
     * @param warehouseId 到货仓库
     * @param skuIds      关心的 SKU 集合（空集 ⇒ 返回空列表，<b>不得</b>发 {@code IN ()}）
     * @return 在途事实（剩余量恒 {@code > 0}；{@code arrivalDate} 可 {@code null}）
     */
    List<InTransitFact> loadFacts(long teamId, long warehouseId, Collection<Long> skuIds);

    /**
     * 单条在途事实。
     *
     * @param skuId        SKU 主键
     * @param remainingQty 剩余在途量（{@code ordered_qty − received_qty}，恒 {@code > 0}）
     * @param arrivalDate  到期日（{@code COALESCE(行级, 单头)}；两者皆空 ⇒ {@code null}，按
     *                     {@code §10.4} 保守计入窗口）
     */
    record InTransitFact(long skuId, BigDecimal remainingQty, LocalDate arrivalDate) {
    }
}
