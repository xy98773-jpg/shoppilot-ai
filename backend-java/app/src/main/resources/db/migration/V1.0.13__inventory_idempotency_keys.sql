-- =============================================================================
-- V1.0.13__inventory_idempotency_keys.sql —— 幂等键占位表（M8-T04 / ADR-008 / R76）
-- =============================================================================
-- 背景（为什么必须新表）：
--   盘点调整（COUNT_ADJUST）在 target == on_hand（差额为 0）时，按
--   INVENTORY_WORKFLOW §5.5 步骤 G「不写流水（幂等），直接返回成功」。
--   但幂等键的唯一载体是 inventory_transactions.idempotency_key
--   （DATABASE_DESIGN §5.10）⇒ 该 key 从未落库 ⇒
--   replayIfIdempotent → selectByIdempotencyKey 永远查不到。
--
--   危害链（R76 实测 tools/rb-b96）：
--     余额 8.000、target=8.000 → 首次 201（key 0 行）
--     → MANUAL_ADJUST −3 把余额改成 5.000
--     → 同 key 同请求体重发 → 201 + 新流水 + 余额被拉回 8.000
--   即「重试」执行了第二次变更，抹掉中间变动，违 API_CONTRACT §6.3。
--
-- 为何不改用「差额 0 也写流水」：
--   DATABASE_CONSTRAINTS 有 ck_invtxn_delta_non_zero (qty_delta <> 0)——
--   「流水必有变化」是账本表的基本不变量，放松它要让 0 变化行成为常态。
--   详见 ADR-008 §2。
--
-- 为何不与流水表双写：
--   两表互斥（差额≠0 → 只写流水；差额=0 → 只写本表），不重叠，
--   从而不引入「两个载体必须永远同步」的新不变式。ADR-008 §4。
--
-- content_hash 必含数量：否则「同 key 换 target」会被误判为同内容。
--   比对字段沿用 R75 口径（不含 reason）。

CREATE TABLE inventory_idempotency_keys (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  team_id         BIGINT UNSIGNED NOT NULL,
  idempotency_key VARCHAR(128)    NOT NULL,
  txn_type        VARCHAR(32)     NOT NULL,
  biz_type        VARCHAR(32)     NOT NULL,
  biz_ref_id      BIGINT UNSIGNED NULL,
  sku_id          BIGINT UNSIGNED NOT NULL,
  warehouse_id    BIGINT UNSIGNED NOT NULL,
  content_hash    CHAR(64)        NOT NULL,
  result_kind     VARCHAR(16)     NOT NULL,
  created_at      DATETIME(3)     NOT NULL,
  created_by      BIGINT UNSIGNED NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_invidem_team_key (team_id, idempotency_key),
  KEY idx_invidem_created (team_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT ='幂等键占位记录（差额 0 的无变更操作在此登记，ADR-008）';

-- result_kind 合法值：NO_CHANGE（差额 0，无流水）｜ TXN（已写流水，本批不写入，仅为诊断/扩展预留）
ALTER TABLE inventory_idempotency_keys
  ADD CONSTRAINT ck_invidem_result_kind CHECK (result_kind IN ('NO_CHANGE', 'TXN'));

-- 不建外键：与 inventory_transactions 同款取舍（DATABASE_CONSTRAINTS §3.3 ④
-- 「账本表不建指向主数据的外键，避免删除主数据时账本被阻塞」）。
-- sku_id / warehouse_id 的存在性由应用层 requireBalance/引用校验保证。