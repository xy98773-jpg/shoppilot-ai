-- =============================================================================
-- V1.0.6__inventory.sql —— inventory 模块库存三表（M3-T08）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DATABASE_DESIGN.md      §5.8  replenishment_policies（逐列：类型/可空/默认值/含义/更新规则）
--                           §5.9  inventory_balances（同上；available_qty 为 STORED 生成列）
--                           §5.10 inventory_transactions（同上；仅 INSERT 的不可变账本）
--                           §7.4  表 A（replenishment_policies / inventory_balances 需 version）
--                                 表 B（inventory_transactions 仅 INSERT → 无 version）
--   DATA_DICTIONARY.md      §4.1 字段集合 A（created_at/updated_at/created_by/updated_by）
--                           §4.1 字段集合 B（仅追加：created_at + created_by，无 updated_*）
--                           §4.2 version 归属（仅 §7.4 表 A 的 12 张表）
--   DATABASE_CONSTRAINTS.md §2.1 唯一约束 #9–#12（uk_pol_team_sku_wh / uk_invbal_team_wh_sku /
--                                uk_invtxn_team_idem / uk_invtxn_team_reversed）
--                           §3.2 索引 #14–#20（七条，全部 team_id 最左）
--                           §4.1 枚举 CHECK（ck_invtxn_type 六值 / ck_invtxn_biz_type 六值）
--                           §4.2 数量非负与一致性 CHECK（ck_invbal_* / ck_invtxn_* 共 7 条）
--                           §5.2 外键 #6–#11（fk_pol_* / fk_invbal_* / fk_invtxn_*，全 RESTRICT）
--   DESIGN_BASIS.md         §9.6  InventoryTxnType（6 值）
--                           §6 规则 1/3/6（表必含 team_id；team_id 只来自服务端；索引 team_id 最左）
--                           §7.4 状态守卫替代 version 的适用条件
--   ER_DIAGRAM.md           §6.2 账本 ↔ 快照不分强绑：inventory_balances 不建指向
--                                inventory_transactions 的外键（last_txn_id 仅逻辑关联）
--
-- 范围：仅三张表（M3-T08，纯 DDL）。
--       **不做** InventoryWriteService / Entity / Mapper / Service / 端点（属 M3-T09 = B6）。
--       **不建** sales_daily（属 M3-T12）。
--       **不修改 V1.0.0 / V1.0.1 / V1.0.2 / V1.0.3 / V1.0.4 / V1.0.5**（Flyway 校验和红线）。
--
-- 团队隔离要点（DESIGN_BASIS §6）：
--   · 规则 1：三表**均含 team_id NOT NULL** → **不得登记**进 TenantTables.WITHOUT_TEAM_ID
--     （该黑名单仍只有 users / teams / refresh_tokens 三张无 team_id 列的表）。
--   · 规则 3：team_id **只来自服务端**（TeamContext），绝不接受客户端传入。
--   · 规则 6：七条二级索引与四条唯一键**全部 team_id 最左**（SEQ_IN_INDEX=1 逐条断言）。
--   · 跨团队的 sku_id / warehouse_id 组合**在 DB 层不拦**（team_id 不建外键），
--     同团队一致性由服务层校验（§5.8「团队隔离」/ §5.9「团队隔离」）。
--
-- ★ 两处口径取舍（均有据，非自由发挥）：
--   1) **不建 idx_invbal_team_wh**：DATABASE_DESIGN §5.9 的索引行写了
--      idx_invbal_team_wh (team_id, warehouse_id, sku_id)，但同句已注明
--      「与唯一键前缀重叠，可依赖唯一键」；DATABASE_CONSTRAINTS §3.2 的
--      inventory_balances 只有 #16 idx_invbal_team_sku 与 #17 idx_invbal_team_available 两条。
--      按 §1.2 冲突优先级（数据库设计同级取约束清单）+ 冗余索引会拖慢写入，
--      故**只建两条**，与约束清单 #16/#17 逐条对齐。
--   2) **available_qty 用 STORED 生成列**（§5.9 设计要点 4）：物理上不产生第二个可写真相，
--      同时可建索引排序（idx_invbal_team_available 依赖它）。
--      MySQL 8.0 限制：生成列表达式只能引用**同一行**的列（此处满足），
--      且**不可显式写入**（显式 INSERT/UPDATE 该列 → 3105 错误，测试覆盖）。
--
-- version 归属（DATABASE_DESIGN §7.4）：
--   replenishment_policies  —— 表 A（V1 + V2：参数读-改-写、多入口并发编辑）
--   inventory_balances      —— 表 A（V1 + V3：库存读-改-写，丢失更新 = 账实不符）
--   inventory_transactions  —— 表 B（**仅 INSERT**，不可变账本 → V1 不成立）→ 无 version、无 updated_at
-- =============================================================================

