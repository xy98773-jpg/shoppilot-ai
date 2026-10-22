# AI 分析走查手册（新建分析任务 → 读结果）

> 适用：dev 栈（http://localhost:8081），团队 7「星海优选」，账号 `owner01 / 1234`。
> 依据代码：`AnalysisRunCreate.vue`、`analysisForm.ts`、`AnalysisParams.java`、`risk_rules.py`、`replenishment.py`。

## 0. 前置

| 项 | 值 |
| --- | --- |
| 入口 | 左侧栏 **✨ 新建 AI 分析**（`/analysis/runs/new`） |
| 默认仓 | 9003（`WH-A-001 星海中央仓`）——已设为 `is_default=1`，表单留空即用它 |
| 演示数据 | `backend-java/tools/evi/seed-demo-risk.sql`（910001–910005 四类风险 + 1 个健康样本） |

## 1. 表单逐字段怎么填

### 分析范围

| 字段（界面标签） | 怎么填 | 说明 |
| --- | --- | --- |
| 范围类型（scope_type） | **指定 SKU（SELECTED_SKUS）** 用于演示；日常用「全部活跃 SKU」 | 全量会跑 1 万+ SKU，慢；演示选指定 SKU 秒出结果 |
| SKU ID（sku_ids） | `910001,910002,910003,910004,910005` | 逗号/空格/中文逗号分隔，表单上限 2000 个 |
| 限定仓库 ID（warehouse_id） | `9003`（或留空，取默认仓） | 留空要求团队配了默认仓，否则报 `COM-0001 未配置默认仓` |
| 数据窗口（date_from ~ date_to） | 默认最近 28 天（今日往前 27 天 ~ 今日） | 窗口内有效销售天数 < `min_days` → 该 SKU 判 `DATA_INSUFFICIENT`（数据不足） |

### 运行参数（保持默认即可）

| 字段 | 填 | 含义 |
| --- | --- | --- |
| 统计窗口 `window_days` | `28` | 日均销量统计窗口（1..365） |
| 最少有效天数 `min_days` | `7` | 少于 7 天有效销量 → 数据不足，不产补货建议 |
| 覆盖天数 `coverage_days` | `7` | 补货周期 R，即"这次补的货要覆盖几天" |
| 默认交期 `lead_time_default` | `7` | 下单到到货天数 L（SKU 上有值优先用 SKU 的） |
| 安全库存系数 `ss_ratio` | `0.5` | `SS = D × ceil(L × ss_ratio)`；默认 7×0.5=3.5→4 天销量 |
| CV 阈值 `cv_high` | `1.0` | 销量波动 CV 超阈值 → 安全库存上浮 1.5 倍 |
| 预算上限 `budget_limit` | 留空 | 留空=不限制；填了会按预算截断建议量 |
| 启用 LLM 解释 | 开 | 关掉则只有数字，无中文解读（`llm_status=SKIPPED`） |

### 算法与模型

| 字段 | 填 | 说明 |
| --- | --- | --- |
| 算法版本 | `avg-ma-3sigma-v1` | 移动平均 + 3σ 截尾，改错后端会拒 |
| Prompt 版本 | `explain_risk.v1` | 必须与 Python 侧注册名一致，否则解释失败（规则结果仍在） |
| 模型名 | 留空 | 留空用服务端默认（DeepSeek `deepseek-chat`） |

点 **创建并开始分析** → 跳详情页。

## 2. 提交后看什么（详情页上半部）

| 指标 | 正常值 |
| --- | --- |
| 状态 | `PENDING → RUNNING → SUCCEEDED`（演示 5 个 SKU 约 13 秒） |
| SKU 进度 | `5 / 5` |
| LLM 状态 | `SUCCEEDED`；若 `DEGRADED` 表示 LLM 失败但**规则结果仍可用** |
| 风险 SKU 数 | `4` |
| 建议数 | `2`（高库存/滞销的 `Q_base=0`，不产建议） |
| 当前步骤 | `DONE` |

## 3. 结果表怎么读（演示数据实测值）

> 术语格式统一为「英文代码（中文）」，与界面显示一致。

