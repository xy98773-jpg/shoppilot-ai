# ShopPilot AI 分析契约（ANALYSIS_CONTRACT）

## 文档元信息

| 项 | 内容 |
| --- | --- |
| 文档名称 | 分析输入输出数据契约 |
| 文档路径 | `docs/04-ai/ANALYSIS_CONTRACT.md` |
| 文档优先级 | P3（见 `DESIGN_BASIS.md §1.1`；输出 Schema 权威定义在 P2 的 `AI_SERVICE_CONTRACT.md`） |
| 所属阶段 | M0 设计阶段 |
| 版本 | v1.0（M0 定稿） |
| 上游权威来源 | `docs/DESIGN_BASIS.md §10`（公式）、`§11`（规则）、`§14`（精度）、`docs/02-database/DATA_DICTIONARY.md`、`docs/03-api/AI_SERVICE_CONTRACT.md` |
| 主要读者 | Java 后端、Python 服务、测试 |

### 本文档负责什么

- 定义**分析输入**的完整字段表（run 级参数 + item 级数据），含**来源表字段**。
- 定义**分析输出**的**字段语义与校验规则表**（输出 Schema 的**权威结构定义**在 `AI_SERVICE_CONTRACT.md §3.5`，本文档**不重复定义两套结构**）。
- 定义**算法与规则版本命名规范**与变更规则。
- 定义**数据质量分级**（`data_quality`）判定与影响。
- 定义**计算过程可追溯性**（`risk_findings`）。
- 给出**缺失与降级矩阵**、**确定性测试（golden cases）要求**、**数值精度与舍入规则**、**Java 落库映射表**。

### 本文档不负责什么

- **不重新定义输出 Schema 的 JSON 结构**（见 `AI_SERVICE_CONTRACT.md §3.5`）。
- **不定义 HTTP 接口/认证/超时**（见 `AI_SERVICE_CONTRACT.md`）。
- **不定义公式本身**（见 `DESIGN_BASIS.md §10`）；本文档只给字段语义。
- **不定义 Prompt 文本**（见 `PROMPT_DESIGN.md`）。
- **不定义表结构**（见 `DATABASE_DESIGN.md` / `DATA_DICTIONARY.md`）。

> **结构权威声明**：分析的**输入结构**以本文档 §1 为准；分析的**输出结构**以 `AI_SERVICE_CONTRACT.md §3.5` 为准。本文档 §2 只做**字段语义与校验规则**的补充说明，不产生第二套定义。

---

## 1. 输入数据契约

### 1.1 Run 级参数

> 对应 `AI_SERVICE_CONTRACT.md §3.2` 请求体的 `params` 与顶层字段。字段名与 `analysis_runs.params_json` 一致。

| 字段 | 类型 | 必填 | 约束 | 默认 | 说明 | 来源 |
| --- | --- | --- | --- | --- | --- | --- |
| `analysis_run_id` | int64 | ✅ | >0 | — | 业务主键，**Java 生成** | `analysis_runs.id` |
| `team_id` | int64 | ✅ | >0 | — | 日志分组用（**非权限依据**） | JWT → `TeamContext` |
| `warehouse_id` | int64 | ✅ | >0 | — | 分析范围仓库 | `warehouses.id` |
| `scope.scope_type` | string | ✅ | ∈ `AnalysisScopeType` | — | `ALL_ACTIVE`/`SELECTED_SKUS`/`CATEGORY` | `analysis_runs.scope_type` |
| `scope.sku_ids` | int64[] | 条件 | `scope_type=SELECTED_SKUS` 时必填，长度 1..2000 | — | SKU 范围 | `analysis_runs.scope_json` |
| `scope.category` | string | 条件 | `scope_type=CATEGORY` 时必填 | — | 商品类目 | `products.category` |
| `date_from` | date | ✅ | `YYYY-MM-DD`，≤ `date_to` | — | 数据窗口起 | 请求 |
| `date_to` | date | ✅ | `YYYY-MM-DD` | — | 数据窗口止 | 请求 |
| `params.window_days` | int | ❌ | 1..365 | **28** | 统计窗口 `N` | `DESIGN_BASIS §10.6` |
| `params.min_days` | int | ❌ | 1..`window_days` | **7** | 最少有效天数 `MIN_DAYS` | `DESIGN_BASIS §10.6` |
| `params.coverage_days` | int | ❌ | 0..365 | **7** | 覆盖天数 `R` | `DESIGN_BASIS §10.6` |
| `params.lead_time_default` | int | ❌ | 0..365 | **7** | 全局默认交期 `DEFAULT_LEAD_TIME_DAYS` | `DESIGN_BASIS §10.6` |
| `params.ss_ratio` | decimal4 | ❌ | ≥0 | **0.5** | `SAFETY_RATIO` | `DESIGN_BASIS §10.3` |
| `params.cv_high` | decimal4 | ❌ | ≥0 | **1.0** | `CV_HIGH` | `DESIGN_BASIS §10.6` |
| `params.budget_limit` | decimal3 \| null | ❌ | ≥0 或 `null` | **null** | 预算上限；`null` = 不限制 | `DESIGN_BASIS §10.5` |
| `params.enable_llm` | bool | ❌ | — | **true** | 是否启用 LLM 解释 | 请求 |
| `algorithm_version` | string | ✅ | 见 §3 | — | 算法版本 | `analysis_runs.algorithm_version` |
| `prompt_version` | string | ✅（`enable_llm=true`） | 见 §3 | — | Prompt 版本 | `analysis_runs.prompt_version` |
| `model_name` | string | ❌ | — | — | LLM 模型名 | `analysis_runs.model_name` |

**策略参数覆盖优先级**（run 级 `params` 与表级策略的合并规则，Java 侧完成合并后再传入）：

