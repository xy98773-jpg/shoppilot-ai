package com.shoppilot.inventory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code inventory_transactions} 表映射（{@code DATABASE_DESIGN §5.10}）。
 *
 * <p><b>不可变账本</b>：写入后永不 UPDATE / DELETE；纠错只能写反向 {@code REVERSAL} 流水并把
 * {@code reversed_txn_id} 指向原行。故审计字段为集合 B（无 {@code updated_at}/{@code updated_by}），
 * 且<b>无 {@code version}</b>（{@code §7.4} 表 B：仅 INSERT → V1 不成立）。
 *
 * <p>{@code qty_before} / {@code qty_after} 让每条流水<b>自证</b>：DB 层
 * {@code ck_invtxn_consistency} 强制 {@code qty_after = qty_before + qty_delta}，对账无需排序重放
 * 即可发现断裂。
 */
@Data
@TableName("inventory_transactions")
public class InventoryTransactionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    private Long warehouseId;
    private Long skuId;

    /** {@code InventoryTxnType}（六值之一，DB CHECK 兜底）。 */
    private String txnType;
    /** 本次变更量（带符号；{@code CHECK (qty_delta <> 0)}）。 */
    private BigDecimal qtyDelta;
    /** 变更前 {@code on_hand_qty}。 */
    private BigDecimal qtyBefore;
    /** 变更后 {@code on_hand_qty}（必须 = {@code qty_before + qty_delta}）。 */
    private BigDecimal qtyAfter;

    /** {@code InventoryBizType}（业务来源，与 {@code txn_type} 正交）。 */
    private String bizType;
    /** 业务单据 ID（多态，逻辑关联，不建外键）。 */
    private Long bizRefId;
    /** 业务单据编号（冗余展示）。 */
    private String bizRefNo;

    /** 幂等键（团队内唯一，{@code uk_invtxn_team_idem}；禁止用 UUID 作键）。 */
    private String idempotencyKey;
    /** 变更原因（盘点 / 手工调整必填）。 */
    private String reason;
    /** 被冲销的原流水 ID（仅 {@code REVERSAL}；逻辑自关联，不建外键）。 */
    private Long reversedTxnId;
    /** 操作人（逻辑关联 {@code users.id}）。 */
    private Long operatorId;
    /** 业务发生时间（UTC）。 */
    private LocalDateTime occurredAt;

    // ---- 审计字段（集合 B：仅 created_at + created_by） ----
    private LocalDateTime createdAt;
    private Long createdBy;
}
