package com.shoppilot.inventory.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 库存调整成功响应体（{@code POST /api/v1/inventory/adjustments} → 201，契约 {@code API_CONTRACT §11.4}）。
 *
 * <p>{@code transaction_id} 在「盘点差额为 0 不写流水」时为 {@code null}（{@code INVENTORY_WORKFLOW §5.5} 步骤 G）。
 * 数量一律以字符串输出（{@code §9.4}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdjustmentResponse {

    private Long transactionId;
    private Long warehouseId;
    private Long skuId;
    private String txnType;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qtyDelta;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qtyBefore;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qtyAfter;

    private Integer balanceVersion;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime occurredAt;

    public static AdjustmentResponse from(com.shoppilot.inventory.application.dto.StockChangeResult result,
                                         Long warehouseId, Long skuId) {
        AdjustmentResponse r = new AdjustmentResponse();
        r.setTransactionId(result.txnId());
        r.setWarehouseId(warehouseId);
        r.setSkuId(skuId);
        r.setTxnType(result.txnType());
        r.setQtyDelta(result.qtyDelta());
        r.setQtyBefore(result.qtyBefore());
        r.setQtyAfter(result.qtyAfter());
        r.setBalanceVersion(result.balanceVersion());
        r.setOccurredAt(result.occurredAt());
        return r;
    }
}
