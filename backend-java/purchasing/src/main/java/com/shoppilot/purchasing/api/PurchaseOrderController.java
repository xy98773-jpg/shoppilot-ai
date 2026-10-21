package com.shoppilot.purchasing.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.purchasing.application.PurchaseOrderAppService;
import com.shoppilot.purchasing.application.PurchaseOrderQueryService;
import com.shoppilot.purchasing.application.dto.CreatePurchaseOrderFromReplenishmentRequest;
import com.shoppilot.purchasing.application.dto.PageQuery;
import com.shoppilot.purchasing.application.dto.PurchaseOrderActionRequest;
import com.shoppilot.purchasing.application.dto.PurchaseOrderActionResponse;
import com.shoppilot.purchasing.application.dto.PurchaseOrderCancelRequest;
import com.shoppilot.purchasing.application.dto.PurchaseOrderDetail;
import com.shoppilot.purchasing.application.dto.PurchaseOrderFilter;
import com.shoppilot.purchasing.application.dto.PurchaseOrderFromReplenishmentResponse;
import com.shoppilot.purchasing.application.dto.PurchaseOrderSummary;
import com.shoppilot.purchasing.application.dto.UpdatePurchaseOrderRequest;
import com.shoppilot.common.web.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采购单端点（M6-T02 / M6-T03；{@code API_CONTRACT §10.8} / §11.8）。
 *
 * <h2>本批端点</h2>
 * <ul>
 *   <li>{@code POST /api/v1/purchasing/orders/from-replenishment} —— 由已审批建议生成采购单（草稿 DRAFT）；</li>
 *   <li>{@code POST .../{id}/confirm} —— 确认（{@code DRAFT→PENDING}，A1）；</li>
 *   <li>{@code POST .../{id}/order} —— 下单（{@code PENDING→ORDERED}，A2）；</li>
 *   <li>{@code POST .../{id}/cancel} —— 取消（→{@code CANCELLED}，A3）；</li>
 *   <li>{@code PATCH .../{id}} —— 编辑草稿（仅 {@code DRAFT}，A4）；</li>
 *   <li>{@code GET .../{id}} —— 详情（A5）；{@code GET ...} —— 列表（A5/#B）。</li>
 * </ul>
 *
 * <p>★ <b>路径是 {@code /purchasing/orders/...}</b>：{@code /purchase-orders} 是 M0 一致性审查
 * 已修正掉的旧写法（{@code API_CONTRACT.md:65}），本路径以契约为准。
 *
 * <p>★ 权限：{@code from-replenishment}/{@code confirm}/{@code order}/{@code cancel} 需
 * {@code APPROVER/ADMIN/OWNER}（OPERATOR 仅能起草 DRAFT / 编辑草稿，不足 ⇒ {@code TEAM-2003}）；
 * {@code PATCH} 需 {@code OPERATOR}+；两个 {@code GET} <b>不加</b> {@code @RequireRole}（任何已登录成员）。
 *
 * <p>★ {@code Idempotency-Key} 请求头必填于 {@code from-replenishment}（缺失 ⇒ {@code COM-0010} 400）。
 *
 * <p><b>不做</b>（后续批次）：到货登记（M6-T04+）、确认到货（M6-T05）、查询页面细节以本批 A5 为准。
 */
@RestController
@RequestMapping("/api/v1/purchasing/orders")
public class PurchaseOrderController {

    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PurchaseOrderAppService appService;
    private final PurchaseOrderQueryService queryService;

    public PurchaseOrderController(PurchaseOrderAppService appService,
                                   PurchaseOrderQueryService queryService) {
        this.appService = appService;
        this.queryService = queryService;
    }

