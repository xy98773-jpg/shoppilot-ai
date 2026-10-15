package com.shoppilot.identity.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.identity.infrastructure.persistence.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code users} 表 Mapper。
 *
 * <p>{@code users} 是 {@code DESIGN_BASIS §6} 规则 1 的显式例外表（无 {@code team_id}），
 * 故不会被 {@code TenantLineInnerInterceptor} 改写，**无需** {@code @InterceptorIgnore}。
 *
 * <p>仅承载 identity 域的持久化；登录名/邮箱查询用 {@code BaseMapper} + Wrapper 组合，
 * 不额外声明自定义方法（避免与租户线产生隐含耦合）。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
