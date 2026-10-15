package com.shoppilot.identity.application;

import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.tenant.TeamScopeValidator;
import com.shoppilot.common.web.BizException;
import com.shoppilot.identity.application.dto.UserSummary;
import com.shoppilot.identity.domain.model.UserStatus;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamMemberEntity;
import com.shoppilot.identity.infrastructure.persistence.entity.UserEntity;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMemberMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.UserMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户启停应用服务（M2-T03，{@code PATCH /api/v1/users/{userId}/status}）。
 *
 * <h2>授权口径（★ 两条硬约束）</h2>
 * <ol>
 *   <li><b>矩阵直查</b>：所需角色 {@code OWNER}/{@code ADMIN} 直接来自
 *       {@code USER_ROLES §2.2} 矩阵第 12 行「成员管理」——按<b>角色名</b>判定，
 *       <b>禁止按权限表列位置推断</b>（F-40 教训）。</li>
 *   <li><b>不采用继承链</b>：{@code OWNER ⊃ ADMIN} 只是能力包含关系，
 *       判定必须是「矩阵直查」（{@code USER_ROLES §1.1} 明文要求），故用
 *       {@link TeamScopeValidator#assertAnyRole} 显式列举允许角色名。</li>
 * </ol>
 *
 * <h2>禁用即时生效</h2>
 * 本服务只改 {@code users.status}；<b>下一个</b>请求由
 * {@code JwtAuthenticationFilter} 的授权判定第 2 步实时校验并返回 {@code AUTH-1003}（403）——
 * 无需吊销该用户的 Access Token，也不引入 jti 黑名单（{@code API_CONTRACT §5.5}）。
 */
@Service
public class UserStatusService {

    private static final Logger log = LoggerFactory.getLogger(UserStatusService.class);

    /** 允许执行「成员管理」的角色（{@code USER_ROLES §2.2} #12，按角色名）。 */
    private static final List<String> MANAGE_MEMBERS_ROLES =
            List.of("OWNER", "ADMIN");

    private final UserMapper userMapper;
    private final TeamMemberMapper teamMemberMapper;

    public UserStatusService(UserMapper userMapper, TeamMemberMapper teamMemberMapper) {
        this.userMapper = userMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    /**
     * 启用 / 禁用用户。
     *
     * <p>判定顺序：角色授权（{@code TEAM-2003}）→ 目标用户存在（{@code AUTH-1004}）
     * → 目标属于当前团队（{@code TEAM-2003}）→ 禁止禁用自己 / {@code ADMIN} 改动 {@code OWNER}
     * （{@code AUTH-1010}）。
     *
     * @param targetUserId 目标用户 ID（来自路径 {@code {userId}}）
     * @param status       目标状态（{@code ACTIVE} / {@code DISABLED}，已由 Bean Validation 收敛取值）
     */
    @Transactional(rollbackFor = Exception.class)
    public UserSummary updateStatus(Long targetUserId, String status) {
        TeamContext.Snapshot snapshot = TeamScopeValidator.requireContext();

        // 第 7 步：角色授权（矩阵直查，按角色名）
        TeamScopeValidator.assertAnyRole(MANAGE_MEMBERS_ROLES);

        // 目标用户必须存在（users 为全局表，无团队维度）
        UserEntity target = userMapper.selectById(targetUserId);
        if (target == null) {
            throw new BizException(ErrorCodes.AUTH_1004, "用户不存在");
        }

        // 目标必须属于当前团队：查询受租户线约束（团队维度双保险），查不到即越权
        TeamMemberEntity membership =
                teamMemberMapper.selectMembershipInTenant(snapshot.teamId(), targetUserId);
        if (membership == null) {
            throw new BizException(ErrorCodes.TEAM_2003, "当前角色无权执行该操作");
        }

        // AUTH-1010：不得禁用自己
        if (UserStatus.DISABLED.name().equals(status) && targetUserId.equals(snapshot.userId())) {
            throw new BizException(ErrorCodes.AUTH_1010, "您无权修改该用户状态");
        }
        // AUTH-1010：ADMIN 不得改动 OWNER（矩阵 #12「ADMIN 可管理成员，但不能移除 OWNER」的同类约束）
        boolean requesterIsOwner = snapshot.hasAnyRole(List.of("OWNER"));
        if ("OWNER".equals(membership.getRole()) && !requesterIsOwner) {
            throw new BizException(ErrorCodes.AUTH_1010, "您无权修改该用户状态");
        }

        UserEntity patch = new UserEntity();
        patch.setId(targetUserId);
        patch.setStatus(status);
        userMapper.updateById(patch);

        log.info("用户状态已更新 targetUserId={} status={} operator={}", targetUserId, status,
                snapshot.userId());

        return new UserSummary(target.getId(), target.getUsername(), target.getDisplayName(), status);
    }
}
