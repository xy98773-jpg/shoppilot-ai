# ShopPilot AI 编码规范（CODING STANDARDS）

## 文档元信息

| 项 | 内容 |
| --- | --- |
| 文档名称 | ShopPilot AI 编码规范 |
| 文档路径 | `docs/06-development/CODING_STANDARDS.md` |
| 文档优先级 | P5（见 `DESIGN_BASIS.md §1.1`） |
| 所属阶段 | M0 定稿；M1 起强制执行 |
| 版本 | v1.0（M0 定稿） |
| 上游权威来源 | `DESIGN_BASIS.md`（§4 命名、§5 角色、§6 团队隔离、§7 模块、§9 枚举、§12 错误码、§14 精度、§16 文档同步）、`docs/01-architecture/PROJECT_STRUCTURE.md`、`docs/01-architecture/MODULE_BOUNDARIES.md`、`docs/03-api/AI_SERVICE_CONTRACT.md`、`docs/02-database/DATABASE_CONSTRAINTS.md` |
| 主要读者 | 全部开发者、code review、AI 协作方 |

### 本文档负责什么

- 规定本项目**通用编码原则、Git 规范、各技术栈编码规范、数据库变更规范、文档同步规范**与**代码评审自检清单**。
- 为 `AGENTS.md` 与 `MODULE_BOUNDARIES.md §9` 的检查清单提供**具体执行条款**。

### 本文档不负责什么

- **不定义**任何术语、枚举值、错误码、公式、字段名（唯一权威处为 `DESIGN_BASIS.md`，本文档只引用）。
- **不定义**测试用例（见 `TEST_STRATEGY.md`）与验收标准（见 `ACCEPTANCE_CRITERIA.md`）。
- **不规定**具体版本号（见 `TECH_STACK.md`）。

> **状态声明**：截至 M0，本项目**尚无任何代码**。本文档中的代码示例均为**示意**，用于说明规范，**不代表任何已实现的代码**；示例未经过编译或运行验证。

---

## 1. 通用原则（General Principles）

| # | 原则 | 说明 | 反例 |
| --- | --- | --- | --- |
| G1 | **小步提交** | 一次提交只做一件事，提交信息能独立解释；避免"巨型提交" | 一个 commit 同时改 8 个模块 + 改文档 |
| G2 | **先写测试，再写实现** | 规则计算、状态机、幂等等可直接判定的逻辑，先写用例（TDD 倾向） | 先实现规则再补测试，导致测试迁就实现 |
| G3 | **不为形式引入抽象** | 无多实现需求就不建接口/工厂/基类（`DESIGN_BASIS §4.2` 已明确只用 4 层、不做 Repository 接口分离） | 为每个实体建 `XxxRepository` + `XxxRepositoryImpl` |
| G4 | **错误必须显式处理** | 不允许吞异常、不允许"失败静默返回成功"；用户可见错误必须给稳定错误码（`§12`） | `catch (Exception e) { return null; }` |
| G5 | **不吞异常** | 捕获后必须处理（转换/记录/重抛）；`catch` 块禁止为空 | `catch (Exception e) {}` |
| G6 | **显式优于隐式** | 依赖显式注入、状态显式流转、字段显式映射；避免隐式懒加载、隐式默认值 | JPA 式隐式懒加载（本项目不用 JPA） |
| G7 | **单一职责与最小依赖** | 类与方法只做一件事；模块只依赖 `§7.1` 允许的模块 | `catalog` 里出现 `InventoryBalance` |
| G8 | **可复现优先** | 计算、导入、迁移结果必须可复现（固定精度、固定排序、固定版本记录） | 用 `HashMap` 迭代顺序影响输出 |
| G9 | **失败要快、要清楚** | 校验尽早失败，错误信息定位到字段/行号；不把错误推迟到深处 | 导入第 9000 行才发现必填列缺失 |
| G10 | **不写死环境与密钥** | 一切环境相关值走配置/环境变量；仓库内不得出现真实密钥 | 代码里写死数据库密码 |

### 1.1 提交前必做的三件事

1. **查看 `git diff`**：逐行确认将要提交的内容（见 §2.3）。
2. **本地跑通该变更的测试**：至少跑与被改代码相关的测试。
3. **核对文档同步**：若本次变更命中 `DESIGN_BASIS.md §16` 的同步责任表，必须同 PR 更新对应文档（见 §8）。

---

## 2. Git 规范

### 2.1 分支模型（首期简化）

首期**采用 `main` + 特性分支 `feat/xxx` 的简化模型**，不引入 `develop`/`release`/`hotfix` 多分支 GitFlow。

