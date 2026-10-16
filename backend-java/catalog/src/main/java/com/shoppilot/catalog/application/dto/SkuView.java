package com.shoppilot.catalog.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * SKU 响应体——字段严格对齐契约 {@code API_CONTRACT §11.2} 的 SKU 成功响应。
 *
 * <p><b>含 {@code team_id}</b>（契约示例输出 team_id）。数量类（{@code moq}/{@code order_multiple}/{@code qty_step}）
 * 以字符串输出并保留标度（{@code §9.4}）；{@code listed_date} 为 {@code DATE}，输出 {@code yyyy-MM-dd}。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SkuView {

    private Long id;
    private Long teamId;
    private Long productId;
    private String skuCode;
    private String name;
    private String status;
    private Long supplierId;
    private Integer defaultLeadTimeDays;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal moq;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal orderMultiple;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qtyStep;

    private LocalDate listedDate;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    public static SkuView of(SkuEntity e) {
        SkuView v = new SkuView();
        v.setId(e.getId());
        v.setTeamId(e.getTeamId());
        v.setProductId(e.getProductId());
        v.setSkuCode(e.getSkuCode());
        v.setName(e.getName());
        v.setStatus(e.getStatus());
        v.setSupplierId(e.getSupplierId());
        v.setDefaultLeadTimeDays(e.getDefaultLeadTimeDays());
        v.setMoq(e.getMoq());
        v.setOrderMultiple(e.getOrderMultiple());
        v.setQtyStep(e.getQtyStep());
        v.setListedDate(e.getListedDate());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }
}