```
策略表 replenishment_policies（按 team_id+sku_id+warehouse_id）
  → 若字段为 NULL，回退 run 级 params
    → 若 params 未提供，回退服务默认值（DESIGN_BASIS §10.6）
```

> `LeadTimeSource`（`POLICY`/`SKU`/`SUPPLIER`/`DEFAULT`）的判定在 Java 侧完成：`replenishment_policies.lead_time_days` → `skus.default_lead_time_days` → `suppliers.lead_time_days` → `params.lead_time_default`（`DESIGN_BASIS §10.2`）。Python 只接收**最终采用值**与**来源标记**。

### 1.2 Item 级数据（每个 SKU）

> 对应 `AI_SERVICE_CONTRACT.md §3.2` 请求体的 `items[]`。

| 字段 | 类型 | 必填 | 约束 | 说明 | 来源表字段 |
| --- | --- | --- | --- | --- | --- |
| `sku_id` | int64 | ✅ | >0，属于该团队且 `ACTIVE` | SKU 主键 | `skus.id` |
| `on_hand_qty` | decimal3 | ✅ | ≥0 | 现有库存 | `inventory_balances.on_hand_qty` |
| `committed_qty` | decimal3 | ✅ | ≥0 | 已承诺库存 | `inventory_balances.committed_qty` |
| `effective_in_transit_qty` | decimal3 | ✅ | ≥0 | **有效在途**（窗口过滤后） | **Java 计算**（`inventory` 在途口径服务；基于 `purchase_order_items`，Python 不接触） |
| `lead_time_days` | int | ❌ | ≥0 | 采用值 `L` | 合并自 `replenishment_policies.lead_time_days` / `skus.default_lead_time_days` / `suppliers.lead_time_days` / `params.lead_time_default` |
| `lead_time_source` | string | ❌ | ∈ `LeadTimeSource` | `L` 来源 | 同上（Java 判定） |
| `safety_stock_override` | decimal3 \| null | ❌ | ≥0 或 `null` | 安全库存绝对值覆盖 | `replenishment_policies.safety_stock_qty` |
| `moq` | decimal3 \| null | ❌ | ≥0 或 `null` | 最小起订量（已按策略→SKU→供应商合并） | `replenishment_policies.moq` → `skus.moq` → `suppliers.moq` |
| `order_multiple` | decimal3 \| null | ❌ | ≥0 或 `null` | 采购倍数（同上合并） | `replenishment_policies.order_multiple` → `skus.order_multiple` → `suppliers.order_multiple` |
| `qty_step` | decimal3 \| null | ❌ | >0 或 `null` | 最小起订精度 | `replenishment_policies.qty_step` / `skus.qty_step` |
| `listed_date` | date \| null | ❌ | `YYYY-MM-DD` | 上架日期（`R-SLOWMOVING` 输入） | `skus.listed_date` |
| `sales_series` | array | ✅ | 长度 ≤ `window_days`；`date` 唯一且升序；`qty` ≥0 | 窗口内日销量序列 | `sales_daily.sale_date` / `sales_daily.qty` |

**`sales_series` 元素**：

| 字段 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `date` | date | ✅ | `YYYY-MM-DD`，∈ `[date_from, date_to]` | 销售日 |
| `qty` | decimal3 | ✅ | ≥0 | 净销量（`sales_daily.qty`）；**缺失日不补 `0`**（由 Python 依据窗口计算 `n`） |

**缺数语义约定（重要）**：

- `sales_series` **只包含有记录的日子**；**不要求** Java 补零。Python 按 `window_days` 与实际序列长度计算 `n`（有效天数）与缺失天数。
- 若窗口内**完全无记录**，`sales_series = []`，`valid_days = 0` → `INSUFFICIENT`。
- `committed_qty` 首期默认 `0.000`，但字段必须传（保留写入通道，`DATA_DICTIONARY.md §5.9`）。

### 1.3 输入数据量与裁剪（引用 `AI_SERVICE_CONTRACT.md §3.3`）

- 单 SKU `sales_series` ≤ `window_days`（默认 28）条。
- 请求体上限 **8MB**；2000 SKU 约 2.5MB（见 `AI_SERVICE_CONTRACT.md §3.3`）。
- **不传**：商品详情、供应商联系方式、价格/成本、采购明细（见 `AI_SERVICE_CONTRACT.md §3.3` 表）。

---

## 2. 输出数据契约（字段语义与校验规则）

> **输出 Schema 的权威结构定义在 `AI_SERVICE_CONTRACT.md §3.5`**（`items[]` 字段一览表）。本节仅补充**语义、校验规则与边界**，**不重复定义结构**。

### 2.1 字段语义与校验规则

