# ShopPilot AI 项目结构规范（PROJECT_STRUCTURE）

## 文档元信息

| 项 | 内容 |
| --- | --- |
| 文档名称 | 项目结构规范 |
| 文档路径 | `docs/01-architecture/PROJECT_STRUCTURE.md` |
| 文档优先级 | P3（见 `DESIGN_BASIS.md §1.1`） |
| 所属阶段 | M0 设计阶段 |
| 版本 | v1.0（M0 定稿） |
| 上游权威来源 | `docs/DESIGN_BASIS.md §4`（命名规范）、`§7.1`（Java 模块）、`§7.2`（Python 模块）、`docs/01-architecture/MODULE_BOUNDARIES.md` |
| 主要读者 | 开发、DevOps |

### 本文档负责什么

- 给出项目的**完整目录树**与每个一级目录的用途。
- 规定 **Java 后端包结构**、**Python 服务目录结构**、**前端目录结构**。
- 规定 **deploy 目录** 与 **scripts 目录** 的组织（**仅规划，M0 不实现脚本**）。
- 明确**文件命名与代码组织规范**、**依赖规范**。

### 本文档不负责什么

- **不定义**模块的服务接口与依赖规则（见 `MODULE_BOUNDARIES.md`）。
- **不定义**技术版本（见 `TECH_STACK.md`）。
- **不定义**部署拓扑与网络（见 `SYSTEM_ARCHITECTURE.md §部署视图`）。
- **不定义**数据库表与迁移内容（见 `docs/02-database/*`）。

> **重要约束**：M0 阶段**只产出设计文档**。本文件中的目录树是**规划**，M0 **不创建**任何代码文件、SQL、配置文件。`§2.1` 明确"正式 Flyway 迁移 SQL（M1 起才生成）"。

---

## 1. 完整目录树

