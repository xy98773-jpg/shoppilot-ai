package com.shoppilot.inventory.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.inventory.infrastructure.persistence.entity.ReplenishmentPolicyEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 补货策略响应体（{@code GET /api/v1/replenishment-policies} 与 {@code .../{id}}，
 * 契约 {@code API_CONTRACT §10.4} / {@code DATABASE_DESIGN §5.8}）。
 *
 * <p>全部参数列均可空（可空即「未配置」，触发向下一级回退）；数量/比例一律以字符串输出（{@code §9.4}）。
 * 主键 {@code id} 与 {@code sku_id}/{@code warehouse_id} 不可变；{@code is_enabled} 停用走 {@code 0}，
 * 不物理删除。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PolicyView {

    private Long id;
    private Long skuId;
    private Long warehouseId;
    /** 展示字段：来源 {@code skus.sku_code}。 */
    private String skuCode;

    private Integer leadTimeDays;
    private Integer coverageDays;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal safetyStockQty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal safetyRatio;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal moq;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal orderMultiple;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qtyStep;

    private Integer windowDays;
    private Integer minDays;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal cvHigh;

    private Integer slowMovingDays;
    private Integer overstockDays;
    private Boolean isEnabled;
    private String remark;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime updatedAt;

    private Integer version;

    public static PolicyView of(ReplenishmentPolicyEntity e, String skuCode) {
        PolicyView v = new PolicyView();
        v.setId(e.getId());
        v.setSkuId(e.getSkuId());
        v.setWarehouseId(e.getWarehouseId());
        v.setSkuCode(skuCode);
        v.setLeadTimeDays(e.getLeadTimeDays());
        v.setCoverageDays(e.getCoverageDays());
        v.setSafetyStockQty(e.getSafetyStockQty());
        v.setSafetyRatio(e.getSafetyRatio());
        v.setMoq(e.getMoq());
        v.setOrderMultiple(e.getOrderMultiple());
        v.setQtyStep(e.getQtyStep());
        v.setWindowDays(e.getWindowDays());
        v.setMinDays(e.getMinDays());
        v.setCvHigh(e.getCvHigh());
        v.setSlowMovingDays(e.getSlowMovingDays());
        v.setOverstockDays(e.getOverstockDays());
        v.setIsEnabled(e.getIsEnabled() != null && e.getIsEnabled() == 1);
        v.setRemark(e.getRemark());
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        v.setVersion(e.getVersion());
        return v;
    }
}
