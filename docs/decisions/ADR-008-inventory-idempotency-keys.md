# ADR-008：差额为 0 的库存操作如何占用幂等键

- **状态**：已采纳（Proposed → Accepted，2026-10-21）
- **日期**：2026-10-21
- **决策者**：项目负责人（M8-T04）
- **关联**：`DESIGN_REVIEW §5.9` `R76`（P2）、`INVENTORY_WORKFLOW §5.5` 步骤 G、`DATABASE_CONSTRAINTS` `ck_invtxn_delta_non_zero`

## 1. 问题

盘点调整（`COUNT_ADJUST`）在 `target == on_hand`（差额为 0）时，按 `INVENTORY_WORKFLOW §5.5` 步骤 G
**不写流水**、直接返回成功。但幂等键的唯一载体是 `inventory_transactions.idempotency_key`
（`DATABASE_DESIGN §5.10`）——不写流水 ⇒ 该 key 从未落库 ⇒
`replayIfIdempotent → selectByIdempotencyKey` **永远查不到**。

于是幂等语义在差额 0 这一支上**静默失效**，并产生一条真实的危害链（`R76` 实测，`tools/rb-b96`）：

```text
余额 8.000，target=8.000
  → 首次请求：201，txn=null，idempotency_key 0 行          ← key 无载体
  → 期间用 MANUAL_ADJUST −3 把余额改成 5.000
  → 客户端超时重试【同 key 同请求体】
  → 201 + txn=5 + 余额被拉回 8.000 + 流水 +1 + 审计 #12 +1   ← 执行了第二次变更
```

重试把余额**拉回盘点目标值**，静默抹掉中间变动 ⇒ 库存虚增、掩盖缺货，违 `API_CONTRACT §6.3`
「同一幂等键的重复请求必须返回首次结果，不得产生第二次效果」。

## 2. 决策：**方案 A —— 新增轻量 `inventory_idempotency_keys` 表**

差额为 0 的操作**额外**登记一行幂等记录（不写流水、不动余额），使幂等键在所有分支都有载体。

```sql
CREATE TABLE inventory_idempotency_keys (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  team_id       BIGINT UNSIGNED NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  txn_type      VARCHAR(32)  NOT NULL,
  sku_id        BIGINT UNSIGNED NOT NULL,
  warehouse_id  BIGINT UNSIGNED NOT NULL,
  content_hash  CHAR(64)     NOT NULL,   -- sha256(txn_type|biz_type|biz_ref|sku|wh|qty)
  result_kind   VARCHAR(16)  NOT NULL,   -- NO_CHANGE（差额 0）｜ TXN（已写流水，见 §4）
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_invidem_team_key (team_id, idempotency_key)
);
```

### 为什么不选另外两个方案

| 方案 | 内容 | 结论 |
| --- | --- | --- |
| **B 契约明示** | 「差额 0 不占幂等键、不可重试」，写进 `API_CONTRACT` | ❌ **否决**：客户端**无法知道**首次是否差额 0（响应被超时吃掉了），而 HTTP 超时自动重试是网关/SDK 的默认行为。把正确性责任推给调用方解决不了「调用方没有信息」这个根本问题 |
| **C 差额 0 也写流水** | 写一条 `qty_delta = 0` 的流水当载体 | ❌ **否决**：`DATABASE_CONSTRAINTS` 有 `ck_invtxn_delta_non_zero (qty_delta <> 0)`——「流水必有变化」是账本表的基本不变量，放松它会让 0 变化行成为常态，且要改的是**数据库约束**（比改文档步骤硬得多）；`R76` 原裁定亦已否决 |
| **A 独立幂等表** | 差额 0 也占位，但不写流水 | ✅ **采纳** |

### 采纳 A 的理由

1. **职责分离更清晰**：流水表 = 库存变化账本；幂等表 = 请求去重记录。后者本就不该由前者兼职。
   `R76` 的根因正是「一个载体兼职两个职责」，A 是从根上解耦。
2. **不放松任何既有不变量**：不动 `ck_invtxn_delta_non_zero`、不动 §5.5 步骤 G、不动余额口径。
3. **风险面小**：迁移是纯 `CREATE TABLE`，不改任何现有表结构或约束，不需要动
   `uk_invtxn_team_idem` 与流水表本身。

## 3. 读路径：判定顺序

```text
replayIfIdempotent(txnType, bizType, bizRefId, sku, wh, qty, key, balance)
  ① 查 inventory_transactions（现状不变，命中即同内容/异内容判定）
  ② 未命中 → 查 inventory_idempotency_keys
       命中 + content_hash 相同 → 纯重放（返回 idempotentHit=true，txnId=null，余额不动）
       命中 + content_hash 不同 → INV-5003（内容不一致）
  ③ 仍未命中 → 继续写入
```

**与现状的差异**：现状只做 ①。新增的 ② 只在 ① 未命中时执行，**不改变任何既有分支的行为**，
只把「①查不到」从「一定没写过」修正为「可能是差额 0 占位」。

## 4. 写路径：两条登记的关系

- **差额 ≠ 0**：按现状写流水。**是否**同时登记幂等表？—— **否**。流水表已是唯一载体，
  双写会引入两个载体必须永远同步的不变式（本 ADR 要消除的正是这类不变式）。
  幂等表里的 `result_kind = 'TXN'` 只为**历史数据/诊断**保留，本批不写入。
- **差额 = 0**：不写流水，**只**登记幂等表，`result_kind = 'NO_CHANGE'`。

⇒ 两张表**互斥**，不重叠，不可能出现「两边都有且不一致」。

## 5. 后果与已知边界

1. `inventory_idempotency_keys` **只增不删**（无 TTL 清理机制）。与 `import_batches.dedupe_hash`
   槽位占用同一权衡：先保正确性，清理策略留待后续批次（登记 `R197`）。
2. 差额 0 的重放返回 `txnId = null`，与首次一致；`StockChangeResult.idempotentHit = true`。
3. `content_hash` 必须包含**数量**，否则「同 key 换 target」会被误判为同内容。
   比对字段沿用 `R75` 口径（不含 `reason`）。

## 6. 实施与验证

| 步 | 内容 |
| --- | --- |
| 1 | 迁移 `V1.0.13__inventory_idempotency_keys.sql`（下一个号，无跳号） |
| 2 | 同步 9 处结构测试的 `flyway_schema_history` 硬编码（`R69` 三查：条数 / `@DisplayName` / 全量 grep） |
| 3 | `DATABASE_CONSTRAINTS.md` 登记表与约束 |
| 4 | 写路径：`attemptCountAdjust` 差额 0 分支登记幂等行 |
| 5 | 读路径：`replayIfIdempotent` 增加第 ② 步 |
| 6 | 用例：① 差额 0 首次 → 幂等表 1 行、流水 0 行；② 余额被改后同 key 重试 → **纯重放**（余额不变、无新流水）；③ 同 key 换 target → `INV-5003`；④ 差额 ≠ 0 的原有行为不变 |

## 7. 替代方案（已评估未采纳）

- 差额 0 也写 `qty_delta=0` 流水（方案 C）：需放松 `ck_invtxn_delta_non_zero`，代价更大。
- 用 `import_batches.dedupe_hash` 式的「占用-释放」：幂等键**不得释放**（重放语义要求永久占用），
  与导入槽位的「终态释放」语义相反。