# ShopPilot AI 验收标准（ACCEPTANCE CRITERIA）

## 文档元信息

| 项 | 内容 |
| --- | --- |
| 文档名称 | ShopPilot AI 验收标准 |
| 文档路径 | `docs/06-development/ACCEPTANCE_CRITERIA.md` |
| 文档优先级 | P5（见 `DESIGN_BASIS.md §1.1`） |
| 所属阶段 | M0 定稿；M1~M7 逐里程碑执行 |
| 版本 | v1.0（M0 定稿） |
| 上游权威来源 | `DESIGN_BASIS.md`（§5 角色、§6 隔离、§9 枚举、§10 公式、§11 规则、§12 错误码、§15 待决策）、`docs/00-product/PRD.md`（F-xxx-nn 与 §9 验收口径总述）、`docs/02-database/DATABASE_CONSTRAINTS.md §6.3/§8`、`docs/03-api/API_CONTRACT.md`、`docs/05-workflows/*` |
| 主要读者 | 项目负责人、验收方、开发、测试 |

> ## ⚠️ 诚实声明
>
> 截至 M0，本项目**尚未执行过任何验收**：没有运行过任何测试、部署、数据库迁移或模型调用。本文档中的全部验收项均为**待执行计划**。M1 起，每条验收项在判定"通过"时**必须附真实证据**（命令原文 + 真实输出 + 日志/截图），**严禁"声称通过但无证据"**（见 §8）。

### 本文档负责什么

- 定义**验收原则**与**验收证据要求**。
- 给出 **M0~M7 每个里程碑的验收清单**（勾选式，每条含**可执行的验证方式**与**期望结果**）。
- 给出**功能验收标准**（按业务模块）、**业务不变量验收**、**非功能验收**、**文档验收**、**交付验收**。
- 明确**未达成项的处置方式**。

### 本文档不负责什么

- **不定义**业务规则、状态枚举、错误码、公式（权威在 `DESIGN_BASIS.md`，本文档只引用）。
- **不定义**测试用例内部实现（见 `TEST_STRATEGY.md`）。
- **不定义**任务排期与编号（见 `DEVELOPMENT_PLAN.md`）。

### 待决策编号引用约定

引用待决策时只引用 `DESIGN_BASIS.md §15` 的 `D-01`~`D-08`。其他文档（`TECH_STACK.md`、`DATABASE_CONSTRAINTS.md`、`DATABASE_DESIGN.md`、`API_CONTRACT.md`、`IMPORT_WORKFLOW.md`、`INVENTORY_RULES.md` 等）出现的 `D-09` 及之后编号均为**建议编号，存在冲突**，本文档一律写作"（编号待 SSOT 统一，见 `DESIGN_REVIEW.md`）"。

---

## 1. 验收原则

| # | 原则 | 含义 |
| --- | --- | --- |
| A1 | **可验证** | 每条验收项必须能通过**具体命令或具体操作步骤**判定"通过/不通过"，且判定标准**不含主观描述**（禁止"界面友好""体验良好"这类无法判定的表述） |
| A2 | **可复现** | 同一验收项在**相同环境与相同数据**下重复执行，结果一致；因此必须提供**可复现的输入**（seed 数据、命令、参数）和**环境说明** |
| A3 | **有证据** | 每条验收项的"通过"必须附**命令原文 + 真实输出 + 日志/截图**（见 §8）；无证据不得判定通过 |
| A4 | **不虚构** | 禁止声称未执行的验收已通过；禁止用"设计上可行""应该可以"替代真实执行；禁止美化失败 |
| A5 | **一致性优先** | 验收过程中若发现实现与 `DESIGN_BASIS.md` 不一致，**以 SSOT 为准**（`§1.2`），并登记 `DESIGN_REVIEW.md` |
| A6 | **硬门槛不可降级** | `DEVELOPMENT_PLAN.md §13` 的里程碑硬门槛**不可因工期压力而放宽**；未满足即该里程碑不得判定完成 |
| A7 | **端到端优先** | 闭环的**端到端可用性**是最高优先级验收目标（对应 `PRD.md §9.1`） |

---

## 2. 验收环境与前置

| 项 | 要求 |
| --- | --- |
| 环境 | 本地或目标服务器，Docker Desktop + `docker compose`；MySQL ≥ 8.0.16 |
| 数据 | **固定 seed 演示数据**（脱敏、版本化），覆盖正常/边界/异常场景（`TEST_STRATEGY.md §16.2`） |
| 账号 | 至少准备两个团队（A/B），并在团队 A 内准备 5 种角色各一个账号（`OWNER`/`ADMIN`/`OPERATOR`/`APPROVER`/`VIEWER`） |
| 工具 | `curl`（或 HTTP 客户端）、`mysql` 客户端、浏览器 |
| 约束 | **禁止在生产库执行验收**；LLM 相关验收默认使用 Mock/关闭（见 §6.3） |

---

## 3. 里程碑验收清单

> 每条含：**验收项 / 可执行的验证方式 / 期望结果**。所有方式均为**计划**，尚未执行。

### 3.1 M0 · 产品与架构设计

