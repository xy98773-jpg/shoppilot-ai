# B10.5（R78）真栈取证 —— 导入确认补挂 INVENTORY_SNAPSHOT / SALES_DAILY

本目录是 **M3-B10.5 回修单（R78）** 的真栈证据归档。
`run-b105-evidence.sh` 每次运行都会重建数据集（独立 MySQL 容器）并重新取一遍证据，
`evidence-b105.txt` 是最近一次运行的**完整原始输出**（PASS/FAIL 逐条，未做任何删改）。

---

## 1. 缺陷与修复

**缺陷（项目负责人真栈实测）**：`ImportConfirmService.SUPPORTED_BIZ_TYPES` 只含
`PRODUCT/SKU/SUPPLIER/WAREHOUSE` 四类，`INVENTORY_SNAPSHOT`/`SALES_DAILY` 走完
上传→预览→映射→校验后在 confirm 必返 `400 COM-0001`（`details[field=bizType]`），
批次保持 `VALIDATED`（未误推进，这部分原本是对的）。

**影响**：销售数据唯一入口失效（销售页只读、`POST /sales/daily` 不在任何前端页面）
⇒ `sales_daily` 恒空 ⇒ M4 补货 `Q_base` 的日均销量 `D` 无数据源；期初库存无法导入
（无 `INIT_IMPORT` 流水来源）。

**修复**：
- `SUPPORTED_BIZ_TYPES` 扩到六类（`default ->` 未知类型防御保留，仍显式失败、不静默跳过）；
- `catalog` 新增 `SkuQueryService#getIdsByCodes` / `WarehouseQueryService#getIdsByCodes`
  （批量 `IN`，`MODULE_BOUNDARIES §2.4`：`importdata` 不注入 catalog 的 Mapper）；
- `ImportConfirmService` 新增数据类落库路径（与主数据同构的两遍式 Pass 1/Pass 2）：
  `INVENTORY_SNAPSHOT → InventoryWriteService#applyInitialImport`、
  `SALES_DAILY → SalesWriteService#batchUpsert`（`source=IMPORT`）；
- `ImportValidationService` 实现 `IMP-4006`（`WARNING`）：库内命中即提示，
  存在性查询经 `InventoryQueryService#findExistingBalanceKeys` /
  `SalesQueryService#findExistingDayKeys` 两个只读批量接口。

---

## 2. 环境（与 dev 栈完全隔离）

| 组件 | 端口 | 说明 |
| --- | --- | --- |
| MySQL（容器 `b105-mysql`） | `3338 → 3306` | `mysql:8.0.36`，`--default-time-zone=+00:00` |
| app（fat jar） | `18118` | `app/target/shoppilot-app-0.1.0.jar` |

> 端口刻意错开回修单【5】2 点名的 `3319`、`3327–3337`、`18099`、`18107–18117`
> （`3338` 紧接 3337 之后，`18118` 紧接 18117 之后）。可用 `B105_MYSQL_PORT` /
> `B105_APP_PORT` 覆盖。

建 jar 与取证：

```bash
cd backend-java && ./mvnw -B -DskipTests package
bash backend-java/tools/b10.5/run-b105-evidence.sh
```

脚本自带 `trap cleanup EXIT`：退出即删容器并 kill 应用进程，不留残留。
退出码 `0` = 全部 PASS；`2/3/4/5/6` = jar 缺失 / MySQL 未就绪 / 应用未起 / 种子失败 / 登录失败。

**种子**：`backend-java/tools/evi/seed-b95.sql`（团队 7 = 星海优选，仓库 `9004`，
SKU-A=`9002` / SKU-B=`9003` / SKU-C=`9005` 各 `10.000`），口令 `S3cret!Passw0rd`。

---

## 3. 断言分组（68 项）

| 组 | 业务类型 | 覆盖内容 |
| --- | --- | --- |
| **G1** | `INVENTORY_SNAPSHOT` | 上传 `201` → 预览 `PARSED` 2 行 → 映射 `MAPPED` → 校验 `VALIDATED`（`error_rows=0`、`warning_rows=2`，两行都命中已有余额）→ 确认 `200 COMPLETED`；**余额增量** `10.000 + 120.000 = 130.000` / `10.000 + 30.000 = 40.000`；**流水** `INIT_IMPORT` × 2、`biz_type=IMPORT`、`biz_ref_id=batchId`、幂等键字面 `INIT_IMPORT:IMPORT:<batch>:<sku>`、`qty_before=10.000 / qty_after=130.000`；**二次 confirm** `200 + Idempotent-Replayed: true`，余额与流水均不增 |
| **G2** | `SALES_DAILY` | 首次导入全绿 → `sales_daily` 2 行，`source=IMPORT`、`batch_id` 回指、`product_id` 由 `skus` 派生、`qty` 原样 `12.000`、`SALES_DAILY_UPSERT` 审计 1 条；**重复导入同一业务键**（改 qty 换文件哈希）→ 校验给出 2 条 `IMP-4006`（`error_level=WARNING`、文案「该日期已存在销售数据，确认导入后将覆盖」）→ 确认后**仍 2 行**（覆盖不累加）、`qty` 变 `20.500`、审计第 2 条 `change_summary` 记「覆盖 2 行 / 共 2 行」 |
| **G3** | `SALES_DAILY`（引用漂移） | 校验通过后 `UPDATE skus SET sku_code='SKU-B-DRIFTED'` → 确认 `200 PARTIAL_SUCCESS`（`success=1 / failed=1`，不变式成立）、`import_row_errors` 第 2 行 `CAT-3001`、只落成功行（`sales_daily` 3 行） |

