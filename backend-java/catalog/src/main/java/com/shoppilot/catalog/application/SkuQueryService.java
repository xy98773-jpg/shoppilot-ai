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
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * SKU 只读查询服务（{@code MODULE_BOUNDARIES §2.3} 对外只读入口之一）。
 */
@Service
public class SkuQueryService {

    private static final Set<String> SORT_WHITELIST =
            Set.of("id", "sku_code", "name", "status", "created_at", "updated_at");

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

    /** 归属判定（受控探测区分 403/404）。 */
    public SkuEntity requireInTeam(Long id) {
        return CatalogGuards.requireInTeam(
                skuMapper.selectById(id),
                () -> skuMapper.selectTeamIdById(id),
                ErrorCodes.CAT_3001, "SKU 不存在");
    }
}