```text
ShopPilot-AI/
├── README.md                          # 项目说明、快速开始
├── AGENTS.md                          # 协作方（人与 AI）行为规则
├── .env.example                       # 环境变量样例（不含真实密钥）
├── .gitignore
├── .editorconfig                      # 跨编辑器风格统一
│
├── docs/                              # 全部设计文档（M0 主战场）
│   ├── DESIGN_BASIS.md                # SSOT（已存在）
│   ├── decisions/
│   │   └── ADR-001-tech-and-architecture.md
│   ├── 00-product/
│   │   ├── PRD.md
│   │   ├── USER_ROLES.md
│   │   └── ACCEPTANCE_CRITERIA.md
│   ├── 01-architecture/
│   │   ├── SYSTEM_ARCHITECTURE.md
│   │   ├── MODULE_BOUNDARIES.md
│   │   ├── TECH_STACK.md
│   │   └── PROJECT_STRUCTURE.md
│   ├── 02-database/
│   │   ├── DATABASE_DESIGN.md
│   │   ├── DATA_DICTIONARY.md
│   │   ├── DATABASE_CONSTRAINTS.md
│   │   └── ER_DIAGRAM.md
│   ├── 03-api/
│   │   ├── API_CONTRACT.md
│   │   ├── AI_SERVICE_CONTRACT.md
│   │   └── ERROR_CODES.md
│   ├── 04-ai/
│   │   ├── AI_ARCHITECTURE.md
│   │   ├── INVENTORY_RULES.md
│   │   └── ANALYSIS_CONTRACT.md
│   ├── 05-workflows/
│   │   ├── IMPORT_WORKFLOW.md
│   │   ├── ANALYSIS_WORKFLOW.md
│   │   ├── REPLENISHMENT_WORKFLOW.md
│   │   └── PURCHASING_WORKFLOW.md
│   └── 06-development/
│       ├── DEVELOPMENT_PLAN.md
│       ├── TEST_STRATEGY.md
│       └── DESIGN_REVIEW.md
│
├── backend-java/                      # Spring Boot 模块化单体（11 模块）
│   ├── pom.xml                        # 父 POM（聚合 + dependencyManagement）
│   ├── mvnw  /  mvnw.cmd
│   ├── .mvn/wrapper/
│   ├── Dockerfile
│   ├── common/
│   │   └── src/main/java/com/shoppilot/common/
│   │       ├── api/                   # ApiResponse、PageResult、分页参数
│   │       ├── application/           # （预留，通常为空）
│   │       ├── domain/                # CodedEnum 等基础接口
│   │       └── infrastructure/
│   │           ├── exception/         # BizException、GlobalExceptionHandler
│   │           ├── context/           # TeamContext、TeamContextFilter
│   │           ├── mybatis/           # MyBatisPlusConfig、TenantLineInnerInterceptor
│   │           ├── audit/             # @Audited 注解与切面定义
│   │           ├── util/              # DateUtils、MoneyUtils、QtyUtils
│   │           └── error/             # ErrorCodes 常量
│   ├── identity/
│   │   └── src/main/java/com/shoppilot/identity/
│   │       ├── api/                   # AuthController、TeamMemberController + DTO
│   │       ├── application/           # AuthAppService、TeamMemberAppService、*QueryService
│   │       ├── domain/                # User、Team、TeamMember 实体、TeamRole
│   │       └── infrastructure/        # *Mapper、*Repository（MyBatis-Plus）
│   ├── catalog/
│   │   └── src/main/java/com/shoppilot/catalog/
│   │       ├── api/                   # WarehouseController、SupplierController…
│   │       ├── application/           # CatalogWriteService、*QueryService
│   │       ├── domain/                # Warehouse、Supplier、Product、Sku
│   │       └── infrastructure/        # *Mapper
│   ├── importdata/
│   │   └── src/main/java/com/shoppilot/importdata/
│   │       ├── api/                   # ImportController
│   │       ├── application/           # ImportAppService、ImportQueryService、*RowValidator
│   │       ├── domain/                # ImportBatch、ImportRowError、ImportBizType
│   │       └── infrastructure/        # *Mapper、*FileParser（CSV/Excel 适配）
│   ├── inventory/
│   │   └── src/main/java/com/shoppilot/inventory/
│   │       ├── api/                   # InventoryController、PolicyController
│   │       ├── application/           # InventoryWriteService、InventoryQueryService、
│   │       │                          # InventoryInTransitQueryService、ReplenishmentPolicyService
│   │       ├── domain/                # InventoryBalance、InventoryTransaction、
│   │       │                          # InventoryTxnType、ReplenishmentPolicy
│   │       └── infrastructure/        # *Mapper
│   ├── sales/
│   │   └── src/main/java/com/shoppilot/sales/
│   │       ├── api/                   # SalesController
│   │       ├── application/           # SalesWriteService、SalesQueryService
│   │       ├── domain/                # SalesDaily
│   │       └── infrastructure/        # *Mapper
│   ├── analysis/
│   │   └── src/main/java/com/shoppilot/analysis/
│   │       ├── api/                   # AnalysisController
│   │       ├── application/           # AnalysisAppService、AnalysisQueryService、
│   │       │                          # AnalysisWriteService
│   │       ├── domain/                # AnalysisRun、AnalysisItem、AnalysisRunStatus
│   │       └── infrastructure/        # *Mapper、AiServiceClient（RestClient 调 Python）
│   ├── replenishment/
│   │   └── src/main/java/com/shoppilot/replenishment/
│   │       ├── api/                   # ReplenishmentController
│   │       ├── application/           # ReplenishmentAppService、ReplenishmentGenerationService、
│   │       │                          # ReplenishmentQueryService
│   │       ├── domain/                # ReplenishmentItem、ReplenishmentReview、
│   │       │                          # ReplenishmentItemStatus、ReviewAction
│   │       └── infrastructure/        # *Mapper
│   ├── purchasing/
│   │   └── src/main/java/com/shoppilot/purchasing/
│   │       ├── api/                   # PurchaseOrderController、GoodsReceiptController
│   │       ├── application/           # PurchaseOrderAppService、GoodsReceiptAppService、*QueryService
│   │       ├── domain/                # PurchaseOrder、PurchaseOrderItem、GoodsReceipt、
│   │       │                          # GoodsReceiptItem、PurchaseOrderStatus、GoodsReceiptStatus
│   │       └── infrastructure/        # *Mapper
│   ├── audit/
│   └── app/
│   │   └── src/main/java/com/shoppilot/app/
│   │       ├── ShopPilotApplication.java   # @SpringBootApplication 启动类（唯一）
│   │       └── config/                      # 配置装配（仅技术能力，无业务规则）
│       └── src/main/java/com/shoppilot/audit/
│           ├── api/                   # AuditLogController
│           ├── application/           # AuditWriteService、AuditQueryService
│           ├── domain/                # AuditLog、AuditEvent
│           └── infrastructure/        # *Mapper
│
├── service-ai/                        # FastAPI 分析服务（8 模块）
│   ├── app/
│   │   ├── main.py                    # FastAPI 应用入口
│   │   ├── api/                       # 路由：analysis、health
│   │   ├── schemas/                   # Pydantic 输入/输出模型
│   │   ├── orchestration/             # 步骤编排、进度、超时、降级
│   │   ├── validation/                # 数据质量检查
│   │   ├── analytics/                 # sales_stats.py
│   │   ├── rules/                     # risk_rules.py、replenishment.py
│   │   ├── llm/                       # prompt 加载、模型适配、Schema 校验、降级
│   │   └── core/                      # 配置、日志、trace、http 客户端、异常
│   ├── tests/                         # pytest 用例
│   ├── pyproject.toml  /  requirements.txt
│   └── Dockerfile
│
├── frontend-web/                      # Vue 3 SPA
│   ├── src/
│   │   ├── main.ts
│   │   ├── App.vue
│   │   ├── router/                    # 路由与守卫
│   │   ├── stores/                    # Pinia（user、team…）
│   │   ├── api/                       # axios 实例与按模块的 API 模块
│   │   ├── types/                     # TS 类型定义
│   │   ├── views/                     # 按模块分目录的页面
│   │   ├── components/                # 通用组件
│   │   ├── composables/               # 组合式函数（轮询等）
│   │   └── utils/                     # 工具
│   ├── public/
│   ├── index.html
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── package.json  /  package-lock.json
│   └── Dockerfile
│
├── deploy/                            # 部署与编排
│   ├── docker-compose.yml             # 集成/演示
│   ├── docker-compose.dev.yml         # 开发（暴露 3306/8080，挂载卷）
│   ├── nginx/
│   │   └── nginx.conf                 # 静态资源 + /api 反向代理
│   ├── init/
│   │   └── README.md                  # 初始化占位（M1 起放初始化脚本规划）
│   └── .env.example                   # 部署环境变量样例
│
└── scripts/                           # 本地脚本（规划，M0 不实现）
    ├── import-demo-data.sh            # 导入演示数据
    ├── health-check.sh                # 健康检查
    └── reset-dev-db.sh                # 重置开发库
```