| 分支 | 用途 | 规则 |
| --- | --- | --- |
| `main` | 稳定主线，任何时刻可构建、可运行 | 只接受通过审查的合并；**禁止直接 push**；**禁止 `git push --force`** |
| `feat/<简短描述>` | 单个功能/任务分支，如 `feat/m3-inventory-write-service` | 从 `main` 切出；完成后合并回 `main` |
| `fix/<简短描述>` | 缺陷修复分支，如 `fix/inv-5002-retry` | 同上 |
| `docs/<简短描述>` | 纯文档变更 | 同上 |

**为什么简化**：

- 首期**单人开发为主**（`PRD.md §2` 描述的都是小团队），GitFlow 的多分支会带来频繁的合并与回滚成本，收益不匹配。
- 首期每个里程碑内部**不发布多个版本**，`release` 分支无意义。
- 简化模型与"每个里程碑停止并等待确认"（`DEVELOPMENT_PLAN.md §15`）配合：里程碑结束时可从 `main` 打标签 `v0.x.y`。

**何时升级**：当出现**多人并行开发同一里程碑**且需要独立发布节奏时，再评估引入 `develop` 分支，并需更新本文件与 `README.md`。

### 2.2 提交信息规范（Conventional Commits + 中文描述）

**格式**：

```
<type>(<scope>): <中文简述>

[可选正文：为什么这么改，而非改了什么]

[可选脚注：关联任务编号，如 M3-T09 / F-INV-01]
```

| `type` | 含义 | 示例 |
| --- | --- | --- |
| `feat` | 新功能 | `feat(inventory): 新增 InventoryWriteService 入库能力` |
| `fix` | 缺陷修复 | `fix(inventory): 修复并发入库丢失更新导致余额偏差` |
| `docs` | 文档变更 | `docs(06-development): 补充 M3 硬门槛测试说明` |
| `test` | 测试变更 | `test(replenishment): 补充双审批并发用例` |
| `refactor` | 重构（不改行为） | `refactor(catalog): 抽取同团队校验到 TeamScopeValidator` |
| `chore` | 构建/依赖/杂项 | `chore(deps): 锁定 mybatis-plus 版本` |
| `perf` | 性能优化 | `perf(import): 导入改按 500 行分批提交` |

**scope 取值**：Java 模块名（`identity`/`catalog`/`importdata`/`inventory`/`sales`/`analysis`/`replenishment`/`purchasing`/`audit`/`common`）、`service-ai`、`frontend-web`、`deploy`、`docs`。

**示例**：

```
feat(analysis): 新增分析任务创建接口并实现并发保护

- 同 idempotency_key 返回原 run
- 同团队已有进行中运行返回 ANL-7001

关联任务：M4-T03
```

**要求**：

- 简述用**中文**，动词开头，不超过 50 字。
- 一次提交**只含一个 type 的语义**；若同时改功能与文档，优先拆成两个提交。
- **禁止**无意义提交信息（`update`、`fix bug`、`111`）。

### 2.3 提交前必查 `git diff`

**规则**：`git commit` 之前**必须**执行并**逐行阅读**以下命令输出：

```bash
git status            # 确认改动文件范围符合预期
git diff              # 确认未提交改动的内容，逐行核对
git diff --staged     # 若已 add，核对暂存区内容
```

**目的**：拦截误提交（密钥、`.env`、调试代码、临时文件、构建产物）。

### 2.4 禁止提交的内容

| 禁止提交 | 说明 | `.gitignore` 应覆盖 |
| --- | --- | --- |
| `.env` / `.env.*`（除 `.env.example`） | 含真实密钥与密码 | ✅ |
| 任何密钥、Token、证书、私钥 | `AI_INTERNAL_TOKEN`、`LLM_API_KEY`、`JWT_SECRET` 等 | ✅ |
| 构建产物 | `target/`、`dist/`、`build/`、`*.class`、`*.jar` | ✅ |
| IDE 配置 | `.idea/`、`*.iml`、`.vscode/`（除团队统一配置） | ✅ |
| 依赖目录 | `node_modules/`、`.venv/`、`venv/` | ✅ |
| 日志与临时文件 | `*.log`、`*.tmp`、本地数据库卷目录 | ✅ |
| 大体积数据文件 | 演示 CSV/Excel 原始文件的敏感版本 | ✅（仅保留脱敏样例） |

> `.env.example` 中**只允许占位符**（如 `LLM_API_KEY=changeme`），**不得含真实值**（`PROJECT_STRUCTURE.md §7.2`）。

### 2.5 PR 规范（首期单人开发的简化流程）

**首期流程**：

1. 特性分支完成后，推送到远端并开 PR（Pull Request）到 `main`。
2. 即使单人开发，**也建议开 PR**：PR 描述即"本次变更做了什么、为什么、如何验证"，形成可追溯记录。
3. **必须完成自审清单**（§8.2）后合并。
4. 合并策略：优先 **Squash merge**（保持 `main` 线性、每个提交可独立解释）。

