-- =============================================================================
-- V1.0.7__inventory_fix_available_qty_not_null.sql —— R53 修正（M3-T09 / B6）
-- =============================================================================
-- 问题（B5 独立复核发现，登记 R53）：
--   DATABASE_DESIGN §5.9 字段表标注 available_qty「可空 = 否」；
--   而 V1.0.6 建出的生成列未声明 NOT NULL →
--   information_schema.columns.IS_NULLABLE = 'YES'，元数据与 SSOT 不一致。
--   实测值域无差异（on_hand_qty / committed_qty 均 NOT NULL ⇒ 结果恒非空），
--   但元数据是代码生成 / ORM 的输入：若按元数据生成可空类型、或写 available_qty IS NULL 判定，
--   会与文档语义脱节。
--
-- 修法（R53 已实测可行，mysql:8.0.36 独立验证）：
--   · 生成列可直接声明 STORED NOT NULL；
--   · ALTER TABLE ... MODIFY COLUMN ... STORED NOT NULL 亦可，且【保留既有数据】。
--   本迁移取后者（V1.0.6 已执行，按 Flyway checksum 红线不得回修，只能增量修正）。
--
-- ★ 不改 V1.0.6（checksum 红线）；不改表达式（on_hand_qty - committed_qty，§3 / §5.9）。
-- ★ 需同步的版本断言 5 处：AuditChannelIntegrationTest / CatalogSchemaConstraintTest /
--   IdentitySchemaAndTenantLineTest / ImportSchemaConstraintTest / InventorySchemaConstraintTest
--   （7 条 → 8 条）。
-- =============================================================================

ALTER TABLE inventory_balances
    MODIFY COLUMN available_qty DECIMAL(18,3)
        GENERATED ALWAYS AS (on_hand_qty - committed_qty) STORED NOT NULL
        COMMENT '可用库存 = on_hand_qty - committed_qty（§3，不含在途；STORED 生成列，不可写）';
