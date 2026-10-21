package com.shoppilot.inventory.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.catalog.application.SkuQueryService;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.inventory.application.dto.AlertView;
import com.shoppilot.inventory.application.dto.BalanceKey;
import com.shoppilot.inventory.application.dto.BalanceView;
import com.shoppilot.inventory.application.dto.InventoryPageQuery;
import com.shoppilot.inventory.application.dto.TransactionView;
import com.shoppilot.inventory.application.support.InventorySorts;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryTransactionEntity;
import com.shoppilot.inventory.infrastructure.persistence.entity.ReplenishmentPolicyEntity;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryBalanceMapper;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryTransactionMapper;
import com.shoppilot.inventory.infrastructure.persistence.mapper.ReplenishmentPolicyMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存只读查询服务（{@code API_CONTRACT §10.4}：balances / transactions / alerts）。
 *
 * <h2>边界（M3-T10，B7）</h2>
 * <ul>
 *   <li>全部查询走<b>只读事务</b>（{@code @Transactional(readOnly=true)}）；<b>绝不产生副作用</b>
 *       （不写库、不写审计、不触发分析）；</li>
 *   <li>{@code sku_code} 经 catalog 的 {@link SkuQueryService} 取（展示字段），
 *       <b>禁止注入 {@code SkuMapper}</b>（{@code MODULE_BOUNDARIES §2.4}，参见风险 {@code R46}）；</li>
 *   <li>{@code available_qty} 仅展示，<b>禁止参与补货判断</b>（{@code DESIGN_BASIS §3.1}）；</li>
 *   <li>预警基于 {@code inventory_balances} 阈值判断，<b>无状态、不落库</b>（{@code INVENTORY_WORKFLOW §5.7}）；</li>
 *   <li>{@code hasBalance} 仅供 M6（purchasing）编排使用，<b>不</b>在此实现 {@code CAT-3007}
 *       拦截（catalog → inventory 会成环，{@code R51}）。</li>
 * </ul>
 */
@Service
public class InventoryQueryService {

    /** 允许排序的物理列白名单（snake_case）。 */
    private static final Set<String> BALANCE_SORT_WHITELIST = Set.of(
            "id", "warehouse_id", "sku_id", "on_hand_qty", "available_qty", "last_txn_at", "version");
    /** 批量余额存在性查询的单次 {@code IN} 分片上限。 */
    private static final int BALANCE_KEY_CHUNK_SIZE = 1000;

    private static final Set<String> TXN_SORT_WHITELIST = Set.of(
            "id", "warehouse_id", "sku_id", "txn_type", "qty_delta", "qty_after",
            "occurred_at", "created_at");

    /** 滞销判定窗口（天）；M6 可改为读取策略 {@code slow_moving_days}，本批用默认 30。 */
    private static final int SLOW_MOVING_WINDOW_DAYS = 30;

    private final InventoryBalanceMapper balanceMapper;
    private final InventoryTransactionMapper transactionMapper;
    private final SkuQueryService skuQueryService;
    private final ReplenishmentPolicyMapper policyMapper;
    private final InventoryInTransitQueryService inTransitQueryService;

    public InventoryQueryService(InventoryBalanceMapper balanceMapper,
                                 InventoryTransactionMapper transactionMapper,
                                 SkuQueryService skuQueryService,
                                 ReplenishmentPolicyMapper policyMapper,
                                 InventoryInTransitQueryService inTransitQueryService) {
        this.balanceMapper = balanceMapper;
        this.transactionMapper = transactionMapper;
        this.skuQueryService = skuQueryService;
        this.policyMapper = policyMapper;
        this.inTransitQueryService = inTransitQueryService;
    }

    // =====================================================================
    // balances 列表（F-INV-01）
    // =====================================================================