---

## 2. 一级目录用途说明

| 目录 / 文件 | 用途 | 归属阶段 |
| --- | --- | --- |
| `docs/` | 全部设计文档；按 `00-product` → `06-development` 分层，与 `DESIGN_BASIS.md §1.1` 优先级对应 | M0 主产出 |
| `backend-java/` | Spring Boot 模块化单体，10 个 Maven 子模块 | M1 起 |
| `service-ai/` | FastAPI 分析服务，8 个内部模块 | M4 起（M1 起建骨架） |
| `frontend-web/` | Vue 3 单页应用 | M1 起 |
| `deploy/` | Docker Compose 编排、nginx、环境变量样例 | M1 起 |
| `scripts/` | 本地开发辅助脚本 | M2 起 |
| `README.md` | 项目说明、环境搭建、快速开始 | M1 |
| `AGENTS.md` | 协作方行为规则（含文档同步责任，见 `DESIGN_BASIS.md §16`） | M0 |
| `.env.example` | 根级环境变量样例（`AI_INTERNAL_TOKEN`、数据库、LLM 配置等），**不含真实密钥** | M1 |
| `.gitignore` | 忽略构建产物、IDE 文件、密钥、日志 | M0 |
| `.editorconfig` | 统一缩进/换行/编码 | M0 |

---

## 3. 关键目录的职责边界

