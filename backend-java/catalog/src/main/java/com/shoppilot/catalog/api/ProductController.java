package com.shoppilot.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppilot.catalog.application.CatalogWriteService;
import com.shoppilot.catalog.application.ProductQueryService;
import com.shoppilot.catalog.application.SkuQueryService;
import com.shoppilot.catalog.application.dto.CatalogPageQuery;
import com.shoppilot.catalog.application.dto.ProductView;
import com.shoppilot.catalog.application.dto.SkuView;
import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.common.web.PageResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品端点（M2-T09，{@code API_CONTRACT §10.3} 的 6 个在批端点，含商品下 SKU 子资源）。
 *
 * <p>鉴权口径同 {@link WarehouseController}：写端点四角色显式列举（含 APPROVER）+ {@code teamIdParam=""}；
 * 读端点不标注。启停端点属 M2-T10，不实现。
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductQueryService productQueryService;
    private final SkuQueryService skuQueryService;
    private final CatalogWriteService catalogWriteService;

    public ProductController(ProductQueryService productQueryService,
                             SkuQueryService skuQueryService,
                             CatalogWriteService catalogWriteService) {
        this.productQueryService = productQueryService;
        this.skuQueryService = skuQueryService;
        this.catalogWriteService = catalogWriteService;
    }

    /** 商品列表（分页；可按 {@code category}/{@code status} 筛选；任何已登录成员）。 */
    @GetMapping
    public ApiResponse<PageResult<ProductView>> list(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "status", required = false) String status) {
        return ApiResponse.success(
                productQueryService.list(CatalogPageQuery.of(page, pageSize, sort), category, status));
    }

    /** 新建商品（{@code OWNER/ADMIN/OPERATOR/APPROVER}）→ 201。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<ProductView> create(@RequestBody JsonNode body) {
        return ApiResponse.success(catalogWriteService.upsertProduct(null, body));
    }

    /** 商品详情（任何已登录成员）：异团队 → 403，不存在 → 404 CAT-3001。 */
    @GetMapping("/{id}")
    public ApiResponse<ProductView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(productQueryService.get(id));
    }

    /** 修改商品（{@code OWNER/ADMIN/OPERATOR/APPROVER}）→ 200。 */
    @PatchMapping("/{id}")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<ProductView> update(@PathVariable("id") Long id, @RequestBody JsonNode body) {
        return ApiResponse.success(catalogWriteService.upsertProduct(id, body));
    }

    /** 商品下的 SKU 列表（分页；任何已登录成员）：商品不存在 → 404 CAT-3001。 */
    @GetMapping("/{id}/skus")
    public ApiResponse<PageResult<SkuView>> listSkus(
            @PathVariable("id") Long id,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort) {
        return ApiResponse.success(
                skuQueryService.listByProduct(id, CatalogPageQuery.of(page, pageSize, sort)));
    }

    /** 在指定商品下新建 SKU（{@code OWNER/ADMIN/OPERATOR/APPROVER}）→ 201。 */
    @PostMapping("/{id}/skus")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<SkuView> createSku(@PathVariable("id") Long id, @RequestBody JsonNode body) {
        return ApiResponse.success(catalogWriteService.upsertSku(null, id, body));
    }
}
