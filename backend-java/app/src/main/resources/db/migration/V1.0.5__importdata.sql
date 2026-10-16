-- =============================================================================
-- V1.0.5__importdata.sql —— importdata 模块导入批次与行错误表（M3-T01）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DATABASE_DESIGN.md      §5.12 import_batches（逐列：类型/可空/默认值/含义/更新规则）
--                           §5.13 import_row_errors（同上）
--                           §6-E（dedupe_hash 槽位"占用/释放"规则；为何不以文件名作幂等依据）
--   DATA_DICTIONARY.md      §4.1 字段集合 A（created_at/updated_at/created_by/updated_by）
--                           §4.1 字段集合 B（仅追加：created_at + created_by，无 updated_*）
--                           §4.2 version 不出现（理由见本文件头末段）
--   DATABASE_CONSTRAINTS.md §3.1 唯一约束 #14 uk_imp_team_hash_biz (team_id, dedupe_hash, biz_type)
--                           §3.2 索引 #25–#30（idx_imp_* 三条 + idx_ire_* 三条，全部 team_id 最左）
--                           §4.1 枚举 CHECK（ck_imp_status 10 值 / ck_imp_biz_type 6 值 /
--                                ck_ire_level 2 值）与范围 CHECK（ck_imp_total_rows /
--                                ck_imp_success_rows / ck_imp_failed_rows / ck_imp_attempt_no /
--                                ck_ire_row_no）
--                           §4.2 外键 #14 fk_ire_batch（ON DELETE RESTRICT ON UPDATE RESTRICT）
--   DESIGN_BASIS.md         §9.5  ImportBatchStatus（10 值状态机）
--                           §9.15 ImportBizType（6 值）
--                           §15.1 导入幂等（可空槽位列 dedupe_hash；FORCE_REIMPORT = 动作而非状态）
--                           §6 规则 1/3（表必含 team_id；team_id 只来自服务端）
--                           §7.4 状态守卫替代 version 的适用条件
--   ER_DIAGRAM.md           §4.4 领域四 · 数据导入（importdata）：
--                           import_batches 1:N import_row_errors（RESTRICT，均不物理删除）；
--                           import_batches 自关联 1:0..1（duplicate_of_batch_id /
--                           superseded_by_batch_id），多态/自关联不建物理外键。
--
-- 范围：仅 import_batches + import_row_errors 两表（M3-T01，纯 DDL）。
--       **不做**导入业务逻辑 / 端点 / Entity / Mapper / Service（属 M3-T02 起）。
--       **不建** inventory_* / sales_daily / replenishment_policies（属 M3-T08 / M3-T12）。
--       **不修改 V1.0.0 / V1.0.1 / V1.0.2 / V1.0.3 / V1.0.4**（Flyway 校验和红线）。
--
-- 团队隔离要点（DESIGN_BASIS §6）：
--   · 规则 1：两表**均含 team_id NOT NULL** → **不得登记**进 TenantTables.WITHOUT_TEAM_ID
--     （该黑名单只给无 team_id 列的表）；本批无需改动 TenantTables 任何内容。
--   · 规则 3：team_id **只来自服务端**（TeamContext），绝不接受客户端传入。
--   · 规则 6：六条二级索引**全部 team_id 最左**（SEQ_IN_INDEX=1 逐条断言）。
--   · 文件哈希的幂等判定**只在团队内**：跨团队同 file_hash / 同 dedupe_hash 允许共存。
--
-- ★ 唯一键口径（避免踩 R31 / 旧编号 D-09）：
--   DESIGN_BASIS §8 表行字面仍写 (team_id, file_hash, biz_type)，属**未同步的次级不一致**。
--   以 §8 补充说明 + §9.5 + DATABASE_CONSTRAINTS §3.1 #14 为准 → 物理列为【可空槽位列 dedupe_hash】。
--   理由：唯一键若直建在 file_hash 上，则 ① 重复上传时无法插入 DUPLICATE_REJECTED 记录；
--   ② FORCE_REIMPORT 的新批次必然违反唯一键（旧批次已是终态 COMPLETED，不可改状态）。
--   约束名 uk_imp_team_hash_biz 与业务语义保持不变。
--
-- 无 version（DESIGN_BASIS §7.4）：
--   import_batches      —— 状态守卫 + 同批次单执行者（同批次由单个工作线程推进）→ V4 不成立。
--   import_row_errors   —— 仅 INSERT 的仅追加表 → V1 不成立。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- import_batches（导入批次）—— 一次导入上传的执行实例：文件级幂等 + 状态机 + 结果计数
-- 审计字段：集合 A（DATA_DICTIONARY §4.1：created_at/updated_at/created_by/updated_by）
-- 幂等：uk_imp_team_hash_biz 建在【可空槽位列 dedupe_hash】上（见文件头口径说明）
-- -----------------------------------------------------------------------------
CREATE TABLE import_batches (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id                 BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（服务端注入，不接受客户端传入；不可变）',
    batch_no                VARCHAR(64)     NOT NULL COMMENT '批次号（业务编号；不可变）',
    biz_type                VARCHAR(32)     NOT NULL COMMENT 'ImportBizType（DESIGN_BASIS §9.15 六值之一；不可变）',
    file_name               VARCHAR(255)    NOT NULL COMMENT '原始文件名（仅展示，不作幂等依据；不可变）',
    file_hash               CHAR(64)        NOT NULL COMMENT '文件内容 SHA-256（十六进制小写；不可变）',
    dedupe_hash             CHAR(64)        NULL     COMMENT '幂等槽位列：占用时 = file_hash，释放时为 NULL（§6-E 占用/释放规则驱动）',
    file_size               BIGINT UNSIGNED NULL     COMMENT '文件字节数',
    storage_path            VARCHAR(512)    NULL     COMMENT '原始文件存储路径（文件是全量原文的唯一真相）',
    status                  VARCHAR(32)     NOT NULL DEFAULT 'UPLOADED' COMMENT 'ImportBatchStatus（DESIGN_BASIS §9.5 十值之一；按状态机单向流转）',
    total_rows              INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '数据行总数（不含表头；PARSED 后固定）',
    success_rows            INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '成功落库行数（IMPORTING 期间累加，终态固定）',
    failed_rows             INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '失败行数（IMPORTING 期间累加，终态固定）',
    attempt_no              INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '同一文件的第几次导入尝试（强制重导时递增）',
    field_mapping_json      JSON            NULL     COMMENT '字段映射（源列 → 目标字段）；MAPPED 时写入，之后不可变',
    parse_options_json      JSON            NULL     COMMENT '解析选项（工作表、表头行号、编码、日期格式）；PARSED 时写入',
    template_version        VARCHAR(32)     NULL     COMMENT '模板版本（用于诊断"为什么昨天能导入今天不行"）',
    error_code              VARCHAR(32)     NULL     COMMENT '批次级错误码（IMP-xxxx）；失败时写入',
    error_message           VARCHAR(500)    NULL     COMMENT '批次级错误说明；失败时写入',
    duplicate_of_batch_id   BIGINT UNSIGNED NULL     COMMENT '命中重复时指向的已存在批次（逻辑自关联，不建 FK；DUPLICATE_REJECTED 时写入）',
    superseded_by_batch_id  BIGINT UNSIGNED NULL     COMMENT '被哪一批次取代（逻辑自关联，不建 FK；FORCE_REIMPORT 时写入）',
    started_at              DATETIME(3)     NULL     COMMENT '开始落库时间（UTC；IMPORTING 时写入）',
    finished_at             DATETIME(3)     NULL     COMMENT '结束时间（UTC；进入终态时写入）',
    -- 公共审计字段（集合 A：DATA_DICTIONARY §4.1；无 version，用状态守卫 §7.4）
    created_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by              BIGINT UNSIGNED NULL     COMMENT '创建人（记录上传人；逻辑关联 users.id）',
    updated_by              BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 幂等唯一键（DATABASE_CONSTRAINTS §3.1 #14；★ 可空槽位列 dedupe_hash，见文件头口径说明）
    UNIQUE KEY uk_imp_team_hash_biz (team_id, dedupe_hash, biz_type),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #25–#27；★ 全部 team_id 最左）
    KEY idx_imp_team_status_time (team_id, status, created_at),
    KEY idx_imp_team_biz_time    (team_id, biz_type, created_at),
    KEY idx_imp_team_file_hash   (team_id, file_hash),
    -- CHECK（DATABASE_CONSTRAINTS §4.1）
    CONSTRAINT ck_imp_status CHECK (status IN ('UPLOADED', 'PARSED', 'MAPPED', 'VALIDATED',
        'IMPORTING', 'COMPLETED', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'DUPLICATE_REJECTED')),
    CONSTRAINT ck_imp_biz_type CHECK (biz_type IN ('PRODUCT', 'SKU', 'SUPPLIER',
        'WAREHOUSE', 'INVENTORY_SNAPSHOT', 'SALES_DAILY')),
    CONSTRAINT ck_imp_total_rows   CHECK (total_rows >= 0),
    CONSTRAINT ck_imp_success_rows CHECK (success_rows >= 0),
    CONSTRAINT ck_imp_failed_rows  CHECK (failed_rows >= 0),
    CONSTRAINT ck_imp_attempt_no   CHECK (attempt_no >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '导入批次（importdata；幂等槽位唯一键 + 状态机，无 version）';

-- -----------------------------------------------------------------------------
-- import_row_errors（导入行错误）—— 导入失败行的逐行错误明细
-- 审计字段：集合 B（DATA_DICTIONARY §4.1：仅 created_at + created_by，无 updated_*）
-- 唯一约束：**无**（§5.13）—— 同一行可能同时命中多条规则，强制唯一会丢弃诊断信息
-- 更新规则：**仅 INSERT**，不 UPDATE / DELETE（批次取消也不删错误行，历史可查）
-- -----------------------------------------------------------------------------
CREATE TABLE import_row_errors (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id        BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（服务端注入，不接受客户端传入；不可变）',
    batch_id       BIGINT UNSIGNED NOT NULL COMMENT '所属批次 import_batches.id（不可变）',
    row_no         INT UNSIGNED    NOT NULL COMMENT '数据行号（1 起，不含表头；不可变）',
    error_level    VARCHAR(16)     NOT NULL DEFAULT 'ERROR' COMMENT 'ERROR（阻断该行）/ WARNING（仅提示）',
    error_code     VARCHAR(32)     NOT NULL COMMENT '错误码（IMP-4003 / COM-0001 / CAT-3002 …）',
    error_field    VARCHAR(64)     NULL     COMMENT '出错字段名（多字段错误写首个）',
    error_message  VARCHAR(500)    NOT NULL COMMENT '可读错误说明（中文）',
    raw_data       JSON            NULL     COMMENT '仅失败行的原始行数据（成功行不冗余存原文，见 §6-E）',
    -- 公共审计字段（集合 B：DATA_DICTIONARY §4.1；仅追加，无 updated_at/updated_by、无 version）
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by     BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #28–#30；★ 全部 team_id 最左）
    KEY idx_ire_team_batch_row (team_id, batch_id, row_no),
    KEY idx_ire_team_code      (team_id, error_code),
    KEY idx_ire_team_level     (team_id, batch_id, error_level),
    -- 外键（DATABASE_CONSTRAINTS §4.2 #14；RESTRICT：批次不物理删除，防孤儿明细）
    CONSTRAINT fk_ire_batch FOREIGN KEY (batch_id) REFERENCES import_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（DATABASE_CONSTRAINTS §4.1）
    CONSTRAINT ck_ire_level  CHECK (error_level IN ('ERROR', 'WARNING')),
    CONSTRAINT ck_ire_row_no CHECK (row_no >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '导入行错误（importdata；仅 INSERT 仅追加，无唯一约束、无 version）';
