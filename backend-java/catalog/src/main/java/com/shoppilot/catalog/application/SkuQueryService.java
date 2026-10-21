package com.shoppilot.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.catalog.application.dto.CatalogPageQuery;
import com.shoppilot.catalog.application.dto.SkuView;
import com.shoppilot.catalog.application.support.CatalogGuards;
import com.shoppilot.catalog.application.support.CatalogSorts;
import com.shoppilot.catalog.infrastructure.persistence.entity.ProductEntity;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.shoppilot.catalog.infrastructure.persistence.mapper.SkuMapper;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.PageResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * SKU 只读查询服务（{@code MODULE_BOUNDARIES §2.3} 对外只读入口之一）。
 */
@Service
public class SkuQueryService {

    private static final Set<String> SORT_WHITELIST =
            Set.of("id", "sku_code", "name", "status", "created_at", "updated_at");

    /** 单次 {@code IN} 查询的编码上限（避免超大 IN 列表把 SQL 语句撑爆）。 */
    private static final int CODE_CHUNK_SIZE = 1000;

    private final SkuMapper skuMapper;
    private final ProductQueryService productQueryService;
    private final ProductMapper productMapper;

    public SkuQueryService(SkuMapper skuMapper, ProductQueryService productQueryService,
                           ProductMapper productMapper) {
        this.skuMapper = skuMapper;
        this.productQueryService = productQueryService;
        this.productMapper = productMapper;
    }

    /**
     * SKU 列表（{@code GET /api/v1/skus}，分页；可按 {@code productId}/{@code status}/{@code keyword} 筛选）。
     *
     * @param productId 所属商品筛选，{@code null} 表示不过滤
     * @param status    状态精确筛选，{@code null}/空 表示不过滤
     * @param keyword   关键字（匹配 {@code sku_code} 或 {@code name} 模糊），{@code null}/空 表示不过滤
     */
    public PageResult<SkuView> list(CatalogPageQuery query, Long productId, String status, String keyword) {
        List<CatalogPageQuery.SortSpec> sort = query.validatedSort(SORT_WHITELIST);
        QueryWrapper<SkuEntity> wrapper = new QueryWrapper<>();
        if (productId != null) {
            wrapper.eq("product_id", productId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like("sku_code", keyword).or().like("name", keyword));
        }
        CatalogSorts.apply(wrapper, sort, "created_at", true);

        Page<SkuEntity> page = skuMapper.selectPage(new Page<>(query.getPage(), query.getPageSize()), wrapper);
        List<SkuView> list = page.getRecords().stream().map(SkuView::of).toList();
        return PageResult.of(page.getTotal(), query.getPage(), query.getPageSize(), list);
    }

    /**
     * 商品下 SKU 列表（{@code GET /api/v1/products/{id}/skus}）。
     *
     * <p>先校验商品存在且同团队（异团队 → 403，不存在 → 404 CAT-3001），再列出其 SKU。
     */
    public PageResult<SkuView> listByProduct(Long productId, CatalogPageQuery query) {
        productQueryService.requireInTeam(productId);
        return list(query, productId, null, null);
    }

    /** SKU 详情（{@code GET /api/v1/skus/{id}}）：异团队 → 403，不存在 → 404 CAT-3001。 */
    public SkuView get(Long id) {
        return SkuView.of(requireInTeam(id));
    }

    /** 取 SKU 实体（{@code MODULE_BOUNDARIES §2.3}）。 */
    public SkuEntity getSku(Long id) {
        return skuMapper.selectById(id);
    }

    /** 取当前团队下某商品的启用 SKU（{@code MODULE_BOUNDARIES §2.3}）。 */
    public List<SkuEntity> listActiveSkus(Long productId) {
        QueryWrapper<SkuEntity> wrapper = new QueryWrapper<SkuEntity>()
                .eq("status", "ACTIVE")
                .orderByAsc("sku_code");
        if (productId != null) {
            wrapper.eq("product_id", productId);
        }
        return skuMapper.selectList(wrapper);
    }

