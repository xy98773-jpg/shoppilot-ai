package com.shoppilot.identity.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code teams} 表 Mapper。
 *
 * <p>{@code teams} 自身即团队边界（无 {@code team_id} 列），是 §6 规则 1 的显式例外表，
 * 不会被租户线改写。
 */
@Mapper
public interface TeamMapper extends BaseMapper<TeamEntity> {
}
