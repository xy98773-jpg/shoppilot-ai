package com.shoppilot.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh Token 编解码（不透明随机串 + SHA-256 摘要）。
 *
 * <h2>形态决策（{@code D-01} + {@code API_CONTRACT §11.1}）</h2>
 * Refresh Token 采用 <b>不透明随机串（前缀 {@code rt_}）</b>，而非 JWT：
 * <ol>
 *   <li>{@code API_CONTRACT §11.1} 登录示例中 {@code refreshToken} 形如 {@code "rt_9f3a2c1e7b4d"}；</li>
 *   <li>撤销表 {@code refresh_tokens} <b>不含 {@code team_id}</b>（R15 三条不可让步约束之②），
 *       故会话绑定信息不落库；不透明串本身不承载任何可解析信息，语义更干净；</li>
 *   <li>{@code uk_rt_token_hash} = 摘要唯一 ⇒ 令牌天然防重放；</li>
 *   <li>撤销/轮换只按摘要精确定位（不透明串无「过期后仍可解析出内容」的问题）。</li>
 * </ol>
 *
 * <h2>★ 安全红线</h2>
 * 落库的<b>只有 SHA-256 摘要</b>（64 位小写十六进制）。明文令牌仅存在于：
 * ① 响应体（回显给客户端）；② {@code Set-Cookie}。服务端<b>不持久化明文</b>。
 *
 * <p>熵源：{@link SecureRandom} 32 字节（256 bit），Base64 URL-safe 无填充（43 字符）。
 */
public final class RefreshTokenCodec {

    /** 令牌前缀（与 {@code API_CONTRACT §11.1} 示例一致，便于客户端/日志识别）。 */
    public static final String PREFIX = "rt_";

    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokenCodec() {
    }

    /** 生成新的不透明 Refresh Token（{@code rt_} + 43 字符 Base64URL）。 */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + ENCODER.encodeToString(bytes);
    }

    /** 计算令牌的 SHA-256 摘要（64 位小写十六进制）——<b>落库的唯一形态</b>。 */
    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，不可能缺失
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
