# B11 真栈取证归档（M3-T19 硬门槛：导入 / 重导 / 部分失败 / 对账三时点）

本目录是 **B11 的唯一真栈证据来源**。它不是「再跑一遍集成测试」——集成测试跑在 Testcontainers 里、
由 Spring 测试上下文驱动；本脚本跑在**自建 fat jar + 独立 MySQL 容器**上，走**真实 HTTP + 真实文件落盘**，
用来证明「打包后的产物」也具备同样的行为（`M1` 的教训：测试绿 ≠ 能跑）。

| 项 | 值 |
|---|---|
| 端口 | MySQL `3361` / 应用 `18250`（任务书指定；`netstat -ano` 实测二者空闲） |
| 容器 | `b11-mysql`（`mysql:8.0.36`，`--default-time-zone=+00:00`） |
| 应用 | 自建 fat jar（`./mvnw -B -DskipTests package`），`JAR_SHA256` 见 `evidence-b11.txt` 首几行 |
| 种子 | `backend-java/tools/evi/seed-b95.sql`（**未改动**：团队 7 / 仓库 9004=WH-MAIN / SKU 9002·9003·9005 各 10.000 且各有等额 `INIT_IMPORT` 流水 ⇒ 种子阶段对账 = 0） |
| `IMPORT_STORAGE_DIR` | 显式设为 `tools/b11/.import-storage`，`trap cleanup EXIT` 时删除 |

**重跑方式**：

```bash
cd backend-java && ./mvnw -B -DskipTests package
bash backend-java/tools/b11/run-b11-evidence.sh
# 可覆盖： B11_MYSQL_PORT=xxxx B11_APP_PORT=xxxxx
```

产物：`evidence-b11.txt`（断言日志，入库）、`evidence-b11-app.log` / `evidence-b11-headers.log`
（应用 stdout 与响应头，`*.log` 已 gitignore）。

## 一、断言分组（对应任务书【2】A1/A2/B1/B2/C1/D1）

| 组 | 门槛对应 | 内容 |
|---|---|---|
| **A1** | A1 链式重导 | 导入 A → 重导得 B（`attempt_no=2`）→ B 走完流程 → 重导得 C（`attempt_no=3`）；A/B 的 `superseded_by_batch_id` 逐级指向后继、`dedupe_hash` 逐级置空；C 占用槽位且复用同一 `storage_path`；`FORCE_REIMPORT` 审计**恰好 2 条**且 `biz_id ∈ {B, C}`（作用域限定 `team + file_hash`）；链式重导不重复建 SKU |
| **A2** | A2 重导不重复建 SKU | 主数据重导后 SKU 行数不变、`id=9002` 身份不变、编码字面量仍 `SKU-A`（`BINARY` 比较）、可变字段 `name` 已更新、余额**绝对值 10.000**（不翻倍/不清零）、不写库存流水、对账 0 |
| **B1** | B1 部分失败 | `SALES_DAILY` 校验后漂移 `listed_date` ⇒ 终态 `PARTIAL_SUCCESS`、`success=1 / failed=1 / total=2`、行级 `SAL-6003`（≠ `COM-0002`）、成功行可查、失败行不落库、`SALES_DAILY_UPSERT` 恰 1 条且摘要为「共 1 行」 |
| **B2** | B2 重复 + 失败叠加 | ① 同批「大小写变体的同键行 + 非法日期」⇒ 校验 2 条 ERROR（`SAL-6004`/`SAL-6003`）、`confirm` 422 `IMP-4009`、**零落库零审计**；② 合法文件 + 校验后删 SKU 的漂移行 ⇒ `PARTIAL_SUCCESS`、成功恰 1 条、余额恰 +1 行量、失败行不建余额行、对账 0 |
| **C1** | C1 对账三时点 | 时点 1（seed 后）/ 时点 2（导入后）/ 时点 3（重导后）各打印差异行数与余额快照，三次均为 **0 行**；附带 `qty_after = qty_before + qty_delta` 恒等式与 `success+failed=total` 不变式 |
| **D1** | D1 反向误杀 | 全合法文件（同 SKU 跨两仓 + 另一 SKU）⇒ 校验 0 ERROR、终态 **`COMPLETED`**（`chkne` 显式排除 `PARTIAL_SUCCESS`）、`failed_rows=0`、两仓余额各自成立、对账 0 |

> **为什么 D1 要单列**：正向用例（A/B/C）全绿也可能是「把所有文件都判成部分失败」造成的。
> 反向用例是唯一能挡住这种「假绿」的探针。