| 字段 | 语义 | 计算/来源 | 校验规则（Java 落库前） |
| --- | --- | --- | --- |
| `sku_id` | SKU 主键 | 回显输入 | 必填；属于团队且 `ACTIVE`；在请求 `scope` 内 |
| `warehouse_id` | 仓库主键 | 回显输入 | 必填；属于团队 |
| `avg_daily_sales`（`D`） | 日均销量 | `DESIGN_BASIS §10.1` | 数据不足为 `null`；否则 ≥0，3 位小数 |
| `sales_qty_total` | 窗口总销量 | `Σ qty` | ≥0，3 位 |
| `valid_days`（`n`） | 有效销售天数 | 窗口内有记录天数 | ≥0，≤ `window_days` |
| `data_quality` | 数据质量分级 | §4 | ∈ `{GOOD,FAIR,POOR,INSUFFICIENT}` |
| `risk_level` | 风险等级 | `MAX(各规则)`（`DESIGN_BASIS §11.1`） | ∈ `RiskLevel`（5 值） |
| `risk_types` | 风险类型数组 | `DESIGN_BASIS §11` | 元素 ∈ `RiskType`（6 值），去重 |
| `risk_findings` | 规则命中明细 | §5 | 数组；每条含 `rule_code`/`inputs`/`threshold`/`result` |
| `on_hand_qty` / `effective_in_transit_qty` / `committed_qty` | 库存快照 | 回显输入 | ≥0，3 位 |
| `inventory_position`（`IP`） | 库存位置 | `on_hand + eff_in_transit − committed`（`DESIGN_BASIS §3`） | 与三项一致（Java 重算比对）；**允许为负**（`committed > on_hand + effective_in_transit` 的超卖场景），Java 侧不得加非负校验 |
| `safety_stock`（`SS`） | 安全库存 | `DESIGN_BASIS §10.3` | ≥0 或 `null`；3 位 |
| `lead_time_days`（`L`） | 采用交期 | `DESIGN_BASIS §10.2` | ≥0 或 `null`；与 `lead_time_source` 一致 |
| `lead_time_source` | `L` 来源 | `DESIGN_BASIS §10.2` | ∈ `LeadTimeSource`（`POLICY`/`SKU`/`SUPPLIER`/`DEFAULT`） |
| `coverage_days`（`R`） | 覆盖天数 | `DESIGN_BASIS §10.2` | ≥0 或 `null` |
| `cv` | 波动率 | `DESIGN_BASIS §10.1` | ≥0 或 `null`（`mean=0` 时 `null`）；4 位 |
| `volatility_adjusted` | 高波动上浮标记 | `DESIGN_BASIS §10.3` | bool |
| `q_base`（`Q_base`） | 基础补货需求 | `DESIGN_BASIS §10.2` | ≥0 或 `null`；3 位 |
| `moq_applied` | 生效 MOQ | `DESIGN_BASIS §10.5` | ≥0 或 `null` |
| `order_multiple_applied` | 生效倍数 | `DESIGN_BASIS §10.5` | ≥0 或 `null` |
| `q_suggest`（`Q_suggest`） | 建议补货量 | `DESIGN_BASIS §10.5` | ≥0 或 `null`；是 `qty_step` 的整数倍 |
| `truncated_by_budget` | 预算截断标记 | `DESIGN_BASIS §10.5` | bool；为 `true` 时 `q_suggest ≤ budget_limit` |
| `llm_explanation` | LLM 解释 | `PROMPT_DESIGN.md` | `null` 或长度 ≤ 2000；降级为 `null` |
| `llm_evidence` | 解释引用的输入字段名 | `PROMPT_DESIGN.md` | 数组；每项应为**已存在**的输入字段名 |
| `llm_status` | 单 item LLM 状态 | `LlmStatus` | ∈ 7 值 |
| `warnings` | 该 item 提示 | — | 数组 |

### 2.2 输出顶层（run 摘要）

| 字段 | 语义 | 校验 |
| --- | --- | --- |
| `analysis_run_id` | 回显 | == 请求值 |
| `execution_status` | Python 执行状态 | ∈ 5 值（`AI_SERVICE_CONTRACT.md §3.4`） |
| `algorithm_version` | 算法版本 | == 请求值 |
| `prompt_version` | Prompt 版本 | == 请求值 |
| `model_name` | 模型名 | == 请求值（可空） |
| `llm_status` | run 级 LLM 状态 | ∈ 7 值；与各 item 兼容（`AI_SERVICE_CONTRACT.md §8`） |
| `data_start_date` / `data_end_date` | 数据窗口 | ∈ `[date_from, date_to]` 且 `start ≤ end` |
| `summary` | 结果摘要 | `total_skus`/`risk_sku_count`/`suggestion_count`/`truncated_count`/`by_risk_level` |
| `items[]` | 明细数组 | 长度 ≤ 请求 SKU 数；`(sku_id, warehouse_id)` 唯一 |

---

## 3. 算法与规则版本

### 3.1 版本命名规范

| 版本类型 | 命名格式 | 示例 | 对应字段 |
| --- | --- | --- | --- |
| 统计算法 | `<method>-v<n>` | `avg-ma-3sigma-v1` | `analysis_runs.algorithm_version` |
| 规则集 | `risk-rules-v<n>` | `risk-rules-v1` | （随 `algorithm_version` 绑定或独立记录） |
| 补货计算 | `replen-calc-v<n>` | `replen-calc-v1` | （随 `algorithm_version` 绑定） |
| Prompt | `<prompt_name>.v<n>` | `explain_risk.v1` | `analysis_runs.prompt_version` |

**首期取值（M0 固化）**：

| 版本 | 值 |
| --- | --- |
| `algorithm_version` | `avg-ma-3sigma-v1`（`DESIGN_BASIS §10.1`） |
| 规则集 | `risk-rules-v1`（`DESIGN_BASIS §11` 的 6 条规则） |
| 补货计算 | `replen-calc-v1`（`DESIGN_BASIS §10.2`/`§10.5`） |
| `prompt_version` | `explain_risk.v1`（默认）；可选 `explain_replenishment.v1`/`run_summary.v1` |

**版本组合表达**：`algorithm_version` 采用**复合版本串**时推荐格式：

```
avg-ma-3sigma-v1+risk-rules-v1+replen-calc-v1
```

- 首期亦可只用 `avg-ma-3sigma-v1` 作为总版本号，**但规则集与补货计算的版本必须能在结果中追溯到**（通过 `risk_findings[].rule_code` 与 `ANALYSIS_CONTRACT` 版本表）。**推荐**采用复合版本串，便于一次性表达三者版本。

### 3.2 版本变更规则

