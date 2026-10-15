package com.shoppilot.identity.infrastructure.security;

import io.jsonwebtoken.Claims;

/**
 * JWT 令牌编解码能力（identity 模块内部技术能力，属 infrastructure 层）。
 *
 * <p>Claim 结构以 {@code API_CONTRACT.md §5.2} 为权威：{@code sub}（userId 字符串）、
 * {@code team_id}（当前上下文团队，单次会话绑定一个团队，D-13）、{@code role}（当前团队内的角色代码）、
 * {@code jti}/{@code iss}/{@code aud}/{@code iat}/{@code exp}。
 * 有效期为 Access 2h / Refresh 7d（{@code D-01}），由实现按 {@code JWT_ACCESS_TTL} / {@code JWT_REFRESH_TTL}
 * 环境变量决定。
 *
 * <p><b>本期（M1-T04）仅定义签名与骨架</b>：所有实现体均为 TODO 占位。
 * 签发与校验的正式实现、以及认证过滤器挂载，均属 <b>M2-T02</b>
 * （{@code DEVELOPMENT_PLAN.md §3.8} 明确 No-Go：M1 不实现 JWT 签发与校验逻辑）。
 */
public interface JwtTokenProvider {

    /**
     * 签发 Access Token（有效期 2h）。
     *
     * @param sub    用户 ID 的字符串形式（{@code users.id}）
     * @param teamId 当前上下文团队 ID（{@code teams.id}，JWT claim {@code team_id}）
     * @param role   当前团队内的角色代码（{@code DESIGN_BASIS §9.3}）
     * @param jti    令牌唯一 ID（审计与潜在吊销）
     * @return 已签名的 JWT 字符串
     */
    String issueAccessToken(String sub, Long teamId, String role, String jti);

    /**
     * 签发 Refresh Token（有效期 7d）。
     *
     * @param sub    用户 ID 的字符串形式（{@code users.id}）
     * @param teamId 当前上下文团队 ID（{@code teams.id}）
     * @param role   当前团队内的角色代码
     * @param jti    令牌唯一 ID（服务端据此撤销/轮换）
     * @return 已签名的 JWT 字符串
     */
    String issueRefreshToken(String sub, Long teamId, String role, String jti);

    /**
     * 解析并校验令牌（校验签名、有效期与 {@code iss}/{@code aud}）。
     *
     * @param token 待解析的 JWT 字符串
     * @return 解析后的 Claims
     * @throws io.jsonwebtoken.JwtException 令牌非法、签名不符或已过期
     */
    Claims parseClaims(String token);
}