| 目录 | 属于 | 明确不包含 |
| --- | --- | --- |
| `docs/decisions/` | 架构决策记录（ADR），约束技术方向 | 具体实现细节 |
| `docs/02-database/` | 数据模型权威定义 | API 细节、业务规则 |
| `docs/03-api/` | 对外与内部接口权威定义 | 数据库字段定义（以 `02-database` 为准，冲突时以数据库为准，见 `DESIGN_BASIS.md §1.2 第 2 条`） |
| `docs/04-ai/` | AI 架构、规则说明、分析契约 | 补货公式定义（以 `DESIGN_BASIS.md §10` 为准） |
| `docs/06-development/DESIGN_REVIEW.md` | **所有被发现的文档冲突必须记录在此**（`§1.2 第 4 条`） | 解决方案细节 |
| `backend-java/*/src/main/java` | 仅生产代码 | 测试代码（在 `src/test/java`） |
| `service-ai/app/` | 服务代码 | 测试（在 `tests/`） |
| `frontend-web/src/` | 前端源码 | 构建产物（`dist/`，被忽略） |
| `deploy/init/` | 数据库初始化/演示数据的**规划** | M0 不落地任何 SQL（`§2.1`） |

---

## 4. Java 后端包结构

### 4.1 包根与分层

```
com.shoppilot.<module>.{api, application, domain, infrastructure}
```

依据 `DESIGN_BASIS.md §4.2`，**仅 4 层**：

| 层 | 职责 | 命名后缀 |
| --- | --- | --- |
| `api` | 对外 REST 控制器、请求/响应 DTO | `*Controller`、`*Request`、`*Response` |
| `application` | 用例编排、事务边界、跨模块协调 | `*AppService`、`*Command`、`*Query` |
| `domain` | 领域模型、业务不变量、领域服务、状态机 | `*DomainService`、`*Policy`、实体类 |
| `infrastructure` | MyBatis-Plus Mapper、外部客户端 | `*Mapper`、`*Client`、`*Repository` |

**示例（inventory 模块）**：

```text
com.shoppilot.inventory
├── api
│   ├── InventoryController.java
│   ├── dto/InventoryBalanceResponse.java
│   └── dto/CountAdjustRequest.java
├── application
│   ├── InventoryWriteService.java          # 跨模块唯一写入口
│   ├── InventoryQueryService.java
│   ├── InventoryInTransitQueryService.java # 在途口径
│   └── ReplenishmentPolicyService.java
├── domain
│   ├── InventoryBalance.java
│   ├── InventoryTransaction.java
│   ├── InventoryTxnType.java
│   └── InventoryDomainService.java          # 余额与流水一致性不变量
└── infrastructure
    ├── InventoryBalanceMapper.java
    ├── InventoryTransactionMapper.java
    └── ReplenishmentPolicyMapper.java
```

### 4.2 为何只用 4 层

依据 `DESIGN_BASIS.md §4.2`：本项目**无多存储实现需求**，Repository 接口与实现分离的纯 DDD 形式只会增加文件数量而无收益，因此采用 4 层。

> **不要**为每个实体再拆 `repository` 接口 + `impl` 包；Mapper 直接放在 `infrastructure`。

### 4.3 Java 多模块 Maven 结构

```
backend-java/
├── pom.xml                 # 父 POM：packaging=pom，<modules> 聚合 11 个模块
├── common/pom.xml
├── identity/pom.xml
├── catalog/pom.xml
├── importdata/pom.xml
├── inventory/pom.xml
├── sales/pom.xml
├── analysis/pom.xml
├── replenishment/pom.xml
├── purchasing/pom.xml
├── audit/pom.xml
└── app/pom.xml
```

- **依赖方向必须在各模块 `pom.xml` 中体现**：例如 `purchasing/pom.xml` 依赖 `replenishment`、`inventory`、`catalog`、`common`；**禁止**出现 `catalog/pom.xml` 依赖 `inventory`。
- 每个模块 `packaging=jar`（首期不拆可执行 jar，由启动模块统一打包；启动模块已裁定为新增 `backend-java/app`（第 11 模块，组合根，非业务）：M1-T02 落骨架，仅含 `@SpringBootApplication` 启动类 + 配置装配，依赖全部业务模块 + common，自身不被依赖。模块清单见 `DESIGN_BASIS §7.1`，依赖边界见 `MODULE_BOUNDARIES §1.1/§1.2`。

---

## 5. Python 服务目录结构