| 规则 | 说明 |
| --- | --- |
| **不可混用** | **同一 run 内所有 item 必须使用同一 `algorithm_version`**；不同版本结果**不得混在同一 run**。 |
| **变更即升版** | 修改公式、阈值默认值、规则逻辑、舍入方式 → **必须**升 `algorithm_version`（`DESIGN_BASIS §10`/`§11` 变更同步，`§16`）。 |
| **版本独立演进** | `algorithm_version` 与 `prompt_version` **独立**：改 Prompt 不改算法版本，改算法不改 Prompt 版本。 |
| **可追溯** | 每个 run 记录使用的版本；历史 run 结果**不重算**（`analysis_items` 不可变）。 |
| **默认值变更也算变更** | 调整 `DESIGN_BASIS §10.6` 默认值（如 `CV_HIGH` 1.0→1.2）→ 升版，并在变更记录说明。 |
| **向后兼容判定** | 结果字段**新增**（如增加 `warnings`）→ 兼容，可同版本；结果**语义变化**（如 `D` 从截尾均值改为中位数）→ 不兼容，必须升版。 |

---

## 4. 数据质量分级

### 4.1 `data_quality` 取值与判定

| 取值 | 判定规则（基于有效天数比例与异常值比例） | 说明 |
| --- | --- | --- |
| `GOOD` | `valid_days / window_days ≥ 0.8` 且 异常值比例 ≤ 5% | 数据充分可信 |
| `FAIR` | `0.5 ≤ valid_days / window_days < 0.8`，或 异常值比例 5%–15% | 基本可用，轻度警示 |
| `POOR` | `valid_days / window_days < 0.5`（但 `valid_days ≥ min_days`），或 异常值比例 > 15% | 可用但需标注警示 |
| `INSUFFICIENT` | `valid_days < min_days` | 数据不足，**不出建议** |

> 异常值定义：窗口内 `qty` 超出 `mean ± ANOMALY_SIGMA × std`（`ANOMALY_SIGMA` 默认 2.0，`DESIGN_BASIS §10.6`）的天数。
> `window_days` 取实际窗口长度（默认 28）。

### 4.2 各级对后续处理的影响

| `data_quality` | 是否计算 `D` | 是否计算 `Q_base` | 是否产出补货建议 | 是否允许 LLM 解释 | 输出标记 |
| --- | --- | --- | --- | --- | --- |
| `GOOD` | ✅ | ✅ | ✅ | ✅ | 无特殊标记 |
| `FAIR` | ✅ | ✅ | ✅ | ✅ | `warnings` 提示"数据覆盖一般" |
| `POOR` | ✅ | ✅ | ✅（**标注警示**） | ✅ | `warnings` 提示"数据质量较差，建议人工复核"；`risk_types` 可含 `DATA_INSUFFICIENT` 的提示性说明（不强制） |
| `INSUFFICIENT` | ❌（`D=null`） | ❌ | ❌ | ❌（`llm_status=SKIPPED`） | `risk_level=UNKNOWN`，`risk_types=["DATA_INSUFFICIENT"]`，`warnings` 提示 |

> **硬规则**：`INSUFFICIENT` **绝不产出补货建议**（`DESIGN_BASIS §10.1`、`PRD.md §9.3 AC-ANL-01`），且**不猜测**。

---

## 5. 计算过程可追溯性

### 5.1 `risk_findings` 结构（每条规则命中）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rule_code` | string | 规则代码，∈ `{R-STOCKOUT, R-LOWSTOCK, R-OVERSTOCK, R-SLOWMOVING, R-SALESANOMALY, R-DATAINSUFFICIENT}`（`DESIGN_BASIS §11`） |
| `inputs` | object | 该规则实际使用的输入值（键为字段名，值为原值） |
| `threshold` | string | 使用的阈值/判据（人类可读，含具体数值） |
| `result` | string | 该规则的结果等级（`RiskLevel`），或 `NOT_TRIGGERED`（未命中） |

- **每个 item 必须能解释"用了哪些输入、套了哪条规则、阈值是多少、结果怎么来的"**。
- 为保证可追溯性，`inputs` **必须回显实际参与计算的值**（如 `D`、`IP`、`L`、`R`、`on_hand_qty`），而**不是**只写规则名。

### 5.2 完整示例 JSON（单个 item）

