# B12 真栈取证归档（R87 / R88：可重导状态集口径收口）

本目录是 **B12 的唯一真栈证据来源**。B12 是微批次——只改「哪些状态允许强制重导」这一条口径，
不碰槽位算法。故本脚本的目的很单一：**在打包产物上证明「`DUPLICATE_REJECTED` 自身重导 → 422 `IMP-4005`」，
且四个合法终态的重导能力一点没被削弱**。

| 项 | 值 |
|---|---|
| 端口 | MySQL `3363` / 应用 `18270`（任务书建议值；`netstat -ano` 实测二者空闲） |
| 容器 | `b12-mysql`（`mysql:8.0.36`，`--default-time-zone=+00:00`） |
| 应用 | 自建 fat jar（`./mvnw -B -DskipTests package`），`JAR_SHA256` 见 `evidence-b12.txt` 首几行 |
| 种子 | `backend-java/tools/evi/seed-b95.sql`（**未改动**，与 B11 同一份：团队 7 / 仓库 9004=WH-MAIN / 商品 9001=P-BAG-001 / `operator01`） |
| `IMPORT_STORAGE_DIR` | 显式设为 `tools/b12/.import-storage`，`trap cleanup EXIT` 时删除 |

**重跑方式**：

```bash
cd backend-java && ./mvnw -B -DskipTests package
bash backend-java/tools/b12/run-b12-evidence.sh
# 可覆盖： B12_MYSQL_PORT=xxxx B12_APP_PORT=xxxxx
```

产物：`evidence-b12.txt`（断言日志，入库）、`evidence-b12-app.log` / `evidence-b12-headers.log`
（应用 stdout 与响应头，`*.log` 已 gitignore）。

## 一、断言分组（对应任务书【五】的 5 条硬要求）

| 组 | 对应要求 | 内容 |
|---|---|---|
| **前置** | 造场 | 上传 → 预览 → 映射 → 校验 → 确认，得到 `A(COMPLETED)`；同文件再传得 `409 IMP-4004` 并落一条 `D(DUPLICATE_REJECTED)`（`dedupe_hash=NULL`、`attempt_no=2`、`duplicate_of_batch_id=A`） |
| **G2** | 要求 ① 错误码 | 对 `D` 自身 `POST .../reimport` ⇒ **422** + `code=IMP-4005`；`chkne` 显式排除 `IMP-4004`（旧行为是 409「请刷新」，是死路） |
| **G3** | 要求 ② 零副作用 | 被拒后：批次总数不变、`IMPORT_BATCH_FORCE_REIMPORT` 审计数为 0、`A.dedupe_hash` 未变、`A.superseded_by_batch_id` 仍 `NULL`、`D` 状态未变 |
| **G6** | 要求 ③ 提示语 | `details[0].message` 含四个合法状态（`COMPLETED`/`PARTIAL_SUCCESS`/`FAILED`/`CANCELLED`）、**不含** `DUPLICATE_REJECTED`；`field=status`；当前状态在 `rejectedValue` 里机读可见 |
| **G1/G5** | 要求 ④ 合法路径不受影响 | `A(COMPLETED)` 重导 ⇒ **201**、新批 `attempt_no=2` 占槽位、`A.dedupe_hash` 置空且 `superseded_by_batch_id=新批`、同 `hash` 恰 1 行占用、`FORCE_REIMPORT` 审计 +1 |
| **G4** | 要求 ⑤ 槽位确已释放 | 重导后同文件再传 ⇒ `409 IMP-4004` 且 `data.existing_batch_id=新批`（若 `A` 槽位没释放，这里会指向旧批或直接报 500） |

> **为什么 G3 必须单列**：只断言「422 + 错误码」是不够的——一个「先插批次再抛异常」的实现同样能返回 422。
> G3 用**副作用快照**（批次总数 / 审计数 / `A` 的槽位与取代标记）把这条路堵死。

## 二、与集成测试的分工（避免「再写一遍」）

