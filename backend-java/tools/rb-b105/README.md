# B10.5（R78 补挂两类导入落库）独立复核 —— 项目负责人侧

**复核对象**：`f7ead47 → 7f4e79b`（2 笔提交，18 文件 `2003+/30-`，全在 `backend-java/` 的
`catalog` / `inventory` / `sales` / `importdata` 与 `app` 测试）。
**结论**：**R78 修复成立**（六类分派、增量/覆盖语义、`IMP-4006`、行级引用错误均实测有效），
但**真栈抓到两条本批引入的 P1 缺陷**：`R79`（Pass 2 行级隔离失效 → 整批 `COM-0002`）与
`R80`（`INIT_IMPORT` 幂等键不含仓库 → 同批同 SKU 跨仓被误判冲突）。**发 B10.6 回修，阻塞 M3 验收**。

---

## 一、机器核验（不采信自报）

| 项 | 自报 | 复核实测 | 结论 |
| --- | --- | --- | --- |
| `./mvnw -B verify` | 393 / 0 / 0 / 0 | **393 / 0 / 0 / 0 / BUILD SUCCESS（12m41s）** | 一致 |
| 禁改项 | 零迁移 / 零前端 / 零 docs / 枚举未改 | `git diff --name-only` 四项全空；迁移仍 `V1.0.0`–`V1.0.8`；`V1.0.9` 未分配 | 一致 |
| 开发真栈 | 68 项 0 FAIL | 产物真实（`evidence-b105.txt` 68 PASS，`JAR_SHA256=31c5bb84…` 与脚本记录一致） | 一致 |
| 生产代码删除行 | — | 仅注释/常量/签名；`ImportValidationService` 与 `ImportConfirmService` 无逻辑削弱 | 成立 |

## 二、独立真栈（本目录，端口 `3339` / `18119`，容器 `rb105-mysql`）

`RB105_CHECK_TOTAL=89 / FAIL=6`。**6 条 FAIL 全部指向 R79 与 R80，无其他失败**。

| 组 | 覆盖 | 结果 |
| --- | --- | --- |
| A | 快照首导：增量、`INIT_IMPORT`、幂等键字面、`last_txn_id`、二次确认重放 | 18 项全绿 |
| **B ★** | **同批同 SKU 跨仓**（开发未测，仅申报） | **4 FAIL → R80 + R79** |
| B2 ★ | 隔离实验：同一 SKU 单独导入副仓 | 全绿（副仓余额 9.000）⇒ 唯一变量是同批碰撞 |
| C ★ | `qty=0`（有余额行 / 无余额行） | 全绿 + 1 NOTE（口径） |
| D1/D2 | 未知仓库 `CAT-3003`、跨团队 `CAT-3005`（校验阶段即拦） | 全绿（我的首版断言写错，已修正） |
| D3 ★ | 漂移后 `parseDataRow` 失败 → `PARTIAL_SUCCESS` | 全绿（1/1、`CAT-3001`、合法行 +4.000 已落） |
| **D4 ★** | **漂移后写服务内部失败**（`SALES_DAILY` 上架日） | **2 FAIL → R79（销售侧同样中招）** |
| E | 销售首导**无** `IMP-4006`（反向面）、覆盖、审计摘要、文件内重复行 | 全绿 + 1 NOTE |
| F ★ | 主数据四类未被数据类分支带坏（SKU 导入回归） | 全绿（skus +1、模板仍 6 类） |
| G ★ | `WARNING` 行不写 `raw_data`、租户边界、幂等键唯一 | 全绿 |

## 三、两条缺陷

### R79（P1）Pass 2 行级隔离对数据类失效

```
ImportConfirmService 外层 TransactionTemplate（REQUIRED）
  └─ InventoryWriteService.applyInitialImport / SalesWriteService.batchUpsert（@Transactional(REQUIRED)）
       └─ 抛 BizException（INV-5003 / SAL-6003）
            ⇒ Spring globalRollbackOnParticipationFailure=true 把【共享事务】标 rollback-only
            ⇒ TransactionTemplate.commit() 抛 UnexpectedRollbackException
            ⇒ isRowLevelFailure=false ⇒ C2 recordWholeBatchFailure
            ⇒ 整批回滚 + 每行记 COM-0002（500「系统内部错误」）
```

- 开发申报「`PARTIAL_SUCCESS` 可用」**只在失败发生在 `parseDataRow` 阶段时成立**（`D3` 证明），
  一旦失败来自写服务内部即整批炸（`D4`：`SALES_DAILY` → `FAILED`、`COM-0002`）。
- 对照组：主数据 Pass 2 走 `CatalogWriteService#upsertSkuByCode`（`@Transactional(NESTED)`，`:826`）⇒ 不受影响。
- 真栈日志证据：`evidence-rb105-app.log:62-65`（`UnexpectedRollbackException ... writeDataRowBatch(ImportConfirmService.java:522)`）。

### R80（P1）`INIT_IMPORT` 幂等键不含仓库

- 键字面 `INIT_IMPORT:IMPORT:<batchId>:<skuId>`（按 `DATABASE_DESIGN §5.10` 要点 4），
  而 `replayIfIdempotent` 的 `sameContent` **比 `warehouse_id`** ⇒ 同批同 SKU 跨仓第二行必抛 `INV-5003`。
- 模板 `INVENTORY_SNAPSHOT` **必填 `warehouse_code`** ⇒ 多仓期初导入是常规场景，不是边角。
- `B2`：同一 SKU 单独导入副仓完全成功（余额 9.000）⇒ 因果唯一。

## 四、复核过程中的自我纠错（如实记录）

1. 首版 `D1/D2/D3` 断言假设「未知编码能过校验、在 confirm 阶段才失败」——**错**：校验阶段就拦，
   全行失败时 `validate` 直接 422 `FAILED`（`IMP-4010`）。已改为「校验阶段拦截 + 对 `FAILED` 批次
   confirm 走重放分支」。
2. 首版 `E3` 假设「文件内同业务键两行 → 后者覆盖」——**错**：校验阶段直接记 `SAL-6004` ERROR，
   confirm 422 `IMP-4009`。该行为比假定更安全，已按实际改写。
3. `D2` 的 `422` 断言同样基于错误假设，已改为 `200 + FAILED`（重放分支）。

## 五、复现

```bash
cd backend-java && ./mvnw -B -DskipTests package      # 产出 fat jar
bash backend-java/tools/rb-b105/run-rb105-evidence.sh # 端口可用 RB105_MYSQL_PORT/RB105_APP_PORT 覆盖
```

产物：`evidence-rb105.txt`（入库）、`evidence-rb105-app.log` / `evidence-rb105-headers.log`
（`*.log` 已 gitignore）、`tmp/`（夹具 CSV 与上传落盘，已 gitignore）。
