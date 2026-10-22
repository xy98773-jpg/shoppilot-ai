# B10（M3-T18）逐页联调取证 —— 组次说明与结论

本目录是 `M3-B10`（前端导入向导 + 库存/销售页）的**联调证据归档**。
脚本 `run-b10-evidence.sh` 每次运行都会重建数据并重新取一遍证据，
`evidence-b10.txt` 是最近一次运行的**完整原始输出**（PASS/FAIL 逐条，未做任何删改）。

---

## 1. 环境（与 dev 栈隔离，不干扰已运行的 `shoppilot-dev-*`）

| 组件 | 端口 | 说明 |
| --- | --- | --- |
| MySQL（容器 `shoppilot-b10-mysql-1`） | `3336 → 3306` | 同一份 `deploy/docker-compose.dev.yml`，独立 compose 项目名 |
| backend-java（`shoppilot-b10-backend-java-1`） | `8082 → 8080` | 健康检查 `/api/v1/health` = 200 |
| frontend dev（Vite） | `5173` | 代理指向 `8082` |

启动命令（两条，均需在仓库根目录）：

```bash
# 1) 后端 + MySQL（隔离栈）
MYSQL_PORT=3336 BACKEND_PORT=8082 \
  docker compose -p shoppilot-b10 -f deploy/docker-compose.dev.yml up -d mysql backend-java

# 2) 前端 dev（代理指向隔离后端）
cd frontend-web && VITE_PROXY_TARGET=http://localhost:8082 npm run dev
```

跑证据：

```bash
bash frontend-web/tools/b10/run-b10-evidence.sh
```

脚本退出码：`0` = 全部 PASS；非 `0` = 有 FAIL 或有前置探活失败（`2`/`3`/`4`/`5`，含义见脚本内 `exit` 处）。

---

## 2. 数据前置

- 种子：`backend-java/tools/evi/seed-b95.sql`（团队 7 = 星海优选，仓库 `9004`，
  SKU `9002`/`9003`/`9005` 各 `10.000`，商品 `9001`=`P-BAG-001`）。
- 脚本按 **FK 安全顺序**先清 `import_row_errors` → `import_batches` → `sales_daily`：
  这两类表不在种子内，残留数据会①撞文件哈希 `IMP-4004` ②让团队删除被 `RESTRICT` 挡。
- MySQL 用 **业务账号** `shoppilot`（compose 的 root 口令由环境变量决定，不可假定）；
  客户端必须 `--default-character-set=utf8mb4`（否则中文 SKU 名乱码 → 断言假失败）。
- 登录口令统一 `1234`（`owner01` / `operator01` / `viewer01`）。

---

## 3. 断言分组（62 项，对应任务书【5】第 4 条「逐页联调」）

| 组 | 页面 | 覆盖内容 |
| --- | --- | --- |
| **P1** | `/importdata/wizard` | SPA 路由 200；`GET /import/templates`（6 类模板，含 `SKU`）；**上传 → 预览 → 映射 → 校验** 全链路真实打后端（`201 / 200 / 200 / 200`，2 行解析、0 错误行） |
| **P2** | `/importdata/batches` | 路由 200；列表（分页字段是 **`list`**、含本批次 `batch_id`）；详情；错误分页（`errorLevel` 过滤）；错误导出（文件流 200 + **UTF-8 BOM `efbbbf`**） |
| **P3** | `/inventory/overview` | 路由 200；余额列表 200 且 **四列齐全**（`on_hand_qty`/`in_transit_qty`/`committed_qty`/`available_qty` 各一）；预警 200 |
| **P4** | `/inventory/transactions` | 路由 200；流水列表 200 |
| **P5** | `/inventory/count-adjust` | 路由 200（写路径在 W2 验证） |
| **P6** | `/sales` | 路由 200；经 `POST /sales/daily` 播种 2 行（均 `201`）；列表 200 且 `total=2`；`dateFrom/dateTo` 日期区间筛选命中 1 行；`qty` 原样字符串 `"12.000"` |
| **W1** | 导入确认（写） | 首跑 `200` **无**重放头、批次 `COMPLETED`、`skus` **+2**、审计 **2 条**；同键重试 `200` **带** `Idempotent-Replayed: true`、`skus` **不新增**、审计**不重复**、`success_rows=2 / failed_rows=0` |
| **W2** | 盘点调整（写） | `SKU-B 10.000 → 6.000` 首跑 `201`、余额 `6.000`、流水 +1；同键重试 `201`、**同一 `transaction_id`**、带重放头、余额与流水量均不再变 |
| **W3** | 冲销（写） | 对 `seed-init-9005` 冲销首跑 `200`、`REVERSAL` +1；同键重试 `200`、**同一 `transaction_id`**、带重放头、`REVERSAL` 仍 1 条 |

