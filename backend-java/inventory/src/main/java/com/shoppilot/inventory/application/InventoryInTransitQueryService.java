package com.shoppilot.inventory.application;

import java.util.List;

/**
 * 在途库存查询服务（<b>骨架 · M3-T16</b>）。
 *
 * <h2>本批只提供接口 + 占位实现，<b>由 M6（purchasing）实现</b></h2>
 * 理由（{@code DATABASE_DESIGN §5.9}「在途库存」口径 + {@code MODULE_BOUNDARIES} DAG）：
 * <ol>
 *   <li><b>数据来源属 M6</b>：{@code inventory_balances.in_transit_qty} 由<b>采购单状态变更</b>驱动
 *       （{@code DRAFT→PENDING→ORDERED} 时该 PO 的 {@code ordered_qty − received_qty} 进入在途口径），
 *       而采购单/Owner 模块属 M6，本批（M3）尚未构建，无法取数；</li>
 *   <li><b>避免 premature 跨模块耦合</b>：若在本批直接接 purchasing 表，会令 inventory → purchasing 反向依赖，
 *       破坏 {@code identity→catalog→{inventory,sales}→analysis→replenishment→purchasing} 的 DAG
 *       （{@code MODULE_BOUNDARIES §2.5}）；</li>
 *   <li><b>为 M4（analysis）预留就绪态</b>：分析引擎需要「在途」作为 {@code IP} 的可支配量输入，故先固化
 *       接口契约与返回形状，待 M6 落库后在实现类内填实，调用方（analysis）无需改动。</li>
 * </ol>
 *
 * <h2>占位实现语义</h2>
 * 当前返回<b>空列表</b>（不查库、不抛异常），保证编译通过与 Spring 装配成功；M6 接管后替换为真实查询。
 */
public interface InventoryInTransitQueryService {

    /**
     * 查询某仓库的「在途库存」明细（SKU 级）。
     *
     * @param teamId       当前团队（占位实现忽略）
     * @param warehouseId  仓库（占位实现忽略）
     * @return 在途明细（本批恒为<b>空列表</b>，M6 实现后填实）
     */
    List<InTransitItem> queryInTransit(long teamId, long warehouseId);

    /** 在途明细项（占位形状，M6 实现时按需扩充）。 */
    record InTransitItem(long skuId, java.math.BigDecimal inTransitQty) {
    }
}
