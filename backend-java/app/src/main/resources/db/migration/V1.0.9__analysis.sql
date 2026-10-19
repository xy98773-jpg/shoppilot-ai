-- =============================================================================
-- V1.0.9__analysis.sql —— analysis 模块 analysis_runs / analysis_items 两表（M4-T02 / B14）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DATABASE_DESIGN.md      §5.14 analysis_runs（逐列：类型/可空/默认值/含义/更新规则）
--                           §5.15 analysis_items（逐列；★ 写入后不可变，仅 INSERT）
--                           §7.4  表 A（analysis_runs 无 version，用状态守卫）
--                                 表 B（analysis_items 仅追加，无 updated_* / version）
--   DATA_DICTIONARY.md      §4.1 字段集合 A（created_at/updated_at/created_by/updated_by）
--                                字段集合 B（created_at/created_by）
--   DATABASE_CONSTRAINTS.md §2.1 唯一约束 #15（uk_run_team_no）/ #16（uk_run_team_idem）
--                                 #17（uk_ai_run_sku_wh，★ 不带 team_id，见下）
--                           §3.2 索引 #31–#35（五条）
--                           §3.3 索引例外（idx_run_timeout 无 team_id 最左）
--                           §4.1 枚举 CHECK（ck_run_status / ck_run_llm_status /
--                                ck_run_scope_type / ck_ai_risk_level / ck_ai_lead_time_source）
--                           §4.2 非负 CHECK（ck_ai_d / ck_ai_qbase / ck_ai_qsuggest / ck_ai_ss；
--                                可空列一律先判 NULL）
--                           §4.3 区间 CHECK（ck_run_progress / ck_run_date_range）
--                           §5.2 外键 #15–#17（fk_ai_run / fk_ai_sku / fk_ai_warehouse，全 RESTRICT）
--   DESIGN_BASIS.md         §6 规则 1/3/6（表必含 team_id；team_id 只来自服务端；索引 team_id 最左）
--                           §13.2 Java 是唯一写方（Python 不感知团队，只回 JSON）
--                           §9.7 AnalysisRunStatus / §9.8 LlmStatus / §9.9 RiskLevel /
--                           §9.16 AnalysisScopeType / §9.17 LeadTimeSource
--
-- 范围：仅两表 DDL（M4-T02）。**不做** Entity / Mapper / Service / Controller / 端点（属 B15+）。
--       **不修改** V1.0.0–V1.0.8（Flyway checksum 红线）。
--
-- 迁移号口径（R94）：V1.0.9 归本批（B14），B13 未占用；V1.0.10 起留给后续批次。
--
-- ★★ 三条需要显式声明的「例外 / 反直觉」之处（复核重点，勿按字面「修正」）：
--   1) 【索引例外】idx_run_timeout (status, timeout_at) 的**最左列是 status，不是 team_id**。
--      依据 DATABASE_CONSTRAINTS §3.3：系统级超时扫描需要跨团队找出「已超时但仍在
--      RUNNING」的运行，若以 team_id 最左则无法一次扫描；该索引只被单个后台任务使用、
--      只取 id/team_id/timeout_at 后按团队逐条处理，不泄漏跨团队数据。
--      这是全仓唯一一条不满足「team_id 最左」的业务索引（另一类例外是 refresh_tokens
--      这类本来就没有 team_id 列的表）。索引名按 R103 裁定用 idx_run_timeout
--      （不是 DATABASE_DESIGN §5.14 正文里的 idx_run_team_timeout）。
--   2) 【唯一键例外】uk_ai_run_sku_wh (run_id, sku_id, warehouse_id) **不带 team_id 前缀**。
--      依据 DATABASE_CONSTRAINTS #17 + DATABASE_DESIGN §5.15 注 + DESIGN_REVIEW R89：
--      run_id 已在 analysis_runs 中唯一绑定团队（uk_run_team_no 带 team_id），
--      「经团队归属父表派生的业务键」豁免 team_id 前缀。**不要好心加上 team_id**，
--      加了就与 #17 不符，且会让「一次运行内每 SKU×仓库一条快照」的语义变松。
--   3) 【索引条数】analysis_items 只建 2 条业务索引。#34 与 §5.15 正文里的
--      idx_ai_team_run_risk 列完全相同（都是 team_id, run_id, risk_level），
--      后者是 §5.15 的笔误，按 R102 裁定**不建**。
--
-- 团队隔离要点（DESIGN_BASIS §6）：
--   · 规则 1：两表均含 team_id **NOT NULL 且无 DEFAULT** → **不得登记**进
--     TenantTables.WITHOUT_TEAM_ID（黑名单仍只有 users / teams / refresh_tokens）。
--     ★ M2_TenantIsolationDbIntegrationTest 会遍历 information_schema 逐表断言这一点，
--       写成 DEFAULT 0 会立刻变红，且不允许改那个测试来迁就。
--   · 规则 3：team_id 只来自服务端（TeamContext），不接受客户端传入。
--   · 规则 6：除 idx_run_timeout 这一处已声明例外，其余索引与唯一键均 team_id 最左。
--
-- ★ analysis_runs **无任何外键**（§5.14 明文）：warehouse_id / triggered_by 均为逻辑关联
--   —— 前者是可空统计维度，后者是触发人，加约束会妨碍历史追溯。
--   ★ 三条外键全部 RESTRICT（不得随子行消失而消失）：快照不可变、主数据不物理删除。
--     全仓唯一的「删除级联」仍是 fk_rt_user（refresh_tokens），本批不得新增第二处级联。
--   ★ InnoDB 会为「无最左前缀索引覆盖」的外键列自动建同名索引（fk_ai_sku / fk_ai_warehouse），
--   这是引擎行为、不属于 §3.2 的业务索引清单；业务索引仍以 idx_ 前缀的 2 条为准。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- analysis_runs（分析任务）—— 一次分析运行的执行实例 + 输入快照依据
-- 审计字段：集合 A（DATABASE_DESIGN §5.14 / §7.4 表 A：**无 version**，用状态守卫）
-- 唯一约束：uk_run_team_no（团队内运行编号）/ uk_run_team_idem（团队内创建幂等）
-- 设计要点：本表是「输入快照依据」的载体——scope_* / params_json / algorithm_version /
--          prompt_version / model_name / request_id / idempotency_key / triggered_by
--          写入后不可变；执行期只允许更新进度与统计列。
-- -----------------------------------------------------------------------------
CREATE TABLE analysis_runs (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键（业务 ID，由 Java 生成后传给 Python，§13.2）',
    team_id           BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（服务端注入，不接受客户端传入；不可变）',
    run_no            VARCHAR(64)     NOT NULL COMMENT '运行编号（团队内唯一；不可变）',
    status            VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT '运行状态：PENDING/RUNNING/SUCCEEDED/DEGRADED/FAILED/TIMEOUT/CANCELLED（AnalysisRunStatus，§9.7；单向流转，终态不可变）',
    scope_type        VARCHAR(32)     NOT NULL COMMENT '分析范围类型：ALL_ACTIVE/SELECTED_SKUS/CATEGORY（AnalysisScopeType，§9.16；不可变）',
    scope_json        JSON            NULL     COMMENT '范围明细（SELECTED_SKUS 的 SKU 列表 / CATEGORY 的类目列表；不可变）',
    warehouse_id      BIGINT UNSIGNED NULL     COMMENT '限定仓库（NULL = 默认仓；逻辑关联 warehouses.id，不建外键；不可变）',
    params_json       JSON            NULL     COMMENT '运行参数（window_days/min_days/budget_limit 等，§10.6；不可变）',
    algorithm_version VARCHAR(64)     NOT NULL COMMENT '算法版本，如 avg-ma-3sigma-v1（§10.1；追溯依据，不可变）',
    prompt_version    VARCHAR(64)     NULL     COMMENT 'Prompt 模板版本（不可变）',
    model_name        VARCHAR(128)    NULL     COMMENT 'LLM 模型名（来自配置，不写密钥；不可变）',
    llm_status        VARCHAR(32)     NOT NULL DEFAULT 'NOT_REQUESTED' COMMENT 'LLM 阶段状态：NOT_REQUESTED/PENDING/SUCCEEDED/FAILED/TIMEOUT/VALIDATION_FAILED/SKIPPED（LlmStatus，§9.8）',
    llm_error_message VARCHAR(500)    NULL     COMMENT 'LLM 失败原因（DEGRADED 时非空）',
    data_start_date   DATE            NULL     COMMENT '输入销售数据最早日期（执行期写入后固定）',
    data_end_date     DATE            NULL     COMMENT '输入销售数据最晚日期（执行期写入后固定）',
    summary_json      JSON            NULL     COMMENT '结果摘要（各风险等级 SKU 数、建议数、截断数；终态时写入）',
    total_skus        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '计划分析 SKU 数',
    processed_skus    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '已完成 SKU 数',
    risk_sku_count    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '存在风险的 SKU 数（终态时写入）',
    suggestion_count  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '生成补货建议数（终态时写入）',
    progress          TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '进度 0–100（§9.7）',
    current_step      VARCHAR(64)     NULL     COMMENT '当前步骤名',
    error_code        VARCHAR(32)     NULL     COMMENT '失败错误码（ANL-7002/ANL-7003/ANL-7004…）',
    error_message     VARCHAR(500)    NULL     COMMENT '失败原因',
    request_id        VARCHAR(64)     NULL     COMMENT '链路追踪 X-Request-Id（§13.3；不可变）',
    idempotency_key   VARCHAR(128)    NULL     COMMENT '创建幂等键（可空 ⇒ 多行 NULL 合法，即未指定幂等键的请求不受幂等约束；不可变）',
    triggered_by      BIGINT UNSIGNED NULL     COMMENT '触发人（逻辑关联 users.id，不建外键；不可变）',
    started_at        DATETIME(3)     NULL     COMMENT '开始执行时间（RUNNING 时写入）',
    finished_at       DATETIME(3)     NULL     COMMENT '结束时间（终态时写入）',
    timeout_at        DATETIME(3)     NULL     COMMENT '超时截止（默认创建 + 300s，§13.4；不可变）',
    -- 公共审计字段（集合 A：DATA_DICTIONARY §4.1；无 version，见文件头 §7.4 表 A）
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by        BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by        BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 唯一约束（DATABASE_CONSTRAINTS §2.1 #15/#16；★ 均 team_id 最左）
    CONSTRAINT uk_run_team_no   UNIQUE (team_id, run_no),
    CONSTRAINT uk_run_team_idem UNIQUE (team_id, idempotency_key),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #31–#33）
    --   #31 最高频：任务列表 + ANL-7001「是否有进行中任务」判定
    --   #32 运行历史时间线
    --   #33 系统级超时扫描 —— ★ status 最左，team_id 例外，依据 §3.3（见文件头第 1 条）
    KEY idx_run_team_status  (team_id, status, created_at),
    KEY idx_run_team_created (team_id, created_at),
    KEY idx_run_timeout      (status, timeout_at),
    -- CHECK（DATABASE_CONSTRAINTS §4.1 枚举 + §4.3 区间；共 5 条）
    --   ★ ck_run_date_range 两端都可空，必须「先判 NULL 再比大小」；
    --     NULL 参与比较结果为 NULL，既不拦截也不报错（静默陷阱）。
    CONSTRAINT ck_run_status      CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'DEGRADED', 'FAILED', 'TIMEOUT', 'CANCELLED')),
    CONSTRAINT ck_run_llm_status  CHECK (llm_status IN ('NOT_REQUESTED', 'PENDING', 'SUCCEEDED', 'FAILED', 'TIMEOUT', 'VALIDATION_FAILED', 'SKIPPED')),
    CONSTRAINT ck_run_scope_type  CHECK (scope_type IN ('ALL_ACTIVE', 'SELECTED_SKUS', 'CATEGORY')),
    CONSTRAINT ck_run_progress    CHECK (progress <= 100),
    CONSTRAINT ck_run_date_range  CHECK (data_start_date IS NULL OR data_end_date IS NULL OR data_start_date <= data_end_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '分析任务（analysis；一次运行的输入快照依据 + 状态机，无 version 用状态守卫）';

-- -----------------------------------------------------------------------------
-- analysis_items（分析明细 · 计算事实快照）—— 一次运行内单个 SKU×仓库 的完整计算快照
-- 审计字段：集合 B（DATA_DICTIONARY §4.1：仅 created_at + created_by）
-- 唯一约束：uk_ai_run_sku_wh（★ 不带 team_id，R89 裁定，见文件头第 2 条）
-- 设计要点：**写入后不可变，仅 INSERT**；任何「重新分析」都产生新 run，不修改旧快照。
--          故**无 updated_at / updated_by / version**（§5.15 明文）。
-- -----------------------------------------------------------------------------
CREATE TABLE analysis_items (
    id                       BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id                  BIGINT UNSIGNED  NOT NULL COMMENT '团队 ID（服务端注入；不可变）',
    run_id                   BIGINT UNSIGNED  NOT NULL COMMENT '所属分析运行（fk_ai_run；不可变）',
    sku_id                   BIGINT UNSIGNED  NOT NULL COMMENT 'SKU ID（fk_ai_sku；不可变）',
    product_id               BIGINT UNSIGNED  NOT NULL COMMENT '冗余商品 ID（类目聚合；逻辑关联 products.id，不建外键；不可变）',
    warehouse_id             BIGINT UNSIGNED  NOT NULL COMMENT '仓库 ID（fk_ai_warehouse；不可变）',
    on_hand_qty              DECIMAL(18,3)    NOT NULL COMMENT '快照：现有库存（无默认值，必须显式写入）',
    in_transit_qty           DECIMAL(18,3)    NOT NULL DEFAULT 0 COMMENT '快照：在途库存',
    effective_in_transit_qty DECIMAL(18,3)    NOT NULL DEFAULT 0 COMMENT '快照：有效在途（§10.4 窗口过滤后）',
    committed_qty            DECIMAL(18,3)    NOT NULL DEFAULT 0 COMMENT '快照：已承诺库存',
    inventory_position_qty   DECIMAL(18,3)    NOT NULL DEFAULT 0 COMMENT '快照：IP = on_hand + effective_in_transit − committed（§3）',
    available_qty            DECIMAL(18,3)    NOT NULL DEFAULT 0 COMMENT '快照：available = on_hand − committed（展示口径）',
    avg_daily_sales          DECIMAL(18,3)    NULL     COMMENT 'D（§10.1）；数据不足时为 NULL',
    sales_window_days        INT UNSIGNED     NULL     COMMENT '统计窗口 N（默认 28）',
    valid_days               INT UNSIGNED     NULL     COMMENT '有效销售天数 n（§10.1）',
    total_sales_qty          DECIMAL(18,3)    NULL     COMMENT '窗口内总销量',
    cv                       DECIMAL(18,4)    NULL     COMMENT '波动率 CV（★ 精度 4 位，与其余数量列的 3 位不同；mean=0 时为 NULL）',
    lead_time_days           INT UNSIGNED     NULL     COMMENT '采用值 L',
    lead_time_source         VARCHAR(32)      NULL     COMMENT 'L 来源：POLICY/SKU/SUPPLIER/DEFAULT（§10.2、§11.2）',
    coverage_days            INT UNSIGNED     NULL     COMMENT '采用值 R',
    safety_stock_qty         DECIMAL(18,3)    NULL     COMMENT '采用值 SS（§10.3）',
    volatility_adjusted      TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '是否因高波动上浮 50%（§10.3）',
    qty_base                 DECIMAL(18,3)    NULL     COMMENT 'Q_base（§10.2）',
    qty_suggest              DECIMAL(18,3)    NULL     COMMENT 'Q_suggest（§10.5，套用 MOQ/倍数/预算后）',
    moq_applied              DECIMAL(18,3)    NULL     COMMENT '实际生效的 MOQ',
    order_multiple_applied   DECIMAL(18,3)    NULL     COMMENT '实际生效的采购倍数',
    truncated_by_budget      TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '是否因预算被截断（§10.5）',
    risk_level               VARCHAR(16)      NOT NULL DEFAULT 'NONE' COMMENT '风险等级：NONE/LOW/MEDIUM/HIGH/UNKNOWN（RiskLevel，§9.9；MAX 合成，§11.1）',
    risk_types               JSON             NULL     COMMENT 'RiskType 数组（§9.10，如 ["STOCKOUT","LOW_STOCK"]）',
    risk_detail_json         JSON             NULL     COMMENT '命中规则明细（规则代码 → 判定值），用于解释与测试',
    data_quality_json        JSON             NULL     COMMENT '数据质量检查结果（缺口、异常值、样本量）',
    llm_explanation          TEXT             NULL     COMMENT 'LLM 生成的解释文本（降级时为 NULL）',
    llm_detail_json          JSON             NULL     COMMENT '条目级 LLM 结果明细：llm_status/置信度/不确定项/检查清单（DESIGN_BASIS §8）；降级时为 NULL',
    -- 公共审计字段（集合 B：DATA_DICTIONARY §4.1；仅追加表，**无 updated_at / updated_by / version**）
    created_at               DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by               BIGINT UNSIGNED  NULL     COMMENT '创建人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 唯一约束（DATABASE_CONSTRAINTS §2.1 #17；★ 不带 team_id，R89 / D-10 已裁定）
    CONSTRAINT uk_ai_run_sku_wh UNIQUE (run_id, sku_id, warehouse_id),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #34–#35；★ 均 team_id 最左）
    --   #34 最高频：结果页按运行 + 风险等级筛选
    --   #35 历史对比：某 SKU 的 D/IP 时间序列（解释「为何这次建议变了」）
    --   ★ 不建 idx_ai_team_run_risk —— 与 #34 列完全相同，是 §5.15 的笔误（R102）
    KEY idx_ai_team_run      (team_id, run_id, risk_level),
    KEY idx_ai_team_sku_time (team_id, sku_id, created_at),
    -- 外键（DATABASE_CONSTRAINTS §5.2 #15–#17；全 RESTRICT：快照不可变、主数据不物理删除）
    CONSTRAINT fk_ai_run       FOREIGN KEY (run_id) REFERENCES analysis_runs (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_sku       FOREIGN KEY (sku_id) REFERENCES skus (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（DATABASE_CONSTRAINTS §4.1 枚举 + §4.2/§4.3 非负；共 6 条）
    --   ★ 四条非负 CHECK 全部「先判 NULL 再比大小」：
    --     avg_daily_sales / qty_base / qty_suggest / safety_stock_qty 均可空，
    --     若写成 col >= 0，NULL 参与比较得 NULL —— 既不拦截也不报错，约束形同虚设。
    CONSTRAINT ck_ai_risk_level       CHECK (risk_level IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')),
    CONSTRAINT ck_ai_lead_time_source CHECK (lead_time_source IS NULL OR lead_time_source IN ('POLICY', 'SKU', 'SUPPLIER', 'DEFAULT')),
    CONSTRAINT ck_ai_d                CHECK (avg_daily_sales IS NULL OR avg_daily_sales >= 0),
    CONSTRAINT ck_ai_qbase            CHECK (qty_base IS NULL OR qty_base >= 0),
    CONSTRAINT ck_ai_qsuggest         CHECK (qty_suggest IS NULL OR qty_suggest >= 0),
    CONSTRAINT ck_ai_ss               CHECK (safety_stock_qty IS NULL OR safety_stock_qty >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '分析明细（analysis；SKU×仓库 计算事实快照，仅 INSERT 不可变，无 updated_*/version）';