**PR 描述模板（建议）**：

```markdown
## 变更内容
- （做了什么）

## 关联任务
- M3-T09 / F-INV-01

## 如何验证
- （具体命令 + 真实输出摘要）

## 文档同步
- [ ] 无需更新文档
- [ ] 已更新：（列出文档与章节）

## 遗留与风险
- （无 / 列出）
```

### 2.6 硬性 Git 禁令

- ❌ **禁止 `git push --force`（含 `--force-with-lease`）到 `main`**。
- ❌ 禁止跳过 hooks（`--no-verify`）。
- ❌ 禁止直接向 `main` 提交功能代码。
- ❌ 禁止在提交历史中出现真实密钥（若误提交，需**立即轮换密钥**并记录到 `DESIGN_REVIEW.md`）。
- ❌ 禁止使用 `git reset --hard` 丢弃他人/自己未确认的工作（优先用 `git stash` 或新分支保留）。

---

## 3. Java 编码规范

### 3.1 包结构与分层职责

包结构（引用 `DESIGN_BASIS.md §4.2` 与 `PROJECT_STRUCTURE.md §4`）：

```
com.shoppilot.<module>.{api, application, domain, infrastructure}
```

| 层 | 职责 | 命名后缀 | 禁止 |
| --- | --- | --- | --- |
| `api` | 对外 REST 控制器、请求/响应 DTO | `*Controller`、`*Request`、`*Response` | 禁止写业务逻辑；禁止直接调 Mapper；**禁止把 `teamId` 作为请求参数**（`DESIGN_BASIS §6 第 3 条`） |
| `application` | 用例编排、**事务边界**、跨模块协调 | `*AppService`、`*Command`、`*Query` | 禁止直接暴露给前端；禁止承载纯计算规则（规则归 `domain` / Python） |
| `domain` | 领域模型、业务不变量、领域服务、状态机 | `*DomainService`、`*Policy`、实体 | 禁止依赖 `infrastructure` 的具体实现；禁止出现 Web/MyBatis 注解 |
| `infrastructure` | MyBatis-Plus Mapper、外部客户端 | `*Mapper`、`*Client`、`*Repository` | 禁止被其他模块直接注入（`MODULE_BOUNDARIES.md §4.1`） |

**模块依赖方向（强制，`DESIGN_BASIS §7.1`）**：

```
purchasing → replenishment → analysis → {inventory, sales} → catalog → identity → common
```

- 禁止反向依赖（如 `catalog → inventory`、`analysis → purchasing`、`inventory → purchasing`）。
- 跨模块调用只能经对方 `application` 层服务接口，**禁止注入对方 Mapper**。

### 3.2 命名

- 遵循 `DESIGN_BASIS.md §4.2` 的后缀约定；类名用大驼峰，方法/变量用小驼峰，常量全大写下划线。
- 领域枚举放在**拥有该状态的模块**内（不在 `common`）。
- 布尔字段/变量用 `is` / `has` 前缀（与 `§4.1` 一致）。

### 3.3 `BigDecimal` 使用规则（精度强制，引用 `§14.2`）

| # | 规则 | 说明 |
| --- | --- | --- |
| B1 | **禁止用 `double` / `float` 表示金额与数量** | 一律 `BigDecimal`（`§14.2`） |
| B2 | **比较必须用 `compareTo`，禁止 `equals`** | `equals` 区分 scale，`1.0` ≠ `1.00`；相等判断用 `a.compareTo(b) == 0` |
| B3 | **除法必须显式给 scale 与 `RoundingMode`** | `a.divide(b, 3, RoundingMode.HALF_UP)`；禁止无参 `divide`（可能抛 `ArithmeticException`） |
| B4 | **构造用 `BigDecimal.valueOf(double)` 或字符串构造** | 禁止 `new BigDecimal(double)`（精度丢失） |
| B5 | **精度约定**：数量 `DECIMAL(18,3)`、单价 `DECIMAL(18,4)`、金额 `DECIMAL(18,2)`；**计算中间量保留 4 位，最终按目标精度四舍五入** | 与 `§14.2` 一致，保证可复现 |
| B6 | **`null` 不做运算** | 运算前判定非空；缺失值用明确语义（如 `null` 表示未计算），不当作 0 |
| B7 | **舍入模式统一 `HALF_UP`** | 避免各处不同舍入导致结果漂移 |

示例（示意）：