    /** 按 id 批量取 SKU（{@code MODULE_BOUNDARIES §2.3}）。 */
    public List<SkuEntity> getCodesByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return skuMapper.selectBatchIds(ids);
    }

    // =====================================================================
    // 分析装配批量只读（M8-T06 / R126：analysis 经本门面而非直连 SkuMapper）
    // =====================================================================

    /**
     * 本团队全部 {@code ACTIVE} SKU（按 {@code id} 升序；供 {@code analysis} 的 {@code ALL_ACTIVE} 范围）。
     *
     * <p>★ 团队隔离由租户线兜底（与 {@link #listProcurementInfo} 同口径），不接收 {@code teamId} 参数。
     * 排序与 {@code analysis} 原 {@code selectSkus(ALL_ACTIVE)} 的 {@code orderByAsc(id)} 一致，
     * 保证出站 items 可复现。
     *
     * @return 本团队 {@code ACTIVE} SKU（可能为空）
     */
    public List<SkuEntity> listActiveByTeam() {
        return skuMapper.selectList(new QueryWrapper<SkuEntity>()
                .eq("status", "ACTIVE")
                .orderByAsc("id"));
    }

    /**
     * 按 {@code id} 批量取 SKU 实体（供 {@code analysis} 的 {@code SELECTED_SKUS} 范围与
     * {@code loadSkuRefs} 主数据回读）。
     *
     * <p>★ 租户线约束：跨团队 / 不存在的 {@code id} 不出现在结果中（调用方按「缺失即拒绝 / 缺失即无」处理）。
     * 不做 {@code status} 过滤——{@code SELECTED_SKUS} 的 {@code ACTIVE} 校验在 {@code analysis} 侧完成，
     * 以保留「非 ACTIVE ⇒ COM-0001 且写明状态」的语义。
     *
     * @return {@code sku_id -> 实体}；入参可空/空/含 {@code null} ⇒ 空 {@code Map}
     */
    public Map<Long, SkuEntity> getByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, SkuEntity> byId = new LinkedHashMap<>();
        for (SkuEntity sku : skuMapper.selectBatchIds(distinct)) {
            byId.put(sku.getId(), sku);
        }
        return byId;
    }

    /**
     * 指定商品下的 {@code ACTIVE} SKU（按 {@code id} 升序；供 {@code analysis} 的 {@code CATEGORY} 范围：
     * 先取类目下的商品 {@code id}，再经本方法取其下启用 SKU）。
     *
     * <p>★ 不额外过滤 {@code products.status}（{@code analysis} 原口径即以 {@code skus.status='ACTIVE'} 为准）。
     * 团队隔离由租户线兜底。
     *
     * @return 命中 SKU（可能为空）
     */
    public List<SkuEntity> listActiveByProductIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = productIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        List<SkuEntity> out = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += CODE_CHUNK_SIZE) {
            List<Long> chunk = distinct.subList(from, Math.min(from + CODE_CHUNK_SIZE, distinct.size()));
            out.addAll(skuMapper.selectList(new QueryWrapper<SkuEntity>()
                    .in("product_id", chunk)
                    .eq("status", "ACTIVE")
                    .orderByAsc("id")));
        }
        return out;
    }

    /**
     * <b>按业务编码批量解析 {@code sku_code → id}</b>（M3-B10.5 / R78；供 {@code importdata} 落库前解析引用）。
     *
     * <h2>为什么是批量</h2>
     * 导入落库按行解析引用若逐行查库，1 万行就是 1 万次往返（{@code CatalogWriteService
     * #preloadWarehouseIdsByCode} 实测逐行 1.91 ms/次 ⇒ 19.1 s，见 {@code ADR-004 D5}）。
     * 本方法对同一批编码只发<b>一条</b> {@code IN} 查询（超长时按 {@value #CODE_CHUNK_SIZE} 分片，
     * 仍是 O(n/1000) 而非 O(n)）。
     *
     * <h2>口径（{@code IMPORT_WORKFLOW §7.6}）</h2>
     * 只要求「<b>必须存在且同团队</b>」，故<b>不</b>加 {@code status='ACTIVE'} 过滤——否则
     * 「停用 SKU 的历史销售导入」会被误判为引用缺失。团队隔离由租户线兜底（跨团队同编码查不到）。
     *
     * <p><b>不改变错误语义</b>：键存在但不在返回 {@code Map} 中即「不存在或跨团队」，
     * 由调用方按 {@code CAT-3001} 处理（文件内重复编码取最后一个，与唯一键
     * {@code uk_sku_team_code} 一致，不可能并存两行）。
     *
     * @param codes 业务编码集合（{@code null}/空白元素自动跳过；{@code null} 或全空 → 空 {@code Map}）
     * @return 命中的 {@code sku_code → id}；未命中的编码<b>不出现</b>在结果中
     */
    public Map<String, Long> getIdsByCodes(Collection<String> codes) {
        Set<String> distinct = distinctCodes(codes);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>(distinct.size() * 2);
        List<String> all = new ArrayList<>(distinct);
        for (int from = 0; from < all.size(); from += CODE_CHUNK_SIZE) {
            List<String> chunk = all.subList(from, Math.min(from + CODE_CHUNK_SIZE, all.size()));
            List<SkuEntity> rows = skuMapper.selectList(new QueryWrapper<SkuEntity>()
                    .select("id", "sku_code")
                    .in("sku_code", chunk));
            for (SkuEntity row : rows) {
                ids.put(row.getSkuCode(), row.getId());
            }
        }
        return ids;
    }

    /** 去空白 + 去重（保持顺序，仅为稳定日志/分片）。 */
    private static Set<String> distinctCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Set.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String code : codes) {
            if (code != null && !code.isBlank()) {
                distinct.add(code.trim());
            }
        }
        return distinct;
    }

    /** 归属判定（受控探测区分 403/404）。 */
    public SkuEntity requireInTeam(Long id) {
        return CatalogGuards.requireInTeam(
                skuMapper.selectById(id),
                () -> skuMapper.selectTeamIdById(id),
                ErrorCodes.CAT_3001, "SKU 不存在");
    }

    // =====================================================================
    // 采购信息批量只读（M5-T02，跨模块只读入口）
    // =====================================================================

    /**
     * 批量读取生成补货建议所需的采购信息（{@code ANALYSIS_CONTRACT §9.2} 的 Java 推断列）。
     *
     * <p>★ <b>为什么放在 catalog</b>：{@code replenishment} 需要 {@code skus.supplier_id} /
     * {@code skus.purchase_price} / {@code products.default_supplier_id}，但
     * {@code MODULE_BOUNDARIES §8.2 E-2} 禁止它直接注入 {@code SkuMapper}/{@code ProductMapper}
     * （{@code R126} 同型债）。故由 catalog 提供这一个<b>批量只读</b>方法。
     *
     * <p>★ 入参<b>不含 teamId</b>：团队来自 {@code TeamContext}，两次查询都走租户线，
     * 因此跨团队 SKU 不会出现在结果里（调用方按「缺失即无采购信息」处理）。
     *
     * <p>★ 批量语义：一次 {@code IN} 查 SKU、一次 {@code IN} 查商品，共 2 条 SQL；
     * 不做 N+1。调用方按 ≤500 个 SKU 分批（{@code DEVELOPMENT_PLAN M5-T02}）。
     *
     * @param skuIds SKU 主键集合（可空/空 ⇒ 返回空 Map）
     * @return {@code skuId → 采购信息}；查不到的 SKU 不出现在结果中
     */
    public Map<Long, ProcurementInfo> listProcurementInfo(Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<SkuEntity> skus = skuMapper.selectBatchIds(ids);
        if (skus.isEmpty()) {
            return Map.of();
        }
        Set<Long> productIds = skus.stream().map(SkuEntity::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Long> defaultSupplierByProduct = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (ProductEntity product : productMapper.selectBatchIds(productIds)) {
                defaultSupplierByProduct.put(product.getId(), product.getDefaultSupplierId());
            }
        }
        Map<Long, ProcurementInfo> result = new LinkedHashMap<>();
        for (SkuEntity sku : skus) {
            result.put(sku.getId(), new ProcurementInfo(sku.getSupplierId(), sku.getPurchasePrice(),
                    defaultSupplierByProduct.get(sku.getProductId())));
        }
        return result;
    }

    /**
     * SKU 的采购信息快照。
     *
     * @param supplierId              SKU 指定供应商（可空）
     * @param purchasePrice           SKU 参考采购单价（可空）
     * @param productDefaultSupplierId 所属商品的默认供应商（可空；SKU 未指定时回退用它）
     */
    public record ProcurementInfo(Long supplierId, BigDecimal purchasePrice, Long productDefaultSupplierId) {
    }
}