```text
service-ai/
├── app/
│   ├── main.py                    # FastAPI 实例、路由注册、异常处理器
│   ├── api/
│   │   ├── analysis.py            # /internal/v1/analysis:validate、/analysis/runs、:cancel、状态查询
│   │   └── health.py              # /internal/v1/health
│   ├── schemas/
│   │   ├── analysis_request.py    # AnalyzeRequest
│   │   ├── analysis_response.py   # AnalyzeResponse / 各步骤结果
│   │   └── common.py              # 通用模型与枚举常量（不含 Java 侧业务枚举定义）
│   ├── orchestration/
│   │   ├── pipeline.py            # 显式顺序编排（validate → stats → rules → replenishment → llm）
│   │   ├── progress.py            # 步骤状态与进度记录
│   │   └── degradation.py         # 降级策略
│   ├── validation/
│   │   └── data_quality.py        # 缺口、异常值、样本不足检查
│   ├── analytics/
│   │   └── sales_stats.py         # compute_avg_daily_sales() 等
│   ├── rules/
│   │   ├── risk_rules.py          # R-STOCKOUT / R-LOWSTOCK / R-OVERSTOCK / R-SLOWMOVING /
│   │   │                          # R-SALESANOMALY / R-DATAINSUFFICIENT
│   │   └── replenishment.py       # Q_base → Q_suggest
│   ├── llm/
│   │   ├── prompts/               # Prompt 模板
│   │   ├── client.py              # OpenAI 兼容客户端（base_url 可配置）
│   │   └── validator.py           # 输出 Schema 校验
│   └── core/
│       ├── config.py              # 环境变量配置（含 AI_INTERNAL_TOKEN）
│       ├── logging.py
│       ├── tracing.py             # X-Request-Id 透传
│       ├── http.py                # httpx 客户端封装
│       └── exceptions.py
├── tests/
│   ├── test_risk_rules.py
│   ├── test_replenishment.py
│   ├── test_sales_stats.py
│   └── test_api.py
├── pyproject.toml  /  requirements.txt
└── Dockerfile
```

> 目录与职责依据 `DESIGN_BASIS.md §7.2`。**包根为 `shoppilot_ai`**（`§2`），上述 `app/` 为服务包内部结构；若采用 `src/shoppilot_ai/` 布局，包根仍为 `shoppilot_ai`。

---

## 6. 前端目录结构

```text
frontend-web/
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── router/
│   │   ├── index.ts               # 路由表
│   │   └── guards.ts              # 登录与前端权限守卫（仅体验层）
│   ├── stores/
│   │   ├── user.ts                # useUserStore（当前用户与令牌）
│   │   └── team.ts                # useTeamStore（当前团队与角色）
│   ├── api/
│   │   ├── http.ts                # axios 实例：注入 Token 与 X-Request-Id、错误码统一处理
│   │   ├── auth.ts
│   │   ├── catalog.ts
│   │   ├── importdata.ts
│   │   ├── inventory.ts
│   │   ├── sales.ts
│   │   ├── analysis.ts
│   │   ├── replenishment.ts
│   │   ├── purchasing.ts
│   │   └── audit.ts
│   ├── types/
│   │   ├── common.ts              # ApiResponse、PageResult
│   │   ├── auth.ts
│   │   ├── inventory.ts
│   │   ├── analysis.ts
│   │   └── ...                    # 与 api/ 一一对应
│   ├── views/
│   │   ├── auth/LoginView.vue
│   │   ├── dashboard/…
│   │   ├── catalog/{ProductList,SkuList,SupplierList,WarehouseList}.vue
│   │   ├── importdata/{ImportWizard,ImportBatchList}.vue
│   │   ├── inventory/{InventoryOverview,TransactionList,CountAdjust}.vue
│   │   ├── sales/SalesList.vue
│   │   ├── analysis/{AnalysisRunList,AnalysisRunDetail}.vue
│   │   ├── replenishment/{ReplenishmentList,ReviewList}.vue
│   │   ├── purchasing/{PurchaseOrderList,GoodsReceiptList}.vue
│   │   ├── audit/AuditLogList.vue
│   │   └── team/{MemberList,TeamSettings}.vue
│   ├── components/
│   │   ├── common/{PageTable,SearchForm,StatusTag,ConfirmDialog}.vue
│   │   ├── inventory/…
│   │   └── analysis/ProgressPanel.vue
│   ├── composables/
│   │   ├── usePolling.ts          # 2s→10s 指数退避轮询，页面卸载停止
│   │   └── usePagination.ts
│   └── utils/
│       ├── format.ts              # 日期（团队时区）、数量、金额格式化
│       └── permission.ts          # 前端权限判断（仅体验层）
├── public/
├── index.html
├── vite.config.ts
├── tsconfig.json
└── package.json
```

