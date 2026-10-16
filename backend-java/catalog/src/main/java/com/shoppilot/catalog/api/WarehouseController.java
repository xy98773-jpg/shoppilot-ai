package com.shoppilot.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppilot.catalog.application.CatalogWriteService;
import com.shoppilot.catalog.application.WarehouseQueryService;
import com.shoppilot.catalog.application.dto.CatalogPageQuery;
import com.shoppilot.catalog.application.dto.WarehouseView;
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
 * 仓库端点（M2-T09，{@code API_CONTRACT §10.3} 的 4 个在批端点）。
 *
 * <h2>鉴权（★ 最易做错）</h2>
 * <ul>
 *   <li><b>写端点</b>（POST/PATCH）：{@code @RequireRole({"OWNER","ADMIN","OPERATOR","APPROVER"})}，
 *       <b>四个角色名显式列举（含 APPROVER）</b>；{@code teamIdParam=""}——catalog 路径无 {@code {teamId}}，
 *       团队来自 JWT，若误填 {@code "id"} 会被切面误判为 {@code TEAM-2001}。</li>
 *   <li><b>读端点</b>（GET）：<b>不标注</b> {@code @RequireRole}——契约对「任何已登录成员」开放；
 *       跨团队 / 不存在由服务层（{@link WarehouseQueryService}）兜底。</li>
 * </ul>
 *
 * <p>启停端点（{@code DELETE /{id}}、{@code PATCH /{id}/status}）属 M2-T10，本批<b>不实现</b>。
 * 响应码：POST → {@code 201}，PATCH → {@code 200}，GET → {@code 200}（契约 §2）。
 */
@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    /** 主数据写角色：四个角色名显式列举（漏掉 APPROVER 是最典型错误，USER_ROLES §2.2 #2）。 */
    private static final String[] WRITE_ROLES = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"};

    private final WarehouseQueryService warehouseQueryService;
    private final CatalogWriteService catalogWriteService;

    public WarehouseController(WarehouseQueryService warehouseQueryService,
                               CatalogWriteService catalogWriteService) {
        this.warehouseQueryService = warehouseQueryService;
        this.catalogWriteService = catalogWriteService;
    }

    /** 仓库列表（分页；任何已登录成员）。 */
    @GetMapping
    public ApiResponse<PageResult<WarehouseView>> list(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort) {
        return ApiResponse.success(warehouseQueryService.list(CatalogPageQuery.of(page, pageSize, sort)));
    }

    /** 新建仓库（{@code OWNER/ADMIN/OPERATOR/APPROVER}）→ 201。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<WarehouseView> create(@RequestBody JsonNode body) {
        return ApiResponse.success(catalogWriteService.upsertWarehouse(null, body));
    }

    /** 仓库详情（任何已登录成员）：异团队 → 403，不存在 → 404 CAT-3003。 */
    @GetMapping("/{id}")
    public ApiResponse<WarehouseView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(warehouseQueryService.get(id));
    }

    /** 修改仓库（{@code OWNER/ADMIN/OPERATOR/APPROVER}）→ 200。 */
    @PatchMapping("/{id}")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<WarehouseView> update(@PathVariable("id") Long id, @RequestBody JsonNode body) {
        return ApiResponse.success(catalogWriteService.upsertWarehouse(id, body));
    }
}
