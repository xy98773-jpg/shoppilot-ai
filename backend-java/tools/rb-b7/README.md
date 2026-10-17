# B7 独立复核证据（项目负责人执行）

依 `DESIGN_REVIEW.md R47`：所有**归因类证据必须入库、可复现**。本目录是项目负责人对 `B7`
（`M3-T10/T11/T14/T15/T16` inventory API 层）**独立复核**的探针与原始输出，
与开发自报的 `tools/evi/` 相互独立（不采信自报）。

## 组成

| 文件 | 说明 |
|---|---|
| `run-rb7-evidence.sh` | 复核脚本：独立 MySQL 容器（`:3327`，与 verify 的 Testcontainers 完全隔离）+ 自建 fat jar（`:18107`）+ `tools/evi/seed-b7.sql` 种子 → curl 取证 → 收尾 `docker rm -f` |
| `evidence-rb7.txt` | 原始输出（本次复核实跑结果） |

## 复现

```bash
cd backend-java && ./mvnw -B verify           # 先产出 fat jar
cd ../tools/rb-b7 && bash run-rb7-evidence.sh
# 退出 0；看输出里的 RB_FAIL_COUNT 与各项 PASS/FAIL
```

## 覆盖

- **复现组 R**（对照开发 E1–E4）：`alerts` 三态、`VIEWER`→`TEAM-2003`、
  `OPERATOR+MANUAL_ADJUST`→`INV-5007`、`OWNER COUNT_ADJUST`→201、冲销回退、策略 upsert 幂等。
- **新增探针 P**（开发未覆盖）：
  - `P1` ★ 策略 `version` 是否递增 → **发现 `R62`**（乐观锁未生效）
  - `P1b` 对照 `inventory_balances.version`（`B6` 手写递增，应为 2）
  - `P2` `pageSize=201` → `COM-0007`
  - `P3` 异团队 SKU upsert 策略 → `CAT-3004`（422）
  - `P4` 二次冲销 → `INV-5009`（409）
  - `P5` `GET` 零副作用（调两次 alerts，流水数不变）
  - `P6` 分页响应字段名为 `list`（非 `items`）

## 结论

`R1–R4` 全部 PASS（与开发自报一致）；`P1` FAIL —— 归档为 `DESIGN_REVIEW.md R62`。
