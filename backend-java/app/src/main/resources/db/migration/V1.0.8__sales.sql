-- =============================================================================
-- V1.0.8__sales.sql —— sales 模块 sales_daily 单表（M3-T12 / B8）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DATABASE_DESIGN.md      §5.11 sales_daily（逐列：类型/可空/默认值/含义/更新规则）
--                           §7.4  表 B（sales_daily 仅 INSERT/upsert 覆盖 → **无 version**）
--   DATA_DICTIONARY.md      §4.1 字段集合 A（created_at/updated_at/created_by/updated_by）
--   DATABASE_CONSTRAINTS.md §2.1 唯一约束 #13（uk_sales_team_wh_sku_date）
--                           §3.2 索引 #21–#24（四条，全部 team_id 最左）
--                           §4.1 枚举 CHECK（ck_sales_source 三值）
--                           §4.2 非负 CHECK（ck_sales_* 三条；可空列先判 NULL）
--                           §5.2 外键 #12–#13（fk_sales_warehouse / fk_sales_sku，全 RESTRICT）
--   DESIGN_BASIS.md         §9.17 SalesSource（IMPORT/MANUAL/PLATFORM）
--                           §6 规则 1/3/6（表必含 team_id；team_id 只来自服务端；索引 team_id 最左）
--
-- 范围：仅 sales_daily 一张表（M3-T12，纯 DDL）。
--       **不做** SalesWriteService / SalesQueryService / Entity / Mapper / 端点（属同批应用层）。
--       **不修改** V1.0.0–V1.0.7（Flyway checksum 红线）。
--
-- 迁移号口径（R32）：按【开发批次顺序】递增——V1.0.5=importdata、V1.0.6=inventory、
--   V1.0.7=inventory R53 修正、V1.0.8=sales（本批）。.workbuddy/M3-启动范围.md 的「V1.0.7=sales」是旧口径，已作废。
--
-- 团队隔离要点（DESIGN_BASIS §6）：
--   · 规则 1：sales_daily **含 team_id NOT NULL** → **不得登记**进 TenantTables.WITHOUT_TEAM_ID
--     （该黑名单仍只有 users / teams / refresh_tokens 三张无 team_id 列的表）。
--   · 规则 3：team_id **只来自服务端**（TeamContext），绝不接受客户端传入。
--   · 规则 6：唯一键与四条二级索引**全部 team_id 最左**（SEQ_IN_INDEX=1 逐条断言）。
--   · 跨团队的 sku_id / warehouse_id 组合**在 DB 层不拦**（team_id 不建外键），
--     同团队一致性由服务层校验（§5.11「团队隔离」）。
--
-- ★ upsert 语义（§6-D.2 U-6）：唯一键 uk_sales_team_wh_sku_date 是「重复导入 / 手工覆盖」
--   的落点——同 (team_id, warehouse_id, sku_id, sale_date) 冲突时**不报错、覆盖**，
--   最终以「最后一次写入为准」。team_id / warehouse_id / sku_id / sale_date / product_id
--   构成业务键，**不可变**；可变的只有 qty / return_qty / sales_amount / order_count / source / batch_id。
--
-- ★ 全仓唯一 ON DELETE CASCADE 只有 fk_rt_user（refresh_tokens）；本批外键**均 RESTRICT**，
--   不得新增第二个 CASCADE。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- sales_daily（日销售）—— 团队×仓库×SKU×自然日 一行的日销售事实
-- 审计字段：集合 A（DATABASE_DESIGN §5.11 / §7.4 表 B：无 version、无状态机）
-- 唯一约束：uk_sales_team_wh_sku_date —— upsert 冲突吸收点（覆盖，不报错）
-- 设计要点：qty 为【净销量】（已扣退货）；return_qty 单独保留（退货率分析）；source 仅标识来源，
--          统计口径统一按 team_id + sale_date，天然不重复。
-- -----------------------------------------------------------------------------
CREATE TABLE sales_daily (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id          BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（服务端注入，不接受客户端传入；不可变）',
    warehouse_id     BIGINT UNSIGNED NOT NULL COMMENT '仓库 ID（不可变）',
    sku_id           BIGINT UNSIGNED NOT NULL COMMENT 'SKU ID（不可变）',
    product_id       BIGINT UNSIGNED NOT NULL COMMENT '冗余商品 ID（加速类目/商品汇总；由不可变 skus.product_id 保证安全；不可变）',
    sale_date        DATE            NOT NULL COMMENT '业务日期（团队时区自然日；不可变）',
    qty              DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '净销售数量（已扣退货；upsert 覆盖）',
    return_qty       DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '退货数量（upsert 覆盖）',
    sales_amount     DECIMAL(18,2)   NULL     COMMENT '销售金额（可空；upsert 覆盖）',
    order_count      INT UNSIGNED    NULL     COMMENT '订单数（可选；upsert 覆盖）',
    source           VARCHAR(32)     NOT NULL DEFAULT 'IMPORT' COMMENT '数据来源：IMPORT/MANUAL/PLATFORM（SalesSource，§9.17；upsert 覆盖）',
    batch_id         BIGINT UNSIGNED NULL     COMMENT '来源导入批次（逻辑关联 import_batches.id，不建外键；upsert 覆盖）',
    -- 公共审计字段（集合 A：DATA_DICTIONARY §4.1；无 version，见文件头 §7.4 表 B）
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by       BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by       BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    PRIMARY KEY (id),
    -- 唯一约束（DATABASE_CONSTRAINTS §2.1 #13；★ team_id 最左；upsert 落点，冲突覆盖不报错）
    CONSTRAINT uk_sales_team_wh_sku_date UNIQUE (team_id, warehouse_id, sku_id, sale_date),
    -- 索引（DATABASE_CONSTRAINTS §3.2 #21–#24；★ 全部 team_id 最左）
    --   #21 最高频：D 的 28 天窗口扫描 / 滞销 / 异常检测
    --   #22 团队级日报/月报；#23 商品/类目维度汇总（免 JOIN skus）；#24 批次回溯
    KEY idx_sales_team_sku_date     (team_id, sku_id, sale_date),
    KEY idx_sales_team_date         (team_id, sale_date),
    KEY idx_sales_team_product_date (team_id, product_id, sale_date),
    KEY idx_sales_team_batch        (team_id, batch_id),
    -- 外键（DATABASE_CONSTRAINTS §5.2 #12–#13；RESTRICT：主数据不物理删除）
    CONSTRAINT fk_sales_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_sales_sku FOREIGN KEY (sku_id) REFERENCES skus (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- CHECK（DATABASE_CONSTRAINTS §4.1 枚举 + §4.2 非负；共 4 条）
    --   ★ 最后一条必须写成「sales_amount IS NULL OR sales_amount >= 0」：
    --     可空列先判 NULL，否则 NULL >= 0 恒为 NULL，既不拦截也不报错（静默陷阱）。
    CONSTRAINT ck_sales_source              CHECK (source IN ('IMPORT', 'MANUAL', 'PLATFORM')),
    CONSTRAINT ck_sales_qty_non_negative    CHECK (qty >= 0),
    CONSTRAINT ck_sales_return_non_negative CHECK (return_qty >= 0),
    CONSTRAINT ck_sales_amount_non_negative CHECK (sales_amount IS NULL OR sales_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '日销售（sales；团队×仓库×SKU×自然日一行，upsert 覆盖，无 version）';
