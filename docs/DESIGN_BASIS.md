# ShopPilot AI 设计基线（Design Basis / Single Source of Truth）

> **文档状态**：已定稿（M0 阶段产出）
> **文档地位**：本文档是 ShopPilot AI 全部设计文档的**单一事实来源（SSOT）**。
> 任何其他文档中出现的**表名、字段名、状态枚举值、错误码、模块名、补货公式符号、角色代码**，
> 必须与本文档完全一致。若出现不一致，以本文档为准，并须提交变更请求（见第 12 节）。
> **适用范围**：M0 设计阶段。M1 起进入编码阶段后，本文档的任何修改都必须走 ADR 流程。

---

## 1. 文档地位与冲突处理规则

### 1.1 文档优先级（从高到低）

| 优先级 | 文档 | 说明 |
| --- | --- | --- |
| P0 | `docs/DESIGN_BASIS.md`（本文档） | 术语、命名、枚举、错误码、公式的唯一定义处 |
| P1 | `docs/decisions/ADR-*.md` | 关键技术决策与其理由，约束架构方向 |
| P2 | `docs/02-database/DATABASE_DESIGN.md` | 数据模型权威定义 |
| P2 | `docs/03-api/API_CONTRACT.md` | 对外 API 权威定义 |
| P2 | `docs/03-api/AI_SERVICE_CONTRACT.md` | Java↔Python 内部契约权威定义 |
| P3 | `docs/01-architecture/*`、`docs/04-ai/*` | 架构与 AI 设计说明 |
| P4 | `docs/00-product/*`、`docs/05-workflows/*` | 产品与流程说明 |
| P5 | `docs/06-development/*` | 工程规范与计划 |

### 1.2 冲突处理

1. 同一事实出现多个版本时，**取优先级更高者**，并**修正低优先级文档**。
2. 若两份 P2 文档冲突（如 API 字段与数据库字段不一致），**以数据库设计为准**，
   因为数据库是最后一道约束防线；同时必须提交 ADR 说明 API 为何需要额外字段。
3. **禁止**在低优先级文档中重新定义高优先级文档已定义的事实（例如在 PRD 中重新定义状态枚举）。
   低优先级文档只能**引用**，例如："状态枚举见 `DESIGN_BASIS.md §9.4`"。
4. 所有被发现的冲突必须记录到 `docs/06-development/DESIGN_REVIEW.md`，即使已经修正。

### 1.3 引用书写规范

- 引用表：`inventory_balances`（表名即引用，不再复述字段）。
- 引用枚举：`ReplenishmentItemStatus`（见 §9.6）。
- 引用公式：`Q_base`（见 §10.2）。
- 引用接口：`POST /api/v1/analysis/runs`（见 `API_CONTRACT.md §x`）。

---

## 2. 项目标识与版本约定

| 项 | 值 |
| --- | --- |
| 项目代号 | `shoppilot-ai` |
| 中文名称 | 电商库存决策与采购协同平台 |
| 首期版本号 | `v0.1.0`（M1 起可用） |
| API 版本 | `/api/v1`（对外）、`/internal/v1`（Java→Python） |
| Java 包根 | `com.shoppilot`（Java 工程根目录 `backend-java/`） |
| Python 包目录 | `app`（工程根 `service-ai/`，分发名 `shoppilot-ai`；导入路径统一写作 `app.*`） |
| 数据库名 | `shoppilot` |
| 默认团队时区 | `Asia/Shanghai` |
| 首期支持语言 | 简体中文（界面与 LLM 输出） |

### 2.1 首期范围（In Scope / Out of Scope）

**In Scope（P0/P1）**

- 多团队 + 团队内五种角色的权限体系。
- 商品 / SKU / 供应商 / 仓库基础资料管理。
- CSV / Excel 导入（商品、SKU、库存快照、历史销售、供应商），含模板、字段映射、预览、校验、错误报告、批次查询。
- 库存余额、库存流水、盘点调整、库存预警。
- 日销售数据（`sales_daily`）。
- 确定性分析与规则引擎：日均销量估算、库存风险识别、补货需求计算。
- LLM 解释层（可降级）+ 结构化输出校验。
- 补货建议状态机 + 人工审核（提交 / 通过 / 驳回）。
- 采购单、采购明细、到货登记（支持部分到货）、库存入库、审计。

**Out of Scope（首期明确不做）**

- 真实电商平台 API 对接（抖店 / 淘宝 / 拼多多）。只预留适配层位。
- 机器学习时间序列预测模型（LSTM / Prophet 等）。
- Multi-Agent 系统、Kubernetes、Kafka / RabbitMQ、微服务拆分、服务注册中心。
- SaaS 计费、多仓库跨仓调拨（数据模型**保留**仓库维度）、多币种。
- Redis（见 ADR-001 §Redis 决策）。
- 正式 Flyway 迁移 SQL（M1 起才生成）。
- 移动端 / 小程序。

---

## 3. 术语表（Terminology）

| 术语 | 英文 / 字段 | 定义 | 计算方式 |
| --- | --- | --- | --- |
| 团队 | Team | 数据隔离的最外层边界，一个团队 = 一个中小电商经营主体 | — |
| 仓库 | Warehouse | 库存物理存放位置。首期每团队一个默认仓库 | — |
| 商品 | Product | 业务概念上的"商品"，承载商品级共享属性（名称、类目、品牌、单位） | — |
| SKU | Sku | 最小可库存单元，隶属一个商品，承载规格与采购属性 | — |
| 现有库存 | `on_hand_qty` | 已实际在仓、可直接出库的实物数量 | 由库存流水累加得出 |
| 在途库存（余额口径） | `inventory_balances.in_transit_qty` | **物化缓存**：该 SKU×仓库当前累计未收数量，用于前端展示与余额表完整性 | Σ(采购明细 `ordered_qty − received_qty`) 中采购单状态 ∈ {`PENDING`, `ORDERED`, `PARTIALLY_RECEIVED`}；由 `purchasing` 在采购单/到货状态变化时通过 `InventoryWriteService` 投影写入 |
| 已承诺库存 | `committed_qty` | 已被业务占用、不能再用于补货判断的数量（如待发货订单锁定） | 首期默认 0，仅保留字段与写入通道 |
| **库存位置** | `IP`（Inventory Position） | 补货判断用的"可支配总量" | `IP = on_hand_qty + effective_in_transit_qty - committed_qty` |
| 有效在途（分析口径） | `effective_in_transit_qty` | 在**业务表**中**不落库**（不是生成列，随日期变化无法持久化），在**快照表 `analysis_items`** 中**落库**以支持结果追溯。在余额口径的基础上再叠加"预计到货日期落在覆盖窗口内"的时间过滤，专门用于补货公式。**包含关系：`effective_in_transit_qty ⊆ inventory_balances.in_transit_qty`** | 见 §10.4 |
| 可用库存 | `available_qty` | 面向用户的展示口径 | `available_qty = on_hand_qty - committed_qty`（**不含**在途） |
| 安全库存 | `SS`（Safety Stock） | 抵御需求波动的缓冲量 | 见 §10.3 |
| 采购交期 | `L`（Lead Time） | 从下单到可入库的天数 | 优先取 SKU 级，缺省取供应商级，再缺省取全局默认 |
| 覆盖天数 | `R`（Review/Extra Coverage Days） | 除交期外额外覆盖的天数 | 策略配置，默认 7 |
| 日均销量 | `D` | 估算的每日销量 | 见 §10.1（移动平均） |
| 基础补货需求 | `Q_base` | 公式算出的理论需求量（未套用采购约束） | 见 §10.2 |
| 建议补货量 | `Q_suggest` | 最终写入补货建议的数量 | 见 §10.5（套用 MOQ 与倍数） |
| 最小采购量 | MOQ | 供应商最小起订量 | 供应商 / SKU / 策略三级配置 |
| 采购倍数 | `order_multiple` | 下单数量必须为其整数倍 | 供应商 / SKU / 策略三级配置 |
| 分析运行 | Analysis Run | 一次完整的分析任务执行实例 | 表 `analysis_runs` |
| 分析明细 | Analysis Item | 一次运行中针对单个 SKU×仓库的计算事实快照（**不可变**） | 表 `analysis_items` |
| 补货建议 | Replenishment Item | 从分析明细派生出的、**有状态、可审核、可采购**的业务对象 | 表 `replenishment_items` |
| 可用性降级 | Degraded | 规则计算成功、但 LLM 解释失败的状态 | `AnalysisRunStatus.DEGRADED` |

### 3.1 关键区分（防混淆，全文遵守）

1. **`analysis_items` ≠ `replenishment_items`**
   - `analysis_items` 是**计算事实快照**，写入后不可修改，用于追溯"当时为什么这么算"。
   - `replenishment_items` 是**业务对象**，有状态机、可被人工修改数量、可被审核、可被采购引用。
   - 一条建议必须引用其来源分析明细（`source_analysis_item_id`），但两者数量可以不同（人工改过）。
