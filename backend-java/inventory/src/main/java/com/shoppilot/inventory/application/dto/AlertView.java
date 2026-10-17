package com.shoppilot.inventory.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 库存预警响应体（{@code GET /api/v1/inventory/alerts}，契约 {@code API_CONTRACT §10.4 / INVENTORY_WORKFLOW §5.7}）。
 *
 * <p>预警是<b>只读、无状态、不落库</b>的快速提示（{@code INVENTORY_WORKFLOW §5.7} 明确与风险规则
 * {@code analysis_items.risk_types} 区分：预警面向「当前量」，风险面向「含预测窗口的可支配量 {@code IP}」）。
 * 三类预警：{@code STOCKOUT}（缺货，{@code on_hand_qty <= 0}）、{@code LOWSTOCK}（低库存，
 * {@code available_qty <= safety_stock_qty}）、{@code SLOWMOVING}（滞销，{@code on_hand_qty > 0} 且近期无销售）。
 *
 * <p>{@code threshold} 仅 {@code LOWSTOCK} 有意义（取策略的 {@code safety_stock_qty}）；其余类型为空。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AlertView {

    private Long warehouseId;
    private Long skuId;
    /** 展示字段：来源 {@code skus.sku_code}。 */
    private String skuCode;

    /** 预警类型：{@code STOCKOUT} / {@code LOWSTOCK} / {@code SLOWMOVING}。 */
    private String alertType;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal onHandQty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal availableQty;

    /** 低库存阈值（{@code LOWSTOCK} 时为策略 {@code safety_stock_qty}；其余为空）。 */
    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal threshold;

    /** 人类可读提示。 */
    private String message;
}