- [ ] **文档齐备**：`ls docs/*/*.md docs/*.md docs/decisions/*.md` → 26 份文档 + `DESIGN_BASIS.md` 存在
- [ ] **SSOT 章节完整**：`DESIGN_BASIS.md` 含 §1~§16 → 全部存在
- [ ] **ADR 决策完整**：`ADR-001-ARCHITECTURE.md` 含 15 个决策节 → 全部存在且各含"背景/可选方案/推荐方案/理由/代价/重评触发"
- [ ] **无未登记冲突**：读 `DESIGN_REVIEW.md` → 所有已知不一致均已登记（含"待处理/已处理"状态）
- [ ] **DO NOT 清单遵守**：`git ls-files` 中无任何 `.java`/`.py`/`.vue`/`.ts`/`.sql`/`pom.xml`/`.env` → 均为空
- [ ] **M0 停止确认**：项目负责人确认 M0 完成并授权 M1（`DEVELOPMENT_PLAN.md §15`）

### 3.2 M1 · 工程骨架与本地运行

- [ ] **三端可构建**：`mvn -q -DskipTests package`（backend）、`npm ci && npm run build`（frontend）、`python -m compileall app`（service-ai）→ 均成功
- [ ] **Compose 全健康**：`docker compose -f deploy/docker-compose.dev.yml up -d && docker compose ps` → `mysql`/`backend-java`/`service-ai`/`nginx` 均 `healthy`
- [ ] **后端健康**：`curl -s localhost:8080/actuator/health` → `{"status":"UP"}`
- [ ] **Python 健康（仅内网）**：`docker compose exec backend-java curl -s service-ai:8000/internal/v1/health` → `{"status":"UP"}`
- [ ] **Python 不对宿主暴露**：`curl -s localhost:8000/internal/v1/health` → **连接失败**（符合 `§13.3`）
- [ ] **MySQL 版本满足**：`mysql -e "SELECT VERSION()"` → ≥ `8.0.16`
- [ ] **Flyway 基线成功**：启动日志/`SELECT * FROM flyway_schema_history` → 基线迁移 `Success`，且**无业务表**
- [ ] **冒烟测试可运行**：`mvn test` 与 `pytest -q` → 冒烟用例通过（附真实输出）
- [ ] **无密钥入库**：`git grep -nE "API_KEY|SECRET|PASSWORD" -- ':!*.example'` → 仅占位符，无真实值
- [ ] **M1 停止确认**：提交验收记录，等待确认

### 3.3 M2 · 登录、团队与基础资料

- [ ] **登录成功**：`curl -X POST .../api/v1/auth/login -d '{"username":"owner_a","password":"..."}'` → 返回令牌
- [ ] **登录失败不区分用户**：错误密码与不存在用户均返回 `AUTH-1002`
- [ ] **禁用用户被拒**：`PATCH /users/{id}/status` 置 `DISABLED` 后登录 → `AUTH-1003`
- [ ] **令牌刷新**：`POST /auth/refresh` → 成功换发；过期令牌 → `AUTH-1005`（见 ERROR_CODES §2.1）
- [ ] **跨团队读被拒**：用 B 团队 Token `GET /api/v1/skus/{a_sku_id}` → 403 `TEAM-2001`
- [ ] **请求体伪造被忽略**：A 的 Token 创建采购明细并传 `teamId=B` → 403 `TEAM-2001`，且产生 `TEAM_CROSS_ACCESS_DENIED` 审计（对象属 M6，M6 验收时勾选）
- [ ] **团队停用**：置 `teams.status='DISABLED'` 后任意接口 → 403 `TEAM-2002`
- [ ] **角色越权**：`OPERATOR` 调用审批接口 → 403 `TEAM-2003`（审批接口属 M6，M6 验收时勾选）
- [ ] **主数据 CRUD**：仓库/供应商/商品/SKU 的增改查可用
- [ ] **编码唯一性**：同团队重复 `sku_code` → `CAT-3002`；跨团队同 `sku_code` → 成功
- [ ] **主数据启停**：停用 SKU 后，历史数据仍可查（`§9.4`）
- [ ] **M2 停止确认**：提交验收记录，等待确认

### 3.4 M3 · 数据导入、销售与库存

- [ ] **导入成功**：导入合法 SKU 文件 → `COMPLETED`
- [ ] **重复导入被拒**：重复上传同文件 → `IMP-4004`，并写 `DUPLICATE_REJECTED`
- [ ] **部分失败**：含错误行的文件 → `PARTIAL_SUCCESS`，错误报告可下载
- [ ] **清单校验**：缺少必填列 → `IMP-4003`；非法文件类型 → `IMP-4001`；格式错误 → `IMP-4002`
- [ ] **库存余额查询**：`GET /api/v1/inventory/balances` → 返回 `on_hand_qty` 与 `available_qty`
- [ ] **流水查询**：`GET /api/v1/inventory/transactions` → 可见 `INIT_IMPORT`/`COUNT_ADJUST` 等类型
- [ ] **盘点调整**：`POST /inventory/count-adjustments` → 产生 `COUNT_ADJUST` 流水，余额同步
- [ ] **手工调整权限**：`OPERATOR` 调用 → `TEAM-2003`；`ADMIN` 调用 → 成功
- [ ] **负库存防护**：出库超量 → `INV-5001`
- [ ] **幂等**：同 `(team_id, idempotency_key)` 重复流水且内容不一致 → `INV-5003`
- [ ] **并发冲突**：并发更新余额 → 可观测 `INV-5002`
- [ ] **对账**：对账 SQL 返回 0 行（`Σ流水 == 余额`）
- [ ] **导入性能**：1 万行导入 < 60s
- [ ] **M3 停止确认**：提交验收记录，等待确认

