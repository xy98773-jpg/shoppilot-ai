package com.shoppilot.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code inventory_balances} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}。
 * 两条自定义语句的理由：
 * <ul>
 *   <li>{@link #insertIfAbsent}：并发首次建行必须用 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 *       吸收 {@code uk_invbal_team_wh_sku} 冲突（{@code INVENTORY_WORKFLOW §8.3} /
 *       {@code DATABASE_DESIGN §6-C.6}）。<b>不用</b>「先查后插 + catch DuplicateKeyException」：
 *       异常控制流会与外层 NESTED 重试单元的事务边界互相干扰。</li>
 *   <li>{@link #lockAnchorSkuIds}：{@code F-03}「同口径锚点行锁」要求 {@code SELECT ... FOR UPDATE}
 *       且按 {@code sku_id} 升序（防死锁）。{@code BaseMapper} 无法表达 {@code FOR UPDATE}。</li>
 * </ul>
 */
@Mapper
public interface InventoryBalanceMapper extends BaseMapper<InventoryBalanceEntity> {

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

    /**
     * 跨团队锚点行锁（M6-T02 转采购冲突检测；{@code DATABASE_DESIGN §7.2 #8} 步骤 ②）。
     *
     * <p>与既有 {@link #lockAnchorSkuIds} 的区别：那个按 {@code (warehouse_id, sku_id)} 锁
     * <b>本团队</b>的行（库存调整路径用）；本方法只按 {@code sku_id} <b>升序</b>锁全部团队的余额行，
     * 用于「同一 SKU 的转采购冲突检测」必须跨团队串行（否则两个不同团队并发转采购同一 SKU 时，
     * 各自只锁自己团队的余额行 ⇒ 检测与写入之间仍有 TOCTOU 窗口）。
     *
     * <p>★ {@code @InterceptorIgnore(tenantLine = "true")} 的理由（与
     * {@code AnalysisRunMapper#selectInFlightAcrossTeams} 同源）：本语句<b>故意</b>跨团队锁行，
     * 加租户线会把锁范围缩回本团队，跨团队并发就失去互斥；
     * 已同步登记到 {@code M2_TenantIsolationDbIntegrationTest.TENANT_LINE_EXCEPTIONS}。
     *
     * <p>★ 本方法自带<b>显式 SQL</b>（否则会落到 {@code BaseMapper} 默认实现，完全失去隔离语义——
     * M2 测试对此有断言）。
     *
     * <p>{@code ORDER BY sku_id ASC} 是<b>防死锁</b>的固定加锁序（{@code §7.2 #8} ②）。
     * 不存在的 SKU 不会被返回（无可锁之行）；调用方据此得出 {@code missingSkuIds}。
     *
     * @param skuIds 待锁 SKU（升序由 SQL 保证）
     * @return 实际锁到的 {@code sku_id}
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT sku_id FROM inventory_balances WHERE sku_id IN "
            + "<foreach collection='skuIds' item='skuId' open='(' separator=',' close=')'>#{skuId}</foreach> "
            + "ORDER BY sku_id ASC FOR UPDATE"
            + "</script>")
    List<Long> lockCrossTeamAnchorSkuIds(@Param("skuIds") List<Long> skuIds);
}