    /**
     * 余额列表（{@code F-INV-01}）。
     *
     * <p>★ <b>在途换源（{@code R178}/{@code §5.78}）</b>：{@code in_transit_qty} 不再读
     * {@code inventory_balances.in_transit_qty}（该列已退役、恒 0），改为按<b>本页 SKU 一次</b>
     * 调用 {@link InventoryInTransitQueryService#batchGetInTransit} 派生填充
     * （<b>不</b>应用到货窗口——展示口径与补货口径不同，见 {@code DESIGN_BASIS §10.4}）。
     *
     * <p><b>已知限制（R184）</b>：{@code warehouseId} 为空时（跨仓查询）<b>不</b>填充在途
     * （保持 {@code 0}），不发明跨仓聚合语义；需要跨仓在途时应显式传 {@code warehouse_id}。
     * 该分支只多一次按页聚合查询，不做 N+1。
     */
    @Transactional(readOnly = true)
    public PageResult<BalanceView> listBalances(InventoryPageQuery query, Long warehouseId, Long skuId) {
        List<InventoryPageQuery.SortSpec> sort = query.validatedSort(BALANCE_SORT_WHITELIST);
        QueryWrapper<InventoryBalanceEntity> wrapper = new QueryWrapper<>();
        if (warehouseId != null) {
            wrapper.eq("warehouse_id", warehouseId);
        }
        if (skuId != null) {
            wrapper.eq("sku_id", skuId);
        }
        // ★ 默认按 available_qty 升序：既符合「低库存优先」语义，又命中索引 idx_invbal_team_available
        InventorySorts.apply(wrapper, sort, "available_qty", false);

        Page<InventoryBalanceEntity> page = balanceMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        Set<Long> pageSkuIds = page.getRecords().stream()
                .map(InventoryBalanceEntity::getSkuId).collect(Collectors.toSet());
        Map<Long, String> skuCodes = skuCodesOf(pageSkuIds);
        Map<Long, BigDecimal> inTransit = (warehouseId == null || pageSkuIds.isEmpty())
                ? Map.of()
                : inTransitQueryService.batchGetInTransit(
                        TeamContext.require().teamId(), warehouseId, pageSkuIds);
        List<BalanceView> list = page.getRecords().stream()
                .map(e -> BalanceView.of(e, skuCodes.getOrDefault(e.getSkuId(), null),
                        inTransit.get(e.getSkuId())))
                .toList();
        return PageResult.of(page.getTotal(), query.getPage(), query.getPageSize(), list);
    }

    // =====================================================================
    // transactions 列表（F-INV-02）
    // =====================================================================

    @Transactional(readOnly = true)
    public PageResult<TransactionView> listTransactions(InventoryPageQuery query, Long warehouseId, Long skuId,
                                                       String txnType, String dateFrom, String dateTo) {
        List<InventoryPageQuery.SortSpec> sort = query.validatedSort(TXN_SORT_WHITELIST);
        QueryWrapper<InventoryTransactionEntity> wrapper = new QueryWrapper<>();
        if (warehouseId != null) {
            wrapper.eq("warehouse_id", warehouseId);
        }
        if (skuId != null) {
            wrapper.eq("sku_id", skuId);
        }
        if (txnType != null && !txnType.isBlank()) {
            wrapper.eq("txn_type", txnType);
        }
        LocalDateTime from = parseDateTime(dateFrom, true);
        LocalDateTime to = parseDateTime(dateTo, false);
        if (from != null) {
            wrapper.ge("occurred_at", from);
        }
        if (to != null) {
            wrapper.le("occurred_at", to);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw BizException.withDetails(ErrorCodes.COM_0007, "分页或排序参数不合法",
                    List.of(new ApiErrorDetail("dateFrom", "dateFrom 不能晚于 dateTo", dateFrom)));
        }
        InventorySorts.apply(wrapper, sort, "occurred_at", true);

        Page<InventoryTransactionEntity> page = transactionMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        Map<Long, String> skuCodes = skuCodesOf(page.getRecords().stream()
                .map(InventoryTransactionEntity::getSkuId).collect(Collectors.toSet()));
        List<TransactionView> list = page.getRecords().stream()
                .map(e -> TransactionView.of(e, skuCodes.getOrDefault(e.getSkuId(), null)))
                .toList();
        return PageResult.of(page.getTotal(), query.getPage(), query.getPageSize(), list);
    }

    // =====================================================================
    // transaction 详情（F-INV-02）：不存在 / 异团队 → INV-5008（404）
    // =====================================================================

