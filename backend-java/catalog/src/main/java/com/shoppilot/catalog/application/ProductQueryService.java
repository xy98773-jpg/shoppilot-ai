package com.shoppilot.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.catalog.application.dto.CatalogPageQuery;
import com.shoppilot.catalog.application.dto.ProductView;
import com.shoppilot.catalog.application.support.CatalogGuards;
import com.shoppilot.catalog.application.support.CatalogSorts;
import com.shoppilot.catalog.infrastructure.persistence.entity.ProductEntity;
import com.shoppilot.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.PageResult;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 商品只读查询服务（{@code MODULE_BOUNDARIES §2.3} 对外只读入口之一）。
 */
@Service
public class ProductQueryService {

    private static final Set<String> SORT_WHITELIST =
            Set.of("id", "product_code", "name", "category", "brand", "status", "created_at", "updated_at");

    private final ProductMapper productMapper;

    public ProductQueryService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    /**
     * 商品列表（{@code GET /api/v1/products}，分页；可按 {@code category}/{@code status} 筛选）。
     *
     * @param category 类目精确筛选，{@code null}/空 表示不过滤
     * @param status   状态精确筛选（ACTIVE/INACTIVE），{@code null}/空 表示不过滤
     */
    public PageResult<ProductView> list(CatalogPageQuery query, String category, String status) {
        List<CatalogPageQuery.SortSpec> sort = query.validatedSort(SORT_WHITELIST);
        QueryWrapper<ProductEntity> wrapper = new QueryWrapper<>();
        if (category != null && !category.isBlank()) {
            wrapper.eq("category", category);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        CatalogSorts.apply(wrapper, sort, "created_at", true);

        Page<ProductEntity> page = productMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        List<ProductView> list = page.getRecords().stream().map(ProductView::of).toList();
        return PageResult.of(page.getTotal(), query.getPage(), query.getPageSize(), list);
    }

    /** 商品详情（{@code GET /api/v1/products/{id}}）：异团队 → 403，不存在 → 404 CAT-3001。 */
    public ProductView get(Long id) {
        return ProductView.of(requireInTeam(id));
    }

    /** 取商品实体（{@code MODULE_BOUNDARIES §2.3}，供下游读取）。 */
    public ProductEntity getById(Long id) {
        return productMapper.selectById(id);
    }

    /** 归属判定（受控探测区分 403/404）。供 SKU 写入与 {@code GET /products/{id}/skus} 复用。 */
    public ProductEntity requireInTeam(Long id) {
        return CatalogGuards.requireInTeam(
                productMapper.selectById(id),
                () -> productMapper.selectTeamIdById(id),
                ErrorCodes.CAT_3001, "商品不存在");
    }
}
