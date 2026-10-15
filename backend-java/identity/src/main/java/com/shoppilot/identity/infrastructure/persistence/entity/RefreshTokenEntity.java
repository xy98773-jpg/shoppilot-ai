package com.shoppilot.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code refresh_tokens} 表映射（Refresh Token 撤销表；{@code DESIGN_BASIS §6} 规则 1 第 3 张例外表，
 * <b>无 {@code team_id}</b>，按 {@code user_id} 组织）。
 *
 * <p><b>安全红线</b>：本表只存 {@code token_hash}（SHA-256 十六进制，64 位），
 * <b>绝不存明文令牌</b>。
 *
 * <p>轮换链：轮换时旧行写 {@code revoked_at} + {@code replaced_by}（指向新令牌的摘要），
 * 新行插入；登出时旧行写 {@code revoked_at}。
 */
@Data
@TableName("refresh_tokens")
public class RefreshTokenEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** Refresh Token 的 SHA-256 摘要（64 位小写十六进制）。 */
    private String tokenHash;

    /** 过期时间（UTC）。 */
    private LocalDateTime expiresAt;

    /** 撤销时间（UTC）；非 {@code null} 即已撤销。 */
    private LocalDateTime revokedAt;

    /** 轮换链：指向新令牌的 {@code token_hash}。 */
    private String replacedBy;

    /** 签发时客户端 IP（IPv6 兼容，≤45）。 */
    private String ipAddr;

    /** 签发时客户端 UA（截断至 255）。 */
    private String userAgent;
}
