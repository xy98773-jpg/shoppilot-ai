package com.shoppilot.inventory.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.BigDecimalStringSerializer;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 库存余额响应体（{@code GET /api/v1/inventory/balances}，契约 {@code API_CONTRACT §11.4}）。
 *
 * <p>字段严格对齐契约示例：{@code sku_code} 为<b>展示字段</b>，来源 {@code skus.sku_code}
 * （经 catalog 的 {@code SkuQueryService} 取，本模块<b>禁止</b>注入 {@code SkuMapper}）；
 * 数量一律以<b>字符串</b>输出并保留标度（{@code §9.4}）；{@code available_qty} 为生成列，
 * <b>仅展示</b>、禁止参与补货判断（{@code DESIGN_BASIS §3.1}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class BalanceView {

    private Long id;
    private Long warehouseId;
    private Long skuId;
    /** 展示字段：来源 {@code skus.sku_code}。 */
    private String skuCode;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal onHandQty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal inTransitQty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal committedQty;

    @JsonSerialize(using = BigDecimalStringSerializer.class)
    private BigDecimal availableQty;

    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime lastTxnAt;

    private Integer version;

    public static BalanceView of(InventoryBalanceEntity e, String skuCode) {
        BalanceView v = new BalanceView();
        v.setId(e.getId());
        v.setWarehouseId(e.getWarehouseId());
        v.setSkuId(e.getSkuId());
        v.setSkuCode(skuCode);
        v.setOnHandQty(e.getOnHandQty());
        v.setInTransitQty(e.getInTransitQty());
        v.setCommittedQty(e.getCommittedQty());
        v.setAvailableQty(e.getAvailableQty());
        v.setLastTxnAt(e.getLastTxnAt());
        v.setVersion(e.getVersion());
        return v;
    }
}
