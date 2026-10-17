package com.shoppilot.inventory.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单笔库存变更的结果（{@code MODULE_BOUNDARIES §2.5} 的返回载体）。
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@code txnId} —— 本次写入的流水 ID；<b>可空</b>：盘点差额为 0 时按 {@code §5.5} 步骤 G
 *       <b>不写流水</b>却仍返回成功，此时为 {@code null}；</li>
 *   <li>{@code qtyBefore} / {@code qtyAfter} —— 与流水同值，满足
 *       {@code qtyAfter = qtyBefore + qtyDelta} 且 {@code qtyAfter == balance.on_hand_qty}；</li>
 *   <li>{@code balanceVersion} —— 本次成功提交后的余额版本（{@code 旧 version + 1}）；</li>
 *   <li>{@code idempotentHit} —— 命中既有幂等键（内容一致）或「差额为 0 不写流水」时为 {@code true}，
 *       调用方据此区分「第一次真正落账」与「重放」。</li>
 * </ul>
 *
 * @param txnId          流水 ID（可空，见上）
 * @param txnType        {@code InventoryTxnType} 名称
 * @param bizType        {@code InventoryBizType} 名称
 * @param qtyDelta       本次带符号变更量
 * @param qtyBefore      变更前现有库存
 * @param qtyAfter       变更后现有库存
 * @param balanceVersion 变更后的余额版本
 * @param occurredAt     业务发生时间（UTC）
 * @param idempotentHit  是否为幂等命中 / 无变更成功
 */
public record StockChangeResult(
        Long txnId,
        String txnType,
        String bizType,
        BigDecimal qtyDelta,
        BigDecimal qtyBefore,
        BigDecimal qtyAfter,
        Integer balanceVersion,
        LocalDateTime occurredAt,
        boolean idempotentHit) {
}
