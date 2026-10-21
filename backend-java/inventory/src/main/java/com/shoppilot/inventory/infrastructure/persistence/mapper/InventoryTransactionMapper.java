package com.shoppilot.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryTransactionEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code inventory_transactions} 表 Mapper。
 *
 * <p><b>仅 INSERT 与 SELECT</b>：流水是不可变账本，本接口刻意不提供任何 UPDATE / DELETE 入口
 * （{@code DATABASE_DESIGN §5.10}「写入后永不 UPDATE / DELETE」）。
 *
 * <p>自定义查询分两组：幂等 / 冲销前置校验（{@link #selectByIdempotencyKey} /
 * {@link #countByReversedTxnId}），以及 M8-T01（{@code R194}）的批量导入三件套
 * （{@link #selectByIdempotencyKeys} / {@link #insertBatch} / {@link #selectIdsByKeys}）。
 * 全部受租户线约束，跨团队流水一律查不到（→ {@code INV-5008}）。
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
     * {@link #selectByIdempotencyKey} 的批量版（M8-T01 / {@code R194}）：一次 {@code IN} 覆盖一片。
     *
     * <p>用途与单行版完全一致——批量路径同样要「先 SELECT 再决定」，把已落库的行判为重放、
     * 内容不一致的判 {@code INV-5003}。<b>不</b>因为走了批量就改成「先插再 catch」：
     * 异常控制流会把「重放」与「真冲突」混成一类，且回滚代价从一行变成一片。
     *
     * <p>只取幂等判定必需的列（<b>不</b> {@code SELECT *}）：片内可能上千行，
     * 少带一列少一份网络与反序列化开销。
     *
     * @param idempotencyKeys 幂等键集合（调用方保证已去重、非空）
     * @return 既有流水列表（不含 {@code reason} 等无关列）
     */
    @Select("<script>"
            + "SELECT id, team_id, warehouse_id, sku_id, txn_type, qty_delta, biz_type, biz_ref_id, "
            + "idempotency_key FROM inventory_transactions WHERE idempotency_key IN "
            + "<foreach collection='idempotencyKeys' item='key' open='(' separator=',' close=')'>#{key}</foreach>"
            + "</script>")
    List<InventoryTransactionEntity> selectByIdempotencyKeys(
            @Param("idempotencyKeys") List<String> idempotencyKeys);

    /**
     * 多值 INSERT 流水（M8-T01 / {@code R194}）：一片一次插入。
     *
     * <p>★ <b>为什么不用 MyBatis-Plus 的 {@code BaseMapper#insert} 批量</b>：
     * MP 的批量 insert 是「一次执行器批处理」，仍按行发协议包；这里的多值 INSERT 是
     * <b>单条语句</b>，服务端一次解析一次执行，才是 {@code B4-PERF} 归因里「按批事务」的形态。
     *
     * <p>★ <b>为什么事后要 {@link #selectIdsByKeys} 查回 ID</b>：多值 INSERT 拿不到逐行自增 ID，
     * 而 {@code last_txn_id} 必须回填真实流水 ID（{@code InventoryWriteServiceTest} 有断言）。
     * <b>不能</b>用 {@code LAST_INSERT_ID()} 顺序推断——并发事务会插在中间拿到相同区间的 ID，
     * 推断错位会把别人的流水 ID 写进余额行。
     *
     * <p><b>不加</b> {@code @InterceptorIgnore}：{@code team_id} 由调用方显式写入每一行的 VALUES，
     * 租户线对 INSERT 的处理是「补齐缺失列」而非重复追加，故不冲突
     * （与 {@code insertIfAbsentBatch} 必须忽略的理由不同：那条用了 ODKU）。
     */
    @Insert("<script>"
            + "INSERT INTO inventory_transactions "
            + "(team_id, warehouse_id, sku_id, txn_type, qty_delta, qty_before, qty_after, "
            + "biz_type, biz_ref_id, idempotency_key, operator_id, occurred_at, created_by) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.teamId}, #{r.warehouseId}, #{r.skuId}, #{r.txnType}, #{r.qtyDelta}, #{r.qtyBefore}, "
            + "#{r.qtyAfter}, #{r.bizType}, #{r.bizRefId}, #{r.idempotencyKey}, #{r.operatorId}, "
            + "#{r.occurredAt}, #{r.createdBy})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("rows") List<TxnInsertRow> rows);

    /**
     * 按幂等键批量查回流水主键（M8-T01 / {@code R194}），用于回填 {@code last_txn_id}。
     *
     * <p>只查刚插入进本事务的那一批键——同一事务内刚提交的行对本连接必然可见，
     * 不需要 {@code FOR UPDATE}，也不会读到别的事务。
     *
     * @param idempotencyKeys 幂等键集合（调用方保证已去重、非空）
     * @return {@code 幂等键 → 流水 ID}
     */
    @Select("<script>"
            + "SELECT id, idempotency_key FROM inventory_transactions WHERE idempotency_key IN "
            + "<foreach collection='idempotencyKeys' item='key' open='(' separator=',' close=')'>#{key}</foreach>"
            + "</script>")
    List<Map<String, Object>> selectIdsByKeys(@Param("idempotencyKeys") List<String> idempotencyKeys);

    /**
     * 统计「以该流水为冲销目标」的流水条数（{@code uk_invtxn_team_reversed} 的业务侧前置校验）。
     *
     * @param reversedTxnId 被冲销的原流水 ID
     * @return 条数；{@code > 0} 表示已被冲销 → {@code INV-5009}
     */
    @Select("SELECT COUNT(*) FROM inventory_transactions WHERE reversed_txn_id = #{reversedTxnId}")
    long countByReversedTxnId(@Param("reversedTxnId") long reversedTxnId);

    /**
     * 多值 INSERT 的行载荷（M8-T01 / {@code R194}）。
     *
     * <p>字段与 {@code InventoryTransactionEntity} 写入列一一对应；不继承实体是为了让
     * 「批量路径写哪些列」在编译期就固定下来，避免实体加字段后批量路径静默漏写。
     */
    record TxnInsertRow(long teamId, long warehouseId, long skuId, String txnType, java.math.BigDecimal qtyDelta,
                        java.math.BigDecimal qtyBefore, java.math.BigDecimal qtyAfter, String bizType,
                        Long bizRefId, String idempotencyKey, Long operatorId,
                        java.time.LocalDateTime occurredAt, Long createdBy) {
    }
}
