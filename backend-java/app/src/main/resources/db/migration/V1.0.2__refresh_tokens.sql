-- =============================================================================
-- V1.0.2__refresh_tokens.sql —— Refresh Token 撤销表（M2-T02-pre / F-B4，闭环 R15）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DESIGN_BASIS.md          §8 第 23 张表、§15 D-01 认证裁定、§6 规则 1 例外表
--   DATABASE_DESIGN.md       §4.4 新增理由、§5.23 逐字段设计
--   DATA_DICTIONARY.md       §5.23 字段字典、§4.1 字段集合 A
--   DATABASE_CONSTRAINTS.md  §2.1 uk_rt_token_hash、§3.2 idx_rt_*、§5.2 fk_rt_user
--   ER_DIAGRAM.md            §4.1 / §5 第 46 条 / §7.1 全库唯一 CASCADE 例外
--   DESIGN_REVIEW.md         R15（本表为其闭环交付物）
--
-- 背景：D-01 裁定要求 Refresh Token 存"MySQL 独立撤销表"。没有本表，
--       F-AUTH-01（POST /api/v1/auth/logout 使 Refresh Token 失效）**物理上无法实现**——
--       纯无状态令牌无处记录"已登出"。本表即为该缺口的最小闭环。
--
-- ★ 三条不可让步的架构约束：
--   1. **只存 token_hash（SHA-256），绝不存明文令牌** —— 安全红线。
--   2. **本表不带 team_id**（按 user_id 组织，与 users 同一层级的跨团队全局账号体系）。
--      ⇒ 必须登记进 TenantTables.WITHOUT_TEAM_ID，否则租户线会追加 `team_id = ?`
--        而**永远查不到行**，导致**登出静默失效**（不报错、只是不生效）。
--   3. 外键 fk_rt_user 用 **ON DELETE CASCADE**（全库唯一例外）：令牌是**派生凭据数据**，
--      用户被物理删除后必须立即失效，否则留下"可刷新但用户已不存在"的悬挂凭据。
--      该例外已在 DATABASE_CONSTRAINTS.md §5.2 与 ER_DIAGRAM.md §7.1 双处登记。
--
-- 审计字段：集合 A（登出写 revoked_at、轮换写 revoked_at + replaced_by ⇒ 本表可被 UPDATE），
--           **无 version**（状态翻转单向一次，uk_rt_token_hash 已保证写入幂等）。
-- 时间：一律 DATETIME(3) 存 UTC；created_at / updated_at 由数据库默认值填充。
--
-- 范围：**只建表**。本批不实现登录/登出/刷新端点（属下一批 M2-Auth）。
--       **不修改 V1.0.0__baseline.sql / V1.0.1__identity.sql**。
-- =============================================================================

CREATE TABLE refresh_tokens (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '令牌所属用户（跨团队全局，无 team_id）',
    token_hash  CHAR(64)        NOT NULL COMMENT 'Refresh Token 的 SHA-256 摘要（64 位小写十六进制）；禁止存明文',
    expires_at  DATETIME(3)     NOT NULL COMMENT '过期时间（UTC），签发时 = 当时 + 7d',
    revoked_at  DATETIME(3)     NULL     COMMENT '撤销时间（UTC）；非 NULL 即已撤销（登出/轮换时写入，写入后不可清空）',
    replaced_by CHAR(64)        NULL     COMMENT '轮换链：指向新令牌的 token_hash',
    ip_addr     VARCHAR(45)     NULL     COMMENT '签发时客户端 IP（45 位 = IPv6 兼容）',
    user_agent  VARCHAR(255)    NULL     COMMENT '签发时客户端 UA（截断至 255）',
    -- 公共审计字段（集合 A）
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by  BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id；通常等于 user_id）',
    updated_by  BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 一个摘要对应一行：撤销/轮换按摘要精确定位；摘要冲突即视为"该令牌已登记"（防重放）
    CONSTRAINT uk_rt_token_hash UNIQUE (token_hash),
    -- 登出/撤销：按用户批量失效其全部刷新令牌（高频）
    KEY idx_rt_user (user_id),
    -- 过期清理任务的范围扫描（系统级、跨用户）
    KEY idx_rt_expires (expires_at),
    -- ★ 全库唯一 ON DELETE CASCADE（例外理由见文件头约束 3）
    CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Refresh Token 撤销表（只存 SHA-256 摘要；跨团队全局，无 team_id）';