2. **`on_hand_qty` ≠ `in_transit_qty` ≠ `committed_qty`**：三者含义互斥，任何公式中不得重复计入同一批货。
3. **`IP` ≠ `available_qty`**：`IP` 用于补货计算（含在途），`available_qty` 用于前端展示（不含在途）。前端**禁止**用 `available_qty` 参与补货判断。
4. **库存余额（`inventory_balances`）≠ 库存流水（`inventory_transactions`）**：余额是当前值，流水是变更历史；余额必须能由流水推导。
5. **补货建议状态（`ReplenishmentItemStatus`）≠ 采购单状态（`PurchaseOrderStatus`）**：前者描述"这个建议被处理到哪一步"，后者描述"这张采购单执行到哪一步"，两者**不得共用字段**。
6. **`inventory_balances.in_transit_qty`（物化）≠ `effective_in_transit_qty`（派生）**：前者是余额表里的缓存字段，服务前端展示与余额口径；后者是分析时的派生过滤值。**补货公式只用后者（§10.4）**，前端展示只用前者。
7. **"已承诺库存"首期恒为 0**：字段与写入通道保留（`committed_qty`），但首期没有任何业务流程会写入非零值。任何设计**不得假设它非零**（例如安全库存公式中不要引入它）。

---

## 4. 命名与编码规范

### 4.1 数据库命名

| 对象 | 规则 | 示例 |
| --- | --- | --- |
| 表名 | 全小写 + 下划线 + **复数** | `purchase_orders`、`analysis_items` |
| 字段名 | 全小写 + 下划线，**单数** | `sku_id`、`created_at` |
| 主键 | 统一 `id`，`BIGINT UNSIGNED AUTO_INCREMENT` | `id` |
| 外键字段 | `<被引用表单数>_id` | `team_id`、`sku_id`、`purchase_order_id` |
| 时间戳 | 后缀 `_at` | `created_at`、`confirmed_at` |
| 业务日期 | 后缀 `_date` | `sale_date`、`expected_arrival_date` |
| 布尔 | 前缀 `is_` / `has_`，类型 `TINYINT(1)` | `is_default` |
| 状态 | 后缀 `_status` | `analysis` 用 `status` 亦可，但必须成对定义枚举 |
| 数量 | 后缀 `_qty` | `on_hand_qty`、`ordered_qty` |
| 金额 | 后缀 `_amount` / `_price` | `total_amount`、`unit_price` |
| 索引 | `idx_<表简写>_<字段>`；唯一 `uk_<表简写>_<字段>`；外键 `fk_...` | `uk_sku_team_code` |
| 布尔/枚举 | 使用 `VARCHAR(32)` 存枚举（可读性优先），不存魔法数字 | `status = 'RUNNING'` |

> **枚举存储决策**：状态字段首期用 `VARCHAR(32)` 存**大写常量字符串**，由应用层枚举 + 数据库 `CHECK` 约束（MySQL 8.0.16+ 支持）双重保证。理由：可读、调试友好、扩展无需改列定义；代价是比 `TINYINT` 多占少量空间（可忽略）。详见 ADR-001 §状态存储。

### 4.2 Java 包与类命名

```
com.shoppilot.<module>.{api, application, domain, infrastructure}
```

| 层 | 职责 | 命名后缀 |
| --- | --- | --- |
| `api` | 对外 REST 控制器、请求/响应 DTO | `*Controller`、`*Request`、`*Response` |
| `application` | 用例编排、事务边界、跨模块协调 | `*AppService`、`*Command`、`*Query` |
| `domain` | 领域模型、业务不变量、领域服务、状态机 | `*DomainService`、`*Policy`、`Entity` |
| `infrastructure` | MyBatis-Plus Mapper、外部客户端、Feign/RestClient | `*Mapper`、`*Client`、`*Repository` |

> 仅 4 层（api / application / domain / infrastructure），**不引入 Repository 接口与实现分离的纯 DDD 形式**，因为该项目无多存储实现需求，接口分离只增加文件数量（详见 ADR-001 §分层粒度）。

### 4.3 前端命名

| 对象 | 规则 | 示例 |
| --- | --- | --- |
| 页面组件 | 大驼峰，`views/<module>/XxxYyy.vue` | `views/inventory/InventoryOverview.vue` |
| 通用组件 | `components/<domain>/XxxYyy.vue` | `components/common/PageTable.vue` |
| API 模块 | `api/<module>.ts`，函数 `camelCase` | `api/analysis.ts` → `createAnalysisRun()` |
| Store | `stores/<module>.ts`，`useXxxStore` | `stores/user.ts` → `useUserStore` |
| 类型 | `types/<module>.ts`，`XxxYyy` 接口 | `types/analysis.ts` → `AnalysisRunItem` |

### 4.4 Python 命名

| 对象 | 规则 |
| --- | --- |
| 模块 | 小写下划线：`app/services/sales_stats.py` |
| Pydantic 模型 | 大驼峰，后缀 `Request`/`Response`/`Item`：`AnalyzeRequest` |
| 规则函数 | 小写下划线，动词开头：`compute_avg_daily_sales()` |
| 常量阈值 | 全大写：`DEFAULT_COVERAGE_DAYS` |

---

## 5. 角色与权限（权威定义）

**角色是团队内角色（team-scoped role）**，不是全局角色。同一用户在不同团队可以有不同角色。

| 角色代码 | 中文 | 权限概述 |
| --- | --- | --- |
| `OWNER` | 团队所有者 | 全部权限，含团队成员管理与团队配置。每团队恰好 1 名（可在未来支持转移） |
| `ADMIN` | 管理员 | 除团队所有者转移外的全部权限，可管理成员（不能移除 OWNER） |
| `OPERATOR` | 运营 | 数据导入、查看库存、创建分析任务、查看/修改补货建议、提交审核；**不能审批** |
| `APPROVER` | 审批人（老板） | OPERATOR 全部权限 + **审批/驳回补货建议** + 创建/取消采购单 |
| `VIEWER` | 只读 | 只读查看业务数据（含分析结果与建议），不能做任何写操作 |

### 5.1 硬规则

1. **团队隔离优先于角色**：任何角色都不能访问其他团队的任何数据，包括 OWNER。跨团队请求一律返回 `TEAM-2001`。
2. **审批权与执行权分离**：创建采购单需要 `APPROVER`。OPERATOR 可以**起草**采购单但不生效（见 `PurchaseOrderStatus.DRAFT`），需 APPROVER 确认。
3. **AI 分析任务**仅 `OPERATOR` / `APPROVER` / `ADMIN` / `OWNER` 可创建；`VIEWER` 只能查看历史结果。
4. 角色权限矩阵的完整定义见 `docs/00-product/USER_ROLES.md`；代码中的授权判定必须以该矩阵为唯一依据。
5. **`MANUAL_ADJUST`（手工库存调整）需要 `ADMIN` 或 `OWNER`**。本条第 2 项与 §9.6 均指向此约束；`OPERATOR` 只能执行 `COUNT_ADJUST`（盘点差异调整，走盘点单流程）。
6. **驳回权归 `APPROVER`**：`ReplenishmentItemStatus.SUGGESTED → REJECTED` 的**直接转换不允许**（驳回必须发生在 `PENDING_REVIEW` 状态之后，见 §9.11）；`APPROVER` 在 `SUGGESTED` 状态的合法动作只有"提交审核"（代运营提交）。

---

## 6. 多团队数据隔离约定（硬约束）

> 这是本项目**最容易出错**的地方，所有文档与代码必须遵守以下 6 条。

1. **所有团队归属的业务表必须含 `team_id BIGINT UNSIGNED NOT NULL`**，且必须存在于：
   `warehouses`、`suppliers`、`products`、`skus`、`replenishment_policies`、`inventory_balances`、
   `inventory_transactions`、`sales_daily`、`import_batches`、`import_row_errors`、`analysis_runs`、
   `analysis_items`、`replenishment_items`、`replenishment_reviews`、`purchase_orders`、
   `purchase_order_items`、`goods_receipts`、`goods_receipt_items`、`audit_logs`、`team_members`。
   **例外**：`users`（跨团队全局）、`teams`（自身即团队）。
   **系统域哨兵**：极少数无团队上下文的记录（如登录失败审计）使用保留值 `team_id = 0` 表示"系统域"。`team_id = 0` **不是合法团队**，任何业务查询都必须排除它；团队 ID 自增序列从 1 开始。
2. **所有业务表的唯一约束必须带 `team_id` 前缀**，例如 `uk_sku_team_code (team_id, sku_code)`。
   禁止在团队业务表上建立不含 `team_id` 的全局唯一约束（`id` 主键除外）。
   **两处已声明的豁免**（其团队归属由**父对象**保证，可直接建立不含 `team_id` 的唯一键）：
   - `uk_ai_run_sku_wh (run_id, sku_id, warehouse_id)`、`uk_repl_run_sku_wh (run_id, sku_id, warehouse_id)`
     —— `run_id` 本身已唯一确定团队；
   - `uk_poi_po_lino (purchase_order_id, line_no)`、`uk_gri_receipt_poi (goods_receipt_id, purchase_order_item_id)`
     —— `purchase_order_id` / `goods_receipt_id` 已唯一确定团队。
   豁免**不等于**可以省略 `team_id` 列：上述表仍然必须有 `team_id` 列并建立以 `team_id` 为最左列的查询索引。
3. **`team_id` 绝不接受客户端传入**，一律由服务端从 JWT 的 `team_id` claim 与请求路径/上下文解析，
   由 `TeamContext`（ThreadLocal / RequestScope）统一提供。