### 3.5 M4 · Python 分析服务

- [ ] **创建分析**：`POST /api/v1/analysis/runs` → 预检通过，返回 `run_id`，任务 `PENDING→RUNNING`
- [ ] **并发保护**：已有进行中运行再次创建 → `ANL-7001`
- [ ] **状态与进度**：`GET /analysis/runs/{id}` → 有 `progress` 与 `current_step`
- [ ] **分析成功**：任务达 `SUCCEEDED`（LLM 开启且成功）或 `DEGRADED`（LLM 关闭/失败）
- [ ] **数据不足不猜测**：`n < MIN_DAYS` 的 SKU → `DATA_INSUFFICIENT`、`D=null`、**无建议**
- [ ] **快照查询**：`GET /analysis/runs/{id}/items` → 返回不可变快照
- [ ] **AI 不可用**：停 `service-ai` 后创建 → `ANL-7002`
- [ ] **AI 超时**：构造超时 → `ANL-7003`
- [ ] **Schema 校验失败**：返回结果不合规 → `ANL-7004` 且**不落库**
- [ ] **取消**：`POST /analysis/runs/{id}:cancel` → `CANCELLED`
- [ ] **golden cases**：18 个逐字段断言通过
- [ ] **规则用例**：6 条规则各 ≥3 用例通过
- [ ] **性能**：500 SKU 分析 < 60s（不含 LLM）
- [ ] **M4 停止确认**：提交验收记录，等待确认

### 3.6 M5 · LLM 解释与补货审批

- [ ] **建议生成**：分析落库后派生 `SUGGESTED` 建议，`source_analysis_item_id` 可追溯
- [ ] **改量**：`PATCH /replenishment/items/{id}` → 成功；并发冲突 → `RPL-8003`
- [ ] **提交/撤回**：`:submit` → `PENDING_REVIEW`；`:withdraw` → `SUGGESTED`
- [ ] **审批通过**：`:approve` → `APPROVED`
- [ ] **驳回必填意见**：`:reject` 无意见 → 被拒
- [ ] **重复提交/审批**：→ `RPL-8002`
- [ ] **双审批并发**：2 线程同时审批 → 恰好 1 条成功
- [ ] **越权审批**：`OPERATOR` → `TEAM-2003`
- [ ] **LLM 降级**：关闭/故障 LLM → 任务 `DEGRADED`，规则结果完整
- [ ] **输出结构校验**：非法枚举/超范围 SKU/编造数字 → 被拦截（`VALIDATION_FAILED`）
- [ ] **已审批不被覆盖**：新一轮分析不覆盖已 `APPROVED`+ 的建议
- [ ] **M5 停止确认**：提交验收记录，等待确认

### 3.7 M6 · 采购与到货闭环

- [ ] **生成采购单**：由 `APPROVED` 建议 `POST /purchase-orders` → `DRAFT`；建议 → `CONVERTED`
- [ ] **重复转换被拒**：同一建议再次转采购 → `PO-9001`
- [ ] **同口径冲突未确认被拒**（`F-03`）：同 `team_id`×`warehouse_id`×`sku_id` 存在其它 `APPROVED` 未转建议 → `RPL-8012`，且不产生任何采购单
- [ ] **显式确认后放行并留痕**：带正确 `conflict_acknowledged` + `acknowledged_item_ids` 重发 → 建单成功，且 `PURCHASE_ORDER_CONFLICT_ACK` 审计存在
- [ ] **确认集过期**：冲突集变化后重发 → 再次 `RPL-8012`，且**不写**确认审计
- [ ] **确认下单**：`:confirm` → `PENDING`；`:order` → `ORDERED`
- [ ] **采购单创建不改库存**：创建/确认/下单前后，余额与流水**不变**
- [ ] **到货登记**：`POST /goods-receipts` → `DRAFT`（不产生流水）
- [ ] **确认到货入库**：`:confirm` → 产生 `PURCHASE_INBOUND` 流水；余额增加
- [ ] **部分到货**：部分确认 → `PARTIALLY_RECEIVED`
- [ ] **全部到货**：全部确认 → `RECEIVED`
- [ ] **超额收货**：到货量 > 待收量 → `PO-9002`
- [ ] **状态非法转换**：终态再操作 → `PO-9003`
- [ ] **重复收货幂等**：同到货单重复确认 → 只入库一次
- [ ] **审计查询**：`GET /api/v1/audit/logs` → 可见关键操作记录；非 `ADMIN`+ 被拒
- [ ] **对账**：到货入库后对账 SQL 返回 0 行
- [ ] **M6 停止确认**：提交验收记录，等待确认

### 3.8 M7 · 集成、部署与项目交付

- [ ] **端到端脚本**：§7.1 的 20 步闭环全部通过
- [ ] **性能**：列表 p95 < 500ms；1 万行导入 < 60s；500 SKU 分析 < 60s（不含 LLM）
- [ ] **安全**：无密钥入库；越权一律 403；SQL 注入载荷无效
- [ ] **可用性**：LLM 不可用时业务仍可用（`DEGRADED`）
- [ ] **可观测性**：任一请求可用 `requestId` 查全链路日志（含审计）
- [ ] **部署可复现**：新环境按 `README.md` 步骤可复现（见 §7.2）
- [ ] **文档一致性**：`DESIGN_REVIEW.md` 无未闭环冲突；`AGENTS.md` 规则齐备
- [ ] **证据归档**：全部验收项附命令 + 输出（见 §8）
- [ ] **未达成项处置**：遗留问题已登记（见 §9）
- [ ] **M7 交付确认**：项目负责人确认交付

