package com.shoppilot.identity.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamMemberEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code team_members} 表 Mapper。
 *
 * <h2>★ 为什么部分方法要 {@code @InterceptorIgnore(tenantLine = "true")}</h2>
 * 本表含 {@code team_id}，会被 {@code TenantLineInnerInterceptor} 自动追加
 * {@code AND team_id = <TeamContext.团队>}；而 {@code ShopPilotTenantLineHandler}
 * 在<b>上下文缺失时直接拒绝查询</b>（抛 {@code AUTH-1001}，不再退化为哨兵 {@code 0}）。
 *
 * <p>但认证流程天然存在「尚无团队上下文」的查询：
 * <ul>
 *   <li><b>登录</b>：必须先查出该用户属于哪些团队，才能选出团队并装配上下文；</li>
 *   <li><b>每请求成员状态校验</b>（授权链第 6 步）：必须先校验成员 ACTIVE，才敢装配上下文。</li>
 * </ul>
 * 这两类查询用带 {@code @InterceptorIgnore} 的重载方法绕过租户线，<b>并由 SQL 显式携带
 * {@code user_id} / {@code team_id} 参数</b>（团队维度仍由服务端参数决定，绝不来自客户端）。
 *
 * <p><b>注意</b>：{@link #selectMembershipInTenant} <b>不带</b> {@code @InterceptorIgnore}——
 * 它用于「已具备团队上下文」的场景（如 M2-T03 用户启停），租户线对其照常生效，
 * 与显式 {@code team_id} 条件形成 {@code DESIGN_BASIS §6} 规则 5 要求的<b>双保险</b>。
 */
@Mapper
public interface TeamMemberMapper extends BaseMapper<TeamMemberEntity> {

    /**
     * 取某用户的全部 ACTIVE 团队成员关系，按「最早加入」排序。
     *
     * <p>用于登录时选默认团队（{@code API_CONTRACT §11.1}：所属多个团队时「首期取最早加入的 ACTIVE 团队」）。
     * 无团队上下文，故绕过租户线。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, team_id, user_id, role, status, joined_at, removed_at "
            + "FROM team_members WHERE user_id = #{userId} AND status = 'ACTIVE' "
            + "ORDER BY joined_at ASC, id ASC")
    List<TeamMemberEntity> selectActiveMemberships(@Param("userId") Long userId);

    /**
     * 取用户在指定团队的成员关系（任意状态），<b>绕过租户线</b>。
     *
     * <p>用于每请求的成员状态实时校验（授权链第 6 步，此时上下文尚未装配）。
     * {@code @return} 无记录时返回 {@code null}。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, team_id, user_id, role, status, joined_at, removed_at "
            + "FROM team_members WHERE team_id = #{teamId} AND user_id = #{userId}")
    TeamMemberEntity selectByTeamAndUserNoTenant(@Param("teamId") Long teamId,
                                                 @Param("userId") Long userId);

    /**
     * 取用户在指定团队的成员关系，<b>受租户线约束</b>（团队维度双保险）。
     *
     * <p>仅用于已装配团队上下文的场景；租户线会自动追加 {@code AND team_id = <当前团队>}。
     */
    @Select("SELECT id, team_id, user_id, role, status, joined_at, removed_at "
            + "FROM team_members WHERE team_id = #{teamId} AND user_id = #{userId}")
    TeamMemberEntity selectMembershipInTenant(@Param("teamId") Long teamId,
                                              @Param("userId") Long userId);
}