## 二、与集成测试的分工（避免「再写一遍」）

| 场景 | 集成测试（`M3HardThresholdTest`） | 真栈脚本（本目录） |
|---|---|---|
| 链式重导 A→B→C | ✅ 6 个方法之一（A1） | ✅ 打包产物上复验 |
| 主数据重导余额绝对值 | ✅ A2 | ✅ A2 |
| `SALES_DAILY` 部分失败 + 审计残留 | ✅ B1（含 `success+failed=total` 与审计恰 1 条） | ✅ B1 |
| 重复 + 失败叠加 | ✅ B2（拆两批，原因见下） | ✅ B2 |
| 对账三时点 | ✅ C1（同一方法内连续断言 + 打印） | ✅ C1（每点打印余额快照） |
| 反向误杀 | ✅ D1 | ✅ D1 |

## 三、必须知道的三个坑（本批实测踩到/规避）

1. **`IMP-4009` 是 any-error 硬门**（`ImportConfirmService:277-279`）：只要批次里有 1 条 `ERROR`，
   `confirm` 在状态推进前就被拦下 ⇒ 「同一批既含文件内重复、又成功落库 1 条」在当前规则下**不可达**。
   故 B2 拆成两个批次：重复批验「零落库」，漂移批验「成功恰 1 条」。
2. **`INVENTORY_SNAPSHOT` 命中既有余额行会产生 `IMP-4006` WARNING**（`§7.5` 增量累加提示）。
   这不是错误，但会让 `warning_rows` 非 0 —— 写断言时必须按真实值写（B2 漂移批 = 1，D1 = 2），
   否则会得到「假 FAIL」。
3. **大小写断言必须 `BINARY`**：`utf8mb4_0900_ai_ci` 下 `WHERE sku_code='SKU-A'` 会匹配 `sku-a` 那行；
   同理「文件内重复」的判定键必须折叠后比较（R85）。

## 四、观测到但不属本批范围的既有现象

`confirm` 走「Pass 1 整批失败 → Pass 2 逐行重放」时，应用日志会打一条
`AuditBufferScope` 的 `ERROR ... 已立即补写兜底`（`action 分布={SALES_DAILY_UPSERT=1}` 等）。
这是 `B4-PERF`（`ADR-004 D2`）既有实现的诊断输出：作用域关闭时缓冲非空即按「编程错误」记 ERROR 并补写。
**可观测结果是正确的**——B1/B2 实测 `SALES_DAILY_UPSERT` 恰 1 条、失败行零残留，
故本批只记录现象、不改 `audit` 模块（B11 白名单不含 `audit` 既有 main 代码）。

## 五、实测结果（2026-10-18）

```text
B11_CHECK_TOTAL=73
B11_FAIL_COUNT=0
B11_RESULT=ALL PASS
```

分组断言数：A1=14、A2=10、B1=15、B2=9+8、C1=9、D1=8（A/B/C 各组均 ≥3）。

对账三时点（C1，每点打印差异行数与余额快照）：

| 时点 | 差异行数 | 余额（team 7） |
| --- | --- | --- |
| 时点 1（seed 后） | 0 | `9004/9002=15.000, 9004/9003=10.000, 9004/9005=10.000` |
| 时点 2（导入后） | 0 | `9004/9002=15.000, 9004/9003=15.000, 9004/9005=10.000` |
| 时点 3（重导后） | 0 | `9004/9002=15.000, 9004/9003=20.000, 9004/9005=10.000` |

> 时点 1 的 `9004/9002=15.000` 不是种子值：A2/B2 各组已在其上追加，故 C1 以「当前基线」作为时点 1，
> 再连续验「导入后」「重导后」两点。种子阶段（`seed-b95.sql` 之后、任何导入之前）的对账同为 0，
> 见 `DESIGN_REVIEW §5.15`（`seed-init-9003` 补齐）与集成测试 `M3HardThresholdTest#reconciliationIsZeroAtThreePoints`。

**首轮 8 项 FAIL 的成因（已归档，供后续复用判据）**：全部集中在 B1 组，根因是**脚本夹具**把
销售日与漂移后的 `listed_date` 写成同一天（`2026-10-10 == 2026-10-10`），而 `SAL-6003` 只拦
「晚于今天 / **早于**上架日」⇒ 不构成违规，`confirm` 正常 `COMPLETED`。把销售日改为 `2026-10-09`
后重跑即 0 FAIL。**未改任何 main 代码、未放宽任何断言**（同轮 A1/A2/C1/D1 全绿）。