---

## 4. 业务不变量验收（重点，≥12 条）

> 业务不变量是本项目**最高优先级**的验收目标：即使功能可用，若不变量被违反，产品也不可信。

| # | 不变量 | 可执行的验证方式 | 期望结果 |
| --- | --- | --- | --- |
| INV-01 | **库存余额可由流水推导** | 执行对账 SQL（`TEST_STRATEGY.md §7.3`） | 返回 **0 行**（`Σ qty_delta == on_hand_qty`） |
| INV-02 | **任何库存变更都有对应流水** | 查询余额变更记录与流水；或对账 SQL | 不存在"有余额变更却无流水"的行 |
| INV-03 | **采购单创建不改变库存** | 创建→确认→下单采购单，前后分别 `GET /inventory/balances` | 余额与流水**不变** |
| INV-04 | **只有确认到货才入库** | 到货单 `DRAFT` 状态 vs `CONFIRMED` 状态对比 | `DRAFT` 无流水；`CONFIRMED` 才产生 `PURCHASE_INBOUND` |
| INV-05 | **同一幂等键不会产生两笔业务** | 同 `idempotency_key` 重复提交（库存/采购/到货/导入） | 仅一笔业务；内容一致返回原结果，内容不一致 `INV-5003`/`COM-0009` |
| INV-06 | **跨团队访问一律 403** | 用团队 B Token 访问团队 A 的各类资源（含 `OWNER`） | 403 `TEAM-2001`（不泄漏任何数据） |
| INV-07 | **已审批建议不能被新一轮分析覆盖** | 审批一条建议 → 触发新一轮分析 → 查看该建议状态 | 保持 `APPROVED`+（不因新 run 被覆盖；未审批的才 `SUPERSEDED`） |
| INV-08 | **已 `CONVERTED` 的建议不能重复创建采购单** | 对已转采购的建议再次转采购 | `PO-9001` |
| INV-09 | **`received_qty ≤ ordered_qty`** | 累计到货超过订购量 | 被拒 `PO-9002`；DB `CHECK` 兜底 |
| INV-10 | **采购单状态由明细到货情况正确推导** | 构造不同明细组合（全收/部分/未收） | `RECEIVED`/`PARTIALLY_RECEIVED`/`ORDERED` 推导正确 |
| INV-11 | **LLM 失败不丢失规则结果** | 关闭/故障 LLM 后运行分析 | 任务 `DEGRADED`；`analysis_items` 的规则结果完整可用 |
| INV-12 | **分析结果可追溯到输入快照与算法/Prompt 版本** | 查 `analysis_items` 与 `analysis_runs` | 可获取输入快照、`algorithm_version`、Prompt 版本 |
| INV-13 | **非负库存** | 出库导致负库存（`D-08`：不允许负库存） | 出库被拒 `INV-5001`；调整/冲销被拒 `INV-5006`；DB `CHECK` 兜底 |
| INV-14 | **流水自证**（`qty_after = qty_before + qty_delta`） | 构造非法流水写入 | DB `CHECK` 拒绝 |
| INV-15 | **审计仅追加** | 尝试对 `audit_logs` 执行 UPDATE/DELETE | 应用层**无对应方法**；不允许修改历史审计 |
| INV-16 | **停用主数据不参与新分析/采购但历史可查** | 停用 SKU 后新建分析 + 查询历史销售 | 该 SKU 不进入分析范围；历史数据仍可查（`§9.4`） |

---

## 5. 功能验收标准（按业务模块）

> 每条含：编号 / 验收项 / 前置条件 / 操作步骤 / 期望结果 / 关联功能编号（`PRD.md` 的 `F-xxx-nn`）。

### 5.1 导入（Import）

| 编号 | 验收项 | 前置条件 | 操作步骤 | 期望结果 | 关联功能 |
| --- | --- | --- | --- | --- | --- |
| AC-IMP-01 | 导入 6 类业务数据 | 已登录 `OPERATOR` | 依次上传商品/SKU/供应商/仓库/库存快照/销售模板文件 | 各批次 `COMPLETED` | F-IMP-01 |
| AC-IMP-02 | 字段映射 | 上传含非标准表头的文件 | 上传 → 确认映射 | 批次 `MAPPED`；数据按映射落库 | F-IMP-02 |
| AC-IMP-03 | 预览与全量校验 | 上传含错误行文件 | 上传 → 校验 | 产出错误报告；批次 `VALIDATED` | F-IMP-03 |
| AC-IMP-04 | 错误报告下载 | 存在错误行 | 下载错误报告 | 报告含行号与错误原因；批次 `PARTIAL_SUCCESS` | F-IMP-03 |
| AC-IMP-05 | 批次查询 | 存在历史批次 | `GET /imports` | 可按状态筛选、分页 | F-IMP-04 |
| AC-IMP-06 | 文件级幂等 | 已成功导入同文件 | 再次上传同文件 | `IMP-4004`；不产生重复数据 | F-IMP-05 |
| AC-IMP-07 | 文件类型/大小校验 | — | 上传非法类型/超大文件 | `IMP-4001` | F-IMP-01 |
| AC-IMP-08 | 路径不可越权 | 团队 A 有批次 | 用团队 B Token 查 A 的批次 | 403 `TEAM-2001` | F-IMP-04 |

