package com.shoppilot.sales.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/**
 * 销售序列点（{@code SalesQueryService.getSeries} 返回，供 {@code analysis} 组装给 Python，{@code §2.6}）。
 *
 * <p>按 {@code sale_date} <b>升序</b>。数量与金额以<b>字符串</b>输出（{@code §9.4}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SalesSeriesPoint {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate saleDate;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal returnQty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal salesAmount;

    public static SalesSeriesPoint of(LocalDate saleDate, BigDecimal qty, BigDecimal returnQty,
                                      BigDecimal salesAmount) {
        SalesSeriesPoint p = new SalesSeriesPoint();
        p.setSaleDate(saleDate);
        p.setQty(qty);
        p.setReturnQty(returnQty);
        p.setSalesAmount(salesAmount);
        return p;
    }
}