4. **跨表关联写入必须做同团队校验**。例：创建采购明细时，必须校验 `sku.team_id == po.team_id`
   且 `supplier.team_id == po.team_id`。**不能只因为参数带了 `team_id` 就认为隔离已完成**。
   MyBatis-Plus 通过全局租户插件（TenantLineInnerInterceptor）自动追加 `team_id` 条件作为**兜底**，
   但**业务层仍需显式校验**（双保险）。
5. **关联查询必须携带 `team_id`**，不得通过"外键成立"推断隔离成立。
6. **索引**：所有团队业务表的主查询路径索引必须以 `team_id` 作为**最左列**（或与 `id` 组合）。

---

## 7. 模块清单

### 7.1 Java 后端模块（模块化单体，10 个）

| 模块 | 中文 | 拥有表 | 允许依赖 |
| --- | --- | --- | --- |
| `identity` | 身份与团队 | `users`、`teams`、`team_members` | `common` |
| `catalog` | 商品与基础资料 | `warehouses`、`suppliers`、`products`、`skus` | `identity`、`common` |
| `importdata` | 数据导入 | `import_batches`、`import_row_errors` | `catalog`、`inventory`、`sales`、`common` |
| `inventory` | 库存 | `inventory_balances`、`inventory_transactions`、`replenishment_policies` | `catalog`、`common` |
| `sales` | 销售数据 | `sales_daily` | `catalog`、`common` |
| `analysis` | 分析任务与结果 | `analysis_runs`、`analysis_items` | `inventory`、`sales`、`catalog`、`common` |
| `replenishment` | 补货建议与审核 | `replenishment_items`、`replenishment_reviews` | `analysis`、`inventory`、`catalog`、`common` |
| `purchasing` | 采购与到货 | `purchase_orders`、`purchase_order_items`、`goods_receipts`、`goods_receipt_items` | `replenishment`、`inventory`、`catalog`、`common` |
| `audit` | 审计 | `audit_logs` | `common`（**被所有模块调用，但自身不依赖任何业务模块**） |
| `common` | 基础设施 | 无业务表 | **只能依赖框架，禁止依赖任何业务模块** |

**依赖方向（强制，`purchasing → replenishment → analysis → {inventory, sales} → catalog → identity`）**：

```
audit  ←（所有模块可调用）
common ←（所有模块可依赖，common 自身不依赖任何业务模块）
identity ← catalog ← {inventory, sales} ← analysis ← replenishment ← purchasing
                                    ↑
                              importdata（可写入 inventory / sales / catalog）
```

**禁止的反向依赖**（代码审查必查）：
- `catalog` 依赖 `inventory` / `analysis` / `purchasing`（❌）
- `analysis` 直接读 `purchasing` 表（❌ 需要采购数据时应通过 `inventory` 提供的在途口径）
- `inventory` 依赖 `purchasing`（❌ 入库由 `purchasing` 调用 `inventory` 的 `InventoryWriteService`，方向不能反）
- 任何模块依赖 `common` 中的业务逻辑（❌ `common` 只能有工具、异常基类、上下文、统一响应等）
- 模块间通过"直接访问对方 Mapper / 表"耦合（❌ 只能通过对方的 `application` 层服务接口）

### 7.2 Python AI 服务模块（8 个）

| 模块 | 路径 | 职责 |
| --- | --- | --- |
| API 层 | `app/api/` | FastAPI 路由、请求/响应 Pydantic 模型、错误转换 |
| Schema 层 | `app/schemas/` | 全部输入/输出数据结构定义与校验 |
| 编排层 | `app/orchestration/` | 分析步骤编排、步骤状态记录、超时与重试、降级策略 |
| 数据校验层 | `app/validation/` | 输入数据质量检查（缺口、异常值、样本不足） |
| 统计模块 | `app/analytics/sales_stats.py` | 日均销量估算、波动率、异常检测 |
| 风险规则模块 | `app/rules/risk_rules.py` | 缺货/低库存/滞销/异常/数据不足判定 |
| 补货计算模块 | `app/rules/replenishment.py` | `Q_base` → `Q_suggest`（MOQ / 倍数 / 预算） |
| LLM 模块 | `app/llm/` | Prompt 加载、模型调用适配、输出 Schema 校验、降级 |
| 基础设施 | `app/core/` | 配置、日志、Trace、外部 HTTP 客户端、异常 |

> **首期不引入 LangGraph**，改用**显式顺序编排 + 步骤记录**（见 ADR-001 §LangGraph）。理由见 `docs/04-ai/AI_ARCHITECTURE.md §Workflow`。

---

## 8. 数据表清单（权威，共 22 张）

| # | 表名 | 中文 | 模块 | 含 `team_id` | 主键 | 关键唯一约束 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `users` | 用户 | identity | ❌（全局） | id | `uk_users_username`、`uk_users_email` |
| 2 | `teams` | 团队 | identity | —（自身） | id | `uk_teams_code` |
| 3 | `team_members` | 团队成员 | identity | ✅ | id | `uk_tm_team_user (team_id,user_id)` |
| 4 | `warehouses` | 仓库 | catalog | ✅ | id | `uk_wh_team_code (team_id,code)` |
| 5 | `suppliers` | 供应商 | catalog | ✅ | id | `uk_sup_team_code (team_id,code)` |
| 6 | `products` | 商品 | catalog | ✅ | id | `uk_prod_team_code (team_id,product_code)` |
| 7 | `skus` | SKU | catalog | ✅ | id | `uk_sku_team_code (team_id,sku_code)` |
| 8 | `replenishment_policies` | 补货策略参数 | inventory | ✅ | id | `uk_pol_team_sku_wh (team_id,sku_id,warehouse_id)` |
| 9 | `inventory_balances` | 库存余额 | inventory | ✅ | id | `uk_invbal_team_wh_sku (team_id,warehouse_id,sku_id)` |
| 10 | `inventory_transactions` | 库存流水 | inventory | ✅ | id | `uk_invtxn_team_idem (team_id,idempotency_key)`、`uk_invtxn_team_reversed (team_id,reversed_txn_id)`（`reversed_txn_id` 可空且唯一 → **一笔流水只能被冲销一次**） |
| 11 | `sales_daily` | 日销售 | sales | ✅ | id | `uk_sales_team_wh_sku_date (team_id,warehouse_id,sku_id,sale_date)` |
| 12 | `import_batches` | 导入批次 | importdata | ✅ | id | `uk_imp_team_hash_biz (team_id,file_hash,biz_type)` |
| 13 | `import_row_errors` | 导入行错误 | importdata | ✅ | id | 无（普通索引） |
| 14 | `analysis_runs` | 分析任务 | analysis | ✅ | id | `uk_run_team_no (team_id,run_no)`、`uk_run_team_idem` |
| 15 | `analysis_items` | 分析明细（快照） | analysis | ✅ | id | `uk_ai_run_sku_wh (run_id,sku_id,warehouse_id)` |
| 16 | `replenishment_items` | 补货建议 | replenishment | ✅ | id | `uk_repl_run_sku_wh (run_id,sku_id,warehouse_id)` |
| 17 | `replenishment_reviews` | 审核记录 | replenishment | ✅ | id | 无（普通索引） |
| 18 | `purchase_orders` | 采购单 | purchasing | ✅ | id | `uk_po_team_no (team_id,po_no)`、`uk_po_team_idem` |
| 19 | `purchase_order_items` | 采购明细 | purchasing | ✅ | id | `uk_poi_po_lino (purchase_order_id,line_no)` |
| 20 | `goods_receipts` | 到货单 | purchasing | ✅ | id | `uk_gr_team_no (team_id,receipt_no)`、`uk_gr_team_idem` |
| 21 | `goods_receipt_items` | 到货明细 | purchasing | ✅ | id | `uk_gri_receipt_poi (goods_receipt_id,purchase_order_item_id)` |
| 22 | `audit_logs` | 审计日志 | audit | ✅ | id | 无（普通索引，仅追加） |

> **相对任务书清单的三处调整**（均在 `DATABASE_DESIGN.md §变更说明` 中给出理由）：
> 1. **新增 `replenishment_policies`**：`L`、`R`、`SS`、MOQ、`order_multiple` 等参数必须有稳定归属，
>    否则只能散落在 SKU 字段里或被 LLM 编造。
> 2. **新增 `analysis_items`**（任务书清单中没有）：把"计算事实快照（不可变）"与"业务建议（有状态）"分离。
> 3. **不新增独立"销量预测结果表"**：预测结果即为 `analysis_items` 字段，避免一次数据两处存放。

> **导入幂等的物理实现（对 §9.5 与 `uk_imp_team_hash_biz` 的澄清）**：
> 既要"同文件不重复入库"，又要"允许用户强制重导"，二者不能靠一个非空唯一列同时实现。约定如下：
> - `import_batches` 上建立可空列 `dedupe_hash VARCHAR(64) NULL`（值为文件内容 SHA-256），唯一键为
>   `uk_imp_team_hash_biz (team_id, dedupe_hash, biz_type)`——**MySQL 唯一索引允许多个 NULL**，
>   因此"释放"过的批次可以共存。
> - 批次处于"未完成/未强制重导"状态时 `dedupe_hash` 有值；用户调用
>   `POST /api/v1/import/batches/{id}/reimport` 时，把旧批次的 `dedupe_hash` 置为 `NULL`（记录
>   `superseded_by_batch_id`），新批次占用该哈希。
> - **`FORCE_REIMPORT` 是一个动作（接口名），不是 `ImportBatchStatus` 的枚举值**。§9.5 中
>   `DUPLICATE_REJECTED` 是状态，`FORCE_REIMPORT` 是用户在该状态下可以发起的动作。

