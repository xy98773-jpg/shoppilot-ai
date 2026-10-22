-- =============================================================================
-- seed-demo-risk.sql —— AI 分析「四类风险」演示数据（M8 走查用，可重复执行）
-- =============================================================================
-- 目的：在团队 7 / 仓库 9003 上构造 4 类互不相同的风险样本 + 1 个健康样本，
--       让「🤖 AI 分析」的结果页能一眼看到 STOCKOUT / LOW_STOCK / OVERSTOCK /
--       SLOW_MOVING 四种判定，而不是满屏 DATA_INSUFFICIENT。
--
-- 前置（本文件不创建主数据，只改演示 SKU 的库存与销量）：
--   · 团队 7 已存在 SKU 910001–910005（code SKU-PERF-00001..5，交期 7，MOQ 10）
--   · 仓库 9003（星海中央仓）为团队 7 的库存所在仓
--   · sales_daily 中 910001–910005 各 14 天、每天 4.000
--
-- 口径（与 service-ai 规则引擎一致，D=4, L=7, R=7, ss_ratio=0.5）：
--   SS      = D × ceil(L × ss_ratio) = 4 × 4 = 16
--   Q_base  = max(0, D × (L + R) + SS − IP) = max(0, 56 + 16 − IP) = 72 − IP
--   LOW_STOCK   : IP < D × (L + R) = 56            → 910002 IP=40 命中
--   STOCKOUT    : on_hand = 0 → HIGH              → 910001 命中
--   OVERSTOCK   : on_hand / D > 90 天 → MEDIUM     → 910003 600/4=150 命中
--   SLOW_MOVING : 近 min(30,window) 天总销量=0 且 on_hand>0 → 910004 命中
--   DATA_INSUFFICIENT 不会出现（910004 虽有 14 天记录但 qty=0，valid_days 够）
--
-- ★ 本文件只用于 dev 演示。生产/测试环境不要执行（会覆盖真实库存数值）。
-- =============================================================================

-- 1) 库存快照：制造四类场景（★ available_qty 是 STORED 生成列 = on_hand_qty - committed_qty，不可写）
UPDATE inventory_balances
   SET on_hand_qty = 0.000, committed_qty = 0.000
 WHERE team_id = 7 AND warehouse_id = 9003 AND sku_id = 910001;   -- 缺货 HIGH

UPDATE inventory_balances
   SET on_hand_qty = 40.000, committed_qty = 0.000
 WHERE team_id = 7 AND warehouse_id = 9003 AND sku_id = 910002;   -- 低库存 MEDIUM

UPDATE inventory_balances
   SET on_hand_qty = 600.000, committed_qty = 0.000
 WHERE team_id = 7 AND warehouse_id = 9003 AND sku_id = 910003;   -- 高库存 MEDIUM

UPDATE inventory_balances
   SET on_hand_qty = 20.000, committed_qty = 0.000
 WHERE team_id = 7 AND warehouse_id = 9003 AND sku_id = 910004;   -- 滞销 MEDIUM

-- 2) 销量：910004 近 14 天全部置零（有记录、零销量 ⇒ 不是数据不足）
UPDATE sales_daily SET qty = 0.000 WHERE team_id = 7 AND sku_id = 910004;

-- 3) 默认仓：分析表单「限定仓库 ID」留空时需要（缺省会报 COM-0001 未配置默认仓）
UPDATE warehouses SET is_default = 0 WHERE team_id = 7;
UPDATE warehouses SET is_default = 1 WHERE team_id = 7 AND id = 9003;
