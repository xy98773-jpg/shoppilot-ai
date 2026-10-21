package com.shoppilot.purchasing.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shoppilot.inventory.application.InTransitFactProvider;
import com.shoppilot.purchasing.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.shoppilot.purchasing.infrastructure.persistence.entity.PurchaseOrderItemEntity;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.PurchaseOrderItemMapper;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.PurchaseOrderMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link InTransitFactProvider} 的采购侧实现（{@code DESIGN_BASIS §10.4.1} 端口反转，
 * {@code MODULE_BOUNDARIES §8.2 E-1}，{@code DESIGN_REVIEW §5.78} 方案 B′）。
 *
 * <h2>口径（逐条来自 SSOT，改动须同步 SSOT）</h2>
 * <ol>
 *   <li><b>计入状态</b> {@code {PENDING, ORDERED, PARTIALLY_RECEIVED}}；{@code DRAFT}（尚未向供应商下单）、
 *       {@code RECEIVED}、{@code CANCELLED} 不计；</li>
 *   <li><b>单行剩余在途</b> {@code ordered_qty − received_qty}，只保留 {@code > 0}；</li>
 *   <li><b>到期日</b> {@code COALESCE(行级 expected_arrival_date, 单头 expected_arrival_date)}，
 *       两者皆空 ⇒ {@code null}（由口径层保守计入窗口）；</li>
 *   <li><b>不</b>做窗口过滤（窗口属 {@code inventory.InventoryInTransitQueryService}）。</li>
 * </ol>
 *
 * <h2>实现约束</h2>
 * <ul>
 *   <li><b>不写自定义 SQL</b>（两个 Mapper 的 javadoc 明文），一律 {@code BaseMapper} + LambdaQuery
 *       ⇒ 租户线自动追加 {@code team_id}；此处<b>仍显式</b> {@code eq(team_id)} 作双保险。</li>
 *   <li>两查：先按 {@code (team_id, warehouse_id, status)} 取采购单，再按 {@code purchase_order_id} 取明细；
 *       {@code IN} 列表按 {@value #CHUNK_SIZE} 分组。</li>
 *   <li>索引现状（R184，本批<b>不加索引</b>）：{@code purchase_orders} 有
 *       {@code idx_po_team_status_time (team_id, status, created_at)}，无
 *       {@code (team_id, warehouse_id, status)} 联合索引 ⇒ 按前两列命中后由 SQL 过滤 {@code warehouse_id}。</li>
 * </ul>
 */
@Component
public class PurchaseOrderInTransitFactProvider implements InTransitFactProvider {

    /** 计入在途的采购单状态（{@code DESIGN_BASIS §10.4}，与 {@code PurchaseOrderStatus} 逐字一致）。 */
    private static final List<String> IN_TRANSIT_STATUSES = List.of(
            "PENDING", "ORDERED", "PARTIALLY_RECEIVED");

    /** 单次 {@code IN} 分片大小（沿用 inventory/sales 的 chunk 惯例）。 */
    private static final int CHUNK_SIZE = 500;

    private final PurchaseOrderMapper purchaseOrderMapper;
    private final PurchaseOrderItemMapper purchaseOrderItemMapper;

    public PurchaseOrderInTransitFactProvider(PurchaseOrderMapper purchaseOrderMapper,
                                              PurchaseOrderItemMapper purchaseOrderItemMapper) {
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.purchaseOrderItemMapper = purchaseOrderItemMapper;
    }

    @Override
    public List<InTransitFact> loadFacts(long teamId, long warehouseId, Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        // ① 在途状态的采购单（只取主键与单头到期日）
        List<PurchaseOrderEntity> orders = purchaseOrderMapper.selectList(
                Wrappers.<PurchaseOrderEntity>lambdaQuery()
                        .eq(PurchaseOrderEntity::getTeamId, teamId)
                        .eq(PurchaseOrderEntity::getWarehouseId, warehouseId)
                        .in(PurchaseOrderEntity::getStatus, IN_TRANSIT_STATUSES)
                        .select(PurchaseOrderEntity::getId, PurchaseOrderEntity::getExpectedArrivalDate));
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, LocalDate> poArrival = new HashMap<>(orders.size());
        for (PurchaseOrderEntity order : orders) {
            poArrival.put(order.getId(), order.getExpectedArrivalDate());
        }

        // ② 明细：按 purchase_order_id × sku_id 两维分片，避免 IN 过长
        List<Long> poIds = new ArrayList<>(poArrival.keySet());
        List<Long> allSkuIds = new ArrayList<>(new java.util.LinkedHashSet<>(skuIds));
        List<InTransitFact> facts = new ArrayList<>();
        for (List<Long> poChunk : chunks(poIds)) {
            for (List<Long> skuChunk : chunks(allSkuIds)) {
                for (PurchaseOrderItemEntity item : purchaseOrderItemMapper.selectList(
                        Wrappers.<PurchaseOrderItemEntity>lambdaQuery()
                                .eq(PurchaseOrderItemEntity::getTeamId, teamId)
                                .in(PurchaseOrderItemEntity::getPurchaseOrderId, poChunk)
                                .in(PurchaseOrderItemEntity::getSkuId, skuChunk)
                                .select(PurchaseOrderItemEntity::getPurchaseOrderId,
                                        PurchaseOrderItemEntity::getSkuId,
                                        PurchaseOrderItemEntity::getOrderedQty,
                                        PurchaseOrderItemEntity::getReceivedQty,
                                        PurchaseOrderItemEntity::getExpectedArrivalDate))) {
                    BigDecimal ordered = item.getOrderedQty() == null ? BigDecimal.ZERO : item.getOrderedQty();
                    BigDecimal received = item.getReceivedQty() == null ? BigDecimal.ZERO : item.getReceivedQty();
                    BigDecimal remaining = ordered.subtract(received);
                    if (remaining.signum() <= 0) {
                        continue;
                    }
                    LocalDate arrival = item.getExpectedArrivalDate() != null
                            ? item.getExpectedArrivalDate()
                            : poArrival.get(item.getPurchaseOrderId());
                    facts.add(new InTransitFact(item.getSkuId(), remaining, arrival));
                }
            }
        }
        return facts;
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> out = new ArrayList<>((ids.size() + CHUNK_SIZE - 1) / CHUNK_SIZE);
        for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
            out.add(ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size())));
        }
        return out;
    }
}