### 6.1 前端分层依赖（严格单向）

```
views → stores → api → types
views → components → api → types
composables → api → types
utils ← 所有层（纯函数，无副作用）
```

| 规则 | 说明 |
| --- | --- |
| `views` **不得**直接 `import` axios | 必须经 `api/<module>.ts` |
| `api` **不得**依赖 `stores` | 令牌注入由 `http.ts` 拦截器完成（从 `stores/user` 读取时需避免循环依赖，建议通过 token 存取工具或 pinia 外部调用） |
| `types` **不得**依赖任何层 | 纯类型定义 |
| `components` 通用组件**不得**依赖具体 `views` | 保持可复用 |
| 前端**不得**承载业务规则 | 补货量、状态机、权限均在服务端（见 `MODULE_BOUNDARIES.md §6`） |

---

## 7. deploy 目录

```text
deploy/
├── docker-compose.yml          # 集成/演示：仅 nginx 暴露 80，其余内网
├── docker-compose.dev.yml      # 开发：暴露 3306、8080；挂载源码与数据卷
├── nginx/
│   └── nginx.conf              # 静态资源托管 + /api 反向代理到 backend-java:8080
├── init/
│   └── README.md               # 初始化规划（M1 起放初始化/演示数据脚本）
└── .env.example                # 部署用环境变量样例
```

### 7.1 Compose 服务与端口（与 `SYSTEM_ARCHITECTURE.md §部署视图` 一致）

| 服务 | 容器内端口 | 主机暴露（集成） | 主机暴露（开发） |
| --- | --- | --- | --- |
| `nginx` | 80 | ✅ 80 | ✅ 80 |
| `frontend-web` | 80 | ❌ | ❌ |
| `backend-java` | 8080 | ❌ | ✅ 8080 |
| `service-ai` | 8000 | ❌ **绝不暴露** | ❌ **绝不暴露** |
| `mysql` | 3306 | ❌ | ✅ 3306 |

### 7.2 必需环境变量（`.env.example` 规划项，不含真实值）

| 变量 | 用途 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_DATABASE`（`shoppilot`） / `MYSQL_USER` / `MYSQL_PASSWORD` | 数据库 |
| `AI_INTERNAL_TOKEN` | Java↔Python 内部认证（`DESIGN_BASIS.md §13.3`） |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | LLM 接入（见 `TECH_STACK.md §1.3.1`） |
| `JWT_SECRET` / `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | 认证（关联待决策 `D-01`） |
| `JAVA_OPTS` / `SPRING_PROFILES_ACTIVE` | 后端运行参数 |

---

## 8. scripts 目录（规划，M0 不实现）

| 脚本 | 用途 | 预计阶段 |
| --- | --- | --- |
| `import-demo-data.sh` | 通过 Java 对外 API 导入演示数据（商品、SKU、库存快照、历史销售），用于演示与验收 | M2 |
| `health-check.sh` | 依次探测 `backend-java`、`service-ai`、`mysql`、`nginx` 健康状态 | M1 |
| `reset-dev-db.sh` | 重置开发库（drop + 重建 + 跑迁移 + 灌演示数据），**仅开发环境**，需二次确认 | M2 |

> **约束**：脚本必须通过**公开接口**（`/api/v1` 或 Actuator/健康端点）操作，**不得**绕过 Java 直连写入业务表（见 `SYSTEM_ARCHITECTURE.md §5` 边界 8）。`reset-dev-db.sh` 是唯一允许直接操作数据库的例外，且必须限定开发环境。

---

## 9. 文件命名与代码组织规范

> 完整命名规范见 `DESIGN_BASIS.md §4`。以下为**组织层面的落地约定**。