| 场景 | 集成测试（`ImportReimportStatusSetTest`） | 真栈脚本（本目录） |
|---|---|---|
| `D` 自身重导 → 422 `IMP-4005` | ✅ `duplicateRejectedBatchReimportRejected` | ✅ G2 |
| 被拒后零副作用 | ✅ 同方法（批次总数 / 审计数 / `A` 槽位 / `A` 未被取代 / `D` 状态） | ✅ G3 |
| 提示语只列四终态 | ✅ 同方法（`message` 正反向 + `field` + `rejectedValue`） | ✅ G6 |
| `COMPLETED` 重导仍正常 | ✅ `completedBatchReimportStillWorks` | ✅ G1/G5 |
| 槽位释放可观测 | ✅ 同方法（`A.dedupe_hash IS NULL`、`superseded_by_batch_id=新批`） | ✅ G4 |

集成测试用 `JdbcTemplate` 直查 + `MockMvc`/真实 HTTP；真栈脚本用 `curl` + `docker exec mysql`，
两者**互不替代**：前者保证逻辑，后者保证「打包后还能这么跑」。

## 三、本批要盯住的三个点（判据，供后续复用）

1. **错误码必须看字符串，不要猜 HTTP 语义**：`IMP-4005` 是 422，`IMP-4004` 是 409。
   `chkne "G2-不是IMP-4004"` 是这条口径的护栏——只断言 422 不能区分「状态守卫拦下」与「重复校验拦下」。
2. **`dataField` 必须先剥信封再取字段**：`ApiResponse` 信封自身带 `"code":"OK"`，
   直接 `grep` 会抓到信封的 `code`，导致「错误码断言恒为 OK」这种假绿。
3. **`DUPLICATE_REJECTED` 的 `dedupe_hash` 恒为 `NULL`**——这是 R87 的根因，也是 G3 里
   「`A` 槽位未变」这条断言的立论基础：`D` 自身重导时第 ② 步释放的是它自己（空转），
   第 ③ 步去占 `file_hash` 槽位时原成功批仍占位 ⇒ `DuplicateKeyException` ⇒ 409「请刷新」死路。

## 四、实测结果（2026-10-18）

```text
B12_CHECK_TOTAL=34
B12_FAIL_COUNT=0
B12_RESULT=ALL PASS
```

分组断言数：前置 5、造 D 5、G2 3、G6 7、G3 5、G1/G5 7、G4 3。

关键原始证据（摘自 `evidence-b12.txt`）：

```text
D-reimport=422 body={"code":"IMP-4005","message":"当前批次状态不允许强制重导","data":null,
  "details":[{"field":"status","message":"仅终态批次可强制重导（COMPLETED / PARTIAL_SUCCESS / FAILED / CANCELLED）",
  "rejectedValue":"DUPLICATE_REJECTED"}],...}

批次全景 team7：
  1/COMPLETED/attempt=1/hash=NULL,
  2/DUPLICATE_REJECTED/attempt=2/hash=NULL,
  3/UPLOADED/attempt=2/hash=42d5120e,
  4/DUPLICATE_REJECTED/attempt=3/hash=NULL
```

> 批次 4 是 G4「重导后同文件再传」留下的新 `DUPLICATE_REJECTED`（`attempt_no=3`），属预期；
> 其 `dedupe_hash` 同样为 `NULL`，不影响批次 3 的占位。

**首轮 2 项 FAIL 的成因（已归档，供后续复用判据）**：`D-existing_batch_id` 与 `G4-existing_batch_id`
实际值均为空串。不是实现问题——响应体里 `data.existing_batch_id` 明明是数字（`1` / `3`），
而是取值函数 `dataField` 的模式 `"key":"[^"]*"` **只匹配字符串值**，抓不到 JSON number。
新增 `dataNum`（`"key":[0-9]+`）后重跑即 0 FAIL。**未改任何 main 代码、未放宽任何断言**。