-- -----------------------------------------------------------------------------
-- replenishment_policies（补货策略参数）—— SKU×仓库维度的补货参数
-- 审计字段：集合 A + version（DATABASE_DESIGN §5.8 / §7.4 表 A）
-- 唯一约束：uk_pol_team_sku_wh —— 一 SKU 一仓库至多一条策略；保存走 upsert（不报冲突）
-- 所有参数列可空 = "未配置" → 触发 §10.2 / §10.5 的默认值回退
-- -----------------------------------------------------------------------------
CREATE TABLE replenishment_policies (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id           BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（服务端注入，不接受客户端传入；不可变）',
    sku_id            BIGINT UNSIGNED NOT NULL COMMENT 'SKU ID（不可变）',
    warehouse_id      BIGINT UNSIGNED NOT NULL COMMENT '仓库 ID（不可变）',
    lead_time_days    INT UNSIGNED    NULL     COMMENT '交期 L（天；可空=未配置，回退 SKU/供应商/默认）',
    coverage_days     INT UNSIGNED    NULL     COMMENT '覆盖天数 R（可空=未配置，默认 7，§10.6）',
    safety_stock_qty  DECIMAL(18,3)   NULL     COMMENT '安全库存绝对值（优先于公式，§10.3）',
    safety_ratio      DECIMAL(18,4)   NULL     COMMENT 'SAFETY_RATIO 覆盖值（可空=未配置，默认 0.5）',
    moq               DECIMAL(18,3)   NULL     COMMENT '最小起订量（可空=未配置）',
    order_multiple    DECIMAL(18,3)   NULL     COMMENT '采购倍数（可空=未配置）',
    qty_step          DECIMAL(18,3)   NULL     COMMENT '数量精度步长（可空=未配置）',
    window_days       INT UNSIGNED    NULL     COMMENT '统计窗口 N（可空=未配置，默认 28）',
    min_days          INT UNSIGNED    NULL     COMMENT '最少有效天数 MIN_DAYS（可空=未配置，默认 7）',
    cv_high           DECIMAL(18,4)   NULL     COMMENT '高波动阈值 CV_HIGH（可空=未配置，默认 1.0）',
    slow_moving_days  INT UNSIGNED    NULL     COMMENT '滞销窗口（可空=未配置，默认 30）',
    overstock_days    INT UNSIGNED    NULL     COMMENT '过高库存窗口（可空=未配置，默认 90）',
    is_enabled        TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用（停用走 0，不物理删除）',
    remark            VARCHAR(255)    NULL     COMMENT '备注',
    -- 公共审计字段（集合 A：DATA_DICTIONARY §4.1）
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by        BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by        BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    -- version（DATABASE_DESIGN §7.4 表 A：V1 参数读-改-写 + V2 多入口并发编辑）
    version           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    -- 唯一约束（DATABASE_CONSTRAINTS §2.1 #9；★ team_id 最左）
    CONSTRAINT uk_pol_team_sku_wh UNIQUE (team_id, sku_id, warehouse_id),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #14–#15；★ 全部 team_id 最左）
    KEY idx_pol_team_sku (team_id, sku_id, is_enabled),
    KEY idx_pol_team_wh  (team_id, warehouse_id),
    -- 外键（DATABASE_CONSTRAINTS §5.2 #6–#7；RESTRICT：主数据不物理删除）
    CONSTRAINT fk_pol_sku FOREIGN KEY (sku_id) REFERENCES skus (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pol_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '补货策略参数（inventory；SKU×仓库维度，含 version）';

-- -----------------------------------------------------------------------------
-- inventory_balances（库存余额）—— SKU×仓库的当前库存状态
-- 审计字段：集合 A + version（DATABASE_DESIGN §5.9 / §7.4 表 A）
-- 唯一约束：uk_invbal_team_wh_sku —— 一 SKU 一仓库一行（并发首次建行的冲突吸收点）
-- 核心不变式：余额只能由流水驱动；available_qty 为 STORED 生成列，物理上无第二个可写真相
-- -----------------------------------------------------------------------------
CREATE TABLE inventory_balances (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id         BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（服务端注入，不接受客户端传入；不可变）',
    warehouse_id    BIGINT UNSIGNED NOT NULL COMMENT '仓库 ID（不可变）',
    sku_id          BIGINT UNSIGNED NOT NULL COMMENT 'SKU ID（不可变）',
    on_hand_qty     DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '现有库存（已实际在仓、可直接出库；只能由流水驱动）',
    in_transit_qty  DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '在途库存（已下采购单未确认到货；由采购单状态变更驱动，无对应流水）',
    committed_qty   DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '已承诺库存（首期默认 0，仅保留写入通道）',
    available_qty   DECIMAL(18,3)   GENERATED ALWAYS AS (on_hand_qty - committed_qty) STORED
            COMMENT '可用库存 = on_hand_qty - committed_qty（§3，不含在途；STORED 生成列，不可写）',
    last_txn_id     BIGINT UNSIGNED NULL     COMMENT '最近一次流水 ID（逻辑关联 inventory_transactions.id，不建外键）',
    last_txn_at     DATETIME(3)     NULL     COMMENT '最近一次流水发生时间（UTC）',
    -- 公共审计字段（集合 A：DATA_DICTIONARY §4.1）
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by      BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by      BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    -- version（DATABASE_DESIGN §7.4 表 A：V1 读-改-写 + V3 丢失更新=账实不符）
    version         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    -- 唯一约束（DATABASE_CONSTRAINTS §2.1 #10；★ team_id 最左）
    CONSTRAINT uk_invbal_team_wh_sku UNIQUE (team_id, warehouse_id, sku_id),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #16–#17；★ 全部 team_id 最左）
    -- ★ 不建 idx_invbal_team_wh：与唯一键前缀重叠，约束清单只列两条（见文件头「口径取舍」1）
    KEY idx_invbal_team_sku       (team_id, sku_id),
    KEY idx_invbal_team_available (team_id, available_qty),
    -- 外键（DATABASE_CONSTRAINTS §5.2 #8–#9；RESTRICT：主数据不物理删除）
    CONSTRAINT fk_invbal_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_invbal_sku FOREIGN KEY (sku_id) REFERENCES skus (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（DATABASE_CONSTRAINTS §4.2：D-08 不允许负库存）
    CONSTRAINT ck_invbal_on_hand_non_negative    CHECK (on_hand_qty >= 0),
    CONSTRAINT ck_invbal_in_transit_non_negative CHECK (in_transit_qty >= 0),
    CONSTRAINT ck_invbal_committed_non_negative  CHECK (committed_qty >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '库存余额（inventory；SKU×仓库一行，available_qty 为 STORED 生成列，含 version）';

-- -----------------------------------------------------------------------------
-- inventory_transactions（库存流水）—— 库存变更的不可变账本
-- 审计字段：集合 B（DATA_DICTIONARY §4.1：仅 created_at + created_by，无 updated_*）
-- 可变性：**写入后永不 UPDATE / DELETE**；纠错只能写反向 REVERSAL 流水
-- 无 version（DATABASE_DESIGN §7.4 表 B：仅 INSERT → V1 不成立）
-- -----------------------------------------------------------------------------
CREATE TABLE inventory_transactions (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id          BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（服务端注入，不接受客户端传入；不可变）',
    warehouse_id     BIGINT UNSIGNED NOT NULL COMMENT '仓库 ID（不可变）',
    sku_id           BIGINT UNSIGNED NOT NULL COMMENT 'SKU ID（不可变）',
    txn_type         VARCHAR(32)     NOT NULL COMMENT 'InventoryTxnType（DESIGN_BASIS §9.6 六值之一；不可变）',
    qty_delta        DECIMAL(18,3)   NOT NULL COMMENT '本次变更量（带符号；0 无意义，CHECK 拒绝）',
    qty_before       DECIMAL(18,3)   NOT NULL COMMENT '变更前 on_hand_qty（流水自证，对账第一道防线）',
    qty_after        DECIMAL(18,3)   NOT NULL COMMENT '变更后 on_hand_qty（必须 = qty_before + qty_delta）',
    biz_type         VARCHAR(32)     NOT NULL COMMENT '业务来源类型（GOODS_RECEIPT/COUNT/MANUAL/IMPORT/SALE/REVERSAL）',
    biz_ref_id       BIGINT UNSIGNED NULL     COMMENT '业务单据 ID（多态，逻辑关联；不建外键）',
    biz_ref_no       VARCHAR(64)     NULL     COMMENT '业务单据编号（冗余展示）',
    idempotency_key  VARCHAR(128)    NOT NULL COMMENT '幂等键（团队内唯一；禁止用 UUID 作键）',
    reason           VARCHAR(255)    NULL     COMMENT '变更原因（盘点/手工调整必填）',
    reversed_txn_id  BIGINT UNSIGNED NULL     COMMENT '被冲销的原流水 ID（仅 REVERSAL；逻辑自关联，不建外键）',
    operator_id      BIGINT UNSIGNED NULL     COMMENT '操作人（逻辑关联 users.id）',
    occurred_at      DATETIME(3)     NOT NULL COMMENT '业务发生时间（UTC）',
    -- 公共审计字段（集合 B：DATA_DICTIONARY §4.1；仅追加，无 updated_at/updated_by、无 version）
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by       BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 唯一约束（DATABASE_CONSTRAINTS §2.1 #11–#12；★ team_id 最左）
    -- uk_invtxn_team_reversed 建在可空列上：MySQL 唯一索引允许多行 NULL → 「未被冲销」的流水可无限多
    CONSTRAINT uk_invtxn_team_idem     UNIQUE (team_id, idempotency_key),
    CONSTRAINT uk_invtxn_team_reversed UNIQUE (team_id, reversed_txn_id),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #18–#20；★ 全部 team_id 最左）
    KEY idx_invtxn_team_wh_sku_time (team_id, warehouse_id, sku_id, id DESC),
    KEY idx_invtxn_team_biz         (team_id, biz_type, biz_ref_id),
    KEY idx_invtxn_team_occurred    (team_id, occurred_at),
    -- 外键（DATABASE_CONSTRAINTS §5.2 #10–#11；RESTRICT：主数据不物理删除）
    CONSTRAINT fk_invtxn_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_invtxn_sku FOREIGN KEY (sku_id) REFERENCES skus (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（DATABASE_CONSTRAINTS §4.1 枚举 + §4.2 一致性与非负；共 7 条）
    CONSTRAINT ck_invtxn_type CHECK (txn_type IN ('INIT_IMPORT', 'PURCHASE_INBOUND', 'SALE_OUTBOUND',
        'COUNT_ADJUST', 'MANUAL_ADJUST', 'REVERSAL')),
    CONSTRAINT ck_invtxn_biz_type CHECK (biz_type IN ('GOODS_RECEIPT', 'COUNT', 'MANUAL',
        'IMPORT', 'SALE', 'REVERSAL')),
    CONSTRAINT ck_invtxn_consistency         CHECK (qty_after = qty_before + qty_delta),
    CONSTRAINT ck_invtxn_before_non_negative CHECK (qty_before >= 0),
    CONSTRAINT ck_invtxn_after_non_negative  CHECK (qty_after  >= 0),
    CONSTRAINT ck_invtxn_delta_non_zero      CHECK (qty_delta <> 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '库存流水（inventory；仅 INSERT 的不可变账本，无 version、无 updated_at）';