| SKU | 在库 | 日均 D | 安全库存 | Q_base（基础补货量） | 建议补货 | 等级 | 类型 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 910001 | 0.000 | 4.000 | 16.000 | 72.000 | **80.000** | HIGH（高风险） | STOCKOUT（缺货） + LOW_STOCK（低库存） |
| 910002 | 40.000 | 4.000 | 16.000 | 32.000 | **40.000** | MEDIUM（中风险） | LOW_STOCK（低库存） |
| 910003 | 600.000 | 4.000 | 16.000 | 0.000 | —（null） | MEDIUM（中风险） | OVERSTOCK（库存积压） |
| 910004 | 20.000 | 0.000 | 0.000 | 0.000 | —（null） | MEDIUM（中风险） | SLOW_MOVING（滞销） |
| 910005 | 300.000 | 4.000 | 16.000 | 0.000 | —（null） | NONE（无风险） | （无） |

判读要点：

1. **910001**：`on_hand=0` 直接命中 STOCKOUT HIGH；同时 `IP(0) < D×(L+R)=56` 命中 LOW_STOCK。`Q_base = 4×14 + 16 − 0 = 72`，MOQ=10 → 上取到 **80**。
2. **910002**：库存 40 够 10 天（> 交期 7 天，不判缺货），但补货周期 7 天到货前会破 56 的门槛 → LOW_STOCK MEDIUM。`Q_base = 56+16−40 = 32` → MOQ 上取 **40**。
3. **910003**：`600/4 = 150 天` 覆盖，> 90 天 → OVERSTOCK MEDIUM；`Q_base` 为 0，**不产补货建议**（正确）。
4. **910004**：近 28 天销量 0 且有库存 → SLOW_MOVING MEDIUM。注意 `D=0` 时 STOCKOUT/LOW_STOCK/OVERSTOCK 因 `D>0` 前置被跳过，且它有 14 天记录 ⇒ **不是** DATA_INSUFFICIENT。
5. **910005**：75 天覆盖，落在 7~90 天的健康区间 → `NONE`，**不误报**。

### 术语对照（界面显示格式）

| 英文代码 | 中文 | 英文代码 | 中文 |
| --- | --- | --- | --- |
| HIGH | 高风险 | STOCKOUT | 缺货 |
| MEDIUM | 中风险 | LOW_STOCK | 低库存 |
| LOW | 低风险 | OVERSTOCK | 库存积压 |
| NONE | 无风险 | SLOW_MOVING | 滞销 |
| UNKNOWN | 未知 | SALES_ANOMALY | 销售异常 |
| ALL_ACTIVE | 全部活跃 SKU | DATA_INSUFFICIENT | 数据不足 |
| SELECTED_SKUS | 指定 SKU | SUCCEEDED | 已生成 |
| CATEGORY | 按类目 | SKIPPED | 已跳过 |
| GOOD / FAIR / POOR / INSUFFICIENT | 充足 / 一般 / 偏差 / 数据不足 | DEFAULT / SKU / SUPPLIER / POLICY | 全局默认 / SKU 配置 / 供应商配置 / 补货策略配置 |
| INIT_IMPORT | 期初导入 | Q_base | 基础补货量 |
| PURCHASE_INBOUND | 采购入库 | IP | 库存位（在库 + 在途 − 已占用） |
| SALE_OUTBOUND | 销售出库 | SS | 安全库存 |
| COUNT_ADJUST / MANUAL_ADJUST | 盘点调整 / 手工调整 | MOQ | 最小起订量 |
| REVERSAL | 冲销 | CV | 销量波动系数 |

点每行「详情」看完整字段：`CV`、`有效天数/窗口天数`、`交期来源`（SKU 覆盖/默认）、`库存位 IP`、`Q_base` 与 `Q_suggest` 的差别（MOQ/倍数/步长、预算截断）、`数据质量`（有效/缺失天数）、以及 **LLM 解释正文**。

## 4. 常见返回与处置

| 报错/状态 | 原因 | 处置 |
| --- | --- | --- |
| `ANL-7009 参数不合法`（params.ss_ratio） | 比率传了字符串（旧前端 bug） | 已修（R199）；若仍出现请硬刷新页面 |
| `COM-0001 未配置默认仓` | 团队无 `is_default=1` 仓库 | 填仓库 ID `9003`，或执行 `seed-demo-risk.sql` |
| `ANL-7008 数据不足` | 窗口内有效天数 < `min_days` | 调大窗口/改日期区间，确保覆盖销售流水 |
| `DEGRADED` | LLM 调用失败（key/网络/超时） | 规则结果照常可用；查 `service-ai` 日志与 `.env` 的 `LLM_API_KEY` |
| `FAILED` | 分析异常 | 详情页看错误信息，调整范围后重建 |

## 5. 下一步闭环

对 `建议补货量 > 0` 的 SKU（910001、910002）→ 侧栏「采购单」新建采购单 → 到货单确认入库 → 库存回补 → 再跑一次分析看风险消失。
