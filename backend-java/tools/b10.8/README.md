# B10.8 取证归档（R85：编码大小写一律不敏感等价 + seed 对账缺口）

## 一、本批修的是什么

| 编号 | 现象（修复前） | 根因 | 修法 |
|---|---|---|---|
| **R85** | 同批两行 `SKU-A,WH-MAIN,2.000` / `sku-a,WH-MAIN,3.000` → 校验 `error_rows=0`（未拦截）；`confirm` `PARTIAL_SUCCESS 1/1`；余额只 `+2.000` | 三处「文件内重复」判定键用 Java 字符串比较（大小写敏感），而 `getIdsByCodes` 走 `in("sku_code", …)` 受 `utf8mb4_0900_ai_ci`（大小写不敏感） | 全部判定键统一 `trim` + `toUpperCase(Locale.ROOT)`（§8.6 裁定），共用**唯一**实现 |
| **R85 连带**（任务书未列，SSOT 同条款） | 文件里写 `sku-a` / `wh-b` 时：`validate` 阶段引用查表落空 → 误以为「引用不存在」；`confirm` 阶段 `CAT-3001` / `CAT-3003` 误报，`status=FAILED` | `getIdsByCodes` 返回的 Map 键是**库里存的原样写法**（`ids.put(row.getSkuCode(), …)`），而调用方用**文件里的原始大小写**去 `get` | 查表前用 `ImportCodeKeys.fold(...)` 把返回表折叠成规范化键 |
| **seed** | `seed-b95.sql` 给 SKU-B(9003) 建了 10.000 余额行却**没有对应流水** ⇒ `TEST_STRATEGY §7.3` 对账 SQL 在种子阶段就返回 1 行 | 种子漏了一条 `INIT_IMPORT` | 补一条 `(7, 9004, 9003)` 的 `INIT_IMPORT`，键 `seed-init-9003` |

### 为什么口径是「大小写不敏感」而不是「区分大小写」

`skus.sku_code` / `warehouses.code` / `suppliers.code` 的唯一键 `(team_id, <code>)` 受
MySQL 的 `utf8mb4_0900_ai_ci` 约束 ⇒ **物理上不可能**共存 `SKU-A` 与 `sku-a` 两条记录。
因此「引用解析大小写不敏感」是**既成事实**（所有编码查询 SQL 都走该排序规则）；
判定键若用大小写敏感比较，必然与之分叉。

❌ 反向方案（把排序规则改成 `utf8mb4_bin` / `BINARY`）**被任务书明令禁止**，
本文档只作记录，不建议回头改。

## 二、环境与产物

| 项 | 值 |
|---|---|
| 端口 | MySQL `3355` / 应用 `18210`（避开 3319、3327–3342、18099、18107–18200；**3350 被宿主 `WorkBuddy.exe` 占用，任务书亦已禁**） |
| 容器 | `b108-mysql`（`mysql:8.0.36`，`--default-time-zone=+00:00`） |
| 应用 | 自建 fat jar（`./mvnw -B -DskipTests package`），`JAR_SHA256` 见 `evidence-b108.txt` 首几行 |
| 种子 | `backend-java/tools/evi/seed-b95.sql`（**未在脚本内额外补流水**——本批已把缺口补进 seed 文件本身） |
| `IMPORT_STORAGE_DIR` | 显式设为 `tools/b10.8/.import-storage`，`trap cleanup EXIT` 时删除，避免上传文件落进仓库 |

**重跑方式**：

```bash
cd backend-java && ./mvnw -B -DskipTests package
bash backend-java/tools/b10.8/run-b108-evidence.sh
# 可覆盖： B108_MYSQL_PORT=xxxx B108_APP_PORT=xxxxx
```

产物：`evidence-b108.txt`（断言日志，入库）、`*.log`（应用 stdout / 响应头，已 gitignore）。

## 三、断言分组

