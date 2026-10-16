-- =============================================================================
-- V1.0.4__audit.sql —— audit 模块审计日志表（M2-T11）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DATABASE_DESIGN.md  §5.22 audit_logs（逐列：类型/可空/默认值/含义）
--   DATA_DICTIONARY.md §4.1 字段集合 B（仅追加：created_at + created_by，无 updated_*）
--                      §4.2 version 不出现（audit_logs 仅 INSERT，不可变）
--                      §5.22 audit_logs 字段字典（键/约束：CK ck_al_result、IDX）
--   DATABASE_CONSTRAINTS.md §3.2 索引 #54–#58（idx_al_* 五条，全部 team_id 最左）
--                      §4.1 枚举 CHECK（ck_al_result：SUCCESS/FAILURE）
--   DESIGN_BASIS.md     §9.17 AuditAction（35 条权威清单，action 列取值域）、
--                      AuditResult（SUCCESS/FAILURE）、§6 规则 1/3/4/5、
--                      §15 D-11（team_id = 0 系统域哨兵）
--   ER_DIAGRAM.md       §4.6（零外键理由：多态关联 + 只追加 + 需记录已停用对象）
--   DATABASE_DESIGN.md  §6-J.4（仅追加：应用层禁止 UPDATE/DELETE）
--
-- 范围：仅 audit_logs 一表 + audit 模块写入通道（M2-T11）。
--       **不做**审计查询端点（M6-T09 F-AUD-02）、**不做**导出（M6/M7）、
--       **不做** DB 级仅 INSERT 账号（D-05，M7 视需要）。
--       **不修改 V1.0.0 / V1.0.1 / V1.0.2 / V1.0.3**（校验和红线）。
--
-- 团队隔离要点（DESIGN_BASIS §6）：
--   · 规则 1：本表**含 team_id**（NOT NULL；0 = 系统域，D-11）→ **不登记**
--     TenantTables.WITHOUT_TEAM_ID（该黑名单只给无 team_id 列的表）。
--   · 规则 3：team_id **只来自服务端**（TeamContext 或 0 系统域），绝不接受客户端传入。
--   · 规则 4/5：租户线本可自动追加 team_id，但 ShopPilotTenantLineHandler 在
--     **上下文缺失时抛 AUTH-1001**（M2-T06 既定防线，不改）→ 登录失败/系统任务
--     无团队上下文，写不进去。故 audit_logs 的写方法显式
--     @InterceptorIgnore(tenantLine="true") + 实体显式 setTeamId(0 或上下文值)。
--     这是**绕过条件追加**，不是放弃隔离：team_id 仍逐行显式写入，读取侧显式带条件。
--   · 规则 6：五条索引**全部 team_id 最左**（SEQ_IN_INDEX=1 逐条断言）。
--
-- 仅追加（§6-J.4）：零外键（多态逻辑关联 biz_type+biz_id；对象可能已停用/删除）、
--   零唯一约束（同一操作可产生多条审计）、无 version、无 updated_at/updated_by。
--   应用层只暴露 insert 与只读查询（AuditLogMapper 不继承 BaseMapper）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- audit_logs（审计日志）—— 全部关键操作的追加式轨迹
-- 审计字段：集合 B（DATA_DICTIONARY §4.1：仅 created_at / created_by）
-- -----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id        BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（0 = 系统域，无团队上下文；D-11；服务端注入，不接受客户端传入）',
    action         VARCHAR(64)  NOT NULL COMMENT '动作常量（DESIGN_BASIS §9.17 AuditAction 35 条之一）',
    biz_type       VARCHAR(64)  NOT NULL COMMENT '业务对象类型（表名或领域名，如 WAREHOUSE/TEAM_MEMBER/USER）',
    biz_id         BIGINT UNSIGNED NULL COMMENT '业务对象 ID（多态，逻辑关联；由 biz_type 决定目标表）',
    biz_no         VARCHAR(64)  NULL COMMENT '业务编号快照（对象可能已软停用）',
    operator_id    BIGINT UNSIGNED NULL COMMENT '操作人 users.id（系统任务为 NULL）',
    operator_name  VARCHAR(64)  NULL COMMENT '操作人显示名快照',
    operator_role  VARCHAR(32)  NULL COMMENT '操作时角色快照（角色可变，审计记当时角色）',
    http_method    VARCHAR(8)   NULL COMMENT 'HTTP 方法（系统任务为空）',
    request_uri    VARCHAR(255) NULL COMMENT '请求路径（不含查询串，防 ?token= 泄漏）',
    request_id     VARCHAR(64)  NULL COMMENT 'X-Request-Id，链路追踪',
    ip             VARCHAR(64)  NULL COMMENT '客户端 IP',
    user_agent     VARCHAR(255) NULL COMMENT '客户端 UA（截断至 255）',
    result         VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS / FAILURE',
    error_code     VARCHAR(32)  NULL COMMENT '失败错误码（ERROR_CODES §12.2 稳定常量）',
    change_summary VARCHAR(1000) NULL COMMENT '人类可读变更摘要（截断至 1000）',
    before_json    JSON         NULL COMMENT '变更前关键字段（已脱敏）',
    after_json     JSON         NULL COMMENT '变更后关键字段（已脱敏）',
    occurred_at    DATETIME(3)  NOT NULL COMMENT '操作发生时间（UTC）',
    -- 公共审计字段（集合 B：仅追加表，无 updated_at/updated_by、无 version）
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC；用于排查审计写入延迟）',
    created_by     BIGINT UNSIGNED NULL COMMENT '创建人（逻辑关联 users.id；系统任务为 NULL）',
    PRIMARY KEY (id),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #54–#58；★ 全部 team_id 最左）
    KEY idx_al_team_biz_time      (team_id, biz_type, biz_id, occurred_at),
    KEY idx_al_team_operator_time (team_id, operator_id, occurred_at),
    KEY idx_al_team_action_time   (team_id, action, occurred_at),
    KEY idx_al_team_request       (team_id, request_id),
    KEY idx_al_team_result_time   (team_id, result, occurred_at),
    CONSTRAINT ck_al_result CHECK (result IN ('SUCCESS', 'FAILURE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '审计日志（audit；仅追加：零外键、零唯一约束、无 version）';
