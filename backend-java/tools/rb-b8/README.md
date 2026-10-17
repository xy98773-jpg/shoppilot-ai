# B8 独立复核证据（项目负责人执行）

依 `DESIGN_REVIEW.md R47`：归因类证据必须**入库、可复现**。本目录是项目负责人对 `B8`
（`M3-T12/T13` · sales）**独立复核**的探针与原始输出，与开发的 `tools/b8/` 相互独立。

## 组成

| 文件 | 说明 |
|---|---|
| `run-rb8-evidence.sh` | 复核脚本：独立 MySQL `:3329` + 自建 fat jar `:18109` + `tools/evi/seed-b8.sql` → curl/SQL 取证 → 收尾清理 |
| `evidence-rb8.txt` | 原始输出（本目录，入库） |
| `evidence-rb8-app.log` | 应用 stdout（`*.log` 已 gitignore，不入库） |

端口刻意错开：B7 复核 `3327/18107`、B8 开发 `3328/18108`、本次 `3329/18109`。

## 复现

```bash
cd backend-java && ./mvnw -B verify      # 先产出 fat jar
cd ../ && bash backend-java/tools/rb-b8/run-rb8-evidence.sh
```

## 覆盖

**复现组 R**（对照开发自报）：迁移链 9 条、`sales_daily` 约束、upsert 覆盖（15→7 行数仍 1）、
三聚合、角色 `VIEWER→TEAM-2003`、审计 `SALES_DAILY_UPSERT` + `change_summary`。

**新增探针 P**（开发未覆盖的 SSOT 硬要求）：

| 探针 | 结果 |
|---|---|
| `P1` `sale_date` 晚于今天 | **FINDING**：`201` 且落库 → `R68(a)` |
| `P2` `sale_date` 早于 `skus.listed_date` | **FINDING**：`201` 且落库 → `R68(b)` |
| `P3` 覆盖更新后 `created_by`/`created_at` 保持、`qty` 更新 | PASS |
| `P4` `qty=0` 允许写入 | PASS |
| `P5` 团队 8 读不到团队 7 的销售行 | PASS |
| `P6` `pageSize=201` → `COM-0007` | PASS |
| `P7` `sales_amount` 可覆盖为 `NULL` | PASS |

## 结论

`R1–R5` 全 PASS；`P3–P7` 全 PASS；`P1`/`P2` 为同一缺陷（`SAL-6003` 范围校验）→ 归档 `R68`，发 `B8-hotfix` 回修。

## ★ 探针自身踩过的两个坑（写下来免得重犯）

1. **统计唯一键必须排除 `PRIMARY`**：`information_schema.statistics` 里主键同样 `non_unique=0`，
   不写 `AND index_name<>'PRIMARY'` 会得 2 → 误报 FAIL。
2. **`group_key` 是字符串**：`groupBy=warehouse` 返回 `"group_key":"9007"`（不是数字），
   用 `"group_key":9007` 正则匹配不到 → 空值 → 误报 FAIL。
   诊断方法：先把原始响应 `note` 出来，再改解析；**不要用解析失败推断产品缺陷**。
