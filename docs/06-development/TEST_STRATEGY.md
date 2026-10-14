# ShopPilot AI 测试策略（TEST STRATEGY）

## 文档元信息

| 项 | 内容 |
| --- | --- |
| 文档名称 | ShopPilot AI 测试策略 |
| 文档路径 | `docs/06-development/TEST_STRATEGY.md` |
| 文档优先级 | P5（见 `DESIGN_BASIS.md §1.1`） |
| 所属阶段 | M0 定稿；M1 起执行 |
| 版本 | v1.0（M0 定稿） |
| 上游权威来源 | `DESIGN_BASIS.md`（§9 枚举、§10 公式、§11 风险规则、§12 错误码、§13 契约、§14 精度）、`docs/05-workflows/INVENTORY_RULES.md §6`、`docs/04-ai/ANALYSIS_CONTRACT.md`（golden cases）、`docs/02-database/DATABASE_CONSTRAINTS.md §8` |
| 主要读者 | 开发、测试、验收方 |

> ## ⚠️ 诚实声明（必读，置于文档开头）
>
> **截至 M0（2026-10-14），ShopPilot AI 项目处于设计阶段，尚未编写或执行过任何一行测试代码。**
>
> - 本项目**没有任何`.java` / `.py` / `.vue` / `.ts` 测试文件**；
> - **没有任何测试被运行过**，因而**不存在任何"测试通过""验证成功"的结果**；
> - 本项目**没有任何 Docker 部署被执行过**、**没有任何数据库迁移被执行过**、**没有任何模型/LLM 调用被执行过**。
>
> 本文档中的全部内容均为**测试计划（plan）**，使用"将""应""计划"等计划语气书写。后续每个里程碑的实际执行必须提供**真实命令 + 真实输出**作为证据（见 **§18 测试状态：真实 vs 计划**），**严禁虚构**。

### 本文档负责什么

- 定义**测试金字塔与分层**、各层工具与运行时机、目标覆盖率。
- 规定 **Java / Python / 前端 / 联调 / 部署**各层的**测试范围、做法与用例组织**。
- 给出**规则引擎、数据库约束、跨团队权限、并发与幂等、采购到货状态、模型调用、AI 输出校验**的具体测试设计。
- 明确**覆盖率与质量门槛**、**测试数据管理**、**测试风险与局限**。

### 本文档不负责什么

- **不定义**业务规则与阈值（权威在 `DESIGN_BASIS.md §10/§11`，本文档只引用并组织用例）。
- **不定义**验收判定线（见 `ACCEPTANCE_CRITERIA.md`）。
- **不定义**开发排期与任务编号（见 `DEVELOPMENT_PLAN.md`）。

---

## 1. 测试金字塔

### 1.1 比例建议

```
        ▲
       ╱ ╲        E2E / 端到端（约 10%）
      ╱───╲       手工联调 + 端到端演示脚本
     ╱     ╲      —— 慢、贵、脆弱，但最接近真实闭环
    ╱───────╲     集成测试（约 30%）
   ╱         ╲    SpringBootTest + MockMvc + Testcontainers MySQL / FastAPI TestClient
  ╱───────────╲   —— 中等成本，验证约束、事务、契约、隔离
 ╱             ╲  单元测试（约 60%）
╱───────────────╲ JUnit 5 / pytest
                  —— 快、便宜、稳定，覆盖规则计算与状态机判定
```

| 层 | 占比 | 成本 | 收益 | 适用对象 |
| --- | --- | --- | --- | --- |
| 单元 | ~60% | 低（毫秒级、无外部依赖） | 快速反馈、精确定位 | 规则计算、状态机判定、精度工具、DTO 映射、前端关键交互 |
| 集成 | ~30% | 中（秒级、需 Docker/DB） | 验证约束/事务/契约/隔离，能发现"单元测不出"的问题 | API、约束、租户隔离、幂等、并发 |
| 端到端 | ~10% | 高（分钟级、依赖齐全环境） | 验证完整业务闭环与集成缺陷 | 导入→分析→建议→审批→采购→到货→入库→对账 |

### 1.2 为什么不是"全用端到端"或"全用单元测试"

- **全用端到端**：慢且脆弱，定位成本高；本项目闭环含 LLM 与异步任务，全端到端会被外部不稳定因素拖累。
- **全用单元测试**：无法验证数据库 `CHECK` 约束、唯一约束、事务回滚、租户插件的真实行为——而这些恰是本项目**最易出错**的地方（`DESIGN_BASIS §6` 明确警示团队隔离）。
- **因此采用金字塔**：以单元测试保证"算得对"，以集成测试保证"存得住、隔得开"，以端到端保证"跑得通"。

---

## 2. 测试分层与工具表

| 层 | 范围 | 工具 | 运行时机 | 是否需要 Docker | 目标覆盖率 |
| --- | --- | --- | --- | --- | --- |
| Java 单元测试 | `domain` 层规则、状态机判定、`BigDecimal` 工具、DTO 映射 | JUnit 5 (Jupiter)、AssertJ、Mockito | 每次提交 / 本地频繁 | 否 | `domain` 层行覆盖 ≥ 70% |
| Java API 集成测试 | Controller → application → DB 全链路 | `@SpringBootTest` + MockMvc / RestAssured + **Testcontainers MySQL** | 每次提交 / CI | ✅ | 关键接口全覆盖 |
| 数据库约束测试 | 唯一约束、`CHECK`、外键 `RESTRICT`、团队隔离 | Testcontainers MySQL + JdbcTemplate | CI（必需） | ✅ | 24 唯一 + 24 `CHECK` 关键项 |
| 跨团队权限测试 | 角色 × 操作 × 团队 | Testcontainers + 注入多团队 Token | CI（必需） | ✅ | 越权矩阵全覆盖 |
| 并发与幂等测试 | 库存入库/出库、审批、建议转采购、重复收货 | JUnit 5 + `CountDownLatch` + 线程池 + Testcontainers | CI（必需） | ✅ | 关键并发场景全覆盖 |
| Python 单元测试 | 统计、规则、补货计算、数据质量 | pytest | 每次提交 / 本地频繁 | 否 | 规则计算 ≥ 85% |
| Python API 测试 | FastAPI 路由、契约、错误路径 | `TestClient` + pytest | 每次提交 / CI | 否 | 契约与错误路径全覆盖 |
| 规则引擎测试 | 6 条规则 × ≥3 用例 + 18 golden cases | pytest（固定 fixture） | CI（必需） | 否 | 规则用例 100% 通过 |
| 模型调用测试 | LLM 输出校验与降级 | pytest + **Mock / 固定 fixture** | CI（必需） | 否 | 降级路径全覆盖 |
| 前端测试 | 关键交互组件 | Vitest + Vue Test Utils | CI | 否 | 关键组件/交互覆盖 |
| 前后端联调 | 业务闭环手工验证 | 手工清单 + 契约一致性检查 | 每个里程碑末 | ✅（本地环境） | 20 步清单全通过 |
| 部署验证 | Compose 编排与服务可用性 | `docker compose` + 健康检查脚本 | 里程碑末 / M7 | ✅ | 部署清单全通过 |