```json
{
  "sku_id": 5001,
  "warehouse_id": 100,
  "avg_daily_sales": "6.250",
  "sales_qty_total": "162.500",
  "valid_days": 26,
  "data_quality": "GOOD",
  "risk_level": "HIGH",
  "risk_types": ["STOCKOUT", "LOW_STOCK"],
  "risk_findings": [
    {
      "rule_code": "R-STOCKOUT",
      "inputs": { "on_hand_qty": "8.000", "D": "6.250" },
      "threshold": "on_hand_qty / D = 1.28 天 <= 2 天",
      "result": "HIGH"
    },
    {
      "rule_code": "R-LOWSTOCK",
      "inputs": { "IP": "8.000", "D": "6.250", "L": 7, "R": 7 },
      "threshold": "IP(8.000) < D*(L+R)(87.500)",
      "result": "MEDIUM"
    },
    {
      "rule_code": "R-OVERSTOCK",
      "inputs": { "on_hand_qty": "8.000", "D": "6.250" },
      "threshold": "on_hand_qty / D = 1.28 天 <= OVERSTOCK_DAYS(90)",
      "result": "NOT_TRIGGERED"
    },
    {
      "rule_code": "R-SLOWMOVING",
      "inputs": { "last_30d_sales_qty": "162.500", "on_hand_qty": "8.000" },
      "threshold": "近 min(SLOW_MOVING_DAYS(30), window_days(28)) 天销量 = 0 且 on_hand_qty > 0",
      "result": "NOT_TRIGGERED"
    },
    {
      "rule_code": "R-SALESANOMALY",
      "inputs": { "last7_avg": "6.000", "prev21_avg": "6.400", "prev21_std": "1.200" },
      "threshold": "|Δ|(0.400) > ANOMALY_SIGMA(2.0)*std(1.200)=2.400 且变化率 > 50%",
      "result": "NOT_TRIGGERED"
    }
  ],
  "on_hand_qty": "8.000",
  "effective_in_transit_qty": "0.000",
  "committed_qty": "0.000",
  "inventory_position": "8.000",
  "safety_stock": "25.000",
  "lead_time_days": 7,
  "lead_time_source": "SKU",
  "coverage_days": 7,
  "cv": "0.4200",
  "volatility_adjusted": false,
  "q_base": "104.500",
  "moq_applied": "50.000",
  "order_multiple_applied": "10.000",
  "q_suggest": "110.000",
  "truncated_by_budget": false,
  "llm_explanation": "SKU 当前现有库存 8 件，近 28 天日均销量约 6.25 件，仅够支撑约 1.3 天销售（低于 2 天警戒线），判定为缺货高风险。按交期 7 天与覆盖 7 天计算，理论补货约 104.5 件，叠加最小起订量后建议补货 110 件。",
  "llm_evidence": ["on_hand_qty", "avg_daily_sales", "lead_time_days", "coverage_days", "q_suggest"],
  "llm_status": "SUCCEEDED",
  "warnings": []
}
```

> `threshold` 中出现的数值（如 `87.500`）必须**可由 `inputs` 与 `DESIGN_BASIS §11` 复算得出**，以便审计复核。

---

## 6. 缺失与降级矩阵

| # | 缺失情形 | 系统行为 | 输出字段标记 | 前端展示提示 |
| --- | --- | --- | --- | --- |
| 1 | **无销售数据**（窗口内 `sales_series=[]`） | `valid_days=0` → `INSUFFICIENT`；`D=null`；不计算 `Q`；不生成建议；跳过 LLM | `risk_level=UNKNOWN`，`risk_types=["DATA_INSUFFICIENT"]`，`data_quality="INSUFFICIENT"`，`q_suggest=null` | "无销售数据，无法分析" 灰色标识 |
| 2 | **销售数据不连续**（`valid_days < window_days` 但 ≥ `min_days`） | 正常计算，`D` 基于有效日 | `data_quality` ∈ `FAIR`/`POOR`；`warnings` 说明缺失天数 | "数据覆盖 X/Y 天" 黄色警示 |
| 3 | **有效天数不足**（`< min_days`） | 同 #1：不出建议 | `risk_types=["DATA_INSUFFICIENT"]`，`risk_level=UNKNOWN` | "数据不足，未生成补货建议" |
| 4 | **无库存记录**（`(wh,sku)` 无余额行） | Java 侧视 `on_hand_qty=0`（建议）或拒绝该 SKU（按团队策略）；Python 侧收到 `on_hand_qty=0.000` | `on_hand_qty="0.000"`；可能触发 `R-STOCKOUT`（若 `D>0`） | "无库存记录，按 0 处理" 提示 |
| 5 | **无补货策略**（`replenishment_policies` 无匹配） | 回退 run 级 `params` → 服务默认（`L=7`、`R=7`、`SS_RATIO=0.5`、MOQ=1） | `lead_time_source="DEFAULT"`；`moq_applied` 可能为 `1.000` | 无强制提示（信息级） |
| 6 | **无交期**（策略/SKU/供应商均未配） | 用 `params.lead_time_default` | `lead_time_source="DEFAULT"` | "交期使用默认值，建议在 SKU/供应商补充" 提示 |
| 7 | **无供应商** | 不影响计算；`supplier_id` 落库为 `null`，采购时需补 | `warnings` 含"未配置供应商" | "未指定供应商，转采购前需补充" 提示 |
| 8 | **新品（无历史/上架日近）** | 若 `valid_days < min_days` → 同 #1；若 `listed_date` 近期但日均正常 → 正常计算 | `risk_types` 可能含 `DATA_INSUFFICIENT` 或正常；`warnings` 说明"新品" | "新品，历史数据有限" 提示 |
| 9 | **LLM 关闭**（`enable_llm=false`） | 跳过步骤 7/8 | `llm_status="SKIPPED"`（item 与 run 级），`llm_explanation=null` | 不显示解释区（非异常） |
| 10 | **LLM 失败/超时/输出非法** | 保留规则结果，标记降级 | item `llm_status` ∈ `FAILED`/`TIMEOUT`/`VALIDATION_FAILED`；run `DEGRADED` | "解释生成失败，结果仍可用" 提示 |
| 11 | **销量异常**（近 7 天波动大） | 仅提示，不做修正（`DESIGN_BASIS §11.2`） | `risk_types` 含 `SALES_ANOMALY`（`LOW`） | "近期销量波动较大，请留意" 提示 |
| 12 | **预算限制触发** | `q_suggest` 截断 | `truncated_by_budget=true` | "受预算限制，建议量已截断" 提示 |

---

## 7. 确定性测试要求

### 7.1 原则

- **确定性测试不依赖大模型**：将 `params.enable_llm=false` 即可**全量验证**规则与计算结果（步骤 1–6、9–10 均为确定性，`AI_ARCHITECTURE.md §3.2`）。
- **固定输入 → 固定输出**：每个用例给出输入 JSON 与期望输出 JSON（golden case），**逐字段**断言（数值按约定精度比较；`llm_*` 字段在 `enable_llm=false` 下恒为空/`SKIPPED`）。
- fixture 存放于 `tests/fixtures/analysis/`，**Java 与 Python 共享同一份**（`AI_SERVICE_CONTRACT.md §9.3`）。