| 组 | 门槛对应 | 内容 |
|---|---|---|
| **G1** | 门槛 2 a | 快照同仓 `SKU-A` + `sku-a` → 后行 `ERROR COM-0001`、文案逐字、`error_rows=1`/`warning_rows=0`、`confirm` 422 `IMP-4009`、余额与流水均不变 |
| **G2** | 门槛 2 b | 销售同 `(WH-MAIN, SKU-A, 同日期)` 但大小写不同（且仓库也写作 `wh-main`）→ 后行 `SAL-6004`（**不是** `COM-0001`）、文案逐字、`confirm` 422、一行都不落库 |
| **G3** | 门槛 2 c | SKU 主数据 `SKU-NEW-1` + `sku-new-1` → 后行 `CAT-3002`（**不是** `COM-0001`）、SKU 未新增 |
| **G4** | 门槛 2 d | 回归：同仓同 SKU **同大小写**两行仍判重复（`R83` 不回退） |
| **G5** | 任务书未列 | 单行**全小写** `sku-b,wh-main` → 校验 `error_rows=0`/`warning_rows=1`（命中 `IMP-4006`）、`confirm` 200 `COMPLETED`、余额 `+1.000`、流水 `+1`、幂等键用库中原样写法 |
| **G6** | 门槛 4 边界 1 | `R80` 跨仓不误杀：同 SKU 两仓（且仓库写作 `wh-b`）→ `COMPLETED`、两仓余额 `13.000`/`4.000`、幂等键后缀 `9004`/`9006` |
| **G7** | 门槛 4 边界 2 | `R79` `PARTIAL_SUCCESS` 仍可达：改 `listed_date` 制造写服务内部失败 → 行级 `SAL-6003`（**不是** `COM-0002`）、成功行已落库 |
| **G8** | 门槛 3 | 跑完 `seed-b95.sql` 后**直接**执行 `§7.3` 对账 SQL → **0 行**（不额外补流水）；`qty_after = qty_before + qty_delta` 违例 0 行；`success+failed=total` 违例 0 行；「有余额行但无任何流水」的行数 0 |

## 四、关键 SQL 与前后值

### G1（缺陷复现点）

```sql
-- 校验期：两行键在库看来是同一个 (WH-MAIN, 9002)，但判定键不折叠 ⇒ error_rows=0
SELECT row_no, error_code, error_level, error_message
  FROM import_row_errors WHERE batch_id = <G1> ORDER BY row_no;
-- 期望：row_no=2 | COM-0001 | ERROR | 同一仓库、同一 SKU 在文件内重复

-- 修复前该批次 confirm 会走到 PARTIAL_SUCCESS 且余额只 +2.000：
SELECT on_hand_qty FROM inventory_balances
 WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002;
-- 期望：与前置值一致（被 IMP-4009 前置门拦下，一行都不落）
```

### G5（引用解析，SSOT 同条款）

```sql
-- 小写编码解析到的是同一个 SKU-B（库中原样写法），流水正常
SELECT COUNT(*) FROM inventory_transactions
 WHERE team_id=7 AND sku_id=9003;                 -- 前 → 后：+1
SELECT on_hand_qty FROM inventory_balances
 WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003;   -- 10.000 → 11.000
```

### G8（seed 对账）

```sql
-- 跑完 seed-b95.sql（未补任何额外流水）后执行 TEST_STRATEGY §7.3：
SELECT COUNT(*) FROM (
  SELECT b.team_id, b.warehouse_id, b.sku_id, b.on_hand_qty,
         COALESCE(SUM(t.qty_delta), 0) AS derived_qty
    FROM inventory_balances b
    LEFT JOIN inventory_transactions t
      ON t.team_id = b.team_id AND t.warehouse_id = b.warehouse_id AND t.sku_id = b.sku_id
   GROUP BY b.team_id, b.warehouse_id, b.sku_id, b.on_hand_qty
  HAVING b.on_hand_qty <> COALESCE(SUM(t.qty_delta), 0)) x;
-- 期望：0   （B10.7 时此处是 1，脚本内靠「临时补一条流水」绕过；本批已把缺口补进 seed 文件）

SELECT COUNT(*) FROM inventory_transactions
 WHERE qty_after <> qty_before + qty_delta;
-- 期望：0
```

## 五、探针有效性（负向验证）

把 `ImportCodeKeys#normalize` 临时改成只 `trim`（关闭大小写折叠），重跑
`ImportDataConfirmIntegrationTest`：**26 项中 5 项失败**，且现象与缺陷报告一致：