---

## 3. Java 单元测试

### 3.1 范围

- **`domain` 层的判定逻辑**：状态机合法转换判定、同团队关联校验的**纯判定部分**、库存不变量断言（非数据库部分）。
- **精度与工具**：`BigDecimal` 计算工具（`MoneyUtils`/`QtyUtils`）、日期时区转换工具（`§14.1`）。
- **DTO ↔ 领域值映射**：纯映射逻辑（不涉及 DB）。

> **明确不做**（避免无价值 Mock）：不为纯 getter/setter、不为 Service 里"仅转发调用"的方法写单元测试（由集成测试覆盖）。

### 3.2 Mock 策略

| 对象 | 策略 |
| --- | --- |
| 数据库 Mapper | **不 Mock**（该层由集成测试覆盖）；单元测试只测不依赖 DB 的逻辑，必要时把纯计算抽到 `domain` 层 |
| 外部 HTTP 客户端（`AiServiceClient`） | 单元测试中 Mock（返回固定响应）；真实的网络交互由集成测试或契约测试覆盖 |
| 时钟 | **注入 `Clock`**（不直接用 `LocalDate.now()`），保证时区/日期用例可复现（`§14.1`） |
| 随机数 / 编号生成 | 注入生成器，保证断言可复现 |

### 3.3 JUnit 5 用法约定

- 使用 `@Test`、`@ParameterizedTest`（表驱动）、`@Nested`（分组）、`@DisplayName`。
- 断言使用 **AssertJ**（`assertThat(...)`），异常断言用 `assertThatThrownBy`。
- **禁止**在单元测试中用 `Thread.sleep` 等不稳定手段；并发测试见 §7。
- 测试方法保持**无副作用、可独立运行、顺序无关**。

### 3.4 命名规范（统一选择）

**统一选择：中文 `@DisplayName` + 结构化英文方法名 `should_期望行为_when_条件`。**

| 项 | 约定 |
| --- | --- |
| 类名 | `<被测类>Test`，如 `ReplenishmentItemStateMachineTest` |
| 方法名 | `should_<期望行为>_when_<条件>`，全小写蛇形，如 `should_allow_approve_when_status_is_pending_review` |
| 显示名 | `@DisplayName("<中文描述>")`，如 `@DisplayName("当建议处于待审批时，应允许审批通过")` |

理由：英文方法名便于检索与工具统计；中文 `@DisplayName` 便于团队与业务方阅读；两者结合兼顾可维护性与可读性。

---

## 4. Java API 集成测试

### 4.1 基本形态

- 使用 **`@SpringBootTest` + MockMvc**（或 RestAssured，取决于是否需要真实 HTTP 层）。
- 使用 **Testcontainers 启动真实 MySQL**，**不使用 H2**（`TECH_STACK.md §1.2`：H2 的 `CHECK`/`DECIMAL` 行为与 MySQL 不一致，会掩盖问题）。
- 每个测试类通过 `@DynamicPropertySource` 指向容器数据源；Flyway 在测试容器上执行迁移。

### 4.2 测试数据隔离（结论）

**结论：首期采用"每个测试独立的团队 + 事务回滚"的组合策略。**

| 手段 | 说明 |
| --- | --- |
| **每测试独立团队** | 每次测试生成**新的 `team_id`（及配套用户/成员）**，天然隔离，也便于验证多团队场景 |
| **事务回滚** | 测试方法（或类）上使用 `@Transactional`，测试结束回滚；**例外**：并发/多线程测试**不能**用事务回滚（线程无法共享测试事务），须在测试后**按 `team_id` 清理**或使用独立容器 |
| **禁止清库式全局重置** | 不用"每个测试 truncate 全库"（慢且破坏并发的真实语义），除非确有必要 |
| **唯一键数据** | 固定 seed 提供基础对照数据（见 §16），测试用例产生的数据用随机后缀避免碰撞 |

> 说明：并发测试（§7）与被测代码自身开启的事务交互时，`@Transactional` 会改变事务可见性，因此**并发测试必须显式创建/清理数据**，不使用测试级事务回滚。

### 4.3 认证模拟

- 提供测试工具类（如 `TestAuthSupport`）：
  1. **构造 Token**：用测试私钥/密钥签发 JWT，claim 含 `team_id` 与角色；或
  2. **注入 `SecurityContext`**：通过 `SecurityMockMvcRequestPostProcessors` 或自定义 `RequestPostProcessor` 注入已认证的 `Authentication`。
- 每个团队一个 Token 工厂：`tokenFor(teamId, TeamRole.X)`，用于构造跨团队与角色矩阵用例。
- **`team_id` 必须从 Token 解析，不接受请求传入**（`DESIGN_BASIS §6 第 3 条`）；测试须验证"请求体传 `teamId` 被忽略"。

---

## 5. 数据库约束测试

> 与 `DATABASE_CONSTRAINTS.md §8` 对齐。全部为**集成测试**（真实 MySQL 容器）。

### 5.1 唯一约束

| # | 用例 | 期望 |
| --- | --- | --- |
| U-1 | 同团队插入重复 `sku_code` | 唯一冲突 → 应用层翻译 `CAT-3002` |
| U-2 | **不同团队**插入相同 `sku_code` | **成功**（`§6` 规则 2 核心验证） |
| U-3 | 同 `(team_id, warehouse_id, sku_id)` 插两条余额 | 第二条被并发首建路径吸收（不报错） |
| U-4 | 同 `(team_id, idempotency_key)` 插两条流水（内容一致） | 返回原流水（幂等成功） |
| U-5 | 同上但 `qty_delta` 不同 | `INV-5003` |
| U-6 | 同 `(wh, sku, date)` 两次 upsert 销售 | 覆盖，行数仍为 1 |
| U-7 | 同团队同 `file_hash` 同 `biz_type` 二次上传 | `IMP-4004`，写 `DUPLICATE_REJECTED`（槽位 `NULL`） |
| U-8 | 同文件 `FORCE_REIMPORT` | 新批次成功，旧批次 `superseded_by_batch_id` 回填、`dedupe_hash = NULL` |
| U-9 | 同 `file_hash` **不同 `biz_type`** | 都成功 |
| U-10 | 同 `run_id` 下两条同 `(sku, wh)` 快照 | 第二条冲突 |
| U-11 | 两条**不同 `run_id`** 的同 `(sku, wh)` 快照 | 都成功（历史快照必须保留） |
| U-12 | 同采购单两条同 `line_no` | 第二条冲突 |
| U-13 | 同到货单两条同 `purchase_order_item_id` | 第二条冲突 |
| U-14 | 已知被冲销的流水再次冲销 | 冲突 → `INV-5003`（冲销唯一约束生效后） |
| U-15 | `idempotency_key = NULL` 的采购单多行 | 都成功（可空唯一索引允许多 `NULL`） |

