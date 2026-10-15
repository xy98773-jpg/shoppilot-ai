package com.shoppilot.identity.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.tenant.TeamScopeValidator;
import com.shoppilot.common.web.BizException;
import com.shoppilot.identity.application.dto.AddTeamMemberRequest;
import com.shoppilot.identity.application.dto.TeamMemberSummary;
import com.shoppilot.identity.application.dto.UpdateTeamMemberRoleRequest;
import com.shoppilot.identity.application.dto.UserListItem;
import com.shoppilot.identity.domain.model.MemberStatus;
import com.shoppilot.identity.domain.model.TeamRole;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamEntity;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamMemberEntity;
import com.shoppilot.identity.infrastructure.persistence.entity.UserEntity;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMemberMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.UserMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 团队成员管理应用服务（M2-T04）：成员列表 / 添加 / 改角色 / 移出，以及选人用户列表。
 *
 * <h2>三条最易做错的规则（★ 本类的实现口径）</h2>
 * <ol>
 *   <li><b>禁止移除 OWNER</b>：{@code USER_ROLES §2.2} #12 备注「{@code ADMIN} 可管理成员，
 *       但<b>不能移除 {@code OWNER}</b>」→ {@code TEAM-2006}（403）。</li>
 *   <li><b>移出是软删</b>：{@code team_members.status = 'REMOVED'}，
 *       <b>绝不物理删除行</b>（{@code DESIGN_BASIS §9.3}「保留历史记录」）。</li>
 *   <li><b>角色判定矩阵直查</b>：{@code OWNER ⊃ ADMIN ⊃ APPROVER ⊃ OPERATOR ⊃ VIEWER}
 *       只是能力包含关系，判定按<b>角色名</b>显式列举，<b>禁止按权限表列位置/序数比较</b>
 *       （{@code USER_ROLES §1.1}，F-40 教训）。</li>
 * </ol>
 *
 * <h2>判定顺序（{@code USER_ROLES §4.1}）</h2>
 * 团队归属（{@code TEAM-2001}）→ 团队状态（{@code TEAM-2002}）→ 角色授权（{@code TEAM-2003}）
 * → 业务规则（{@code TEAM-2004}/{@code 2005}/{@code 2006}/{@code 2007}/{@code 2009}）。
 *
 * <h2>租户线（{@code DESIGN_BASIS §6} 规则 4/5）</h2>
 * {@code team_members} 含 {@code team_id}，{@code TenantLineInnerInterceptor} 会自动追加
 * {@code AND team_id = <当前团队>}（SELECT/UPDATE/DELETE）并补齐 INSERT 的 {@code team_id} 列。
 * 故本类<b>不再手写多余的 {@code team_id} 条件</b>，避免重复；INSERT 仍显式设值以策安全。
 */
