-- M6-T13 真栈种子（独立库；不入 db/migration）
-- 团队 7 / 仓库 9004 / SKU 9002 / 供应商 9201；余额 10.000（在途列刻意种 0，用于证明「零写入」）
-- 口令统一 S3cret!Passw0rd（与 seed-b7/b8/b9/b95 同源哈希）

DELETE FROM goods_receipt_items;
DELETE FROM goods_receipts;
DELETE FROM purchase_order_items;
DELETE FROM purchase_orders;
DELETE FROM sales_daily;
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
DELETE FROM suppliers;
DELETE FROM warehouses;

INSERT INTO teams (id, code, name, status, default_timezone) VALUES
  (7, 'T-T13', 'M6收口团', 'ACTIVE', 'Asia/Shanghai');

INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES
  (1004, 'owner01',    'owner01@example.com',    '$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6', '老板',   'ACTIVE'),
  (1005, 'admin01',    'admin01@example.com',    '$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6', '主管',   'ACTIVE'),
  (1001, 'operator01', 'operator01@example.com', '$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6', '小王',   'ACTIVE');

INSERT INTO team_members (team_id, user_id, role, status) VALUES
  (7, 1004, 'OWNER',    'ACTIVE'),
  (7, 1005, 'ADMIN',    'ACTIVE'),
  (7, 1001, 'OPERATOR', 'ACTIVE');

INSERT INTO products (id, team_id, product_code, name, status) VALUES
  (9001, 7, 'P-BAG-001', '箱包', 'ACTIVE');

INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES
  (9002, 7, 9001, 'SKU-A', 'SKU-A', 'ACTIVE');

INSERT INTO warehouses (id, team_id, code, name, status, is_default) VALUES
  (9004, 7, 'WH-MAIN', '主仓', 'ACTIVE', 1);

INSERT INTO suppliers (id, team_id, code, name) VALUES
  (9201, 7, 'SUP-T13', '收口供应商');

INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty,
                                in_transit_qty, committed_qty, created_by, updated_by, version) VALUES
  (7, 9004, 9002, 10.000, 0.000, 0.000, 1001, 1001, 0);

INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta,
                                    qty_before, qty_after, biz_type, idempotency_key, reason,
                                    operator_id, occurred_at, created_by) VALUES
  (7, 9004, 9002, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9002',
   '种子导入', 1001, NOW(3), 1001);

-- ---------------------------------------------------------------------------
-- 在途夹具（★ 相对日期，不写死绝对日期）
--   PO-T13-1：ORDERED，单头到期 CURDATE()+3，行 ordered 30 / received 0 ⇒ 窗口内 30
--   PO-T13-2：ORDERED，单头到期 CURDATE()+200，行 ordered 100 / received 0 ⇒ 窗口外 100
--   预期：原始在途 130；有效在途（today+L+R=+14）30
-- ---------------------------------------------------------------------------
INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, status, source,
                             expected_arrival_date, total_qty, total_amount, received_qty,
                             version, created_by, updated_by) VALUES
  (7, 'PO-T13-1', 9201, 9004, 'ORDERED', 'MANUAL', DATE_ADD(CURDATE(), INTERVAL 3 DAY),
   30.000, 300.00, 0.000, 0, 1005, 1005),
  (7, 'PO-T13-2', 9201, 9004, 'ORDERED', 'MANUAL', DATE_ADD(CURDATE(), INTERVAL 200 DAY),
   100.000, 1000.00, 0.000, 0, 1005, 1005);

INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, product_id,
                                  ordered_qty, received_qty, unit_price, amount,
                                  expected_arrival_date, version, created_by, updated_by)
SELECT 7, po.id, 1, 9002, 9001, 30.000, 0.000, 10.0000, 300.00, NULL, 0, 1005, 1005
  FROM purchase_orders po WHERE po.po_no = 'PO-T13-1';

INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, product_id,
                                  ordered_qty, received_qty, unit_price, amount,
                                  expected_arrival_date, version, created_by, updated_by)
SELECT 7, po.id, 1, 9002, 9001, 100.000, 0.000, 10.0000, 1000.00, NULL, 0, 1005, 1005
  FROM purchase_orders po WHERE po.po_no = 'PO-T13-2';

-- ---------------------------------------------------------------------------
-- 销售夹具（★ 分析预检 analysis:validate 要求有效天数 ≥ min_days=7，
--   否则创建运行直接 ANL-7008「有效数据不足」——这不是缺陷，是正确守卫）
--   近 14 天、每日 10 件；落在 date_from=today-28 ~ today 窗口内
-- ---------------------------------------------------------------------------
INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, qty,
                         return_qty, sales_amount, order_count, source, created_by, updated_by)
SELECT 7, 9004, 9002, 9001, DATE_SUB(CURDATE(), INTERVAL n DAY), 10.000,
       0.000, 100.00, 2, 'IMPORT', 1001, 1001
  FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
        UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
        UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
        UNION ALL SELECT 12 UNION ALL SELECT 13) AS seq;