```java
// ✅ 正确
BigDecimal d = BigDecimal.valueOf(2.500);           // 而非 new BigDecimal(2.5)
BigDecimal q = base.divide(step, 4, RoundingMode.HALF_UP); // 显式 scale + 舍入
if (q1.compareTo(q2) == 0) { /* ... */ }             // 相等用 compareTo

// ❌ 错误
double amount = 0.1 + 0.2;                            // 浮点误差
if (q1.equals(q2)) { /* scale 不同会判不等 */ }
base.divide(step);                                    // 无 scale，可能抛异常
```

### 3.4 空值处理

- **禁止返回 `null` 集合**：集合类返回值一律返回空集合（`Collections.emptyList()` 等）。
- `Optional` 的边界：**仅用于"可能没有单个值"的返回值**；禁止用作字段、方法参数、集合元素。
- 对外 DTO 的可选字段：显式约定 `null` 语义（"未计算" vs "0"），并在 `API_CONTRACT.md` 中记录（如 `D = null` 表示数据不足，见 `DESIGN_BASIS §10.1`）。
- 禁止用 `null` 表达业务状态（状态一律用枚举，见 §9）。

### 3.5 异常规范

| # | 规则 |
| --- | --- |
| E1 | **业务异常统一 `BizException`**（定义在 `common`），携带**稳定错误码**（`§12`）+ 用户可读消息 |
| E2 | **领域异常层次**：`BizException` 为基类，按业务域派生子类或按错误码分段组织；`GlobalExceptionHandler` 统一转换为 `API_CONTRACT.md` 的统一响应体 |
| E3 | **禁止用异常做流程控制** | 可判定的状态用 `if` + 状态守卫，不用"捕获异常再说" |
| E4 | **禁止 `catch (Exception e) {}`（空捕获）** | 必须处理：转换、记录、重抛 |
| E5 | **唯一约束冲突必须在应用层翻译为业务错误码** | 如 `DuplicateKeyException` → `CAT-3002`（`DATABASE_CONSTRAINTS.md §1.2 C4`）；禁止把 MySQL 原生错误抛给前端 |
| E6 | **不捕获后"假装成功"** | 失败必须显式失败（对应 G4） |
| E7 | **内部异常内嵌 `cause`** | 转换异常时保留原始 `cause`，便于排查 |

错误码分段（引用 `§12.2`）：`COM`/`AUTH`/`TEAM`/`CAT`/`IMP`/`INV`/`SAL`/`ANL`/`RPL`/`PO`。**不得自造错误码**；确需新增须先登记 `DESIGN_REVIEW.md`（编号待 SSOT 统一）。

### 3.6 日志规范

| # | 规则 |
| --- | --- |
| L1 | **使用占位符，禁止字符串拼接** | `log.info("runId={}, skuId={}", runId, skuId)` |
| L2 | **结构化**：关键字段用 `key=value`；必有 `requestId`（`NFR-OBS-01`） |
| L3 | **禁止打印敏感数据** | 密码、Token、密钥、完整个人信息不得入日志 |
| L4 | **级别使用**：`ERROR` 仅用于需人工干预的失败；`WARN` 用于可恢复异常/降级（如 LLM `DEGRADED`）；`INFO` 用于关键业务节点；`DEBUG` 用于排查细节 |
| L5 | **异常日志记录堆栈** | `log.error("...", e)`，不写 `e.getMessage()` 代替 |
| L6 | **同一异常不重复打印** | 已在入口统一处理的不再逐层打印 |

### 3.7 Spring 规范

| # | 规则 |
| --- | --- |
| S1 | **构造器注入**（`@RequiredArgsConstructor` 或显式构造器）；禁止字段注入（`@Autowired` 字段） |
| S2 | **`@Transactional` 只放在 `application` 层** | 不在 `api` / `infrastructure` 层开事务 |
| S3 | **事务内禁止远程调用**（HTTP 到 `service-ai` 或 LLM） | 异步分析通过"创建 + 轮询"解耦（`DESIGN_BASIS §13.1`）；不在事务中发起网络请求 |
| S4 | **事务边界明确**：单事务行数上限 500（`DATABASE_CONSTRAINTS.md §7.3`）；导入分批 |
| S5 | **跨模块写调用复用调用方事务**（`REQUIRED`）；被调用方**不得**提交/回滚外层事务（`MODULE_BOUNDARIES.md §4.1`） |
| S6 | **`common` 只依赖框架**，禁止依赖任何业务模块（`MODULE_BOUNDARIES.md §3.2`） |
| S7 | 事务隔离级别 `READ COMMITTED`（`DATABASE_CONSTRAINTS.md §7.3`） |

### 3.8 MyBatis-Plus 规范

