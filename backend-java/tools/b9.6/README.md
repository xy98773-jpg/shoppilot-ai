# B9.6 真栈取证（R74 `Idempotent-Replayed` 头误标）

本目录是 **M3-B9.6 微回修单**的真栈取证套件（`R47`：脚本 + 种子 + 说明必须入库）。
目标是在**独立 MySQL + 自建 fat jar** 上证明：**真重放仍带头**、**无变更成功不再带头**。

## 缺陷

`M3-B9.5` 可选项 2.4 给调整/冲销端点加了 `Idempotent-Replayed: true` 响应头（`API_CONTRACT §6.3`），
判据直接用了 `StockChangeResult.idempotentHit()`。但该字段口径是「命中既有幂等键 **或** 差额为 0 不写流水」
（`INVENTORY_WORKFLOW §5.5` 步骤 G），于是：

```
POST /api/v1/inventory/adjustments  txn_type=COUNT_ADJUST  target=10.000（当前也是 10.000）
→ 201 + Idempotent-Replayed: true      ← 既非重放、也无变更
```

B10 前端若据此提示「该操作已提交过」，一次正常的无差异盘点就被谎报成重放。

## 修法（只两处，均在 `inventory` 模块）

| 文件 | 改动 |
| --- | --- |
| `StockChangeResult` | 新增派生方法 `replayed() { return idempotentHit && txnId != null; }`，**不改构造参数、不改字段值** |
| `InventoryController#markReplayed` | 判据由 `idempotentHit()` 换成 `replayed()` |

★ **不能**把差额 0 分支的 `idempotentHit` 由 `true` 改成 `false`：`recordAdjustAudit` /
`recordReversalAudit` 用它跳过审计（B9 裁定「无变更不写审计」），改字段值会连带改审计语义。
本单因此只做**派生判定**。

## 文件

| 文件 | 作用 |
| --- | --- |
| `run-b96-evidence.sh` | 采集脚本：起独立 MySQL 8.0.36 + 自建 fat jar，跑 W1–W3 |
| `evidence-b96.txt` | 运行产物（含 jar SHA256、逐条 `PASS/FAIL`、SQL 与响应头原文） |
| `../evi/seed-b95.sql` | 复用 B9.5 种子（团队 7/8、SKU-A/B/C 各 10.000、2 笔可冲销流水） |

`evidence-b96-app.log` / `evidence-b96-headers.log` 是运行中间产物，已被 `.gitignore` 的 `*.log` 覆盖。

## 环境

| 项 | 值 |
| --- | --- |
| MySQL | 独立容器 `b96-mysql`，宿主端口 **3335** |
| 应用 | 自建 fat jar，端口 **18115** |
| 口令 | `S3cret!Passw0rd`（与 `seed-b7/b8/b9/b95` 同源哈希） |

端口刻意避开历史批次与复核已用过的 `3319 / 3327–3334` 与 `18099 / 18107–18114`。

## 组次与断言

| 组 | 断言 | 依据 |
| --- | --- | --- |
| W1 | `COUNT_ADJUST` 目标 `10.000` == 当前 `10.000` → **201**、`transaction_id` 为 `null`、**无** `Idempotent-Replayed`、不写流水、`#12` 审计不增、余额不变 | `§5.5` 步骤 G + `§6.3` 头语义（**R74 修复点**） |
| W2 | 同键重放（有实际变更的 `10.000 → 6.000`）：首次 **201 无头**；重放 **201 + 同一 `transaction_id` + 头 `true`** | `§6.3`；**反向防线**：修 R74 不得变成「重放也不带头」 |
| W3 | 冲销首次 **200 无头**；同键重放 **200 + 头 `true` + 同一 `transaction_id`**，`REVERSAL` 仍 1 行 | `R73` 回归不退化 |

★ 数据分工：W1 用 `SKU-A`（差额 0，余额不动），W2 用 `SKU-B`（10 → 6），W3 用 `SKU-C` 的
`seed-init-9005`（10 → 0）。三组互不污染；若 W1 与 W2 共用 SKU，差额 0 的断言会被后续变更掩盖。

★ 审计条数一律 `WHERE action=? AND team_id=?`（登录会写 `AUTH_LOGIN_SUCCESS`，全表 0 行永不成立）。

## 结论（本轮 2026-10-17）

```
JAR_SHA256=1c7c11f027136cc0998c7774d50c78955e501ed200b71f813e455c328598c869
B96_CHECK_TOTAL=28
B96_FAIL_COUNT=0
B96_RESULT=ALL PASS
```

关键原文：

```
W1-http=201  body.transaction_id=null  replay_header=[]     → 流水 2（不变）/ #12=0 / 余额 10.000
W2-首次 201 txn=3 header=[]  → 重放 201 txn=3 header=[true] → 余额 6.000 / #12=1 / SKU-B 流水 1 条
W3-首次 200 txn=4 header=[]  → 重放 200 txn=4 header=[true] → REVERSAL=1 / #13=1 / 余额 0.000
```

修复前 W1 的 `replay_header` 为 `true`（即项目负责人复核 `rb-b95` 的 `P1` FAIL）；本次 `[]` 即 R74 已闭环。
W2/W3 的 `[true]` 是**反向防线**：头只在「真重放」时出现，不会因修 R74 而整体消失。
