# rb-b14 —— B14 / B14.1 真栈取证（V1.0.9 analysis 迁移）

## 一、这个脚本证明什么

`M4-T02` 交付的是 `V1.0.9__analysis.sql`（`analysis_runs` / `analysis_items` 两表）。
单测 `AnalysisSchemaConstraintTest` 用 Testcontainers 已断言约束/索引/外键/CHECK 生效，
但**迁移在真实部署路径上的最终形态**需要一份独立取证：

- 容器化的 `mysql:8.0.36`（端口 `3380`），不是随机端口的临时库；
- 由 **backend-java fat jar 启动时的 Flyway** 执行迁移，不是脚本里另写一遍 DDL；
- 从库里导出 `SHOW CREATE TABLE` 原文，并对约束/索引/审计字段跑 29 条断言。

> 只有「应用真实启动路径上的 Flyway」才能证明 `V1.0.9` 真的被应用。
> 手工 `mysql < V1.0.9.sql` 只能证明 SQL 语法对，证明不了迁移被装配。

## 二、怎么跑

```bash
# 前置：先让 backend-java 全量 verify 通过（会产出 fat jar）
cd backend-java && MAVEN_OPTS=-Xmx512m ./mvnw -B clean verify \
  -DargLine="-Xmx1536m -XX:MaxMetaspaceSize=640m" -Dsurefire.reuseForks=false

# 再跑取证（端口 3380 / 18320，跑前 netstat 复核空闲）
bash backend-java/tools/rb-b14/run-rb-b14.sh
```

端口可用环境变量覆盖：`MYSQL_PORT=xxxx APP_PORT=yyyy bash …/run-rb-b14.sh`。

`trap cleanup EXIT` 保证 MySQL 容器与 java 进程必被回收；跑完 `netstat` 应无残留。

## 三、脚本做了什么

| 步 | 内容 |
|---|---|
| 1 | 检查 fat jar 存在（不存在即退出码 4，并提示先跑 verify） |
| 2 | `netstat` 自检 `3380` / `18320`，占用即退出码 6 |
| 3 | 起 `rb-b14-mysql`（`mysql:8.0.36 --default-time-zone=+00:00`），等 `mysqladmin ping` |
| 4 | `java -jar shoppilot-app-0.1.0.jar --server.port=18320`，等 `/api/v1/health` 返 200 |
| 5 | 导出 `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank` |
| 6 | 导出 `SHOW CREATE TABLE analysis_runs` / `analysis_items` 原文 |
| 7 | 29 条结构断言（见下） |
| 8 | 输出 `RB14_CHECK_TOTAL` / `RB14_FAIL_COUNT` / `RB14_RESULT` |

## 四、断言清单（29 条）

**迁移链（3）**：`V1.0.9 success=1`；总数 10；success=1 条数 10。

**索引（6）**：`analysis_runs` 二级索引恰 3 条且名字集合 == `{idx_run_team_created, idx_run_team_status, idx_run_timeout}`；
`analysis_items` 恰 2 条且名字集合 == `{idx_ai_team_run, idx_ai_team_sku_time}`；
未建 `idx_ai_team_run_risk`（R102）；
`idx_run_timeout` 最左列 == `status`（R103 + `DATABASE_CONSTRAINTS §3.3` 例外）；
`idx_run_team_status` / `idx_ai_team_run` 最左列 == `team_id`。

**唯一键（3）**：`uk_ai_run_sku_wh` 列序 == `run_id,sku_id,warehouse_id`（R89：不带 `team_id`）；
`analysis_runs` 唯一约束 2 条；`analysis_items` 1 条。

**CHECK / 外键（5）**：`analysis_runs` CHECK 5 条；`analysis_items` CHECK 6 条；
`analysis_items` 外键 3 条且全部 `RESTRICT`；`analysis_runs` 外键 0 条（§5.14 明文无外键）。

**审计字段与 team_id（6）**：两表 `team_id` 默认值均为空（NOT NULL 无 DEFAULT，
`M2_TenantIsolationDbIntegrationTest` 会遍历全表断言这一点）；
`analysis_runs` 无 `version`；`analysis_items` 无 `updated_at` / `updated_by` / `version`。

**关键列类型（6）**：`cv` = `decimal(18,4)`；`progress` = `tinyint unsigned`；
`llm_explanation` = `text`；`params_json` = `json`；`timeout_at` = `datetime(3)`；
`idempotency_key` = `varchar(128)`。

## 五、实测结果

复现命令（端口 3380 / 18320，跑前 `netstat` 复核空闲）：

```bash
cd backend-java && MAVEN_OPTS=-Xmx512m ./mvnw -B clean verify \
  -DargLine="-Xmx1536m -XX:MaxMetaspaceSize=640m" -Dsurefire.reuseForks=false
bash backend-java/tools/rb-b14/run-rb-b14.sh
```

实测：

```text
JAR_SHA256=2034937cc87c68126f35cbbd9b4ba49d78a9c8f679655b3eb6c1f4f01947d77d
MYSQL_READY=1 (rb-b14-mysql, 3380)   JAVA_UP=1 (18320, 4s)
RB14_CHECK_TOTAL=33   RB14_FAIL_COUNT=0   RB14_RESULT=ALL PASS
```

迁移链（`SELECT version, success FROM flyway_schema_history ORDER BY installed_rank`）：

```text
version	success
1.0.0	1       1.0.5	1
1.0.1	1       1.0.6	1
1.0.2	1       1.0.7	1
1.0.3	1       1.0.8	1
1.0.4	1       1.0.9	1     ← M4-T02 新增，success=1
```

两条 `SHOW CREATE TABLE` 原文见 `evidence-rb-b14.txt`（不在此处转抄，避免二手失真）。

### 首轮跑出 1 FAIL 的经过（如实记录）

首轮 `RB14_CHECK_TOTAL=33 / RB14_FAIL_COUNT=1`，失败项是 `V109-Success`：

```text
[FAIL] V109-Success => expected [1.0.9	1] but was [1.0.9\t1]
```

根因是**取值写法**：断言用了 `CONCAT(version,'\t',success)`，而 `\t` 未被解释成制表符，
拿到的是字面量 `\t`，与实际导出值差一个字符。同一份证据文件里的迁移链表**明确显示**
`1.0.9  1`，可见数据本身没问题。改用 `SELECT COUNT(*) … WHERE version='1.0.9' AND success=1`
（期望 `1`）后重跑即 `33 / 0 / ALL PASS`。未改任何 main / 迁移 / 断言口径。

## 六、产物

| 文件 | 说明 |
|---|---|
| `evidence-rb-b14.txt` | 取证原文（迁移链 + 两条 `SHOW CREATE TABLE` + 每条断言 [PASS]/[FAIL]） |
| `app.log` | 应用启动日志（含 Flyway 「Migrating schema … to version 1.0.9」） |
| `storage/` | `IMPORT_STORAGE_DIR`（本批不用，仅为让应用能起） |

`app.log` 与 `storage/` 不入库（`.gitignore` 覆盖 `*.log`；`storage/` 内容为空）。