| # | 规则 |
| --- | --- |
| M1 | **禁止在 XML 中拼接 `${}`**（SQL 注入风险）；需要动态列/表名时使用白名单校验，优先用 `#{}` |
| M2 | **分页使用分页插件**；列表优先键集分页（keyset），与索引设计匹配（`DATABASE_CONSTRAINTS.md §3.1 I2`） |
| M3 | **租户插件（`TenantLineInnerInterceptor`）必须启用**，作为 `team_id` 过滤兜底；但**不作为唯一防线**（`DESIGN_BASIS §6 第 4 条`） |
| M4 | **批量操作**：导入批量 `insert` 按 500 行分批；`saveBatch` 需确认批次大小与事务传播 |
| M5 | **枚举用自定义 `TypeHandler`**，映射为 `VARCHAR(32)` 大写字符串（`§4.1`） |
| M6 | **禁止手写 `SELECT *` 返回 `Map`** 作为业务数据传递；必须映射为明确类型 |
| M7 | **查询方法必须带 `team_id` 条件**（或由租户插件注入）；关联查询不得通过"外键成立"推断隔离成立（`§6 第 5 条`） |

### 3.9 DTO 与实体分离

- **禁止把 Entity 直接返回给前端**：`api` 层必须用 `*Response` DTO。
- `api` 层的 `*Request` **禁止包含 `teamId`**（`§6 第 3 条`），`teamId` 由 `TeamContext` 提供。
- 跨模块调用传 DTO，不传实体（`MODULE_BOUNDARIES.md §9 #16`）。

### 3.10 校验规范

- **Bean Validation** 用于 `api` 层请求体校验（`@NotNull`、`@Size`、`@Min` 等）；校验失败统一返回 `COM-0001`。
- **业务校验放在 `domain` / `application`**（需要读数据才能判断的，如跨表同团队校验、`received_qty ≤ ordered_qty`）。
- **数据库 `CHECK` 约束作为兜底**（`DATABASE_CONSTRAINTS.md §1.1`），不替代应用层校验。

---

## 4. Python 编码规范

### 4.1 虚拟环境与依赖管理

- **结论：首期使用 `requirements.txt`（含 `==` 精确版本）**，理由：
  1. 首期依赖数量有限、无复杂可选依赖组，`requirements.txt` 足够；
  2. 与 `TECH_STACK.md §3.1` 的"精确版本锁定"要求直接匹配、上手成本低；
  3. Docker 构建链最简单（`pip install -r requirements.txt`）。
- **可选的 `pyproject.toml`**：若 M4 起需要区分 dev/prod 依赖组或需要打包为可分发包，再迁移到 `pyproject.toml`（`PROJECT_STRUCTURE.md §5` 已同时列出两者）。**迁移需更新本文件与 `TECH_STACK.md`**。
- 依赖必须锁定精确版本；升级需在 PR 说明理由并登记 `DESIGN_REVIEW.md`。

### 4.2 类型注解（强制）

- **所有函数签名必须带类型注解**（参数与返回值），包括私有函数。
- 复杂结构用 `pydantic` 模型或 `TypedDict` / `dataclass` 表达，禁止裸 `dict` 作为核心数据结构跨模块传递。
- 使用 `ruff` / `black` 统一风格（`TECH_STACK.md §1.3`）。

### 4.3 Pydantic v2 使用规范

| # | 规则 |
| --- | --- |
| P1 | 使用 **Pydantic v2**；模型后缀 `Request` / `Response` / `Item`（`DESIGN_BASIS §4.4`） |
| P2 | 所有**输入与输出**都必须经 Pydantic 校验；**LLM 输出必须经 Schema 校验**（否则任务 `DEGRADED` + `VALIDATION_FAILED`，`§9.7/§9.8`） |
| P3 | **数值字段使用 `Decimal`**（对应 `§14.2`）；`float` 不得跨 API 边界用于金额/数量 |
| P4 | 枚举字段用 `str` + `Enum`（大写字符串），与 `§9` 一致；**不得在 Python 侧重新定义 Java 业务枚举**（`PROJECT_STRUCTURE.md §5` 注） |
| P5 | 使用 `model_config` 明确 `extra` 行为（建议 `forbid` 以防字段污染） |
| P6 | 契约测试必须断言"响应符合 Pydantic Schema"（`TEST_STRATEGY.md §12`） |

### 4.4 pandas 使用边界

| # | 规则 |
| --- | --- |
| PD1 | **pandas 只用于统计计算内部**；进入计算层前需把输入转为 `Decimal`，计算结果必须显式转回 `Decimal`（`TECH_STACK.md §1.3`） |
| PD2 | **禁止把 DataFrame 泄露到 API 层或跨模块边界**；`api` / `schemas` 只接受/返回 Pydantic 模型 |
| PD3 | pandas 计算需注意浮点：中间计算可用 `float`，但**输出前必须按 `§14.2` 精度显式量化** |
| PD4 | 涉及排序/分组的输出必须确定（显式 `sort`），保证结果可复现（G8） |

### 4.5 异常与日志

