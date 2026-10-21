package com.shoppilot.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link InventoryInTransitQueryService} 的<b>唯一实现</b>（{@code DESIGN_BASIS §10.4} 口径单点）。
 *
 * <p>本类只做两件事：<b>取事实</b>（委托 {@link InTransitFactProvider}，实现方在 {@code purchasing}）
 * 与<b>套口径</b>（按 SKU 的窗口末日过滤、按 SKU 累加、剔除 {@code <= 0}）。
 * <b>不</b>查任何采购表、<b>不</b>写任何库、<b>不</b>缓存。
 *
 * <p>★ 本类不标注 {@code @Transactional}：纯只读、无跨表原子性要求；租户隔离由实现方的
 * {@code team_id} 条件 + 租户线保证。
 */
@Service
public class InventoryInTransitQueryServiceImpl implements InventoryInTransitQueryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryInTransitQueryServiceImpl.class);

    private final InTransitFactProvider factProvider;

    public InventoryInTransitQueryServiceImpl(InTransitFactProvider factProvider) {
        this.factProvider = factProvider;
    }

    @Override
    public Map<Long, BigDecimal> batchGetEffectiveInTransit(long teamId,
                                                           long warehouseId,
                                                           Map<Long, LocalDate> skuWindowEnds) {
        if (skuWindowEnds == null || skuWindowEnds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> totals = new HashMap<>();
        Set<Long> warned = new HashSet<>();
        for (InTransitFactProvider.InTransitFact fact : factProvider.loadFacts(
                teamId, warehouseId, skuWindowEnds.keySet())) {
            LocalDate windowEnd = skuWindowEnds.get(fact.skuId());
            if (windowEnd == null) {
                // 调用方没给该 SKU 的窗口 ⇒ 口径不可判定，保守地不计入（并告警一次）
                if (warned.add(fact.skuId())) {
                    log.warn("在途窗口缺失，该 SKU 不计入有效在途：teamId={} warehouseId={} skuId={}",
                            teamId, warehouseId, fact.skuId());
                }
                continue;
            }
            LocalDate arrival = fact.arrivalDate();
            // §10.4：含等号；arrival 为空 ⇒ 保守计入
            if (arrival != null && arrival.isAfter(windowEnd)) {
                continue;
            }
            totals.merge(fact.skuId(), fact.remainingQty(), BigDecimal::add);
        }
        return positiveOnly(totals);
    }

    @Override
    public Map<Long, BigDecimal> batchGetInTransit(long teamId,
                                                  long warehouseId,
                                                  Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> requested = new HashSet<>(skuIds);
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (InTransitFactProvider.InTransitFact fact : factProvider.loadFacts(teamId, warehouseId, requested)) {
            if (!requested.contains(fact.skuId())) {
                continue;
            }
            totals.merge(fact.skuId(), fact.remainingQty(), BigDecimal::add);
        }
        return positiveOnly(totals);
    }

    /** 只保留 {@code > 0} 的项（负数/零对 {@code IP} 与展示都无意义，且会让「只含有在途的 SKU」语义失真）。 */
    private static Map<Long, BigDecimal> positiveOnly(Map<Long, BigDecimal> totals) {
        Map<Long, BigDecimal> out = new HashMap<>(totals.size());
        totals.forEach((skuId, qty) -> {
            if (qty != null && qty.signum() > 0) {
                out.put(skuId, qty);
            }
        });
        return Map.copyOf(out);
    }
}
