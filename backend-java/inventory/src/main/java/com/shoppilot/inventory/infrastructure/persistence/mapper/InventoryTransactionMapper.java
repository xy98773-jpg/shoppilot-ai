package com.shoppilot.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryTransactionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code inventory_transactions} 表 Mapper。
 *
 * <p><b>仅 INSERT 与 SELECT</b>：流水是不可变账本，本接口刻意不提供任何 UPDATE / DELETE 入口
 * （{@code DATABASE_DESIGN §5.10}「写入后永不 UPDATE / DELETE」）。
 *
 * <p>两条自定义查询都是幂等 / 冲销前置校验的必备语句；租户线自动追加
 * {@code AND team_id = <当前团队>}，故跨团队流水一律查不到（→ {@code INV-5008}）。
 */
@Mapper
public interface InventoryTransactionMapper extends BaseMapper<InventoryTransactionEntity> {

    /**
     * 按幂等键取<b>本团队内</b>的既有流水（{@code uk_invtxn_team_idem} 的点查）。
     *
     * <p>用于「先 SELECT 再决定」的幂等判定（{@code INVENTORY_WORKFLOW §8.3}）：
     * 内容一致 → 返回既有结果；内容不一致 → {@code INV-5003}。
     * <b>不靠</b>捕获 {@code DuplicateKeyException} 反推内容。
     *
     * @param idempotencyKey 幂等键（调用方按 {@code §8.3} 构造）
     * @return 既有流水；不存在返回 {@code null}
     */
    @Select("SELECT * FROM inventory_transactions WHERE idempotency_key = #{idempotencyKey}")
    InventoryTransactionEntity selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 统计「以该流水为冲销目标」的流水条数（{@code uk_invtxn_team_reversed} 的业务侧前置校验）。
     *
     * @param reversedTxnId 被冲销的原流水 ID
     * @return 条数；{@code > 0} 表示已被冲销 → {@code INV-5009}
     */
    @Select("SELECT COUNT(*) FROM inventory_transactions WHERE reversed_txn_id = #{reversedTxnId}")
    long countByReversedTxnId(@Param("reversedTxnId") long reversedTxnId);
}
