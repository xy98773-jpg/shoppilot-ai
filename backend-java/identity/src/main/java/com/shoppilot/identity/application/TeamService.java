package com.shoppilot.identity.application;

import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.tenant.TeamScopeValidator;
import com.shoppilot.common.web.BizException;
import com.shoppilot.identity.application.dto.TeamDetail;
import com.shoppilot.identity.application.dto.TeamMembershipSummary;
import com.shoppilot.identity.application.dto.UpdateTeamRequest;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamEntity;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamMemberEntity;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMemberMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 团队配置应用服务（M2-T05：{@code GET /api/v1/teams}、{@code GET|PATCH /api/v1/teams/{teamId}}）。
 *
 * <h2>判定顺序（{@code USER_ROLES §4.1}，固定不得调换）</h2>
 * <pre>
 * 认证(AUTH-1001，过滤器) → 用户状态(AUTH-1003，过滤器) → 团队解析
 * → 团队归属(TEAM-2001) → 团队状态(TEAM-2002) → 成员状态/角色授权(TEAM-2003) → 业务规则
 * </pre>
 * 具体到本类：{@link TeamScopeValidator#assertPathTeam}（第 4 步）→ {@link #requireActiveTeam}
 * （第 5 步）→ {@link TeamScopeValidator#assertAnyRole}（第 7 步）。
 *
 * <h2>授权口径（★ 矩阵直查，非继承链）</h2>
 * 「团队配置」= {@code USER_ROLES §2.2} 矩阵第 <b>13</b> 行 = {@code OWNER}/{@code ADMIN}。
 * 按<b>角色名</b>判定；{@code OWNER ⊃ ADMIN} 只是能力包含关系，<b>不采用继承链</b>
 * （{@code USER_ROLES §1.1} 明文）。
 *
 * <h2>依赖缺口（诚实登记）</h2>
 * {@code PATCH} 的 {@code default_warehouse_id} 按契约 §10.2 应校验「关联对象不属于同一团队 → CAT-3005」，
 * 但 {@code warehouses} 表<b>尚未迁移</b>（属 M2-T08 catalog 批次），本批<b>无法</b>落地该同团队校验，
 * 故仅按契约接受并持久化该字段。待 T08 落地后补 {@code CAT-3005}。
 */
@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    /** 允许「团队配置」的角色（{@code USER_ROLES §2.2} #13，按角色名）。 */
    private static final List<String> MANAGE_TEAM_CONFIG_ROLES = List.of("OWNER", "ADMIN");

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;

    public TeamService(TeamMapper teamMapper, TeamMemberMapper teamMemberMapper) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    /**
     * 当前用户所属团队列表（{@code GET /api/v1/teams}，任何已登录用户）。
     *
     * <p>用 {@link TeamMemberMapper#selectActiveMemberships} 取「我的全部 ACTIVE 成员关系」——
     * 该查询<b>绕过租户线</b>（否则会被限制到当前单一团队），但其 {@code userId} 参数来自服务端上下文。
     * 返回项含每个团队的 {@code role}（团队作用域角色，{@code USER_ROLES §6.1}）。
     */
    public List<TeamMembershipSummary> listMyTeams() {
        TeamContext.Snapshot snapshot = TeamScopeValidator.requireContext();
        List<TeamMemberEntity> memberships = teamMemberMapper.selectActiveMemberships(snapshot.userId());
        List<TeamMembershipSummary> result = new ArrayList<>(memberships.size());
        for (TeamMemberEntity membership : memberships) {
            TeamEntity team = teamMapper.selectById(membership.getTeamId());
            if (team != null) {
                result.add(new TeamMembershipSummary(team.getId(), team.getName(), team.getStatus(),
                        membership.getRole()));
            }
        }
        return result;
    }

    /**
     * 团队配置查询（{@code GET /api/v1/teams/{teamId}}，任何已登录成员）。
     *
     * <p>{@code {teamId}} 是资源定位符：与 JWT 不符 → {@code TEAM-2001}（{@code API_CONTRACT §5.3}）。
     */
    public TeamDetail getTeam(Long pathTeamId) {
        TeamScopeValidator.assertPathTeam(pathTeamId);
        return toDetail(requireActiveTeam(pathTeamId));
    }

    /**
     * 团队配置修改（{@code PATCH /api/v1/teams/{teamId}}，{@code OWNER}/{@code ADMIN}）。
     *
     * <p>{@code null} 字段表示不修改（PATCH 语义）。
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamDetail updateTeam(Long pathTeamId, UpdateTeamRequest request) {
        TeamScopeValidator.assertPathTeam(pathTeamId);
        TeamEntity team = requireActiveTeam(pathTeamId);
        TeamScopeValidator.assertAnyRole(MANAGE_TEAM_CONFIG_ROLES);

        TeamEntity patch = new TeamEntity();
        patch.setId(team.getId());
        if (request.name() != null) {
            patch.setName(request.name());
        }
        if (request.defaultTimezone() != null) {
            patch.setDefaultTimezone(request.defaultTimezone());
        }
        // ★ 契约 §10.2 要求校验 default_warehouse_id 同团队 → CAT-3005；
        //    warehouses 表尚未迁移（M2-T08），故此处仅持久化，见类 javadoc「依赖缺口」。
        if (request.defaultWarehouseId() != null) {
            patch.setDefaultWarehouseId(request.defaultWarehouseId());
        }
        teamMapper.updateById(patch);

        log.info("团队配置已更新 teamId={} operator={}", team.getId(),
                TeamScopeValidator.requireContext().userId());
        return toDetail(teamMapper.selectById(team.getId()));
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /**
     * 判定顺序第 5 步：团队存在 + {@code ACTIVE}。
     *
     * <p>不存在 → {@code COM-0003}（404）；已停用 → {@code TEAM-2002}（403）。
     */
    private TeamEntity requireActiveTeam(Long teamId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null) {
            throw new BizException(ErrorCodes.COM_0003, "您访问的资源不存在或已被移除");
        }
        TeamScopeValidator.assertTeamActive(team.getStatus());
        return team;
    }

    private static TeamDetail toDetail(TeamEntity team) {
        return new TeamDetail(team.getId(), team.getName(), team.getStatus(),
                team.getDefaultTimezone(), team.getDefaultWarehouseId());
    }
}
