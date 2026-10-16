-- =============================================================================
-- V1.0.3__catalog.sql —— catalog 模块基础表（M2-T08）
-- =============================================================================
-- 依据（逐字段有据，非凭记忆造字段）：
--   DATABASE_DESIGN.md  §5.4 warehouses / §5.5 suppliers / §5.6 products / §5.7 skus
--   DATA_DICTIONARY.md §4.1 字段集合 A / version 12 张表 / §5.4-§5.7 字段字典
--   DATABASE_CONSTRAINTS.md §2.1 唯一约束、§3.2 索引、§4.1 枚举 CHECK、§4.3 外键、§5.2 数量精度、§6.3 D2
--   DESIGN_BASIS.md     §6 多团队隔离 6 条硬规则、§7.4 表 A（审计 + version）
--   DATABASE_DESIGN.md  §9.5 baseline-on-migrate=false（V1 起顺序执行到底）
--
-- 范围：仅 catalog 四表（warehouses / suppliers / products / skus）。**不建任何业务 CRUD**
--       （M2-T09）、不建启停端点（M2-T10）、不碰审计（M2-T11）、不碰前端（M2-T12）。
--       **不修改 V1.0.0 / V1.0.1 / V1.0.2**（校验和会变 → 启动失败）。
--
-- 团队隔离要点（DESIGN_BASIS §6）：四表**均含 team_id**（无例外；TenantTables.WITHOUT_TEAM_ID 不改）：
--   · 规则 1：team_id 为隔离边界，故四表都带 team_id 列。
--   · 规则 2：唯一约束凡带团队维度者必须以 team_id 为前缀（uk_*_team_*）。
--   · 规则 6：主查询路径索引以 team_id 为最左列（idx_*_team_*）。
--   · D2（§6.3）：同团队内唯一；**跨团队同编码可重复**（uk 带 team_id 前缀即天然满足）。
--
-- 精度红线（DATABASE_DESIGN §3.2 / DATABASE_CONSTRAINTS §5.2）：数量类 DECIMAL(18,3)；
--   单价类 DECIMAL(18,4)；金额类 DECIMAL(18,2)。moq / order_multiple / qty_step / weight_gram
--   是数量 → (18,3)；purchase_price / sale_price 是单价 → (18,4)。
--
-- 时间：一律 DATETIME(3) 存 UTC（§3.2 / §14.1）；created_at / updated_at 由数据库默认值填充，
--   应用层不得显式覆盖。version 为乐观锁；created_by / updated_by 逻辑关联 users.id，不建外键。
-- 外键统一 RESTRICT（DESIGN_BASIS §6 规则 + DATABASE_CONSTRAINTS §4.3）；唯一级联例外不在本批。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- warehouses（仓库）—— 库存物理存放位置；数据模型保留仓库维度
-- 审计字段：集合 A + version（DATABASE_DESIGN §5.4 / §7.4 表 A）
-- -----------------------------------------------------------------------------
CREATE TABLE warehouses (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id       BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（不可变）',
    code          VARCHAR(32)     NOT NULL COMMENT '仓库编码',
    name          VARCHAR(128)    NOT NULL COMMENT '仓库名称',
    status        VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'MasterDataStatus：ACTIVE/INACTIVE',
    is_default    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否默认仓（同团队至多一行为 1，服务层事务内保证）',
    address       VARCHAR(255)    NULL     COMMENT '地址',
    contact_name  VARCHAR(64)     NULL     COMMENT '联系人',
    contact_phone VARCHAR(32)     NULL     COMMENT '联系电话',
    remark        VARCHAR(255)    NULL     COMMENT '备注',
    -- 公共审计字段（集合 A）
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by    BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by    BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    version       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    CONSTRAINT uk_wh_team_code    UNIQUE (team_id, code),
    KEY idx_wh_team_status  (team_id, status),
    KEY idx_wh_team_default (team_id, is_default),
    CONSTRAINT ck_wh_status         CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_wh_status_default CHECK (is_default IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '仓库（catalog 主数据；含 team_id，租户线自动隔离）';

-- -----------------------------------------------------------------------------
-- suppliers（供应商）—— 供应商主数据，并作为 L / MOQ / order_multiple 的二级来源
-- 审计字段：集合 A + version（DATABASE_DESIGN §5.5 / §7.4 表 A）
-- -----------------------------------------------------------------------------
CREATE TABLE suppliers (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id         BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（不可变）',
    code            VARCHAR(32)     NOT NULL COMMENT '供应商编码',
    name            VARCHAR(128)    NOT NULL COMMENT '供应商名称',
    status          VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'MasterDataStatus：ACTIVE/INACTIVE',
    contact_name    VARCHAR(64)     NULL     COMMENT '联系人',
    contact_phone   VARCHAR(32)     NULL     COMMENT '联系电话',
    email           VARCHAR(128)    NULL     COMMENT '邮箱',
    address         VARCHAR(255)    NULL     COMMENT '地址',
    lead_time_days  INT UNSIGNED    NULL     COMMENT '供应商级交期 L（天；可空=未配置，触发向下一级回退）',
    moq             DECIMAL(18,3)   NULL     COMMENT '供应商级最小起订量（可空=未配置）',
    order_multiple  DECIMAL(18,3)   NULL     COMMENT '供应商级采购倍数（可空=未配置）',
    payment_terms   VARCHAR(64)     NULL     COMMENT '付款条件（仅展示）',
    remark          VARCHAR(255)    NULL     COMMENT '备注',
    -- 公共审计字段（集合 A）
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by      BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by      BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    version         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    CONSTRAINT uk_sup_team_code  UNIQUE (team_id, code),
    KEY idx_sup_team_status (team_id, status),
    CONSTRAINT ck_sup_status    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_sup_lead_time CHECK (lead_time_days IS NULL OR lead_time_days >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '供应商（catalog 主数据；含 team_id，租户线自动隔离）';

-- -----------------------------------------------------------------------------
-- products（商品）—— 商品级共享属性（名称/类目/品牌/单位）
-- 审计字段：集合 A + version（DATABASE_DESIGN §5.6 / §7.4 表 A）
-- -----------------------------------------------------------------------------
CREATE TABLE products (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id             BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（不可变）',
    product_code        VARCHAR(64)     NOT NULL COMMENT '商品编码',
    name                VARCHAR(200)    NOT NULL COMMENT '商品名称',
    category            VARCHAR(64)     NULL     COMMENT '类目（扁平字符串，首期无类目树）',
    brand               VARCHAR(64)     NULL     COMMENT '品牌',
    unit                VARCHAR(16)     NULL     COMMENT '计量单位',
    status              VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'MasterDataStatus：ACTIVE/INACTIVE',
    default_supplier_id BIGINT UNSIGNED NULL     COMMENT '默认供应商（逻辑关联 suppliers.id，须同团队）',
    spec_summary        VARCHAR(255)    NULL     COMMENT '规格摘要（展示用）',
    remark              VARCHAR(255)    NULL     COMMENT '备注',
    -- 公共审计字段（集合 A）
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by          BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by          BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    version             INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    CONSTRAINT uk_prod_team_code    UNIQUE (team_id, product_code),
    KEY idx_prod_team_status   (team_id, status),
    KEY idx_prod_team_category (team_id, category, status),
    KEY idx_prod_team_supplier (team_id, default_supplier_id),
    CONSTRAINT ck_prod_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fk_prod_supplier FOREIGN KEY (default_supplier_id) REFERENCES suppliers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '商品（catalog 主数据；含 team_id，租户线自动隔离）';

-- -----------------------------------------------------------------------------
-- skus（SKU）—— 最小可库存单元，隶属一个商品，承载规格与采购属性
-- 审计字段：集合 A + version（DATABASE_DESIGN §5.7 / §7.4 表 A）
-- -----------------------------------------------------------------------------
CREATE TABLE skus (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    team_id               BIGINT UNSIGNED NOT NULL COMMENT '团队 ID（不可变）',
    product_id            BIGINT UNSIGNED NOT NULL COMMENT '所属商品（不可变；换商品须新建 SKU）',
    sku_code              VARCHAR(64)     NOT NULL COMMENT 'SKU 编码',
    name                  VARCHAR(200)    NULL     COMMENT 'SKU 展示名',
    barcode               VARCHAR(64)     NULL     COMMENT '条码',
    spec_json             JSON            NULL     COMMENT '规格键值（颜色/尺码等）',
    status                VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'MasterDataStatus：ACTIVE/INACTIVE',
    supplier_id           BIGINT UNSIGNED NULL     COMMENT 'SKU 级供应商（逻辑关联 suppliers.id，须同团队）',
    default_lead_time_days INT UNSIGNED   NULL     COMMENT 'SKU 级交期 L（天；可空=未配置）',
    moq                   DECIMAL(18,3)   NULL     COMMENT 'SKU 级最小起订量（可空=未配置）',
    order_multiple        DECIMAL(18,3)   NULL     COMMENT 'SKU 级采购倍数（可空=未配置）',
    qty_step              DECIMAL(18,3)   NULL     COMMENT '最小起订精度',
    purchase_price        DECIMAL(18,4)   NULL     COMMENT '参考采购单价',
    sale_price            DECIMAL(18,4)   NULL     COMMENT '参考售价',
    weight_gram           DECIMAL(18,3)   NULL     COMMENT '重量（克）',
    listed_date           DATE            NULL     COMMENT '上架日期（滞销判定输入）',
    -- 公共审计字段（集合 A）
    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    created_by            BIGINT UNSIGNED NULL     COMMENT '创建人（逻辑关联 users.id）',
    updated_by            BIGINT UNSIGNED NULL     COMMENT '更新人（逻辑关联 users.id）',
    version               INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    CONSTRAINT uk_sku_team_code    UNIQUE (team_id, sku_code),
    KEY idx_sku_team_product  (team_id, product_id, status),
    KEY idx_sku_team_status    (team_id, status),
    KEY idx_sku_team_supplier  (team_id, supplier_id),
    CONSTRAINT ck_sku_status    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_sku_lead_time CHECK (default_lead_time_days IS NULL OR default_lead_time_days >= 0),
    CONSTRAINT fk_sku_product  FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_sku_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'SKU（catalog 主数据；含 team_id，租户线自动隔离）';
