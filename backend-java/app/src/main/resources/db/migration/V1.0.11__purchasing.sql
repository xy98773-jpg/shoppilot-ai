-- =============================================================================
-- V1.0.11__purchasing.sql —— purchasing 模块采购四表（M6-T01 数据模型与迁移）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DATABASE_DESIGN.md      §5.18 purchase_orders（逐列：类型/可空/默认值/含义/更新规则）
--                           §5.19 purchase_order_items（集合 A + version；received_qty 累加
--                                  存在丢失更新风险，version 为必须）
--                           §5.20 goods_receipts（集合 A + version；idempotency_key NOT NULL）
--                           §5.21 goods_receipt_items（集合 B：仅 created_at/created_by，
--                                  确认后不可变，**无 version**）
--   DATA_DICTIONARY.md      §4.1 字段集合 A（created_at/updated_at/created_by/updated_by）
--                                字段集合 B（created_at/created_by）
--   DATABASE_CONSTRAINTS.md §2.1 唯一约束 #19–#24（6 条）
--                           §3.2 索引 #43–#53（11 条，全部 team_id 最左）
--                           §4.1 枚举 CHECK（ck_po_status / ck_po_source / ck_gr_status）
--                           §4.2 非负/范围 CHECK（ck_poi_ordered_positive /
--                                ck_poi_received_range / ck_po_total_qty / ck_po_received_qty /
--                                ck_gr_total_qty / ck_gri_received_positive）
--                           §5.2 外键 #24–#32（9 条，全 RESTRICT）
--                           第 75 行：uk_poi_po_lino / uk_gri_receipt_poi 不带 team_id
--   DESIGN_BASIS.md         §9.13 PurchaseOrderStatus（6 值）/ §9.14 GoodsReceiptStatus（4 值）
--                           §9.17 PurchaseOrderSource（MANUAL/REPLENISHMENT）
--                           §14.2 精度（数量 18,3；单价 18,4；金额 18,2）
--
-- 范围：仅四表 DDL。**不做** Service / Controller / 状态机流转 / 到货入库（M6-T02+）。
--       **不修改** V1.0.0–V1.0.10（Flyway checksum 红线）。
--
-- 迁移号口径（R94）：V1.0.11 为下一未用号 ⇒ 迁移版本数 11 → 12。
--
-- ★★ 四条需要显式声明的「例外 / 反直觉」之处（复核重点，勿按字面「修正」）：
--   1) 【version 三有一无】`purchase_orders` / `purchase_order_items` / `goods_receipts`
--      **必须**有 `version`（`purchase_order_items.received_qty` 累加是典型丢失更新场景，
--      §6-I）；`goods_receipt_items` **没有** `version`（集合 B，确认后不可变）——
--      不要「好心补齐」，那会把「只追加表」变成可更新表。
--   2) 【两个幂等键的 NULL 性相反】`purchase_orders.idempotency_key` **可空**；
--      `goods_receipts.idempotency_key` **NOT NULL**（收货确认产生不可逆库存变更，
--      必须强制幂等保护）。唯一约束分别为 uk_po_team_idem / uk_gr_team_idem。
--   3) 【唯一键不带 team_id 的两条】`uk_poi_po_lino (purchase_order_id, line_no)` 与
--      `uk_gri_receipt_poi (goods_receipt_id, purchase_order_item_id)` 由**同团队的父表**
--      派生团队维度（DATABASE_CONSTRAINTS 第 75 行明文）⇒ **不要好心加 team_id**。
--   4) 【CHECK 只建 9 条】SSOT 另登记了 `ck_gri_receipt_qty`，与 `ck_gri_received_positive`
--      **语义重复**（同为 `goods_receipt_items.received_qty > 0`）⇒ 只建一条，命名取
--      `ck_gri_received_positive`（§5.21 口径），重复登记申报 R156。
--
-- 另：§5.19 正文提到的 `idx_poi_team_pending` 是**可选项**，不在权威索引清单 #43–#53 内
--   ⇒ 本批**不建**（本表业务索引共 11 条，与 #43–#53 逐条对齐）。
--
-- 团队隔离要点（DESIGN_BASIS §6）：
--   · 规则 1：四表均含 team_id **NOT NULL 且无 DEFAULT** → **不得登记**进
--     TenantTables.WITHOUT_TEAM_ID（黑名单仍只有 users / teams / refresh_tokens）。
--   · `purchase_orders` 写入时必须校验 `supplier.team_id == warehouse.team_id == team_id`
--     （§6 规则 4 的显式例子）；`confirmed_by` / `operator_id` / `reversed_by` 逻辑关联
--     users.id，不建外键。
--
-- ★ 外键全 RESTRICT（主数据不物理删除、单据链不可断）：全仓唯一的删除级联仍是
--   `fk_rt_user`（refresh_tokens），本批**不新增 CASCADE**。
-- ★ `purchase_order_items.replenishment_item_id` 为**跨模块逻辑关联**（§6-G），
--   **不建外键**（避免 purchasing → replenishment 的物理耦合）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) purchase_orders —— 采购单（集合 A + version）
-- -----------------------------------------------------------------------------
CREATE TABLE purchase_orders (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id               BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（服务端注入，不接受客户端传入；不可变）',
    po_no                 VARCHAR(64)     NOT NULL COMMENT '采购单号（团队内唯一 uk_po_team_no；不可变）',
    supplier_id           BIGINT UNSIGNED NOT NULL COMMENT '供应商（仅 DRAFT 可更新）',
    warehouse_id          BIGINT UNSIGNED NOT NULL COMMENT '到货仓库（仅 DRAFT 可更新）',
    status                VARCHAR(32)     NOT NULL DEFAULT 'DRAFT' COMMENT '采购状态：DRAFT/PENDING/ORDERED/PARTIALLY_RECEIVED/RECEIVED/CANCELLED（PurchaseOrderStatus，§9.13）',
    source                VARCHAR(32)     NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL/REPLENISHMENT（不可变）',
    expected_arrival_date DATE            NULL     COMMENT '期望到货日（在途窗口查询输入，§10.4；仅 DRAFT/PENDING 可更新）',
    total_qty             DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '下单总数量（明细变更时同事务重算，不接受客户端直接改）',
    received_qty          DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '已到货总数量（确认到货时同事务累加）',
    total_amount          DECIMAL(18,2)   NOT NULL DEFAULT 0 COMMENT '总金额（明细变更时同事务重算）',
    remark                VARCHAR(500)    NULL     COMMENT '备注（仅 DRAFT 可更新）',
    idempotency_key       VARCHAR(128)    NULL     COMMENT '创建幂等键（★ 可空：多行 NULL 合法；不可变）',
    confirmed_at          DATETIME(3)     NULL     COMMENT 'DRAFT→PENDING 时间（视为审批，D-06；状态流转时写入）',
    confirmed_by          BIGINT UNSIGNED NULL     COMMENT '确认人（须 APPROVER；逻辑关联 users.id，不建外键）',
    ordered_at            DATETIME(3)     NULL     COMMENT 'PENDING→ORDERED 时间（状态流转时写入）',
    cancelled_at          DATETIME(3)     NULL     COMMENT '取消时间（状态流转时写入）',
    cancel_reason         VARCHAR(255)    NULL     COMMENT '取消原因（状态流转时写入）',
    closed_at             DATETIME(3)     NULL     COMMENT '终态时间（状态流转时写入）',
    -- 公共审计字段（集合 A：DATA_DICTIONARY §4.1）+ version（§7.4 表 A）
    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by            BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by            BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    version               INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本（状态流转并发；实体须 @Version）',
    PRIMARY KEY (id),
    -- 唯一约束（DATABASE_CONSTRAINTS §2.1 #19–#20）
    CONSTRAINT uk_po_team_no  UNIQUE (team_id, po_no),
    CONSTRAINT uk_po_team_idem UNIQUE (team_id, idempotency_key),
    -- 索引（§3.2 #43–#45；★ 均 team_id 最左）
    --   #43 最高频：采购单列表 + 待收货待办
    --   #44 按供应商统计未完结采购单
    --   #45 在途窗口查询（§10.4 输入）
    KEY idx_po_team_status_time     (team_id, status, created_at),
    KEY idx_po_team_supplier_status (team_id, supplier_id, status),
    KEY idx_po_team_expected        (team_id, expected_arrival_date),
    -- 外键（§5.2 #24–#25；全 RESTRICT：主数据不物理删除）
    CONSTRAINT fk_po_supplier  FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_po_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（§4.1 枚举 + §4.2 非负；共 4 条；数值列均 NOT NULL，无需先判 NULL）
    CONSTRAINT ck_po_status       CHECK (status IN ('DRAFT', 'PENDING', 'ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT ck_po_source       CHECK (source IN ('MANUAL', 'REPLENISHMENT')),
    CONSTRAINT ck_po_total_qty    CHECK (total_qty >= 0),
    CONSTRAINT ck_po_received_qty CHECK (received_qty >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '采购单：承载采购状态机与汇总数量（owner = purchasing，§5.18）';

-- -----------------------------------------------------------------------------
-- 2) purchase_order_items —— 采购明细（集合 A + version）
-- -----------------------------------------------------------------------------
CREATE TABLE purchase_order_items (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id               BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（不可变）',
    purchase_order_id     BIGINT UNSIGNED NOT NULL COMMENT '所属采购单（不可变）',
    line_no               INT UNSIGNED    NOT NULL COMMENT '行号（1 起；单内唯一 uk_poi_po_lino；不可变）',
    sku_id                BIGINT UNSIGNED NOT NULL COMMENT '采购 SKU（仅 DRAFT 可更新）',
    product_id            BIGINT UNSIGNED NOT NULL COMMENT '冗余商品 ID（逻辑关联 products.id；不可变）',
    replenishment_item_id BIGINT UNSIGNED NULL     COMMENT '来源补货建议（★ 跨模块逻辑关联，§6-G，不建外键；不可变）',
    ordered_qty           DECIMAL(18,3)   NOT NULL COMMENT '下单数量（仅 DRAFT 可更新；不可减少到低于 received_qty）',
    received_qty          DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '累计已收数量（★ 只增不减，除冲销；确认到货同事务累加）',
    unit_price            DECIMAL(18,4)   NULL     COMMENT '单价（仅 DRAFT 可更新）',
    amount                DECIMAL(18,2)   NULL     COMMENT '行金额 = ordered_qty × unit_price（与数量/单价同事务重算）',
    expected_arrival_date DATE            NULL     COMMENT '行级期望到货日（覆盖单头；仅 DRAFT/PENDING 可更新）',
    remark                VARCHAR(255)    NULL     COMMENT '备注（仅 DRAFT 可更新）',
    -- 公共审计字段（集合 A）+ version（★ 必须：received_qty 累加是典型丢失更新场景，§6-I）
    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by            BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by            BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    version               INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本（★ received_qty 累加并发；实体须 @Version）',
    PRIMARY KEY (id),
    -- 唯一约束（§2.1 #21；★ 不带 team_id，由同团队父表派生，DATABASE_CONSTRAINTS 第 75 行）
    CONSTRAINT uk_poi_po_lino UNIQUE (purchase_order_id, line_no),
    -- 索引（§3.2 #46–#48；★ 均 team_id 最左）
    --   #46 采购单详情页；汇总与状态重算（带 line_no 保证有序返回）
    --   #47 按 SKU 查采购历史
    --   #48 追溯：建议 → 采购明细（§6-G）
    KEY idx_poi_team_po   (team_id, purchase_order_id, line_no),
    KEY idx_poi_team_sku  (team_id, sku_id),
    KEY idx_poi_team_repl (team_id, replenishment_item_id),
    -- 外键（§5.2 #26–#27；全 RESTRICT）
    CONSTRAINT fk_poi_po  FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_poi_sku FOREIGN KEY (sku_id) REFERENCES skus (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（§4.2；共 2 条）
    --   ★ ck_poi_received_range 是单行兜底（不允许超额收货）；跨单据「Σ到货 ≤ ordered」
    --     由服务层在确认时校验（PO-9002），单行 CHECK 看不到其它行。
    CONSTRAINT ck_poi_ordered_positive CHECK (ordered_qty > 0),
    CONSTRAINT ck_poi_received_range    CHECK (received_qty >= 0 AND received_qty <= ordered_qty)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '采购明细：行状态由 ordered_qty/received_qty 派生，无行级 status / pending_qty（owner = purchasing，§5.19）';

-- -----------------------------------------------------------------------------
-- 3) goods_receipts —— 到货单（集合 A + version）
-- -----------------------------------------------------------------------------
CREATE TABLE goods_receipts (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id           BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（不可变）',
    receipt_no        VARCHAR(64)     NOT NULL COMMENT '到货单号（团队内唯一 uk_gr_team_no；不可变）',
    purchase_order_id BIGINT UNSIGNED NOT NULL COMMENT '关联采购单（不可变）',
    warehouse_id      BIGINT UNSIGNED NOT NULL COMMENT '入库仓库（不可变）',
    status            VARCHAR(32)     NOT NULL DEFAULT 'DRAFT' COMMENT '到货状态：DRAFT/CONFIRMED/REVERSED/CANCELLED（GoodsReceiptStatus，§9.14）',
    receipt_date      DATE            NOT NULL COMMENT '到货业务日期（不可变）',
    total_qty         DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '到货总数量（明细变更时同事务重算）',
    total_amount      DECIMAL(18,2)   NULL     COMMENT '到货总金额（明细变更时同事务重算）',
    idempotency_key   VARCHAR(128)    NOT NULL COMMENT '★ 幂等键 NOT NULL（收货不可逆，必须强制幂等保护；不可变）',
    remark            VARCHAR(500)    NULL     COMMENT '备注（仅 DRAFT 可更新）',
    operator_id       BIGINT UNSIGNED NULL     COMMENT '录入人（逻辑关联 users.id；不可变）',
    confirmed_at      DATETIME(3)     NULL     COMMENT '确认时间（CONFIRMED 时写入）',
    confirmed_by      BIGINT UNSIGNED NULL     COMMENT '确认人（CONFIRMED 时写入）',
    cancelled_at      DATETIME(3)     NULL     COMMENT '作废时间（CANCELLED 时写入）',
    reversed_at       DATETIME(3)     NULL     COMMENT '冲销时间（CONFIRMED→REVERSED 时写入）',
    reversed_by       BIGINT UNSIGNED NULL     COMMENT '冲销人（逻辑关联 users.id）',
    -- 公共审计字段（集合 A）+ version
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by        BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by        BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    version           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本（实体须 @Version）',
    PRIMARY KEY (id),
    -- 唯一约束（§2.1 #22–#23）
    CONSTRAINT uk_gr_team_no  UNIQUE (team_id, receipt_no),
    CONSTRAINT uk_gr_team_idem UNIQUE (team_id, idempotency_key),
    -- 索引（§3.2 #49–#51；★ 均 team_id 最左）
    --   #49 采购单详情页的到货记录
    --   #50 待确认到货待办
    --   #51 到货统计
    KEY idx_gr_team_po_status   (team_id, purchase_order_id, status),
    KEY idx_gr_team_status_time (team_id, status, created_at),
    KEY idx_gr_team_date        (team_id, receipt_date),
    -- 外键（§5.2 #28–#29；全 RESTRICT）
    CONSTRAINT fk_gr_po        FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_gr_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（§4.1 枚举 + §4.2 非负；共 2 条）
    CONSTRAINT ck_gr_status    CHECK (status IN ('DRAFT', 'CONFIRMED', 'REVERSED', 'CANCELLED')),
    CONSTRAINT ck_gr_total_qty CHECK (total_qty >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '到货单：确认后触发出库入库，CONFIRMED 不可作废只能冲销（owner = purchasing，§5.20）';

-- -----------------------------------------------------------------------------
-- 4) goods_receipt_items —— 到货明细（集合 B：仅 created_at/created_by，★ 无 version）
-- -----------------------------------------------------------------------------
CREATE TABLE goods_receipt_items (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id               BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（不可变）',
    goods_receipt_id      BIGINT UNSIGNED NOT NULL COMMENT '所属到货单（不可变）',
    purchase_order_item_id BIGINT UNSIGNED NOT NULL COMMENT '对应采购明细（不可变）',
    sku_id                BIGINT UNSIGNED NOT NULL COMMENT '冗余 SKU ID（入账免 JOIN；不可变）',
    received_qty          DECIMAL(18,3)   NOT NULL COMMENT '★ 本次到货数量（仅到货单 DRAFT 时可改）',
    unit_price            DECIMAL(18,4)   NULL     COMMENT '本次到货单价（仅 DRAFT 可改）',
    amount                DECIMAL(18,2)   NULL     COMMENT '本行金额（仅 DRAFT 可改）',
    batch_no              VARCHAR(64)     NULL     COMMENT '供应方批号（仅记录；仅 DRAFT 可改）',
    remark                VARCHAR(255)    NULL     COMMENT '备注（仅 DRAFT 可改）',
    -- 公共审计字段（集合 B：DATA_DICTIONARY §4.1；★ 仅追加表，**无 updated_at / updated_by / version**）
    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by            BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 唯一约束（§2.1 #24；★ 不带 team_id，由同团队父表派生，DATABASE_CONSTRAINTS 第 75 行）
    --   语义：一张到货单内同一条采购明细只能有一行（防同单重复入库）
    CONSTRAINT uk_gri_receipt_poi UNIQUE (goods_receipt_id, purchase_order_item_id),
    -- 索引（§3.2 #52–#53；★ 均 team_id 最左）
    --   #52 到货单详情页
    --   #53 最高频：某采购明细的到货历史（received_qty 重算依据）
    KEY idx_gri_team_receipt  (team_id, goods_receipt_id),
    KEY idx_gri_team_poi_time (team_id, purchase_order_item_id),
    -- 外键（§5.2 #30–#32；全 RESTRICT）
    CONSTRAINT fk_gri_receipt FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_gri_poi     FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_gri_sku     FOREIGN KEY (sku_id) REFERENCES skus (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（§4.2；★ 只建一条：SSOT 的 ck_gri_receipt_qty 与本条同义，重复登记见 R156）
    CONSTRAINT ck_gri_received_positive CHECK (received_qty > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '到货明细：确认后不可变，仅追加，无 updated_*/version（owner = purchasing，§5.21）';
