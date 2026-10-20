package com.shoppilot.replenishment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentReviewEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code replenishment_reviews} 表 Mapper（{@code DATABASE_DESIGN §5.17} / 迁移 {@code V1.0.10}）。
 *
 * <p><b>★ 仅 {@code insert}</b>：本表是追加式审核轨迹，永不 UPDATE / DELETE
 * （{@code DATABASE_DESIGN} P4 硬规则）。{@link BaseMapper} 虽提供其它方法，
 * 但业务代码<b>不得</b>调用 {@code updateById} / {@code update} / {@code delete*}。
 *
 * <p>租户线自动补 {@code team_id}、自动追加查询条件；<b>不加 {@code @InterceptorIgnore}</b>，
 * 不写自定义 SQL。
 */
@Mapper
public interface ReplenishmentReviewMapper extends BaseMapper<ReplenishmentReviewEntity> {
}