> **条目级 LLM 结果的落库位置**：`analysis_items` 除 `llm_explanation` 外，另设
> `llm_detail_json`（保存该条目的 `llm_status`、置信度、不确定项、检查清单），
> 使条目级 LLM 状态有明确归属，而不是塞进 `analysis_runs` 的汇总字段。

---

## 9. 状态枚举全集（权威定义）

> 所有枚举**只在此处定义一次**。其他文档引用格式：`XxxEnum.VALUE`。
> 枚举值为**大写字符串**，直接存入数据库。

### 9.1 `UserStatus`（`users.status`）

| 值 | 含义 | 合法转换 |
| --- | --- | --- |
| `ACTIVE` | 正常 | → `DISABLED` |
| `DISABLED` | 已禁用，不可登录 | → `ACTIVE` |

### 9.2 `TeamStatus`（`teams.status`）

| 值 | 含义 |
| --- | --- |
| `ACTIVE` | 正常 |
| `DISABLED` | 停用，团队内所有接口拒绝且返回 `TEAM-2002` |

### 9.3 `TeamRole`（`team_members.role`）

`OWNER` / `ADMIN` / `OPERATOR` / `APPROVER` / `VIEWER`（见 §5）。
`team_members.status`：`ACTIVE`（生效）、`REMOVED`（已移出，保留历史）。

### 9.4 主数据状态 `MasterDataStatus`（`warehouses`/`suppliers`/`products`/`skus`.status）

| 值 | 含义 | 约束 |
| --- | --- | --- |
| `ACTIVE` | 正常可用 | 可参与导入校验、分析、采购 |
| `INACTIVE` | 已停用 | **不参与新的分析任务与采购**，但**历史数据仍可查询**（见 §5.3-B 要求） |

### 9.5 `ImportBatchStatus`（`import_batches.status`）

| 值 | 含义 | 下一步 |
| --- | --- | --- |
| `UPLOADED` | 文件已接收，已算哈希，尚未解析 | → `PARSED` / `FAILED` |
| `PARSED` | 已解析出表头与预览数据，等待字段映射 | → `MAPPED` |
| `MAPPED` | 字段映射已确认 | → `VALIDATED` |
| `VALIDATED` | 全量校验完成，产出错误报告，等待用户确认 | → `IMPORTING` / `CANCELLED` |
| `IMPORTING` | 正在落库 | → `COMPLETED` / `PARTIAL_SUCCESS` / `FAILED` |
| `COMPLETED` | 全部行成功 | 终态 |
| `PARTIAL_SUCCESS` | 部分行成功、部分失败 | 终态（可下载错误报告后重新导入） |
| `FAILED` | 全部失败或批次级异常 | 终态 |
| `CANCELLED` | 用户取消 | 终态 |
| `DUPLICATE_REJECTED` | **留痕记录**：该次上传被判定为重复（同团队 + 同内容 SHA-256 + 同 `biz_type` 已存在批次），未产生任何可操作的数据导入，响应中返回命中的既有批次 ID（错误码 `IMP-4004`） | 终态。用户在**既有批次**上调用 `POST /api/v1/import/batches/{id}/reimport`（动作 `FORCE_REIMPORT`）即可强制重导 |

> 幂等规则详见 `DATABASE_CONSTRAINTS.md §导入幂等`。

### 9.6 `InventoryTxnType`（`inventory_transactions.txn_type`）

| 值 | 含义 | `qty_delta` 方向 |
| --- | --- | --- |
| `INIT_IMPORT` | 初始库存导入 | + |
| `PURCHASE_INBOUND` | 采购到货入库 | + |
| `SALE_OUTBOUND` | 销售出库 | − |
| `COUNT_ADJUST` | 盘点调整 | ± |
| `MANUAL_ADJUST` | 手工调整（需 `ADMIN`+） | ± |
| `REVERSAL` | 冲销（回滚某笔流水） | ∓（反向） |

### 9.7 `AnalysisRunStatus`（`analysis_runs.status`）

| 值 | 含义 | 说明 |
| --- | --- | --- |
| `PENDING` | 已创建，未开始 | — |
| `RUNNING` | 执行中 | 附带 `progress`（0-100）与 `current_step` |
| `SUCCEEDED` | 全部成功（含 LLM 解释） | 终态 |
| `DEGRADED` | **规则计算成功但 LLM 解释失败** | 终态，业务可用（关键设计，见 ADR-001） |
| `FAILED` | 规则计算阶段失败 | 终态，附 `error_code` / `error_message` |
| `TIMEOUT` | 超时（`timeout_at` 已过） | 终态，可重试（生成新 run） |
| `CANCELLED` | 用户主动取消 | 终态 |

**合法转换**：`PENDING → RUNNING → {SUCCEEDED | DEGRADED | FAILED | TIMEOUT | CANCELLED}`；终态不可再变。

### 9.8 `LlmStatus`（`analysis_runs.llm_status`）

`NOT_REQUESTED` / `PENDING` / `SUCCEEDED` / `FAILED` / `TIMEOUT` / `VALIDATION_FAILED` / `SKIPPED`

> `SKIPPED` = 分析配置关闭了 LLM 解释，或前置步骤判定无需解释（如全部 SKU 数据不足）。

### 9.9 `RiskLevel`（`analysis_items.risk_level`）

| 值 | 含义 |
| --- | --- |
| `NONE` | 无风险 |
| `LOW` | 低风险 |
| `MEDIUM` | 中风险 |
| `HIGH` | 高风险 |
| `UNKNOWN` | 数据不足，无法判定 |

### 9.10 `RiskType`（`analysis_items.risk_types`，JSON 数组）

`STOCKOUT`（缺货风险）/ `LOW_STOCK`（低库存）/ `OVERSTOCK`（库存过高）/ `SLOW_MOVING`（滞销）/ `SALES_ANOMALY`（销量异常）/ `DATA_INSUFFICIENT`（数据不足）

### 9.11 `ReplenishmentItemStatus`（`replenishment_items.status`）

| 值 | 含义 | 合法转换 |
| --- | --- | --- |
| `SUGGESTED` | 系统生成，待运营处理 | → `PENDING_REVIEW`、`SUPERSEDED`、`CANCELLED`（**不允许直接 `REJECTED`**，见下方说明） |
| `PENDING_REVIEW` | 运营已提交，待审批 | → `APPROVED`、`REJECTED`、`SUGGESTED`（撤回）、`SUPERSEDED` |
| `APPROVED` | 已审批通过，可转采购 | → `CONVERTED`、`CANCELLED` |
| `CONVERTED` | 已生成采购明细 | 终态 |
| `REJECTED` | 已驳回（需填 `review_comment`） | 终态 |
| `SUPERSEDED` | 被更新一轮的分析结果取代（该 SKU 有更新 run 的建议） | 终态 |
| `CANCELLED` | 运营主动作废 | 终态 |

> §9.11 补充规则（**已裁定，非待决策**）：
> 1. **驳回必须经审批**：`SUGGESTED` 不能直接跳到 `REJECTED`。驳回只能由 `APPROVER` 把
>    `PENDING_REVIEW` → `REJECTED`。运营若认为建议无意义，正确动作是 `CANCELLED`（作废）。
> 2. **`SUPERSEDED` 只能作用于 `SUGGESTED` 与 `PENDING_REVIEW`**。`APPROVED` / `CONVERTED` / `REJECTED`
>    均为"已产生业务后果"的状态，新一轮分析不得改动它们（这是"不得覆盖已审批/已执行的采购业务"的落地方式）。
> 3. **`APPROVED → SUPERSEDED` 是非法转换**；`APPROVED` 只能走向 `CONVERTED`（被采购引用）或 `CANCELLED`。
> 4. **`CONVERTED` 是终态**，且只有 `purchasing` 模块创建采购明细时可以触发该转换。

### 9.12 `ReviewAction`（`replenishment_reviews.action`）

`SUBMIT` / `WITHDRAW` / `APPROVE` / `REJECT` / `CANCEL`

### 9.13 `PurchaseOrderStatus`（`purchase_orders.status`）

| 值 | 含义 | 合法转换 |
| --- | --- | --- |
| `DRAFT` | 草稿（含由补货建议生成但未确认） | → `PENDING`、`CANCELLED` |
| `PENDING` | 待采购（已确认，未向供应商下单） | → `ORDERED`、`CANCELLED` |
| `ORDERED` | 采购中（已下单，等到货） | → `PARTIALLY_RECEIVED`、`RECEIVED`、`CANCELLED` |
| `PARTIALLY_RECEIVED` | 部分到货 | → `PARTIALLY_RECEIVED`、`RECEIVED`、`CANCELLED` |
| `RECEIVED` | 全部到货 | 终态 |
| `CANCELLED` | 已取消 | 终态 |

