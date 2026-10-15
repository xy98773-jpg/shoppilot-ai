package com.shoppilot.identity.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 添加成员请求（{@code POST /api/v1/teams/{teamId}/members}，契约 §10.2「添加成员并指定角色」）。
 *
 * @param userId 目标用户 ID（{@code users.id}）
 * @param role   目标 {@code TeamRole}（{@code DESIGN_BASIS §9.3}）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AddTeamMemberRequest(
        @NotNull(message = "不能为空")
        Long userId,
        @Pattern(regexp = "OWNER|ADMIN|OPERATOR|APPROVER|VIEWER",
                message = "取值必须为 OWNER/ADMIN/OPERATOR/APPROVER/VIEWER")
        String role) {
}
