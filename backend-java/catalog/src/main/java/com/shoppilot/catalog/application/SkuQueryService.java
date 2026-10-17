package com.shoppilot.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.catalog.application.dto.CatalogPageQuery;
import com.shoppilot.catalog.application.dto.SkuView;
import com.shoppilot.catalog.application.support.CatalogGuards;
import com.shoppilot.catalog.application.support.CatalogSorts;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.catalog.infrastructure.persistence.mapper.SkuMapper;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.PageResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public SkuQueryService(SkuMapper skuMapper, ProductQueryService productQueryService) {
        this.skuMapper = skuMapper;
        this.productQueryService = productQueryService;
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
}
