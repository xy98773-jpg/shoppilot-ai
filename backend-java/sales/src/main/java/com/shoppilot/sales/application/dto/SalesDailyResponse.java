package com.shoppilot.sales.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.sales.infrastructure.persistence.entity.SalesDailyEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 日销售响应体（{@code GET/POST /api/v1/sales/daily}，契约 {@code API_CONTRACT §10.5}）。
 *
 * <p>{@code sku_code} 为<b>展示字段</b>，来源 {@code skus.sku_code}（经 catalog 的
 * {@code SkuQueryService} 取，本模块<b>禁止</b>注入 {@code SkuMapper}，{@code MODULE_BOUNDARIES §2.4}，
 * 参见 {@code R46}）。数量与金额一律以<b>字符串</b>输出并保留标度（{@code §9.4}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SalesDailyResponse {

    private Long id;
    private Long warehouseId;
    private Long skuId;
    /** 展示字段：来源 {@code skus.sku_code}。 */
    private String skuCode;
    private Long productId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate saleDate;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal returnQty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal salesAmount;

    private Integer orderCount;
    private String source;
    private Long batchId;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime updatedAt;

    public static SalesDailyResponse of(SalesDailyEntity e, String skuCode) {
        SalesDailyResponse v = new SalesDailyResponse();
        v.setId(e.getId());
        v.setWarehouseId(e.getWarehouseId());
        v.setSkuId(e.getSkuId());
        v.setSkuCode(skuCode);
        v.setProductId(e.getProductId());
        v.setSaleDate(e.getSaleDate());
        v.setQty(e.getQty());
        v.setReturnQty(e.getReturnQty());
        v.setSalesAmount(e.getSalesAmount());
        v.setOrderCount(e.getOrderCount());
        v.setSource(e.getSource());
        v.setBatchId(e.getBatchId());
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        return v;
    }
}