- 异常层次放在 `app/core/exceptions.py`；业务错误返回明确错误码（与 Java 侧契约一致）。
- 日志使用结构化字段；**必带 `X-Request-Id`**（`NFR-OBS-01`）；禁止 `print`（使用 `logging`）。
- 禁止吞异常；降级必须显式记录（如 LLM 失败 → 记录 `llm_status` 并返回降级标记，不静默）。

### 4.6 配置管理

- 使用 **`pydantic-settings`** 读取环境变量（`app/core/config.py`）。
- 配置项必须**显式声明类型与默认值**；缺失必填配置在启动时**快速失败**。
- **禁止硬编码密钥**；`AI_INTERNAL_TOKEN`、`LLM_API_KEY` 等只从环境变量读取（`§13.3`）。

### 4.7 异步与超时

- **httpx 超时必须显式设置**（不允许无超时）；与 `DESIGN_BASIS §13.4` 一致：
  - 单次 LLM 调用默认 **30s**，最多 **2 次**指数退避重试（仅网络/5xx）；
  - 内部接口超时按 `§13.4` 表格执行。
- 异步任务（`BackgroundTasks` / 线程池）必须记录执行状态与进度，支持取消与 `timeout_at` 兜底（`§9.7`）。
- **Python 不写业务数据库**（`§13.2`）；不得引入任何数据库连接。

---

## 5. 前端编码规范

### 5.1 TypeScript

- **`tsconfig` 开启 `strict`**。
- **禁止 `any`**；确有必要时必须加**注释说明理由**，且优先用 `unknown` + 类型收窄替代。
- 类型定义集中在 `src/types/`，与 `src/api/` 一一对应（`PROJECT_STRUCTURE.md §6`）。

### 5.2 Vue 3 组合式 API

- 统一使用 `<script setup lang="ts">`。
- 组件命名遵循 `DESIGN_BASIS.md §4.3`：页面 `views/<module>/XxxYyy.vue`，通用组件 `components/<domain>/XxxYyy.vue`。
- **拆分阈值**：单个 `.vue` 文件**超过 300 行**（或模板超过 150 行）应评估拆分子组件；单个函数超过 50 行应评估抽取。
- 页面卸载必须清理定时器/轮询（`usePolling` 使用 `onUnmounted` 停止，`TECH_STACK.md` VueUse 备注）。

### 5.3 API 层封装

- **统一 axios 实例**（`api/http.ts`）：注入 Token 与 `X-Request-Id`，统一处理错误码与 Loading。
- **`views` 禁止直接 `import` axios**，必须经 `api/<module>.ts`（`PROJECT_STRUCTURE.md §6.1`）。
- 错误处理：按 `ERROR_CODES.md` 统一提示；`TEAM-2001/2002/2003` 等权限错误给出明确文案。
- `api` 层**不得依赖 `stores`**（避免循环依赖，`PROJECT_STRUCTURE.md §6.1`）。

### 5.4 状态管理边界

- **Pinia 只放跨页面共享状态**（如当前用户、当前团队与角色）。
- **页面内状态用 `ref` / `reactive`**，不进 Pinia。
- 业务状态（补货量、状态机、权限）**一律以服务端为准**，前端不承载业务规则（`MODULE_BOUNDARIES.md §6`）。

### 5.5 类型定义与后端契约同步

**结论：首期采用"手动维护 + 字段一致性检查清单"，不引入代码生成。**

| 方案 | 说明 | 结论 |
| --- | --- | --- |
| 手动维护 TS 类型 | 从 `API_CONTRACT.md` 手写 `types/*.ts` | ✅ **首期采用**：接口数量可控；契约稳定后再评估生成 |
| 由 OpenAPI 生成 | 从 SpringDoc 生成 TS 类型 | ⏳ 未来评估：当接口数量显著增长、手写同步成本超过引入生成链成本时再评估 |
| 后端共享类型包 | 前后端共用一个类型定义包 | ❌ 首期不做：跨语言，收益低 |

**字段一致性检查清单（每次新增/修改 API 必查）**：

- [ ] 字段名与 `API_CONTRACT.md` 完全一致（大小写、拼写）。
- [ ] 数值字段按 `§14.2` 约定：数量为 3 位小数、金额 2 位；前端**不得用 JS `number` 做金额精确运算**（展示用字符串或后端计算结果）。
- [ ] 日期/时间：时间戳为 ISO-8601 UTC；业务日期为 `YYYY-MM-DD`（`§14.1`）。
- [ ] 枚举值与 `§9` 完全一致（**不得**在前端自定义枚举值）。
- [ ] 分页字段（`page`/`size`/`total`/`items`）与统一分页结构一致。
- [ ] 错误响应结构符合 `API_CONTRACT.md` 统一响应体。
- [ ] `types/*.ts` 与 `api/*.ts` 同步更新，二者一一对应。