| 对象 | 规范 | 出处 |
| --- | --- | --- |
| 数据库表 / 字段 / 索引 | 见 `DESIGN_BASIS.md §4.1` | `§4.1` |
| Java 包与类 | `com.shoppilot.<module>.{api,application,domain,infrastructure}`；后缀见 `§4.2` | `§4.2` |
| 前端 | 页面 `views/<module>/XxxYyy.vue`；通用组件 `components/<domain>/XxxYyy.vue`；API `api/<module>.ts`；Store `stores/<module>.ts`；类型 `types/<module>.ts` | `§4.3` |
| Python | 模块 `小写下划线`；Pydantic 模型 `大驼峰`；规则函数 `动词开头小写下划线`；常量 `全大写` | `§4.4` |
| 文档 | `docs/<层>/<大写下划线>.md`，与本文件目录树一致 | 本文件 |
| 一个 Controller 一个文件 | 按资源拆分，避免"万能 Controller" | 本文件 |
| 前端 `views` 与 `api` 模块一一对应 | 便于追踪矩阵核对（`PRD.md §10`） | 本文件 |

---

## 10. 依赖规范

### 10.1 Java 模块依赖

- 依赖方向**强制**（见 `MODULE_BOUNDARIES.md §1` 与 `DESIGN_BASIS.md §7.1`）。
- 模块依赖必须在 `pom.xml` 显式声明，**不通过传递依赖"白嫖"**上层模块。
- 跨模块调用只能通过对方 `application` 层的服务接口，**禁止注入对方 Mapper**（`MODULE_BOUNDARIES.md §4`）。

### 10.2 前端分层依赖

- 见 §6.1 的单向依赖链，**禁止反向引用**。
- 禁止 `views` 直接调用 axios；禁止前端承载业务规则。

### 10.3 Python 分包依赖

```
api → orchestration → {validation, analytics, rules, llm} → core
schemas ← 全部模块（纯数据结构，被依赖）
```

- `validation` / `analytics` / `rules` / `llm` **互相不直接依赖**，由 `orchestration` 顺序调用。
- **禁止** `rules` 调用 `llm`；**禁止** 任何模块直连数据库（Python 无业务库连接）。
- `core` 不含业务规则。

---

## 11. 与 `DESIGN_BASIS.md` 的一致性核对

| 本文件内容 | `DESIGN_BASIS.md` 出处 | 一致性 |
| --- | --- | --- |
| Java 包根 `com.shoppilot` | `§2`、`§4.2` | ✅ 一致 |
| Python 包根 `shoppilot_ai` | `§2` | ✅ 一致 |
| Java 10 个模块目录名 | `§7.1` | ✅ 一致 |
| `{api, application, domain, infrastructure}` 4 层 | `§4.2` | ✅ 一致 |
| Python 8 模块目录 | `§7.2` | ✅ 一致 |
| 前端命名（`views/<module>` 等） | `§4.3` | ✅ 一致 |
| Python 命名规范 | `§4.4` | ✅ 一致 |
| M0 不生成迁移 SQL / 不创建代码 | `§2.1` Out of Scope | ✅ 一致 |

### 11.1 发现的不一致 / 未明确之处

| # | 位置 | 现象 | 建议 |
| --- | --- | --- | --- |
| 1 | `DESIGN_BASIS.md §7.2` | 规定 Python 模块路径为 `app/api/`、`app/schemas/` 等（即 `app` 为包根内部目录），而 `§2` 规定"Python 包根 `shoppilot_ai`"。两者未说明 `app` 与 `shoppilot_ai` 的关系（`app` 是否为 `shoppilot_ai` 的别名/子包）。 | **与 DESIGN_BASIS 表述未明确**，建议在 `§2` 或 `§7.2` 补一句约定（如"`shoppilot_ai` 为可分发包名，服务容器内以 `app/` 作为包根"，或统一为 `shoppilot_ai/`）。本文件 §5 已按 `§7.2` 的 `app/` 布局书写，并注明包根为 `shoppilot_ai`。 |
| 2 | `DESIGN_BASIS.md §7.1` | 原"10 个模块均为业务模块，未定义 Spring Boot 启动模块" | ✅ **已裁定**：新增 `backend-java/app` 为第 11 模块（组合根，非业务），承载 `@SpringBootApplication`，依赖全部业务模块 + `common`，自身不被依赖（M1-T02 落骨架） |

---

## 12. 变更记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-14 | M0 初稿：目录树、Java/Python/前端结构、deploy 与 scripts 规划、依赖规范 | 架构 |
