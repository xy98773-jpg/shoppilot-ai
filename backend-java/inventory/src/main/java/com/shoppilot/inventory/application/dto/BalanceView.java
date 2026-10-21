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

    /**
     * 数量零值（<b>固定 3 位小数</b>）。
     *
     * <p>★ 与 {@code DECIMAL(18,3)} 的库内标度对齐：若用 {@code BigDecimal.ZERO}（标度 0），
     * 在途缺省值会被序列化成 {@code "0"}，与其余行的 {@code "0.000"} 不一致，
     * 前端按字符串比对/解析时会出现「同字段两种形态」。派生值缺省一律用本常量。
     */
    private static final BigDecimal ZERO_QTY = new BigDecimal("0.000");

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

    /**
     * 组装响应体。
     *
     * <p>★ {@code in_transit_qty} <b>不再取实体列</b>：物化列
     * {@code inventory_balances.in_transit_qty} 自 {@code M6-T10}（{@code R178}）起退役（零写零读），
     * 展示值由 {@code InventoryInTransitQueryService.batchGetInTransit} 派生（<b>不</b>应用到货窗口，
     * 与 {@code API_CONTRACT #27} 的「在途库存」语义一致）。见 {@code DESIGN_BASIS §10.4.1}。
     *
     * @param inTransitQty 派生在途量（{@code null} ⇒ 兜 {@code 0}）
     */
    public static BalanceView of(InventoryBalanceEntity e, String skuCode, BigDecimal inTransitQty) {
        BalanceView v = new BalanceView();
        v.setId(e.getId());
        v.setWarehouseId(e.getWarehouseId());
        v.setSkuId(e.getSkuId());
        v.setSkuCode(skuCode);
        v.setOnHandQty(e.getOnHandQty());
        v.setInTransitQty(inTransitQty == null ? ZERO_QTY : inTransitQty);
        v.setCommittedQty(e.getCommittedQty());
        v.setAvailableQty(e.getAvailableQty());
        v.setLastTxnAt(e.getLastTxnAt());
        v.setVersion(e.getVersion());
        return v;
    }
}
