# NFR-PERF-02 归因探针

`DESIGN_REVIEW §5.1` 的取证工具。纯 JDBC 单文件，不依赖本项目任何代码，用于分离
「业务落库成本」与「审计写入成本」，并对比审计的两种提交粒度。

## 为什么需要它

`NFR-PERF-02` 要求 1 万行导入 < 60 s，实测 ~180 s。要判定优化方向，必须先回答：
慢在业务落库、还是慢在审计写入？审计的「逐条提交」与「按批提交」差多少？

**该探针与「真栈 confirm 计时」是两组不同证据，不可互相替代**：探针隔离提交粒度，
真栈另含 Spring 事务代理、Hikari 借还、事件构造/JSON 序列化等框架侧开销。

## 前置

```bash
docker run -d --name perf-probe -e MYSQL_ROOT_PASSWORD=probe -e MYSQL_DATABASE=probe \
  -p 13397:3306 mysql:8.0.36 --default-time-zone=+00:00
```

## 运行

```bash
# 参数：jdbcUrl user password [rows=10000] [batch=500]
java -cp mysql-connector-j-9.x.jar PerfProbe.java \
  "jdbc:mysql://127.0.0.1:13397/probe?useSSL=false&allowPublicKeyRetrieval=true" \
  root probe 10000 500
```

## 读数口径

| 输出行 | 含义 |
| --- | --- |
| `A business only (no audit)` | 业务落库基线：含按业务编码回查 id（贴近 upsert 路径），无审计 |
| `B business + audit, per-row commit` | 审计逐条独立提交，模拟现状 `REQUIRES_NEW` 每行一次提交 |
| `C business + audit, per-batch commit` | 同批 N 条审计合并为一个独立事务（审计条数不变） |

- `B - A` ≈ 审计写入的边际成本（逐条提交口径）
- `B - C` ≈ **提交粒度的净收益**，这是 `B4-PERF` 方案 ① 的收益上限
- `audit_rows` 必须等于 `rows`（B/C 两行），否则审计被抑制，读数无效
- `fsyncs` 用于交叉校验：逐条提交时约等于业务提交 + 审计条数

## 表结构说明

`audit_logs` 按 `V1.0.4__audit.sql` 重建（22 列 + 5 索引 + JSON 列 + `CHECK`），
`products` 带真实唯一键 `uk_prod_team_code` 与状态索引。**刻意不简化**：索引与 JSON 列
直接影响每次 INSERT 的页分裂与 redo 量，简化会让逐条提交的代价被低估。

## 历史读数（本机 Docker Desktop，mysql:8.0.36，单次运行）

| 日期 | rows / batch | A | B | C | B/C |
| --- | --- | --- | --- | --- | --- |
| 2026-10-17 | 10,000 / 500 | 23.2 s（2.32 ms） | 108.2 s（10.82 ms） | 30.8 s（3.08 ms） | 3.51× |
| 2026-10-17 | 2,000 / 500 | 4.49 s（2.24 ms） | 16.56 s（8.28 ms） | 5.82 s（2.91 ms） | 2.84× |

> 注：绝对值受机器、磁盘、Docker 卷驱动影响，**看比值不看绝对值**。
> 2026-10-17 第三方评审用独立探针测 500 行 × 3 轮得 4.06×，与本表方向一致。

## 不覆盖什么

- Spring 事务代理（`NESTED` / `SAVEPOINT` 往返）、Hikari 连接借还、审计事件构造与 JSON 序列化
- 真实 HTTP 层与鉴权
- 20 万行规模的内存峰值（该场景须单独观测堆）

上述部分须用真栈观测，二者结论合并后才能形成完整的耗时分解。
