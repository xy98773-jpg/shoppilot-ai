# B8（M3-T12 / T13 · sales 销售数据）真栈取证

本目录是 B8 批次**开发自证**用的真栈取证脚本，随代码入库（`R47`）。
与 B7 的 `tools/rb-b7/`（项目负责人独立复核用）区分：两者端口刻意错开，互不干扰。

## 目录

| 文件 | 作用 |
| --- | --- |
| `run-b8-evidence.sh` | 采集脚本：起独立 MySQL 8.0.36 + 自建 fat jar，跑 S1–S5 与 P1–P8 |
| `evidence-b8.txt` | 运行产物（含 jar SHA256、逐条 PASS/FAIL、SQL 取证原文） |
| `evidence-b8-app.log` | 应用 stdout（`*.log` 已 gitignore，不入库） |
| `../evi/seed-b8.sql` | 种子：团队 7/8 的账号、主数据与 4 行日销售 |

> ★ 应用日志必须写**独立文件**。第一版脚本让 `nohup java` 与 `tee -a` 共用同一文件，
> 结果 app 的 stdout 把 S1 整段覆盖掉，`FAIL_COUNT=5` 但日志里只剩 3 条 FAIL——取证日志不可复用同一 fd 目标。

## 端口（避开历史批次，勿复用他人端口）

| 用途 | 本脚本 | 历史占用 |
| --- | --- | --- |
| MySQL | **3328** | 3319（真栈复核）、3327（B7 复核） |
| App | **18108** | 18099（真栈复核）、18107（B7 复核） |

## 运行

```bash
# 前置：先产出 fat jar（verify 已包含 repackage）
cd backend-java && ./mvnw -B verify

# 取证
bash backend-java/tools/b8/run-b8-evidence.sh
echo "exit=$?"     # 0 = 全 PASS；6 = 有 FAIL
```

脚本自带 `trap cleanup EXIT`，结束会 `docker rm -f b8-mysql` 并杀掉本端口的 java 进程
（Windows 上遗留 java 会持有 `app/target/*.jar`，导致后续 `repackage` 报 *Unable to rename*）。

> ★ 所有 `mysql` 客户端调用必须带 `--default-character-set=utf8mb4`。默认 client charset 不是
> utf8mb4 时，中文断言的字面量会被转成 `?` 发出——`LIKE '%覆盖%'` 恒不匹配，
> 摘要打印成 `upsert ?????? 1 ? / ? 1`，产生**假 FAIL**（第一版脚本即因此误报 P5）。

## 组次与断言（共 86 项检查：S1 19 / S2 8 / S3 11 / S4 11 / S5 8 / P 16 / W 13）

> ★ **执行顺序**：`S1 → S4 → S2 → S3 → S5 → P → W`。S4（聚合）刻意排在 S2/S3 之前，
> 这样聚合期望值才等价于「纯种子态」（30/5/30、35/30、45/20）。若先做 S2 的 upsert，
> 9002 会变成 37、9004 会变成 42（第一版脚本就踩了这个坑，报了两条假 FAIL）。
> S4e 用 `2026-09-xx` 写升序探针：既避开 S3 的「`2026-10-04` 零落库」断言，
> 也避开 B8-hotfix（R68）新增的「未来日期拦截」——`2026-11-xx` 会被判 `SAL-6003`。

### S1 迁移链与表结构（19 项）
- `flyway_schema_history` 中 `success=1` 的迁移 **9 条**，最高版本 **1.0.8**（`V1.0.8__sales.sql`）。
- `sales_daily` 存在；唯一键 **1 条** `uk_sales_team_wh_sku_date`，列序 `team_id,warehouse_id,sku_id,sale_date`。
- **声明**的二级索引 **4 条**且集合精确等于 `idx_sales_team_sku_date / idx_sales_team_date /
  idx_sales_team_product_date / idx_sales_team_batch`，**四条均 `team_id` 最左**。
- 另有 **2 条** MySQL **自动为外键创建**的支撑索引（`fk_sales_sku` / `fk_sales_warehouse`）：
  因为 4 条 `idx_sales_team_*` 的最左列都是 `team_id`，无法单独服务 `sku_id` / `warehouse_id` 的外键。
  **不算冗余、不做清理**——这是 MySQL 的必需行为。
- 外键 **2 条**，均 `ON DELETE RESTRICT ON UPDATE RESTRICT`，引用表集合 = `{skus, warehouses}`
  （全仓唯一 `CASCADE` 仍是 `fk_rt_user`，本批未新增）。
- `CHECK` **4 条**（`ck_sales_source` / `ck_sales_qty_non_negative` / `ck_sales_return_non_negative` /
  `ck_sales_amount_non_negative`）；**无 `version` 列**（`DATABASE_DESIGN §7.4` 表 B）。
- 审计字段集合 A = `created_at / created_by / updated_at / updated_by`。
- 精度：`qty` = `decimal(18,3)`、`sales_amount` = `decimal(18,2)`、`sale_date` = `date`。

> ★ 统计索引条数一律 `COUNT(DISTINCT index_name)`——`information_schema.statistics`
> 按列展开，`COUNT(*)` 会把复合索引算成多行（B5 复核曾因此误判 24 条索引）。
> 且**二级索引必须按 `idx_` 前缀过滤**：直接数 `non_unique=1` 会得 6（4 声明 + 2 外键自动），
> 属误判。`LIKE 'idx\_%'` 里的反斜杠是必要的转义——`_` 是 LIKE 的单字符通配符。