### 7.2 Golden Cases 清单（≥12，含边界）

| # | 用例名 | 输入要点 | 期望输出要点 | 覆盖规则/边界 |
| --- | --- | --- | --- | --- |
| GC-01 | 正常热销、库存充足 | `D≈6.25`，`on_hand=200`，`L=7`，`R=7` | `risk_level=NONE`，`q_suggest=null`（`Q_base≤0`），`data_quality=GOOD` | 基线；`Q_base≤0` 不出建议 |
| GC-02 | 缺货高风险 | `D=6.25`，`on_hand=8` | `risk_level=HIGH`，`risk_types` 含 `STOCKOUT`+`LOW_STOCK`，`q_suggest>0` | `R-STOCKOUT` 命中高 |
| GC-03 | 缺货边界（`on_hand/D == 2.000`） | `D=5.000`，`on_hand=10.000` | `STOCKOUT=HIGH`（`<=2` 边界命中） | `R-STOCKOUT` 边界 |
| GC-04 | 低库存（未触发缺货） | `D=2.000`，`on_hand=10`，`IP < D*(L+R)` | `risk_level=MEDIUM`，`LOW_STOCK`，`STOCKOUT` 未命中 | `R-LOWSTOCK` |
| GC-05 | 库存过高 | `D=1.000`，`on_hand=95.000`（`/D=95 > 90`） | `risk_level=MEDIUM`，`OVERSTOCK` | `R-OVERSTOCK` 命中中 |
| GC-06 | 库存过高 HIGH（`> 2*OVERSTOCK_DAYS`） | `D=1.000`，`on_hand=181.000`（`/D=181 > 180`） | `risk_level=HIGH`，`OVERSTOCK` | `R-OVERSTOCK` 高边界 |
| GC-07 | 滞销 | 近 30 天销量 `0`，`on_hand=50` | `risk_level=MEDIUM`，`SLOW_MOVING` | `R-SLOWMOVING` |
| GC-08 | 销量异常 | 近 7 天日均 10，前 21 天日均 2，std 1；变化率 >50% | `risk_types` 含 `SALES_ANOMALY`，`risk_level≥LOW` | `R-SALESANOMALY` |
| GC-09 | 数据不足（`n=6 < 7`） | 窗口内 6 天有数据 | `risk_level=UNKNOWN`，`DATA_INSUFFICIENT`，`D=null`，`q_suggest=null` | `R-DATAINSUFFICIENT`；`INSUFFICIENT` 不出建议 |
| GC-10 | 无销售数据（`n=0`） | `sales_series=[]` | 同 GC-09 | 边界 |
| GC-11 | MOQ 生效 | `Q_base=30`，`MOQ=50` | `moq_applied=50.000`，`q_suggest=50.000` | `DESIGN_BASIS §10.5` |
| GC-12 | 采购倍数生效 | `Q_base=73`，`order_multiple=10` | `q_suggest=80.000`（向上取整到倍数） | `DESIGN_BASIS §10.5` |
| GC-13 | 预算截断 | `Q_base=200`，`budget_limit=150` | `q_suggest=150.000`，`truncated_by_budget=true` | `DESIGN_BASIS §10.5` |
| GC-14 | 高波动上浮 | `CV=1.5 > CV_HIGH(1.0)` | `volatility_adjusted=true`，`SS` 上浮 50% | `DESIGN_BASIS §10.3` |
| GC-15 | 安全库存覆盖 | `safety_stock_override=100` | `SS=100.000`（覆盖公式） | `DESIGN_BASIS §10.3` |
| GC-16 | 有效在途计入 `IP` | `on_hand=10`，`eff_in_transit=8`，`committed=0` | `IP=18.000`；`Q_base` 按 `IP=18` 计算 | `DESIGN_BASIS §10.4` |
| GC-17 | 3σ 截尾 | 窗口含 1 个极端日（`qty` 远超 `mean+3σ`） | 极端日被剔除，`D` 为截尾后均值 | `DESIGN_BASIS §10.1` |
| GC-18 | 全部规则未命中且 `Q_base<=0` | `D=1`，`on_hand=50`，`L=0`，`R=0`，`SS=0`（原稿 `on_hand=1000` 会触发 `OVERSTOCK`，不满足「全部未命中」，见 `DESIGN_REVIEW R116④`） | `risk_level=NONE`，`q_suggest=null` | 边界 |

> **`enable_llm=false`**：以上 18 个用例**全部**可在无 LLM 条件下断言（`llm_explanation=null`、`llm_status="SKIPPED"`）。`llm_*` 相关行为另见 `AI_SERVICE_CONTRACT.md §9.2` 的 Mock LLM 用例。

### 7.3 断言要求

- **数值**：按 `ANALYSIS_CONTRACT §8` 精度比较（`Decimal`，`ROUND_HALF_UP`），**禁止**浮点相等比较。
- **集合**：`risk_types` 按集合比较（顺序不敏感）；`risk_findings` 按 `rule_code` 匹配后比较。
- **`threshold` 文本**：允许规范化后比较（去空白），但**必须**含关键数值。

---

## 8. 数值精度与舍入规则

> 引用 `DESIGN_BASIS §14.2`。

