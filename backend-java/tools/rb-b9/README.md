# `tools/rb-b9` —— `B9`（M3-T17 审计埋点）项目负责人独立复核

**用途**：不采信开发自报，用**自建 fat jar + 独立 MySQL 8.0.36** 复现 `B9` 的四条审计埋点，
并补测开发未覆盖的「失败面 / 一致性 / 越权与禁令」三类风险。

**独立性**：端口 `3332`（MySQL）与 `18112`（app），刻意错开历史批次已用过的
`3319/3327/3328/3329/3330/3331` 与 `18099/18107/18108/18109/18110/18111`。
种子复用 `tools/evi/seed-b9.sql`（与集成测试 `resetAndSeed` 的 ID / 数值对齐，便于互证）。

## 运行

```bash
# 前置：先在 backend-java/ 下构建出 fat jar
cd backend-java && MAVEN_OPTS=-Xmx512m ./mvnw -B verify \
  -DargLine="-Xmx1536m -XX:MaxMetaspaceSize=640m" -Dsurefire.reuseForks=false
# 再跑本脚本（任意目录）
bash backend-java/tools/rb-b9/run-rb9-evidence.sh
```

产物：

| 文件 | 说明 | 是否入库 |
| --- | --- | --- |
| `evidence-rb9.txt` | 断言日志（`PASS` / `FAIL` / `FINDING` / 汇总） | ✅ |
| `evidence-rb9-app.log` | 应用 stdout（`*.log` 被 gitignore，不入库） | ❌ |

退出码 = `FAIL` 数（`FINDING` 不计入；`FINDING` 是「产品行为与 SSOT 有差距」的实证，不是脚本错误）。

## 组次

| 组 | 内容 | 结果 |
| --- | --- | --- |
| `R1–R4` | 复现基线：`#12`/`#11`/`#13`/`#7` 的 `biz_type`、`biz_id`、摘要前后值；零增量失败不写审计 | 全 PASS |
| `P2` | 策略侧失败/越权不写审计：跨团队 `CAT-3004`(422) / `disable` 不存在 `CAT-3001`(404) / `VIEWER` `TEAM-2003`(403) | 全 PASS |
| `P3` | 账本—审计对账：`#11+#12` == 调整流水数；`#13` == `REVERSAL` 流水数 | PASS |
| `P4`/`P4b` | 策略 `before_json`/`after_json` 只含变化字段；无字段变化的 upsert 仍写 1 条并标注「（无字段变化）」 | PASS |
| `P5` | 禁令 9 静态核验：`applyInitialImport`/`applyInbound`/`applyOutbound` 无审计调用；埋点调用点数 | PASS |
| `P6`/`P6b` | 审计 `occurred_at` 与流水同源（差 0s）；`request_uri`/`request_id`/`ip`/`http_method` 采集 | PASS |
| `P7` | 横向越权：团队 8 的 `OWNER` 调团队 7 资源 → 4xx，四条动作在团队 8 名下 0 行 | PASS |
| `P8` | 冲销同 `Idempotency-Key` 重放 | **FINDING → `R73`** |
| `P1` | 审计写失败（触发器阻断）→ 业务仍成功、审计缺失、无补偿 | **FINDING → `R72`** |

## 探针自身的坑（踩过，别再踩）

1. **`grep -c "@Test"` 会把 `@Testcontainers` 算进用例数**（每文件 +1）→ 用 `@DisplayName` 或 `@Test$`。
2. **MySQL `JSON_EXTRACT` 返回的是 JSON `null`，不是 SQL `NULL`**：`JSON_EXTRACT(...) IS NULL` 恒为 0（假 FAIL）→ 用 `JSON_TYPE(...) = 'NULL'`。
3. **JSON 数值会归一化**：`8.000` 存进 `JSON` 列后取出是 `8` → 断言按归一化值写。
4. **「某团队零审计」不能只按 `team_id` 计数**：登录本身写 `AUTH_LOGIN_SUCCESS`（团队 8 有 1 条合法行）→ 必须按 `action` 过滤。
5. **统计调用点数时 `grep -c 'recordPolicyAudit('` 会把定义行算进去** → `grep -vc 'private void'`。
6. 应用日志必须写**独立文件**：与 `tee` 共用同一文件会互相覆盖（B8 脚本首版因此丢整段）。
7. `mysql` 客户端必须 `--default-character-set=utf8mb4`，否则中文断言字面量退化成 `?`（假 FAIL）。

## 结论

`RB9_CHECK_TOTAL=57`、`RB9_FAIL_COUNT=0`、`RB9_FINDING_COUNT=2`。
`B9` 的埋点本身（四条动作、`biz_type`/`biz_id` 口径、只写成功、失败与越权不写、`change_summary` 前后值、
团队归属、HTTP 上下文）**全部与自报一致**；两条 `FINDING` 分别是
「高风险操作审计未与业务同事务（`R72`）」与「冲销同 key 重放返回 409（`R73`）」，
均属 M2/B6 遗留、由 `B9.5` 回修，不影响本批收口。
