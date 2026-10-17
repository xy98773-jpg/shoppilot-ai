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
 *   <li>{@code idempotentHit} —— 命中既有幂等键（内容一致）或「差额为 0 不写流水」时为 {@code true}。
 *       <b>★ 不要直接用它判断是否为幂等重放</b>，用 {@link #replayed()}（差额 0 也返回 {@code true}）。</li>
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
 * @param idempotentHit  是否为幂等命中 / 无变更成功（★ 判「重放」请用 {@link #replayed()}）
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

    /**
     * 是否属于【幂等重放】—— 供 HTTP 头 {@code Idempotent-Replayed}（{@code API_CONTRACT §6.3}）使用。
     *
     * <p>★ 与 {@link #idempotentHit()} 的区别（不要再混用）：
     * {@code idempotentHit} 还涵盖「盘点差额为 0、不写流水」的无变更成功（{@code §5.5} 步骤 G）——
     * 那种情况 {@code txnId} 为 {@code null}，既没有重放、也没有变更，<b>不得</b>标成重放。
     * 真重放必定带回既有流水 id。
     *
     * <p>本方法只是<b>派生判定</b>，不改变任何字段值：{@code idempotentHit} 的原语义
     * （含差额 0）被审计跳过逻辑（{@code recordAdjustAudit} / {@code recordReversalAudit}，
     * {@code M3-B9} 裁定「无变更不写审计」）依赖，<b>不可</b>为区分重放而改字段值。
     */
    public boolean replayed() {
        return idempotentHit && txnId != null;
    }
}
