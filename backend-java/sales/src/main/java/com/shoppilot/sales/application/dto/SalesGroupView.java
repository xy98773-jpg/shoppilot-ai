package com.shoppilot.sales.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 日销售维度聚合行（{@code GET /api/v1/sales/daily?groupBy=sku|warehouse|date}）。
 *
 * <p>{@code groupKey} 为该维度的取值（{@code sku_id} / {@code warehouse_id} / {@code sale_date}），
 * 以字符串承载以兼容三种维度（数字或日期）。其余为 {@code SUM} 聚合：{@code qty} / {@code return_qty} /
 * {@code sales_amount} / {@code order_count}。数量与金额以<b>字符串</b>输出（{@code §9.4}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SalesGroupView {

    /** 分组键：sku_id / warehouse_id / sale_date（字符串形式）。 */
    private String groupKey;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal returnQty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal salesAmount;

    private Long orderCount;
}