> §9.13 补充规则（**已裁定**）：
> 1. **除冲销外，状态只能单向向前**：`DRAFT → PENDING → ORDERED → PARTIALLY_RECEIVED → RECEIVED`。
> 2. **唯一允许回退的场景是到货冲销**：当一张已 `CONFIRMED` 的到货单被冲销（见 §9.14）时，
>    采购单状态需按明细的剩余累计收货量**重新推导**，允许从 `RECEIVED` 回到 `PARTIALLY_RECEIVED`，
>    或从 `PARTIALLY_RECEIVED` 回到 `ORDERED`。该回退必须在审计日志中显式记录原因。
> 3. **采购单状态由明细推导，不由用户直接设置**：用户只能触发 `DRAFT→PENDING`、`→ORDERED`、`CANCELLED`；
>    `PARTIALLY_RECEIVED` / `RECEIVED` 由到货确认后自动推导。

### 9.14 `GoodsReceiptStatus`（`goods_receipts.status`）

| 值 | 含义 |
| --- | --- |
| `DRAFT` | 已录入未确认（不产生库存流水） |
| `CONFIRMED` | 已确认，已产生 `PURCHASE_INBOUND` 流水与库存变更 |
| `REVERSED` | 已冲销（针对曾 `CONFIRMED` 的到货单：已生成 `REVERSAL` 流水，库存已回退，采购单状态已重算） |
| `CANCELLED` | 已作废（**仅 `DRAFT` 可直接作废**；`CONFIRMED` 必须走冲销 → `REVERSED`，不允许直接 `CANCELLED`） |

### 9.15 `ImportBizType`（`import_batches.biz_type`）

`PRODUCT` / `SKU` / `SUPPLIER` / `WAREHOUSE` / `INVENTORY_SNAPSHOT` / `SALES_DAILY`

### 9.16 `AnalysisScopeType`（`analysis_runs.scope_type`）

`ALL_ACTIVE`（全部启用 SKU）/ `SELECTED_SKUS` / `CATEGORY`（按商品类目）

### 9.17 辅助枚举与常量（补齐，供各文档统一引用）

以下枚举/常量在各设计文档中被引用，在此统一固化，避免各文档自行定义：

| 名称 | 用途 | 取值 |
| --- | --- | --- |
| `LeadTimeSource` | 交期取值来源（用于可解释性标记） | `SKU`（SKU 级）/ `SUPPLIER`（供应商级）/ `POLICY`（策略表覆盖）/ `DEFAULT`（全局默认，需在结果中提示） |
| `DataQualityGrade` | 分析输入数据质量分级 | `GOOD` / `FAIR` / `POOR` / `INSUFFICIENT`（判定规则见 `ANALYSIS_CONTRACT.md`） |
| `SalesSource` | `sales_daily.source` | `IMPORT`（导入）/ `MANUAL`（手工录入）/ `PLATFORM`（未来平台接入，首期不产生） |
| `InventoryBizType` | `inventory_transactions.biz_type` | `IMPORT_BATCH` / `PURCHASE_ORDER` / `GOODS_RECEIPT` / `SALES_IMPORT` / `MANUAL_ADJUSTMENT` / `STOCK_COUNT` / `REVERSAL` |
| `PurchaseOrderSource` | `purchase_orders.source` | `REPLENISHMENT`（由补货建议生成）/ `MANUAL`（手工创建） |
| `ImportRowErrorLevel` | `import_row_errors.error_level` | `ERROR`（阻止该行入库）/ `WARNING`（入库但提示，如库存快照累加） |
| `AuditAction` | `audit_logs.action` 常量清单 | 至少包含：`AUTH_LOGIN`、`AUTH_LOGIN_FAILED`、`TEAM_MEMBER_ADD`、`TEAM_MEMBER_ROLE_CHANGE`、`TEAM_MEMBER_REMOVE`、`CATALOG_PRODUCT_CREATE_UPDATE`、`CATALOG_SKU_CREATE_UPDATE`、`CATALOG_SUPPLIER_CREATE_UPDATE`、`CATALOG_WAREHOUSE_CREATE_UPDATE`、`IMPORT_BATCH_CONFIRM`、`IMPORT_BATCH_FORCE_REIMPORT`、`INVENTORY_ADJUST`、`INVENTORY_REVERSE`、`ANALYSIS_RUN_CREATE`、`ANALYSIS_RUN_CANCEL`、`REPLENISHMENT_ITEM_EDIT`、`REPLENISHMENT_SUBMIT`、`REPLENISHMENT_WITHDRAW`、`REPLENISHMENT_APPROVE`、`REPLENISHMENT_REJECT`、`REPLENISHMENT_CANCEL`、`PURCHASE_ORDER_CREATE`、`PURCHASE_ORDER_CONFIRM`、`PURCHASE_ORDER_ORDERED`、`PURCHASE_ORDER_CANCEL`、`GOODS_RECEIPT_CONFIRM`、`GOODS_RECEIPT_REVERSE`、`GOODS_RECEIPT_CANCEL` |
| `RuleCode` | 风险规则代码（§11） | `R-STOCKOUT` / `R-LOWSTOCK` / `R-OVERSTOCK` / `R-SLOWMOVING` / `R-SALESANOMALY` / `R-DATAINSUFFICIENT` |

---

## 10. 补货公式与默认参数（权威）

### 10.1 日均销量估算 `D`

首期采用**截尾移动平均 + 数据质量判定**：

```
输入：某 SKU×仓库 最近 N 天的 sales_daily（默认 N = 28）
1. 取有效销售天数 n = 窗口内有销售记录（含 0 销量记录）的天数
2. 若 n < MIN_DAYS（默认 7）→ 标记 DATA_INSUFFICIENT，D = null，不产出补货建议
3. 计算窗口内 qty 的算术平均：D_raw = sum(qty) / n
4. 异常处理：对窗口内 qty 做 3σ 截尾（剔除超出 mean ± 3σ 的极端日，最多截尾 2 天）
5. D = round(截尾后均值, 3)
6. 波动率 CV = std(qty) / mean(qty)（mean=0 时 CV 记为 null）
```

- `D` 的**算法版本**记录在 `analysis_runs.algorithm_version`（如 `avg-ma-3sigma-v1`）。
- 首期**不做**季节性、促销、趋势外推。→ 待决策 `D-03`。

### 10.2 基础补货需求

```
Q_base = max(0, D × (L + R) + SS − IP)
```

其中（明确字段映射，禁止重复计数）：

| 符号 | 来源字段 | 计算 |
| --- | --- | --- |
| `D` | 计算值（§10.1） | — |
| `L` | `replenishment_policies.lead_time_days` → `skus.default_lead_time_days` → `suppliers.lead_time_days` → 全局默认 `DEFAULT_LEAD_TIME_DAYS = 7` | 取第一个非空值 |
| `R` | `replenishment_policies.coverage_days`，默认 `DEFAULT_COVERAGE_DAYS = 7` | — |
| `SS` | 见 §10.3 | — |
| `IP` | `on_hand_qty + effective_in_transit_qty − committed_qty`（全部取自 `inventory_balances`） | — |
| `effective_in_transit_qty` | 见 §10.4 | — |

### 10.3 安全库存 `SS`

首期使用**简化服务水平法（可直接解释、可测试）**：

```
SS = D × SS_FACTOR
SS_FACTOR 默认 = ceil(L × SAFETY_RATIO)，SAFETY_RATIO 默认 0.5（即约半个交期的量）
可被 replenishment_policies.safety_stock_qty 直接覆盖（绝对量优先）
```

- 若 `CV > CV_HIGH`（默认 1.0，波动大），`SS_FACTOR` 上浮 50%，并在结果中标注 `volatility_adjusted = true`。
- → 待决策 `D-04`：是否改用 `Z × σ × sqrt(L)` 的标准安全库存公式（更严谨但需服务水平参数）。

### 10.4 有效在途 `effective_in_transit_qty`

```
effective_in_transit_qty = Σ(poi.ordered_qty − poi.received_qty)
  其中 poi 满足：po.status ∈ {PENDING, ORDERED, PARTIALLY_RECEIVED}
              且 (po.expected_arrival_date IS NULL OR po.expected_arrival_date ≤ today + L + R)
```

- 只在覆盖窗口内计入在途，避免"远期在途"虚增 `IP` 导致补货不足。
- `expected_arrival_date` 为空时**保守计入**（视作窗口内）。

### 10.5 建议补货量 `Q_suggest`（套用采购约束）

```
若 Q_base <= 0                     → Q_suggest = 0（不生成补货建议，只记录风险）
基础量 = max(Q_base, MOQ)           MOQ 来自 policies → skus → suppliers → 默认 1
若 order_multiple > 1:
     Q_suggest = ceil(基础量 / order_multiple) × order_multiple
否则 Q_suggest = ceil(基础量)（数量取整到 1 个单位，SKU 最小起订精度见 policies.qty_step）
若 Q_suggest > budget_limit_remaining → 截断为 budget_limit_remaining（并标注 truncated_by_budget = true）
```

- `MOQ` / `order_multiple` 三级来源：`replenishment_policies` → `skus` → `suppliers`。
- 预算限制首期**为可选项**，默认不启用（`analysis_runs.params_json.budget_limit` 为空即不限制）。

### 10.6 参数默认值总表（可配置项）

