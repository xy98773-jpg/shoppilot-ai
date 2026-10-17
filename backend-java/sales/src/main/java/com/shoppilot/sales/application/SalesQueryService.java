package com.shoppilot.sales.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.catalog.application.SkuQueryService;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.sales.application.dto.SalesDailyFilter;
import com.shoppilot.sales.application.dto.SalesDailyResponse;
import com.shoppilot.sales.application.dto.SalesGroupView;
import com.shoppilot.sales.application.dto.SalesPageQuery;
import com.shoppilot.sales.application.dto.SalesSeriesPoint;
import com.shoppilot.sales.application.support.SalesSorts;
import com.shoppilot.sales.infrastructure.persistence.entity.SalesDailyEntity;
import com.shoppilot.sales.infrastructure.persistence.mapper.SalesDailyMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 销售只读查询服务（{@code MODULE_BOUNDARIES §2.6}：{@code listDaily} / {@code getSeries}）。
 *
 * <h2>边界（M3-T12，B8）</h2>
 * <ul>
 *   <li>全部查询走<b>只读事务</b>（{@code @Transactional(readOnly=true)}）；<b>绝不产生副作用</b>
 *       （不写库、不写审计、不触发分析）；</li>
 *   <li>{@code sku_code} 经 catalog 的 {@link SkuQueryService} 取（展示字段），
 *       <b>禁止注入 {@code SkuMapper}</b>（{@code MODULE_BOUNDARIES §2.4}，参见 {@code R46}）；</li>
 *   <li>分页响应字段是 <b>{@code list}</b>（{@link PageResult}），{@code pageSize > 200} → {@code COM-0007}
 *       （不做静默夹取）；排序走物理列白名单，非法 → {@code COM-0007}（防注入）。</li>
 * </ul>
 *
 * <p>★ {@code getSeries} 返回<b>按日期升序</b>的销量序列，供 {@code analysis} 组装给 Python
 * （{@code §2.6} / {@code §4}：{@code analysis} 应经本服务而非直查 {@code sales_daily}）。
 */
@Service
public class SalesQueryService {

    /** 原始行允许排序的物理列白名单（snake_case）。 */
    private static final Set<String> SORT_WHITELIST = Set.of(
            "id", "warehouse_id", "sku_id", "product_id", "sale_date", "qty",
            "return_qty", "sales_amount", "order_count", "source", "created_at", "updated_at");

    private final SalesDailyMapper salesDailyMapper;
    private final SkuQueryService skuQueryService;

    public SalesQueryService(SalesDailyMapper salesDailyMapper, SkuQueryService skuQueryService) {
        this.salesDailyMapper = salesDailyMapper;
        this.skuQueryService = skuQueryService;
    }

    // =====================================================================
    // 原始行列表（F-SAL-02，不聚合）
    // =====================================================================

    @Transactional(readOnly = true)
    public PageResult<SalesDailyResponse> listDaily(SalesDailyFilter filter, SalesPageQuery page) {
        List<SalesPageQuery.SortSpec> sort = page.validatedSort(SORT_WHITELIST);
        assertDateRange(filter);

        QueryWrapper<SalesDailyEntity> wrapper = new QueryWrapper<>();
        if (filter.skuId() != null) {
            wrapper.eq("sku_id", filter.skuId());
        }
        if (filter.warehouseId() != null) {
            wrapper.eq("warehouse_id", filter.warehouseId());
        }
        if (filter.dateFrom() != null) {
            wrapper.ge("sale_date", filter.dateFrom());
        }
        if (filter.dateTo() != null) {
            wrapper.le("sale_date", filter.dateTo());
        }
        // 默认按 sale_date 倒序（最新在前），命中 idx_sales_team_date
        SalesSorts.apply(wrapper, sort, "sale_date", true);

        Page<SalesDailyEntity> p = salesDailyMapper.selectPage(
                new Page<>(page.getPage(), page.getPageSize()), wrapper);
        Map<Long, String> skuCodes = skuCodesOf(p.getRecords().stream()
                .map(SalesDailyEntity::getSkuId).collect(Collectors.toSet()));
        List<SalesDailyResponse> list = p.getRecords().stream()
                .map(e -> SalesDailyResponse.of(e, skuCodes.get(e.getSkuId())))
                .toList();
        return PageResult.of(p.getTotal(), page.getPage(), page.getPageSize(), list);
    }

    // =====================================================================
    // 维度聚合（groupBy=sku|warehouse|date）
    // =====================================================================