### 5.2 `CHECK` 约束

| # | 用例 | 期望 |
| --- | --- | --- |
| C-1 | 写 `skus.status = 'DRAFT'`（非法枚举） | `CHECK` 违反 |
| C-2 | 写 `inventory_balances.on_hand_qty = -1` | `CHECK` 违反 |
| C-3 | 写流水使 `qty_after ≠ qty_before + qty_delta` | `CHECK` 违反 |
| C-4 | 写 `purchase_order_items.received_qty > ordered_qty` | `CHECK` 违反 |
| C-5 | 写 `replenishment_reviews`，`action='REJECT'` 且 `comment=NULL` | `CHECK` 违反 |
| C-6 | 写 `analysis_runs.progress = 101` | `CHECK` 违反 |
| C-7 | 写 `analysis_runs`，`data_start_date > data_end_date` | `CHECK` 违反 |
| C-8 | 写 `inventory_transactions.qty_delta = 0` | `CHECK` 违反 |
| C-9 | 写 `suppliers.lead_time_days = 0` | **成功**（0 合法：即时交付） |
| C-10 | 写 `suppliers.lead_time_days = NULL` | **成功**（未配置） |
| C-11 | MySQL 版本 < 8.0.16 时启动 | CI **断言失败并阻断**（防 `CHECK` 被静默忽略） |

### 5.3 外键 `RESTRICT`

| # | 用例 | 期望 |
| --- | --- | --- |
| F-1 | 删除仍有明细的采购单 | 外键 `RESTRICT` |
| F-2 | 删除被流水引用的 SKU | 外键 `RESTRICT` |
| F-3 | 停用 SKU 后查询历史 `sales_daily` | **成功返回**（历史数据必须可查，`§9.4`） |
| F-4 | 停用 SKU 后新建分析任务 | 该 SKU **不进入**分析范围 |

### 5.4 团队隔离（数据库层）

| # | 用例 | 期望 |
| --- | --- | --- |
| T-1.1 | 用 A 的 `team_id` 与 B 的 `sku_id` 直接 INSERT 流水 | **DB 层成功**（外键只校验存在性）→ 证明"必须有应用层同团队校验"（L4 必须存在） |
| T-1.2 | 应用层不注入 `team_id` 直接 INSERT | **失败**（`NOT NULL` 无默认值） |
| T-6.1 | 新增索引评审 | 断言索引首列为 `team_id`（白名单：系统级超时索引 `idx_run_timeout`） |

---

## 6. 跨团队权限测试

### 6.1 跨团队越权场景（5 个）

> 基准：团队 A 的用户（Token `team_id = A`）访问团队 B 的数据。**对象不存在与不属于本团队返回同一错误码**（防对象探测）。

| # | 场景 | 请求 | 期望 HTTP | 期望错误码 |
| --- | --- | --- | --- | --- |
| 1 | 路径越权（读） | `GET /api/v1/skus/{b_sku_id}` | 403 | `TEAM-2001` |
| 2 | 请求体伪造 | 创建采购明细，body 传 `teamId=B` 且 `skuId=b_sku_id` | 403 | `TEAM-2001`（`teamId` 被忽略，并记审计 `TEAM_CROSS_ACCESS_DENIED`） |
| 3 | 关联校验越权 | `sku_id` 属 A 但 `purchase_order_id` 属 B | 403 | `TEAM-2001`（**进入 DB 前失败**，无写入） |
| 4 | 状态操作越权 | A 审批 B 的建议 | 403 | `TEAM-2001`（不泄漏任何 B 的信息） |
| 5 | 团队停用 | A 团队被停用后任意业务接口 | 403 | `TEAM-2002`（**先判团队状态再判权限**） |

### 6.2 角色 × 关键操作 测试矩阵

> 角色代码见 `DESIGN_BASIS.md §5`；权限矩阵权威依据为 `USER_ROLES.md §2.2`。✅=期望成功，⛔=期望拒绝（错误码如表头）。

| 关键操作 | `OWNER` | `ADMIN` | `OPERATOR` | `APPROVER` | `VIEWER` | 期望拒绝码 |
| --- | --- | --- | --- | --- | --- | --- |
| 登录 | ✅ | ✅ | ✅ | ✅ | ✅ | —（失败 `AUTH-1002`） |
| 管理团队成员 | ✅ | ✅（不能移除 OWNER） | ⛔ | ⛔ | ⛔ | `TEAM-2003` |
| 管理团队配置 | ✅ | ✅ | ⛔ | ⛔ | ⛔ | `TEAM-2003` |
| 维护主数据 / 导入数据 | ✅ | ✅ | ✅ | ✅ | ⛔ | `TEAM-2003` |
| 查看库存 / 分析结果 / 建议 | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| 库存盘点调整 | ✅ | ✅ | ✅ | ✅ | ⛔ | `TEAM-2003` |
| 手工调整（`MANUAL_ADJUST`） | ✅ | ✅ | ⛔ | ⛔ | ⛔ | `TEAM-2003` |
| 创建分析任务 | ✅ | ✅ | ✅ | ✅ | ⛔ | `TEAM-2003` |
| 修改建议数量 | ✅ | ✅ | ✅ | ✅ | ⛔ | `TEAM-2003` |
| 提交审核 / 撤回 | ✅ | ✅ | ✅ | ✅ | ⛔ | `TEAM-2003` |
| **审批通过 / 驳回** | ✅ | ✅ | ⛔ | ✅ | ⛔ | `TEAM-2003` |
| 创建采购单（含起草） | ✅ | ✅ | ✅（仅 `DRAFT`） | ✅ | ⛔ | `TEAM-2003` |
| **采购单确认（`DRAFT→PENDING`）** | ✅ | ✅ | ⛔ | ✅ | ⛔ | `TEAM-2003` |
| 采购单取消 | ✅ | ✅ | ⛔ | ✅ | ⛔ | `TEAM-2003` |
| 到货登记 / 确认到货 | ✅ | ✅ | ✅（登记） | ✅ | ⛔ | `TEAM-2003` |
| 查看审计日志 | ✅ | ✅ | ⛔ | ⛔ | ⛔ | `TEAM-2003` |
| **跨团队任意操作（含 OWNER）** | ⛔ | ⛔ | ⛔ | ⛔ | ⛔ | `TEAM-2001` |
| **团队已停用时任意操作** | ⛔ | ⛔ | ⛔ | ⛔ | ⛔ | `TEAM-2002` |