`IMP-4006` 的可见性在 G1、G2 各验一次：既在 `POST /{id}/validate` 响应的
`data.errors[]` 里（`error_level=WARNING`），也在 `GET /{id}/errors?errorLevel=WARNING`
的列表与 `total` 里。

---

## 4. 与前端同参数/同头

任务书【5】3 要求「至少用与前端同参数/同头、含 `Idempotency-Key` 的 curl 证明」。
脚本里每个写请求（`confirm`）都带：

```bash
POST /api/v1/import/batches/{id}/confirm
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: b105-g1-confirm / b105-g2-confirm-1 / b105-g2-confirm-2 / b105-g3-drift
-d '{}'
```

与前端 `src/api/importdata.ts#confirmBatch`（`postWithIdempotency`）发出的头**逐项一致**：
`Content-Type: application/json` + `Idempotency-Key`（UUID；脚本用可读字符串），body `{}`。
上传用 `multipart/form-data`（`file` + `bizType`），映射用
`{"field_mapping_json": {...}, "parse_options_json": {}}` —— 同样与前端同形。

---

## 5. 关键 SQL 前后值（脚本内直接断言）

```sql
-- G1 余额增量
SELECT on_hand_qty FROM inventory_balances
 WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002;   -- 10.000 → 130.000

-- G1 INIT_IMPORT 流水与字面幂等键
SELECT txn_type, biz_type, biz_ref_id, idempotency_key, qty_before, qty_after
  FROM inventory_transactions WHERE team_id=7 AND idempotency_key IN
       ('INIT_IMPORT:IMPORT:1:9002','INIT_IMPORT:IMPORT:1:9003');

-- G2 覆盖不累加
SELECT COUNT(*) FROM sales_daily WHERE team_id=7;          -- 2 → 2（两次导入后仍是 2）
SELECT qty FROM sales_daily WHERE team_id=7 AND sku_id=9002 AND sale_date='2026-10-10';
                                                           -- 12.000 → 20.500

-- G2 审计被覆盖行数
SELECT change_summary FROM audit_logs
 WHERE action='SALES_DAILY_UPSERT' AND team_id=7 ORDER BY id DESC LIMIT 1;
-- upsert 日销售：覆盖 2 行 / 共 2 行（source=IMPORT）

-- G3 行级失败
SELECT row_no, error_code, error_level FROM import_row_errors WHERE batch_id=4;
-- 2 | CAT-3001 | ERROR
```

---

## 6. 复现注意（都是真踩过的）

1. **`curl` 必须 `--noproxy '*'`**：本机全局 `http.proxy=127.0.0.1:7890`（Clash）会劫持 localhost。
2. **`-F file=@` 必须用 Windows 路径**：`curl` 是原生 Windows 程序，读不懂 Git Bash 的 `/d/...`
   （会 `exit 26` → HTTP `000`）。脚本用 `pwd -W` 转换。
3. **`mysql` 客户端必须 `--default-character-set=utf8mb4`**：否则中文断言字面量退化成 `?`，产生假 FAIL。
4. **应用 stdout 必须写独立文件**：与 `tee` 共用同一文件会互相覆盖（B8 脚本第一版丢过整段日志）。
5. **重复导入同一文件会被 `IMP-4004` 拦**（文件哈希去重）⇒ 覆盖场景必须改内容（本脚本改 `qty`）。
6. **`sales_daily.sale_date` 不能是未来日期**（`SAL-6003`）⇒ 脚本写死过去日期 `2026-10-10`–`13`。
7. **`source` 只能取 `IMPORT/MANUAL/PLATFORM`**，脚本断言导入后是 `IMPORT`。

---

## 7. 本目录文件

| 文件 | 说明 |
| --- | --- |
| `run-b105-evidence.sh` | 取证脚本（自建容器 + 自建 jar，可重复运行） |
| `evidence-b105.txt` | 最近一次运行的**完整原始输出**（入库，68 项断言） |
| `inventory-snapshot.csv` | G1 用的导入文件（2 行，含 `snapshot_date` / `remark`） |
| `sales-daily-b105.csv` | G2 首次导入（2 行日销售） |
| `sales-daily-b105-overwrite.csv` | G2 覆盖导入（同业务键、改 `qty`） |
| `sales-daily-b105-drift.csv` | G3 引用漂移（2 行，确认前改名 SKU） |
| `evidence-b105-app.log` / `evidence-b105-headers.log` | 应用 stdout / 最近一次响应头（`*.log` 已 gitignore） |