| 量 | 中间精度 | 最终精度 | 舍入模式 | 越界处置 |
| --- | --- | --- | --- | --- |
| `D`（日均销量） | 6 位 | **3 位** | `ROUND_HALF_UP` | Java 落库前校验 scale ≤ 3，否则 `ANL-7004` |
| `Q_base` | 6 位 | **3 位** | `ROUND_HALF_UP` | 同上 |
| `Q_suggest` | — | **取整到 `qty_step`**（默认 1，即 3 位小数且为步长整数倍） | 先 `ROUND_HALF_UP` 到 3 位，再按 `qty_step` **向上取整** | 同上 |
| `SS`（安全库存） | 6 位 | **3 位** | `ROUND_HALF_UP` | 同上 |
| `IP`（库存位置） | 6 位 | **3 位** | `ROUND_HALF_UP` | Java 重算比对 |
| 金额 | 4 位 | **2 位** | `ROUND_HALF_UP` | 单价 4 位、金额 2 位（`§14.2`） |
| `cv` / `ss_ratio` / `cv_high` | 6 位 | **4 位** | `ROUND_HALF_UP` | — |

**硬规则**：

1. **舍入模式统一 `ROUND_HALF_UP`**（Java `RoundingMode.HALF_UP`，Python `decimal.ROUND_HALF_UP`）。**禁止** `ROUND_HALF_EVEN`（银行家舍入）。
2. **禁止 `FLOAT`/`DOUBLE`**：Java 用 `BigDecimal`，Python 用 `Decimal`（`DESIGN_BASIS §14.2`）。pandas 结果须**显式转换**为 `Decimal`（`Decimal(str(round(x, 3)))`）。
3. **`q_suggest` 取整到 `qty_step`**：默认 `qty_step=1`，即整数；若 `qty_step=0.5` 则结果为其整数倍。
4. **传输格式**：数量/金额在 JSON 中用**字符串**（`AI_SERVICE_CONTRACT.md §4.1`）。
5. **比较容差**：Java 重算比对时用 `BigDecimal.compareTo == 0`（同精度下），**不用** `equals`（避免 scale 差异）。

---

## 9. Java 落库映射表

> 分析输出 → `analysis_items` / `replenishment_items` **实际字段**（字段名以 `DATA_DICTIONARY.md` 为准）。

### 9.1 输出 → `analysis_items`（逐 SKU 一行，**不可变快照**）

| 输出字段 | `analysis_items` 字段 | 类型 | 说明 |
| --- | --- | --- | --- |
| `sku_id` | `sku_id` | BIGINT | 键（`uk_ai_run_sku_wh`） |
| `warehouse_id` | `warehouse_id` | BIGINT | 键 |
| （run 上下文） | `run_id` | BIGINT | 键；来自 `analysis_runs.id` |
| （run 上下文） | `team_id` | BIGINT | `TeamContext` 注入 |
| （Java 补充） | `product_id` | BIGINT | 冗余，由 `skus.product_id` 回填 |
| `on_hand_qty` | `on_hand_qty` | DECIMAL(18,3) | 快照 |
| （输入） | `in_transit_qty` | DECIMAL(18,3) | **总在途**（`inventory_balances.in_transit_qty`），非有效在途 |
| `effective_in_transit_qty` | `effective_in_transit_qty` | DECIMAL(18,3) | 有效在途 |
| `committed_qty` | `committed_qty` | DECIMAL(18,3) | 快照 |
| `inventory_position` | `inventory_position_qty` | DECIMAL(18,3) | `IP` |
| （Java 计算） | `available_qty` | DECIMAL(18,3) | `on_hand_qty − committed_qty`（展示口径） |
| `avg_daily_sales` | `avg_daily_sales` | DECIMAL(18,3) | `D` |
| （run 参数） | `sales_window_days` | INT UNSIGNED | `window_days` |
| `valid_days` | `valid_days` | INT UNSIGNED | `n` |
| `sales_qty_total` | `total_sales_qty` | DECIMAL(18,3) | — |
| `cv` | `cv` | DECIMAL(18,4) | — |
| `lead_time_days` | `lead_time_days` | INT UNSIGNED | `L` |
| `lead_time_source` | `lead_time_source` | VARCHAR(32) | `LeadTimeSource` |
| `coverage_days` | `coverage_days` | INT UNSIGNED | `R` |
| `safety_stock` | `safety_stock_qty` | DECIMAL(18,3) | `SS` |
| `volatility_adjusted` | `volatility_adjusted` | TINYINT(1) | — |
| `q_base` | `qty_base` | DECIMAL(18,3) | `Q_base` |
| `q_suggest` | `qty_suggest` | DECIMAL(18,3) | `Q_suggest` |
| `moq_applied` | `moq_applied` | DECIMAL(18,3) | — |
| `order_multiple_applied` | `order_multiple_applied` | DECIMAL(18,3) | — |
| `truncated_by_budget` | `truncated_by_budget` | TINYINT(1) | — |
| `risk_level` | `risk_level` | VARCHAR(16) | `RiskLevel` |
| `risk_types` | `risk_types` | JSON | 数组 |
| `risk_findings` | `risk_detail_json` | JSON | 规则命中明细（规则代码 → 判定值）；**不含** LLM 信息 |
| `data_quality` | `data_quality_json` | JSON | 包装为 `{"level": "...", "valid_days": n, "missing_days": m, "outlier_days": k}` |
| `llm_explanation` | `llm_explanation` | TEXT | 降级为 `NULL` |
| `llm_evidence` / `llm_status` / `warnings` | `llm_detail_json` | JSON | 独立列（`DESIGN_BASIS §8` 补充说明），见 §9.3 |

### 9.2 输出 → `replenishment_items`（对 `q_suggest > 0` 生成，`status=SUGGESTED`）

