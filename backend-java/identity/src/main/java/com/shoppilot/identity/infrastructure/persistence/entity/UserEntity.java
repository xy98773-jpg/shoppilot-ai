package com.shoppilot.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code users} 表映射（跨团队全局账号；{@code DESIGN_BASIS §6} 规则 1 的显式例外表，<b>无 {@code team_id}</b>）。
 *
 * <p>本实体只映射认证所需列；公共审计字段（{@code created_at}/{@code created_by}/…）与
 * {@code version} 由数据库默认值维护，不在本实体中读写，避免 MyBatis-Plus 以 {@code null} 覆盖。
 * 时间列一律 {@code DATETIME(3)} 存 UTC（JDBC 连接参数 {@code connectionTimeZone=UTC}）。
 */
@Data
@TableName("users")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名（全局唯一，{@code uk_users_username}）。 */
    private String username;

    /** 邮箱（可空；唯一约束允许多行 NULL）。登录名或邮箱均可登录。 */
    private String email;

    /** BCrypt 口令哈希；**永不进入日志与响应**。 */
    private String passwordHash;

    /** 显示名。 */
    private String displayName;

    /** 手机号（响应与日志中需掩码）。 */
    private String phone;

    /** {@code UserStatus}：ACTIVE / DISABLED。 */
    private String status;

    /** 最近登录时间（UTC）；登录成功时更新。 */
    private LocalDateTime lastLoginAt;
}
