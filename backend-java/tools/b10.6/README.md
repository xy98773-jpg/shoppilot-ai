# tools/b10.6 —— R79 / R80 真栈取证

本目录是 **M3-B10.6 回修单**的取证归档（基线 `2e6b65d`）。

## 一、要修的两个缺陷

### R79（P1）Pass 2 行级隔离对「数据类」失效

`ImportConfirmService.writeDataRowBatch` 的外层 `TransactionTemplate`（`REQUIRED`）调用
`InventoryWriteService#applyInitialImport` / `SalesWriteService#batchUpsert`，而这两个入口当时也是
`@Transactional(propagation = REQUIRED)`。于是：

```
行内抛 BizException（INV-5003 / SAL-6003）
  ⇒ 内层 REQUIRED「参与」共享事务，代理在异常时 setRollbackOnly
  ⇒ Spring globalRollbackOnParticipationFailure=true 把共享事务标 rollback-only
  ⇒ 外层 TransactionTemplate.commit() 抛 UnexpectedRollbackException
  ⇒ isRowLevelFailure=false（既不是 BizException 也不是 DuplicateKeyException）
  ⇒ C2 recordWholeBatchFailure：整批回滚 + 每行记 COM-0002（500「系统内部错误」）
```

后果：`INVENTORY_SNAPSHOT` / `SALES_DAILY` 的 **`PARTIAL_SUCCESS` 实际不可达**
（除 `parseDataRow` 阶段失败），任何一行被写服务拒绝就整批 0 行落库，用户只看到「系统内部错误」，
无法定位到行。应用日志特征：`UnexpectedRollbackException ... writeDataRowBatch(...)`。

**修法（首选方案）**：把两个写服务入口的传播改为 `Propagation.NESTED`，与主数据单行入口
`CatalogWriteService#upsertSkuByCode`（`:826` 已是 `NESTED`）对齐。行内失败只回滚到自己这一行的
`SAVEPOINT`，外层批事务继续 ⇒ Pass 2 的行级隔离成立。
**无外层事务时 Spring 把 `NESTED` 退化为「新建事务」** ⇒ `POST /sales/daily`、
`POST /inventory/adjustments` 等 HTTP 直调路径语义不变。

未采用备选方案（「Pass 2 逐行独立事务」）——首选方案改动面最小、与既有主数据范式一致，
且不需要重新编排 Pass 1 的批内原子性与审计缓冲边界。

未使用（且任务书明文禁止）的两条「假修法」：
- ❌ 关掉 `globalRollbackOnParticipationFailure`（全局放宽会掩盖真实回滚需求）；
- ❌ 在 `importdata` 里 `catch` 掉 `UnexpectedRollbackException` 当作成功（事务已回滚 = 假成功）。

### R80（P1）`INIT_IMPORT` 幂等键不含仓库

旧键按 `§5.10` 一般式构造为 `INIT_IMPORT:IMPORT:<batchId>:<skuId>`，不含仓库；
而 `InventoryWriteService#replayIfIdempotent` 的 `sameContent` **比 `warehouse_id`**。于是
「同一批次内同一 SKU 出现在两个仓库」时，第二行命中同一幂等键 + 内容不同 ⇒ 抛 `INV-5003`
（文案「该库存操作已提交，请勿重复提交」，完全误导）。而 `INVENTORY_SNAPSHOT` 模板的
`warehouse_code` 是**必填**列 ⇒ 多仓期初导入是常规场景。

**修法**：`ImportConfirmService#initImportIdempotencyKey(batchId, skuId, warehouseId)`
→ `INIT_IMPORT:IMPORT:<batchId>:<skuId>:<warehouseId>`。依据是项目负责人在 `0f4cade` 订正的
`DATABASE_DESIGN §5.10` 要点 4（`[:<suffix>]` 的判定口径：**若不加后缀会导致「两条合法业务行同键」，
则该后缀是必填而非可选**）。

`replayIfIdempotent` 的比对字段一字未改（`reason` 仍不参与比对 —— R75 维持；`warehouse_id` 继续比）。

## 二、环境与端口

| 项 | 值 |
|---|---|
| MySQL | 独立容器 `b106-mysql`，`mysql:8.0.36`，端口 **3340** |
| 应用 | 自建 fat jar `app/target/shoppilot-app-0.1.0.jar`，端口 **18120** |
| 种子 | `tools/evi/seed-b95.sql`（团队 7，仓库 9004 `WH-MAIN`，SKU-A=9002 / SKU-B=9003 / SKU-C=9005 各 10.000） |
| 额外数据 | 脚本内插入仓库 9006 `WH-B`（跨仓场景用；`seed-b95.sql` 未含，故由脚本补，**不改种子文件**） |

