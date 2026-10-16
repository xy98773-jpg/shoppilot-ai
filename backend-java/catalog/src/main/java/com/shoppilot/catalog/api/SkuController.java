package com.shoppilot.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppilot.catalog.application.CatalogWriteService;
import com.shoppilot.catalog.application.SkuQueryService;
import com.shoppilot.catalog.application.dto.CatalogPageQuery;
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
 * SKU 端点（M2-T09，{@code API_CONTRACT §10.3} 的 4 个在批端点）。
 *
 * <p>鉴权口径同 {@link WarehouseController}：写端点四角色显式列举（含 APPROVER）+ {@code teamIdParam=""}；
 * 读端点不标注。独立 {@code POST /skus} 须在请求体带 {@code product_id}；启停端点属 M2-T10，不实现。
 */
@RestController
@RequestMapping("/api/v1/skus")
public class SkuController {

    private final SkuQueryService skuQueryService;
    private final CatalogWriteService catalogWriteService;

    public SkuController(SkuQueryService skuQueryService, CatalogWriteService catalogWriteService) {
        this.skuQueryService = skuQueryService;
        this.catalogWriteService = catalogWriteService;
    }

    /** SKU 列表（分页；可按 {@code productId}/{@code status}/{@code keyword} 筛选；任何已登录成员）。 */
    @GetMapping
    public ApiResponse<PageResult<SkuView>> list(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort,
            @RequestParam(name = "productId", required = false) Long productId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return ApiResponse.success(skuQueryService.list(
                CatalogPageQuery.of(page, pageSize, sort), productId, status, keyword));
    }

    /** 新建 SKU（{@code OWNER/ADMIN/OPERATOR/APPROVER}；请求体须带 {@code product_id}）→ 201。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<SkuView> create(@RequestBody JsonNode body) {
        return ApiResponse.success(catalogWriteService.upsertSku(null, null, body));
    }

    /** SKU 详情（任何已登录成员）：异团队 → 403，不存在 → 404 CAT-3001。 */
    @GetMapping("/{id}")
    public ApiResponse<SkuView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(skuQueryService.get(id));
    }

    /** 修改 SKU（{@code OWNER/ADMIN/OPERATOR/APPROVER}；采购属性/交期/MOQ 等）→ 200。 */
    @PatchMapping("/{id}")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<SkuView> update(@PathVariable("id") Long id, @RequestBody JsonNode body) {
        return ApiResponse.success(catalogWriteService.upsertSku(id, null, body));
    }
}