### 5.2 库存（Inventory）

| 编号 | 验收项 | 前置条件 | 操作步骤 | 期望结果 | 关联功能 |
| --- | --- | --- | --- | --- | --- |
| AC-INV-01 | 余额查询 | 已有库存 | `GET /inventory/balances` | 返回 `on_hand_qty` 与 `available_qty`（口径区分） | F-INV-01 |
| AC-INV-02 | 流水查询 | 已有流水 | `GET /inventory/transactions` | 返回流水，类型符合 `§9.6` | F-INV-02 |
| AC-INV-03 | 盘点调整 | `OPERATOR` | `POST /inventory/count-adjustments` | 产生 `COUNT_ADJUST`；余额更新 | F-INV-03 |
| AC-INV-04 | 手工调整权限 | — | `OPERATOR` 调用手工调整 | `TEAM-2003` | F-INV-04 |
| AC-INV-05 | 库存预警 | 存在低库存 SKU | `GET /inventory/alerts` | 命中低库存/缺货提示 | F-INV-05 |
| AC-INV-06 | 负库存拦截 | 库存不足 | 出库超量 | `INV-5001` | F-INV-03 |
| AC-INV-07 | 对账 | 任意操作后 | 对账 SQL | 0 行（INV-01） | F-INV-01 |
| AC-INV-08 | 策略参数维护 | `ADMIN` | `GET/PUT /replenishment-policies` | 三级来源参数可维护 | F-CAT-06 |

### 5.3 销售（Sales）

| 编号 | 验收项 | 前置条件 | 操作步骤 | 期望结果 | 关联功能 |
| --- | --- | --- | --- | --- | --- |
| AC-SAL-01 | 销售数据导入 | `OPERATOR` | 导入 `SALES_DAILY` | 批次 `COMPLETED`；`sales_daily` 有数据 | F-SAL-01 |
| AC-SAL-02 | 唯一性/upsert | 同一 `(wh, sku, date)` 已存在 | 再次导入同记录 | 覆盖，行数不变 | F-SAL-01 |
| AC-SAL-03 | 查询与聚合 | 有销售数据 | `GET /sales/daily` | 可按日期范围/维度查询聚合 | F-SAL-02 |
| AC-SAL-04 | 序列正确 | 有 28 天数据 | 查询某 SKU 序列 | 按日期升序返回 | F-SAL-02 |

### 5.4 分析（Analysis）

| 编号 | 验收项 | 前置条件 | 操作步骤 | 期望结果 | 关联功能 |
| --- | --- | --- | --- | --- | --- |
| AC-ANL-01 | 创建任务 | `OPERATOR` | `POST /analysis/runs` | 预检通过；`run_id` 返回；`PENDING→RUNNING` | F-ANL-01 |
| AC-ANL-02 | 并发保护 | 已有运行中任务 | 再次创建 | `ANL-7001` | F-ANL-01 |
| AC-ANL-03 | 状态/进度 | 任务执行中 | `GET /analysis/runs/{id}` | `progress`/`current_step` 可见 | F-ANL-02 |
| AC-ANL-04 | 日均销量 | 数据充足 | 查看结果 | `D` 按 `avg-ma-3sigma-v1` 计算（`§10.1`） | F-ANL-03 |
| AC-ANL-05 | 风险识别 | 构造各风险场景 | 查看结果 | `risk_types`/`risk_level` 符合 `§11` | F-ANL-04 |
| AC-ANL-06 | 补货计算 | 数据充足 | 查看结果 | `Q_base`→`Q_suggest` 符合 `§10.2/§10.5` | F-ANL-05 |
| AC-ANL-07 | 数据不足 | 样本不足 | 查看结果 | `DATA_INSUFFICIENT`、`D=null`、无建议 | F-ANL-03/05 |
| AC-ANL-08 | 快照不可变 | 任务完成 | 查看 `analysis_items` | 快照可查、不可修改 | F-ANL-06 |
| AC-ANL-09 | 取消/超时 | 任务进行中 | `:cancel` / 等待超时 | `CANCELLED` / `TIMEOUT` | F-ANL-07 |
| AC-ANL-10 | LLM 降级 | LLM 关闭 | 运行分析 | `DEGRADED`；规则结果完整 | F-ANL-08 |
| AC-ANL-11 | 结果校验 | 构造非法返回 | 运行分析 | `ANL-7004` 且不落库 | F-ANL-01 |

### 5.5 补货审核（Replenishment）

