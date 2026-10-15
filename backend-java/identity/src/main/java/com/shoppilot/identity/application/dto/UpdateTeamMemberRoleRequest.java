package com.shoppilot.identity.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 修改成员角色请求（{@code PATCH /api/v1/teams/{teamId}/members/{userId}}，契约 §10.2）。
 *
 * @param role 新 {@code TeamRole}（{@code DESIGN_BASIS §9.3}）
 */
public record UpdateTeamMemberRoleRequest(
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "OWNER|ADMIN|OPERATOR|APPROVER|VIEWER",
                message = "取值必须为 OWNER/ADMIN/OPERATOR/APPROVER/VIEWER")
        String role) {
}
