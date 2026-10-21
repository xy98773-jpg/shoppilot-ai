package com.shoppilot.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * 在途库存口径服务（{@code DESIGN_BASIS §10.4} / {@code §10.4.1}；{@code R178} + {@code R124} 闭环）。
 *
 * <h2>口径单点</h2>
 * <p>在途的<b>全部语义</b>（状态集合、剩余量、到期日、窗口判定）只在本服务的实现类内实现一次；
 * 事实来源经 {@link InTransitFactProvider} 由 {@code purchasing} 提供（端口反转，见该接口 javadoc）。
 * 调用方（{@code analysis} 补货公式、{@code inventory} 余额展示）<b>不得</b>自行聚合采购表，
 * 也不得自行实现窗口比较。
 *
 * <h2>两个方法的分工</h2>
 * <table>
 *   <caption>在途口径的两面</caption>
 *   <tr><th>方法</th><th>窗口过滤</th><th>用途</th><th>进 {@code IP}</th></tr>
 *   <tr><td>{@link #batchGetEffectiveInTransit}</td><td>✅ 按 SKU 的 {@code today + L + R} 过滤</td>
 *       <td>补货公式（{@code DESIGN_BASIS §10.4}）</td><td>✅</td></tr>
 *   <tr><td>{@link #batchGetInTransit}</td><td>❌ 全量累计</td>
 *       <td>余额展示（{@code API_CONTRACT #27}）与 {@code analysis_items.in_transit_qty} 快照</td>
 *       <td>❌</td></tr>
 * </table>
 *
 * <p>★ 物化列 {@code inventory_balances.in_transit_qty} 自本批起<b>退役</b>（零写零读），
 * 该列保留仅为迁移兼容（{@code §5.78}，零迁移）。
 */
public interface InventoryInTransitQueryService {

    /**
     * 批量计算「有效在途」（应用 {@code §10.4} 到货窗口）。
     *
     * <p>★ <b>窗口边界必须逐 SKU</b>：交期 {@code L} 按 {@code POLICY → SKU → SUPPLIER → default}
     * 四级回退解析，各 SKU 的 {@code L} 可能不同，因此入参是 {@code Map<skuId, windowEndDate>}
     * 而不是单个日期。退化为单值会<b>漏计</b> {@code L} 较大的 SKU 的远期在途。
     *
     * <p>窗口判定：{@code arrivalDate == null || !arrivalDate.isAfter(windowEndDate)}（含等号）。
     *
     * @param teamId        当前团队
     * @param warehouseId   到货仓库
     * @param skuWindowEnds 每个 SKU 的窗口末日（{@code today + L + R}）；缺某 SKU ⇒ 该 SKU 不计入并告警
     * @return {@code sku_id -> 有效在途}；<b>只含 &gt; 0 的项</b>，调用方一律 {@code getOrDefault(skuId, ZERO)}
     */
    Map<Long, BigDecimal> batchGetEffectiveInTransit(long teamId,
                                                     long warehouseId,
                                                     Map<Long, LocalDate> skuWindowEnds);

    /**
     * 批量计算「原始在途」（<b>不</b>应用到货窗口）。
     *
     * @param teamId      当前团队
     * @param warehouseId 到货仓库
     * @param skuIds      关心的 SKU 集合（空集 ⇒ 空 map）
     * @return {@code sku_id -> 在途量}；<b>只含 &gt; 0 的项</b>
     */
    Map<Long, BigDecimal> batchGetInTransit(long teamId,
                                            long warehouseId,
                                            Collection<Long> skuIds);
}