@Service
public class TeamMemberService {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberService.class);

    /** 允许「成员管理」的角色（{@code USER_ROLES §2.2} #12，按角色名直查）。 */
    private static final List<String> MANAGE_MEMBERS_ROLES = List.of("OWNER", "ADMIN");

    private static final String ROLE_OWNER = TeamRole.OWNER.name();

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final UserMapper userMapper;

    public TeamMemberService(TeamMapper teamMapper,
                             TeamMemberMapper teamMemberMapper,
                             UserMapper userMapper) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.userMapper = userMapper;
    }

    // ---------------------------------------------------------------------
    // 成员列表
    // ---------------------------------------------------------------------

    /** 成员列表（{@code GET /api/v1/teams/{teamId}/members}，{@code OWNER}/{@code ADMIN}）。 */
    public List<TeamMemberSummary> listMembers(Long pathTeamId) {
        assertTeamScope(pathTeamId);
        TeamScopeValidator.assertAnyRole(MANAGE_MEMBERS_ROLES);

        // 不手写 team_id：租户线自动追加 AND team_id = <当前团队>
        List<TeamMemberEntity> rows = teamMemberMapper.selectList(
                Wrappers.<TeamMemberEntity>lambdaQuery().orderByAsc(TeamMemberEntity::getId));
        return toSummaries(rows);
    }

    // ---------------------------------------------------------------------
    // 添加成员
    // ---------------------------------------------------------------------

    /**
     * 添加成员（{@code POST /api/v1/teams/{teamId}/members}，{@code OWNER}/{@code ADMIN}）。
     *
     * <p>已是 ACTIVE 成员 → {@code TEAM-2005}（409）；新增第二名 {@code OWNER} → {@code TEAM-2007}（409）。
     * 曾是成员（{@code REMOVED}）时<b>复活同一行</b>（{@code uk_tm_team_user} 保证一人一行），
     * 并清空 {@code removed_at}、更新 {@code role}。
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamMemberSummary addMember(Long pathTeamId, AddTeamMemberRequest request) {
        TeamEntity team = assertTeamScope(pathTeamId);
        TeamScopeValidator.assertAnyRole(MANAGE_MEMBERS_ROLES);

        UserEntity user = userMapper.selectById(request.userId());
        if (user == null) {
            throw new BizException(ErrorCodes.AUTH_1004, "用户不存在");
        }
        String role = normalizeRole(request.role());

        TeamMemberEntity existing = teamMemberMapper.selectOne(
                Wrappers.<TeamMemberEntity>lambdaQuery().eq(TeamMemberEntity::getUserId, user.getId()));
        if (existing != null && MemberStatus.ACTIVE.name().equals(existing.getStatus())) {
            throw new BizException(ErrorCodes.TEAM_2005, "该用户已是团队成员");
        }
        if (ROLE_OWNER.equals(role)) {
            // 每团队恰好 1 名 OWNER（DESIGN_BASIS §5）；DB 无法表达部分唯一，故事务内校验
            assertNoActiveOwner();
        }

        if (existing == null) {
            TeamMemberEntity entity = new TeamMemberEntity();
            entity.setTeamId(team.getId()); // 显式设值（租户线亦会补齐，双保险）
            entity.setUserId(user.getId());
            entity.setRole(role);
            entity.setStatus(MemberStatus.ACTIVE.name());
            entity.setJoinedAt(nowUtc());
            teamMemberMapper.insert(entity);
        } else {
            // 复活：复用同一行，清空 removed_at（removed_at 需显式 set null，updateById 会忽略 null）
            teamMemberMapper.update(null, Wrappers.<TeamMemberEntity>lambdaUpdate()
                    .eq(TeamMemberEntity::getId, existing.getId())
                    .set(TeamMemberEntity::getRole, role)
                    .set(TeamMemberEntity::getStatus, MemberStatus.ACTIVE.name())
                    .set(TeamMemberEntity::getRemovedAt, null));
        }

        log.info("成员已添加 teamId={} targetUserId={} role={} operator={}", team.getId(), user.getId(), role,
                TeamScopeValidator.requireContext().userId());
        return new TeamMemberSummary(user.getId(), user.getUsername(), user.getDisplayName(), role,
                MemberStatus.ACTIVE.name());
    }

    // ---------------------------------------------------------------------
    // 修改成员角色
    // ---------------------------------------------------------------------

    /**
     * 修改成员角色（{@code PATCH /api/v1/teams/{teamId}/members/{userId}}，{@code OWNER}/{@code ADMIN}）。
     *
     * <p>把 {@code OWNER} 改为非 {@code OWNER} → {@code TEAM-2006}（等价于移除所有者，会致团队无 OWNER）；
     * 目标成员不存在/已移除 → {@code TEAM-2004}（404）；升为第二名 {@code OWNER} → {@code TEAM-2007}（409）。
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamMemberSummary updateMemberRole(Long pathTeamId, Long targetUserId,
                                              UpdateTeamMemberRoleRequest request) {
        TeamEntity team = assertTeamScope(pathTeamId);
        TeamScopeValidator.assertAnyRole(MANAGE_MEMBERS_ROLES);

        TeamMemberEntity membership = requireActiveMembership(targetUserId);
        String newRole = normalizeRole(request.role());

        if (ROLE_OWNER.equals(membership.getRole()) && !ROLE_OWNER.equals(newRole)) {
            // 不能移除团队所有者（含「所有者把自己降级」——同样会致团队无 OWNER）
            throw new BizException(ErrorCodes.TEAM_2006, "不能移除团队所有者");
        }
        if (ROLE_OWNER.equals(newRole) && !ROLE_OWNER.equals(membership.getRole())) {
            assertNoActiveOwner();
        }

        teamMemberMapper.update(null, Wrappers.<TeamMemberEntity>lambdaUpdate()
                .eq(TeamMemberEntity::getId, membership.getId())
                .set(TeamMemberEntity::getRole, newRole));

        UserEntity user = userMapper.selectById(targetUserId);
        log.info("成员角色已更新 teamId={} targetUserId={} newRole={} operator={}", team.getId(), targetUserId,
                newRole, TeamScopeValidator.requireContext().userId());
        return new TeamMemberSummary(targetUserId,
                user == null ? null : user.getUsername(),
                user == null ? null : user.getDisplayName(),
                newRole, MemberStatus.ACTIVE.name());
    }

    // ---------------------------------------------------------------------
    // 移出成员（软删）
    // ---------------------------------------------------------------------

    /**
     * 移出成员（{@code DELETE /api/v1/teams/{teamId}/members/{userId}}，{@code OWNER}/{@code ADMIN}）。
     *
     * <p><b>软删</b>：仅把 {@code team_members.status} 置为 {@code REMOVED} 并写 {@code removed_at}，
     * <b>保留行</b>（{@code DESIGN_BASIS §9.3}）。移出自己 → {@code TEAM-2009}（403）；
     * 目标是 {@code OWNER} → {@code TEAM-2006}（403）。
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamMemberSummary removeMember(Long pathTeamId, Long targetUserId) {
        TeamEntity team = assertTeamScope(pathTeamId);
        TeamScopeValidator.assertAnyRole(MANAGE_MEMBERS_ROLES);
        Long operatorId = TeamScopeValidator.requireContext().userId();

        TeamMemberEntity membership = requireActiveMembership(targetUserId);

        if (targetUserId.equals(operatorId)) {
            throw new BizException(ErrorCodes.TEAM_2009, "不能将自己移出团队");
        }
        if (ROLE_OWNER.equals(membership.getRole())) {
            throw new BizException(ErrorCodes.TEAM_2006, "不能移除团队所有者");
        }

        teamMemberMapper.update(null, Wrappers.<TeamMemberEntity>lambdaUpdate()
                .eq(TeamMemberEntity::getId, membership.getId())
                .set(TeamMemberEntity::getStatus, MemberStatus.REMOVED.name())
                .set(TeamMemberEntity::getRemovedAt, nowUtc()));

        UserEntity user = userMapper.selectById(targetUserId);
        log.info("成员已移出（软删） teamId={} targetUserId={} operator={}", team.getId(), targetUserId, operatorId);
        return new TeamMemberSummary(targetUserId,
                user == null ? null : user.getUsername(),
                user == null ? null : user.getDisplayName(),
                membership.getRole(), MemberStatus.REMOVED.name());
    }

    // ---------------------------------------------------------------------
    // 用户列表（选人）
    // ---------------------------------------------------------------------

    /**
     * 用户列表（{@code GET /api/v1/users}，{@code OWNER}/{@code ADMIN}，契约 §10.1「用于成员管理选人」）。
     *
     * <p>{@code users} 为 {@code DESIGN_BASIS §6} 规则 1 例外表（无 {@code team_id}），不受租户线影响，
     * 返回全局用户供选择加入当前团队。
     */
    public List<UserListItem> listUsers() {
        // 判定顺序第 5 步：团队状态（团队停用时整个团队不可用 → TEAM-2002）
        TeamContext.Snapshot snapshot = TeamScopeValidator.requireContext();
        TeamEntity team = teamMapper.selectById(snapshot.teamId());
        if (team != null) {
            TeamScopeValidator.assertTeamActive(team.getStatus());
        }
        // 判定顺序第 7 步：角色授权（矩阵 #12）
        TeamScopeValidator.assertAnyRole(MANAGE_MEMBERS_ROLES);

        return userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                        .orderByAsc(UserEntity::getUsername))
                .stream()
                .map(u -> new UserListItem(u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus()))
                .toList();
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /**
     * 判定顺序第 4/5 步：路径 {@code {teamId}} 归属（{@code TEAM-2001}）+ 团队存在与 {@code ACTIVE}
     * （{@code TEAM-2002}）。
     */
    private TeamEntity assertTeamScope(Long pathTeamId) {
        TeamScopeValidator.assertPathTeam(pathTeamId);
        TeamEntity team = teamMapper.selectById(pathTeamId);
        if (team == null) {
            throw new BizException(ErrorCodes.COM_0003, "您访问的资源不存在或已被移除");
        }
        TeamScopeValidator.assertTeamActive(team.getStatus());
        return team;
    }

    /**
     * 取当前团队内的 <b>{@code ACTIVE}</b> 成员关系；不存在或已移出 → {@code TEAM-2004}（404）。
     *
     * <p>查询不手写 {@code team_id}——租户线自动追加 {@code AND team_id = <当前团队>}，
     * 因此「他人团队的成员」天然查不到，与「不存在」表现一致（防枚举，{@code API_CONTRACT §5.6}）。
     */
    private TeamMemberEntity requireActiveMembership(Long userId) {
        TeamMemberEntity membership = teamMemberMapper.selectOne(
                Wrappers.<TeamMemberEntity>lambdaQuery().eq(TeamMemberEntity::getUserId, userId));
        if (membership == null || !MemberStatus.ACTIVE.name().equals(membership.getStatus())) {
            throw new BizException(ErrorCodes.TEAM_2004, "该成员不存在");
        }
        return membership;
    }

    /** 每团队恰好 1 名 {@code OWNER}（{@code DESIGN_BASIS §5}）：已存在 ACTIVE {@code OWNER} → {@code TEAM-2007}。 */
    private void assertNoActiveOwner() {
        Long owners = teamMemberMapper.selectCount(Wrappers.<TeamMemberEntity>lambdaQuery()
                .eq(TeamMemberEntity::getRole, ROLE_OWNER)
                .eq(TeamMemberEntity::getStatus, MemberStatus.ACTIVE.name()));
        if (owners != null && owners > 0) {
            throw new BizException(ErrorCodes.TEAM_2007, "团队所有者只能有一名");
        }
    }

    /** Bean Validation 已在入参收敛取值，这里再以枚举兜底（防御性）。 */
    private static String normalizeRole(String role) {
        return TeamRole.from(role)
                .orElseThrow(() -> new BizException(ErrorCodes.COM_0001, "角色取值必须为 TeamRole"))
                .name();
    }

    private List<TeamMemberSummary> toSummaries(List<TeamMemberEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = rows.stream().map(TeamMemberEntity::getUserId).distinct().toList();
        Map<Long, UserEntity> users = new LinkedHashMap<>();
        for (UserEntity user : userMapper.selectBatchIds(userIds)) {
            users.put(user.getId(), user);
        }
        List<TeamMemberSummary> summaries = new ArrayList<>(rows.size());
        for (TeamMemberEntity row : rows) {
            UserEntity user = users.get(row.getUserId());
            summaries.add(new TeamMemberSummary(row.getUserId(),
                    user == null ? null : user.getUsername(),
                    user == null ? null : user.getDisplayName(),
                    row.getRole(), row.getStatus()));
        }
        return summaries;
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
