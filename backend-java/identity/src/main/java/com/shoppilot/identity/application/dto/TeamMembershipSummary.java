package com.shoppilot.identity.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 「我的团队」条目（{@code GET /api/v1/teams}，{@code API_CONTRACT §10.2}「当前用户所属团队列表」）。
 *
 * <p>{@code role} 是<b>当前用户在该团队内的角色</b>（团队作用域角色，
 * {@code USER_ROLES §6.1}）——同一用户在不同团队可有不同角色。
 *
 * @param teamId   团队 ID（{@code teams.id}）
 * @param teamName 团队名称（{@code teams.name}）
 * @param status   团队状态（{@code TeamStatus}：ACTIVE / DISABLED）
 * @param role     当前用户在该团队的角色（{@code team_members.role}）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TeamMembershipSummary(
        Long teamId,
        String teamName,
        String status,
        String role) {
}