### S2 upsert 覆盖语义（8 项，`§6-D.2 U-6`）
同一业务键 `(wh=9004, sku=9002, date=2026-10-03)` 连写两次（qty 15 → 7）：
`COUNT(*)` 仍为 **1**、**主键不变**、`qty` 变为 **7.000**、不存在 `22.000` 的累加行。

### S3 校验类错误码（11 项）
| 用例 | 期望 code | 期望 HTTP |
| --- | --- | --- |
| 跨团队 SKU（9102） | `SAL-6005` | 422 |
| 跨团队仓库（9103） | `SAL-6005` | 422 |
| `qty=-1` | `SAL-6002` | 400 |
| `sale_date=2026-13-40` | `SAL-6003` | 400 |
| 缺 `sku_id` | `COM-0001` | 400 |

并断言负向用例 **零落库**。

### S4 聚合与升序（11 项）
- `groupBy=sku` → `9002=30`、`9003=5`、`9005=30`
- `groupBy=warehouse` → `9004=35`、`9007=30`
- `groupBy=date` → `2026-10-01=45`、`2026-10-02=20`
- `groupBy=product`（非法维度）→ `COM-0007` / 400

**S4e 日期升序**：乱序写入 `2026-09-06 → 2026-09-04 → 2026-09-05`，确认物理插入序（`ORDER BY id`）**非**升序，
而 `ORDER BY sale_date ASC` 结果为 `2026-09-04,2026-09-05,2026-09-06`。

> ⚠ 口径说明：`getSeries` 按 `MODULE_BOUNDARIES §2.6` 是**内部服务方法**（供 `analysis` 调用），
> 本批**不暴露 HTTP 端点**。故脚本 S4e 取证的是它发出的 `ORDER BY sale_date ASC` 语义；
> Java 层完整断言（含逆序入参 → `COM-0007`、`skuId` 空 → `COM-0001`）在
> `app/src/test/java/com/shoppilot/sales/SalesQueryServiceTest.java`，该测试**同为真 MySQL
> （Testcontainers）+ 真 Spring Bean**，不是 mock。

### S5 角色与控制（8 项）
`VIEWER` POST → `TEAM-2003` / 403；`OPERATOR` POST → 201 且审计 **+1**；`ADMIN` POST → 201；
`VIEWER` GET → 200（读端点对任何已登录成员开放）；无令牌 GET → 401。

### P 附加探针（P1–P8 共 8 组、16 项断言；开发主动补测的 SSOT 硬要求）
| # | 探针 | 依据 |
| --- | --- | --- |
| P1 | upsert 可把 `sales_amount` 覆盖为 **NULL** | 覆盖语义完整性（MyBatis-Plus `NOT_NULL` 策略陷阱） |
| P2 | `pageSize=201` → `COM-0007`，**不静默夹取** | `API_CONTRACT §2` + 任务书 |
| P3 | 分页字段名是 `list`，**无 `items`** | `PageResult` 契约 |
| P4 | `product_id` 冗余列 == `sku.product_id`（全表 0 不一致） | `DATA_DICTIONARY §4.1` |
| P5 | 审计 `change_summary` 精确记被覆盖行数（受控两连写：新键 `覆盖 0 行` → 同键再写 `覆盖 1 行`，且全表摘要格式统一） | `§6-J.1 #17` / `IMPORT_WORKFLOW:265` |
| P6 | `team_id` 由租户线自动补 **7**（调用方不传） | `TenantTables` 红线的反向验证 |
| P7 | GET 零副作用（行数不变） | 只读事务 |
| P8 | 团队 8 的 `owner8` 读到 **0** 行 | 团队隔离 |

### W 组：`sale_date` 范围校验（B8-hotfix / R68，13 项）
依据 `ERROR_CODES §2.7`：`SAL-6003` = 「`sale_date` **晚于今天** **或** **早于上架日**」。

| # | 探针 | 期望 |
| --- | --- | --- |
| W1 | `sale_date = 今天+1` | `SAL-6003` / 400 / **零落库** |
| W2 | `sale_date = 2026-09-30`（`SKU-E` 的 `listed_date = 2026-10-01`） | `SAL-6003` / 400 / **零落库** |
| W3 | `sale_date = 今天`（≥ `listed_date`） | **201** 且值正确（**不得误杀**） |
| W3b | 边界日 `listed_date` 当天（`2026-10-01`） | **201**（证明比较符是 `<` 而非 `<=`） |

`SKU-E`（`id=9008`）在 `seed-b8.sql` 里显式设 `listed_date = 2026-10-01`，其余 SKU 保持 `NULL`
（`NULL` 表示未设置上架日 ⇒ 只校验「晚于今天」这一半）。该 SKU 不参与任何既有种子断言，
且 W 组之前无 `sales_daily` 行，所以「零落库」断言不受其他组干扰。

> ★ `W3` 有前置断言 `今天 > 2026-09-30`。若在 `2026-10-01` 之前重跑本脚本，
> `listed_date` 会落在未来，`W3` 会按预期失败——**届时须先调 `seed-b8.sql` 的 `listed_date`**。

## 结论口径

- `B8_FAIL_COUNT=0` + `exit=0` 才算通过；任一 FAIL 都会写进 `evidence-b8.txt` 并在退出码 6 体现。
- 报告里引用取证结论时，请连 `evidence-b8.txt` 的**行号或原文**一起给，避免「只报结论不报证据」。
