package com.shoppilot.analysis.infrastructure.sampling;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shoppilot.analysis.domain.AnalysisScope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunItem;
import com.shoppilot.analysis.infrastructure.ai.dto.AiSalesPoint;
import com.shoppilot.catalog.infrastructure.persistence.entity.ProductEntity;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.catalog.infrastructure.persistence.entity.SupplierEntity;
import com.shoppilot.catalog.infrastructure.persistence.entity.WarehouseEntity;
import com.shoppilot.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.SkuMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.SupplierMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.WarehouseMapper;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import com.shoppilot.inventory.infrastructure.persistence.entity.ReplenishmentPolicyEntity;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryBalanceMapper;
import com.shoppilot.inventory.infrastructure.persistence.mapper.ReplenishmentPolicyMapper;
import com.shoppilot.sales.application.SalesQueryService;
import com.shoppilot.sales.application.dto.SalesSeriesPoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 逐 SKU 计算输入装配器（M4-T12 前置；解除 {@code R120} / {@code R123}）。
 *
 * <p><b>职责</b>：把「范围」解析成一组 {@link AiRunItem}（内部契约 {@code items[]}），
 * 供 {@code AnalysisAppService.toAiRequest}（发起）与 {@code createRun} 内的数据质量预检
 * （{@code AiValidateRequest.of}，{@code M4-B20.4} {@code R131}）<b>共用同一次装配</b>。
 * <b>纯只读</b>：不写库、不写审计、不调外部服务。
 *
 * <h2>★ 定位升级：analysis 对 catalog / inventory 的只读查询门面（§5.38 裁定 #12）</h2>
 * <p>M4-B20.2 起本类除「装配」外，还承载<b>结果二次校验所需的主数据回读</b>（{@code R132}）：
 * {@link #loadSkuRefs} / {@link #warehouseTeamId} / {@link #loadInTransitQty}。
 * 之所以仍走本类而不是 application 直连 Mapper：{@code §5.36} 裁定 #1
 * <b>禁止 application 直连 Mapper</b>，而这些查询要跨 catalog / inventory 两个模块。
 * 这三个方法全部<b>带租户线</b>（{@code BaseMapper} + LambdaQuery），
 * 因此<b>不需要</b>新增任何 {@code @InterceptorIgnore} 受控例外。
 *
 * <h2>★ 装配位置与依赖（§5.36 裁定 #1/#2）</h2>
 * <p>放在 {@code infrastructure} 层是因为它要直连各模块的 <b>只读</b> Mapper；
 * {@code application} 层只调本类，<b>禁止</b>在 application 直连 Mapper。
 * 跨模块一律「调服务 / 调 Mapper 读自己的表」，<b>禁止跨模块 JOIN SQL</b>，
 * 也<b>不改</b> catalog / inventory / sales 的任何文件。
 *
 * <h2>★ 四条必须留在类注释里的口径</h2>
 * <ol>
 *   <li><b>策略 → params 的覆盖 M4 不做</b>（§5.36 裁定 #11）：{@code replenishment_policies}
 *       里的 {@code window_days/min_days/coverage_days/ss_ratio/cv_high} <b>不下发</b>，
 *       请求 {@code params} 优先；{@code safety_ratio/slow_moving_days/overstock_days} 同样不下发。
 *       这块留给 M5/M6。</li>
 *   <li><b>幂等指纹</b>（§5.36 裁定 #13）：装配结果会进入 Python 的 {@code request_hash}
 *       （Java 侧幂等键是请求体指纹的类比），因此<b>库存/销量变化会让同一 {@code Idempotency-Key}
 *       的重投被判异体 {@code COM-0009}</b>。这是既有语义（请求体即指纹），本批<b>不改</b>。</li>
 *   <li><b>{@code effective_in_transit_qty} 是临时口径（R124）</b>：本批取
 *       {@code inventory_balances.in_transit_qty}（余额列口径，<b>没有到货窗口过滤</b>），
 *       会高估 {@code IP}、低估补货量。{@code DESIGN_BASIS §10.4} 的
 *       {@code Σ(poi.ordered_qty − poi.received_qty)}（按 PO 状态 + 到货窗口过滤）需要
 *       {@code purchase_orders/purchase_order_items}，而采购模块 <b>M6 才建</b>
 *       （{@code InventoryInTransitQueryServiceImpl} 现为空占位）。
 *       <b>M6 落地后必须换回 §10.4 口径并重算</b>，本类会为每次装配打一条 WARN。</li>
 *   <li><b>默认仓只读 {@code warehouses}（R125）</b>：{@code is_default=1 AND status='ACTIVE'}，
 *       <b>不读 {@code teams.default_warehouse_id}</b>——analysis 的模块白名单不含 identity
 *       （{@code MODULE_BOUNDARIES §1.1}），且两套机制可能互相不一致；命中多于一行按
 *       <b>数据异常</b>报错（§5.36 Q1 修订），不静默取第一行。</li>
 * </ol>
 *
 * <h2>性能</h2>
 * <p>余额/策略/供应商按 {@value #QUERY_CHUNK_SIZE} 个 SKU 一批查询；
 * 销量序列因 {@code SalesQueryService} 只提供单 SKU 的 {@code getSeries}（{@code §2.6} 约定），
 * 仍是「每 SKU 一次」——上限由 {@code MAX_ANALYSIS_SKUS=2000} 兜底，本批<b>不改 sales</b>。
 */
@Component
public class AnalysisItemAssembler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisItemAssembler.class);

    /** 主数据「启用」状态值（{@code skus/warehouses.status}；catalog 内为私有常量，此处按字面取值）。 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    /** 交期兜底值（{@code DESIGN_BASIS §10.6} 的 {@code DEFAULT_LEAD_TIME_DAYS}）。 */
    private static final int DEFAULT_LEAD_TIME_DAYS = 7;

    /** 批量查询分片大小（§5.36 裁定 #12：≤500/批，沿用 inventory/sales 的 chunk 惯例）。 */
    public static final int QUERY_CHUNK_SIZE = 500;

    /** 数量出站精度（{@code AI_SERVICE_CONTRACT §4.1}：3 位小数 + 字符串）。 */
    private static final int QUANTITY_SCALE = 3;

    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final SupplierMapper supplierMapper;
    private final WarehouseMapper warehouseMapper;
    private final InventoryBalanceMapper inventoryBalanceMapper;
    private final ReplenishmentPolicyMapper replenishmentPolicyMapper;
    private final SalesQueryService salesQueryService;

    public AnalysisItemAssembler(SkuMapper skuMapper,
                                 ProductMapper productMapper,
                                 SupplierMapper supplierMapper,
                                 WarehouseMapper warehouseMapper,
                                 InventoryBalanceMapper inventoryBalanceMapper,
                                 ReplenishmentPolicyMapper replenishmentPolicyMapper,
                                 SalesQueryService salesQueryService) {
        this.skuMapper = skuMapper;
        this.productMapper = productMapper;
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.inventoryBalanceMapper = inventoryBalanceMapper;
        this.replenishmentPolicyMapper = replenishmentPolicyMapper;
        this.salesQueryService = salesQueryService;
    }

    // =====================================================================
    // 默认仓解析（§5.36 Q1 修订 / R125）
    // =====================================================================

    /**
     * 解析本次分析使用的仓库：请求已指定则原样使用；未指定则解析团队默认仓。
     *
     * <p>请求显式指定的仓库<b>不在本批校验归属</b>（跨团队/状态校验属 B20.2 的结果落库二次校验，
     * 见 {@code AI_SERVICE_CONTRACT §7} 清单第 3 条）。
     *
     * @throws BizException {@code COM-0001}（未配置默认仓 / 默认仓命中多行＝数据异常）
     */
    public Long resolveWarehouseId(Long teamId, Long requestedWarehouseId) {
        if (requestedWarehouseId != null) {
            // ★ R128（§5.38 第四节，2026-10-19 顺手补）：显式传入的仓库若**不属于本团队**
            //   ⇒ COM-0001，而不是「装配全 0 → Python 判 AI-2002」。后者对用户是误导：
            //   他以为自己选了一个仓，实际上什么都没算。
            //   只针对**显式传入**；默认仓解析路径本来就带 team_id 条件，不受影响。
            Long ownerTeamId = warehouseMapper.selectTeamIdById(requestedWarehouseId);
            if (ownerTeamId == null || !Objects.equals(ownerTeamId, teamId)) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(new ApiErrorDetail("warehouse_id", "仓库不属于当前团队",
                                requestedWarehouseId)));
            }
            return requestedWarehouseId;
        }
        List<WarehouseEntity> defaults = warehouseMapper.selectList(
                Wrappers.<WarehouseEntity>lambdaQuery()
                        .eq(WarehouseEntity::getTeamId, teamId)
                        .eq(WarehouseEntity::getIsDefault, Boolean.TRUE)
                        .eq(WarehouseEntity::getStatus, STATUS_ACTIVE)
                        .orderByAsc(WarehouseEntity::getId)
                        .last("LIMIT 2"));
        if (defaults.size() > 1) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("warehouse_id",
                            "默认仓数据异常：同一团队命中多个 is_default=1 且 ACTIVE 的仓库",
                            defaults.stream().map(WarehouseEntity::getId).toList())));
        }
        if (defaults.isEmpty()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("warehouse_id", "未配置默认仓", null)));
        }
        return defaults.get(0).getId();
    }

    // =====================================================================
    // 装配
    // =====================================================================

    /**
     * 按范围装配逐 SKU 计算输入。
     *
     * @param teamId      团队（来自 JWT 上下文，铁律⑤；租户线也会自动追加该条件）
     * @param warehouseId 已解析的仓库（{@link #resolveWarehouseId}）
     * @param scope       分析范围
     * @param dateFrom    数据窗口起
     * @param dateTo      数据窗口止
     * @return 按 SKU id 升序的出站 items（可能为空列表——范围命中 0 个 SKU 时交由 Python 判 AI-2002）
     * @throws BizException {@code COM-0001}（跨团队 / 非 ACTIVE SKU）/ {@code ANL-7007}（SKU 超 2000）
     */
    public List<AiRunItem> assemble(Long teamId,
                                    Long warehouseId,
                                    AnalysisScope scope,
                                    LocalDate dateFrom,
                                    LocalDate dateTo) {
        List<SkuEntity> skus = selectSkus(teamId, scope);
        assertSkuLimit(skus.size());

        List<Long> skuIds = skus.stream().map(SkuEntity::getId).toList();
        Map<Long, InventoryBalanceEntity> balances = balancesOf(warehouseId, skuIds);
        Map<Long, ReplenishmentPolicyEntity> policies = policiesOf(warehouseId, skuIds);
        Map<Long, SupplierEntity> suppliers = suppliersOf(skus);

        log.warn("effective_in_transit_qty 使用临时口径（inventory_balances.in_transit_qty，"
                + "无到货窗口过滤，会高估 IP）：R124，M6 落地后换回 DESIGN_BASIS §10.4 口径并重算。"
                + "teamId={} warehouseId={} skuCount={}",
                teamId, warehouseId, skus.size());

        List<AiRunItem> items = new ArrayList<>(skus.size());
        for (SkuEntity sku : skus) {
            // ★ 供应商 map 的键是 supplier_id（不是 sku_id）
            items.add(toItem(sku, warehouseId, dateFrom, dateTo,
                    balances.get(sku.getId()), policies.get(sku.getId()),
                    sku.getSupplierId() == null ? null : suppliers.get(sku.getSupplierId())));
        }
        return List.copyOf(items);
    }

    // =====================================================================
    // 结果二次校验所需的主数据回读（M4-T12 / §5.38 裁定 #12 / R132）
    // =====================================================================

    /**
     * 回读 SKU 的团队 / 状态 / 商品 / 编码（{@code AI_SERVICE_CONTRACT §7} 第 2 项 + {@code R132} 的
     * {@code product_id} 来源；{@code M4-T13} 快照查询的 {@code sku_code} 回填来源）。
     *
     * <p><b>带租户线</b>：查不到 = 不存在<b>或</b>属他团队，两者在业务上同为「拒绝」，
     * 不做区分（避免向调用方泄露他团队 SKU 是否存在）。
     *
     * @return {@code sku_id -> SkuRef}；查不到的 sku_id <b>不在 map 中</b>（调用方按缺失判定）
     */
    public Map<Long, SkuRef> loadSkuRefs(Collection<Long> skuIds) {
        Map<Long, SkuRef> byId = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(skuIds)) {
            for (SkuEntity sku : skuMapper.selectList(Wrappers.<SkuEntity>lambdaQuery()
                    .in(SkuEntity::getId, chunk)
                    .select(SkuEntity::getId, SkuEntity::getTeamId, SkuEntity::getStatus,
                            SkuEntity::getProductId, SkuEntity::getSkuCode))) {
                byId.put(sku.getId(), new SkuRef(sku.getTeamId(), sku.getStatus(),
                        sku.getProductId(), sku.getSkuCode()));
            }
        }
        return byId;
    }

    /**
     * 跨团队探测：给定仓库取其 {@code team_id}（{@code §7} 第 3 项「仓库属于该团队」）。
     *
     * <p>复用 catalog 已登记的受控例外 {@code WarehouseMapper#selectTeamIdById}
     * （仅取 {@code team_id} 单列，{@code API_CONTRACT §5.6}），<b>analysis 不新增例外</b>。
     *
     * @return 该仓库的团队；仓库不存在返回 {@code null}
     */
    public Long warehouseTeamId(Long warehouseId) {
        return warehouseId == null ? null : warehouseMapper.selectTeamIdById(warehouseId);
    }

    /**
     * 回读在途库存余额（{@code R132}：Python 的 {@code ResultItem} <b>不回显</b>
     * {@code in_transit_qty}，而该列 {@code NOT NULL}）。
     *
     * <p>★ 与出站的 {@code effective_in_transit_qty} <b>口径不同</b>：本方法给的是
     * 「快照当时的余额列原值」，出站给的是「本次分析使用的有效在途」（R124 临时口径下两者相等，
     * M6 换回 {@code §10.4} 后会产生差异）。缺行不报错，由调用方兜 {@code 0}。
     *
     * @return {@code sku_id -> in_transit_qty}；缺余额行的 sku <b>不在 map 中</b>
     */
    public Map<Long, BigDecimal> loadInTransitQty(Collection<Long> skuIds, Long warehouseId) {
        Map<Long, BigDecimal> bySku = new LinkedHashMap<>();
        if (warehouseId == null) {
            return bySku;
        }
        for (List<Long> chunk : chunks(skuIds)) {
            for (InventoryBalanceEntity balance : inventoryBalanceMapper.selectList(
                    Wrappers.<InventoryBalanceEntity>lambdaQuery()
                            .in(InventoryBalanceEntity::getSkuId, chunk)
                            .eq(InventoryBalanceEntity::getWarehouseId, warehouseId)
                            .select(InventoryBalanceEntity::getSkuId, InventoryBalanceEntity::getInTransitQty))) {
                bySku.put(balance.getSkuId(), balance.getInTransitQty());
            }
        }
        return bySku;
    }

    /**
     * SKU 的最小主数据投影（团队 / 状态 / 所属商品 / 业务编码）。
     *
     * @param teamId    所属团队（租户线已保证等于当前团队，保留字段便于日志与断言）
     * @param status    主数据状态（{@code ACTIVE} / 其它）
     * @param productId 所属商品（落库 {@code analysis_items.product_id} 用；R132）
     * @param skuCode   业务编码（{@code M4-T13} 快照查询回填 {@code sku_code} 用）
     */
    public record SkuRef(Long teamId, String status, Long productId, String skuCode) {
    }

    // =====================================================================
    // SKU 选取（§5.36 裁定 #3）
    // =====================================================================

    private List<SkuEntity> selectSkus(Long teamId, AnalysisScope scope) {
        return switch (scope.scopeType()) {
            case ALL_ACTIVE -> skuMapper.selectList(Wrappers.<SkuEntity>lambdaQuery()
                    .eq(SkuEntity::getTeamId, teamId)
                    .eq(SkuEntity::getStatus, STATUS_ACTIVE)
                    .orderByAsc(SkuEntity::getId));
            case SELECTED_SKUS -> selectedSkus(scope.skuIds());
            case CATEGORY -> categorySkus(scope.category());
        };
    }

    /**
     * {@code SELECTED_SKUS}：逐个校验「属本团队且 {@code ACTIVE}」，任一不满足即
     * {@code COM-0001}——<b>创建期不静默剔除</b>（剔除属 M4-T12 落库前校验）。
     *
     * <p>租户线自动追加 {@code team_id}，因此<b>跨团队的 sku_id 查不到行</b>，
     * 与「不存在」同归一条错误文案，不泄漏他团队是否存在该 SKU。
     */
    private List<SkuEntity> selectedSkus(List<Long> requestedIds) {
        Map<Long, SkuEntity> found = new HashMap<>();
        for (List<Long> chunk : chunks(requestedIds)) {
            for (SkuEntity sku : skuMapper.selectList(Wrappers.<SkuEntity>lambdaQuery()
                    .in(SkuEntity::getId, chunk))) {
                found.put(sku.getId(), sku);
            }
        }
        for (Long skuId : requestedIds) {
            SkuEntity sku = found.get(skuId);
            if (sku == null) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(new ApiErrorDetail("scope.sku_ids",
                                "SKU 不存在或不属于当前团队", skuId)));
            }
            if (!STATUS_ACTIVE.equals(sku.getStatus())) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(new ApiErrorDetail("scope.sku_ids",
                                "SKU 状态不是 ACTIVE（当前：" + sku.getStatus() + "）", skuId)));
            }
        }
        return requestedIds.stream().distinct().map(found::get).toList();
    }

    /**
     * {@code CATEGORY}：{@code products.category} 等值（命中 {@code idx_prod_team_category}），
     * 再取其下 {@code ACTIVE} 的 SKU。
     *
     * <p>★ <b>不额外过滤 {@code products.status}</b>（§5.36 裁定 #3 修订）：
     * 范围口径以 {@code skus.status='ACTIVE'} 为准。
     */
    private List<SkuEntity> categorySkus(String category) {
        List<ProductEntity> products = productMapper.selectList(
                Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getCategory, category)
                        .select(ProductEntity::getId));
        if (products.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = products.stream().map(ProductEntity::getId).toList();
        List<SkuEntity> skus = new ArrayList<>();
        for (List<Long> chunk : chunks(productIds)) {
            skus.addAll(skuMapper.selectList(Wrappers.<SkuEntity>lambdaQuery()
                    .in(SkuEntity::getProductId, chunk)
                    .eq(SkuEntity::getStatus, STATUS_ACTIVE)
                    .orderByAsc(SkuEntity::getId)));
        }
        return skus;
    }

    private static void assertSkuLimit(int size) {
        if (size > AnalysisScope.MAX_SKUS) {
            throw BizException.withDetails(ErrorCodes.ANL_7007, "分析 SKU 数量超限",
                    List.of(new ApiErrorDetail("scope",
                            "分析 SKU 数量 " + size + " 超过上限 " + AnalysisScope.MAX_SKUS, null)));
        }
    }

    // =====================================================================
    // 批量只读查询
    // =====================================================================

    /** 余额快照（缺行不是错误，由 {@link #toItem} 兜成 {@code 0.000}）。 */
    private Map<Long, InventoryBalanceEntity> balancesOf(Long warehouseId, List<Long> skuIds) {
        Map<Long, InventoryBalanceEntity> bySku = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(skuIds)) {
            for (InventoryBalanceEntity balance : inventoryBalanceMapper.selectList(
                    Wrappers.<InventoryBalanceEntity>lambdaQuery()
                            .in(InventoryBalanceEntity::getSkuId, chunk)
                            .eq(InventoryBalanceEntity::getWarehouseId, warehouseId))) {
                bySku.put(balance.getSkuId(), balance);
            }
        }
        return bySku;
    }

    /**
     * 补货策略（SKU × 仓）。
     *
     * <p>★ 只取 {@code is_enabled = 1} 的行：被禁用的策略等于「没有这条策略」，
     * 否则会用一个运营已明确关掉的配置去算 {@code L} / {@code SS} / MOQ。
     * （§5.36 未显式写该列，按「禁用策略不生效」处理，已在交付汇报申报。）
     */
    private Map<Long, ReplenishmentPolicyEntity> policiesOf(Long warehouseId, List<Long> skuIds) {
        Map<Long, ReplenishmentPolicyEntity> bySku = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(skuIds)) {
            for (ReplenishmentPolicyEntity policy : replenishmentPolicyMapper.selectList(
                    Wrappers.<ReplenishmentPolicyEntity>lambdaQuery()
                            .in(ReplenishmentPolicyEntity::getSkuId, chunk)
                            .eq(ReplenishmentPolicyEntity::getWarehouseId, warehouseId)
                            .eq(ReplenishmentPolicyEntity::getIsEnabled, 1)
                            .orderByAsc(ReplenishmentPolicyEntity::getId))) {
                bySku.putIfAbsent(policy.getSkuId(), policy);
            }
        }
        return bySku;
    }

    private Map<Long, SupplierEntity> suppliersOf(List<SkuEntity> skus) {
        List<Long> supplierIds = skus.stream()
                .map(SkuEntity::getSupplierId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, SupplierEntity> byId = new HashMap<>();
        for (List<Long> chunk : chunks(supplierIds)) {
            for (SupplierEntity supplier : supplierMapper.selectList(
                    Wrappers.<SupplierEntity>lambdaQuery().in(SupplierEntity::getId, chunk))) {
                byId.put(supplier.getId(), supplier);
            }
        }
        return byId;
    }

    // =====================================================================
    // 单 item 组装
    // =====================================================================

    private AiRunItem toItem(SkuEntity sku,
                             Long warehouseId,
                             LocalDate dateFrom,
                             LocalDate dateTo,
                             InventoryBalanceEntity balance,
                             ReplenishmentPolicyEntity policy,
                             SupplierEntity supplier) {
        // ★ 缺余额行**不是错误**（裁定 #6）：三项一律兜 0.000，而不是省略字段——
        //   Python 侧 Item 这三项是必填，省略会直接 400 AI-1001。
        BigDecimal onHand = balance == null ? BigDecimal.ZERO : balance.getOnHandQty();
        BigDecimal committed = balance == null ? BigDecimal.ZERO : balance.getCommittedQty();
        BigDecimal inTransit = balance == null ? BigDecimal.ZERO : balance.getInTransitQty();

        LeadTime leadTime = resolveLeadTime(sku, policy, supplier);

        return new AiRunItem(
                sku.getId(),
                decimal3(onHand),
                decimal3(committed),
                // ★ R124 临时口径（见类注释 ③）：余额列 in_transit_qty，无到货窗口过滤
                decimal3(inTransit),
                leadTime.days(),
                leadTime.source(),
                decimal3(policy == null ? null : policy.getSafetyStockQty()),
                decimal3(firstNonNull(
                        policy == null ? null : policy.getMoq(), sku.getMoq())),
                decimal3(firstNonNull(
                        policy == null ? null : policy.getOrderMultiple(), sku.getOrderMultiple())),
                decimal3(firstNonNull(
                        policy == null ? null : policy.getQtyStep(), sku.getQtyStep())),
                sku.getListedDate(),
                salesSeries(sku.getId(), warehouseId, dateFrom, dateTo));
    }

    /**
     * 交期来源优先级（§5.36 裁定 #7）：
     * {@code policies(POLICY) → skus(SKU) → suppliers(SUPPLIER) → 7(DEFAULT)}，取第一个非空。
     */
    private LeadTime resolveLeadTime(SkuEntity sku,
                                     ReplenishmentPolicyEntity policy,
                                     SupplierEntity supplier) {
        if (policy != null && policy.getLeadTimeDays() != null) {
            return new LeadTime(policy.getLeadTimeDays(), "POLICY");
        }
        if (sku.getDefaultLeadTimeDays() != null) {
            return new LeadTime(sku.getDefaultLeadTimeDays(), "SKU");
        }
        if (supplier != null && supplier.getLeadTimeDays() != null) {
            return new LeadTime(supplier.getLeadTimeDays(), "SUPPLIER");
        }
        return new LeadTime(DEFAULT_LEAD_TIME_DAYS, "DEFAULT");
    }

    /** 销量序列：<b>只含有记录的日子</b>（{@code ANALYSIS_CONTRACT §1.2}），空数组合法。 */
    private List<AiSalesPoint> salesSeries(Long skuId,
                                           Long warehouseId,
                                           LocalDate dateFrom,
                                           LocalDate dateTo) {
        List<SalesSeriesPoint> series = salesQueryService.getSeries(skuId, warehouseId, dateFrom, dateTo);
        if (series == null || series.isEmpty()) {
            return List.of();
        }
        return series.stream()
                .map(point -> new AiSalesPoint(point.getSaleDate(), decimal3(point.getQty())))
                .toList();
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 数量定点字符串（3 位小数、{@code toPlainString}、禁 double；null 透传为「不下发」）。 */
    private static String decimal3(BigDecimal value) {
        return value == null ? null : value.setScale(QUANTITY_SCALE, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first != null ? first : second;
    }

    private static List<List<Long>> chunks(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> all = new ArrayList<>(ids);
        List<List<Long>> out = new ArrayList<>((all.size() + QUERY_CHUNK_SIZE - 1) / QUERY_CHUNK_SIZE);
        for (int from = 0; from < all.size(); from += QUERY_CHUNK_SIZE) {
            out.add(List.copyOf(all.subList(from, Math.min(from + QUERY_CHUNK_SIZE, all.size()))));
        }
        return out;
    }

    /** 交期取值 + 来源（{@code LeadTimeSource} 四值）。 */
    private record LeadTime(Integer days, String source) {
    }
}