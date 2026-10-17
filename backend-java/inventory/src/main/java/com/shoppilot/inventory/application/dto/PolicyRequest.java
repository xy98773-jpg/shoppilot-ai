package com.shoppilot.inventory.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 补货策略 upsert 请求体（{@code PUT /api/v1/replenishment-policies}，契约 {@code API_CONTRACT §10.4}）。
 *
 * <p>按 {@code (sku_id, warehouse_id)} 全量 upsert（{@code DATABASE_DESIGN §5.8}）。所有参数列可空
 * （可空即「未配置」）。数量/比例一律以字符串传输（{@code §9.4}）。{@code id} 仅详情/回显用途，
 * upsert 以 {@code sku_id + warehouse_id} 为锚点，传入的 {@code id} 不参与定位。
 *
 * <p>校验：{@code sku_id}/{@code warehouse_id} 必填（{@code COM-0001}）；二者须同团队
 * （{@code CAT-3004}）；参数不得为负（{@code CAT-3010}）。{@code lead_time_days} 等整型若为 null
 * 表示未配置。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PolicyRequest {

    private Long id;
    private Long skuId;
    private Long warehouseId;

    private Integer leadTimeDays;
    private Integer coverageDays;

    private BigDecimal safetyStockQty;
    private BigDecimal safetyRatio;
    private BigDecimal moq;
    private BigDecimal orderMultiple;
    private BigDecimal qtyStep;

    private Integer windowDays;
    private Integer minDays;
    private BigDecimal cvHigh;

    private Integer slowMovingDays;
    private Integer overstockDays;

    /** 启用状态（缺省视为 {@code true}；停用走 {@code false}，不物理删除）。 */
    private Boolean isEnabled;

    private String remark;
}
