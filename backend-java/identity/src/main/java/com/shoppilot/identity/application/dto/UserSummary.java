package com.shoppilot.identity.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 用户摘要（登录 / me 响应中的 {@code data.user}）。
 *
 * <p>字段名严格对齐 {@code API_CONTRACT §11.1} 的 <b>snake_case</b>
 * （{@code user_id} / {@code display_name}），故用 {@link JsonNaming} 显式声明命名策略——
 * 契约中 token 相关字段是 camelCase、user/team 内是 snake_case，二者混排，不能靠全局策略。
 *
 * <p>不含 {@code password_hash}、{@code phone} 等敏感列。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UserSummary(
        Long userId,
        String username,
        String displayName,
        String status) {
}
