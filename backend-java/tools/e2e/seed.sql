-- M7-T01 端到端演示种子：只种「团队 + 用户 + 成员关系」
-- 主数据（供应商/商品/SKU/仓库/库存/销售）全部由导入接口产生 —— 这是 §7.2 第 3~8 步的验证对象，
-- 若在此预置会让「导入是否真的落库」变成假阳性。
-- 口令统一 1234（与 tools/evi/seed-b7|b8|b9|b95 同源哈希）。

DELETE FROM refresh_tokens;
DELETE FROM team_members;
DELETE FROM users;
DELETE FROM teams;

INSERT INTO teams (id, code, name, status, default_timezone) VALUES
  (7, 'T-E2E',   '端到端演示团队', 'ACTIVE', 'Asia/Shanghai'),
  (8, 'T-OTHER', '别的团队',       'ACTIVE', 'Asia/Shanghai');

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
