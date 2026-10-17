package com.shoppilot.sales.application.dto;

import java.time.LocalDate;

/**
 * 日销售查询筛选条件（{@code GET /api/v1/sales/daily}）。
 *
 * @param skuId       SKU 筛选，可空
 * @param warehouseId 仓库筛选，可空
 * @param dateFrom    起始业务日期（含），可空
 * @param dateTo      结束业务日期（含），可空
 * @param groupBy     聚合维度：{@code sku} / {@code warehouse} / {@code date}；可空表示不聚合（原始行）
 */
public record SalesDailyFilter(Long skuId, Long warehouseId, LocalDate dateFrom, LocalDate dateTo,
                               String groupBy) {

    /** 是否请求维度聚合。 */
    public boolean grouped() {
        return groupBy != null && !groupBy.isBlank();
    }
}
