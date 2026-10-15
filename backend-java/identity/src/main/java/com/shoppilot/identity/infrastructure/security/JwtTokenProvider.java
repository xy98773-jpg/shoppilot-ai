package com.shoppilot.identity.infrastructure.security;

import io.jsonwebtoken.Claims;

/**
 * Access Token（JWT）编解码能力（identity 模块内部技术能力，属 infrastructure 层）。
 *
 * <p>Claim 结构以 {@code API_CONTRACT.md §5.2} 为权威：{@code sub}（userId 字符串）、
 * {@code team_id}（当前上下文团队，单次会话绑定一个团队，{@code D-13}）、
 * {@code role}（当前团队内的角色代码）、{@code jti}/{@code iss}/{@code aud}/{@code iat}/{@code exp}。
 * 另按 M2-T02 任务书 §2 增加 {@code username} claim（便于审计与日志，属**附加**claim，
 * 不与 §5.2 既列 claim 冲突）。
 *
 * <p>有效期 Access <b>2h</b>（{@code DESIGN_BASIS §15 D-01}），由 {@link JwtProperties} 注入。
 *
 * <h2>★ 为什么没有 {@code issueRefreshToken}</h2>
 * 本接口在 M1-T04 曾是「Access + Refresh 均签发 JWT」的骨架。M2-T02 落地时依据
 * {@code API_CONTRACT §11.1} 的 {@code refreshToken} 示例形态（{@code rt_…}）与
 * {@code D-01} 的「只存 SHA-256 摘要」要求，裁定 <b>Refresh Token 为不透明随机串</b>，
 * 由 {@link RefreshTokenCodec} 负责生成与摘要，<b>不经过 JWT 签名</b>。
 * 故本接口收敛为 Access Token 专用。
 */
public interface JwtTokenProvider {

    /**
     * 签发 Access Token（有效期 {@link JwtProperties#getAccessTtlSeconds()}）。
     *
     * @param userId   用户 ID（{@code users.id}，写入 {@code sub}）
     * @param username 登录名（附加 claim {@code username}，用于审计/日志）
     * @param teamId   当前上下文团队 ID（{@code teams.id}，claim {@code team_id}）
     * @param role     当前团队内的角色代码（{@code DESIGN_BASIS §9.3}，claim {@code role}）
     * @param jti      令牌唯一 ID（{@code jti}，审计与潜在吊销）
     * @return 已签名的 JWT 字符串
     */
    String issueAccessToken(Long userId, String username, Long teamId, String role, String jti);

    /**
     * 解析并校验令牌（校验签名、有效期与 {@code iss}/{@code aud}）。
     *
     * @param token 待解析的 JWT 字符串
     * @return 解析后的 Claims
     * @throws io.jsonwebtoken.ExpiredJwtException 令牌已过期（调用方应映射 {@code AUTH-1005}）
     * @throws io.jsonwebtoken.JwtException        令牌非法或签名不符（调用方应映射 {@code AUTH-1001}）
     */
    Claims parseClaims(String token);

    /** Access Token 有效期（秒）——登录/刷新响应 {@code expiresIn} 的取值来源。 */
    long accessTtlSeconds();
}
