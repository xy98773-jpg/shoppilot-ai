package com.shoppilot.inventory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code inventory_idempotency_keys} 表映射（M8-T04 / ADR-008 / {@code R76}）。
 *
 * <p><b>只增不删的请求去重记录</b>：当前只写「盘点调整差额为 0」这一种情况
 * （{@code result_kind = NO_CHANGE}），但结构按可扩展设计（{@code result_kind} 预留 {@code TXN}）。
 *
 * <p><b>为什么不是流水表的一部分</b>：差额为 0 时{@code INVENTORY_WORKFLOW §5.5} 步骤 G
 * 明确「不写流水」，而 {@code DATABASE_CONSTRAINTS} 的 {@code ck_invtxn_delta_non_zero}
 * 又禁止 {@code qty_delta = 0} ⇒ 流水表<b>结构上</b>无法承载这个占位。
 * 另起一张职责单一的表，比给流水表加例外更清晰（ADR-008 §2）。
 *
 * <p><b>无 {@code updated_at}/{@code updated_by}</b>（与 {@link InventoryTransactionEntity} 同款集合 B）：
 * 本表只 INSERT，永不 UPDATE；纠错也不需要改这里（幂等记录本身就该永存）。
 */
@Data
@TableName("inventory_idempotency_keys")
public class InventoryIdempotencyKeyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    private String idempotencyKey;

    private String txnType;
    private String bizType;
    private Long bizRefId;
    private Long skuId;
    private Long warehouseId;

    /**
     * 内容哈希（{@code sha256(txn_type|biz_type|biz_ref_id|sku_id|warehouse_id|qty)}）。
     *
     * <p>★ 必须<b>包含数量</b>：否则「同 key 换 target」会被误判为同内容而被当成纯重放。
     * 比对字段沿用 {@code R75} 口径（不含 {@code reason}）——{@code reason} 相同与否
     * 不影响「是否产生了同样的库存效果」。
     */
    private String contentHash;

    /** {@code NO_CHANGE}（差额 0，无流水）｜ {@code TXN}（已写流水，本批不写入，仅预留）。 */
    private String resultKind;

    private LocalDateTime createdAt;
    private Long createdBy;
}