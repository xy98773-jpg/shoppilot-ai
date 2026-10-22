-- B7 真栈 fat-jar 取证种子（独立库，不入 db/migration）
-- 与 InventoryApiIntegrationTest#resetAndSeed 完全对齐的 ID 与数值，便于对照 31 项集成测试。
-- ★ 导入命令必须带 --default-character-set=utf8mb4（2026-10-22 教训：不带会按 latin1 双重编码，
--   users.display_name / teams.name / warehouses.name 全部变 mojibake，需用
--   CONVERT(BINARY CONVERT(col USING latin1) USING utf8mb4) 反转修复）。
-- 口令统一：1234（对应下方 BCrypt 哈希）

DELETE FROM refresh_tokens;
DELETE FROM team_members;
DELETE FROM users;
DELETE FROM teams;

DELETE FROM inventory_transactions;
DELETE FROM inventory_balances;
DELETE FROM replenishment_policies;
DELETE FROM skus;
DELETE FROM products;
DELETE FROM warehouses;

INSERT INTO teams (id, code, name, status, default_timezone) VALUES
  (7, 'T-HX',    '星海优选', 'ACTIVE', 'Asia/Shanghai'),
  (8, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai');

INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES
  (1004, 'owner01',    'owner01@example.com',    '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '老板',   'ACTIVE'),
  (1005, 'admin01',    'admin01@example.com',    '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '主管',   'ACTIVE'),
  (1001, 'operator01', 'operator01@example.com', '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '小王',   'ACTIVE'),
  (1008, 'approver01', 'approver01@example.com', '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '审批人', 'ACTIVE'),
  (1003, 'viewer01',   'viewer01@example.com',   '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '观察者', 'ACTIVE'),
  (2004, 'owner8',     'owner8@example.com',     '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '外来户', 'ACTIVE');

INSERT INTO team_members (team_id, user_id, role, status) VALUES
  (7, 1004, 'OWNER',    'ACTIVE'),
  (7, 1005, 'ADMIN',    'ACTIVE'),
  (7, 1001, 'OPERATOR', 'ACTIVE'),
  (7, 1008, 'APPROVER', 'ACTIVE'),
  (7, 1003, 'VIEWER',   'ACTIVE'),
  (8, 2004, 'OWNER',    'ACTIVE');

INSERT INTO products (id, team_id, product_code, name, status) VALUES
  (9001, 7, 'P-BAG-001',   '箱包',   'ACTIVE'),
  (9101, 8, 'P-OTHER-001', '别家商品', 'ACTIVE');

INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES
  (9002, 7, 9001, 'SKU-A',      'SKU-A',      'ACTIVE'),
  (9003, 7, 9001, 'SKU-B',      'SKU-B',      'ACTIVE'),
  (9005, 7, 9001, 'SKU-C',      'SKU-C',      'ACTIVE'),
  (9006, 7, 9001, 'SKU-D',      'SKU-D',      'ACTIVE'),
  (9102, 8, 9101, 'SKU-OTHER',  'SKU-OTHER',  'ACTIVE');

INSERT INTO warehouses (id, team_id, code, name, status) VALUES
  (9004, 7, 'WH-MAIN',  '主仓',   'ACTIVE'),
  (9103, 8, 'WH-OTHER', '别家仓', 'ACTIVE');

-- available_qty 为 STORED 生成列，不显式写入
INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, in_transit_qty, committed_qty, created_by, updated_by, version) VALUES
  (7, 9004, 9002, 10.000, 0.000, 0.000, 1001, 1001, 0),  -- SKU-A 正常
  (7, 9004, 9003, 0.000,  0.000, 0.000, 1001, 1001, 0),  -- SKU-B 缺货 → STOCKOUT
  (7, 9004, 9005, 20.000, 0.000, 0.000, 1001, 1001, 0),  -- SKU-C 滞销 → SLOWMOVING
  (7, 9004, 9006, 5.000,  0.000, 0.000, 1001, 1001, 0);  -- SKU-D 低库存（策略 safety=10 → LOWSTOCK）

INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, qty_before, qty_after, biz_type, idempotency_key, reason, operator_id, occurred_at, created_by) VALUES
  (7, 9004, 9002, 'INIT_IMPORT',   10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9002', '种子导入', 1001, NOW(3), 1001),
  (7, 9004, 9002, 'SALE_OUTBOUND', -2.000, 12.000, 10.000, 'SALE',   'seed-sale-9002', '销售',    1001, NOW(3), 1001),
  (7, 9004, 9006, 'SALE_OUTBOUND', -1.000, 6.000, 5.000,  'SALE',   'seed-sale-9006', '销售',    1001, NOW(3), 1001);

-- 团队 7：SKU-D 安全库存 10 → 触发 LOWSTOCK（available=5 ≤ 10）
INSERT INTO replenishment_policies (team_id, sku_id, warehouse_id, safety_stock_qty, is_enabled, created_by, updated_by, version) VALUES
  (7, 9006, 9004, 10.000, 1, 1001, 1001, 0);

-- 跨团队负向用例数据（团队 8）
INSERT INTO replenishment_policies (team_id, sku_id, warehouse_id, is_enabled, created_by, updated_by, version) VALUES
  (8, 9102, 9103, 1, 2004, 2004, 0);
INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, qty_before, qty_after, biz_type, idempotency_key, operator_id, occurred_at, created_by) VALUES
  (8, 9103, 9102, 'MANUAL_ADJUST', 1.000, 0.000, 1.000, 'MANUAL', 'other-txn', 2004, NOW(3), 2004);
