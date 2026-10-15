package com.shoppilot.identity.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.identity.infrastructure.persistence.entity.RefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code refresh_tokens} 表 Mapper。
 *
 * <p>本表无 {@code team_id}（{@code DESIGN_BASIS §6} 规则 1 第 3 张例外表，R15 闭环），
 * 已被登记进 {@code TenantTables.WITHOUT_TEAM_ID}，<b>不会被追加租户条件</b>。
 * 若漏登记，登出/刷新的按摘要查询会变成 {@code WHERE token_hash = ? AND team_id = ?}
 * 而永远查不到行——<b>登出静默失效</b>（不报错、只是不生效）。
 */
@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenEntity> {
}
