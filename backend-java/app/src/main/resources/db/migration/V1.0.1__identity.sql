-- =============================================================================
-- V1.0.1__identity.sql —— identity 模块基础表（M2-Foundation / F-B3）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DATABASE_DESIGN.md  §5.1 users / §5.2 teams / §5.3 team_members
--   DATA_DICTIONARY.md  §4.1 字段集合 A / version 12 张表 / §5.1-§5.3 字段字典
--   DATABASE_CONSTRAINTS.md §2.1 唯一约束、§3.2 索引、§4.1 枚举 CHECK
--   DESIGN_BASIS.md     §6 多团队隔离 6 条硬规则、§9.1-§9.3 枚举取值
--   DATABASE_DESIGN.md  §9.5 baseline-on-migrate=false（V1 起顺序执行到底）
--
-- 范围：仅 identity 三表（users / teams / team_members）。**不建任何业务表**
--       （warehouses/skus/... 属 M2 后续批次）。**不修改 V1.0.0__baseline.sql**。
--
-- 团队隔离要点（DESIGN_BASIS §6）：
--   · 规则 1：例外表仅 users（跨团队全局）、teams（自身即团队），二者**无 team_id 列**；
--     team_members 含 team_id，故有 team_id 列并以其为最左前缀建立索引。
--   · 规则 2：唯一约束凡带团队维度者必须以 team_id 为前缀（uk_tm_team_user）。
--   · 规则 6：主查询路径索引以 team_id 为最左列（idx_tm_team_role）。
--
-- 时间：一律 DATETIME(3) 存 UTC（DATABASE_DESIGN §3.2 / §14.1）；
--       created_at / updated_at 由数据库默认值填充，应用层不得显式覆盖。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- users（用户）—— 跨团队全局账号；§6 规则 1 的显式例外表（无 team_id）
-- 审计字段：集合 A + version（DATABASE_DESIGN §5.1 / §7.4 表 A）
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(64)     NOT NULL COMMENT '登录名（全局唯一）',
    email         VARCHAR(128)    NULL     COMMENT '邮箱（可空；唯一索引允许多行 NULL）',
    password_hash VARCHAR(255)    NOT NULL COMMENT '口令哈希（BCrypt）；禁止进入审计日志',
    display_name  VARCHAR(64)     NULL     COMMENT '显示名',
    phone         VARCHAR(32)     NULL     COMMENT '手机号（审计中需掩码）',
    status        VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'UserStatus：ACTIVE/DISABLED',
    last_login_at DATETIME(3)     NULL     COMMENT '最近登录时间（UTC）',
    -- 公共审计字段（集合 A）
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by    BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by    BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    version       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email    UNIQUE (email),
    CONSTRAINT ck_users_status   CHECK (status IN ('ACTIVE', 'DISABLED')),
    KEY idx_users_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户（平台全局账号，跨团队存在）';

-- -----------------------------------------------------------------------------
-- teams（团队）—— 数据隔离的最外层边界；自身即团队（无 team_id 列）
-- 审计字段：集合 A + version（DATABASE_DESIGN §5.2 / §7.4 表 A）
-- -----------------------------------------------------------------------------
CREATE TABLE teams (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    code                 VARCHAR(32)     NOT NULL COMMENT '团队编码（对外标识，不可变）',
    name                 VARCHAR(128)    NOT NULL COMMENT '团队名称',
    status               VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'TeamStatus：ACTIVE/DISABLED',
    default_timezone     VARCHAR(64)     NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '团队默认时区',
    default_warehouse_id BIGINT UNSIGNED NULL     COMMENT '默认仓库（逻辑关联 warehouses.id，不建外键防环形）',
    contact_name         VARCHAR(64)     NULL     COMMENT '联系人',
    contact_phone        VARCHAR(32)     NULL     COMMENT '联系电话',
    -- 公共审计字段（集合 A）
    created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by           BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by           BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    version              INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    CONSTRAINT uk_teams_code   UNIQUE (code),
    CONSTRAINT ck_teams_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    KEY idx_teams_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '团队（数据隔离的最外层边界）';

-- -----------------------------------------------------------------------------
-- team_members（团队成员）—— users × teams 多对多 + 团队内角色
-- 审计字段：集合 A（**无 version**：唯一约束保证一人一团队一行，状态翻转幂等）
-- -----------------------------------------------------------------------------
CREATE TABLE team_members (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id    BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（不可变）',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户 ID（不可变）',
    role       VARCHAR(32)     NOT NULL DEFAULT 'VIEWER' COMMENT 'TeamRole：OWNER/ADMIN/OPERATOR/APPROVER/VIEWER',
    status     VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '成员状态：ACTIVE/REMOVED（REMOVED 保留历史）',
    joined_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '加入时间（不可变）',
    removed_at DATETIME(3)     NULL     COMMENT '移出时间（移出时写入；复活时清空）',
    -- 公共审计字段（集合 A）
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 一个用户在一个团队只有一行；重新加入时复活同一行（不新增行）
    CONSTRAINT uk_tm_team_user UNIQUE (team_id, user_id),
    CONSTRAINT ck_tm_role      CHECK (role   IN ('OWNER', 'ADMIN', 'OPERATOR', 'APPROVER', 'VIEWER')),
    CONSTRAINT ck_tm_status    CHECK (status IN ('ACTIVE', 'REMOVED')),
    -- 规则 6：团队业务表主查询路径索引以 team_id 为最左列
    KEY idx_tm_team_role (team_id, role),
    KEY idx_tm_user_status (user_id, status),
    CONSTRAINT fk_tm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '团队成员（含团队内角色；team_id 为隔离边界）';