| 参数 | 默认值 | 配置位置 |
| --- | --- | --- |
| `N`（统计窗口天数） | 28 | 分析请求 `params.window_days` |
| `MIN_DAYS`（最少有效天数） | 7 | 分析请求 / 服务默认 |
| `DEFAULT_LEAD_TIME_DAYS` | 7 | 服务默认（M1 起为配置项） |
| `DEFAULT_COVERAGE_DAYS`（R） | 7 | 分析请求 / 策略表 |
| `SAFETY_RATIO` | 0.5 | 服务默认 |
| `CV_HIGH` | 1.0 | 服务默认 |
| `SLOW_MOVING_DAYS` | 30 | 服务默认（滞销判定窗口） |
| `OVERSTOCK_DAYS` | 90 | 服务默认（过高库存判定） |
| `ANOMALY_SIGMA` | 2.0 | 服务默认（异常检测） |
| `MAX_ANALYSIS_SKUS` | 2000 | 服务默认（单次运行上限） |

---

## 11. 库存风险规则（首期可执行规则集）

> 规则引擎为**确定性**计算，输出写入 `analysis_items`。**LLM 不参与任何判定**。

| 规则代码 | 名称 | 输入 | 判断逻辑 | 输出 |
| --- | --- | --- | --- | --- |
| `R-STOCKOUT` | 缺货风险 | `on_hand_qty`, `D` | `D > 0 AND on_hand_qty <= 0` → `HIGH`；`on_hand_qty / D <= 2` 天 → `HIGH`；`<= L` → `MEDIUM` | `risk_types += STOCKOUT` |
| `R-LOWSTOCK` | 低库存 | `IP`, `D`, `L`, `R` | `D > 0` 且 `IP < D × (L + R)` → `MEDIUM`。**与 `R-STOCKOUT` 可以同时命中**（`risk_types` 是数组，不是单值；同时命中时 `risk_level` 取较高者） | `risk_types += LOW_STOCK` |
| `R-OVERSTOCK` | 库存过高 | `on_hand_qty`, `D` | `D > 0 AND on_hand_qty / D > OVERSTOCK_DAYS` → `MEDIUM`；`> 2 × OVERSTOCK_DAYS` → `HIGH` | `OVERSTOCK` |
| `R-SLOWMOVING` | 滞销 | 窗口销量、上架天数 | 近 `SLOW_MOVING_DAYS` 天总销量 = 0 且 `on_hand_qty > 0` → `MEDIUM` | `SLOW_MOVING` |
| `R-SALESANOMALY` | 销量异常 | 近 7 天 vs 前 21 天 | `|近7天日均 − 前21天日均| > ANOMALY_SIGMA × 前21天标准差` 且变化率 > 50% → `LOW`（仅提示） | `SALES_ANOMALY` |
| `R-DATAINSUFFICIENT` | 数据不足 | `n < MIN_DAYS` | → `UNKNOWN`，`D = null`，**不生成补货建议**，仅记录 | `DATA_INSUFFICIENT` |

### 11.1 风险等级合成

`risk_level = MAX(各规则命中等级)`，`UNKNOWN` 仅当**所有**规则都因数据不足无法判定时使用。

### 11.2 首期不支持的业务场景（明确提示数据不足，不猜测）

新商品无历史销量、供应商交期未知（用默认值并在结果中标注 `lead_time_source = DEFAULT`）、
促销期销量异常（只提示 `SALES_ANOMALY`，不做促销修正）、跨仓调拨建议、多供应商比价。

### 11.3 每个规则必须有测试用例

测试用例清单见 `docs/06-development/TEST_STRATEGY.md §规则引擎测试`，
每条规则至少 3 个用例（命中边界 / 未命中 / 数据不足）。

---

## 12. 错误码规范

### 12.1 格式

```
<DOMAIN>-<4位数字>
```

- `DOMAIN` 为 3 字母业务域缩写（见下表）。
- 用户可见错误码必须是**稳定常量**，不得随实现变化。
- 统一错误响应体见 `API_CONTRACT.md §统一响应结构`，完整错误码表见 `ERROR_CODES.md`。

### 12.2 业务域分段

| 前缀 | 业务域 | 号码段 |
| --- | --- | --- |
| `COM` | 通用 / 参数校验 / 系统 | 0001–0999 |
| `AUTH` | 认证与登录 | 1001–1099 |
| `TEAM` | 团队与权限隔离 | 2001–2099 |
| `CAT` | 商品 / SKU / 供应商 / 仓库 | 3001–3099 |
| `IMP` | 数据导入 | 4001–4099 |
| `INV` | 库存 | 5001–5099 |
| `SAL` | 销售数据 | 6001–6099 |
| `ANL` | 分析任务 | 7001–7099 |
| `RPL` | 补货建议与审核 | 8001–8099 |
| `PO` | 采购与到货 | 9001–9099 |

### 12.3 首批必须固化的错误码（示例，完整表见 `ERROR_CODES.md`）

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| `COM-0001` | 400 | 请求参数校验失败 |
| `COM-0002` | 500 | 系统内部错误 |
| `COM-0009` | 409 | 幂等键冲突但请求体不一致 |
| `AUTH-1001` | 401 | 未登录或 Token 失效 |
| `AUTH-1002` | 401 | 用户名或密码错误（**不区分**用户是否存在，防枚举） |
| `AUTH-1003` | 403 | 账号已禁用 |
| `TEAM-2001` | 403 | 无权访问该团队数据（跨团队越权） |
| `TEAM-2002` | 403 | 团队已停用 |
| `TEAM-2003` | 403 | 当前角色无权执行该操作 |
| `CAT-3001` | 404 | 商品 / SKU / 供应商不存在 |
| `CAT-3002` | 409 | 编码在同一团队内重复 |
| `IMP-4001` | 400 | 文件类型或大小不合法 |
| `IMP-4002` | 400 | 文件解析失败（编码 / 格式） |
| `IMP-4003` | 400 | 必填列缺失 |
| `IMP-4004` | 409 | 重复导入（同文件哈希批次已存在） |
| `IMP-4005` | 422 | 批次状态不允许该操作 |
| `INV-5001` | 409 | 库存不足，无法出库 |
| `INV-5002` | 409 | 并发更新冲突（乐观锁失败） |
| `INV-5003` | 409 | 幂等键重复且内容不一致 |
| `ANL-7001` | 409 | 同一 SKU 范围已有运行中的分析任务 |
| `ANL-7002` | 502 | AI 服务不可用 |
| `ANL-7003` | 504 | AI 服务超时 |
| `ANL-7004` | 422 | AI 返回结果未通过 Schema 校验 |
| `RPL-8001` | 422 | 当前状态不允许该操作 |
| `RPL-8002` | 409 | 重复提交 / 重复审批 |
| `RPL-8003` | 409 | 建议已被修改，请刷新后重试（乐观锁） |
| `PO-9001` | 409 | 该补货建议已生成采购单 |
| `PO-9002` | 409 | 到货数量超过待收数量 |
| `PO-9003` | 409 | 采购单状态不允许该操作 |
| `AUTH-1004` | 403 | 无权访问该资源（角色权限不足，与 `TEAM-2003` 语义重叠时优先返回 `TEAM-2003`） |
| `IMP-4006` | 200 | **WARNING**：导入命中已有数据，已按既定语义处理（`INVENTORY_SNAPSHOT` = 增量累加；`SALES_DAILY` = 覆盖）。**不中断导入**，仅在导入结果与 `import_row_errors`（`error_level = WARNING`）中提示 |
| `IMP-4007` | 404 | 导入批次不存在（ID 无效或跨团队） |
| `IMP-4013` | 400 | 文件行数超过首期上限（20 万行），请拆分后导入 |
| `IMP-4014` | 200 | **WARNING**：数据质量告警 —— 同一 SKU 的日销售日期缺口比例超过阈值（默认 30%） |
| `INV-5006` | 409 | 出库/调整将导致负库存 |
| `INV-5009` | 409 | 该流水已被冲销（一笔流水只能冲销一次） |
| `RPL-8007` | 403 | 审批人不能与提交人相同（仅在启用四眼原则 `D-09` 时触发） |
| `ANL-7005` | 409 | 分析任务已处于终态，不能取消 |
| `RPL-8004` | 409 | 该建议已被采购单引用，不能作废 |
| `PO-9004` | 404 | 采购单不存在（`purchase_order_id` 无效或跨团队） |

> **AI 服务内部错误域**：Python 内部接口使用**独立前缀** `AI-xxxx`，仅用于 Java ↔ Python 内部通信，与 `§12.2` 的对外业务域不重叠。号码段划分：
>
> - `AI-0xxx` 内部令牌与鉴权
> - `AI-1xxx` 请求 Schema 校验
> - `AI-2xxx` 输入数据（SKU 数据缺失 / 窗口数据不足）
> - `AI-3xxx` 规则引擎执行
> - `AI-4xxx` 补货计算
> - `AI-5xxx` LLM 调用与输出
> - `AI-6xxx` 任务状态
> - `AI-7xxx` 并发与规模限制
> - `AI-9xxx` 未预期内部错误
>
> **AI 域错误码的权威定义见 `ERROR_CODES.md §2.11`**（逐码含义与「Java 映射」列均以该表为准，本节不复制；转换时必须按该列**逐码执行**，不得自行归纳）。
>
> **两条不变量**：
>
> 1. **不外泄**：Java **不得**把 `AI-xxxx` 透传给前端，必须先转换为对外错误码或 run 状态；对用户可见信息（`detail`、`stage`、`sku_id`、异常栈等）必须脱敏。
> 2. **降级不可破**：`AI-5xxx`（LLM 类）**一律不置 run `FAILED`**，只置 `llm_status` 并使 run 转 `DEGRADED`；规则计算结果必须保留。

