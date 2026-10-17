package com.shoppilot.inventory.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.inventory.application.InventoryPolicyService;
import com.shoppilot.inventory.application.dto.InventoryPageQuery;
import com.shoppilot.inventory.application.dto.PolicyRequest;
import com.shoppilot.inventory.application.dto.PolicyView;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 补货策略端点（M3-T14，B7；{@code API_CONTRACT §10.4}）。
 *
 * <h2>端点清单</h2>
 * <ul>
 *   <li>{@code GET    /replenishment-policies}（任何已登录成员，F-CAT-06）；</li>
 *   <li>{@code GET    /replenishment-policies/{id}}（任何已登录成员；不存在 → CAT-3001）；</li>
 *   <li>{@code PUT    /replenishment-policies}（OPERATOR+，按 (sku_id,warehouse_id) 全量 upsert）；</li>
 *   <li>{@code DELETE /replenishment-policies/{id}}（OPERATOR+，停用 is_enabled=0）。</li>
 * </ul>
 *
 * <p>写端点（PUT/DELETE）四写角色（OWNER/ADMIN/OPERATOR/APPROVER）→ VIEWER 不满足 {@code TEAM-2003}。
 */
@RestController
@RequestMapping("/api/v1/replenishment-policies")
public class InventoryPolicyController {

    private final InventoryPolicyService policyService;

    public InventoryPolicyController(InventoryPolicyService policyService) {
        this.policyService = policyService;
    }

    /** 补货策略列表（F-CAT-06，任何已登录成员）。 */
    @GetMapping
    public ApiResponse<PageResult<PolicyView>> list(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort,
            @RequestParam(name = "sku_id", required = false) Long skuId,
            @RequestParam(name = "warehouse_id", required = false) Long warehouseId) {
        return ApiResponse.success(policyService.list(
                InventoryPageQuery.of(page, pageSize, sort), skuId, warehouseId));
    }

    /** 补货策略详情（F-CAT-06，任何已登录成员；不存在/异团队 → CAT-3001）。 */
    @GetMapping("/{id}")
    public ApiResponse<PolicyView> detail(@PathVariable("id") long id) {
        return ApiResponse.success(policyService.get(id));
    }

    /**
     * 按 (sku_id, warehouse_id) 全量 upsert（OPERATOR+）。
     *
     * <p>校验：sku_id/warehouse_id 必填（COM-0001）、二者须同团队（CAT-3004）、参数不得为负（CAT-3010）。
     * 并发冲突由 {@code uk_pol_team_sku_wh} 兜底（不报 409）。
     */
    @PutMapping
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<PolicyView> upsert(@RequestBody PolicyRequest req) {
        return ApiResponse.success(policyService.upsert(req));
    }

    /** 停用策略（OPERATOR+，is_enabled=0，不物理删除）。 */
    @DeleteMapping("/{id}")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<PolicyView> disable(@PathVariable("id") long id) {
        return ApiResponse.success(policyService.disable(id));
    }
}