    /**
     * 由已审批建议生成采购单（{@code POST /from-replenishment}）。
     *
     * @param idempotencyKey {@code Idempotency-Key} 请求头（必填；同键同体 ⇒ 返回首次创建的采购单）
     * @param request         请求体（数量/单价/供应商一律取自建议，不接受客户端覆盖）
     * @return 201 + 采购单（草稿）详情
     */
    @PostMapping("/from-replenishment")
    @RequireRole(value = {"OWNER", "ADMIN", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<PurchaseOrderFromReplenishmentResponse>> fromReplenishment(
            @RequestHeader(value = HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CreatePurchaseOrderFromReplenishmentRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(appService.createFromApprovedItems(request, idempotencyKey)));
    }

    /**
     * A1 确认采购单（{@code DRAFT → PENDING}）。
     *
     * @param id              采购单 ID
     * @param request         {@code {"expected_version": 1}}（契约 §11.8 请求 B；缺失 ⇒ {@code COM-0001}）
     * @return 200 + {@code {purchase_order_id, po_no, status, confirmed_by, confirmed_at, version}}
     */
    @PostMapping("/{id}/confirm")
    @RequireRole(value = {"OWNER", "ADMIN", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<PurchaseOrderActionResponse>> confirm(
            @PathVariable("id") long id,
            @RequestBody PurchaseOrderActionRequest request) {

        Integer expectedVersion = request == null ? null : request.expectedVersion();
        return ResponseEntity.ok(ApiResponse.success(appService.confirm(id, expectedVersion)));
    }

    /**
     * A2 下单（{@code PENDING → ORDERED}）。
     *
     * <p>★ {@code effective_in_transit_qty} 口径切换属 M6-T10，本端点不写库存（{@code INV-03}）。
     *
     * @return 200 + {@code {purchase_order_id, po_no, status, ordered_at, version}}
     */
    @PostMapping("/{id}/order")
    @RequireRole(value = {"OWNER", "ADMIN", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<PurchaseOrderActionResponse>> order(
            @PathVariable("id") long id,
            @RequestBody PurchaseOrderActionRequest request) {

        Integer expectedVersion = request == null ? null : request.expectedVersion();
        return ResponseEntity.ok(ApiResponse.success(appService.order(id, expectedVersion)));
    }

    /**
     * A3 取消采购单（→ {@code CANCELLED}）。
     *
     * @param request {@code {"reason": "..."}}（必填，≤255；缺失/超长 ⇒ {@code COM-0001}）
     * @return 200 + {@code {purchase_order_id, po_no, status, cancelled_at, cancel_reason, version}}
     */
    @PostMapping("/{id}/cancel")
    @RequireRole(value = {"OWNER", "ADMIN", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<PurchaseOrderActionResponse>> cancel(
            @PathVariable("id") long id,
            @RequestBody PurchaseOrderCancelRequest request) {

        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(ApiResponse.success(appService.cancel(id, reason)));
    }

    /**
     * A4 编辑采购单草稿（仅 {@code DRAFT}；角色 {@code OPERATOR}+）。
     *
     * @return 200 + 更新后的采购单详情（A5 形态）
     */
    @PatchMapping("/{id}")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<PurchaseOrderDetail>> updateDraft(
            @PathVariable("id") long id,
            @RequestBody UpdatePurchaseOrderRequest request) {

        return ResponseEntity.ok(ApiResponse.success(appService.updateDraft(id, request)));
    }

    /**
     * A5-1 采购单详情（含明细与到货进度；任何已登录成员）。
     *
     * @return 200 + 详情；不存在/跨团队 ⇒ {@code PO-9004}（404）
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PurchaseOrderDetail>> get(@PathVariable("id") long id) {
        return ResponseEntity.ok(ApiResponse.success(queryService.getOrder(id)));
    }

    /**
     * A5-2 采购单列表（任何已登录成员；分页字段名 {@code list}）。
     *
     * @param status      状态过滤（白名单六值；省略 = 不限）
     * @param supplierId  供应商过滤
     * @param warehouseId 仓库过滤
     * @param poNo        {@code po_no} 关键字（模糊）
     * @param page        页码（默认 1，≥1）
     * @param pageSize    每页（默认 20，1..200）
     * @return 200 + {@code {list, page, pageSize, total}}
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<PurchaseOrderSummary>>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "supplier_id", required = false) Long supplierId,
            @RequestParam(value = "warehouse_id", required = false) Long warehouseId,
            @RequestParam(value = "po_no", required = false) String poNo,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {

        PurchaseOrderFilter filter = new PurchaseOrderFilter();
        filter.setStatus(status);
        filter.setSupplierId(supplierId);
        filter.setWarehouseId(warehouseId);
        filter.setPoNoLike(poNo);
        PageQuery query = PageQuery.of(
                page == null ? PageQuery.DEFAULT_PAGE : page,
                pageSize == null ? PageQuery.DEFAULT_PAGE_SIZE : pageSize);
        return ResponseEntity.ok(ApiResponse.success(queryService.listOrders(filter, query)));
    }
}
