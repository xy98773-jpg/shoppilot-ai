package com.shoppilot.identity.infrastructure.security;

import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Component;

/**
 * {@link JwtTokenProvider} 的骨架实现（M1-T04）。
 *
 * <p><b>★ 本期为纯骨架：所有方法体仅抛占位异常，未实现任何签发或校验逻辑。</b>
 * 这是刻意的范围约束——{@code DEVELOPMENT_PLAN.md §3.8} No-Go 明确要求
 * M1 不实现 JWT 的签发与校验（仅做 Security 过滤器链骨架与依赖引入），
 * 正式实现属 M2-T02（登录 / 令牌刷新）。
 *
 * <p>密钥来源（M2-T02）：环境变量 {@code JWT_SECRET}（不得入库、不得入仓，见
 * {@code ADR-001-ARCHITECTURE.md §ADR-001-12 风险表}）；TTL 取 {@code JWT_ACCESS_TTL} /
 * {@code JWT_REFRESH_TTL}（{@code PROJECT_STRUCTURE.md §7.2}）。
 * 算法与 {@code iss}=shoppilot-ai / {@code aud}=frontend-web 见 {@code API_CONTRACT.md §5.2}。
 */
@Component
public class JwtTokenProviderImpl implements JwtTokenProvider {

    private static final String NOT_IMPLEMENTED_IN_M1 =
            "M1-T04 骨架：JWT 签发/校验逻辑属 M2-T02（DEVELOPMENT_PLAN §3.8 No-Go）";

    @Override
    public String issueAccessToken(String sub, Long teamId, String role, String jti) {
        // TODO M2-T02：按 API_CONTRACT §5.2 组装 Claims，使用 JWT_SECRET 以 HMAC-SHA256 签名，TTL=JWT_ACCESS_TTL
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_IN_M1);
    }

    @Override
    public String issueRefreshToken(String sub, Long teamId, String role, String jti) {
        // TODO M2-T02：同 Access Token，TTL=JWT_REFRESH_TTL（7d），并登记 Refresh 记录以支持撤销/轮换
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_IN_M1);
    }

    @Override
    public Claims parseClaims(String token) {
        // TODO M2-T02：校验签名与有效期后返回 Claims；失败抛 JwtException，由认证过滤器转为 AUTH-1001
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_IN_M1);
    }
}