> 表内具体行以 `USER_ROLES.md §2.2` 为准；本矩阵用于**驱动参数化测试**（角色 × 操作 × 期望）。

---

## 7. 库存并发与幂等测试

### 7.1 如何构造并发

- 使用 **`CountDownLatch` 起跑门 + 固定大小线程池**：所有线程 `await()` 到同一起点后同时发起操作。
- 每个线程使用**独立的 `team_id`+线程 ID 组合**或共享同一 `team_id`（视场景），操作目标为同一 SKU×仓库。
- 并发测试**不使用测试级事务回滚**（见 §4.2），测试后按 `team_id` 清理或使用一次性容器。
- 断言分两部分：① 各线程的**返回码分布**；② 结束后**数据库的最终状态一致性**。

### 7.2 期望结果

| # | 场景 | 期望 |
| --- | --- | --- |
| P-1 | 10 线程并发对同一 SKU **入库** | 全部成功（乐观锁重试）或部分 `INV-5002`；**最终余额 = Σ qty_delta** |
| P-2 | 10 线程**出库**，库存刚好够 5 次 | 恰好 5 次成功、5 次 `INV-5001`；**余额不为负** |
| P-3 | 2 线程同时**审批**同一建议 | **恰好 1 条成功**，另一条 `RPL-8001`/`RPL-8002`；审核记录无重复 `APPROVE` |
| P-4 | 2 线程同时**建议转采购** | 恰好 1 张采购单；另一条 `PO-9001` |
| P-5 | 2 线程同时**确认同一到货单** | 恰好 1 次入库；另一条返回已确认结果（幂等） |
| P-6 | 同 `idempotency_key` 并发 10 次**确认到货** | 只有 1 条流水；其余返回同一结果 |
| P-7 | 2 线程同时上传同一导入文件 | 1 个成功、1 个 `IMP-4004` |
| P-8 | 并发导入 1000 行同一 SKU 的初始库存 | 余额 = 1000 行之和（无丢失更新） |

### 7.3 余额与流水一致性的对账断言

**方法**：并发操作结束后，执行对账 SQL（示意，具体以 `DATABASE_DESIGN.md` 为准）：

```sql
-- ⚠️ 说明性示例，本项目尚未执行
SELECT b.team_id, b.warehouse_id, b.sku_id,
       b.on_hand_qty,
       COALESCE(SUM(t.qty_delta), 0) AS derived_qty
FROM inventory_balances b
LEFT JOIN inventory_transactions t
       ON t.team_id = b.team_id
      AND t.warehouse_id = b.warehouse_id
      AND t.sku_id = b.sku_id
GROUP BY b.team_id, b.warehouse_id, b.sku_id, b.on_hand_qty
HAVING b.on_hand_qty <> COALESCE(SUM(t.qty_delta), 0);
```

**断言**：上述查询返回**零行**（即"余额可由流水推导"）。这是 `ACCEPTANCE_CRITERIA.md §4` 的核心不变量在测试层的落地。

对账断言应作为**每个库存相关集成测试的收尾步骤**，并单独提供"随机 100 次库存操作后对账"的用例如（P-10）。

---

## 8. 采购与到货状态测试

### 8.1 状态转换表驱动测试

**采购单状态（`PurchaseOrderStatus`，见 `DESIGN_BASIS §9.13`）**：合法转换全通过、非法转换全被拒（`PO-9003`）。

| 起始状态 | 目标状态 | 合法性 | 期望 |
| --- | --- | --- | --- |
| `DRAFT` | `PENDING` | 合法 | 成功（仅 `APPROVER`+） |
| `DRAFT` | `CANCELLED` | 合法 | 成功 |
| `PENDING` | `ORDERED` | 合法 | 成功 |
| `PENDING` | `CANCELLED` | 合法 | 成功 |
| `ORDERED` | `PARTIALLY_RECEIVED` | 合法 | 成功（部分到货） |
| `ORDERED` | `RECEIVED` | 合法 | 成功（全部到货） |
| `ORDERED` | `CANCELLED` | 合法 | 成功 |
| `PARTIALLY_RECEIVED` | `PARTIALLY_RECEIVED` | 合法 | 成功（继续部分到货） |
| `PARTIALLY_RECEIVED` | `RECEIVED` | 合法 | 成功 |
| `PARTIALLY_RECEIVED` | `CANCELLED` | 合法 | 成功 |
| `RECEIVED` / `CANCELLED` | 任意 | **非法** | `PO-9003`（终态不可变） |
| `DRAFT` | `ORDERED`（跳级） | **非法** | `PO-9003` |
| `PENDING` | `RECEIVED`（跳级） | **非法** | `PO-9003` |

**建议单状态（`ReplenishmentItemStatus`，见 `DESIGN_BASIS §9.11`）**：同样以表驱动方式覆盖全部合法/非法转换；驳回未填意见必须被拒（`CHECK` + 应用层）。

### 8.2 部分到货 / 超额收货 / 重复收货

| # | 场景 | 期望 |
| --- | --- | --- |
| R-1 | 采购明细 `ordered=100`，到货 40 确认 | PO → `PARTIALLY_RECEIVED`；`received_qty=40`；产生 40 的入库流水 |
| R-2 | 在 R-1 基础上再到货 60 确认 | PO → `RECEIVED`；`received_qty=100` |
| R-3 | 到货数量 > 待收数量（如待收 60 但登记 70） | `PO-9002`；**无任何库存变更** |
| R-4 | 同一到货单重复确认 | 幂等：只入库一次，返回已确认结果 |
| R-5 | 同 `idempotency_key` 但内容不同的到货单 | `COM-0009` |
| R-6 | 多张到货单累计 `received_qty` 使总和 > `ordered_qty` | 第 N 张被拒 `PO-9002` |
| R-7 | 确认到货中途（PO 状态更新步）抛异常 | **全部回滚**：到货单仍 `DRAFT`、无流水、余额不变、`received_qty` 不变 |

