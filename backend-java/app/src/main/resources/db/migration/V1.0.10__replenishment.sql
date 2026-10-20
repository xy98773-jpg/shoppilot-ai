-- =============================================================================
-- V1.0.10__replenishment.sql —— replenishment 模块 replenishment_items / replenishment_reviews
--                              （M5-T01，数据模型与迁移）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DATABASE_DESIGN.md      §5.16 replenishment_items（逐列：类型/可空/默认值/含义/更新规则）
--                           §5.17 replenishment_reviews（逐列；★ 仅追加，永不 UPDATE/DELETE）
--                           §7.4  表 A（replenishment_items 含 version：人工改量 + 状态机 + 审批并发）
--                                 表 B（replenishment_reviews 仅追加，无 updated_* / version）
--                           P4 硬规则（第 34 行）：replenishment_reviews 仅 INSERT 不可变
--   DATA_DICTIONARY.md      §4.1 字段集合 A（created_at/updated_at/created_by/updated_by）
--                                字段集合 B（created_at/created_by）
--   DATABASE_CONSTRAINTS.md §2.1 唯一约束 #18（uk_repl_run_sku_wh，★ 不带 team_id，R89/D-10）
--                           §3.2 索引 #36–#39（items 四条）+ #40–#42（reviews 三条）
--                           §4.1 枚举 CHECK（ck_repl_status / ck_repl_risk_level /
--                                ck_rr_action / ck_rr_from_status / ck_rr_to_status / ck_rr_role）
--                           §4.2 非负 CHECK（ck_repl_suggested_qty / ck_repl_qty）
--                           §4.3 ck_rr_comment_required（驳回必填原因）
--                           §5.2 外键 #18–#23（fk_repl_source / fk_repl_run / fk_repl_sku /
--                                fk_repl_warehouse / fk_repl_supplier / fk_rr_item，全 RESTRICT）
--   DESIGN_BASIS.md         §6 规则 1/6（表必含 team_id；索引 team_id 最左）
--                           §9.3 TeamRole / §9.11 ReplenishmentItemStatus / §9.12 ReviewAction
--                           §14.2 精度（数量 18,3；单价 18,4）
--
-- 范围：仅两表 DDL。**不做** Entity 之外的 Service / Controller / 状态机流转 / 建议生成（M5-T02+）。
--       **不修改** V1.0.0–V1.0.9（Flyway checksum 红线）。
--
-- ★★ 三条需要显式声明的「例外 / 反直觉」之处（复核重点，勿按字面「修正」）：
--   1) 【唯一键例外】uk_repl_run_sku_wh (run_id, sku_id, warehouse_id) **不带 team_id 前缀**。
--      依据 DATABASE_CONSTRAINTS #18 + DESIGN_REVIEW R89：run_id 已在 analysis_runs 中唯一绑定团队
--      （uk_run_team_no 带 team_id），「经团队归属父表派生的业务键」豁免 team_id 前缀。
--      **不要好心加上 team_id**——加了就与 #18 不符，且会让「一次运行内每 SKU×仓库一条建议」失效。
--   2) 【外键索引】4 条 items 外键中，fk_repl_run / fk_repl_sku / fk_repl_warehouse / fk_repl_supplier
--      的列都不是任何业务索引的最左列，InnoDB 会为它们各建一条同名自动索引；
--      fk_repl_source 同理（idx_repl_team_source 的 source_analysis_item_id 在第二位）。
--      业务索引 idx_repl_team_source 仍必须建（team_id 最左，供「由快照反查建议」），
--      二者列不同、用途不同，**不是重复索引**。
--   3) 【CHECK 口径裁定】ck_rr_comment_required 采用 TRIM(comment) <> ''（**不是**文档示例里的
--      comment <> ''）：utf8mb4_0900_ai_ci 是 NO PAD，实测 '  ' <> '' 为真 ⇒ 纯空白会被放行，
--      与 M5-T01 任务书 C2「REJECT 且 comment 为空/空白必须被拒绝」冲突。
--      项目负责人 2026-10-20 裁定改用 TRIM(comment) <> ''；DATABASE_CONSTRAINTS §4.3 的文本
--      留待项目负责人后续修订（本批 docs 零改动，G1）。
--
-- 团队隔离要点（DESIGN_BASIS §6）：
--   · 规则 1：两表均含 team_id **NOT NULL 且无 DEFAULT** → **不得登记**进
--     TenantTables.WITHOUT_TEAM_ID（黑名单仍只有 users / teams / refresh_tokens）。
--   · 规则 6：除唯一键这一处 R89 豁免，其余索引均 team_id 最左。
--
-- ★ 外键全部 RESTRICT：主数据不物理删除、快照不可变、审核轨迹不可丢；
--   全仓唯一的删除级联仍是 fk_rt_user（refresh_tokens），本批不得新增第二处级联。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) replenishment_items —— 补货建议（集合 A + version）
-- -----------------------------------------------------------------------------
CREATE TABLE replenishment_items (
    id                       BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id                  BIGINT UNSIGNED  NOT NULL COMMENT '团队 ID（服务端注入，不接受客户端传入；不可变）',
    source_analysis_item_id  BIGINT UNSIGNED  NOT NULL COMMENT '来源分析快照（追溯链核心；不可变）',
    run_id                   BIGINT UNSIGNED  NOT NULL COMMENT '冗余：来源运行（不可变）',
    sku_id                   BIGINT UNSIGNED  NOT NULL COMMENT 'SKU ID（不可变）',
    product_id               BIGINT UNSIGNED  NOT NULL COMMENT '冗余商品 ID（逻辑关联 products.id；不可变）',
    warehouse_id             BIGINT UNSIGNED  NOT NULL COMMENT '仓库 ID（不可变）',
    status                   VARCHAR(32)      NOT NULL DEFAULT 'SUGGESTED' COMMENT '建议状态：SUGGESTED/PENDING_REVIEW/APPROVED/CONVERTED/REJECTED/SUPERSEDED/CANCELLED（ReplenishmentItemStatus，§9.11）',
    suggested_qty            DECIMAL(18,3)    NOT NULL COMMENT '系统原始建议量（= 快照 qty_suggest；不可变，用于对比人工修改幅度）',
    qty                      DECIMAL(18,3)    NOT NULL COMMENT '当前生效数量（运营可改：仅 SUGGESTED/PENDING_REVIEW 阶段）',
    risk_level               VARCHAR(16)      NOT NULL DEFAULT 'NONE' COMMENT '冗余快照：风险等级 NONE/LOW/MEDIUM/HIGH/UNKNOWN（RiskLevel，§9.9；不可变）',
    reason                   VARCHAR(500)     NULL     COMMENT '建议理由摘要（规则摘要或 LLM 解释）',
    supplier_id              BIGINT UNSIGNED  NULL     COMMENT '指定供应商（APPROVED 前可改；逻辑关联 suppliers.id）',
    purchase_price           DECIMAL(18,4)    NULL     COMMENT '参考采购单价（单价 4 位小数，§14.2；APPROVED 前可改）',
    expected_arrival_date    DATE             NULL     COMMENT '期望到货日（APPROVED 前可改）',
    converted_po_item_ids    JSON             NULL     COMMENT '已生成的采购明细 ID 摘要（仅展示，§6-G；CONVERTED 时写入）',
    converted_at             DATETIME(3)      NULL     COMMENT '转采购时间（UTC；CONVERTED 时写入）',
    superseded_by_item_id    BIGINT UNSIGNED  NULL     COMMENT '被哪条新建议取代（逻辑自关联 replenishment_items.id，不建外键；SUPERSEDED 时写入）',
    submitted_at             DATETIME(3)      NULL     COMMENT '提交审核时间（UTC；状态流转时写入）',
    approved_at              DATETIME(3)      NULL     COMMENT '审批通过时间（UTC；状态流转时写入）',
    approved_by              BIGINT UNSIGNED  NULL     COMMENT '审批人（逻辑关联 users.id，不建外键；状态流转时写入）',
    closed_at                DATETIME(3)      NULL     COMMENT '进入终态时间（UTC；状态流转时写入）',
    remark                   VARCHAR(500)     NULL     COMMENT '备注（终态前可改）',
    -- 公共审计字段（集合 A + version：DATA_DICTIONARY §4.1；人工改量 + 状态机 + 审批并发）
    created_at               DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at               DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by               BIGINT UNSIGNED  NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by               BIGINT UNSIGNED  NULL     COMMENT '更新人（逻辑关联 users.id）',
    version                  INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '乐观锁版本（§7.4 表 A；审批并发主战场，实体须 @Version）',
    PRIMARY KEY (id),
    -- 唯一约束（DATABASE_CONSTRAINTS §2.1 #18；★ 不带 team_id，R89 / D-10 已裁定）
    CONSTRAINT uk_repl_run_sku_wh UNIQUE (run_id, sku_id, warehouse_id),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #36–#39；★ 均 team_id 最左）
    --   #36 最高频：建议列表按状态筛选、PENDING_REVIEW 待办
    --   #37 某 SKU 的建议历史
    --   #38 按运行批量拉取建议
    --   #39 追溯：由快照反查建议（与 fk_repl_source 的自动索引列不同，二者都要有）
    KEY idx_repl_team_status_time (team_id, status, created_at),
    KEY idx_repl_team_sku_time    (team_id, sku_id, created_at),
    KEY idx_repl_team_run         (team_id, run_id),
    KEY idx_repl_team_source      (team_id, source_analysis_item_id),
    -- 外键（DATABASE_CONSTRAINTS §5.2 #18–#22；全 RESTRICT：快照不可变、主数据不物理删除）
    CONSTRAINT fk_repl_source    FOREIGN KEY (source_analysis_item_id) REFERENCES analysis_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_repl_run       FOREIGN KEY (run_id) REFERENCES analysis_runs (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_repl_sku       FOREIGN KEY (sku_id) REFERENCES skus (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_repl_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_repl_supplier  FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（DATABASE_CONSTRAINTS §4.1 枚举 + §4.2 非负；共 4 条）
    CONSTRAINT ck_repl_status        CHECK (status IN ('SUGGESTED', 'PENDING_REVIEW', 'APPROVED', 'CONVERTED', 'REJECTED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT ck_repl_risk_level    CHECK (risk_level IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')),
    CONSTRAINT ck_repl_suggested_qty CHECK (suggested_qty >= 0),
    CONSTRAINT ck_repl_qty           CHECK (qty >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '补货建议：分析快照派生、有状态可审核可采购（owner = replenishment，§5.16）';

-- -----------------------------------------------------------------------------
-- 2) replenishment_reviews —— 审核记录（集合 B；仅 INSERT，永不 UPDATE/DELETE）
-- -----------------------------------------------------------------------------
CREATE TABLE replenishment_reviews (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id                BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（不可变）',
    replenishment_item_id  BIGINT UNSIGNED NOT NULL COMMENT '被操作的建议（不可变）',
    action                 VARCHAR(32)     NOT NULL COMMENT '动作：SUBMIT/WITHDRAW/APPROVE/REJECT/CANCEL（ReviewAction，§9.12）',
    from_status            VARCHAR(32)     NOT NULL COMMENT '变更前状态（ReplenishmentItemStatus，§9.11）',
    to_status              VARCHAR(32)     NOT NULL COMMENT '变更后状态（ReplenishmentItemStatus，§9.11）',
    comment                VARCHAR(500)    NULL     COMMENT '意见（REJECT 时必填，§9.11；CHECK 用 TRIM 拦空白）',
    qty_before             DECIMAL(18,3)   NULL     COMMENT '变更前的建议数量',
    qty_after              DECIMAL(18,3)   NULL     COMMENT '变更后的建议数量',
    reviewer_id            BIGINT UNSIGNED NOT NULL COMMENT '操作人（逻辑关联 users.id，不建外键）',
    reviewer_role          VARCHAR(32)     NOT NULL COMMENT '操作时角色快照：OWNER/ADMIN/OPERATOR/APPROVER/VIEWER（TeamRole，§9.3）',
    request_id             VARCHAR(64)     NULL     COMMENT '链路追踪 X-Request-Id',
    occurred_at            DATETIME(3)     NOT NULL COMMENT '操作发生时间（UTC；与 created_at 同值，语义更明确）',
    -- 公共审计字段（集合 B：DATA_DICTIONARY §4.1；★ 仅追加表，**无 updated_at / updated_by / version**）
    created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by             BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 唯一约束：**无**（§5.17）。同一条建议会被多次操作（提交→驳回→修改→再提交→通过），
    -- 每一步都必须留痕，唯一约束会摧毁轨迹。
    -- 索引（DATABASE_CONSTRAINTS §3.2 #40–#42；★ 均 team_id 最左）
    KEY idx_rr_team_item_time     (team_id, replenishment_item_id, occurred_at),
    KEY idx_rr_team_reviewer_time (team_id, reviewer_id, occurred_at),
    KEY idx_rr_team_action_time   (team_id, action, occurred_at),
    -- 外键（DATABASE_CONSTRAINTS §5.2 #23；RESTRICT：审核轨迹不可因建议删除而丢）
    CONSTRAINT fk_rr_item FOREIGN KEY (replenishment_item_id) REFERENCES replenishment_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（DATABASE_CONSTRAINTS §4.1 枚举 + §4.3；共 5 条）
    --   ★ ck_rr_comment_required 用 TRIM(comment) <> ''：NO PAD 排序规则下 '  ' <> '' 为真，
    --     只写 <> '' 会放行纯空白（M5-T01 任务书 C2，项目负责人 2026-10-20 裁定）。
    CONSTRAINT ck_rr_action           CHECK (action IN ('SUBMIT', 'WITHDRAW', 'APPROVE', 'REJECT', 'CANCEL')),
    CONSTRAINT ck_rr_from_status      CHECK (from_status IN ('SUGGESTED', 'PENDING_REVIEW', 'APPROVED', 'CONVERTED', 'REJECTED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT ck_rr_to_status        CHECK (to_status IN ('SUGGESTED', 'PENDING_REVIEW', 'APPROVED', 'CONVERTED', 'REJECTED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT ck_rr_role             CHECK (reviewer_role IN ('OWNER', 'ADMIN', 'OPERATOR', 'APPROVER', 'VIEWER')),
    CONSTRAINT ck_rr_comment_required CHECK (action <> 'REJECT' OR (comment IS NOT NULL AND TRIM(comment) <> ''))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '补货建议审核记录：追加式轨迹，仅 INSERT 不可变（owner = replenishment，§5.17）';
