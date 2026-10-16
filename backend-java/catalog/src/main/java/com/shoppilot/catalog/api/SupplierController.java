package com.shoppilot.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppilot.catalog.application.CatalogWriteService;
import com.shoppilot.catalog.application.SupplierQueryService;
import com.shoppilot.catalog.application.dto.CatalogPageQuery;
import com.shoppilot.catalog.application.dto.SupplierView;
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
 * 供应商端点（M2-T09，{@code API_CONTRACT §10.3} 的 4 个在批端点）。
 *
 * <p>鉴权口径同 {@link WarehouseController}：写端点四角色显式列举（含 APPROVER）+ {@code teamIdParam=""}；
 * 读端点不标注。启停端点属 M2-T10，不实现。
 */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    private final SupplierQueryService supplierQueryService;
    private final CatalogWriteService catalogWriteService;

    public SupplierController(SupplierQueryService supplierQueryService,
                              CatalogWriteService catalogWriteService) {
        this.supplierQueryService = supplierQueryService;
        this.catalogWriteService = catalogWriteService;
    }

    /** 供应商列表（分页；任何已登录成员）。 */
    @GetMapping
    public ApiResponse<PageResult<SupplierView>> list(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort) {
        return ApiResponse.success(supplierQueryService.list(CatalogPageQuery.of(page, pageSize, sort)));
    }

    /** 新建供应商（{@code OWNER/ADMIN/OPERATOR/APPROVER}）→ 201。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<SupplierView> create(@RequestBody JsonNode body) {
        return ApiResponse.success(catalogWriteService.upsertSupplier(null, body));
    }

    /** 供应商详情（任何已登录成员）：异团队 → 403，不存在 → 404 CAT-3001。 */
    @GetMapping("/{id}")
    public ApiResponse<SupplierView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(supplierQueryService.get(id));
    }

    /** 修改供应商（{@code OWNER/ADMIN/OPERATOR/APPROVER}；含 {@code lead_time_days}/{@code moq}/{@code order_multiple}）→ 200。 */
    @PatchMapping("/{id}")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<SupplierView> update(@PathVariable("id") Long id, @RequestBody JsonNode body) {
        return ApiResponse.success(catalogWriteService.upsertSupplier(id, body));
    }
}
