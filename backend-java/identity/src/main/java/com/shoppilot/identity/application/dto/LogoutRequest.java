package com.shoppilot.identity.application.dto;

/**
 * 登出请求体（{@code POST /api/v1/auth/logout}）。
 *
 * <p>{@code refreshToken} 可空——语义见 {@code AuthService#logout}：
 * 提供时只撤销该令牌；未提供时撤销当前用户的<b>全部</b>未撤销令牌（「登出全部会话」）。
 * 幂等：重复登出不报错（{@code API_CONTRACT §10.1} 幂等列 ✅）。
 *
 * @param refreshToken {@code rt_} 前缀的不透明令牌（可空）
 */
public record LogoutRequest(String refreshToken) {
}
