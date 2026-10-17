package com.shoppilot.inventory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code inventory_balances} 表映射（{@code DATABASE_DESIGN §5.9}）。
 *
 * <p>一 SKU 一仓库一行（{@code uk_invbal_team_wh_sku}），是「余额不存在重复行」的根本保证，
 * 也是并发首次建行的冲突吸收点（{@code §6-C.6}）。余额<b>只能由流水驱动</b>：
 * 任何修改 {@code on_hand_qty} 的路径都必须在同一本地事务内写一条 {@code inventory_transactions}，
 * 且满足 {@code txn.qty_after == balance.on_hand_qty}（{@code §5.9} 设计要点 2）。
 *
 * <p>审计字段集合 A + {@code version}（{@code §7.4} 表 A）。
 */
@Data
@TableName("inventory_balances")
public class InventoryBalanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    private Long warehouseId;
    private Long skuId;

    /** 现有库存（只能由流水驱动；{@code CHECK (on_hand_qty >= 0)}）。 */
    private BigDecimal onHandQty;
    /** 在途库存（由采购单状态变更驱动，<b>无对应流水</b>）。 */
    private BigDecimal inTransitQty;
    /** 已承诺库存（首期恒 0，仅保留写入通道）。 */
    private BigDecimal committedQty;

    /**
     * 可用库存 = {@code on_hand_qty - committed_qty}（STORED 生成列）。
     *
     * <p><b>只读</b>：{@code insertStrategy/updateStrategy = NEVER} 保证本实体<b>永不</b>把该列写进
     * INSERT / UPDATE 语句——MySQL 对生成列显式赋值会直接报 {@code ERROR 3105}。
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private BigDecimal availableQty;

    /** 最近一次流水 ID（逻辑关联 {@code inventory_transactions.id}，<b>不建外键</b>）。 */
    private Long lastTxnId;
    /** 最近一次流水发生时间（UTC）。 */
    private LocalDateTime lastTxnAt;

    // ---- 审计字段（集合 A）+ version ----
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private Integer version;
}
