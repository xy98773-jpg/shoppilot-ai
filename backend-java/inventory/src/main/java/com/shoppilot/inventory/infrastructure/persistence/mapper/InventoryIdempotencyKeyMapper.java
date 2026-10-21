package com.shoppilot.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryIdempotencyKeyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code inventory_idempotency_keys} 表 Mapper（M8-T04 / ADR-008 / {@code R76}）。
 *
 * <h2>这张表存在的唯一理由</h2>
 * <p>幂等键<b>原本只有</b> {@code inventory_transactions.idempotency_key} 一个载体，
 * 而盘点调整在差额为 0 时按 {@code INVENTORY_WORKFLOW §5.5} 步骤 G「不写流水」
 * ⇒ 该 key 从未落库 ⇒ {@code replayIfIdempotent} 永远查不到 ⇒
 * 同 key 重试会在余额被改动后<b>真落账</b>（把余额拉回盘点目标值，静默抹掉中间变动）。
 *
 * <p>故差额为 0 的操作在此登记占位行（{@code result_kind = NO_CHANGE}），
 * 让幂等键在<b>所有分支</b>都有载体。与流水表<b>互斥</b>（ADR-008 §4）：
 * 差额 ≠ 0 只写流水、差额 = 0 只写本表，不存在「两边都有且需同步」的不变式。
 *
 * <p>★ <b>不建外键</b>：与 {@code inventory_transactions} 同款取舍
 * （{@code DATABASE_CONSTRAINTS §3.3} ④「账本类表不建指向主数据的外键」）。
 *
 * <p>租户线：{@link #selectByKey} <b>不加</b> {@code @InterceptorIgnore}，
 * 插件自动追加 {@code AND team_id = <当前团队>} ⇒ 跨团队 key 查不到。
 */
@Mapper
public interface InventoryIdempotencyKeyMapper extends BaseMapper<InventoryIdempotencyKeyEntity> {

    /**
     * 按幂等键取本团队的占位行。
     *
     * <p>租户线自动追加 {@code AND team_id = <当前团队>}；本表不建外键，
     * {@code sku_id}/{@code warehouse_id} 仅用于对账定位。
     *
     * @param idempotencyKey 幂等键
     * @return 占位行；不存在返回 {@code null}
     */
    @Select("SELECT * FROM inventory_idempotency_keys WHERE idempotency_key = #{idempotencyKey}")
    InventoryIdempotencyKeyEntity selectByKey(@Param("idempotencyKey") String idempotencyKey);
}