# `rb-b8h` —— 项目负责人对 `B8-hotfix`（R68）的独立真栈复核

> **归属**：项目负责人复核证据（`R47`：归因类证据必须可复现、可入库）。
> **对象**：`a1debd9 → 2a4ee81`（`SalesWriteService` 补 `sale_date` 范围校验）。
> **结论**：`RB8H_CHECK_TOTAL=30`、`RB8H_FAIL_COUNT=0`、`exit=0`。

## 与开发 `tools/b8/` 的分工（刻意不重叠）

开发 `W1–W3b` 证明的是「**正向拦截生效**」（未来日期 / 早于上架日 → `SAL-6003` 且零落库）。
本脚本**不重复证明同一件事**，专门补它没测的四类风险 —— 这是 `B7`（`R62`）与 `B8`（`R68`）两轮的共同教训：
**只复现对方给的场景，证明不了完整性。**

| 类别 | 为什么必须测 |
| --- | --- |
| **A 反向误杀** | 新校验最常见的缺陷不是「拦不住」，而是「拦过头」——把 `listed_date=NULL`、历史行覆盖、边界日一起拦掉 |
| **B 校验优先级** | 日期校验插在 `sku`/仓库归属校验之间，顺序错了会**泄露**「SKU 是否存在 / 何时上架」 |
| **C 副产物** | 被拒请求若仍写审计或残留行，等于「拒绝」是假的 |
| **D 相邻回归** | 格式非法路径、读端点不应受影响 |

## 断言清单（30 项）

| # | 探针 | 期望 | 依据 |
| --- | --- | --- | --- |
| V0 | 迁移 9 条 / 最高 `1.0.8` / `SKU-E listed_date=2026-10-01` | 确认跑的是含 hotfix 的 jar | — |
| V1 | 历史行覆盖 `9002/WH1/2026-10-03` 15→7 | **201/201**、行数 1、`qty=7.000` | `§6-D.2 U-6` 覆盖语义不得因新校验失效 |
| V2 | `listed_date=NULL` 的 SKU 写 `2026-01-05` | **201** | `NULL` 表示未设置，不得参与比较（否则全部误杀） |
| V3 | `sale_date=明天` | `SAL-6003` / 400 / **零落库** | `ERROR_CODES §2.7`（复现 W1） |
| V4 | `sale_date=2026-09-30`（`< listed_date`） | `SAL-6003` / 400 / **零落库** | 同上（复现 W2） |
| V5 | 边界日 `= listed_date` | **201** | 比较符必须是 `<` 而非 `<=`（复现 W3b） |
| V6 | `sale_date=今天` | **201** 且 `qty=2.000` | 最容易被「未来日期」补丁误杀的一天 |
| V7 | 未来日期 + 不存在 SKU `9999` | **422 `SAL-6005`** | `sku` 归属先于日期 ⇒ 不泄露存在性 |
| V8 | 早于上架日 + 异团队 SKU `9102` | **422 `SAL-6005`** | 隔离先于日期 ⇒ 不泄露上架日 |
| V9 | `SELECT COUNT(*) WHERE sale_date > 今天` | **0** | 无未来日期残留 |
| V10 | V3/V4 前后 `SALES_DAILY_UPSERT` 审计条数 | **不变**（`3 → 3`） | 被拒请求不得留痕 |
| V11 | `sale_date=2026-13-40` | 400 `SAL-6003` | 格式路径未被新逻辑破坏 |
| V12 | `GET /api/v1/sales/daily` | **200** | 读路径零回归 |
| V13a/b/c | 观察项（未来+`qty<0` / 未来+异团队仓库 / 未来+非法 `source`） | 仅记录，**不设断言** | 见下「探针自身的坑」 |

## 探针自身的坑（★ 别把自己的 bug 记成产品缺陷）

### 1. `V13c` 是本脚本的**无效探针**，不产生任何结论

`"source":"BAD"` 期望触发 `COM-0001`，实测返回 `SAL-6003`。**不是产品缺陷**：
`SalesDailyRequest` **没有** `source` 字段（`API_CONTRACT §10.5` 规定 REST 写入恒为 `MANUAL`，
见 `SalesController` 直接传 `SalesWriteService.SOURCE_MANUAL`），未知属性被 Jackson 忽略 ⇒
`source` 校验根本走不到。写在这里是为了防止下一次有人拿这个结果去「修」不存在的问题。

### 2. 统计 `verify` 总数：逐类行 + 模块汇总行会**正好翻倍**

```bash
# ✗ 错：718（= 359 × 2）
grep -oE "Tests run: [0-9]+, Failures: ..." log | awk ...

# ✓ 对：先剔除逐类行（"-- in com.foo.Bar"），只累加模块汇总行
grep -E "Tests run:" log | grep -v " -- in " 
```

Maven 同时打印「每个测试类一行」和「每个模块一行」，两者相加恒为 2 倍。本轮首算得 `718`，
真值 `359`。与 `R56`/`R69` 同源教训：**「数出来的数」必须先确认统计面**。

### 3. 其余沿用 `tools/b8/README.md` 的四个坑

`tee` 覆盖日志 / mysql 缺 `--default-character-set=utf8mb4` 致中文断言假 FAIL /
聚合须排在写入之前 / 声明索引须 `LIKE 'idx\_%'` 过滤外键自动索引 —— 本脚本已按此写。
另：统计唯一键必须 `AND index_name<>'PRIMARY'`（主键也是 `non_unique=0`，不排除会得 2）。

## 运行

```bash
bash backend-java/tools/rb-b8h/run-rb8h-evidence.sh
```

- 前置：`backend-java/app/target/shoppilot-app-*.jar` 已由 `./mvnw -B verify` 产出（含 hotfix）。
- 端口：MySQL `3330` + 应用 `18110`（**刻意错开**开发 `3328/18108`、`B7` 复核 `3327/18107`、
  `B8` 复核 `3329/18109`），可与它们并行互不干扰。
- 产物：`evidence-rb8h.txt`（入库）、`evidence-rb8h-app.log`（`*.log` 已 gitignore）。
- 判定：`RB8H_FAIL_COUNT=0` 且 `exit=0` 才算通过；任一 FAIL 都会写进 `evidence-rb8h.txt`。
- ★ 时间依赖：`V3/V6` 用 `date +%F` / `+1 day` 取当天，**任何日期都能跑**（不像 `tools/b8` 的 `W3`
  需要 `今天 > 2026-09-30` 这个前置条件）。`V4/V5` 依赖 `SKU-E` 的固定 `listed_date=2026-10-01`。