---

## 13. Java ↔ Python 内部契约骨架

> 完整契约见 `docs/03-api/AI_SERVICE_CONTRACT.md`。

### 13.1 调用方式

- **同步**：`POST /internal/v1/analysis:validate`（轻量数据质量预检，超时 5s）。
- **异步任务**：`POST /internal/v1/analysis/runs`（创建并**同步返回 202 + run_id**，
  实际执行在 Python 内部以 `BackgroundTasks`/线程池进行，Java 通过轮询状态接口获取进度）。
  > 首期**不引入消息队列**，用 HTTP + 状态轮询实现（理由见 ADR-001 §异步任务）。
- **状态查询**：`GET /internal/v1/analysis/runs/{analysis_run_id}`。
- **取消**：`POST /internal/v1/analysis/runs/{analysis_run_id}:cancel`。

### 13.2 职责归属（关键，禁止双写状态）

| 职责 | 归属 | 说明 |
| --- | --- | --- |
| 生成 `analysis_run_id`（业务 ID） | **Java** | Java 建 `analysis_runs` 记录（`PENDING`）后，把 ID 传给 Python |
| 分析任务业务状态（`AnalysisRunStatus`） | **Java 权威** | Java 依据 Python 回报的状态更新；Python 侧状态只是执行细节 |
| Python 执行状态 | Python | Python 保存执行进度，但**不写 Java 数据库** |
| 结果持久化（`analysis_items` / `replenishment_items`） | **Java** | Python 只返回 JSON，Java 校验后落库 |
| 权限与业务校验 | **Java** | Python 不感知权限 |

### 13.3 内部接口认证

- Java → Python：`X-Internal-Token`（共享密钥，来自环境变量 `AI_INTERNAL_TOKEN`）。
- Python 服务**不对公网暴露**，仅在 Docker 内部网络可达。
- 所有内部接口必须携带 `X-Request-Id` 用于链路追踪。

### 13.4 超时与重试约定

| 接口 | 超时 | 重试 |
| --- | --- | --- |
| `analysis:validate` | 5s | 0 次（快速失败） |
| `POST /analysis/runs` | 10s（仅创建，不含执行） | 1 次（仅网络错误） |
| `GET /analysis/runs/{id}` | 3s | 1 次 |
| 单次 LLM 调用（Python → LLM） | 默认 30s | 最多 2 次（指数退避，仅网络/5xx） |
| 整体分析执行 | 默认 300s（`analysis_runs.timeout_at`） | 不自动重试；用户可发起新 run |

---

## 14. 时间、金额与数量精度约定

### 14.1 时间

| 类型 | 数据库类型 | 语义 | API 传输 |
| --- | --- | --- | --- |
| 时间戳 | `DATETIME(3)`，**存 UTC** | 字段后缀 `_at` | ISO-8601 UTC，如 `2026-10-14T08:00:00.000Z` |
| 业务日期 | `DATE` | 字段后缀 `_date`，代表**团队时区下的自然日** | `YYYY-MM-DD` |

- 时间字段统一由应用层转换时区，**数据库不存时区信息**。
- 团队时区存于 `teams.default_timezone`（默认 `Asia/Shanghai`）。
- 前端展示时按团队时区渲染；`sale_date` 等业务日期**不做时区转换**（它本来就是"那一天"）。

### 14.2 金额与数量

| 用途 | 类型 | 理由 |
| --- | --- | --- |
| 数量（库存 / 销售 / 采购） | `DECIMAL(18,3)` | 支持拆零（0.001），电商可能出现拆分销售 |
| 单价 | `DECIMAL(18,4)` | 保留采购单价精度 |
| 金额 | `DECIMAL(18,2)` | 金额仅两位小数，避免浮点误差 |
| 计算中间量 | 保留 4 位小数，最终结果按精度四舍五入 | 保证可复现 |

- **禁止使用 `FLOAT` / `DOUBLE` 存储金额与数量。**
- Java 侧使用 `BigDecimal`，Python 侧使用 `Decimal`（pandas 计算结果须显式转换）。
  这是 Java↔Python 数值一致性的关键约定（见 `AI_SERVICE_CONTRACT.md §数值一致性`）。

---

## 15. 待决策事项清单（Pending Decisions）

> 只有"会显著影响架构且无法合理采用默认值"的问题才进入此表。
> 其余细节均已采用合理默认值并在对应文档中说明，**不作为问题提出**。

| 编号 | 问题 | 推荐方案 | 可选方案 | 影响 |
| --- | --- | --- | --- | --- |
| `D-01` | 认证方案选 JWT 还是 Session | **JWT（无状态）**，Access Token 2h + Refresh Token 7d，Refresh Token 存 `users` 无关的独立表（M2 决定存储位置） | 服务端 Session（需引入 Redis 或 JDBC Session） | 影响是否引入 Redis、影响登出即时失效能力 |
| `D-02` | 是否首期支持多仓库（同团队多仓） | **数据模型支持，UI 只暴露默认仓** | 首期完整多仓 UI | 影响库存页面复杂度与 `warehouse_id` 必填策略 |
| `D-03` | 日均销量是否需要趋势/季节修正 | **首期不做**，用 `avg-ma-3sigma-v1` | 加线性趋势回归 | 影响补货准确性，但会显著增加可解释性与测试成本 |
| `D-04` | 安全库存公式 | **简化 `SS = D × ceil(L × 0.5)`** | 标准式 `Z × σ × √L`（需服务水平参数） | 影响补货量的严谨性与参数复杂度 |
| `D-05` | 审计日志是否使用独立数据库账号（仅 INSERT） | **M3 前采用"应用层禁止 UPDATE/DELETE + 独立 Mapper"，M7 视需要加 DB 级权限** | 首期即建独立 DB 账号 | 影响部署复杂度 |
| `D-06` | 采购单是否需要独立审批流（`PENDING_APPROVAL`） | **首期不设**：`APPROVER` 直接确认 `DRAFT → PENDING` 视为审批 | 加独立采购审批状态与审批表 | 影响状态机与页面数量 |
| `D-07` | 是否引入 Redis | **首期不引入**（见 ADR-001） | 用于分析任务状态缓存 / 分布式锁 | 影响依赖数量与部署复杂度 |
| `D-08` | 库存余额是否允许负数 | **不允许**（`on_hand_qty >= 0` 约束 + 出库校验） | 允许负库存以容忍后补数据 | 影响流水冲销顺序与导入容错 |
| `D-09` | 是否强制"提交人与审批人分离"（四眼原则） | **首期不强制**：`APPROVER` 可审批自己提交的建议，但审计日志与审批记录必须留痕操作者 | 强制"不得自审"（单人团队需引入额外配置） | 影响小团队可用性与合规强度 |
| `D-10` | Spring Boot 基线版本停在 3.x 还是升到 4.x | **停留 3.5.x**（任务书明确要求 3.x 基线；3.5.x 为 3.x 最后一支） | 升级 Spring Boot 4.x | 影响依赖生态成熟度、升级工作量与安全补丁窗口 |
| `D-11` | MySQL 版本 8.0.x 还是 8.4 LTS | **8.0.x**（与 MyBatis-Plus / Flyway / Testcontainers 生态验证最充分） | 8.4 LTS（更长支持窗口） | 影响镜像版本与长期维护成本 |
| `D-12` | 数量/金额在 JSON 中用 number 还是 string 传输 | **用 string**（`"12.500"`），Java `BigDecimal` ↔ Python `Decimal`，避免 JS 与序列化精度丢失 | 用 number + 约定小数位 | 影响前后端与 Java↔Python 全部数值字段的序列化配置 |
| `D-13` | 用户属于多团队时 JWT 如何绑定团队 | **Token 绑定单个 `team_id`**，切换团队需重新签发 Token | Token 携带团队列表 + 请求头指定团队 | 影响登录体验、Token 失效策略与越权测试设计 |
| `D-14` | 同一团队并发分析运行数上限 | **限制为 1**（同团队存在 `RUNNING` 任务时新请求返回 `ANL-7001`） | 允许多个并行（需更复杂的资源与幂等控制） | 影响分析吞吐与用户体验 |
| `D-15` | 单次分析 SKU 超 `MAX_ANALYSIS_SKUS`（2000）时的行为 | **拒绝并提示**（错误码待补，或复用 `COM-0001` + details） | 自动截断 / 自动分片为多次运行 | 影响用户操作方式与分析时长 |
| `D-16` | 补货公式是否扣减"已 `APPROVED` 未 `CONVERTED`"的建议量 | **首期不扣减**，但**同 SKU×仓库已有 `APPROVED` 建议时，新一轮分析把旧建议置 `SUPERSEDED` 或标注冲突** | 在 `IP` 中扣减待转采购量（更严谨，需引入"已承诺补货量"概念） | 影响重复采购风险与资金占用 |
| `D-17` | 预算截断与采购倍数的优先级 | **先套 MOQ 与倍数，最后套预算截断**，截断后需再对齐 `qty_step`，并标记 `truncated_by_budget` | 先套预算再套倍数（可能突破预算） | 影响建议数量的可解释性与采购约束一致性 |
| `D-18` | 手工创建的采购单是否也需 `APPROVER` 确认 | **需要**（与由建议生成的采购单一致，避免绕过审批） | 手工创建单可跳过确认 | 影响 `PurchaseOrderStatus.DRAFT` 的使用频率与权限矩阵 |
| `D-19` | `DataQualityGrade` 各等级的具体阈值 | **首期用固定阈值**（有效天数占比 ≥ 80% 为 GOOD、≥ 50% 为 FAIR、< 50% 为 POOR、< `MIN_DAYS` 为 INSUFFICIENT） | 阈值团队可配置 | 影响分析结果标注与前端提示 |
| `D-20` | LLM 输出"数字溯源"校验失败时是硬失败还是软警告 | **软警告**：记录 `warnings` 并降低置信度，不阻断落库（因为规则结果仍权威） | 硬失败（重新生成，仍失败则 `VALIDATION_FAILED`） | 影响 `DEGRADED` 触发频率与解释质量 |
| `D-21` | 分析超时后是否支持断点续跑 | **不支持**：超时即 `TIMEOUT` 终态，用户发起新 run | 保存中间步骤状态以支持续跑 | 影响 Python 侧状态持久化设计与复杂度 |
| `D-22` | "重试 LLM 解释"是否改变 `analysis_runs.status` | **不改变**：`DEGRADED` 为终态，重试只更新 `llm_explanation` 与 `llm_detail_json`，并在审计中留痕 | 新增状态 `DEGRADED_RETRYING` / 或允许 `DEGRADED → SUCCEEDED` | 影响状态机的终态定义与前端展示 |
| `D-23` | 无库存记录（`inventory_balances` 无行）的 SKU 如何处理 | **视为 `on_hand_qty = 0`**，走正常分析（缺货风险规则会命中），并在结果中标注 `balance_missing = true` | 跳过该 SKU 并报数据不足 | 影响新 SKU 的首次分析行为 |
| `D-24` | 审计/流水等只增表的保留期与归档策略 | **首期不归档**（数据量可控），仅建立索引；M7 前评估是否需要归档脚本 | 引入按月分区或归档表 | 影响长期数据量与查询性能 |
| `D-25` | 业务单号（`run_no` / `po_no` / `receipt_no`）生成策略 | **团队内日期前缀 + 序列**（如 `PO-20261014-0001`），由数据库唯一约束兜底并发 | 全局 UUID / 雪花 ID（可读性差） | 影响单号可读性、并发安全与跨团队一致性 |

