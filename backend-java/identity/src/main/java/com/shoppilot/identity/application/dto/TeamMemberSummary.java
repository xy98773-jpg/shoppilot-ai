package com.shoppilot.identity.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 团队成员条目（{@code GET /api/v1/teams/{teamId}/members}，契约 §10.2「含角色与状态」）。
 *
 * <p>字段对齐 {@code API_CONTRACT §12}：{@code user_id}、{@code username}、
 * {@code display_name}、{@code role}（{@code team_members.role}）、{@code status}
 * （成员状态 {@code ACTIVE}/{@code REMOVED}）。
 *
 * <p>不含 {@code password_hash}、{@code phone} 等敏感列。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TeamMemberSummary(
        Long userId,
        String username,
        String displayName,
        String role,
        String status) {
}