| 失败用例 | 断言 | 实测（折叠关闭后） |
|---|---|---|
| `snapshotCaseInsensitiveDuplicateIsError` | `error_rows` = 1 | **0** |
| `salesDailyCaseInsensitiveDuplicateIsError` | `error_rows` = 1 | **0** |
| `masterDataCaseInsensitiveDuplicateIsError` | `error_rows` = 1 | **0** |
| `lowercaseCodeStillTriggersOverwriteWarning` | `warning_rows` = 1 | **0**（漏报 `IMP-4006`） |
| `lowercaseCodeResolvesAndImportsSuccessfully` | `status` = `COMPLETED` | **`FAILED`**（`CAT-3001` 误报，整批回滚） |

`snapshotExactCaseDuplicateStillError`（R85-d 回归项）**仍通过** —— 这是正确的：
同大小写的重复不依赖折叠，所以它验证的是「R83 未被破坏」，不是「R85 生效」。
这一区分很重要：**若回归项也跟着失败，说明折叠实现误伤了原有行为**。

恢复后 26 项全绿；负向验证期间的临时改动已全部还原（`ImportCodeKeys.java` 恢复为
`raw.trim().toUpperCase(Locale.ROOT)`）。

## 六、实现要点（下次接续先看这段）

1. **唯一实现落在包级工具类** `ImportCodeKeys`（`normalize` / `fold`），
   两个 service 各自保留同名薄封装（`normalizeKey` / `foldKeys`）只为可读性。
   任务书要求「抽一个私有工具方法、三处共用、禁止复制三份」——若只放在
   `ImportValidationService` 里，`ImportConfirmService` 就用不到（跨类），故提到包级。
2. **`trim` 必须在 `toUpperCase` 之前**，且**必须带 `Locale.ROOT`**。
   土耳其语环境下 `"i".toUpperCase()`（默认语言）会得到「带点大写 I」，
   与 `utf8mb4_0900_ai_ci` 的折叠结果不一致 ⇒ 同一编码在不同机器上判定不同。
3. **`getIdsByCodes` 的 Map 键是库里写法**，这是 R85 连带缺陷的唯一来源。
   只要调用方用文件原始大小写查表就必然落空。⚠️ 将来若在别处新增对该方法的调用，
   记得先 `fold()`。
4. **错误文案回显用户原样写法**（`raw.trim()`），不折叠 —— 便于用户在自己的文件里定位；
   只有**比较**用折叠值。

## 七、复现坑（真栈脚本层面）

- **`import_row_errors` 取错误行必须加 `AND error_level='ERROR'`**：同一批次里成功行可能带
  `IMP-4006`（`WARNING`），用 `ORDER BY row_no LIMIT 1` 会取到第 1 行的 `WARNING`。
- **CSV 解析器不对单元格 `trim`**：`"SKU-A "` 与 `"SKU-A"` 在判定键上必须等价，故 `trim` 是口径的一部分。
- **端口先 `netstat -ano` 再用**：3350 被宿主 `WorkBuddy.exe` 监听（PID 14500），
  任务书已禁；分配新端口前务必实测。
- **`pwd -W` 转 Windows 路径**：Windows 原生 `curl -F file=@...` 不认 `/d/...`。
- **`--noproxy '*'`**：本机全局 `http.proxy=127.0.0.1:7890`（Clash）会劫持 `localhost`。
- **`--default-character-set=utf8mb4`**：否则 `mysql` 客户端的中文字面量退化成 `?`，产生假 `FAIL`。
- **应用日志写独立文件**，不要与 `tee` 共用同一路径（会互相覆盖）。
- **显式设 `IMPORT_STORAGE_DIR`**：否则上传文件落到仓库根的 `./data/import/`。

## 八、偏差登记

见交付汇报【6】。要点：

1. 修了任务书未列、但 SSOT §8.6 同条款明文包含的**引用解析**第四处。
2. `SALES_DAILY` 三元组原实现**连 `trim` 都没做**，本次一并补（任务书已明确要求）。
3. 测试首轮 2 项失败属**我自己的断言/夹具错误**（`compareTo` 相等返回 0；
   夹具给 SKU-A 建余额行却用 `sku-b` 指向 SKU-B），非代码缺陷，已修正。