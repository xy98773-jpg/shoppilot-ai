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
 * <p>★ <b>不加 {@code @InterceptorIgnore}</b>（本模块无跨团队后台任务，M5-T01 无查询需求）；
 * ★ <b>不写自定义 SQL</b>（本批只落地数据模型）。
 *
 * <p>★ 更新口径（{@code R62} / B23）：本表实体带 {@code @Version}，
 * <b>必须</b>{@code updateById(entity)} 并判返回值（0 行 ⇒ {@code COM-0015}）；
 * <b>禁止</b> {@code update(null, wrapper)}——那会绕过乐观锁。
 */
@Mapper
public interface ReplenishmentItemMapper extends BaseMapper<ReplenishmentItemEntity> {

    /**
     * 跨团队「已审批建议」锚点行锁（M6-T02 转采购<b>降级锁</b>；{@code DATABASE_DESIGN §7.2 #8} 步骤 ②b）。
     *
     * <p><b>何时用</b>：步骤 ② 的 {@code inventory_balances} 锚点缺失（该 SKU 在任何团队都没有余额行）时，
     * 降级改为锁本表 {@code APPROVED} 行——「同 SKU×仓库是否还有别的已审批建议」这个判定的依据就在本表，
     * 锁它等价于锁住判定的输入（走 {@code idx_repl_team_sku_time}）。
     *
     * <p>★ {@code @InterceptorIgnore(tenantLine = "true")}：与
     * {@code InventoryBalanceMapper#lockCrossTeamAnchorSkuIds} 同源——冲突检测必须跨团队互斥，
     * 否则锁范围被租户线缩回本团队就失去意义；已登记白名单。
     *
     * <p>★ 显式 SQL（{@link Select}）+ {@code ORDER BY sku_id ASC}（固定加锁序，防死锁）。
     *
     * @param skuIds 待锁 SKU（升序由 SQL 保证）
     * @return 实际锁到的建议 {@code id}（可能为空：没有 APPROVED 行）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>"
            + "SELECT id FROM replenishment_items WHERE status = 'APPROVED' AND sku_id IN "
            + "<foreach collection='skuIds' item='skuId' open='(' separator=',' close=')'>#{skuId}</foreach> "
            + "ORDER BY sku_id ASC FOR UPDATE"
            + "</script>")
    List<Long> lockApprovedAnchorItemIds(@Param("skuIds") List<Long> skuIds);
}
