package com.shoppilot.purchasing.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.purchasing.application.GoodsReceiptAppService;
import com.shoppilot.purchasing.application.GoodsReceiptQueryService;
import com.shoppilot.purchasing.application.dto.CreateGoodsReceiptRequest;
import com.shoppilot.purchasing.application.dto.GoodsReceiptCancelResponse;
import com.shoppilot.purchasing.application.dto.GoodsReceiptDetail;
import com.shoppilot.purchasing.application.dto.GoodsReceiptFilter;
import com.shoppilot.purchasing.application.dto.GoodsReceiptSummary;
import com.shoppilot.purchasing.application.dto.PageQuery;
import com.shoppilot.purchasing.application.dto.UpdateGoodsReceiptRequest;
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
 * 到货单端点（M6-T04；{@code API_CONTRACT §11.9}）。
 *
 * <h2>端点（5 个）</h2>
 * <ul>
 *   <li>{@code POST /api/v1/purchasing/receipts} —— 登记到货（草稿 DRAFT，201）；</li>
 *   <li>{@code PATCH /api/v1/purchasing/receipts/{id}} —— 编辑到货草稿（200）；</li>
 *   <li>{@code POST /api/v1/purchasing/receipts/{id}/confirm} —— <b>确认到货入库</b>
 *       （{@code DRAFT→CONFIRMED}，触发 {@code PURCHASE_INBOUND}，200；M6-T05+T06）；</li>
 *   <li>{@code POST /api/v1/purchasing/receipts/{id}/cancel} —— 作废到货草稿（200）；</li>
 *   <li>{@code GET /api/v1/purchasing/receipts/{id}} + {@code GET /api/v1/purchasing/receipts}
 *       —— 详情 / 列表（200）。</li>
 * </ul>
 *
 * <p>★ 权限：写入类端点 {@code OPERATOR}+（契约 §11.9）；两个 {@code GET} <b>不加</b>
 * {@code @RequireRole}（任何已登录成员可看）。
 *
 * <p>★ {@code Idempotency-Key} 请求头在<b>登记</b>时必填（{@code goods_receipts.idempotency_key}
 * 是 {@code NOT NULL}）⇒ 缺失 {@code COM-0010}（400）。
 *
 * <p>★ <b>草稿绝不写库存（INV-03）</b>：登记 / 编辑 / 作废 / 查询四个端点都不产生库存流水；
 * 入库只发生在 {@code POST /receipts/{id}/confirm}（M6-T05+T06 已实现，见 {@link #confirm(long)}）。
 */
@RestController
@RequestMapping("/api/v1/purchasing/receipts")
public class GoodsReceiptController {

    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    private final GoodsReceiptAppService appService;
    private final GoodsReceiptQueryService queryService;

    public GoodsReceiptController(GoodsReceiptAppService appService,
                                  GoodsReceiptQueryService queryService) {
        this.appService = appService;
        this.queryService = queryService;
    }

    /**
     * A1 登记到货（草稿）。
     *
     * @param idempotencyKey {@code Idempotency-Key} 请求头（必填；同键同体 ⇒ 返回首次创建的那张）
     * @param request         请求体
     * @return 201 + 到货单（草稿）详情
     */
    @PostMapping
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<GoodsReceiptDetail>> create(
            @RequestHeader(value = HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CreateGoodsReceiptRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(appService.create(request, idempotencyKey)));
    }

    /**
     * A2 编辑到货草稿（仅 DRAFT；非 DRAFT ⇒ {@code PO-9008}）。
     *
     * @param id      到货单 ID
     * @param request 请求体
     * @return 200 + 更新后的到货单详情
     */
    @PatchMapping("/{id}")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<GoodsReceiptDetail>> update(
            @PathVariable("id") long id,
            @RequestBody UpdateGoodsReceiptRequest request) {

        return ResponseEntity.ok(ApiResponse.success(appService.update(id, request)));
    }

    /**
     * A3 作废到货草稿（仅 DRAFT；非 DRAFT ⇒ {@code PO-9008}）。
     *
     * @param id 到货单 ID
     * @return 200 + 作废结果
     */
    @PostMapping("/{id}/cancel")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<GoodsReceiptCancelResponse>> cancel(
            @PathVariable("id") long id) {

        return ResponseEntity.ok(ApiResponse.success(appService.cancel(id)));
    }

    /**
     * A5 确认到货入库（{@code DRAFT → CONFIRMED}，触发 {@code PURCHASE_INBOUND}）。
     *
     * <p>角色 {@code OPERATOR}+（契约 {@code :605}；不足 ⇒ {@code TEAM-2003}）。
     * <ul>
     *   <li>到货单不存在 / 跨团队 ⇒ {@code PO-9005}（404）；</li>
     *   <li>非 {@code DRAFT}（已 {@code CONFIRMED} / {@code CANCELLED}）⇒ {@code PO-9003}（409）；</li>
     *   <li>采购单非 {@code ORDERED}/{@code PARTIALLY_RECEIVED} ⇒ {@code PO-9003}（409）；</li>
     *   <li>超额（跨单据累计）⇒ {@code PO-9002}（409，带 {@code details}），<b>且不产生任何库存变更</b>；</li>
     *   <li>库存余额乐观锁冲突 ⇒ {@code INV-5002}（409）。</li>
     * </ul>
     *
     * @param id 到货单 ID
     * @return 200 + 确认后的到货单详情（含 {@code confirmed_at}/{@code confirmed_by}）
     */
    @PostMapping("/{id}/confirm")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<GoodsReceiptDetail>> confirm(@PathVariable("id") long id) {
        return ResponseEntity.ok(ApiResponse.success(appService.confirm(id)));
    }

    /**
     * A4 到货单详情（不存在 / 跨团队 ⇒ {@code PO-9005}）。
     *
     * @param id 到货单 ID
     * @return 200 + 到货单详情（含明细行）
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GoodsReceiptDetail>> get(@PathVariable("id") long id) {
        return ResponseEntity.ok(ApiResponse.success(queryService.getReceipt(id)));
    }

    /**
     * A4 到货单列表（分页字段名 {@code list}）。
     *
     * @param purchaseOrderId 采购单 ID（可选）
     * @param status          到货状态（可选）
     * @param warehouseId     入库仓库（可选）
     * @param receiptNoLike   到货单号关键字（可选，模糊）
     * @param page            页码（≥1）
     * @param pageSize        每页条数（1..200）
     * @return 200 + 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<GoodsReceiptSummary>>> list(
            @RequestParam(value = "purchase_order_id", required = false) Long purchaseOrderId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "warehouse_id", required = false) Long warehouseId,
            @RequestParam(value = "receipt_no", required = false) String receiptNoLike,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {

        GoodsReceiptFilter filter = new GoodsReceiptFilter();
        filter.setPurchaseOrderId(purchaseOrderId);
        filter.setStatus(status);
        filter.setWarehouseId(warehouseId);
        filter.setReceiptNoLike(receiptNoLike);
        return ResponseEntity.ok(ApiResponse.success(
                queryService.listReceipts(filter, new PageQuery(page, pageSize))));
    }
}
