# -*- coding: utf-8 -*-
"""M4-B22.1 R148 真栈种子生成器：500 个 ACTIVE SKU + 28 天销售 + 库存余额。

★ 独立库种子，不入 db/migration（与 tools/rb-b2041/seed.sql 同套路）。
★ 生成而非手写：500 × 28 = 14,000 行销量，手写不可复核。
用法：python gen-seed-500.py > seed-500.sql
"""
import sys

TEAM = 7
OTHER_TEAM = 8
USER_OPERATOR = 1001
USER_VIEWER = 1003
USER_OTHER = 2004
PRODUCT = 9001
PRODUCT_OTHER = 9101
WAREHOUSE = 9004
WAREHOUSE_OTHER = 9103
SKU_BASE = 10000
SKU_COUNT = 500
DATES = ["2026-09-16", "2026-09-17", "2026-09-18", "2026-09-19", "2026-09-20",
         "2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25",
         "2026-09-26", "2026-09-27", "2026-09-28", "2026-09-29", "2026-09-30",
         "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05",
         "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10",
         "2026-10-11", "2026-10-12", "2026-10-13"]

# 口令统一 S3cret!Passw0rd（BCrypt 哈希沿用 tools/evi/seed-b7*.sql）
PWD_HASH = "$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6"

out = []
w = out.append

w("-- M4-B22.1 R148 性能补证种子（500 SKU × 28 天；由 gen-seed-500.py 生成，勿手改）")
w("DELETE FROM refresh_tokens;")
w("DELETE FROM team_members;")
w("DELETE FROM users;")
w("DELETE FROM teams;")
w("DELETE FROM inventory_transactions;")
w("DELETE FROM inventory_balances;")
w("DELETE FROM replenishment_policies;")
w("DELETE FROM sales_daily;")
w("DELETE FROM skus;")
w("DELETE FROM products;")
w("DELETE FROM suppliers;")
w("DELETE FROM warehouses;")
w("")
w("INSERT INTO teams (id, code, name, status, default_timezone) VALUES")
w("  (%d, 'T-PERF', '性能组', 'ACTIVE', 'Asia/Shanghai')," % TEAM)
w("  (%d, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai');" % OTHER_TEAM)
w("")
w("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES")
w("  (%d, 'operator01', 'operator01@example.com', '%s', '小王', 'ACTIVE')," % (USER_OPERATOR, PWD_HASH))
w("  (%d, 'viewer01',   'viewer01@example.com',   '%s', '观察者', 'ACTIVE')," % (USER_VIEWER, PWD_HASH))
w("  (%d, 'owner8',     'owner8@example.com',     '%s', '外来户', 'ACTIVE');" % (USER_OTHER, PWD_HASH))
w("")
w("INSERT INTO team_members (team_id, user_id, role, status) VALUES")
w("  (%d, %d, 'OPERATOR', 'ACTIVE')," % (TEAM, USER_OPERATOR))
w("  (%d, %d, 'VIEWER',   'ACTIVE')," % (TEAM, USER_VIEWER))
w("  (%d, %d, 'OWNER',    'ACTIVE');" % (OTHER_TEAM, USER_OTHER))
w("")
w("INSERT INTO products (id, team_id, product_code, name, category, status) VALUES")
w("  (%d, %d, 'P-PERF', '性能商品', 'CAT-01', 'ACTIVE')," % (PRODUCT, TEAM))
w("  (%d, %d, 'P-OTHER', '别家商品', 'CAT-01', 'ACTIVE');" % (PRODUCT_OTHER, OTHER_TEAM))
w("")
w("INSERT INTO warehouses (id, team_id, code, name, status, is_default) VALUES")
w("  (%d, %d, 'WH-PERF', '性能主仓', 'ACTIVE', 1)," % (WAREHOUSE, TEAM))
w("  (%d, %d, 'WH-OTHER', '别家仓', 'ACTIVE', 1);" % (WAREHOUSE_OTHER, OTHER_TEAM))
w("")
w("-- 500 个 ACTIVE SKU（id 10001..10500；无供应商/无策略 ⇒ 交期走 DEFAULT）")
w("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES")
rows = []
for n in range(1, SKU_COUNT + 1):
    rows.append("  (%d, %d, %d, 'SKU-%04d', '规格%04d', 'ACTIVE')" % (SKU_BASE + n, TEAM, PRODUCT, n, n))
w(",\n".join(rows) + ";")
w("")
w("-- 每个 SKU 一行余额：on_hand=10 / committed=1 ⇒ IP=9，保证 Q_suggest 非 0（真实写路径）")
w("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, in_transit_qty, committed_qty, created_by, updated_by, version) VALUES")
rows = []
for n in range(1, SKU_COUNT + 1):
    rows.append("  (%d, %d, %d, 10.000, 0.000, 1.000, %d, %d, 0)" % (TEAM, WAREHOUSE, SKU_BASE + n, USER_OPERATOR, USER_OPERATOR))
w(",\n".join(rows) + ";")
w("")
w("-- 500 × 28 = %d 行销量（每日 5.000）" % (SKU_COUNT * len(DATES)))
w("INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, qty, return_qty, sales_amount, source) VALUES")
rows = []
for n in range(1, SKU_COUNT + 1):
    for d in DATES:
        rows.append("  (%d, %d, %d, %d, '%s', 5.000, 0.000, 50.00, 'IMPORT')" % (TEAM, WAREHOUSE, SKU_BASE + n, PRODUCT, d))
w(",\n".join(rows) + ";")
w("")

sys.stdout.write("\n".join(out))
