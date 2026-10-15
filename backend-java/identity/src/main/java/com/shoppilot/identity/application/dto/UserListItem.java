package com.shoppilot.identity.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 用户列表条目（{@code GET /api/v1/users}，契约 §10.1「用户列表（用于成员管理选人）」）。
 *
 * <p>字段对齐 {@code API_CONTRACT §12}：{@code user_id}、{@code username}、
 * {@code display_name}、{@code status}。不含 {@code password_hash}、{@code phone}。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UserListItem(
        Long userId,
        String username,
        String displayName,
        String status) {
}