    @Transactional(readOnly = true)
    public TransactionView getTransaction(long id) {
        // 租户线自动追加 team_id：异团队流水查不到 → 按「不存在」处理（§5.6，INV-5008）
        InventoryTransactionEntity entity = transactionMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCodes.INV_5008, "库存流水不存在");
        }
        SkuEntity sku = skuQueryService.getSku(entity.getSkuId());
        String skuCode = sku != null ? sku.getSkuCode() : null;
        return TransactionView.of(entity, skuCode);
    }

    // =====================================================================
    // alerts 列表（F-INV-05）：无状态、不落库
    // =====================================================================

    @Transactional(readOnly = true)
    public PageResult<AlertView> listAlerts(InventoryPageQuery query, Long warehouseId, Long skuId) {
        // 取全部命中的余额（按筛选条件，团队由租户线约束），再内存分页
        LambdaQueryWrapper<InventoryBalanceEntity> wrapper = new LambdaQueryWrapper<>();
        if (warehouseId != null) {
            wrapper.eq(InventoryBalanceEntity::getWarehouseId, warehouseId);
        }
        if (skuId != null) {
            wrapper.eq(InventoryBalanceEntity::getSkuId, skuId);
        }
        // ★ 命中 idx_invbal_team_available：按 available_qty 升序（缺货/低库存自然排前）
        wrapper.orderByAsc(InventoryBalanceEntity::getAvailableQty);
        List<InventoryBalanceEntity> balances = balanceMapper.selectList(wrapper);

        Map<Long, String> skuCodes = skuCodesOf(balances.stream()
                .map(InventoryBalanceEntity::getSkuId).collect(Collectors.toSet()));
        Map<String, BigDecimal> safetyByKey = safetyStockByKey(balances);

        // SLOWMOVING：近窗口内有 SALE_OUTBOUND 的 (warehouse_id, sku_id) 集合
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(SLOW_MOVING_WINDOW_DAYS);
        Set<String> soldRecently = recentSalesKeys(cutoff);

        List<AlertView> alerts = new ArrayList<>();
        for (InventoryBalanceEntity b : balances) {
            BigDecimal onHand = b.getOnHandQty();
            BigDecimal available = b.getAvailableQty();
            String key = b.getSkuId() + ":" + b.getWarehouseId();

            if (onHand != null && onHand.compareTo(BigDecimal.ZERO) <= 0) {
                alerts.add(alert(b, skuCodes, "STOCKOUT", null, "库存为 0 或为负，存在缺货风险"));
            }
            BigDecimal threshold = safetyByKey.get(key);
            if (threshold != null && onHand != null && onHand.compareTo(BigDecimal.ZERO) > 0
                    && available != null && available.compareTo(threshold) <= 0) {
                alerts.add(alert(b, skuCodes, "LOWSTOCK", threshold,
                        "可用库存 " + available.toPlainString() + " 已低于安全库存阈值 "
                                + threshold.toPlainString()));
            }
            if (onHand != null && onHand.compareTo(BigDecimal.ZERO) > 0 && !soldRecently.contains(key)) {
                alerts.add(alert(b, skuCodes, "SLOWMOVING", null,
                        "近 " + SLOW_MOVING_WINDOW_DAYS + " 天无销售且库存为正，存在滞销风险"));
            }
        }

        int total = alerts.size();
        int fromIndex = Math.max(0, (query.getPage() - 1) * query.getPageSize());
        int toIndex = Math.min(total, fromIndex + query.getPageSize());
        List<AlertView> pageList = fromIndex >= total ? List.of() : alerts.subList(fromIndex, toIndex);
        return PageResult.of(total, query.getPage(), query.getPageSize(), pageList);
    }

    // =====================================================================
    // R51：暴露「该 SKU/仓库是否持有库存」查询（供 M6 编排；不做 CAT-3007 拦截）
    // =====================================================================

    @Transactional(readOnly = true)
    public boolean hasBalance(long skuId, long warehouseId) {
        LambdaQueryWrapper<InventoryBalanceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryBalanceEntity::getWarehouseId, warehouseId)
                .eq(InventoryBalanceEntity::getSkuId, skuId)
                .last("LIMIT 1");
        return balanceMapper.selectCount(wrapper) > 0;
    }

    /**
     * <b>批量判定</b>「这些 {@code (sku_id, warehouse_id)} 是否已有余额行」（M3-B10.5 / R78）。
     *
     * <h2>用途与口径</h2>
     * {@code importdata} 在 {@code VALIDATED} 阶段要用它产生 {@code IMP-4006}（{@code WARNING}，
     * 「该 {@code (wh, sku)} 已有余额行，确认后将按增量累加」，{@code IMPORT_WORKFLOW §7.5}）。
     * 判定口径就是 {@code uk_invbal_team_wh_sku}：<b>行存在即命中</b>，不看数量是否为 0、不看 {@code version}。
     *
     * <h2>为什么不是逐行 {@link #hasBalance}</h2>
     * 那是 N+1：1 万行 = 1 万次往返。这里按 {@value #BALANCE_KEY_CHUNK_SIZE} 分片对
     * {@code sku_id} 发 {@code IN} 查询，再用入参集合<b>精确过滤</b>（同一 SKU 跨仓库是笛卡尔超集，
     * 必须回到精确 {@code Set} 判定，不能只看「该 SKU 有任意余额行」）。
     *
     * <p>只读：{@code @Transactional(readOnly = true)}，无任何副作用。
     *
     * @param keys 待判定的业务键（{@code null}/空 → 空结果）
     * @return 命中的子集（与入参同一元素类型）
     */
    @Transactional(readOnly = true)
    public Set<BalanceKey> findExistingBalanceKeys(Collection<BalanceKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        Set<BalanceKey> wanted = new HashSet<>(keys);
        Set<Long> skuIds = wanted.stream().map(BalanceKey::skuId).collect(Collectors.toSet());
        Set<Long> warehouseIds = wanted.stream().map(BalanceKey::warehouseId).collect(Collectors.toSet());
        if (skuIds.isEmpty() || warehouseIds.isEmpty()) {
            return Set.of();
        }
        Set<BalanceKey> hit = new HashSet<>();
        List<Long> all = new ArrayList<>(skuIds);
        for (int from = 0; from < all.size(); from += BALANCE_KEY_CHUNK_SIZE) {
            List<Long> chunk = all.subList(from, Math.min(from + BALANCE_KEY_CHUNK_SIZE, all.size()));
            List<InventoryBalanceEntity> rows = balanceMapper.selectList(
                    new LambdaQueryWrapper<InventoryBalanceEntity>()
                            .select(InventoryBalanceEntity::getSkuId, InventoryBalanceEntity::getWarehouseId)
                            .in(InventoryBalanceEntity::getSkuId, chunk)
                            .in(InventoryBalanceEntity::getWarehouseId, warehouseIds));
            for (InventoryBalanceEntity row : rows) {
                BalanceKey key = BalanceKey.of(row.getSkuId(), row.getWarehouseId());
                if (wanted.contains(key)) {
                    hit.add(key);
                }
            }
        }
        return hit;
    }

    // =====================================================================
    // 分析装配批量只读（M8-T06 / R126：analysis 经本门面而非直连 InventoryBalanceMapper /
    // ReplenishmentPolicyMapper）
    // =====================================================================

    /**
     * 批量取某仓的余额行（供 {@code analysis} 装配 {@code on_hand_qty}/{@code committed_qty}）。
     *
     * <p>★ 团队隔离由租户线兜底（与 {@link #findExistingBalanceKeys} 同口径）；仅按
     * {@code (warehouse_id, sku_id)} 过滤，<b>不</b>额外加 {@code team_id}（原装配器亦如此，依赖租户线）。
     * 缺行不返回（调用方按「缺行 ⇒ 0.000」处理）。
     *
     * @param warehouseId 仓库（{@code null} ⇒ 空 Map）
     * @param skuIds      SKU 集合（空 ⇒ 空 Map）
     * @return {@code sku_id -> 余额实体}；无余额的 sku <b>不在</b> map 中
     */
    @Transactional(readOnly = true)
    public Map<Long, InventoryBalanceEntity> balancesBySkuIds(Long warehouseId, Collection<Long> skuIds) {
        if (warehouseId == null || skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, InventoryBalanceEntity> bySku = new LinkedHashMap<>();
        for (int from = 0; from < distinct.size(); from += BALANCE_KEY_CHUNK_SIZE) {
            List<Long> chunk = distinct.subList(from, Math.min(from + BALANCE_KEY_CHUNK_SIZE, distinct.size()));
            for (InventoryBalanceEntity balance : balanceMapper.selectList(
                    new LambdaQueryWrapper<InventoryBalanceEntity>()
                            .in(InventoryBalanceEntity::getSkuId, chunk)
                            .eq(InventoryBalanceEntity::getWarehouseId, warehouseId))) {
                bySku.put(balance.getSkuId(), balance);
            }
        }
        return bySku;
    }

    /**
     * 批量取某仓「启用」的补货策略（供 {@code analysis} 装配交期 {@code POLICY} 来源与采购参数）。
     *
     * <p>★ 只取 {@code is_enabled = 1} 的行（被停用的策略等于「没有这条策略」，与原装配器 {@code policiesOf}
     * 口径一致）；同一 {@code sku_id} 多行时取首行（{@code orderByAsc(id)}），与原 {@code putIfAbsent} 一致。
     * 团队隔离由租户线兜底。
     *
     * @param warehouseId 仓库（{@code null} ⇒ 空 Map）
     * @param skuIds      SKU 集合（空 ⇒ 空 Map）
     * @return {@code sku_id -> 策略实体}；无启用策略的 sku <b>不在</b> map 中
     */
    @Transactional(readOnly = true)
    public Map<Long, ReplenishmentPolicyEntity> enabledPoliciesBySkuIds(Long warehouseId, Collection<Long> skuIds) {
        if (warehouseId == null || skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, ReplenishmentPolicyEntity> bySku = new LinkedHashMap<>();
        for (int from = 0; from < distinct.size(); from += BALANCE_KEY_CHUNK_SIZE) {
            List<Long> chunk = distinct.subList(from, Math.min(from + BALANCE_KEY_CHUNK_SIZE, distinct.size()));
            for (ReplenishmentPolicyEntity policy : policyMapper.selectList(
                    new LambdaQueryWrapper<ReplenishmentPolicyEntity>()
                            .in(ReplenishmentPolicyEntity::getSkuId, chunk)
                            .eq(ReplenishmentPolicyEntity::getWarehouseId, warehouseId)
                            .eq(ReplenishmentPolicyEntity::getIsEnabled, 1)
                            .orderByAsc(ReplenishmentPolicyEntity::getId))) {
                bySku.putIfAbsent(policy.getSkuId(), policy);
            }
        }
        return bySku;
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private AlertView alert(InventoryBalanceEntity b, Map<Long, String> skuCodes, String type,
                            BigDecimal threshold, String message) {
        AlertView v = new AlertView();
        v.setWarehouseId(b.getWarehouseId());
        v.setSkuId(b.getSkuId());
        v.setSkuCode(skuCodes.getOrDefault(b.getSkuId(), null));
        v.setAlertType(type);
        v.setOnHandQty(b.getOnHandQty());
        v.setAvailableQty(b.getAvailableQty());
        v.setThreshold(threshold);
        v.setMessage(message);
        return v;
    }

    /** 取 SKU 编码映射（租户线约束：异团队 SKU 不在返回内）。 */
    private Map<Long, String> skuCodesOf(Set<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return skuQueryService.getCodesByIds(new ArrayList<>(skuIds)).stream()
                .collect(Collectors.toMap(SkuEntity::getId, SkuEntity::getSkuCode,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** 取策略安全库存（LOWSTOCK 阈值）映射，key = {@code skuId:warehouseId}。 */
    private Map<String, BigDecimal> safetyStockByKey(List<InventoryBalanceEntity> balances) {
        if (balances.isEmpty()) {
            return Map.of();
        }
        Set<Long> skuIds = balances.stream().map(InventoryBalanceEntity::getSkuId).collect(Collectors.toSet());
        Set<Long> whIds = balances.stream().map(InventoryBalanceEntity::getWarehouseId).collect(Collectors.toSet());
        LambdaQueryWrapper<ReplenishmentPolicyEntity> w = new LambdaQueryWrapper<>();
        w.in(ReplenishmentPolicyEntity::getSkuId, skuIds)
                .in(ReplenishmentPolicyEntity::getWarehouseId, whIds);
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (ReplenishmentPolicyEntity p : policyMapper.selectList(w)) {
            if (p.getSafetyStockQty() != null) {
                map.put(p.getSkuId() + ":" + p.getWarehouseId(), p.getSafetyStockQty());
            }
        }
        return map;
    }

    /** 近窗口内有 SALE_OUTBOUND 的 (warehouse_id:sku_id) 集合（租户线约束）。 */
    private Set<String> recentSalesKeys(LocalDateTime cutoff) {
        LambdaQueryWrapper<InventoryTransactionEntity> w = new LambdaQueryWrapper<>();
        w.eq(InventoryTransactionEntity::getTxnType, "SALE_OUTBOUND")
                .ge(InventoryTransactionEntity::getOccurredAt, cutoff)
                .select(InventoryTransactionEntity::getWarehouseId, InventoryTransactionEntity::getSkuId);
        return transactionMapper.selectList(w).stream()
                .map(t -> t.getSkuId() + ":" + t.getWarehouseId())
                .collect(Collectors.toSet());
    }

    /** 解析日期筛选参数（支持 {@code yyyy-MM-dd} 与 ISO 本地时间）。{@code asStart}=true 时取当日 00:00。 */
    private LocalDateTime parseDateTime(String value, boolean asStart) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String v = value.trim();
            if (v.length() == 10) {
                java.time.LocalDate d = java.time.LocalDate.parse(v);
                return asStart ? d.atStartOfDay() : d.atTime(23, 59, 59);
            }
            LocalDateTime ldt = LocalDateTime.parse(v, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return asStart ? ldt : ldt.withHour(23).withMinute(59).withSecond(59).withNano(999_999_999);
        } catch (DateTimeParseException ex) {
            throw BizException.withDetails(ErrorCodes.COM_0007, "分页或排序参数不合法",
                    List.of(new ApiErrorDetail("date", "日期格式非法", value)));
        }
    }
}
