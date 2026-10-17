package com.shoppilot.sales.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.sales.application.SalesQueryService;
import com.shoppilot.sales.application.SalesWriteService;
import com.shoppilot.sales.application.dto.SalesDailyFilter;
import com.shoppilot.sales.application.dto.SalesDailyRequest;
import com.shoppilot.sales.application.dto.SalesDailyResponse;
import com.shoppilot.sales.application.dto.SalesDailyUpsertRow;
import com.shoppilot.sales.application.dto.SalesPageQuery;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 销售端点（M3-T12，B8；{@code API_CONTRACT §10.5}）。
 *
 * <h2>端点清单</h2>
 * <ul>
 *   <li>{@code POST /sales/daily}（OPERATOR+，F-SAL-01，{@code source=MANUAL}，upsert 覆盖）；
 *       错误码 {@code SAL-6002}/{@code SAL-6003}/{@code SAL-6005}；</li>
 *   <li>{@code GET  /sales/daily}（任何已登录成员，F-SAL-02）；
 *       {@code groupBy=sku|warehouse|date} 三聚合；查询参数非法 → {@code COM-0007}
 *       （契约 {@code SAL-6006} 未固化，按任务书降级，与 B7 {@code listTransactions} 同口径）。</li>
 * </ul>
 *
 * <p>★ 本层<b>不</b>做团队归属判定（由 {@link SalesWriteService} 经 {@code SkuQueryService} /
 * {@code WarehouseQueryService} 校验，跨团队 → {@code SAL-6005}）；<b>不</b>注入 catalog Mapper。
 */
@RestController
@RequestMapping("/api/v1/sales")
public class SalesController {

    private final SalesWriteService writeService;
    private final SalesQueryService queryService;

    public SalesController(SalesWriteService writeService, SalesQueryService queryService) {
        this.writeService = writeService;
        this.queryService = queryService;
    }

    /**
     * 手工写入 / 覆盖单条日销售（F-SAL-01，OPERATOR+，{@code source=MANUAL}）。
     *
     * <p>幂等键来自请求头 {@code Idempotency-Key}（可选；缺失时按业务键派生
     * {@code MANUAL:<sku>:<warehouse>:<date>}）。覆盖语义由业务键 {@code (team, wh, sku, date)} 保证。
     */
    @PostMapping("/daily")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ApiResponse<SalesDailyResponse> createDaily(
            @RequestBody SalesDailyRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        LocalDate saleDate = parseSaleDate(req.getSaleDate());
        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? "MANUAL:" + req.getSkuId() + ":" + req.getWarehouseId() + ":" + saleDate
                : idempotencyKey.trim();

        SalesDailyUpsertRow row = new SalesDailyUpsertRow(
                req.getSkuId(), req.getWarehouseId(), saleDate,
                req.getQty(), req.getReturnQty(), req.getSalesAmount(),
                req.getOrderCount(), SalesWriteService.SOURCE_MANUAL, req.getBatchId(), key);
        List<SalesDailyResponse> result = writeService.batchUpsert(List.of(row));
        return ApiResponse.success(result.get(0));
    }

    /**
     * 日销售查询（F-SAL-02，任何已登录成员）。
     *
     * <p>无 {@code groupBy} → 原始行分页；{@code groupBy=sku|warehouse|date} → 维度聚合。
     * 两种形态的 {@code data} 均为 {@link PageResult}（字段 {@code list}）。
     */
    @GetMapping("/daily")
    public ApiResponse<PageResult<?>> listDaily(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort,
            @RequestParam(name = "sku_id", required = false) Long skuId,
            @RequestParam(name = "warehouse_id", required = false) Long warehouseId,
            @RequestParam(name = "dateFrom", required = false) String dateFrom,
            @RequestParam(name = "dateTo", required = false) String dateTo,
            @RequestParam(name = "groupBy", required = false) String groupBy) {
        SalesDailyFilter filter = new SalesDailyFilter(
                skuId, warehouseId, parseFilterDate(dateFrom), parseFilterDate(dateTo), groupBy);
        SalesPageQuery query = SalesPageQuery.of(page, pageSize, sort);
        if (filter.grouped()) {
            return ApiResponse.success(queryService.listGrouped(filter, query));
        }
        return ApiResponse.success(queryService.listDaily(filter, query));
    }

    // =====================================================================
    // 辅助：日期解析（非法 → SAL-6003 / COM-0007）
    // =====================================================================

    /** 写入路径的业务日期：缺失 / 非法 → {@code SAL-6003}（{@code 400}）。 */
    private static LocalDate parseSaleDate(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.withDetails(ErrorCodes.SAL_6003, "sale_date 不合法",
                    List.of(ApiErrorDetail.of("sale_date", "sale_date 必填，格式 yyyy-MM-dd")));
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw BizException.withDetails(ErrorCodes.SAL_6003, "sale_date 不合法",
                    List.of(new ApiErrorDetail("sale_date", "日期格式非法，应为 yyyy-MM-dd", value)));
        }
    }

    /** 查询路径的日期筛选：缺失 → null（不过滤）；非法 → {@code COM-0007}（{@code 400}）。 */
    private static LocalDate parseFilterDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw BizException.withDetails(ErrorCodes.COM_0007, "分页或排序参数不合法",
                    List.of(new ApiErrorDetail("date", "日期格式非法，应为 yyyy-MM-dd", value)));
        }
    }
}
