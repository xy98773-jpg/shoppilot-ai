# tools/b9 —— M3-T17（B9）审计埋点真栈取证

本目录是 **B9（审计埋点：#7 / #11 / #12 / #13）** 的独立真栈取证脚本与产物。
目的是**不依赖开发自述**，用自建 fat jar + 独立 MySQL 8.0.36 复现
「四个写动作是否各写一条、字段是否对、失败与越权是否一条都不写」。

| 文件 | 作用 |
| --- | --- |
| `run-b9-evidence.sh` | 采集脚本：起独立 MySQL 8.0.36（端口 3331）+ 自建 fat jar（18111），跑 A1–A5 与 P1–P6 |
| `evidence-b9.txt` | 运行产物（含 jar SHA256、逐条 PASS/FAIL、审计原文） |
| `README.md` | 本文件 |
| `../evi/seed-b9.sql` | 种子：团队 7/8 账号、主数据、3 行余额、2 笔可冲销流水 |

## 怎么跑

```bash
cd backend-java
./mvnw -B verify            # 先产出最新 fat jar（应用日志见 tools/b9/evidence-b9-app.log）
bash tools/b9/run-b9-evidence.sh
```

- 端口 `3331 / 18111`，刻意错开历史批次已用过的 `3319 / 3327 / 3328 / 3329 / 3330`
  与 `18099 / 18107 / 18108 / 18109 / 18110`。
- 脚本自带 `trap cleanup EXIT`，结束会 `docker rm -f b9-mysql` 并杀掉本端口的 java 进程
  （Windows 上遗留 java 会持有 `app/target/*.jar`，导致后续 `repackage` 报 *Unable to rename*）。

## 本轮结果（2026-10-17）

- JAR `shoppilot-app-0.1.0.jar`，`sha256=2a9ef094…543cc8f`
- **`B9_CHECK_TOTAL=77`，`B9_FAIL_COUNT=0`，`exit 0`**
- 分组：A1 **14** / A2 **9** / A3+A3b **10** / A4 **15**（a6+b4+c5）/ A5 **15** / P **14**
- 审计全景（team 7，`GROUP BY action`）：
  `INVENTORY_COUNT_ADJUST=2`（A1 + P1）、`INVENTORY_MANUAL_ADJUST=1`、
  `INVENTORY_REVERSE=1`、`REPLENISHMENT_POLICY_UPSERT=3`、`AUTH_LOGIN_SUCCESS=4`（登录，非本批）

## 组次与断言

> ★ **计数口径**：所有审计断言一律 `WHERE action = ? AND team_id = 7`。
> 登录本身会写 `AUTH_LOGIN_SUCCESS`，**全表 `COUNT(*)` 在同库多动作场景下毫无意义**。

### A1 `#12 INVENTORY_COUNT_ADJUST`（盘点调整，OPERATOR）

`SKU-A 10.000 → 7.000`，断言：恰 +1 条；`action=INVENTORY_COUNT_ADJUST`；
`biz_type=INVENTORY_BALANCE`；**`biz_id` = 本次新增流水 id**；`result=SUCCESS`；
`team_id=7`；`operator_id=1001`、`operator_role=OPERATOR`；
`change_summary` 含 `10.000 → 7.000`、`COUNT_ADJUST`、`原因：月度盘点`、`流水 <id>`；
`request_uri` 自动采集为 `/api/v1/inventory/adjustments`。

### A2 `#11 INVENTORY_MANUAL_ADJUST`（手工调整，ADMIN）

`SKU-B 10.000 → 8.000`（`qty_delta=-2`），断言恰 +1 条、`biz_id` = 新流水 id、
`operator_id=1005`/`operator_role=ADMIN`、摘要含前后值与 `MANUAL_ADJUST`。

### A3 `#13 INVENTORY_REVERSE`（冲销，OWNER）

冲销种子流水 `seed-init-9005`（SKU-C `+10.000` → 余额 0）。断言：恰 +1 条；
`biz_type=INVENTORY_TRANSACTION`；**`biz_id` = 被冲销的原流水 id（不是新流水）**；
摘要含 `冲销流水 <原> → 新流水 <新>` 与 `10.000 → 0.000`。
再冲一次 → `409 INV-5009` 且审计条数不变。

### A4 `#7 REPLENISHMENT_POLICY_UPSERT`（策略：创建 / 更新 / 停用**同用一条** action）

首次 upsert（安全库存 8）→ 二次 upsert（改 12）→ DELETE 停用。
断言：恰 3 条；三条 `biz_id` 为同一策略行 id（`COUNT(DISTINCT biz_id)=1`）；
首次摘要为 `策略 <id> 变更：sku=9002 仓库=9004 安全库存 无 → 8.000`；
二次摘要含 `安全库存 8.000 → 12.000` 且**不含**未变的「提前期天数」；
停用摘要恰为 `停用策略 <id>`，落库 `is_enabled=0`（不物理删除）。

### A5 失败与越权**一条都不写**

`INV-5005`（手工调整 0，400）、`INV-5006`（盘点到 −1，409）、
`TEAM-2003`（VIEWER，403）、`INV-5007`（OPERATOR 做 MANUAL_ADJUST，403）、
`COM-0001`（策略 `lead_time_days=-1`，400）——执行后四个 action 的条数与流水总数**全部不变**。

### P 附加探针（开发主动补测）

| 编号 | 探针 | 依据 |
| --- | --- | --- |
| P1 | 幂等重放（同 `Idempotency-Key` 同内容）→ 同一条流水、审计**只 +1** | `§8.3`；一次变更只应记一次 |
| P2 | `#13` 的 `biz_id` 是原流水，新 `REVERSAL` 流水 id 只在摘要里，两者都存在且不同 | `§6-J.1 #13` |
| P3 | 审计行 `team_id` 全为 7，团队 8 名下 0 行 | 租户隔离 |
| P4 | 策略二次 upsert 摘要只含实际变化字段 | `§6-J.3`「只记实际变化」 |
| P5 | `before_json`/`after_json` 含 `on_hand_qty` 前后值，且全文无 `password`/`secret` | `§6-J.3` 红线 |
| P6 | 盘点差额为 0（目标=当前）→ 不写流水、不写审计 | `INVENTORY_WORKFLOW §5.5` 步骤 G |

> P1 与 P6 是**同一口径的两面**：没有变更就没有审计。把「重放」也记一条，会让同一次库存变更
> 在审计里出现两次，且第二次的 `occurred_at` 与前后值都不是事实。此口径已写进
> `InventoryWriteService` 类注释「审计埋点」第 3 条。

## 脚本自身的坑（非产品缺陷，写下来避免下次重踩）

1. **应用日志必须独立文件**：`nohup java -jar ... >> 同一个 tee 文件` 会互相覆盖
   （B8 脚本第一版因此丢了整段 S1）。
2. **`mysql` 客户端必须 `--default-character-set=utf8mb4`**：否则中文断言字面量退化成 `?`，
   `LIKE '%覆盖%'` 恒不匹配 → 假 FAIL。
3. **探测组之间必须隔离业务键**：A1 调 SKU-A（10→7）之后再去冲 SKU-A 的 `+10` 导入流水，
   会得到 `7-10 = -3` 撞 `INV-5006`，取证结论直接失真。故种子给 A3 单独一条
   `seed-init-9005`（SKU-C）。**这属于「探针互相污染」，不是产品缺陷。**
4. **JSON 列取值用 `JSON_UNQUOTE(JSON_EXTRACT(...))`**：直接比 `before_json` 原文字符串会
   被 MySQL 的键序/空格格式绑死，换个版本就假 FAIL。