    @Transactional(readOnly = true)
    public PageResult<SalesGroupView> listGrouped(SalesDailyFilter filter, SalesPageQuery page) {
        // 仍校验分页 / 排序参数（pageSize>200 或非法 sort → COM-0007）；聚合结果按 group_key 排序，忽略显式 sort
        page.validatedSort(SORT_WHITELIST);
        assertDateRange(filter);
        String groupBy = normalizeGroupBy(filter.groupBy());

        List<Map<String, Object>> rows = salesDailyMapper.groupAggregate(
                groupBy, filter.skuId(), filter.warehouseId(), filter.dateFrom(), filter.dateTo());

        // 分组结果行数远小于原始行数：内存分页（与 B7 alerts 同口径；SQL 聚合已下推）
        int total = rows.size();
        int from = Math.max(0, (page.getPage() - 1) * page.getPageSize());
        int to = Math.min(total, from + page.getPageSize());
        List<SalesGroupView> list = from >= total
                ? List.of()
                : rows.subList(from, to).stream().map(this::toGroupView).toList();
        return PageResult.of(total, page.getPage(), page.getPageSize(), list);
    }

    // =====================================================================
    // 销售序列（供 analysis；日期升序）
    // =====================================================================

    @Transactional(readOnly = true)
    public List<SalesSeriesPoint> getSeries(Long skuId, Long warehouseId, LocalDate fromDate, LocalDate toDate) {
        if (skuId == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("sku_id", "sku_id 必填")));
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw BizException.withDetails(ErrorCodes.COM_0007, "分页或排序参数不合法",
                    List.of(new ApiErrorDetail("dateFrom", "fromDate 不能晚于 toDate", fromDate)));
        }
        LambdaQueryWrapper<SalesDailyEntity> w = new LambdaQueryWrapper<>();
        w.eq(SalesDailyEntity::getSkuId, skuId);
        if (warehouseId != null) {
            w.eq(SalesDailyEntity::getWarehouseId, warehouseId);
        }
        if (fromDate != null) {
            w.ge(SalesDailyEntity::getSaleDate, fromDate);
        }
        if (toDate != null) {
            w.le(SalesDailyEntity::getSaleDate, toDate);
        }
        w.orderByAsc(SalesDailyEntity::getSaleDate);
        return salesDailyMapper.selectList(w).stream()
                .map(e -> SalesSeriesPoint.of(e.getSaleDate(), e.getQty(), e.getReturnQty(), e.getSalesAmount()))
                .toList();
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private void assertDateRange(SalesDailyFilter filter) {
        if (filter.dateFrom() != null && filter.dateTo() != null
                && filter.dateFrom().isAfter(filter.dateTo())) {
            throw BizException.withDetails(ErrorCodes.COM_0007, "分页或排序参数不合法",
                    List.of(new ApiErrorDetail("dateFrom", "dateFrom 不能晚于 dateTo", filter.dateFrom())));
        }
    }

    /** 归一化分组维度；非 {@code sku|warehouse|date} → {@code COM-0007}（契约 SAL-6006 降级口径）。 */
    private String normalizeGroupBy(String groupBy) {
        String v = groupBy == null ? "" : groupBy.trim().toLowerCase();
        if (v.equals("sku") || v.equals("warehouse") || v.equals("date")) {
            return v;
        }
        throw BizException.withDetails(ErrorCodes.COM_0007, "分页或排序参数不合法",
                List.of(new ApiErrorDetail("groupBy", "groupBy 仅支持 sku / warehouse / date", groupBy)));
    }

    private SalesGroupView toGroupView(Map<String, Object> row) {
        SalesGroupView v = new SalesGroupView();
        Object key = row.get("group_key");
        v.setGroupKey(key == null ? null : String.valueOf(key));
        v.setQty(toBigDecimal(row.get("qty")));
        v.setReturnQty(toBigDecimal(row.get("return_qty")));
        v.setSalesAmount(toBigDecimal(row.get("sales_amount")));
        v.setOrderCount(toLong(row.get("order_count")));
        return v;
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof BigDecimal bd ? bd : new BigDecimal(o.toString());
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof Number n ? n.longValue() : Long.valueOf(o.toString());
    }

    /** 取 SKU 编码映射（租户线约束：异团队 SKU 不在返回内）。 */
    private Map<Long, String> skuCodesOf(Set<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return skuQueryService.getCodesByIds(new ArrayList<>(skuIds)).stream()
                .collect(Collectors.toMap(SkuEntity::getId, SkuEntity::getSkuCode,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
