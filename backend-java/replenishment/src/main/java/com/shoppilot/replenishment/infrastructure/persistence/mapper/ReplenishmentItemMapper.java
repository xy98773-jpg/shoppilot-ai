package com.shoppilot.replenishment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentItemEntity;
import org.apache.ibatis.annotations.Mapper;

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
}