> **低影响收尾项（合并记录，不再单列编号）**：库存调整是否另设独立"调整单"入口、采购单"标记已下单"是否独立接口、补货建议撤回是否限于提交人本人 —— 三者均属**接口粒度**问题，默认取"最小实现"：调整复用 `POST /api/v1/inventory/adjustments`、下单复用 `POST /api/v1/purchasing/orders/{id}/confirm` 之外新增 `/ordered` 动作、撤回限提交人本人或 `ADMIN`/`OWNER`。实现时如有异议再升级为正式待决策。

### 15.1 已在 M0 自我审查中裁定的事项（**不再作为待决策**）

> 以下问题在 M0 一致性审查中被发现，已直接在本基线或对应文档中裁定并落实，
> 在此留档以便追溯（详细检查记录见 `docs/06-development/DESIGN_REVIEW.md`）。

| 原建议编号 | 问题 | 裁定结果 | 落实位置 |
| --- | --- | --- | --- |
| — | 导入幂等（唯一键）与"允许强制重导"的物理冲突 | 引入可空列 `dedupe_hash` + 唯一键（允许 NULL 共存）；`FORCE_REIMPORT` 明确为**动作**而非状态 | 本文档 §8 补充说明、§9.5 |
| — | `uk_ai_run_sku_wh` 等唯一键不含 `team_id` | 声明"父对象已确定团队"的豁免条款（4 个键） | 本文档 §6 规则 2 |
| — | 无团队上下文记录的 `team_id` 取值 | 保留哨兵 `team_id = 0`（系统域） | 本文档 §6 规则 1 |
| — | `IMP-4006` / `IMP-4007` 等错误码缺失 | 补入 §12.3，并新增 `AI-xxxx` 内部错误域 | 本文档 §12.3 |
| — | 一笔流水是否可被多次冲销 | 新增 `uk_invtxn_team_reversed (team_id, reversed_txn_id)` | 本文档 §8 |
| — | `LeadTimeSource` / `DataQualityGrade` / `InventoryBizType` / `SalesSource` / `Source` / 审计动作常量 未定义 | 统一补入 §9.17 | 本文档 §9.17 |
| — | `SUGGESTED` 是否允许直接 `REJECTED` | **不允许**，驳回必须经 `PENDING_REVIEW` | 本文档 §5 规则 6、§9.11 补充规则 |
| — | `R-LOWSTOCK` 与 `R-STOCKOUT` 是否互斥 | **可同时命中**，`risk_types` 为数组 | 本文档 §11 |
| — | 到货冲销后采购单状态能否回退 | **允许回退**，为唯一例外，须审计留痕 | 本文档 §9.13 补充规则 |
| — | 已冲销到货单的状态标记 | 新增 `GoodsReceiptStatus.REVERSED` | 本文档 §9.14 |
| — | `in_transit_qty` 是派生还是物化 | **物化缓存**（余额口径）+ 派生过滤值（分析口径）双口径，并说明包含关系 | 本文档 §3、§3.1、§10.4 |
| — | `analysis_items` 条目级 LLM 状态无处落库 | 新增字段 `llm_detail_json` | 本文档 §8 补充说明 |
| — | Python 包根 `shoppilot_ai` 与目录 `app/` 的关系 | 工程根 `service-ai/`，Python 包目录 `app/`，分发名 `shoppilot-ai`（§2 已同步修正） | 本文档 §2、`PROJECT_STRUCTURE.md` |
| — | 分析完成→生成建议的编排入口归属 | 由 `replenishment` 模块编排（`analysis` 不反向调用 `replenishment`） | `MODULE_BOUNDARIES.md`、ADR-001 |
| — | 采购在途数据如何被 `analysis` 使用 | `purchasing` 通过 `InventoryWriteService` 投影写入在途缓存，`analysis` 只读 `inventory` 口径 | `MODULE_BOUNDARIES.md`、ADR-001 |
| — | LLM 输出结构校验失败的处理 | 重试 1 次，仍失败置 `llm_status = VALIDATION_FAILED`，Java 侧 run 置 `DEGRADED` 并保留规则结果 | `PROMPT_DESIGN.md`、`AI_SERVICE_CONTRACT.md` |

### 15.2 待决策编号规则（**强制**）

M0 编写过程中，多个文档曾各自使用 `D-09` 及之后的编号提出"建议新增待决策"，导致**同一编号在不同文档含义不同**。现作如下统一规定：

1. **`D-01`~`D-25` 是本文档 §15 独有的全局权威编号。** 任何文档引用待决策时，**只能引用这些编号**。
2. **其他文档中出现的 `D-09` 及之后的旧编号一律作废**，不得再作为待决策依据使用。这些旧编号与权威编号的对应关系（以及哪些已在 M0 审查中被裁定关闭）完整记录在
   [`docs/06-development/DESIGN_REVIEW.md`](06-development/DESIGN_REVIEW.md) 的**「编号迁移表」**中。
3. **实现阶段遇到未裁定的待决策项**：按本文档给出的"推荐方案"实施，并在报告中标注"采用了待决策 `D-xx` 的推荐方案"。
4. **不得自行创造新的编号**。如确需新增关键决策点，写"建议新增待决策 `D-26`（附理由）"并等待确认后由本文档正式登记。
5. **M1-T01 的清理任务**（见 `DEVELOPMENT_PLAN.md`）包含"把全部文档中的旧编号替换为权威编号或『已裁定』标记"。

---

## 16. 文档与代码的同步责任

| 变更类型 | 必须同步更新 |
| --- | --- |
| 新增/修改表或字段 | `DATABASE_DESIGN.md`、`DATA_DICTIONARY.md`、`DATABASE_CONSTRAINTS.md`、`ER_DIAGRAM.md` |
| 新增/修改 API | `API_CONTRACT.md`、`ERROR_CODES.md`，相关页面文档 |
| 修改状态枚举 | **本文档 §9** + 所有引用它的文档 + 状态机测试 |
| 修改补货公式 / 风险规则阈值 | **本文档 §10/§11** + `INVENTORY_RULES.md` + `TEST_STRATEGY.md` 用例 |
| 修改 Java↔Python 契约 | `AI_SERVICE_CONTRACT.md`、`AI_ARCHITECTURE.md`、`ANALYSIS_CONTRACT.md` |
| 修改模块依赖 | `MODULE_BOUNDARIES.md`、`PROJECT_STRUCTURE.md`、ADR |

> 该表是 `AGENTS.md` 第 10–12 条规则的具体执行依据。
