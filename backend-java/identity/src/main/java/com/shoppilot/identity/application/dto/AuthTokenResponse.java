package com.shoppilot.identity.application.dto;

/**
 * 令牌签发结果（{@code POST /api/v1/auth/login} 与 {@code /refresh} 的 {@code data}）。
 *
 * <p>字段与 {@code API_CONTRACT §11.1} 成功响应<b>逐字段一致</b>：
 * {@code accessToken} / {@code refreshToken} / {@code tokenType} / {@code expiresIn}
 * 为 camelCase（故不加 {@link com.fasterxml.jackson.databind.annotation.JsonNaming}），
 * 内嵌 {@code user} / {@code team} 为 snake_case（见 {@link UserSummary} / {@link TeamSummary}）。
 *
 * @param accessToken  JWT Access Token（客户端放 {@code Authorization: Bearer}）
 * @param refreshToken 不透明 Refresh Token（{@code rt_} 前缀；服务端只存其 SHA-256 摘要）
 * @param tokenType    固定 {@code "Bearer"}
 * @param expiresIn    Access Token 有效期（秒），{@code 7200}（D-01：2h）
 * @param user         当前用户摘要
 * @param team         当前团队摘要
 * @param role         当前团队内角色名（{@code USER_ROLES §2.2}）
 */
public record AuthTokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserSummary user,
        TeamSummary team,
        String role) {

    /** 令牌类型常量（契约 §11.1）。 */
    public static final String TOKEN_TYPE_BEARER = "Bearer";
}