| 编号 | 验收项 | 前置条件 | 操作步骤 | 期望结果 | 关联功能 |
| --- | --- | --- | --- | --- | --- |
| AC-RPL-01 | 建议生成 | 分析完成 | 查看建议 | `SUGGESTED`，可追溯来源快照 | F-RPL-01 |
| AC-RPL-02 | 数量修改 | `OPERATOR` | `PATCH /replenishment/items/{id}` | 修改成功；并发冲突 `RPL-8003` | F-RPL-02 |
| AC-RPL-03 | 提交/撤回 | `OPERATOR` | `:submit`/`:withdraw` | `PENDING_REVIEW`/回 `SUGGESTED` | F-RPL-03 |
| AC-RPL-04 | 审批/驳回 | `APPROVER` | `:approve`/`:reject` | `APPROVED`/`REJECTED`（驳回必填意见） | F-RPL-04 |
| AC-RPL-05 | 越权审批 | `OPERATOR` | `:approve` | `TEAM-2003` | F-RPL-04 |
| AC-RPL-06 | 重复操作 | 已提交 | 再次提交 | `RPL-8002` | F-RPL-03 |
| AC-RPL-07 | 双审批并发 | 2 个 `APPROVER` | 同时 `:approve` | 恰好 1 条成功 | F-RPL-04 |
| AC-RPL-08 | 作废/被取代 | 存在建议 | `:cancel` / 触发新 run | `CANCELLED` / 未审批建议 `SUPERSEDED` | F-RPL-05 |
| AC-RPL-09 | 审核记录 | 有审核动作 | `GET .../reviews` | 时间线完整 | F-RPL-06 |

### 5.6 采购与到货（Purchasing）

| 编号 | 验收项 | 前置条件 | 操作步骤 | 期望结果 | 关联功能 |
| --- | --- | --- | --- | --- | --- |
| AC-PO-01 | 生成采购单 | 建议 `APPROVED` | `POST /purchase-orders` | 采购单 `DRAFT`；建议 `CONVERTED` | F-PO-01 |
| AC-PO-02 | 重复转换 | 已转采购 | 再次转换 | `PO-9001` | F-PO-01 |
| AC-PO-03 | 确认与下单 | `APPROVER` | `:confirm`/`:order` | `PENDING`/`ORDERED` | F-PO-02 |
| AC-PO-04 | 取消采购单 | 未到货 | `:cancel` | `CANCELLED`；提示在途影响 | F-PO-03 |
| AC-PO-05 | 创建不改库存 | — | 创建/确认/下单前后查库存 | 余额与流水不变（INV-03） | F-PO-01/02 |
| AC-PO-06 | 到货登记 | 采购单 `ORDERED` | `POST /goods-receipts` | 到货单 `DRAFT`（无流水） | F-PO-04 |
| AC-PO-07 | 部分到货 | 到货量 < 待收 | `:confirm` | `PARTIALLY_RECEIVED`；入库流水=到货量 | F-PO-04/05 |
| AC-PO-08 | 全部到货 | 累计到货=订购 | `:confirm` | `RECEIVED` | F-PO-05 |
| AC-PO-09 | 超额收货 | 到货量 > 待收 | `:confirm` | `PO-9002`；无库存变更 | F-PO-04 |
| AC-PO-10 | 重复收货幂等 | 到货单已确认 | 再次 `:confirm` | 只入库一次 | F-PO-05 |
| AC-PO-11 | 状态非法转换 | 终态单据 | 再次操作 | `PO-9003` | F-PO-02 |
| AC-PO-12 | 查询 | 有单据 | `GET /purchase-orders`、`/goods-receipts` | 分页、筛选正确 | F-PO-06 |

### 5.7 审计（Audit）

| 编号 | 验收项 | 前置条件 | 操作步骤 | 期望结果 | 关联功能 |
| --- | --- | --- | --- | --- | --- |
| AC-AUD-01 | 关键操作留痕 | 执行任意高风险写操作 | 查 `audit_logs` | 有对应记录（操作者/团队/动作/目标/结果/`requestId`） | F-AUD-01 |
| AC-AUD-02 | 审计查询与筛选 | 有审计记录 | `GET /audit-logs` | 可按人/动作/对象/时间筛选 | F-AUD-02 |
| AC-AUD-03 | 审计仅追加 | 有审计记录 | 尝试修改/删除 | 应用层无对应入口 | F-AUD-01 |
| AC-AUD-04 | 审计权限 | `OPERATOR` | `GET /audit-logs` | `TEAM-2003` | F-AUD-02 |

### 5.8 团队隔离（Team Isolation）

| 编号 | 验收项 | 前置条件 | 操作步骤 | 期望结果 | 关联功能 |
| --- | --- | --- | --- | --- | --- |
| AC-TEAM-01 | 跨团队读 | 团队 A/B | B 的 Token 读 A 的资源 | 403 `TEAM-2001` | F-TEAM-02 |
| AC-TEAM-02 | 请求体伪造 | 团队 A/B | 请求体传 `teamId=B` | 被忽略；`TEAM-2001` | F-TEAM-02 |
| AC-TEAM-03 | 关联越权 | A 的 SKU + B 的采购单 | 组合创建 | 进 DB 前失败；`TEAM-2001` | F-TEAM-02 |
| AC-TEAM-04 | 团队停用 | 停用 A | A 任意接口 | 403 `TEAM-2002` | F-TEAM-03 |
| AC-TEAM-05 | 角色越权 | 各角色 | 执行越权操作 | 403 `TEAM-2003` | F-TEAM-01 |
| AC-TEAM-06 | 编码可跨团队重复 | 团队 A/B | 两团队用同 `sku_code` | 均成功 | F-CAT-04 |
| AC-TEAM-07 | 成员管理限制 | `ADMIN` | 移除 `OWNER` | 被拒 | F-TEAM-01 |

