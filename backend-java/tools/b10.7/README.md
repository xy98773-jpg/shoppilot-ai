# M3-B10.7（R83 + R84）真栈取证

| 项 | 值 |
| --- | --- |
| 基线 | `a8b79dd`（工作区干净；基线 verify 399 / 0 / 0 / 0） |
| 缺陷 | **R83** `INVENTORY_SNAPSHOT` 无「文件内重复」校验；**R84** 缺 `POST /api/v1/import/batches/{id}/reimport` |
| 修复后 verify | **410 / 0 / 0 / 0**（399 + R83 4 项 + R84 7 项） |
| 真栈断言 | **115 项，FAIL 0**（`B107_RESULT=ALL PASS`） |
| 端口 | MySQL **3342** / 应用 **18200**（独立容器 `b107-mysql`，`mysql:8.0.36`，**不**复用 dev 栈） |
| JAR_SHA256 | `0aebc7db32985efd8dec08de960223324a7aec47fd2f08e19d062e416eaec844` |
| 取证时间 | `2026-10-17T20:06:47Z` |
| 迁移 | 零迁移（仍 `V1.0.0`–`V1.0.8`，`V1.0.9` 未分配） |

## 一、用法

```bash
cd backend-java && ./mvnw -B -DskipTests package     # 先建 fat jar
bash backend-java/tools/b10.7/run-b107-evidence.sh   # 可重复执行，trap cleanup EXIT 自动清场
```

可用 `B107_MYSQL_PORT` / `B107_APP_PORT` 覆盖端口。

## 二、产物

| 文件 | 说明 |
| --- | --- |
| `run-b107-evidence.sh` | 取证脚本（可重复） |
| `evidence-b107.txt` | **115 项断言原始输出**（入库） |
| `evidence-b107-app.log` | 应用 stdout（`*.log` 已 gitignore） |
| `evidence-b107-headers.log` | 最近一次响应头（`*.log` 已 gitignore） |
| `README.md` | 本文件 |

CSV 由脚本在运行时生成（不入库，避免与后续批次编号冲突）。

## 三、断言分组（115 项）

| 组 | 对应任务书 | 关键断言 |
| --- | --- | --- |
| G1 | R83-a | 同仓同 SKU **同数量**两行 → `error_rows=1`、后行 `COM-0001`/`ERROR`、文案逐字、confirm `422 IMP-4009`、余额与流水不变 |
| G2 | R83-b | 同仓同 SKU **不同数量**两行 → 同样后行 `COM-0001`（≠`INV-5003`）、confirm `422 IMP-4009`、**本批 0 条流水** |
| G3 | R83-c | 同 SKU **跨两仓** → 校验 0 错、`COMPLETED`、两仓余额 13.000/4.000、两条幂等键后缀 `:9004`/`:9006`（**R80 不回退**） |
| G4 | R83-d | 回归：`SALES_DAILY` 三元组重复仍 `SAL-6004`；主数据 `SKU` 编码重复仍 `CAT-3002`（后行） |
| G5 | R84-e | `201 UPLOADED`、`attempt_no=原+1`、`dedupe_hash=file_hash`、六个副本字段逐项对齐、原批 `superseded_by_batch_id` 回填且 `dedupe_hash=NULL`、槽位唯一占用者=新批、审计恰好 1 条（`biz_id`=新批、记原批 id） |
| G6 | R84-f | `UPLOADED` / `VALIDATED` 重导 → `422 IMP-4005`，状态不变、不建新批 |
| G7 | R84-g | 不存在 / 跨团队 → `404 IMP-4007`（≠`IMP-4004`）；已被取代 → `422 IMP-4005`、链条不分叉、审计仍 1 条 |
| G8 | R84-h | reimport 后同文件 upload → `409 IMP-4004` 且 `existing_batch_id`=**新批**；新批释放槽位后 upload → `201` |
| G9 | 门槛 4 | R79 `PARTIAL_SUCCESS` 不回退：写服务内部失败 → `SAL-6003`（≠`COM-0002`）、成功行已落 |
| G10 | 门槛 3 | 对账：`余额 = Σ流水` 返回 **0 行**；`qty_after = qty_before + qty_delta` **0 行**；`success+failed=total` **0 行** |

## 四、关键 SQL 前后值

