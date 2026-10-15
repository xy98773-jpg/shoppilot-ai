package com.shoppilot.identity.api;

import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.identity.application.TeamMemberService;
import com.shoppilot.identity.application.UserStatusService;
import com.shoppilot.identity.application.dto.UpdateUserStatusRequest;
import com.shoppilot.identity.application.dto.UserListItem;
import com.shoppilot.identity.application.dto.UserSummary;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端点：{@code GET /api/v1/users}（M2-T04 选人）+ {@code PATCH /api/v1/users/{userId}/status}
 * （M2-T03 用户启停），见 {@code API_CONTRACT §10.1}。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserStatusService userStatusService;
    private final TeamMemberService teamMemberService;

    public UserController(UserStatusService userStatusService, TeamMemberService teamMemberService) {
        this.userStatusService = userStatusService;
        this.teamMemberService = teamMemberService;
    }

    /**
     * 用户列表（用于成员管理选人，{@code OWNER}/{@code ADMIN}，契约 §10.1，主错误码 {@code TEAM-2003}）。
     */
    @GetMapping
    public ApiResponse<List<UserListItem>> listUsers() {
        return ApiResponse.success(teamMemberService.listUsers());
    }

    /**
     * 启用 / 禁用用户（{@code OWNER}/{@code ADMIN}）。
     *
     * <p>权限判定按 {@code USER_ROLES §2.2} 矩阵<b>角色名直查</b>（#12 成员管理），
     * 不采用继承链（{@code USER_ROLES §1.1}）。
     */
    @PatchMapping("/{userId}/status")
    public ApiResponse<UserSummary> updateStatus(@PathVariable("userId") Long userId,
                                                 @Valid @RequestBody UpdateUserStatusRequest request) {
        return ApiResponse.success(userStatusService.updateStatus(userId, request.status()));
    }
}
