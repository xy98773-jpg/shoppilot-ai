package com.shoppilot.identity.infrastructure.security;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置（前缀 {@code shoppilot.jwt}）。
 *
 * <h2>★ 密钥红线</h2>
 * {@code secret} <b>只从环境变量 / 外部配置注入，绝不入仓</b>
 * （{@code CODING_STANDARDS.md}、{@code ADR-001-ARCHITECTURE.md} ADR-001-12 风险表）。
 * {@code application.yml} 中写作 {@code ${JWT_SECRET:}}——未注入时为空串，
 * 由 {@link #validate()} 在<b>启动时即刻失败</b>（宁可起不来，不允许用弱默认密钥静默运行）。
 *
 * <h2>TTL（{@code DESIGN_BASIS §15 D-01}）</h2>
 * Access Token <b>2h（7200s）</b>、Refresh Token <b>7d（604800s）</b>。
 * 环境变量 {@code JWT_ACCESS_TTL} / {@code JWT_REFRESH_TTL} 可覆盖（单位：秒）。
 */
@Component
@ConfigurationProperties(prefix = "shoppilot.jwt")
public class JwtProperties {

    /** HS256 密钥最小字节数（RFC 7518：HS256 要求密钥 ≥ 256 bit）。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** HMAC 签名密钥（必填，仅从环境变量注入）。 */
    private String secret;

    /** Access Token 有效期（秒），默认 2h（D-01）。 */
    private long accessTtlSeconds = 7200L;

    /** Refresh Token 有效期（秒），默认 7d（D-01）。 */
    private long refreshTtlSeconds = 604800L;

    /** 签发者（固定 {@code shoppilot-ai}，{@code API_CONTRACT §5.2}）。 */
    private String issuer = "shoppilot-ai";

    /** 受众（固定 {@code frontend-web}，{@code API_CONTRACT §5.2}）。 */
    private String audience = "frontend-web";

    /** 启动即校验：密钥缺失或过短直接失败（避免弱密钥静默运行）。 */
    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 JWT 签名密钥：请通过环境变量 JWT_SECRET 注入（密钥不得入仓，见 ADR-001-12 风险表）");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 签名密钥过短：" + bytes + " 字节 < " + MIN_SECRET_BYTES + " 字节（HS256 要求 ≥ 256 bit）");
        }
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public void setAccessTtlSeconds(long accessTtlSeconds) {
        this.accessTtlSeconds = accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    public void setRefreshTtlSeconds(long refreshTtlSeconds) {
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }
}