### 5.6 样式规范

- **优先使用 Element Plus 变量与组件**，不重复造轮子。
- **避免深层选择器**（`>>>` / `::v-deep`）：如确需穿透，限定最小范围并加注释。
- 颜色/间距使用统一主题变量；`risk_level` 展示按 `INVENTORY_RULES.md §6.2` 的语义色（与股票涨跌色无关）。

---

## 6. 数据库变更规范

| # | 规则 |
| --- | --- |
| D1 | **所有变更必须走 Flyway**；禁止手工在库上执行 DDL 后"补一个迁移" |
| D2 | **命名规范**（`§4.1`）：表名复数下划线；索引 `idx_`/`uk_`/`fk_` 前缀；迁移文件 `V<版本>__<描述>.sql` |
| D3 | **禁止修改已执行过的迁移文件**：迁移一经合并执行即不可变；修复必须新增迁移 |
| D4 | **破坏性变更流程**：删列/改类型/改约束必须①评估数据迁移与回滚②新增迁移脚本③在 PR 中说明④同步更新 `DATABASE_DESIGN.md`/`DATA_DICTIONARY.md`/`DATABASE_CONSTRAINTS.md`/`ER_DIAGRAM.md`（`§16`） |
| D5 | **枚举取值变更**必须同步 `DESIGN_BASIS.md §9` + 所有引用文档 + 状态机测试（`§16`） |
| D6 | **团队业务表必须含 `team_id NOT NULL`（无默认值）**；唯一约束必须带 `team_id` 前缀（`§6`） |
| D7 | **索引必须以 `team_id` 为最左列**（系统级调度索引例外须显式声明，`DATABASE_CONSTRAINTS.md §3.3`） |
| D8 | **不使用触发器、存储过程、数据库级 RLS**（`DATABASE_CONSTRAINTS.md §1.3`） |
| D9 | **MySQL 必须 ≥ 8.0.16**（`CHECK` 约束前提）；迁移中可加版本守卫断言 |

---

## 7. 文档同步规范

> 依据 `DESIGN_BASIS.md §16`（文档与代码同步责任）。**"改了 X 必须更新 Y"** 逐条落地如下。

| 改了 X | 必须同步更新 Y |
| --- | --- |
| 新增/修改**表或字段** | `docs/02-database/DATABASE_DESIGN.md`、`DATA_DICTIONARY.md`、`DATABASE_CONSTRAINTS.md`、`ER_DIAGRAM.md` |
| 新增/修改 **API** | `docs/03-api/API_CONTRACT.md`、`ERROR_CODES.md`，以及相关页面文档（`PROJECT_STRUCTURE.md §3`） |
| 修改**状态枚举** | **`DESIGN_BASIS.md §9`** + 所有引用它的文档 + **状态机测试** |
| 修改**补货公式 / 风险规则阈值** | **`DESIGN_BASIS.md §10/§11`** + `INVENTORY_RULES.md` + `TEST_STRATEGY.md` 用例 |
| 修改 **Java↔Python 契约** | `docs/03-api/AI_SERVICE_CONTRACT.md`、`docs/04-ai/AI_ARCHITECTURE.md`、`docs/04-ai/ANALYSIS_CONTRACT.md` |
| 修改**模块依赖** | `docs/01-architecture/MODULE_BOUNDARIES.md`、`PROJECT_STRUCTURE.md`、ADR |
| 修改**技术版本** | `docs/01-architecture/TECH_STACK.md` + 锁定文件（`pom.xml`/`package-lock.json`/`requirements.txt`） |
| 修改**验收口径** | `docs/06-development/ACCEPTANCE_CRITERIA.md` + `docs/00-product/PRD.md §9`（如涉及总述） |
| 修改**开发计划/里程碑** | `docs/06-development/DEVELOPMENT_PLAN.md` |
| **发现任何文档冲突** | `docs/06-development/DESIGN_REVIEW.md`（`§1.2 第 4 条`：**即使已修正也必须记录**） |

**待决策编号规则**：引用待决策只引用 `DESIGN_BASIS.md §15` 的 `D-01`~`D-08`；其他文档的 `D-09` 及之后编号为**建议编号，存在冲突**，一律写作"（编号待 SSOT 统一，见 `DESIGN_REVIEW.md`）"。

---

## 8. 代码评审自检清单

### 8.1 清单（≥20 条）

