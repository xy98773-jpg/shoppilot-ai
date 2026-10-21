-- =============================================================================
-- V1.0.12__replenishment_anchor_lock_index.sql —— 转采购降级锚点行锁专用索引
--                             （R165 / DESIGN_REVIEW §5.68；M6-T02-D）
-- =============================================================================
-- 背景（为什么必须单独建）：
--   M6-T02 转采购的降级锁 SQL（DATABASE_DESIGN §7.2 #8 步骤 ②b）：
--     SELECT id FROM replenishment_items
--      WHERE team_id = ? AND warehouse_id = ? AND sku_id IN ( … )
--        AND status = 'APPROVED'
--      ORDER BY sku_id ASC FOR UPDATE
--   既有索引 idx_repl_team_sku_time (team_id, sku_id, created_at) 中 warehouse_id 是
--   「索引外过滤条件」，实测 EXPLAIN 下优化器改选 idx_repl_team_status_time
--   （type=ref, key_len=138）并对 sku_id 呈现 Using filesort ⇒
--   ORDER BY sku_id 的确定性加锁序无法保证，而死锁预防（DATABASE_CONSTRAINTS §7.3 ①）
--   依赖该顺序 ⇒ 必须补一个把 warehouse_id 纳入索引前缀的专用索引。
--
-- 与 inventory_balances.uk_invbal_team_wh_sku (team_id, warehouse_id, sku_id) 同构对齐：
--   ② 的余额行锚点锁走的就是那个唯一键，②b 降级锁与之同口径（team × warehouse × sku）。
--
-- status='APPROVED' 选择性低，仍作索引外过滤条件，不入索引（与 idx_poi_team_* 同款取舍）。
--
-- 本迁移只 ADD 索引，不动数据、不改既有索引/约束、不含任何 DDL 破坏性变更。
-- =============================================================================

ALTER TABLE replenishment_items
    ADD KEY idx_repl_team_wh_sku (team_id, warehouse_id, sku_id);