### 8.3 状态由明细推导的断言

- 采购单 `status` 必须能**由明细到货情况正确推导**：全部明细 `received_qty == ordered_qty` → `RECEIVED`；存在 `received_qty > 0` 且未全部收齐 → `PARTIALLY_RECEIVED`。
- 测试对每种明细组合断言单头状态与派生结果一致（表驱动）。

---

## 9. Python 单元测试

### 9.1 框架与范围

- 使用 **pytest**；测试放在 `service-ai/tests/`。
- 范围：`analytics/sales_stats.py`、`rules/risk_rules.py`、`rules/replenishment.py`、`validation/data_quality.py`。
- 全部使用**固定 fixture 输入**，不访问网络、不读数据库。

### 9.2 规则计算测试（golden cases）

- **依据** `docs/04-ai/ANALYSIS_CONTRACT.md` 的 golden cases **`GC-01`~`GC-18`（至少 18 个）**：
  - 每个 golden case 为**固定输入 → 固定输出**，**逐字段断言**；
  - `enable_llm=false` 时，`llm_*` 字段恒定为空或 `SKIPPED`（`INVENTORY_RULES.md §10.3`）。
- golden cases 与 `INVENTORY_RULES.md §6` 的 20 个规则用例**互为补充**，共同构成 M4 回归基线。

### 9.3 数据质量分级测试

| # | 场景 | 期望 |
| --- | --- | --- |
| Q-1 | 窗口内有效天数 `n >= MIN_DAYS` | 可正常计算 `D` |
| Q-2 | `n < MIN_DAYS` | `DATA_INSUFFICIENT`、`D = null`、**不产出建议** |
| Q-3 | `n = 0`（无数据） | 同 Q-2 |
| Q-4 | 存在超出 `mean ± 3σ` 的极端日 | 截尾（最多 2 天）后计算 |
| Q-5 | `mean = 0` | `CV = null`（`§10.1`） |

### 9.4 边界值测试

| # | 边界 | 期望 |
| --- | --- | --- |
| BV-1 | **0 销量**（窗口全 0） | `D = 0`；不触发缺货规则（`D > 0` 不满足） |
| BV-2 | **负值输入**（异常数据） | 被数据质量层标记/剔除；不得产出负的 `D`/`Q` |
| BV-3 | **极端波动**（`CV > CV_HIGH`） | `SS_FACTOR` 上浮 50%，`volatility_adjusted = true`（`§10.3`） |
| BV-4 | **空序列** | 归入数据不足，不猜测 |
| BV-5 | `n = MIN_DAYS`（恰好等于 7） | **不**判数据不足，正常计算 |
| BV-6 | `Q_base <= 0` | `Q_suggest = 0`，不生成建议，仅记风险（`§10.5`） |

---

## 10. Python API 测试

- 使用 **FastAPI `TestClient`**，覆盖 `/internal/v1/analysis:validate`、`POST /analysis/runs`、状态查询、`:cancel`。
- **契约测试**：响应体必须**符合 Pydantic Schema**（字段名、类型、必填项、枚举取值、精度）；与 `AI_SERVICE_CONTRACT.md` 对齐。
- **超时与错误路径**：模拟 AI 内部超时（返回 `ANL-7003` 语义）、校验失败（`ANL-7004` 语义）、非法入参（`COM-0001` 语义）。
- **鉴权**：缺少或错误 `X-Internal-Token` 必须被拒；`X-Request-Id` 必须透传（`§13.3`）。

---

## 11. 规则引擎测试（风险规则用例表）

> 每条规则**至少 3 个用例**：命中边界 / 未命中 / 数据不足（`DESIGN_BASIS §11.3`）。
> 规则判定逻辑见 `DESIGN_BASIS §11`；用例与 `INVENTORY_RULES.md §6.1` 及 golden cases 对齐。

| 规则代码 | 用例名 | 输入摘要 | 期望风险等级 / 风险类型 |
| --- | --- | --- | --- |
| `R-STOCKOUT` | `stockout_zero_onhand`（命中） | `D=5.000, on_hand=0` | `STOCKOUT` / `HIGH` |
| `R-STOCKOUT` | `stockout_boundary_2days`（命中边界） | `D=5.000, on_hand=10.000`（`/D=2.000`） | `STOCKOUT` / `HIGH` |
| `R-STOCKOUT` | `stockout_within_leadtime`（命中） | `on_hand/D <= L`（不大于 2） | `STOCKOUT` / `MEDIUM` |
| `R-STOCKOUT` | `stockout_not_hit`（未命中） | `D=2.000, on_hand=100.000`（`/D=50 > L`） | 未命中 |
| `R-STOCKOUT` | `stockout_data_insufficient`（数据不足） | `D=null`（`n=6`） | 跳过（由数据不足规则处理） |
| `R-LOWSTOCK` | `lowstock_hit`（命中边界） | `D=2.000, L=R=7, IP=20`（< 28） | `LOW_STOCK` / `MEDIUM` |
| `R-LOWSTOCK` | `lowstock_not_hit`（未命中） | `IP=100 ≥ D×(L+R)=28` | 未命中 |
| `R-LOWSTOCK` | `lowstock_data_insufficient`（数据不足） | `D=null` | 跳过 |
| `R-OVERSTOCK` | `overstock_medium`（命中边界） | `D=1.000, on_hand=95.000`（`/D=95 > 90`） | `OVERSTOCK` / `MEDIUM` |
| `R-OVERSTOCK` | `overstock_high`（命中边界） | `D=1.000, on_hand=181.000`（`/D=181 > 180`） | `OVERSTOCK` / `HIGH` |
| `R-OVERSTOCK` | `overstock_not_hit`（未命中） | `D=1.000, on_hand=30.000`（`/D=30 ≤ 90`） | 未命中 |
| `R-OVERSTOCK` | `overstock_data_insufficient`（数据不足） | `D=null` | 跳过 |
| `R-SLOWMOVING` | `slowmoving_hit`（命中） | 近 30 天总销量 = 0，`on_hand=50` | `SLOW_MOVING` / `MEDIUM` |
| `R-SLOWMOVING` | `slowmoving_not_hit`（未命中） | 近 30 天总销量 = 3（>0） | 未命中 |
| `R-SLOWMOVING` | `slowmoving_no_stock`（边界） | 近 30 天销量 = 0，`on_hand=0` | 未命中（`on_hand > 0` 不满足） |
| `R-SALESANOMALY` | `anomaly_hit`（命中） | 近 7 天日均 10，前 21 天日均 2，std 1，变化率 > 50% | `SALES_ANOMALY` / `≥ LOW`（仅提示） |
| `R-SALESANOMALY` | `anomaly_not_hit`（未命中） | 近 7 天日均 2.5，前 21 天日均 2，std 4 | 未命中 |
| `R-SALESANOMALY` | `anomaly_data_insufficient`（数据不足） | 前 21 天无数据（`n < 21`） | 跳过 |
| `R-DATAINSUFFICIENT` | `datainsufficient_hit`（命中） | 窗口内 6 天有数据（`n < 7`） | `UNKNOWN`，`D=null`，`q_suggest=null` |
| `R-DATAINSUFFICIENT` | `datainsufficient_zero`（命中边界） | 无销售数据（`n=0`） | 同命中用例 |
| `R-DATAINSUFFICIENT` | `datainsufficient_not_hit`（未命中边界） | `n=7`（恰好等于 `MIN_DAYS`） | 不判数据不足，正常计算 |