所有写操作都在**同一用户意图**下一次生成 UUID 幂等键，重试**复用同一个键**——
这正是 `src/utils/idempotency.ts` 的 `createIdempotencyIntent()` 的语义。

---

## 4. ★ 口径说明：为什么导入用 `SKU` 而不是 `SALES_DAILY`

`backend-java/.../ImportConfirmService.java` 第 139 行（既有事实，非本批引入）：

```java
private static final Set<String> SUPPORTED_BIZ_TYPES =
        Set.of(ImportBizType.PRODUCT.name(), ImportBizType.SKU.name(),
                ImportBizType.SUPPLIER.name(), ImportBizType.WAREHOUSE.name());
```

`INVENTORY_SNAPSHOT` / `SALES_DAILY` 走 `POST /{id}/confirm` **必返 `400 COM-0001`**
（`details[field=bizType]`，message「该业务类型落库尚未支持（依赖表未建）」），
且批次**保持 `VALIDATED`**。本批是**纯前端**（任务书禁改 `backend-java/`），因此：

- **导入向导**用 `SKU` 主数据批次（`SKU-B10-1` / `SKU-B10-2` 挂到已存在的 `P-BAG-001`），
  全链路真实成功，`skus` 表有可断言的 +2 增量；
- **`/sales` 页**数据用后端已验证通路 `POST /api/v1/sales/daily`（B8 的手工写入）播种，
  读取仍走 `GET /api/v1/sales/daily`。

**没有**任何前端造假数据、mock 或本地兜底。该后端限制已在交付汇报「偏差申报」中单列。

---

## 5. 复现注意（踩过的坑，逐条都真踩过）

1. **`curl` 必须 `--noproxy '*'`**：本机全局 `http.proxy=127.0.0.1:7890`（Clash）会劫持 localhost，
   不加以外一切请求可能 502/超时。
2. **`-F file=@` 必须用 Windows 路径**：curl 是原生 Windows 程序，读不懂 Git Bash 的 `/d/...`，
   会直接 `exit 26` → HTTP `000`。脚本用 `pwd -W` 转换。
3. **`upload` 的 `Content-Type` 不能手写**：`FormData` 必须让浏览器/axios 自己带 boundary；
   `src/api/importdata.ts` 里显式传 `Content-Type: undefined` 就为这个。
4. **列表分页字段是 `list` 不是 `items`**，批次列表项的 ID 字段是 **`batch_id` 不是 `id`**
   （`src/types/importdata.ts` 的 `ImportBatchListItem` 已对齐）。
5. **销售查询参数是驼峰** `dateFrom` / `dateTo`（不是 `date_from`），
   而 `/inventory/*` 与 `/sales/daily` 的筛选键（`sku_id`/`warehouse_id`）是 snake_case——两套并存，别统一。
6. **`sales_daily.sale_date` 不能是未来日期**（`SAL-6003`，服务层校验），脚本用 `2026-10-15/16`。

---

## 6. 本目录文件

| 文件 | 说明 |
| --- | --- |
| `run-b10-evidence.sh` | 取证脚本（可重复运行，自带数据清理与种子） |
| `evidence-b10.txt` | 最近一次运行的**完整原始输出**（入库） |
| `sku-master.csv` | W1/P1 用的导入文件（2 个新 SKU，便于复现 +2 增量） |
| `evidence-b10-headers.log` | 最近一次响应头（`*.log` 已 gitignore，不入库） |
