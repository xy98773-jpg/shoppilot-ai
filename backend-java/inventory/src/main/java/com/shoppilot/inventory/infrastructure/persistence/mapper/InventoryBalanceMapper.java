package com.shoppilot.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code inventory_balances} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}。
 * 自定义语句的理由：
 * <ul>
 *   <li>{@link #insertIfAbsent}：并发首次建行必须用 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 *       吸收 {@code uk_invbal_team_wh_sku} 冲突（{@code INVENTORY_WORKFLOW §8.3} /
 *       {@code DATABASE_DESIGN §6-C.6}）。<b>不用</b>「先查后插 + catch DuplicateKeyException」：
 *       异常控制流会与外层 NESTED 重试单元的事务边界互相干扰。</li>
 *   <li>{@link #lockAnchorSkuIds}：{@code F-03}「同口径锚点行锁」要求 {@code SELECT ... FOR UPDATE}
 *       且按 {@code sku_id} 升序（防死锁）。{@code BaseMapper} 无法表达 {@code FOR UPDATE}。</li>
 *   <li>{@link #insertIfAbsentBatch} / {@link #selectByWarehouseSkuKeys} /
 *       {@link #addOnHandByIds}（M8-T01 / {@code R194}）：批量导入路径，一次语句覆盖一片，
 *       替代「每行 3 次查询 + 1 次 UPDATE」的逐行形态。</li>
 * </ul>
 */
@Mapper
public interface InventoryBalanceMapper extends BaseMapper<InventoryBalanceEntity> {

    /** 余额定位键（仓库 + SKU）；业务键的 {@code team_id} 由租户线/显式参数补齐。 */
    record BalanceKey(long warehouseId, long skuId) {
    }

    /**
     * 一行增量更新指令：把 {@code balanceId} 的 {@code on_hand_qty} 加 {@code delta}，
     * 并回填 {@code last_txn_id} / {@code last_txn_at}（M8-T01 / {@code R194}）。
     *
     * @param txnId 本行的流水 ID（查回得到，见 {@code InventoryTransactionMapper#selectIdsByKeys}）
     */
    record BalanceDelta(long balanceId, BigDecimal delta, Long txnId) {
    }

    /**
     * 建行（已存在则<b>不报错、不改数据</b>）。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE version = version} 是「空更新」：只让 MySQL 吸收唯一键冲突，
     * 不改变任何业务列。调用方随后重新 {@code SELECT} 取 {@code id / version}，
     * 从而两个并发请求都能拿到同一行的确定状态（{@code §6-C.6}）。
     *
     * <p>★ {@code @InterceptorIgnore(tenantLine = "true")} 的两个理由（与
     * {@code AuditLogMapper#insert} 同一处置口径）：
     * <ol>
     *   <li>本语句<b>自带</b> {@code team_id} 列；若再由租户插件追加一次会变成重复列而报错。</li>
     *   <li>{@code ON DUPLICATE KEY UPDATE} 并非所有 JSqlParser 版本都能解析，
     *       让插件整体跳过本语句可彻底避开解析风险。</li>
     * </ol>
     * 代价是失去「插件兜底」，故 {@code team_id} 由调用方从 {@code TeamContext} 取后显式传入
     * （见 {@code InventoryWriteService#currentTeamId}），并在
     * {@code M2_TenantIsolationDbIntegrationTest#TENANT_LINE_EXCEPTIONS} 登记。
     *
     * <p>{@code on_hand_qty = 0} 为「初值」，真正的数量由随后的流水累加得出——
     * 余额是<b>可推导物化状态</b>，不能凭空写成非 0（{@code §5.9} 设计要点 2）。
     *
     * @param teamId      团队（服务端注入，绝不来自客户端）
     * @param warehouseId 仓库
     * @param skuId       SKU
     * @param operatorId  操作人（可空）
     * @return 受影响行数（新建 = 1；已存在 = 0 或 2，取决于 MySQL 判定，<b>不用于业务判断</b>）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO inventory_balances "
            + "(team_id, warehouse_id, sku_id, on_hand_qty, in_transit_qty, committed_qty, version, created_by) "
            + "VALUES (#{teamId}, #{warehouseId}, #{skuId}, 0, 0, 0, 0, #{operatorId}) "
            + "ON DUPLICATE KEY UPDATE version = version")
    int insertIfAbsent(@Param("teamId") long teamId,
                       @Param("warehouseId") long warehouseId,
                       @Param("skuId") long skuId,
                       @Param("operatorId") Long operatorId);

    /**
     * {@link #insertIfAbsent} 的多值版（M8-T01 / {@code R194}）：一片一次建行。
     *
     * <p>★ 与单行版同一口径：{@code @InterceptorIgnore(tenantLine = "true")}（语句自带 {@code team_id} 列，
     * 且 ODKU 不冒险交给 JSqlParser 解析），故必须在
     * {@code M2_TenantIsolationDbIntegrationTest#TENANT_LINE_EXCEPTIONS} 登记（14 → 15 条）。
     * {@code team_id} 由调用方从 {@code TeamContext} 取后显式传入，<b>绝不来自客户端</b>。
     *
     * <p>{@code ON DUPLICANT KEY UPDATE version = version} 仍是「空更新」：只吸收唯一键冲突，不改业务列。
     *
     * @param keys       (仓库, SKU) 定位键集合，调用方保证已去重
     * @param operatorId 操作人（可空）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("<script>"
            + "INSERT INTO inventory_balances "
            + "(team_id, warehouse_id, sku_id, on_hand_qty, in_transit_qty, committed_qty, version, created_by) VALUES "
            + "<foreach collection='keys' item='k' separator=','>"
            + "(#{teamId}, #{k.warehouseId}, #{k.skuId}, 0, 0, 0, 0, #{operatorId})"
            + "</foreach> "
            + "ON DUPLICATE KEY UPDATE version = version"
            + "</script>")
    int insertIfAbsentBatch(@Param("teamId") long teamId,
                            @Param("keys") List<BalanceKey> keys,
                            @Param("operatorId") Long operatorId);

    /**
     * 按 (仓库, SKU) 批量读余额（M8-T01 / {@code R194}）。
     *
     * <p>★ <b>刻意按仓库分组后各自 {@code sku_id IN}</b>，不用 {@code (warehouse_id, sku_id) IN ((?,?),...)}：
     * 后者虽短，但 row-constructor 形态要过租户线的 JSqlParser 解析，平白多一层解析风险；
     * 分组后每条都是最普通的 {@code WHERE warehouse_id = ? AND sku_id IN (...)}，
     * 且天然命中 {@code uk_invbal_team_wh_sku (team_id, warehouse_id, sku_id)} 最左前缀。
     *
     * <p><b>不加</b> {@code @InterceptorIgnore}：租户线自动追加 {@code AND team_id = <当前团队>}，
     * 跨团队行不可能被读到（与 {@link #insertIfAbsentBatch} 的取舍不同，此处无需 ODKU）。
     *
     * @param warehouseId 仓库（同组同一仓库）
     * @param skuIds      SKU 集合（调用方保证已去重、非空）
     */
    @Select("<script>"
            + "SELECT id, team_id, warehouse_id, sku_id, on_hand_qty, version FROM inventory_balances "
            + "WHERE warehouse_id = #{warehouseId} AND sku_id IN "
            + "<foreach collection='skuIds' item='skuId' open='(' separator=',' close=')'>#{skuId}</foreach>"
            + "</script>")
    List<InventoryBalanceEntity> selectByWarehouseSkuKeys(@Param("warehouseId") long warehouseId,
                                                          @Param("skuIds") List<Long> skuIds);

    /**
     * 按主键批量做<b>增量</b>更新（M8-T01 / {@code R194}）：{@code on_hand_qty = on_hand_qty + delta}，
     * 并回填 {@code last_txn_id} / {@code last_txn_at} / {@code updated_by}，{@code version = version + 1}。
     *
     * <p>★ <b>为什么不做 version CAS</b>（与 {@code InventoryBalanceUpdater} 的本质区别）：
     * 批量路径是<b>纯增量</b>写入，不存在「读-改-写」的丢失更新窗口——两条并发语句各自
     * {@code +delta} 由 InnoDB 行锁串行化，结果与逐行串行执行一致。若改用 CAS，
     * 任一行 version 变化就要整片重试，反而把批量优势还回去。
     * version 仍 {@code +1}，保持「每次写入版本号递增」的可观测语义。
     *
     * <p>{@code available_qty} 是 {@code STORED GENERATED}（{@code on_hand_qty - committed_qty}），
     * 无需也不能显式更新。
     *
     * <p><b>不加</b> {@code @InterceptorIgnore}：租户线追加 {@code AND team_id = <当前团队>}，
     * 配合主键 {@code IN} 双重收窄，跨团队行不可能被更新到。
     *
     * @param deltas     增量指令集合（调用方保证 {@code balanceId} 已去重、非空）
     * @param occurredAt 本批统一发生时间（UTC；与流水 {@code occurred_at} 同值）
     * @param operatorId 操作人（可空）
     * @return 受影响行数（应等于 {@code deltas.size()}）
     */
    @Update("<script>"
            + "UPDATE inventory_balances SET "
            + "on_hand_qty = on_hand_qty + CASE id "
            + "<foreach collection='deltas' item='d' separator=' '>WHEN #{d.balanceId} THEN #{d.delta}</foreach> END, "
            + "last_txn_id = CASE id "
            + "<foreach collection='deltas' item='d' separator=' '>WHEN #{d.balanceId} THEN #{d.txnId}</foreach> END, "
            + "last_txn_at = #{occurredAt}, "
            + "updated_by = #{operatorId}, "
            + "version = version + 1 "
            + "WHERE id IN "
            + "<foreach collection='deltas' item='d' open='(' separator=',' close=')'>#{d.balanceId}</foreach>"
            + "</script>")
    int addOnHandByIds(@Param("deltas") List<BalanceDelta> deltas,
                       @Param("occurredAt") java.time.LocalDateTime occurredAt,
                       @Param("operatorId") Long operatorId);

    /**
     * 对给定 SKU 集合的余额行加<b>排他行锁</b>（{@code SELECT ... FOR UPDATE}），返回真正被锁到的
     * {@code sku_id} 列表（升序）。
     *
     * <p>★ 加锁顺序按 {@code sku_id} 升序：调用方并行度再高也按同一全局顺序取锁，避免死锁
     * （{@code DATABASE_DESIGN §7.2} #3 / {@code §6-C.5}）。{@code ORDER BY} 与
     * {@code uk_invbal_team_wh_sku (team_id, warehouse_id, sku_id)} 的索引序一致，InnoDB 按此序扫描并加锁。
     *
     * <p>不存在的 SKU 不会被返回（无可锁之行）；调用方据此区分 {@code locked} 与 {@code missing}。
     * 租户线自动追加 {@code AND team_id = <当前团队>}，跨团队行不可能被锁到。
     */
    @Select("<script>"
            + "SELECT sku_id FROM inventory_balances "
            + "WHERE warehouse_id = #{warehouseId} AND sku_id IN "
            + "<foreach collection='skuIds' item='skuId' open='(' separator=',' close=')'>#{skuId}</foreach> "
            + "ORDER BY sku_id ASC FOR UPDATE"
            + "</script>")
    List<Long> lockAnchorSkuIds(@Param("warehouseId") long warehouseId,
                                @Param("skuIds") List<Long> skuIds);

}