端口刻意避开回修单【0】点名的已用端口：`3319`、`3327–3339`、`18099`、`18107–18119`。

复现：

```bash
cd backend-java && ./mvnw -B -DskipTests package
bash backend-java/tools/b10.6/run-b106-evidence.sh
# 如需换端口：B106_MYSQL_PORT=3341 B106_APP_PORT=18121 bash ...
```

脚本 `trap cleanup EXIT` 会删容器 + kill 应用，可反复执行。

## 三、断言分组（68 项，0 FAIL）

| 组 | 场景 | 关键断言 |
|---|---|---|
| **G1**（R80 正向） | 同批同 SKU 跨两仓：`SKU-A@WH-MAIN 5.000` + `SKU-A@WH-B 7.000` | `COMPLETED`；`success_rows=2`；主仓余额 `10.000→15.000`、副仓 `0→7.000`；本批 2 条 `INIT_IMPORT`；幂等键 `INIT_IMPORT:IMPORT:<b>:9002:9004` 与 `...:9002:9006` 各 1 条；**旧格式键 `...:9002` 为 0 条**；两条流水 `qty_delta` 分别 5.000 / 7.000 |
| **G1R**（R80 重放） | 同一跨仓批次再次 confirm | `200` + `Idempotent-Replayed: true`；流水条数、两仓余额**均不变** |
| **G2**（R79 库存侧） | 同批同 SKU **同仓**两行（5.000 / 9.000，幂等键相同、数量不同） | `PARTIAL_SUCCESS`；`success=1`/`failed=1`；`success+failed=total`；失败行是**第 2 行**；行级码 **`INV-5003`**、级别 `ERROR`、**≠ `COM-0002`**；成功行余额 `10.000→15.000`、本批只 1 条流水；该批同时保留 2 条 `IMP-4006` `WARNING`（校验阶段产物） |
| **G3**（R79 销售侧） | 校验后把 `SKU-C` 的 `listed_date` 改到销售日之后 | `PARTIAL_SUCCESS`；失败行第 2 行；行级码 **`SAL-6003`**、**≠ `COM-0002`**；`sales_daily` 只落成功行（SKU 9002），失败行 0 行 |
| **G4**（主数据回归） | SKU 主数据导入两行 | `COMPLETED`；`success_rows=2`；`skus` 新增 2 行 —— 证明 `NESTED` 改动未带坏主数据路径 |

汇总行输出：

```
B106_CHECK_TOTAL=68
B106_FAIL_COUNT=0
B106_RESULT=ALL PASS
```

## 四、与前端同参数 / 同头

脚本走的就是前端导入向导的同一套接口与同一套参数，**不是**另造的内部调用：

- 上传 `POST /api/v1/import/batches`：`multipart/form-data`，字段 `file`（`text/csv`）+ `bizType`；
- 预览 `GET .../preview?limit=20`、映射 `PUT .../mapping`（`field_mapping_json` + `parse_options_json`）；
- 校验 `POST .../validate`（无 body）；
- 确认 `POST .../confirm`：`Authorization: Bearer <access>` + `Content-Type: application/json` +
  **`Idempotency-Key`**（缺失即 `400 COM-0001`，与前端一致）。
- 所有 curl 均带 `--noproxy '*'`（本机全局 `http.proxy=127.0.0.1:7890` 会劫持 localhost）。

## 五、关键 SQL 前后值

| 场景 | 表 / 列 | 前 | 后 |
|---|---|---|---|
| G1 跨仓 | `inventory_balances.on_hand_qty`（9004/9002） | 10.000 | **15.000** |
| G1 跨仓 | `inventory_balances.on_hand_qty`（9006/9002） | 行不存在 | **7.000** |
| G1 跨仓 | `inventory_transactions`（team 7） | 2 条（种子） | **4 条**（本批 +2） |
| G1 跨仓 | `inventory_transactions.idempotency_key` | 无 | `INIT_IMPORT:IMPORT:<b>:9002:9004` / `...:9002:9006` |
| G1R 重放 | 上述两处余额 + 流水条数 | 15.000 / 7.000 / 5 条 | **完全不变** |
| G2 同仓重复 | `inventory_balances.on_hand_qty`（9004/9003） | 10.000 | **15.000**（只加成功行 5.000） |
| G2 同仓重复 | `inventory_transactions`（本批 `biz_ref_id`） | 0 | **1**（失败行不写流水） |
| G3 销售失败 | `sales_daily`（team 7） | 0 行 | **1 行**（仅 SKU 9002） |
| G4 主数据 | `skus`（`SKU-B106-%`） | 0 | **2** |