**风险等级合成断言**（`§11.1`）：`risk_level = MAX(各规则命中等级)`；`UNKNOWN` **仅当所有规则都无法判定**时使用，不是 `MAX` 的产物。

> ✅ **已裁定（M0 审查）**：`R-LOWSTOCK` 与 `R-STOCKOUT` **可共存**（`risk_types` 为数组，`risk_level` 取较高者），与 golden case **GC-02** 一致。SSOT `§11` 已同步删除「且不满足 `R-STOCKOUT`」。测试按可共存断言，无待决项。

---

## 12. 模型调用测试

### 12.1 强制要求

> **必须使用 Mock / 固定 fixture；禁止所有测试都调用真实模型 API。**

**理由**：

| 维度 | 说明 |
| --- | --- |
| **成本** | 真实模型按 token 计费，CI 上每次全量回归都会产生不可控费用 |
| **稳定性** | 真实模型输出具有随机性与版本漂移，会导致测试"偶发失败"，破坏可复现性（违反通用原则 G8） |
| **速度** | 真实调用有网络延迟（数秒~数十秒），使 CI 变慢，降低提交频率 |
| **可测性** | 降级路径（超时、非法 JSON、Schema 失败、编造数字）**无法**用真实模型稳定构造，必须 Mock |

### 12.2 Mock 策略

| # | 手段 | 说明 |
| --- | --- | --- |
| M-1 | **Mock httpx 响应** | 用 `respx` / `httpx.MockTransport` 拦截 LLM 调用，返回固定响应体 |
| M-2 | **内置 fixture JSON** | 在 `tests/fixtures/` 放置"合法输出""非法 JSON""字段缺失""非法枚举""编造数字""超时"等固定样本 |
| M-3 | **依赖注入** | LLM 客户端通过接口注入，测试注入 Fake 客户端 |
| M-4 | **真实调用仅限人工演示** | 仅在人工端到端演示（M7）时可选用真实调用，**不进 CI、不作为验收唯一依据** |

### 12.3 降级路径测试

| # | 构造方式 | 期望 |
| --- | --- | --- |
| DG-1 | Mock 超时（超过 30s 语义） | `LlmStatus = TIMEOUT` → `AnalysisRunStatus = DEGRADED`；**规则结果完整保留** |
| DG-2 | Mock 返回非法 JSON | `LlmStatus = VALIDATION_FAILED` → `DEGRADED` |
| DG-3 | Mock 返回 Schema 校验失败（缺字段/类型错） | 同上 |
| DG-4 | Mock 返回**含编造数字**（引用不存在的 SKU/数量） | 校验拦截 → `VALIDATION_FAILED` → `DEGRADED`；**不得把编造数字写入 `analysis_items`** |
| DG-5 | 关闭 LLM（`enable_llm=false`） | `LlmStatus = SKIPPED`；任务可为 `SUCCEEDED`（规则成功） |
| DG-6 | Mock 5xx / 网络错误（可重试） | 按 `§13.4` 最多 2 次指数退避；仍失败 → `DEGRADED` |

> **核心不变量**：**LLM 失败不得丢失规则结果**（`ACCEPTANCE_CRITERIA.md §4` 不变量 11）。

---

## 13. AI 输出结构校验测试

| # | 校验项 | 用例 | 期望 |
| --- | --- | --- | --- |
| S-1 | Schema 符合性 | 缺必填字段 / 多余字段 | 校验失败 → `ANL-7004` 语义 / `VALIDATION_FAILED` |
| S-2 | 必填字段 | 解释文本为空 | 拒绝 |
| S-3 | **精度** | 数量出现 > 3 位小数、金额 > 2 位 | 拒绝或按 `§14.2` 量化（按契约规定） |
| S-4 | **非法枚举值** | `risk_level = 'CRITICAL'` | 拒绝 |
| S-5 | **超范围 SKU** | 输出了请求中不存在的 `sku_id` | 拒绝（防编造） |
| S-6 | 长度限制 | 超长文本 | 按契约截断或拒绝 |
| S-7 | 语言与格式 | 非中文/非约定格式 | 按契约处理（首期输出为简体中文，`§2`） |

> 校验失败时：Java 侧必须返回 `ANL-7004` 且**不落库**（`API_CONTRACT.md`、`DESIGN_BASIS §12.3`）。

---

## 14. 前端测试

### 14.1 首期范围结论

**结论：首期采用"Vitest 组件测试（覆盖关键交互）+ 手工联调清单（覆盖业务闭环）"，不引入 E2E 框架（如 Playwright / Cypress）。**

| 方案 | 首期 | 说明 |
| --- | --- | --- |
| Vitest + Vue Test Utils（关键交互组件） | ✅ **采用** | 覆盖登录表单校验、列表筛选、状态标签渲染、轮询启动/卸载等关键交互 |
| 手工联调清单 | ✅ **采用** | 覆盖 20 步业务闭环（见 §15），是首期"端到端"的主要手段 |
| E2E 框架（Playwright/Cypress） | ❌ **首期不引入** | 见下 |

**不引入 E2E 框架的理由**：

1. **成本收益不匹配**：E2E 需要维护浏览器环境、选择器、测试数据，且易受异步/LLM 影响而脆弱；首期业务闭环已由手工联调清单 + Java 集成/端到端脚本覆盖。
2. **首期单人为主**：维护一套 E2E 的相对成本高。
3. **前端不承载业务规则**（`MODULE_BOUNDARIES.md §6`），前端缺陷主要集中在交互与展示，组件测试 + 手工清单足够。

