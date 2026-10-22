# B9.5 真栈取证（R72 审计事务耦合 + R73 冲销幂等重放）

本目录是 **M3-B9.5 回修单**的真栈取证套件（`R47`：脚本 + 种子 + 说明必须入库）。
目标不是复述集成测试，而是在**独立 MySQL + 自建 fat jar** 上重新制造两个缺陷场景，
证明回修真的生效、且没有把原有拦截（`INV-5009`、失败不写审计）顺手削弱。

## 文件

| 文件 | 作用 |
| --- | --- |
| `run-b95-evidence.sh` | 采集脚本：起独立 MySQL 8.0.36 + 自建 fat jar，跑 C1–C4 与 R1–R3 |
| `evidence-b95.txt` | 运行产物（含 jar SHA256、逐条 `PASS/FAIL`、SQL 与响应原文） |
| `../evi/seed-b95.sql` | 种子：团队 7/8 的账号与主数据、3 行余额、2 笔可冲销流水 |

`evidence-b95-app.log` / `evidence-b95-headers.log` 是运行中间产物，已被 `.gitignore` 的 `*.log` 覆盖。

## 环境

| 项 | 值 |
| --- | --- |
| MySQL | 独立容器 `b95-mysql`，宿主端口 **3333** |
| 应用 | 自建 fat jar，端口 **18113** |
| 口令 | `1234`（BCrypt 哈希与 `seed-b7/b8/b9` 同源） |

端口刻意避开历史批次用过的 `3319 / 3327 / 3328 / 3329 / 3330 / 3331 / 3332` 与
`18099 / 18107 / 18108 / 18109 / 18110 / 18111 / 18112`。

★ `docker run` 必须带 `--log_bin_trust_function_creators=1`：MySQL 8 默认开 binlog，
容器内的普通账号建触发器会报
`ERROR 1419 (HY000): You do not have the SUPER privilege and binary logging is enabled`。
C1/C4 依赖 `BEFORE INSERT` 触发器制造「审计写不进」，故在**启动参数**上放开（仅取证容器，不影响生产配置）。

## 组次与断言

| 组 | 断言 | 依据 |
| --- | --- | --- |
| C1 | 触发器阻断审计后 `COUNT_ADJUST` → **500 `COM-0002`**，余额仍 `10.000`、流水数不变、`#12` 审计 0 行、全表审计行数不变 | `MODULE_BOUNDARIES §2.10`（高风险操作审计与业务同事务） |
| C2 | `DROP TRIGGER` 后同一动作 → **201**，余额 `7.000`、流水 +1、`#12` +1（反证失败确由触发器导致） | 同上 |
| C3 | 目标 `-1.000` → **409 `INV-5006`**，审计条数不变 | 只写成功 |
| C4 | 触发器阻断下 `#7` 策略 upsert 仍 **200** 且策略落库、`#7` 审计 0 行 | §2.10 高风险清单**不含**策略变更 ⇒ 有意差异 |
| R1 | 同键同体重放 → **200 + 首次 `transaction_id` + `Idempotent-Replayed: true`**，`REVERSAL` 仅 1 行、`#13` 审计仍 1 条；**首次执行不带该头** | `API_CONTRACT §6.3` |
| R2 | 同键不同体（换一笔原流水）→ **409 `INV-5003`**，不新增冲销、余额不变 | 幂等键内容冲突 |
| R3 | 不同键二次冲销 → **409 `INV-5009`**（回归点：重放前移不得削弱拦截） | `INVENTORY_WORKFLOW §5.6` |

★ 数据分工：C1/C2 用 `SKU-A`（10 → 7），R1 用 `SKU-C` 的 `seed-init-9005`（10 → 0），
R2 用 `SKU-A` 的 `seed-init-9002` 配 R1 的键（**只触发内容冲突，不动余额**）。
若 R1 也用 `SKU-A`，`7 - 10 = -3` 会撞 `INV-5006`，取证结论就被另一个错误污染了。

★ 审计条数一律 `WHERE action=? AND team_id=?`，并额外断言**全表行数不变**——
登录本身写 `AUTH_LOGIN_SUCCESS`，真栈里「全表 0 行」永远不成立。

## 结论（本轮 2026-10-17）

```
JAR_SHA256=a791a789be9a5dd08687d08cbdae7b43508e1fbf172260ccc5f65d464c5e61a8
B95_CHECK_TOTAL=36
B95_FAIL_COUNT=0
B95_RESULT=ALL PASS
```

关键原文：

```
C1-http=500 code=COM-0002        → C1-余额回滚=10.000 / 流水回滚=2 / #12审计0行 / 全表审计不变=3
C2-http=201 txn=4                → 余额 7.000 / 流水 3 / #12=1
C3-http=409 code=INV-5006        → #12 仍 1
C4-http=200                      → 策略落库 1 行 / #7 审计 0（阻断下仍成功，有意差异）
R1-http=200 txn=5 header=[]      → R1b txn=5 header=true / REVERSAL=1 / #13=1
R2-http=409 code=INV-5003        → 冲销仍未增 1 行
R3-http=409 code=INV-5009        → #13 仍 1
```

★ `C1` 的 `500` 与 `COM-0002` 是<b>预期</b>：审计写不进时异常上抛，业务事务整体回滚，
由 `GlobalExceptionHandler` 的未捕获异常兜底返回 5xx。回修单要求的正是「<b>非 2xx</b>」，
没有为此新增错误码（`ErrorCodes.java` 未改）。