\* 文末「数据全景」是**全部组跑完后的累计值**（`balances=4 txn=5 sales=1 skus=5 batches=4`）：
`txn=5` = 种子 2 + G1 跨仓 2 + G2 同仓重复 1（G3/G4 不写库存流水）。

## 六、复现坑（本机实测，写下来省下一次踩）

1. **`mysql -e` 里取错误行必须先按 `error_level='ERROR'` 过滤**：一个批次里「成功行」可能带
   `IMP-4006`（`WARNING`），若用 `ORDER BY row_no LIMIT 1` 直接取首条，会拿到第 1 行的 WARNING
   而不是第 2 行的 ERROR —— 第一版脚本正是因此产生 3 条**假 FAIL**（断言错，不是代码错）。
2. **同一 `row_no` 下 WARNING 与 ERROR 可以并存**（跨阶段：校验阶段写 WARNING、confirm 阶段补 ERROR）。
   `G2` 里两行都命中已有余额 ⇒ 2 条 `IMP-4006`；第 2 行再追加 1 条 `INV-5003` `ERROR`。
3. **G1 的 `warning_rows` 是 1 不是 2**：跨仓场景第 1 行（已有余额）告警、第 2 行（`WH-B` 无余额行）不告警。
4. **`listed_date` 漂移必须在校验之后做**：`ImportValidationService` 只拦「晚于今天」，
   不查 `skus.listed_date` ⇒ 这是唯一能让 `SalesWriteService#validate` 的 `SAL-6003` 在 confirm 阶段触发的路径。
5. **`INVENTORY_SNAPSHOT` 不做文件内重复校验**（`§10.2` 只对 `SALES_DAILY` 单列
   `warehouse|sku|date`）⇒ 「同批同 SKU 同仓两行」能顺利进到 confirm，由写服务的幂等键判定失败。
6. **应用日志必须写独立文件**：与 `tee` 共用同一文件会互相覆盖（B8 脚本第一版即因此丢过整段）。
7. **`curl` 必须 `--noproxy '*'`**；**`mysql` 必须 `--default-character-set=utf8mb4`**（否则中文断言
   字面量退化成 `?`，产生假 FAIL）。
8. **`-Dtest=Class#a+b`** 语法可一次只跑两个方法（负向验证时省时间）；跨模块要加
   `-Dsurefire.failIfNoSpecifiedTests=false`，否则无匹配用例的模块会 FAIL。

## 七、探针有效性（负向验证）

新增用例不是「反正都会绿」的装饰。本批对两类缺陷各做了一次**回退实测**：

| 回退内容 | 探针 | 实际结果 |
|---|---|---|
| 两个写服务入口改回 `REQUIRED` | `sameSkuSameWarehouseIsIsolatedAtRowLevel`、`salesDailyWriteFailureIsRowLevel` | **2 项均失败**：`status=FAILED`、`success_rows=0`、`failed_rows=2`（与缺陷报告的现象一致） |
| `initImportIdempotencyKey` 去掉 `:<warehouseId>` | `sameSkuAcrossTwoWarehousesIsLegal` | **失败**：`expected COMPLETED but was PARTIAL_SUCCESS`（`success=1`/`failed=1`） |

回退后均已恢复，最终 `verify` 与真栈结果均基于修复后的代码。

## 八、文件清单

| 文件 | 说明 |
|---|---|
| `run-b106-evidence.sh` | 取证脚本（可重复执行，自带 cleanup） |
| `evidence-b106.txt` | 68 项断言原始输出（入库） |
| `cross-warehouse.csv` | G1/G1R：同批同 SKU 跨两仓 |
| `same-warehouse-dup.csv` | G2：同批同 SKU 同仓两行 |
| `sales-listed-drift.csv` | G3：销售侧上架日漂移 |
| `sku-master.csv` | G4：主数据 SKU 回归 |
| `evidence-b106-app.log` / `evidence-b106-headers.log` | 应用 stdout / 最近一次响应头（`*.log` 已 gitignore，不入库） |
