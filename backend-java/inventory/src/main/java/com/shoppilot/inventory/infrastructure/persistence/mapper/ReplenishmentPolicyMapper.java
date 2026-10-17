package com.shoppilot.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.inventory.infrastructure.persistence.entity.ReplenishmentPolicyEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code replenishment_policies} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}（SELECT/UPDATE/DELETE）
 * 并在 INSERT 时补 {@code team_id} 列（从 {@code TeamContext} 取）。<b>本 Mapper 不含任何
 * {@code @InterceptorIgnore}</b>——upsert 的「冲突吸收」由应用层「先查后写」+ {@code uk_pol_team_sku_wh}
 * 唯一约束在并发时兜底（见 {@code InventoryPolicyService}），无需绕过租户插件。
 */
@Mapper
public interface ReplenishmentPolicyMapper extends BaseMapper<ReplenishmentPolicyEntity> {
}