---

## 6. 非功能验收

### 6.1 性能

| 编号 | 验收项 | 验证方式 | 期望结果 | 依据 |
| --- | --- | --- | --- | --- |
| NFR-PERF-01 | 列表接口响应 | 在 1 万 SKU / 10 万流水量级下压测列表接口 | p95 **< 500ms** | `PRD.md §6.1` |
| NFR-PERF-02 | 导入吞吐 | 导入 1 万行文件并计时 | **< 60s** | `PRD.md §6.1` |
| NFR-PERF-03 | 同步预检 | 调用 `analysis:validate` | 5s 内快速失败（超时即失败） | `DESIGN_BASIS §13.4` |
| NFR-PERF-04 | 单次分析 | 500 SKU 分析计时（不含 LLM） | **< 60s** | 本文档 §3.5 |
| NFR-PERF-05 | 前端轮询 | 观察分析进度页 | 初始 2s，退避至 10s；卸载即停 | `PRD.md §6.1` |

### 6.2 安全

| 编号 | 验收项 | 验证方式 | 期望结果 | 依据 |
| --- | --- | --- | --- | --- |
| NFR-SEC-01 | 无密钥入库 | `git grep` 扫描仓库 | 无真实密钥/口令 | `CODING_STANDARDS.md §2.4` |
| NFR-SEC-02 | 越权拦截 | 跨团队/越权矩阵 | 一律 403（`TEAM-2001/2002/2003`） | `§5/§6` |
| NFR-SEC-03 | SQL 注入防护 | 对查询/筛选参数注入载荷 | 无注入生效；MyBatis 无 `${}` 拼接 | `CODING_STANDARDS.md §3.8` |
| NFR-SEC-04 | 内部接口不暴露 | 从宿主机访问 `service-ai` | 连接失败 | `§13.3` |
| NFR-SEC-05 | 内部令牌 | 无/错误 `X-Internal-Token` 调用内部接口 | 被拒 | `§13.3` |
| NFR-SEC-06 | 登录防枚举 | 错误密码 vs 不存在用户 | 均 `AUTH-1002` | `§12.3` |
| NFR-SEC-07 | 审计仅追加 | 见 AC-AUD-03 | 不可修改历史审计 | `§12.3`/待决策 `D-05` |

### 6.3 可用性

| 编号 | 验收项 | 验证方式 | 期望结果 | 依据 |
| --- | --- | --- | --- | --- |
| NFR-AVAIL-01 | LLM 不可用业务可用 | 关闭 LLM 后运行分析 | `DEGRADED`，业务结果完整 | `PRD.md §6.2` |
| NFR-AVAIL-02 | AI 服务异常显式 | 停/超时 AI 服务 | 返回 `ANL-7002`/`ANL-7003`，不静默失败 | `PRD.md §6.2` |
| NFR-AVAIL-03 | 可重试 | 分析失败后 | 用户可发起新 run | `§13.4` |
| NFR-AVAIL-04 | Python 不拖垮主链路 | AI 服务宕机 | Java 主链路（非分析相关接口）正常 | `PRD.md §6.2` |

### 6.4 可观测性

| 编号 | 验收项 | 验证方式 | 期望结果 | 依据 |
| --- | --- | --- | --- | --- |
| NFR-OBS-01 | `requestId` 全链路 | 任一次请求，用 `requestId` 查日志与审计 | Java↔Python↔审计均可串联 | `PRD.md §6.5` |
| NFR-OBS-02 | 关键指标埋点 | 查看指标端点 | 导入成功率/分析成功率/降级率/耗时可见 | `PRD.md §6.5` |
| NFR-OBS-03 | 进度可查询 | `GET /analysis/runs/{id}` | 返回 `progress`/`current_step` | `§9.7` |

---

## 7. 文档验收与交付验收

### 7.1 文档验收

- [ ] **设计文档一致性通过**：对照 `docs/06-development/DESIGN_REVIEW.md` → 无未闭环冲突；所有已发现冲突均有处置记录
- [ ] **`AGENTS.md` 规则齐备**：含文档同步责任（对应 `DESIGN_BASIS.md §16`）与协作纪律
- [ ] **同步责任落实**：任一变更已按 `§16` 更新对应文档（抽查 3 处变更）
- [ ] **待决策状态明确**：`D-01`~`D-08` 的状态（已决/待决）记录清晰；其他编号统一登记在 `DESIGN_REVIEW.md`

### 7.2 交付验收：M7 端到端演示脚本（20 步）

> 在全新环境上按顺序执行；每步记录操作与期望结果，产出**可复现的演示记录**。

