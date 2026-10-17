package com.shoppilot.sales.application.dto;

import java.time.LocalDate;

/**
 * 日销售行的业务键 {@code (sku_id, warehouse_id, sale_date)}（M3-B10.5 / R78）。
 *
 * <h2>用途</h2>
 * {@code importdata} 在 {@code VALIDATED} 阶段对 {@code SALES_DAILY} 的每一行判定
 * 「是否命中 {@code uk_sales_team_wh_sku_date}」以产生 {@code IMP-4006}（{@code WARNING}，
 * 「将覆盖 N 行已有数据」，{@code IMPORT_WORKFLOW §7.6} / {@code DATABASE_DESIGN §6-D.2}）。
 * 逐行查询是 N+1，故以本记录作为<b>批量</b>存在性查询的入参/出参元素。
 *
 * <p>{@code MODULE_BOUNDARIES §2.4}：{@code importdata} 只能经本模块的 application 服务
 * 与本包的 DTO 交互，<b>不得</b>注入 {@code SalesDailyMapper}。
 *
 * @param skuId       SKU ID
 * @param warehouseId 仓库 ID
 * @param saleDate    业务日期（团队时区自然日）
 */
public record SalesDayKey(long skuId, long warehouseId, LocalDate saleDate) {

    /** 语义化工厂。 */
    public static SalesDayKey of(long skuId, long warehouseId, LocalDate saleDate) {
        return new SalesDayKey(skuId, warehouseId, saleDate);
    }
}
