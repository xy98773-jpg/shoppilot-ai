package com.shoppilot.inventory.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 库存调整请求体（{@code POST /api/v1/inventory/adjustments}，契约 {@code API_CONTRACT §11.4}）。
 *
 * <p>二合一入口：{@code txn_type ∈ {COUNT_ADJUST, MANUAL_ADJUST}}。
 * <ul>
 *   <li>{@code COUNT_ADJUST}（盘点调整）：以<b>目标值</b> {@code target_on_hand_qty} 表达，差额由服务换算；</li>
 *   <li>{@code MANUAL_ADJUST}（手工调整）：以<b>带符号增量</b> {@code qty_delta} 表达，需 {@code ADMIN}+
 *       （{@code INV-5007}，见 {@link com.shoppilot.inventory.api.InventoryController}）。</li>
 * </ul>
 * 数量一律以<b>字符串</b>传输（{@code §9.4}）。{@code expected_version} 仅作契约兼容字段，本批并发控制
 * 由 {@code InventoryWriteService} 内部乐观锁（{@code INV-5002}）权威处理，不在此层二次校验。
 * {@code occurred_at} 由服务端以 UTC 生成，客户端传值被忽略。
 *
 * <p>★ 幂等键来自请求头 {@code Idempotency-Key}（由控制器读取后透传，本 DTO 不含）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdjustmentRequest {

    private Long warehouseId;
    private Long skuId;

    /** {@code COUNT_ADJUST} / {@code MANUAL_ADJUST}。 */
    private String txnType;

    /** 盘点目标值（{@code COUNT_ADJUST} 用）。 */
    private BigDecimal targetOnHandQty;

    /** 带符号增量（{@code MANUAL_ADJUST} 用）。 */
    private BigDecimal qtyDelta;

    /** 调整原因（{@code COUNT_ADJUST}/{@code MANUAL_ADJUST} 必填，{@code INV-5011}）。 */
    private String reason;

    /** 契约兼容字段（未强制使用），见类注释。 */
    private Long expectedVersion;
}
