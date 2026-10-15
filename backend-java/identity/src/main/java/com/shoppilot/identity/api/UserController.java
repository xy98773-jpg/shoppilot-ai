package com.shoppilot.identity.api;

import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.identity.application.UserStatusService;
import com.shoppilot.identity.application.dto.UpdateUserStatusRequest;
import com.shoppilot.identity.application.dto.UserSummary;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端点（M2-T03）。
 *
 * <p>范围：仅 {@code PATCH /api/v1/users/{userId}/status}（{@code API_CONTRACT §10.1}）。
 * 用户列表（{@code GET /api/v1/users}）与成员管理（{@code POST/PATCH/DELETE
 * /api/v1/teams/{teamId}/members}）属 M2-T04/T05，<b>不在本批范围</b>。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserStatusService userStatusService;

    public UserController(UserStatusService userStatusService) {
        this.userStatusService = userStatusService;
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
