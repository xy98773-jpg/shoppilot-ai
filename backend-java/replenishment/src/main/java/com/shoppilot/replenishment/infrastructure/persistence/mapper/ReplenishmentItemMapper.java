package com.shoppilot.replenishment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentItemEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code replenishment_items} 表 Mapper（{@code DATABASE_DESIGN §5.16} / 迁移 {@code V1.0.10}）。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}；租户线自动追加 {@code AND team_id = <当前团队>}
 * （SELECT/UPDATE/DELETE）并在 INSERT 时补 {@code team_id}（从 {@code TeamContext} 取）。
 *
 * <p>★ <b>唯一的 {@code @InterceptorIgnore} 例外</b>：{@link #lockApprovedAnchorItemIds}
 * （转采购冲突检测的降级锚点行锁，M6-T02）。它自带显式 {@code team_id}/{@code warehouse_id}
 * 条件（租户线会重复追加等值谓词 {@code team_id = ?}），故加 {@code @InterceptorIgnore(tenantLine = "true")}，
 * 已登记 {@code M2_TenantIsolationDbIntegrationTest.TENANT_LINE_EXCEPTIONS}。其余方法一律不加。
 *
 * <p>★ <b>更新口径</b>：本表实体带 {@code @Version}。普通更新必须 {@code updateById(entity)}
 * 并判返回值（0 行 ⇒ 乐观锁冲突）；<b>禁止</b> {@code update(null, wrapper)} 做覆盖写——那会绕过
 * {@code @Version}（{@code R62}）。{@code markConverted} 的 CAS 式 wrapper 更新（把
 * {@code version} 作为等值条件 + 显式自增，等价于 CAS，不构成绕过）属有据可依的例外，见
 * {@code ReplenishmentAppService#markConverted} 的 Javadoc。
 */
@Mapper
public interface ReplenishmentItemMapper extends BaseMapper<ReplenishmentItemEntity> {

    /**
     * 「已审批建议」锚点行锁（M6-T02 转采购<b>降级锁</b>；{@code DATABASE_DESIGN §7.2 #8} 步骤 ②b；
     * 契约 {@code MODULE_BOUNDARIES.md:245–246}）。
     *
     * <p><b>何时用</b>：步骤 ② 的 {@code inventory_balances} 锚点缺失（该 SKU 在<b>本团队本仓库</b>
     * 没有余额行、无行可锁）时，降级改为锁本表 {@code APPROVED} 行——「同 SKU×仓库是否还有别的
     * 已审批建议」这个判定的输入就在本表，锁它等价于锁住判定的输入。
     *
     * <p>★ <b>SQL 谓词段契约一字不改</b>：{@code WHERE team_id=? AND warehouse_id=? AND sku_id IN (…)
     * AND status='APPROVED' ORDER BY sku_id ASC FOR UPDATE}。
     *
     * <p>★ <b>为什么 {@code FORCE INDEX (idx_repl_team_wh_sku)}</b>（M6-T02-E / R169 闭环，
     * 依据 {@code DESIGN_REVIEW §5.69.2} 的 9 组实测）：
     * <ol>
     *   <li><b>这是正确性不变量，不是性能优化</b>：{@code ORDER BY sku_id ASC} 的确定性加锁序是
     *       死锁预防的硬不变量（{@code DATABASE_CONSTRAINTS §7.3} ①）。实测（组 E，2 行小数据量）
     *       优化器会改选更窄的 {@code idx_repl_team_status_time} 并对 {@code sku_id}
     *       <b>filesort</b> ⇒ 锁序失效 ⇒ 多 SKU 并发转采购可能死锁。<b>不应依赖优化器的成本估算</b>；
     *       {@code FORCE INDEX} 在 2 行与 20 000 行两种规模下均确定性无 filesort（组 F/H）。</li>
     *   <li><b>性能等价</b>：20 000 行下自然选择（组 G）与 FORCE INDEX（组 H）的 key/rows/Extra
     *       完全一致（均为 {@code idx_repl_team_wh_sku}, rows=3, 无 filesort）⇒ 无代价。</li>
     *   <li><b>fail-fast 语义</b>：索引若被误删/误改，SQL 直接 {@code ERROR 1176} 报错（组 I）
     *       而非静默降级全表扫描 ⇒ 适合承载不变量。</li>
     * </ol>
     *
     * <p>★ {@code @InterceptorIgnore(tenantLine = "true")}：本 SQL 已自带显式 {@code team_id}，
     * 租户线会重复追加等值谓词 {@code team_id = ?} ⇒ 必须忽略租户线；已登记白名单（理由见类 Javadoc）。
     *
     * <p>★ 该方法无副作用：只加锁、不写数据、不写审计。
     *
     * @param teamId      团队 ID
     * @param warehouseId 仓库 ID
     * @param skuIds      SKU 集合（升序由 SQL 保证）
     * @return 实际锁到的建议 {@code id} 列表（可能为空：无匹配的 APPROVED 行）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT id FROM replenishment_items FORCE INDEX (idx_repl_team_wh_sku) "
            + "WHERE team_id = #{teamId} AND warehouse_id = #{warehouseId} "
            + "AND sku_id IN "
            + "<foreach collection='skuIds' item='skuId' open='(' separator=',' close=')'>#{skuId}</foreach> "
            + "AND status = 'APPROVED' "
            + "ORDER BY sku_id ASC FOR UPDATE"
            + "</script>")
    List<Long> lockApprovedAnchorItemIds(@Param("teamId") long teamId,
                                         @Param("warehouseId") long warehouseId,
                                         @Param("skuIds") List<Long> skuIds);
}
