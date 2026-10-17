package com.shoppilot.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import com.shoppilot.inventory.infrastructure.persistence.mapper.InventoryBalanceMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 余额行的<b>乐观锁更新单元</b>（{@code DATABASE_DESIGN §6-C.2} 步骤 5 / {@code §7.2}）。
 *
 * <p>为什么单独成类而不写在 {@code InventoryWriteService} 里：这条语句是「流水已插入、余额尚未更新」
 * 这个<b>唯一可能产生半成品</b>的临界点（乐观锁失败即产生孤儿流水的风险点），也是重试单元里
 * 唯一需要被故障注入观测的位置。收敛到单一方法，既让语句与 {@code §6-C.2} 逐条对应，
 * 也让「乐观锁冲突」「更新异常」两种情况可被精确定位与测试，而不必 mock Mapper。
 *
 * <p><b>语句</b>（与 {@code §6-C.2} 步骤 5 逐字对应，仅多带 {@code team_id} 双保险与
 * {@code updated_by} 审计列）：
 * <pre>
 * UPDATE inventory_balances
 *    SET on_hand_qty = ?, last_txn_id = ?, last_txn_at = ?, updated_by = ?, version = version + 1
 *  WHERE id = ? AND version = ? AND team_id = ?
 * </pre>
 * 影响行数必须恰为 1；{@code 0} 表示版本已被并发方推进 → 调用方回滚到本次尝试的 SAVEPOINT 后重试。
 *
 * <p>★ 租户线会再追加一次 {@code team_id = <当前团队>}；与这里的显式条件语义相同，
 * 属 {@code §5.9}「所有更新语句必须带 team_id 条件」的双保险（幂等重复条件不影响结果）。
 */
@Component
public class InventoryBalanceUpdater {

    private final InventoryBalanceMapper balanceMapper;

    public InventoryBalanceUpdater(InventoryBalanceMapper balanceMapper) {
        this.balanceMapper = balanceMapper;
    }

    /**
     * 以乐观锁方式把余额推进到 {@code onHandQty}。
     *
     * @param teamId          当前团队（服务端上下文）
     * @param balanceId       余额行主键
     * @param expectedVersion 读取时拿到的版本（{@code WHERE version = ?}）
     * @param onHandQty       目标现有库存（= 流水的 {@code qty_after}）
     * @param txnId           本次流水 ID（回填 {@code last_txn_id}）
     * @param txnAt           本次流水发生时间（回填 {@code last_txn_at}）
     * @param operatorId      操作人（可空）
     * @return 受影响行数（1 = 成功；0 = 版本冲突）
     */
    public int apply(long teamId,
                     long balanceId,
                     int expectedVersion,
                     BigDecimal onHandQty,
                     Long txnId,
                     LocalDateTime txnAt,
                     Long operatorId) {
        return balanceMapper.update(null, new LambdaUpdateWrapper<InventoryBalanceEntity>()
                .eq(InventoryBalanceEntity::getId, balanceId)
                .eq(InventoryBalanceEntity::getTeamId, teamId)
                .eq(InventoryBalanceEntity::getVersion, expectedVersion)
                .set(InventoryBalanceEntity::getOnHandQty, onHandQty)
                .set(InventoryBalanceEntity::getLastTxnId, txnId)
                .set(InventoryBalanceEntity::getLastTxnAt, txnAt)
                .set(InventoryBalanceEntity::getUpdatedBy, operatorId)
                .setSql("version = version + 1"));
    }
}