| 步骤 | 操作 | 期望结果 |
| --- | --- | --- |
| 1 | 部署：`docker compose up -d`（集成配置） | 四服务 `healthy`；Flyway 迁移成功 |
| 2 | 登录（团队 A 的 `OWNER`） | 登录成功，获取令牌 |
| 3 | 导入商品 | 批次 `COMPLETED` |
| 4 | 导入 SKU | 批次 `COMPLETED` |
| 5 | 导入供应商（含交期） | 批次 `COMPLETED` |
| 6 | 导入仓库（默认仓） | 批次 `COMPLETED` |
| 7 | 导入库存快照 | 批次 `COMPLETED`；余额成立（流水 `INIT_IMPORT`） |
| 8 | 导入 28 天历史销售 | 批次 `COMPLETED` |
| 9 | 重复上传销售文件 | `IMP-4004`；无重复数据 |
| 10 | 上传含错误行文件 | `PARTIAL_SUCCESS`；错误报告可下载 |
| 11 | 查看库存总览 | `on_hand_qty`/`available_qty` 正确展示 |
| 12 | 创建分析任务 | `run_id` 返回；`PENDING→RUNNING` |
| 13 | 轮询进度至完成 | `progress` 递增；任务达 `SUCCEEDED`/`DEGRADED` |
| 14 | 查看分析明细 | 风险等级/类型、`D`/`IP`/`Q_suggest` 正确 |
| 15 | 修改建议数量并提交审核 | `PENDING_REVIEW`；审核记录 `SUBMIT` |
| 16 | 审批人批准 | `APPROVED`；审核记录 `APPROVE` |
| 17 | 生成采购单并确认下单 | 建议 `CONVERTED`；采购单 `ORDERED`；**库存不变** |
| 18 | 到货登记（部分）并确认 | `PARTIALLY_RECEIVED`；`PURCHASE_INBOUND` 入库 |
| 19 | 到货登记（剩余）并确认 | `RECEIVED` |
| 20 | 对账 + 审计 + 只读/越权复核 | 对账 0 行；审计记录完整；`VIEWER` 只读；跨团队 `TEAM-2001` |

### 7.3 新环境部署步骤可复现

- [ ] **部署文档完整**：`README.md` 含环境要求、变量配置、启动命令、验证命令
- [ ] **可复现验证**：在**全新环境**按文档执行，无需额外人工干预即可达到"四服务健康 + 迁移成功 + 前端可访问"
- [ ] **`.env` 配置明确**：所有必填环境变量在 `.env.example` 中列出并说明用途（`AI_INTERNAL_TOKEN`、`LLM_*`、`JWT_*`、DB）
- [ ] **回滚/重置说明**：开发库重置步骤（仅开发环境）可用

---

## 8. 验收证据要求

**每条验收项判定"通过"时，必须提供以下证据（缺一不可）**：

| # | 证据 | 形式 |
| --- | --- | --- |
| 1 | **命令原文** | 可直接复制执行的命令（含参数、环境） |
| 2 | **真实输出** | 命令的实际输出（返回码、响应体、测试摘要、通过/失败数） |
| 3 | **截图或日志片段** | 关键链路日志（含 `requestId`）、前端页面截图、DB 查询结果 |
| 4 | **失败与跳过说明** | 若有失败/跳过项，必须列出并说明原因（不得只报成功） |
| 5 | **环境与数据说明** | 执行时的环境（版本、配置）与使用的 seed 数据版本 |

### 8.1 明确禁止

- ❌ **禁止"声称通过但无证据"**：任何"已通过/验证成功/已部署/已迁移"的表述必须附上述证据。
- ❌ 禁止用"设计上可行""应该能跑通"作为验收结论。
- ❌ 禁止用 Mock 结果冒充真实模型调用结果（`TEST_STRATEGY.md §18`）。
- ❌ 禁止在证据中省略失败项或选择性地只截取成功片段。

---

## 9. 未达成项的处置

| 情形 | 处置 |
| --- | --- |
| **功能未实现/未达预期** | 记录为**遗留问题**：写入 `DESIGN_REVIEW.md`（含现象、影响、阻塞的验收项、计划修复的里程碑） |
| **与 SSOT 不一致** | 以 `DESIGN_BASIS.md` 为准（`§1.2`），修正低优先级文档，并登记 `DESIGN_REVIEW.md` |
| **需要偏离既定架构/技术决策** | **必须新增或修订 ADR**（`ADR-001-ARCHITECTURE.md` 的决策不是不可更改的事实），并在 `DESIGN_REVIEW.md` 关联 |
| **新增待决策** | 登记到 `DESIGN_REVIEW.md`；编号统一由 SSOT 分配（本文档不自行编号） |
| **硬门槛未满足** | **不得判定里程碑完成**（`DEVELOPMENT_PLAN.md §13`）；修复后重新验收 |
| **已知风险被接受** | 需**显式记录**接受理由与缓解措施，并指定复核时间 |

**原则**：**不得隐瞒**任何未达成项；遗留问题是"待办"而不是"失败"，但**未登记的遗留问题**会被视为隐瞒。

---

## 10. 与 SSOT 的一致性声明

| 检查项 | 结论 |
| --- | --- |
| 功能编号 | 引用 `PRD.md` 的 `F-xxx-nn`，未自造 |
| 错误码 | 只引用 `§12.3` 已定义错误码 |
| 状态枚举 | 与 `§9` 一致 |
| 补货公式/规则 | 与 `§10/§11` 一致 |
| 团队隔离 | 与 `§6` 六条硬规则一致 |
| 精度 | 与 `§14` 一致 |
| 待决策引用 | 只引用 `D-01`~`D-08`；其他写作"（编号待 SSOT 统一，见 `DESIGN_REVIEW.md`）" |
| 执行状态 | 全部验收项为**计划**；无任何验收被执行过 |

---

## 11. 变更记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-14 | M0 定稿：验收原则、M0~M7 里程碑验收清单、功能验收、16 条业务不变量、非功能验收、文档与交付验收、证据要求、未达成项处置 | 工程效能 |