**未来评估条件**（满足任一即重新评估引入 E2E）：

- 前端页面数量或交互复杂度显著上升，手工回归成为瓶颈；
- 出现**只在浏览器端复现**的缺陷（如轮询泄漏、时序问题）；
- 团队具备多人并行且需要"提交即回归"的自动化保障；
- 引入 CI 浏览器环境成本可接受。

### 14.2 关键交互测试清单（计划）

- 登录表单校验（必填、格式、错误码提示）。
- 列表页筛选 + 分页 + 空态展示。
- `risk_level` / 状态标签的语义色映射（`INVENTORY_RULES.md §6.2`）。
- 分析进度轮询：页面卸载后停止轮询（`NFR-PERF-05`）。
- 前端权限仅体验层：无权限按钮隐藏/禁用，但**不依赖前端做安全**（`USER_ROLES.md §4`）。

---

## 15. 前后端联调测试

### 15.1 手工联调清单（20 步业务闭环）

> 在完整本地环境（Compose 起三端 + MySQL）上执行；每步记录**操作、期望结果、实际结果**。

| # | 操作 | 期望结果 |
| --- | --- | --- |
| 1 | 用 `OWNER` 账号登录 | 登录成功，返回令牌；进入主页 |
| 2 | 创建/启用默认仓库 | 仓库列表出现默认仓 |
| 3 | 录入/导入供应商（含交期） | 供应商可查，交期正确 |
| 4 | 导入商品（`PRODUCT`） | 批次 `COMPLETED`；商品可查 |
| 5 | 导入 SKU（`SKU`） | 批次 `COMPLETED`；SKU 可查 |
| 6 | 导入库存快照（`INVENTORY_SNAPSHOT`） | 批次 `COMPLETED`；余额出现，流水为 `INIT_IMPORT` |
| 7 | 导入历史销售（`SALES_DAILY`，≥28 天） | 批次 `COMPLETED`；销售可查 |
| 8 | **重复上传同一销售文件** | 返回 `IMP-4004`；未产生重复数据 |
| 9 | 上传一个含错误行的 SKU 文件 | 批次 `PARTIAL_SUCCESS`；错误报告可下载 |
| 10 | 查看库存总览 | 展示 `on_hand_qty` 与 `available_qty`；口径说明可见 |
| 11 | 执行一次盘点调整 | 产生 `COUNT_ADJUST` 流水；余额同步变化 |
| 12 | 创建分析任务（`ALL_ACTIVE`） | 同步预检通过；返回 `run_id`；任务 `PENDING→RUNNING` |
| 13 | 轮询分析进度 | 进度 `progress` 递增；页面卸载后轮询停止 |
| 14 | 分析完成 | 任务 `SUCCEEDED` 或（LLM 关闭时）`DEGRADED`；`analysis_items` 快照生成 |
| 15 | 查看分析结果 | 风险等级/类型正确；`D`/`IP`/`Q_suggest` 有值或明确数据不足 |
| 16 | 查看补货建议并修改数量 | 建议 `SUGGESTED`；改量成功（乐观锁正常） |
| 17 | 运营提交审核 | `SUGGESTED→PENDING_REVIEW`；审核记录 `SUBMIT` |
| 18 | 审批人批准建议 | `PENDING_REVIEW→APPROVED`；审核记录 `APPROVE` |
| 19 | 由已审批建议生成采购单并确认下单 | 建议 `CONVERTED`；采购单 `DRAFT→PENDING→ORDERED`；**此时库存不变** |
| 20 | 到货登记并确认到货 | 产生 `PURCHASE_INBOUND` 流水；余额增加；采购单 `RECEIVED`；**对账 `Σ流水 == 余额`** |

> 附加收尾步：用 `VIEWER` 账号复核只读权限；用另一团队账号尝试跨团队访问，期望 `TEAM-2001`。

### 15.2 契约一致性检查（API_CONTRACT ↔ 前端类型）

| # | 检查项 | 方法 |
| --- | --- | --- |
| 1 | 字段名一致 | 对照 `API_CONTRACT.md` 与 `types/*.ts`，逐字段核对 |
| 2 | 数值精度 | 数量 3 位 / 单价 4 位 / 金额 2 位；前端不做精确算术（`§14.2`） |
| 3 | 时间格式 | 时间戳 ISO-8601 UTC；业务日期 `YYYY-MM-DD`（`§14.1`） |
| 4 | 枚举取值 | 与 `§9` 完全一致 |
| 5 | 分页结构 | 统一分页字段一致 |
| 6 | 错误响应 | 统一响应体 + 错误码（`ERROR_CODES.md`）一致 |
| 7 | 一一对应 | `api/*.ts` 与 `types/*.ts` 的文件与函数命名对应（`PROJECT_STRUCTURE.md §6`） |

---

## 16. Docker Compose 部署验证

### 16.1 验证清单

| # | 验证项 | 方式 | 期望 |
| --- | --- | --- | --- |
| 1 | 所有服务健康 | `docker compose ps` | `mysql`/`backend-java`/`service-ai`/`nginx` 均 `healthy` |
| 2 | 前端可访问 | 浏览器打开 `http://localhost` | 页面加载 |
| 3 | 后端可访问 | `GET /actuator/health` | `UP` |
| 4 | Flyway 迁移成功 | 启动日志 / `flyway_schema_history` | 全部 `Success` |
| 5 | 数据库可连接 | 从容器内连接 `mysql` | 连接成功 |
| 6 | **Python 内部接口不可从公网/宿主机访问** | 宿主机 `curl http://localhost:8000/internal/v1/health` | **连接失败**（`§13.3`；Compose 中 `service-ai` 不映射 `ports`） |
| 7 | 日志可查 | `docker compose logs <service>` | 有结构化日志，含 `requestId` |
| 8 | 卷持久化 | 重启 Compose 后查看数据 | 数据仍在（MySQL 数据卷） |

### 16.2 测试数据管理

| # | 规则 |
| --- | --- |
| TD-1 | **固定 seed 数据**：提供一套脱敏的演示数据（商品/SKU/供应商/库存/销售），用于验收与演示，**版本化**存放 |
| TD-2 | **Testcontainers 与本地库隔离**：集成测试使用临时容器，**绝不连接本地开发库** |
| TD-3 | **禁止在生产库跑测试**：测试连接串必须指向测试/本地环境；代码与脚本中不得存在指向生产的默认连接 |
| TD-4 | **敏感数据脱敏**：演示数据不得含真实客户/交易信息 |
| TD-5 | **可清理**：测试产生的数据可按 `team_id` 清理（见 §4.2） |

