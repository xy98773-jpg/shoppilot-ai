-- B9.5（R72 审计事务耦合 + R73 冲销幂等重放）真栈 fat-jar 取证种子（独立库，不入 db/migration）
-- 与 InventoryAuditTxCouplingTest / InventoryReversalReplayTest 的 ID / 数值对齐，
-- ★ 导入命令必须带 --default-character-set=utf8mb4（2026-10-22 教训：不带会按 latin1 双重编码，
--   users.display_name / teams.name / warehouses.name 全部变 mojibake，需用
--   CONVERT(BINARY CONVERT(col USING latin1) USING utf8mb4) 反转修复）。
-- 便于脚本结论与集成测试互证。口令统一：1234（与 seed-b7/b8/b9 同源哈希）
--
-- 团队 7（星海优选）：仓库 9004=WH-MAIN；SKU 9002=SKU-A、9003=SKU-B、9005=SKU-C（均属商品 9001）
-- 团队 8（别的团队）：仓库 9103=WH-OTHER；SKU 9102（属商品 9101）—— 保留作跨团队背景数据
--
-- 余额：SKU-A/B/C 各 10.000
-- 可冲销流水：seed-init-9002（SKU-A，INIT_IMPORT +10）、seed-init-9003（SKU-B，同）、
--            seed-init-9005（SKU-C，同）
-- ★ 不变量（TEST_STRATEGY §7.3）：每条余额行都必须有等额 INIT_IMPORT 流水，
--   即 on_hand_qty = SUM(qty_delta)。新增/改动余额行时【必须】同步补流水，否则按 §7.3
--   硬验的批次会产生假 FAIL（R85 曾因此漏补 9003）。
--
-- ★ 组次与数据的分工（避免探针互相污染）：
--   C1  R72 触发器阻断：调 SKU-A（10 → 7）→ 必须失败并回滚；解除阻断后同样动作成功
--   C2  INV-5006（目标 -1）→ 失败不写审计
--   C3  触发器阻断下 #7 策略仍成功（有意差异，走 record 解耦通道）
--   R1  R73 重放：冲 seed-init-9005（SKU-C，10 → 0），同键同体重放
--   R2  同键不同体：拿 seed-init-9002 配 R1 的键 → INV-5003（不动余额）
--   R3  不同键二次冲销：seed-init-9005 已被 R1 冲销 → INV-5009
--   ★ R1 刻意用 SKU-C 而非 SKU-A：C1 已把 SKU-A 改成 7，再冲 +10 会得 -3 撞 INV-5006，
--     取证结论就会被「另一个错误」污染。
-- 补货策略：刻意【不种】—— C3 的 upsert 必须走 insert 分支
--
-- ★ audit_logs 清空后仍不能用全表 COUNT(*)：登录会写 AUTH_LOGIN_SUCCESS。
--   所有审计断言一律 WHERE action=? AND team_id=?（任务书 4.1 末段口径）。

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
  (1004, 'owner01',    'owner01@example.com',    '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '老板',   'ACTIVE'),
  (1005, 'admin01',    'admin01@example.com',    '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '主管',   'ACTIVE'),
  (1001, 'operator01', 'operator01@example.com', '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '小王',   'ACTIVE'),
  (1003, 'viewer01',   'viewer01@example.com',   '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '观察者', 'ACTIVE'),
  (2004, 'owner8',     'owner8@example.com',     '$2a$10$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS', '外来户', 'ACTIVE');

INSERT INTO team_members (team_id, user_id, role, status) VALUES
  (7, 1004, 'OWNER',    'ACTIVE'),
  (7, 1005, 'ADMIN',    'ACTIVE'),
  (7, 1001, 'OPERATOR', 'ACTIVE'),
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
  -- ★ R85：SKU-B(9003) 的余额行在上一条 INSERT 里存在，但本段原先【缺】它的 INIT_IMPORT 流水
  --   ⇒ TEST_STRATEGY §7.3 的对账 SQL（余额 = Σ流水）在【种子阶段】就返回 1 行，
  --   任何按 §7.3 硬验的批次都会产生假 FAIL。本次只补这一条，字段与既有两行完全同构。
  (7, 9004, 9003, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9003',
   '种子导入', 1001, NOW(3), 1001),
  (7, 9004, 9005, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9005',
   '种子导入', 1001, NOW(3), 1001);
