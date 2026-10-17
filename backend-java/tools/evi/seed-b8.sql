-- B8 真栈 fat-jar 取证种子（独立库，不入 db/migration）
-- 与 SalesApiIntegrationTest#resetAndSeed 完全对齐的 ID 与数值，便于脚本结论与集成测试互证。
-- 口令统一：S3cret!Passw0rd（对应下方 BCrypt 哈希；与 seed-b7.sql 同源）
--
-- 团队 7（星海优选）：仓库 9004=WH-1、9007=WH-2；SKU 9002/9003/9005（均属商品 9001）
-- 团队 8（别的团队）：仓库 9103=WH-OTHER；SKU 9102（属商品 9101）—— 跨团队负向用例
--
-- 种子日销售（团队 7，全部 source=IMPORT）：
--   SKU-A / WH1：2026-10-01=10、2026-10-02=20
--   SKU-B / WH1：2026-10-01=5
--   SKU-C / WH2：2026-10-01=30
-- 由此派生的聚合期望值：
--   groupBy=sku       → 9002=30、9003=5、9005=30
--   groupBy=warehouse → 9004=35、9007=30
--   groupBy=date      → 2026-10-01=45、2026-10-02=20

DELETE FROM audit_logs;
DELETE FROM sales_daily;

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
  (9004, 7, 'WH-1',     '一号仓', 'ACTIVE'),
  (9007, 7, 'WH-2',     '二号仓', 'ACTIVE'),
  (9103, 8, 'WH-OTHER', '别家仓', 'ACTIVE');

-- 种子日销售：product_id 为冗余列，必须与 sku.product_id 一致
INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, qty, return_qty, sales_amount, order_count, source, created_by, updated_by) VALUES
  (7, 9004, 9002, 9001, '2026-10-01', 10.000, 0.000, 100.00, 2, 'IMPORT', 1001, 1001),
  (7, 9004, 9002, 9001, '2026-10-02', 20.000, 0.000, 200.00, 3, 'IMPORT', 1001, 1001),
  (7, 9004, 9003, 9001, '2026-10-01',  5.000, 0.000,  50.00, 1, 'IMPORT', 1001, 1001),
  (7, 9007, 9005, 9001, '2026-10-01', 30.000, 0.000, 300.00, 4, 'IMPORT', 1001, 1001);