---

## 17. 覆盖率与质量门槛

### 17.1 门槛（明确但不过度）

| 范围 | 门槛 | 理由 |
| --- | --- | --- |
| **规则计算（Python `rules/`）** | 行覆盖 **≥ 85%** | 规则判定是产品可信度核心；golden cases + 规则用例已接近全覆盖 |
| **Java `domain` 层** | 行覆盖 **≥ 70%** | 状态机与不变量逻辑必须充分覆盖；但不追求对 DTO/样板代码覆盖 |
| **整体（Java + Python）** | 行覆盖 **≥ 60%** | 保证主要路径与关键分支被覆盖 |
| **规则用例通过率** | **100%** | 6 条规则 × ≥3 用例 + 18 golden cases 必须全通过 |
| **跨团队越权矩阵** | **100%** | 隔离是硬约束，不允许遗漏 |

### 17.2 为什么**不追求 100%**

- 100% 覆盖会产生大量**无价值测试**（getter、配置类、DTO、框架胶水代码），维护成本高于收益。
- 覆盖率是**手段不是目的**：本项目真正的质量目标是"**业务不变量成立**"（见 `ACCEPTANCE_CRITERIA.md §4`），而非行覆盖数字。
- 与其追求 100%，不如把精力投入到**关键路径**（规则、隔离、并发、一致性）的**深用例**上。

### 17.3 质量门槛的执行

- 覆盖率工具（Java 用 JaCoCo，Python 用 `pytest-cov`）在 CI 中生成报告；**低于门槛应阻断合并**（阈值可配置，但不得下调至低于本文档规定）。
- 覆盖率**不是**合并的唯一条件：关键用例缺失时，即使覆盖率达标也不应合并（以评审判断为准）。

---

## 18. 测试状态：真实 vs 计划

> 本节是对本项目测试现状的**正式、可核查的声明**，要求在任何汇报、评审、验收中**严格区分"计划"与"真实"**。

### 18.1 截至 M0 的真实状态（**计划态**）

| 项 | 真实状态 |
| --- | --- |
| 已编写的测试文件数量 | **0** |
| 已执行的测试数量 | **0** |
| 已通过的测试数量 | **0**（不存在任何"通过"的结果） |
| 已执行的 Docker 部署次数 | **0** |
| 已执行的数据库迁移次数 | **0** |
| 已执行的模型/LLM 调用次数 | **0** |
| 覆盖率报告 | **不存在** |

**因此**：本文档中出现的所有"用例""门槛""清单"**均为计划**；**不得**据此声称"测试已通过""验证成功""已部署""已迁移"。

### 18.2 证据形式要求（后续每个里程碑强制）

每个里程碑（M1 起）在声明"测试通过"时，**必须**同时提供：

1. **执行的命令原文**（可复制、可复现，如 `mvn verify`、`pytest -q`、`docker compose ...`）。
2. **真实的命令输出**（测试摘要、通过/失败数、失败堆栈如有）。
3. **日志或截图片段**（关键链路、`requestId`、异常）。
4. **对失败/未达成项的说明**（不得省略、不得美化）。

### 18.3 禁止事项

- ❌ **禁止**出现"测试已通过""验证成功""已部署""已迁移"等**无证据**的表述。
- ❌ **禁止**用"应该可以""按设计可行"替代真实执行。
- ❌ **禁止**用 Mock 结果冒充真实模型调用结果。
- ❌ **禁止**只报告成功率而不报告失败与跳过数。

> 该要求与 `ACCEPTANCE_CRITERIA.md §8`（验收证据要求）和 `DEVELOPMENT_PLAN.md §15`（里程碑停止确认）一致，并受 `AGENTS.md` 约束。

---

## 19. 测试风险与局限

| # | 风险 / 局限 | 说明 | 缓解 |
| --- | --- | --- | --- |
| 1 | **测试尚未编写与执行** | 截至 M0，全部测试为计划状态（见开头诚实声明） | 每个里程碑按 `DEVELOPMENT_PLAN.md` 落地测试并报告**真实结果** |
| 2 | **真实模型行为不可完全复现** | Mock 无法覆盖真实模型的全部输出形态 | 保持"LLM 不参与判定 + Schema 校验 + 降级"三重防线；人工演示时可抽查真实调用 |
| 3 | **E2E 覆盖受限** | 首期不引入 E2E 框架，端到端依赖手工清单 | 手工清单尽量详尽（20 步）；满足 §14.1 触发条件时引入 E2E |
| 4 | **并发测试的非确定性** | 线程调度不确定，可能出现"偶发通过" | 用例断言"最终状态一致性"而非"特定线程成功"；必要时反复运行；关键不变量用 DB 约束兜底 |
| 5 | **Testcontainers 依赖 Docker** | CI 若无 Docker 则集成测试不可运行 | 明确 CI 前置条件；本地以 Docker Desktop 为准；CI 提供 Docker |
| 6 | **演示数据代表性有限** | 演示数据规模小于真实，性能问题可能被掩盖 | M7 用更大规模数据做性能验证（1 万 SKU / 1 万行导入 / 500 SKU 分析） |
| 7 | **覆盖率数字可能被"刷"** | 为达标而写无断言测试 | 以关键用例与不变量为准，评审时审查测试有效性 |

---

## 20. 与 SSOT 的一致性声明

| 检查项 | 结论 |
| --- | --- |
| 错误码引用 | 只引用 `§12`，不自造 |
| 状态枚举 | 与 `§9` 一致；表驱动测试覆盖合法/非法转换 |
| 规则与用例 | 与 `§11`、`§11.3` 一致；6 条规则各 ≥3 用例 |
| golden cases | 对齐 `ANALYSIS_CONTRACT.md`（GC-01~GC-18，≥18 个） |
| 精度 | 与 `§14` 一致（数量 3 位 / 单价 4 位 / 金额 2 位） |
| 隔离 | 与 `§6` 六条硬规则一致，越权矩阵为必备测试 |
| 待决策引用 | 只引用 `D-01`~`D-08`；其他写作"（编号待 SSOT 统一，见 `DESIGN_REVIEW.md`）" |
| 执行状态 | 全部为**计划**；无任何测试被编写或执行 |

---

## 21. 变更记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-14 | M0 定稿：测试金字塔、分层工具表、Java/Python/前端/联调/部署测试、规则与模型调用测试、覆盖率门槛、风险与局限、诚实声明 | 工程效能 |
