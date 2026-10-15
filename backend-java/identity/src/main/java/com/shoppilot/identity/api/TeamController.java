package com.shoppilot.identity.api;

import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.identity.application.TeamMemberService;
import com.shoppilot.identity.application.TeamService;
import com.shoppilot.identity.application.dto.AddTeamMemberRequest;
import com.shoppilot.identity.application.dto.TeamDetail;
import com.shoppilot.identity.application.dto.TeamMemberSummary;
import com.shoppilot.identity.application.dto.TeamMembershipSummary;
import com.shoppilot.identity.application.dto.UpdateTeamMemberRoleRequest;
import com.shoppilot.identity.application.dto.UpdateTeamRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 团队与成员端点（M2-T04 成员管理 + M2-T05 团队配置）。
 *
 * <p>覆盖 {@code API_CONTRACT §10.2} 全部 7 条路径：
 * <ul>
 *   <li>{@code GET    /api/v1/teams}                             —— 我的团队列表（任何已登录用户）</li>
 *   <li>{@code GET    /api/v1/teams/{teamId}}                    —— 团队配置查询（任何已登录成员）</li>
 *   <li>{@code PATCH  /api/v1/teams/{teamId}}                    —— 团队配置修改（OWNER/ADMIN）</li>
 *   <li>{@code GET    /api/v1/teams/{teamId}/members}            —— 成员列表（OWNER/ADMIN）</li>
 *   <li>{@code POST   /api/v1/teams/{teamId}/members}            —— 添加成员（OWNER/ADMIN）</li>
 *   <li>{@code PATCH  /api/v1/teams/{teamId}/members/{userId}}   —— 修改成员角色（OWNER/ADMIN）</li>
 *   <li>{@code DELETE /api/v1/teams/{teamId}/members/{userId}}   —— 移出成员，软删（OWNER/ADMIN）</li>
 * </ul>
 *
 * <p><b>{@code {teamId}} 是资源定位符</b>，不是「客户端选择团队」的入口：服务端校验
 * {@code {teamId} == JWT.team_id}，不符 → {@code TEAM-2001}（{@code API_CONTRACT §5.3} /
 * {@code USER_ROLES §6.4}）。授权判定在服务层按 {@code USER_ROLES §2.2} 矩阵<b>角色名直查</b>。
 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamService teamService;
    private final TeamMemberService teamMemberService;

    public TeamController(TeamService teamService, TeamMemberService teamMemberService) {
        this.teamService = teamService;
        this.teamMemberService = teamMemberService;
    }

    /** 我的团队列表（{@code GET /api/v1/teams}）。 */
    @GetMapping
    public ApiResponse<List<TeamMembershipSummary>> listMyTeams() {
        return ApiResponse.success(teamService.listMyTeams());
    }

    /** 团队配置查询（{@code GET /api/v1/teams/{teamId}}）。 */
    @GetMapping("/{teamId}")
    public ApiResponse<TeamDetail> getTeam(@PathVariable("teamId") Long teamId) {
        return ApiResponse.success(teamService.getTeam(teamId));
    }

    /** 团队配置修改（{@code PATCH /api/v1/teams/{teamId}}，{@code OWNER}/{@code ADMIN}）。 */
    @PatchMapping("/{teamId}")
    public ApiResponse<TeamDetail> updateTeam(@PathVariable("teamId") Long teamId,
                                              @Valid @RequestBody UpdateTeamRequest request) {
        return ApiResponse.success(teamService.updateTeam(teamId, request));
    }

    /** 成员列表（{@code GET /api/v1/teams/{teamId}/members}，{@code OWNER}/{@code ADMIN}）。 */
    @GetMapping("/{teamId}/members")
    public ApiResponse<List<TeamMemberSummary>> listMembers(@PathVariable("teamId") Long teamId) {
        return ApiResponse.success(teamMemberService.listMembers(teamId));
    }

    /** 添加成员（{@code POST /api/v1/teams/{teamId}/members}，{@code OWNER}/{@code ADMIN}）。 */
    @PostMapping("/{teamId}/members")
    public ApiResponse<TeamMemberSummary> addMember(@PathVariable("teamId") Long teamId,
                                                    @Valid @RequestBody AddTeamMemberRequest request) {
        return ApiResponse.success(teamMemberService.addMember(teamId, request));
    }

    /** 修改成员角色（{@code PATCH /api/v1/teams/{teamId}/members/{userId}}）。 */
    @PatchMapping("/{teamId}/members/{userId}")
    public ApiResponse<TeamMemberSummary> updateMemberRole(
            @PathVariable("teamId") Long teamId,
            @PathVariable("userId") Long userId,
            @Valid @RequestBody UpdateTeamMemberRoleRequest request) {
        return ApiResponse.success(teamMemberService.updateMemberRole(teamId, userId, request));
    }

    /** 移出成员（{@code DELETE /api/v1/teams/{teamId}/members/{userId}}，软删）。 */
    @DeleteMapping("/{teamId}/members/{userId}")
    public ApiResponse<TeamMemberSummary> removeMember(@PathVariable("teamId") Long teamId,
                                                       @PathVariable("userId") Long userId) {
        return ApiResponse.success(teamMemberService.removeMember(teamId, userId));
    }
}