| # | 检查项 | 判定 |
| --- | --- | --- |
| 1 | 新增的模块依赖是否在 `DESIGN_BASIS.md §7.1` 允许列表内？ | 是 / 否 |
| 2 | 是否存在**反向依赖**（`catalog → inventory`、`analysis → purchasing`、`inventory → purchasing` 等）？ | 无 |
| 3 | 是否存在跨模块**直接注入对方 Mapper**？ | 无 |
| 4 | 跨模块写入是否经对方 `application` 层服务接口？ | 是 |
| 5 | 跨模块写入是否正确声明**事务传播**，被调用方不提交/回滚外层事务？ | 是 |
| 6 | 库存相关变更是否**全部经 `InventoryWriteService`**，无直接 `UPDATE inventory_balances`？ | 是 |
| 7 | 余额与流水是否**在同一事务**内写入？ | 是 |
| 8 | 跨表关联写入是否做了**显式同团队校验**（非仅依赖租户插件）？ | 是 |
| 9 | `team_id` 是否**绝不出现在 Controller 请求参数**中（由 `TeamContext` 提供）？ | 未出现 |
| 10 | `common` 模块是否**未新增任何业务名词/实体/Mapper/规则**？ | 是 |
| 11 | 所有库存/采购/到货/导入写操作是否携带 **`idempotencyKey`** 并命中唯一约束？ | 是 |
| 12 | 状态流转是否**仅由拥有该状态的模块**执行，且符合 `§9` 合法转换（状态守卫式 UPDATE）？ | 是 |
| 13 | 权限判定是否以 `USER_ROLES.md §2.2` 矩阵为唯一依据，且为**服务端校验**？ | 是 |
| 14 | 高风险操作（库存调整/审批/确认到货/取消采购单）是否有**额外前置校验**？ | 是 |
| 15 | 金额/数量是否使用 `BigDecimal`，且比较用 `compareTo`、除法显式 scale + `RoundingMode`？ | 是 |
| 16 | 是否存在 `double`/`float` 参与金额或数量计算？ | 无 |
| 17 | 是否返回 `null` 集合？`Optional` 是否被滥用（作字段/参数）？ | 无 |
| 18 | 异常是否显式处理（无空 `catch`、无吞异常、无异常做流程控制）？ | 是 |
| 19 | 唯一约束冲突是否翻译为**业务错误码**（未暴露 MySQL 原生错误）？ | 是 |
| 20 | 日志是否用占位符、含 `requestId`、**未打印敏感数据**？ | 是 |
| 21 | `@Transactional` 是否只在 `application` 层？事务内是否有远程调用？ | 是 / 无 |
| 22 | MyBatis XML 是否存在 `${}` 拼接？分页是否走插件？ | 无 / 是 |
| 23 | Entity 是否被直接返回给前端（应改为 DTO）？ | 未出现 |
| 24 | 新增/修改逻辑是否有**对应测试**（单元/集成/规则用例）？ | 是 |
| 25 | 是否命中 `§16` 同步责任表，且**相关文档已同步更新**？ | 是 / 不适用 |
| 26 | 是否引入了 `§2.1` Out of Scope 的技术（Redis/MQ/K8s/ML 等）？ | 无 |
| 27 | 新增索引/唯一约束是否以 `team_id` 为最左列（例外须显式声明）？ | 是 |
| 28 | 是否出现硬编码密钥、环境地址、写死的 `base_url`？ | 无 |
| 29 | 前端是否出现 `any`（无注释说明）、`views` 直接调 axios、前端承载业务规则？ | 无 |
| 30 | 提交中是否包含 `.env`、密钥、构建产物、IDE 配置？ | 无 |

### 8.2 合并前自审流程

1. 逐条勾选 §8.1 清单，任一"否/未出现"不符合预期时，**必须在 PR 中说明或修改**。
2. 本地执行相关测试并附**真实输出**（见 `ACCEPTANCE_CRITERIA.md §8`）。
3. 确认 `DESIGN_REVIEW.md` 无未登记的新冲突。

---

## 9. 与 SSOT 的一致性声明

| 检查项 | 结论 |
| --- | --- |
| 4 层分层与命名后缀 | 与 `DESIGN_BASIS.md §4.2` 一致 |
| 模块依赖方向 | 与 `§7.1`、`MODULE_BOUNDARIES.md` 一致 |
| 团队隔离 6 条硬规则 | 与 `§6` 一致，并补充代码级检查项 |
| `BigDecimal`/精度 | 与 `§14.2` 一致 |
| 错误码 | 只引用 `§12`，不自造 |
| 文档同步 | 逐条对应 `§16` |
| 待决策引用 | 只引用 `D-01`~`D-08`；其他写作"（编号待 SSOT 统一，见 `DESIGN_REVIEW.md`）" |
| 代码/示例状态 | 全部为**示意**，本项目尚无任何代码 |

---

## 10. 变更记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-14 | M0 定稿：通用原则、Git 规范、Java/Python/前端/数据库/文档规范、30 条评审自检清单 | 工程效能 |
