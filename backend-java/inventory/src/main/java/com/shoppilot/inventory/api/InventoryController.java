package com.shoppilot.inventory.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.inventory.application.InventoryQueryService;
import com.shoppilot.inventory.application.InventoryWriteService;
import com.shoppilot.inventory.application.dto.AdjustmentRequest;
import com.shoppilot.inventory.application.dto.AdjustmentResponse;
import com.shoppilot.inventory.application.dto.AlertView;
import com.shoppilot.inventory.application.dto.BalanceView;
import com.shoppilot.inventory.application.dto.InventoryPageQuery;
import com.shoppilot.inventory.application.dto.ReverseRequest;
import com.shoppilot.inventory.application.dto.StockChangeResult;
import com.shoppilot.inventory.application.dto.TransactionView;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存端点（M3-T10/T11，B7；{@code API_CONTRACT §10.4}）。
 *
 * <h2>端点清单</h2>
 * <ul>
 *   <li>{@code GET  /inventory/balances}（任何已登录成员，F-INV-01）；</li>
 *   <li>{@code GET  /inventory/transactions}（任何已登录成员，F-INV-02）；</li>
 *   <li>{@code GET  /inventory/transactions/{id}}（任何已登录成员；不存在/异团队 → INV-5008）；</li>
 *   <li>{@code POST /inventory/adjustments}（写角色 + R59 动态角色，F-INV-03/04）；</li>
 *   <li>{@code POST /inventory/transactions/{id}/reverse}（ADMIN+，F-INV-06）；</li>
 *   <li>{@code GET  /inventory/alerts}（任何已登录成员，F-INV-05）。</li>
 * </ul>
 *
 * <h2>★ R59 角色校验口径（本批重点）</h2>
 * <ul>
 *   <li>{@code POST /inventory/adjustments}：① 静态层 {@link RequireRole} 四写角色
 *       （OWNER/ADMIN/OPERATOR/APPROVER）→ VIEWER 不满足直接 {@code TEAM-2003}（切面默认行为，不改动切面）；
 *       ② 方法内动态层：{@code txn_type == MANUAL_ADJUST} 时<b>额外</b>要求 ADMIN+
 *       （OWNER/ADMIN），否则 {@code INV-5007}（403）。两码分工不同，<b>不互相替代</b>。</li>
 *   <li>{@code POST /inventory/transactions/{id}/reverse}：{@link RequireRole}{@code (OWNER,ADMIN)}
 *       → 角色不足 {@code TEAM-2003}；<b>不</b>用 {@code INV-5007}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryQueryService queryService;
    private final InventoryWriteService writeService;

    public InventoryController(InventoryQueryService queryService, InventoryWriteService writeService) {
        this.queryService = queryService;
        this.writeService = writeService;
    }

    /** 库存余额列表（F-INV-01，任何已登录成员）。 */
    @GetMapping("/balances")
    public ApiResponse<PageResult<BalanceView>> balances(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort,
            @RequestParam(name = "warehouse_id", required = false) Long warehouseId,
            @RequestParam(name = "sku_id", required = false) Long skuId) {
        return ApiResponse.success(queryService.listBalances(
                InventoryPageQuery.of(page, pageSize, sort), warehouseId, skuId));
    }

    /** 库存流水列表（F-INV-02，任何已登录成员）。 */
    @GetMapping("/transactions")
    public ApiResponse<PageResult<TransactionView>> transactions(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort,
            @RequestParam(name = "warehouse_id", required = false) Long warehouseId,
            @RequestParam(name = "sku_id", required = false) Long skuId,
            @RequestParam(name = "txn_type", required = false) String txnType,
            @RequestParam(name = "dateFrom", required = false) String dateFrom,
            @RequestParam(name = "dateTo", required = false) String dateTo) {
        return ApiResponse.success(queryService.listTransactions(
                InventoryPageQuery.of(page, pageSize, sort), warehouseId, skuId, txnType, dateFrom, dateTo));
    }

    /** 单笔流水详情（F-INV-02）：不存在 / 异团队 → INV-5008（404）。 */
    @GetMapping("/transactions/{id}")
    public ApiResponse<TransactionView> transactionDetail(@PathVariable("id") long id) {
        return ApiResponse.success(queryService.getTransaction(id));
    }

    /**
     * 库存调整（F-INV-03/04）：盘点（COUNT_ADJUST）/ 手工（MANUAL_ADJUST）二合一。
     *
     * <p>★ R59：四写角色静态层（@RequireRole）→ VIEWER 直接 TEAM-2003；方法内对 MANUAL_ADJUST 额外要求
     * ADMIN+，否则 INV-5007。幂等键来自请求头 {@code Idempotency-Key}，<b>原样透传</b>给
     * {@link InventoryWriteService}，不自行构造（缺失/空白 → COM-0001；契约标注的 COM-0010 未固化，本批降级使用）。
     * <p>★ 幂等重放命中（同键同内容）→ 结果与首次一致并补 {@code Idempotent-Replayed: true}（{@code §6.3}）。
     */
    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<AdjustmentResponse> adjustments(
            @RequestBody AdjustmentRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletResponse httpResponse) {
        String key = requireIdempotencyKey(idempotencyKey);

        if (req.getWarehouseId() == null || req.getSkuId() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("warehouse_id/sku_id", "必填")));
        }
        String txnType = req.getTxnType();
        if (!"COUNT_ADJUST".equals(txnType) && !"MANUAL_ADJUST".equals(txnType)) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("txn_type", "仅支持 COUNT_ADJUST / MANUAL_ADJUST", txnType)));
        }
        // ★ R59 动态角色：MANUAL_ADJUST 需 ADMIN+（OWNER/ADMIN），否则 INV-5007
        if ("MANUAL_ADJUST".equals(txnType)
                && !TeamContext.require().hasAnyRole(List.of("OWNER", "ADMIN"))) {
            throw new BizException(ErrorCodes.INV_5007, "当前角色无权执行该库存调整");
        }

        AdjustmentResponse response;
        if ("COUNT_ADJUST".equals(txnType)) {
            BigDecimal target = req.getTargetOnHandQty();
            if (target == null) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("target_on_hand_qty", "盘点调整必填目标值")));
            }
            StockChangeResult result = writeService.applyCountAdjust(req.getSkuId(), req.getWarehouseId(),
                    target, req.getReason(), null, key);
            markReplayed(httpResponse, result);
            response = AdjustmentResponse.from(result, req.getWarehouseId(), req.getSkuId());
        } else {
            BigDecimal delta = req.getQtyDelta();
            if (delta == null) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("qty_delta", "手工调整必填带符号增量")));
            }
            StockChangeResult result = writeService.applyManualAdjust(req.getSkuId(), req.getWarehouseId(),
                    delta, req.getReason(), key);
            markReplayed(httpResponse, result);
            response = AdjustmentResponse.from(result, req.getWarehouseId(), req.getSkuId());
        }
        return ApiResponse.success(response);
    }

    /**
     * 冲销某笔流水（F-INV-06）：ADMIN+（OWNER/ADMIN）。
     *
     * <p>★ 角色不足 → TEAM-2003（<b>不</b>用 INV-5007）。幂等键同样来自请求头 {@code Idempotency-Key}。
     * INV-5008 / INV-5010 / INV-5009 由 B6 的 {@link InventoryWriteService} 抛出，本层不做二次映射。
     * <p>★ {@code R73}（{@code M3-B9.5}）：同幂等键 + 同内容的重放返回 <b>200</b> + 首次的
     * {@code transaction_id}，并补 {@code Idempotent-Replayed: true}；同键不同内容 → {@code INV-5003}；
     * 不同键的二次冲销 → {@code INV-5009}。判定顺序在服务层（{@code attemptReversal}），本层不重复实现。
     */
    @PostMapping("/transactions/{id}/reverse")
    @RequireRole(value = {"OWNER", "ADMIN"}, teamIdParam = "")
    public ApiResponse<AdjustmentResponse> reverse(
            @PathVariable("id") long id,
            @RequestBody ReverseRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletResponse httpResponse) {
        String key = requireIdempotencyKey(idempotencyKey);
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("reason", "冲销原因必填")));
        }
        StockChangeResult result = writeService.applyReversal(id, req.getReason(), key);
        markReplayed(httpResponse, result);
        return ApiResponse.success(AdjustmentResponse.from(result, null, null));
    }

    /** 库存预警列表（F-INV-05，任何已登录成员；无状态、不落库）。 */
    @GetMapping("/alerts")
    public ApiResponse<PageResult<AlertView>> alerts(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort,
            @RequestParam(name = "warehouse_id", required = false) Long warehouseId,
            @RequestParam(name = "sku_id", required = false) Long skuId) {
        return ApiResponse.success(queryService.listAlerts(
                InventoryPageQuery.of(page, pageSize, sort), warehouseId, skuId));
    }

    /**
     * 幂等重放命中时补响应头 {@code Idempotent-Replayed: true}（{@code API_CONTRACT §6.3}「可带」，
     * {@code M3-B9.5}；与 {@code importdata} 的 {@code confirm} 同口径）。
     *
     * <p>★ 只加 HTTP 头，<b>不动</b> {@link ApiResponse} 封装（公共响应结构是跨模块契约）。
     * 首次执行不带该头；重放时业务结果与首次一致，客户端凭此头区分「重放」与「新执行」。
     */
    private static void markReplayed(HttpServletResponse httpResponse, StockChangeResult result) {
        if (result != null && result.idempotentHit()) {
            httpResponse.setHeader("Idempotent-Replayed", "true");
        }
    }

    /** 幂等键：缺失/空白 → COM-0001（契约标注的 COM-0010 未固化，本批降级使用）。 */
    private static String requireIdempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "缺少必要的请求信息",
                    List.of(ApiErrorDetail.of("Idempotency-Key", "幂等键必填")));
        }
        return header.trim();
    }
}