| 来源 | `replenishment_items` 字段 | 说明 |
| --- | --- | --- |
| 关联 | `source_analysis_item_id` | 指向刚落库的 `analysis_items.id` |
| 上下文 | `run_id` / `team_id` / `sku_id` / `product_id` / `warehouse_id` | 来自 run 与回填 |
| 固定 | `status` = `'SUGGESTED'` | 初始状态 |
| `q_suggest` | `suggested_qty` | = 快照 `qty_suggest`（**不可变**） |
| `q_suggest` | `qty` | 当前生效量，初始同 `suggested_qty` |
| `risk_level` | `risk_level` | 冗余快照 |
| `llm_explanation`（截断后） | `reason` | 建议理由摘要（≤500 字符，`VARCHAR(500)`） |
| （Java 推断） | `supplier_id` | 由 `skus.supplier_id` 或 `products.default_supplier_id` 回填，可空 |
| （Java 取） | `purchase_price` | `skus.purchase_price` |
| （可空） | `expected_arrival_date` | 首期可留空或 `today + L` |
| 其他 | `version` = 0 | 乐观锁初始 |

**`reason` 与 `llm_explanation` 的关系**：`reason` 是**短摘要**（≤500，进 `VARCHAR(500)`），`llm_explanation` 是**完整解释**（≤2000，进 `TEXT`）。Java 从 `llm_explanation` 截取前 500 字符生成 `reason`；若 LLM 降级，`reason` 用规则摘要生成（如"缺货高风险，建议补货 80 件"）。

### 9.3 item 级 LLM 状态落库（`llm_detail_json`）

`analysis_items` 已设**独立列** `llm_detail_json`（`DESIGN_BASIS §8` 补充说明；`DATABASE_DESIGN.md §5.15` 与 `DATA_DICTIONARY.md §5.15` 均已落库）。item 级 LLM 信息**写入该列**，**不得**塞进 `risk_detail_json` 或 `data_quality_json` 的子对象。

`risk_detail_json`（规则命中明细）：

```json
{
  "findings": [
    { "rule_code": "R-STOCKOUT", "inputs": { "on_hand_qty": "8.000", "D": "6.250" }, "threshold": "on_hand_qty / D = 1.28 天 <= 2 天", "result": "HIGH" }
  ],
  "warnings": []
}
```

`llm_detail_json`（条目级 LLM 结果）：

```json
{
  "status": "SUCCEEDED",
  "evidence": ["on_hand_qty", "avg_daily_sales", "lead_time_days", "coverage_days", "q_suggest"],
  "prompt_version": "explain_risk.v1",
  "model_name": "gpt-4o-mini"
}
```

> **降级约定**：LLM 不可用时 `llm_detail_json` 与 `llm_explanation` **同为 `NULL`**，规则计算结果（`risk_detail_json` 等）**必须保留**（`DESIGN_BASIS §5.1` 硬规则）。

### 9.4 未落库字段

`risk_findings[].rule_code` 中的规则**代码**不单独建列；已含于 `risk_detail_json`。`prompt_version`/`model_name` 已在 `analysis_runs` 顶层记录，item 级在 `llm_detail_json` 中冗余便于单条追溯。

---

## 10. 与其它文档的不一致与待决策

| # | 现象 | 建议 |
| --- | --- | --- |
| 1 | ~~`analysis_items` 无 `llm_status`/`llm_evidence`/`warnings` 列~~ | ✅ **已解决**：`DESIGN_BASIS §8` 补充说明已裁定新增 `analysis_items.llm_detail_json`，`DATABASE_DESIGN.md §5.15` 与 `DATA_DICTIONARY.md §5.15` 均已落库；本文档 §9.1 映射表与 §9.3 已同步（`D-21` 对应方案为"增列"，已采纳） |
| 2 | ✅ **已闭环**：`LeadTimeSource`（`analysis_items.lead_time_source`）已由 `DESIGN_BASIS §9.17` 定义 | 本文档 §1.2/§2.1 采用 `POLICY`/`SKU`/`SUPPLIER`/`DEFAULT`，与 `§9.17` 一致（旧编号 `D-14` 作废） |
| 3 | `DESIGN_BASIS §10.6` 未定义 `ANOMALY_SIGMA` 与数据质量分级阈值（0.8/0.5 等） | 本文档 §4 给出 `data_quality` 判定阈值（0.8/0.5、异常值 5%/15%），**属新增约定**；建议登记 `DESIGN_BASIS §15`（**建议 `D-27`**） |
| 4 | ✅ **已闭环**：`data_quality` 的四个取值已由 `DESIGN_BASIS §9.17` 的 `DataQualityGrade` 定义 | 本文档**引用** `GOOD`/`FAIR`/`POOR`/`INSUFFICIENT`；阈值仍属本文档 §4（见 `D-19`） |

**新增待决策**：

| 编号（建议） | 问题 | 推荐方案 | 影响 |
| --- | --- | --- | --- |
| **D-27（建议）** | `data_quality` 枚举与判定阈值（0.8 / 0.5 / 5% / 15%）是否固化到 `DESIGN_BASIS §9`/`§15` | **固化** | 影响数据质量分级的一致性与可测试性 |
| **D-28（建议）** | `algorithm_version` 是否采用复合版本串（`avg-ma-3sigma-v1+risk-rules-v1+replen-calc-v1`） | **推荐采用** | 影响版本追溯粒度与落库长度（`VARCHAR(64)` 足够） |
| **D-29（建议）** | 无库存记录时按 `on_hand_qty=0` 处理还是拒绝该 SKU | **推荐按 0 处理并提示**（更符合"不猜测、不阻断"） | 影响库存缺失场景行为 |

---

## 11. 变更记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-14 | M0 初稿：输入契约（run 级 + item 级字段表）、输出字段语义与校验规则、算法/规则/Prompt 版本规范、数据质量四级、可追溯性示例、缺失降级矩阵 12 行、18 个 golden cases、精度与舍入、Java 落库映射 | API 契约 / AI 架构 |
