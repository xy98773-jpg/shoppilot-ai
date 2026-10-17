package com.shoppilot.inventory.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryTransactionEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 库存流水响应体（{@code GET /api/v1/inventory/transactions} 与
 * {@code GET /api/v1/inventory/transactions/{id}}，契约 {@code API_CONTRACT §10.4}）。
 *
 * <p>不可变账本（{@code DATABASE_DESIGN §5.10}）。{@code sku_code} 为展示字段，来源 catalog；
 * 数量一律以字符串输出（{@code §9.4}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TransactionView {

    private Long id;
    private Long warehouseId;
    private Long skuId;
    /** 展示字段：来源 {@code skus.sku_code}。 */
    private String skuCode;

    private String txnType;
    private String bizType;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qtyDelta;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qtyBefore;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal qtyAfter;

    private Long bizRefId;
    private String bizRefNo;
    private String idempotencyKey;
    private String reason;
    private Long reversedTxnId;
    private Long operatorId;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime occurredAt;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime createdAt;

    public static TransactionView of(InventoryTransactionEntity e, String skuCode) {
        TransactionView v = new TransactionView();
        v.setId(e.getId());
        v.setWarehouseId(e.getWarehouseId());
        v.setSkuId(e.getSkuId());
        v.setSkuCode(skuCode);
        v.setTxnType(e.getTxnType());
        v.setBizType(e.getBizType());
        v.setQtyDelta(e.getQtyDelta());
        v.setQtyBefore(e.getQtyBefore());
        v.setQtyAfter(e.getQtyAfter());
        v.setBizRefId(e.getBizRefId());
        v.setBizRefNo(e.getBizRefNo());
        v.setIdempotencyKey(e.getIdempotencyKey());
        v.setReason(e.getReason());
        v.setReversedTxnId(e.getReversedTxnId());
        v.setOperatorId(e.getOperatorId());
        v.setOccurredAt(e.getOccurredAt());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }
}
