package com.shoppilot.purchasing.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.purchasing.application.PurchaseOrderAppService;
import com.shoppilot.purchasing.application.dto.CreatePurchaseOrderFromReplenishmentRequest;
import com.shoppilot.purchasing.application.dto.PurchaseOrderFromReplenishmentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采购单端点（M6-T02；{@code API_CONTRACT §10.8} / §11.8）。
 *
 * <h2>本批端点</h2>
 * <ul>
 *   <li>{@code POST /api/v1/purchasing/orders/from-replenishment} —— 由已审批建议生成采购单（草稿 DRAFT）。</li>
 * </ul>
 *
 * <p>★ <b>路径是 {@code /purchasing/orders/...}</b>：{@code /purchase-orders} 是 M0 一致性审查
 * 已修正掉的旧写法（{@code API_CONTRACT.md:65}），本路径以契约为准。
 *
 * <p>★ 权限 {@code APPROVER/ADMIN/OWNER}（OPERATOR 仅能起草手工采购单）。
 * ★ {@code Idempotency-Key} 请求头必填（缺失 ⇒ {@code COM-0010} 400）。
 *
 * <p><b>不做</b>（后续批次）：{@code confirm}/{@code order}/{@code cancel}（M6-T03）、
 * 到货登记（M6-T04+）、列表查询（M6-T07）。
 */
@RestController
@RequestMapping("/api/v1/purchasing/orders")
public class PurchaseOrderController {

    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PurchaseOrderAppService appService;

    public PurchaseOrderController(PurchaseOrderAppService appService) {
        this.appService = appService;
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
}