```
G1 前置 SKU-B@WH-MAIN=10.000 流水=3
G1 validate → err=1 warn=1
G1 errors[] = [ {row_no:2, ERROR,   COM-0001, "同一仓库、同一 SKU 在文件内重复"},
                {row_no:2, WARNING, IMP-4006, "该仓库与 SKU 已有余额行…"},
                {row_no:1, WARNING, IMP-4006, …} ]        ← WARNING 与 ERROR 并存（R83 要求 5）
G1 confirm  → 422 IMP-4009；余额 10.000（不变）、流水 3（不变）

G3 前置 SKU-C@WH-MAIN=10.000 SKU-C@WH-B=<无行>
G3 confirm  → COMPLETED；余额 13.000 / 4.000；两条幂等键
              INIT_IMPORT:IMPORT:3:9005:9004 / INIT_IMPORT:IMPORT:3:9005:9006

G5 原批 id=6（COMPLETED, attempt_no=1）→ reimport → 新批 id=7（UPLOADED, attempt_no=2）
   原批 superseded_by_batch_id=7、dedupe_hash=NULL
   新批 dedupe_hash=9838514b…（= file_hash）、duplicate_of_batch_id=NULL、total_rows=0
   审计 IMPORT_BATCH_FORCE_REIMPORT × 1，biz_id=7，change_summary 含「原批次 id=6」

G8 同文件 upload → 409 IMP-4004，data.existing_batch_id=7（新批）
   新批置 FAILED + dedupe_hash=NULL → upload → 201（新批 id=12，占槽位）

G10 对账明细 9004/9002:10.000 9004/9003:10.000 9004/9005:13.000 9006/9005:4.000 → 差异 0 行
    SELECT COUNT(*) FROM inventory_transactions WHERE qty_after <> qty_before + qty_delta → 0
```

## 五、探针有效性（负向验证，两次回退实测）

| 回退 | 结果 |
| --- | --- |
| R83：把 `if (bizType == INVENTORY_SNAPSHOT)` 短路掉 | `ImportDataConfirmIntegrationTest` 两条探针失败，`error_rows` 期望 1 实际 0（`snapshotDuplicateWithSameQtyIsError` / `…WithDifferentQtyIsError`） |
| R84：把三步槽位的第 ③ 步（占用新槽位）写成占位值 | `ImportReimportIntegrationTest` 两条失败：`reimportSuccessfulBatchTransfersSlotAndAudits`（`dedupe_hash` 期望 file_hash 实际占位值）、`reimportReleasesOriginSlotAndOccupiesNewSlot`（upload 期望 409 实际 201 —— 新槽位没占住，重复文件被当成全新上传） |

两次回退均已恢复，最终结论基于修复后代码。

## 六、复现坑（本批新踩 / 沿用）

1. **`import_row_errors` 取错误行必须带 `AND error_level='ERROR'`**：同一批次里成功行可能带 `IMP-4006`（WARNING），`ORDER BY row_no LIMIT 1` 会先拿到 WARNING（沿用 B10.6 的坑）。
2. **R83 之后「同仓同 SKU 同数量两行」的 `warning_rows` 仍是 1**：第 1 行有 WARNING 无 ERROR；第 2 行 WARNING+ERROR 并存，只计 `error_rows`。断言不要写成「无 WARNING」。
3. **`confirm` 的前置门对「1 行 ERROR / 2 行」同样生效**（`422 IMP-4009`，不是 `IMP-4010`）——`IMP-4010` 只在**全行**失败时出现。
4. **`3350` 被宿主进程 `WorkBuddy.exe` 占用**（`netstat -ano` 实测 PID 14500），任务书【3】指定的 MySQL 端口无法使用 ⇒ 下调到 `3342`（应用侧仍用任务书给的 `18200`）。
5. **`seed-b95.sql` 自身违反对账不变量**：给 SKU-B(9003) 建了 10.000 的余额行却没有对应流水 ⇒ 对账 SQL 在种子阶段就返回 1 行。脚本内补一条种子流水（`seed-init-9003`）后为 0 行；**未改 seed 文件**，已登记偏差。
6. 沿用：`curl --noproxy '*'`（Clash 劫持 localhost）、`mysql --default-character-set=utf8mb4`（否则中文断言退化成 `?`）、应用日志独立文件（不可与 `tee` 共文件）。

## 七、文件清单（本批改动）

| 文件 | 变更 |
| --- | --- |
| `importdata/application/ImportValidationService.java` | R83：`duplicateSnapshotKey` + 类头文档；`applyOverwriteWarnings` 改为「WARNING 与 ERROR 可并存」（去掉 `hasError` 前置门） |
| `importdata/application/ImportUploadService.java` | R84：`reimport` / `doReimport` / `copyForReimport` / `assertOriginalFileReadable` / `recordReimportAudit` + 三步槽位文档 |
| `importdata/api/ImportBatchController.java` | R84：`POST /{id}/reimport`，`@RequireRole` 与 upload 一致 |
| `app/src/test/.../ImportDataConfirmIntegrationTest.java` | R83 四项（a–d）+ R79-1 迁移为「外层事务内 SAVEPOINT」场景 |
| `app/src/test/.../ImportReimportIntegrationTest.java`（新） | R84 七项（e–j） |
