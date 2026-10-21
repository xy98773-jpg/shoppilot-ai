-- B9（M3-T17 审计埋点）真栈 fat-jar 取证种子（独立库，不入 db/migration）
-- 与 InventoryAuditIntegrationTest#resetAndSeed 的 ID / 数值对齐，便于脚本结论与集成测试互证。
-- ★ 导入命令必须带 --default-character-set=utf8mb4（2026-10-22 教训：不带会按 latin1 双重编码，
--   users.display_name / teams.name / warehouses.name 全部变 mojibake，需用
--   CONVERT(BINARY CONVERT(col USING latin1) USING utf8mb4) 反转修复）。
-- 口令统一：S3cret!Passw0rd（对应下方 BCrypt 哈希；与 seed-b7.sql / seed-b8.sql 同源）
--
-- 团队 7（星海优选）：仓库 9004=WH-MAIN；SKU 9002=SKU-A、9003=SKU-B、9005=SKU-C（均属商品 9001）
-- 团队 8（别的团队）：仓库 9103=WH-OTHER；SKU 9102（属商品 9101）—— 跨团队负向用例
--
-- 库存余额（团队 7）：SKU-A=10.000、SKU-B=10.000、SKU-C=10.000
-- 可冲销流水：idem=seed-init-9002（SKU-A，INIT_IMPORT +10.000）、seed-init-9005（SKU-C，同）
--   ★ 脚本专用分工，避免探测组互相污染：
--     A1 调 SKU-A（10→7）、A2 调 SKU-B（10→8）、A3 冲 SKU-C 的 seed-init-9005（10→0）。
--     若 A3 也冲 SKU-A，A1 先跑完 7-10 = -3 会撞 INV-5006，取证结论就不可信了。
-- 补货策略：刻意【不种】—— A4 的「首次 upsert」必须走 insert 分支（含 biz_id 回填）
--
-- ★ audit_logs 清空：登录本身会写 AUTH_LOGIN_SUCCESS，所有审计断言必须
--   WHERE action=? AND team_id=? 精确计数，不得用全表 COUNT(*)（任务书 4.1 末段）。

DELETE FROM audit_logs;

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
  (1004, 'owner01',    'owner01@example.com',    '$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6', '老板',   'ACTIVE'),
  (1005, 'admin01',    'admin01@example.com',    '$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6', '主管',   'ACTIVE'),
  (1001, 'operator01', 'operator01@example.com', '$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6', '小王',   'ACTIVE'),
  (1008, 'approver01', 'approver01@example.com', '$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6', '审批人', 'ACTIVE'),
  (1003, 'viewer01',   'viewer01@example.com',   '$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6', '观察者', 'ACTIVE'),
  (2004, 'owner8',     'owner8@example.com',     '$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6', '外来户', 'ACTIVE');

INSERT INTO team_members (team_id, user_id, role, status) VALUES
  (7, 1004, 'OWNER',    'ACTIVE'),
  (7, 1005, 'ADMIN',    'ACTIVE'),
  (7, 1001, 'OPERATOR', 'ACTIVE'),
  (7, 1008, 'APPROVER', 'ACTIVE'),
  (7, 1003, 'VIEWER',   'ACTIVE'),
  (8, 2004, 'OWNER',    'ACTIVE');

INSERT INTO products (id, team_id, product_code, name, status) VALUES
  (9001, 7, 'P-BAG-001',   '箱包',     'ACTIVE'),
  (9101, 8, 'P-OTHER-001', '别家商品', 'ACTIVE');

INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES
  (9002, 7, 9001, 'SKU-A',     'SKU-A',     'ACTIVE'),
  (9003, 7, 9001, 'SKU-B',     'SKU-B',     'ACTIVE'),
  (9005, 7, 9001, 'SKU-C',     'SKU-C',     'ACTIVE'),
  (9102, 8, 9101, 'SKU-OTHER', 'SKU-OTHER', 'ACTIVE');

INSERT INTO warehouses (id, team_id, code, name, status) VALUES
  (9004, 7, 'WH-MAIN',  '主仓',   'ACTIVE'),
  (9103, 8, 'WH-OTHER', '别家仓', 'ACTIVE');

-- available_qty 是 STORED 生成列，不显式写入
INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty,
                                in_transit_qty, committed_qty, created_by, updated_by, version) VALUES
  (7, 9004, 9002, 10.000, 0.000, 0.000, 1001, 1001, 0),
  (7, 9004, 9003, 10.000, 0.000, 0.000, 1001, 1001, 0),
  (7, 9004, 9005, 10.000, 0.000, 0.000, 1001, 1001, 0);

INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta,
                                    qty_before, qty_after, biz_type, idempotency_key, reason,
                                    operator_id, occurred_at, created_by) VALUES
  (7, 9004, 9002, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9002',
   '种子导入', 1001, NOW(3), 1001),
  (7, 9004, 9005, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9005',
   '种子导入', 1001, NOW(3), 1001);
