package com.shoppilot.analysis.infrastructure.sampling;

import com.shoppilot.analysis.domain.AnalysisParams;
import com.shoppilot.analysis.domain.AnalysisScope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunItem;
import com.shoppilot.analysis.infrastructure.ai.dto.AiSalesPoint;
import com.shoppilot.catalog.application.ProductQueryService;
import com.shoppilot.catalog.application.SkuQueryService;
import com.shoppilot.catalog.application.SupplierQueryService;
import com.shoppilot.catalog.application.WarehouseQueryService;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.catalog.infrastructure.persistence.entity.SupplierEntity;
import com.shoppilot.catalog.infrastructure.persistence.entity.WarehouseEntity;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.inventory.application.InventoryInTransitQueryService;
import com.shoppilot.inventory.application.InventoryQueryService;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import com.shoppilot.inventory.infrastructure.persistence.entity.ReplenishmentPolicyEntity;
import com.shoppilot.sales.application.SalesQueryService;
import com.shoppilot.sales.application.dto.SalesSeriesPoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 逐 SKU 计算输入装配器（M4-T12 前置；解除 {@code R120} / {@code R123}）。
 *
 * <p><b>职责</b>：把「范围」解析成一组 {@link AiRunItem}（内部契约 {@code items[]}），
 * 供 {@code AnalysisAppService.toAiRequest}（发起）与 {@code createRun} 内的数据质量预检
 * （{@code AiValidateRequest.of}，{@code M4-B20.4} {@code R131}）<b>共用同一次装配</b>。
 * <b>纯只读</b>：不写库、不写审计、不调外部服务。
 *
 * <h2>★ 定位升级：analysis 对 catalog / inventory 的只读查询门面（§5.38 裁定 #12；M8-T06 闭环 R126）</h2>
 * <p>M4-B20.2 起本类除「装配」外，还承载<b>结果二次校验所需的主数据回读</b>（{@code R132}）：
 * {@link #loadSkuRefs} / {@link #warehouseTeamId} / {@link #loadInTransitQty}。
 * 这些查询跨 catalog / inventory 两个模块，故<b>经各自模块的只读查询服务</b>完成
 * （catalog 的 {@code SkuQueryService}/{@code ProductQueryService}/{@code SupplierQueryService}/
 * {@code WarehouseQueryService}、inventory 的 {@code InventoryQueryService}）——
 * 与 {@code §5.36} 裁定 #1「<b>禁止 application 直连 Mapper</b>」一致，
 * 也满足 {@code R126}「跨模块只读必须经门面、不得直连对方 Mapper」的边界纪律。
 * 服务内部查询全部<b>带租户线</b>，<b>不需要</b>本类新增任何 {@code @InterceptorIgnore} 受控例外。
 *
 * <h2>★ 装配位置与依赖（§5.36 裁定 #1/#2；M8-T06 闭环 R126）</h2>
 * <p>放在 {@code infrastructure} 层是因为它要编排各模块的<b>只读查询服务</b>（catalog 4 个、
 * inventory 的 {@code InventoryQueryService}/{@code InventoryInTransitQueryService}、
 * sales 的 {@code SalesQueryService}）；<b>不再</b>直连任何跨模块 {@code *Mapper}
 * （{@code R126} 已闭环，原 6 个对方 Mapper 直连全部移除）。
 * {@code application} 层只调本类，<b>禁止</b>在 application 直连 Mapper。
 * 跨模块一律「调服务 / 服务内读自己的表」，<b>禁止跨模块 JOIN SQL</b>，
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
 *   <li><b>{@code effective_in_transit_qty} 走 {@code DESIGN_BASIS §10.4} 口径（{@code M6-T10}，
 *       {@code R124} 已闭环）</b>：由 {@link InventoryInTransitQueryService#batchGetEffectiveInTransit}
 *       提供——按 SKU 的 {@code today + L + R} 窗口过滤（{@code L} 走本类四级回退，
 *       {@code R = params.coverageDays}），<b>不再</b>读 {@code inventory_balances.in_transit_qty}
 *       （该列已退役，{@code §5.78} 方案 B′）。窗口规则本身在 {@code inventory} 实现，本类只算边界日。
 *       窗口末日<b>逐 SKU</b>不同（{@code L} 各异），因此传 {@code Map<skuId, windowEndDate>}。</li>
 *   <li><b>默认仓只读 {@code warehouses}（R125）</b>：{@code is_default=1 AND status='ACTIVE'}，
 *       <b>不读 {@code teams.default_warehouse_id}</b>——analysis 的模块白名单不含 identity
 *       （{@code MODULE_BOUNDARIES §1.1}），且两套机制可能互相不一致；命中多于一行按
 *       <b>数据异常</b>报错（§5.36 Q1 修订），不静默取第一行。</li>
 * </ol>
 *
 * <h2>性能</h2>
 * <p>余额 / 策略 / SKU / 供应商 / 商品的批量查询<b>由各自模块的只读服务内部分片</b>
 * （catalog {@code CODE_CHUNK_SIZE=1000}、inventory {@code BALANCE_KEY_CHUNK_SIZE=1000}）；
 * 本类<b>不再</b>本地做 {@code IN} 分片——{@code R126} 门面化后分片职责随之下沉到服务侧。
 * 销量序列因 {@code SalesQueryService} 只提供单 SKU 的 {@code getSeries}（{@code §2.6} 约定），
 * 仍是「每 SKU 一次」——上限由 {@code MAX_ANALYSIS_SKUS=2000} 兜底，本批<b>不改 sales</b>。
 */
@Component
public class AnalysisItemAssembler {

    /** 主数据「启用」状态值（{@code skus/warehouses.status}；catalog 内为私有常量，此处按字面取值）。 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    /** 交期兜底值（{@code DESIGN_BASIS §10.6} 的 {@code DEFAULT_LEAD_TIME_DAYS}；{@code params} 缺失时才用）。 */
    private static final int DEFAULT_LEAD_TIME_DAYS = AnalysisParams.DEFAULT_LEAD_TIME_DAYS;

    /** 数量出站精度（{@code AI_SERVICE_CONTRACT §4.1}：3 位小数 + 字符串）。 */
    private static final int QUANTITY_SCALE = 3;

    private final SkuQueryService skuQueryService;
    private final ProductQueryService productQueryService;
    private final SupplierQueryService supplierQueryService;
    private final WarehouseQueryService warehouseQueryService;
    private final InventoryQueryService inventoryQueryService;
    private final SalesQueryService salesQueryService;
    private final InventoryInTransitQueryService inTransitQueryService;

    public AnalysisItemAssembler(SkuQueryService skuQueryService,
                                 ProductQueryService productQueryService,
                                 SupplierQueryService supplierQueryService,
                                 WarehouseQueryService warehouseQueryService,
                                 InventoryQueryService inventoryQueryService,
                                 SalesQueryService salesQueryService,
                                 InventoryInTransitQueryService inTransitQueryService) {
        this.skuQueryService = skuQueryService;
        this.productQueryService = productQueryService;
        this.supplierQueryService = supplierQueryService;
        this.warehouseQueryService = warehouseQueryService;
        this.inventoryQueryService = inventoryQueryService;
        this.salesQueryService = salesQueryService;
        this.inTransitQueryService = inTransitQueryService;
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
            Long ownerTeamId = warehouseQueryService.getTeamIdById(requestedWarehouseId);
            if (ownerTeamId == null || !Objects.equals(ownerTeamId, teamId)) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(new ApiErrorDetail("warehouse_id", "仓库不属于当前团队",
                                requestedWarehouseId)));
            }
            return requestedWarehouseId;
        }
        // ★ R126：默认仓解析经 catalog 门面（租户线保证只返回本团队），
        //   0 / >1 的判定仍在调用方（本类），不在门面里静默取第一行。
        List<WarehouseEntity> defaults = warehouseQueryService.listDefaultWarehouses();
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
     * @param params      已解析的运行参数（{@code lead_time_default} 兜底交期、{@code coverage_days} 即 {@code R}）；
     *                    {@code null} 时按 {@link AnalysisParams#resolve} 的默认值兜底
     * @return 按 SKU id 升序的出站 items（可能为空列表——范围命中 0 个 SKU 时交由 Python 判 AI-2002）
     * @throws BizException {@code COM-0001}（跨团队 / 非 ACTIVE SKU）/ {@code ANL-7007}（SKU 超 2000）
     */
    public List<AiRunItem> assemble(Long teamId,
                                    Long warehouseId,
                                    AnalysisScope scope,
                                    LocalDate dateFrom,
                                    LocalDate dateTo,
                                    AnalysisParams params) {
        List<SkuEntity> skus = selectSkus(teamId, scope);
        assertSkuLimit(skus.size());

        List<Long> skuIds = skus.stream().map(SkuEntity::getId).toList();
        Map<Long, InventoryBalanceEntity> balances = balancesOf(warehouseId, skuIds);
        Map<Long, ReplenishmentPolicyEntity> policies = policiesOf(warehouseId, skuIds);
        Map<Long, SupplierEntity> suppliers = suppliersOf(skus);

        int leadTimeFallback = params == null || params.leadTimeDefault() == null
                ? DEFAULT_LEAD_TIME_DAYS : params.leadTimeDefault();
        int coverageDays = params == null || params.coverageDays() == null
                ? AnalysisParams.DEFAULT_COVERAGE_DAYS : params.coverageDays();
        // ★ today 用 UTC（与 DATETIME(3) UTC 存储口径一致）；窗口末日逐 SKU 计算（L 各异）
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<Long, LeadTime> leadTimes = new LinkedHashMap<>();
        Map<Long, LocalDate> windowEnds = new LinkedHashMap<>();
        for (SkuEntity sku : skus) {
            LeadTime leadTime = resolveLeadTime(sku, policies.get(sku.getId()),
                    sku.getSupplierId() == null ? null : suppliers.get(sku.getSupplierId()),
                    leadTimeFallback);
            leadTimes.put(sku.getId(), leadTime);
            windowEnds.put(sku.getId(), today.plusDays((long) leadTime.days() + coverageDays));
        }
        // §10.4 有效在途：口径在 inventory 单点实现，这里只给「每个 SKU 的窗口末日」
        Map<Long, BigDecimal> effectiveInTransit = warehouseId == null
                ? Map.of()
                : inTransitQueryService.batchGetEffectiveInTransit(teamId, warehouseId, windowEnds);

        List<AiRunItem> items = new ArrayList<>(skus.size());
        for (SkuEntity sku : skus) {
            // ★ 供应商 map 的键是 supplier_id（不是 sku_id）
            items.add(toItem(sku, warehouseId, dateFrom, dateTo,
                    balances.get(sku.getId()), policies.get(sku.getId()),
                    sku.getSupplierId() == null ? null : suppliers.get(sku.getSupplierId()),
                    leadTimes.get(sku.getId()),
                    effectiveInTransit.getOrDefault(sku.getId(), BigDecimal.ZERO)));
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
        for (SkuEntity sku : skuQueryService.getByIds(skuIds).values()) {
            byId.put(sku.getId(), new SkuRef(sku.getTeamId(), sku.getStatus(),
                    sku.getProductId(), sku.getSkuCode()));
        }
        return byId;
    }

    /**
     * 跨团队探测：给定仓库取其 {@code team_id}（{@code §7} 第 3 项「仓库属于该团队」）。
     *
     * <p>经 {@link WarehouseQueryService#getTeamIdById}（{@code R126} 门面化），其内部仍复用 catalog
     * 已登记的受控租户线例外 {@code WarehouseMapper#selectTeamIdById}
     * （仅取 {@code team_id} 单列，{@code API_CONTRACT §5.6}），<b>analysis 不新增例外、也不直连 Mapper</b>。
     *
     * @return 该仓库的团队；仓库不存在返回 {@code null}
     */
    public Long warehouseTeamId(Long warehouseId) {
        return warehouseQueryService.getTeamIdById(warehouseId);
    }

    /**
     * 回读在途量，用于落库 {@code analysis_items.in_transit_qty} 快照（{@code R132}：Python 的
     * {@code ResultItem} <b>不回显</b> {@code in_transit_qty}，而该列 {@code NOT NULL}）。
     *
     * <p>★ 与出站的 {@code effective_in_transit_qty} <b>口径不同</b>（{@code DESIGN_BASIS §10.4}）：
     * <ul>
     *   <li>本方法 = <b>原始在途</b>（{@code batchGetInTransit}，<b>不</b>应用到货窗口）——
     *       快照列要的是「当时的在途事实」，不是「本次计算的窗口内有效在途」；</li>
     *   <li>出站 = <b>有效在途</b>（{@code batchGetEffectiveInTransit}，已按 {@code today + L + R} 过滤）。</li>
     * </ul>
     * 二者不再相等（{@code M6-T10} 前都读余额列所以恒等，现由 {@code R178} 起分道）。
     * 缺在途不报错，由调用方兜 {@code 0}。
     *
     * @param teamId      当前团队（租户隔离；不接受客户端传入）
     * @param warehouseId 仓库；{@code null} ⇒ 空 map
     * @param skuIds      关心的 SKU
     * @return {@code sku_id -> 原始在途}；无在途的 sku <b>不在 map 中</b>
     */
    public Map<Long, BigDecimal> loadInTransitQty(long teamId, Long warehouseId, Collection<Long> skuIds) {
        if (warehouseId == null || skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        return inTransitQueryService.batchGetInTransit(teamId, warehouseId, skuIds);
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
            case ALL_ACTIVE -> skuQueryService.listActiveByTeam();
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
        Map<Long, SkuEntity> found = skuQueryService.getByIds(requestedIds);
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
        List<Long> productIds = productQueryService.listIdsByCategory(category);
        if (productIds.isEmpty()) {
            return List.of();
        }
        return skuQueryService.listActiveByProductIds(productIds);
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
        return inventoryQueryService.balancesBySkuIds(warehouseId, skuIds);
    }

    /**
     * 补货策略（SKU × 仓）。
     *
     * <p>★ 只取 {@code is_enabled = 1} 的行：被禁用的策略等于「没有这条策略」，
     * 否则会用一个运营已明确关掉的配置去算 {@code L} / {@code SS} / MOQ。
     * （§5.36 未显式写该列，按「禁用策略不生效」处理，已在交付汇报申报。）
     */
    private Map<Long, ReplenishmentPolicyEntity> policiesOf(Long warehouseId, List<Long> skuIds) {
        return inventoryQueryService.enabledPoliciesBySkuIds(warehouseId, skuIds);
    }

    private Map<Long, SupplierEntity> suppliersOf(List<SkuEntity> skus) {
        List<Long> supplierIds = skus.stream()
                .map(SkuEntity::getSupplierId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return supplierQueryService.getByIds(supplierIds);
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
                             SupplierEntity supplier,
                             LeadTime leadTime,
                             BigDecimal effectiveInTransit) {
        // ★ 缺余额行**不是错误**（裁定 #6）：三项一律兜 0.000，而不是省略字段——
        //   Python 侧 Item 这三项是必填，省略会直接 400 AI-1001。
        BigDecimal onHand = balance == null ? BigDecimal.ZERO : balance.getOnHandQty();
        BigDecimal committed = balance == null ? BigDecimal.ZERO : balance.getCommittedQty();
        // ★ 在途**不再**取 balance.getInTransitQty()（物化列已退役，§5.78）：由调用方传入 §10.4 有效在途
        BigDecimal inTransit = effectiveInTransit == null ? BigDecimal.ZERO : effectiveInTransit;

        return new AiRunItem(
                sku.getId(),
                decimal3(onHand),
                decimal3(committed),
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
     * {@code policies(POLICY) → skus(SKU) → suppliers(SUPPLIER) → params.lead_time_default(DEFAULT)}，
     * 取第一个非空。★ 兜底值取请求参数 {@code lead_time_default}（{@code M6-T10} 前误用硬编码 7）。
     */
    private LeadTime resolveLeadTime(SkuEntity sku,
                                     ReplenishmentPolicyEntity policy,
                                     SupplierEntity supplier,
                                     int leadTimeDefault) {
        if (policy != null && policy.getLeadTimeDays() != null) {
            return new LeadTime(policy.getLeadTimeDays(), "POLICY");
        }
        if (sku.getDefaultLeadTimeDays() != null) {
            return new LeadTime(sku.getDefaultLeadTimeDays(), "SKU");
        }
        if (supplier != null && supplier.getLeadTimeDays() != null) {
            return new LeadTime(supplier.getLeadTimeDays(), "SUPPLIER");
        }
        return new LeadTime(leadTimeDefault, "DEFAULT");
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

    /** 交期取值 + 来源（{@code LeadTimeSource} 四值）。 */
    private record LeadTime(Integer days, String source) {
    }
}