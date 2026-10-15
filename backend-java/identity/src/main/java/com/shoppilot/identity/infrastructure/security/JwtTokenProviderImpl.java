package com.shoppilot.identity.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * {@link JwtTokenProvider} 的正式实现（M2-T02，替换 M1-T04 的骨架）。
 *
 * <p>算法 HMAC-SHA256；{@code iss=shoppilot-ai}、{@code aud=frontend-web}（{@code API_CONTRACT §5.2}）；
 * 校验签名、有效期与 {@code iss}/{@code aud}。
 *
 * <p>密钥由 {@link JwtProperties} 从环境变量注入（{@code JWT_SECRET}），<b>不在代码/仓库中出现</b>。
 * 密钥在构造时固化为 {@link SecretKey}，避免每次签发重复解析。
 */
@Component
public class JwtTokenProviderImpl implements JwtTokenProvider {

    /** Claim 名：用户 ID（契约 §5.2 使用 {@code sub}）。 */
    public static final String CLAIM_TEAM_ID = "team_id";
    /** Claim 名：团队内角色代码（契约 §5.2）。 */
    public static final String CLAIM_ROLE = "role";
    /** Claim 名：登录名（M2-T02 附加，用于审计/日志）。 */
    public static final String CLAIM_USERNAME = "username";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtTokenProviderImpl(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issueAccessToken(Long userId, String username, Long teamId, String role, String jti) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.getAccessTtlSeconds());
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TEAM_ID, teamId)
                .claim(CLAIM_ROLE, role)
                .id(jti)
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.getIssuer())
                .requireAudience(properties.getAudience())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Override
    public long accessTtlSeconds() {
        return properties.getAccessTtlSeconds();
    }
}
