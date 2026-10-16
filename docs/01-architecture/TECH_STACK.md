# ShopPilot AI 技术选型（TECH_STACK）

## 文档元信息

| 项 | 内容 |
| --- | --- |
| 文档名称 | 技术选型说明 |
| 文档路径 | `docs/01-architecture/TECH_STACK.md` |
| 文档优先级 | P3（见 `DESIGN_BASIS.md §1.1`） |
| 所属阶段 | M0 设计阶段 |
| 版本 | v1.0（M0 定稿） |
| 上游权威来源 | `docs/DESIGN_BASIS.md §2`（技术标识）、`§2.1`（In/Out of Scope）、`docs/decisions/ADR-001-*.md` |
| 主要读者 | 开发、DevOps、架构评审 |

### 本文档负责什么

- 逐项列出技术选型：**项 / 版本 / 用途 / 选择理由 / 替代方案 / 备注**。
- 明确**不引入**的技术及理由。
- 定义**版本锁定**与**依赖升级**策略。
- 定义**开发环境要求**。
- 与 `DESIGN_BASIS.md §2.1` Out of Scope 做**一致性核对**。

### 本文档不负责什么

- **不定义**模块边界与依赖（见 `MODULE_BOUNDARIES.md`）。
- **不定义**目录结构（见 `PROJECT_STRUCTURE.md`）。
- **不定义**部署拓扑（见 `SYSTEM_ARCHITECTURE.md §部署视图`）。
- **不定义**具体业务规则与公式（见 `DESIGN_BASIS.md §10`、`§11`）。

> **版本说明**：本文档给出的是 **M0 基线版本**。实际依赖由 `backend-java/pom.xml`、`frontend-web/package.json`、`service-ai/pyproject.toml`（或 `requirements.txt`）锁定；若与本文档不一致，以**锁定文件**为准，但变更需在 PR 中说明理由。

---

## 1. 技术选型总表

### 1.1 前端（`frontend-web`）

| 项 | 版本 | 用途 | 选择理由 | 替代方案 | 备注 |
| --- | --- | --- | --- | --- | --- |
| Vue | **3.4.x / 3.5.x** | SPA 框架 | 组合式 API 与 TypeScript 结合好；生态成熟（Element Plus / Pinia）；中文文档完善 | React、Svelte | 使用 `<script setup>` + 组合式 API |
| TypeScript | **5.x（5.4+）** | 类型系统 | 编译期拦截类型错误；配合前端类型定义降低联调成本 | 纯 JS（不推荐） | 开启 `strict` 模式；`types/` 目录集中定义 |
| Vite | **5.x / 6.x** | 构建与开发服务器 | 冷启动快、HMR 快、配置简单；与 Vue 3 官方模板一体 | Webpack、Rspack | 生产构建输出静态资源，由 nginx 托管 |
| Element Plus | **2.x** | UI 组件库 | 中后台组件齐全（表格、表单、日期、上传）；与 Vue 3 原生适配 | Ant Design Vue、Naive UI | 按需引入以控制体积 |
| Pinia | **2.x** | 状态管理 | Vue 3 官方推荐；API 简洁、TS 友好 | Vuex | 仅存全局态（用户、团队、权限），业务态尽量走 API |
| Vue Router | **4.x** | 路由 | Vue 3 配套；支持路由守卫做前端权限拦截（仅体验层） | — | **前端权限仅体验层，安全在服务端**（见 `USER_ROLES.md §4`） |
| Axios | **1.x** | HTTP 客户端 | 拦截器便于统一注入 Token 与 `X-Request-Id`、统一处理错误码 | fetch 封装 | 统一在 `api/` 层封装，`views` 不直接调 axios |
| ECharts | **5.x** | 图表 | 库存趋势等可视化；中文文档与示例丰富 | Chart.js、AntV G2 | 按需引入模块以控制体积；首期仅 P2 使用 |
| VueUse | **10.x+**（可选） | 组合式工具库 | 轮询、防抖、卸载清理等常用逻辑 | 手写 composable | **轮询必须用 `useIntervalFn`/`onUnmounted` 保证页面卸载停止**（见 `SYSTEM_ARCHITECTURE.md §7.5`） |

### 1.2 后端（`backend-java`）

| 项 | 版本 | 用途 | 选择理由 | 替代方案 | 备注 |
| --- | --- | --- | --- | --- | --- |
| Java | **21 LTS** | 运行时 | LTS、长期支持；虚拟线程、Record、模式匹配；`DESIGN_BASIS.md §2` 未限定但 `§14.2` 要求 `BigDecimal` | Java 17 LTS | 见 §1.2.1 对 Spring Boot 版本的联动 |
| Spring Boot | **3.5.x**（基线建议 `3.5.15`） | 应用框架 | 见 §1.2.1 详细论证 | Spring Boot 3.4.x / 4.x | **与 Java 21 是官方最佳组合**；锁定 patch 版本 |
| Spring Security | **6.5.x**（随 Boot 3.5 管理） | 认证与授权 | `§5.1` 要求权限矩阵与团队隔离；6.x 的 Lambda DSL 与 `AuthorizationManager` 类型安全；生态可扩展 OAuth2/OIDC | Apache Shiro | Spring Boot 3.5 默认管理 6.5.x，无需手动指定 |
| MySQL | **8.0.x**（建议 8.0.36+） | 业务数据库 | `DESIGN_BASIS.md §2` 数据库名 `shoppilot`；8.0.16+ 支持 `CHECK` 约束（`§4.1` 枚举校验依赖） | MySQL 8.4 LTS、PostgreSQL | **必须 ≥ 8.0.16**，否则 `CHECK` 约束不可用 |
| Maven | **3.9.x** | 构建工具 | 多模块（10 个模块）聚合构建成熟；与 Spring Boot 父 POM 配合好 | Gradle | 多模块 `<modules>` 聚合 |
| Flyway | **随 Boot 3.5 管理（11.x）** | 数据库迁移 | 版本化迁移、可重复、可校验；`§2.1` 明确 M1 起生成迁移 SQL | Liquibase | **M0 不生成迁移 SQL**（`§2.1`） |
| MyBatis-Plus | **3.5.x**（建议 `3.5.12+`，当前 `3.5.17`） | 数据访问 | 见 §1.2.2 详细论证；**关键：需要 `mybatis-plus-spring-boot3-starter`** | Spring Data JPA、原生 MyBatis | `TenantLineInnerInterceptor` 是团队隔离兜底（`§6 第 4 条`） |
| JUnit | **5.x（Jupiter）** | 单元测试 | Spring Boot 3.x 默认测试框架 | TestNG | 规则引擎测试（`§11.3`）需覆盖边界用例 |
| Testcontainers | **1.19.x+** | 集成测试 | 用真实 MySQL 容器做集成测试，避免"内存库与生产不一致" | H2 + 手写 SQL | **不使用 H2 替代 MySQL**（枚举 `CHECK`、`DECIMAL` 行为差异） |
| Micrometer + Actuator | **随 Boot 3.5 管理** | 指标与健康检查 | `NFR-OBS` 要求指标埋点；Actuator 提供健康端点 | 自研监控 | 暴露 `/actuator/health`；指标前缀统一 |
| SpringDoc OpenAPI | **2.x**（springdoc-openapi-starter-webmvc-ui，与 Boot 3.x 匹配） | API 文档 | 由注解生成 OpenAPI，供 `API_CONTRACT.md` 校验 | Springfox（已不维护） | 与 `docs/03-api/API_CONTRACT.md` 人工校验双轨 |
| jjwt | **0.12.x** | JWT 编解码 | 若 `D-01` 采纳 JWT（无状态），需 JWT 库 | Nimbus JOSE + JWT | **与待决策 `D-01` 强相关**；Access 2h / Refresh 7d |
| Lombok | **1.18.x** | 样板代码生成 | 减少 getter/setter/builder 样板 | Java Record、手写 | 与 `DESIGN_BASIS.md §4.2` 的 4 层结构配合；避免过度使用 |
| Apache POI | **5.3.0**（显式指定，Boot BOM **不管理**） | Excel 解析 | M3 导入需读写 `.xlsx`（XSSF）与 `.xls`（HSSF），`F-IMP-01` 要求 CSV/Excel 双格式；采用 SAX 事件模型（`XSSFReader` + `XSSFSheetXMLHandler` / `HSSFEventFactory`）做流式解析，避免整文件读入内存（`IMPORT_WORKFLOW §8.5`） | EasyExcel（禁：`§2.1` 不引入）、JXL（已停维护） | M3-T03 引入（`poi:5.3.0` + `poi-ooxml:5.3.0`）。**★ 配套锁版**：`poi-ooxml` 运行时要求 `commons-compress ≥ 1.26.2`；Testcontainers 传递声明 `1.24.0`，按 Maven「最近者优先」胜出并进 fat jar，会致运行期 `NoSuchMethodError: ZipArchiveOutputStream.putArchiveEntry`（`.xlsx` 读写全废）。故在父 `pom.xml` `<dependencyManagement>` **锁定 `commons-compress:1.26.2`**——此属**既有传递依赖的版本纠正**，非新增依赖（见 R36 第二根因） |

#### 1.2.1 Spring Boot 版本论证（重点）

**基线建议：`Spring Boot 3.5.x`（锁定 patch，如 `3.5.15`）**

| 维度 | 论证 |
| --- | --- |
| 与 Java 21 的兼容性 | Spring Boot 3.x 最低要求 Java 17，官方推荐 Java 21。3.5.x 官方兼容 Java 17–25，是 **3.x 系列对 Java 21 支持最成熟**的一支（虚拟线程、容器启动优化）。 |
| 与 Spring Security 6 的兼容性 | Spring Boot 3.5 默认管理 **Spring Security 6.5.x**，6.x 的过滤器链与 `AuthorizationManager` 与 Boot 3.5 完全对齐，无需手动降级。 |
| 与 MyBatis-Plus 的兼容性 | MyBatis-Plus 3.5.x 明确要求 Spring Boot 3.x（最低 3.5.3.1），3.5.x 组合为官方文档主流组合，社区案例最多。 |
| 为何**不选** `3.3.x` / `3.4.x` | 截至本文档编写时间（2026-10），**Spring Boot 3.3 的 OSS 支持已结束（2025-06-30），3.4 的 OSS 支持已结束（2025-12-31）**，二者均已不再接收开源补丁。全新项目选它们等于一开工就带安全债，因此**不推荐**。 |
| 为何**暂不选** `4.x` | Spring Boot 4.x（Spring Framework 7）虽为当前 OSS 主线，但对**全新生态适配**（第三方 starter、字节码工具、`Jakarta EE 11`）仍在追赶期；本项目为"快速形成闭环"的首期工程，**以生态成熟度优先**，且 `DESIGN_BASIS.md` 与 `SYSTEM_ARCHITECTURE.md` 均按 `Spring Boot 3.x` 基线下笔。 |
| OSS 支持窗口与风险提示 | Spring Boot 3.5.x 的 OSS 支持已于 2026-06-30 结束（商业支持窗口更长）。**本项目需在 M7 前评估升级到 4.x 的路径**，并把"Spring Boot 升级"列入 §3.2 升级策略的**强制评审项**。若团队具备 4.x 适配能力且需要长期 OSS 支持，可在 M1 前将基线切换为 `4.x`，并将本文档与 `SYSTEM_ARCHITECTURE.md` 中的 `Spring Boot 3.x` 表述同步更新（**新增待决策，建议编号 D-10**，见 §6）。 |

> **结论**：M0 基线锁定 **Spring Boot 3.5.x + Java 21 + Spring Security 6.5.x + MyBatis-Plus 3.5.x**，并在 M7 前完成 4.x 升级评估。

#### 1.2.2 为何用 MyBatis-Plus 而非 JPA/Hibernate

| 维度 | MyBatis-Plus（选） | JPA/Hibernate（不选） |
| --- | --- | --- |
| SQL 可控性 | SQL 显式可控，便于按 `team_id` 最左索引优化（`§6 第 6 条`） | 隐式生成 SQL，复杂查询与索引控制成本高 |
| 团队隔离兜底 | `TenantLineInnerInterceptor` 自动追加 `team_id`，是 SSOT `§6 第 4 条` 指定的兜底方案 | 需 `@Filter`/多租户实现，配置分散 |
| 枚举存储 | 与 `§4.1` "枚举以 `VARCHAR(32)` 存大写字符串"完全贴合（自定义 `TypeHandler`） | 与 `@Enumerated(STRING)` 也能配合，但迁移与 `CHECK` 约束更繁琐 |
| 批量导入 | 原生批量 `insert` / `update` 性能可控（导入 1 万行 < 60s，`NFR-PERF-02`） | `saveAll` 与一级缓存在大批量下需要额外调优 |
| 可读性 | 复杂统计/聚合同样需要手写 SQL，MyBatis 天然合适 | 复杂聚合用 Criteria/JPQL 可读性差 |
| 学习成本 | 团队普遍熟悉 MyBatis | JPA 的脏检查、延迟加载、N+1 是常见坑 |
| 一致性 | 与 `TenantLineInnerInterceptor` 需求直接匹配 | 无直接对应 |

> 依据：`DESIGN_BASIS.md §6 第 4 条`要求"MyBatis-Plus 通过全局租户插件（TenantLineInnerInterceptor）自动追加 `team_id` 条件作为兜底"，即 SSOT **已明确选定 MyBatis-Plus**。

### 1.3 AI 服务（`service-ai`）

| 项 | 版本 | 用途 | 选择理由 | 替代方案 | 备注 |
| --- | --- | --- | --- | --- | --- |
| Python | **3.11+** | 运行时 | 3.11 起性能与异常信息显著改善；`DESIGN_BASIS.md §2` Python 包根 `shoppilot_ai` | Python 3.12 | 锁定次版本，避免 3.13 生态未稳 |
| FastAPI | **0.11x** | Web 框架 | 原生异步、Pydantic 集成、自动 OpenAPI（便于 `AI_SERVICE_CONTRACT.md` 对齐） | Flask、Litestar | 用 `uvicorn` 作为 ASGI 服务器 |
| Pydantic | **v2** | 数据校验 | `§7.2` 要求 Schema 层与结构化输出校验；v2 性能更好（Rust 内核） | v1（不推荐） | **Python 侧结果 Schema 校验的落地依赖**（`ANL-7004`） |
| pandas | **2.x** | 数据分析 | 移动平均、3σ 截尾、波动率等统计计算（`§10.1`） | NumPy 手写、Polars | **pandas 计算结果须显式转 `Decimal`**（`§14.2`） |
| httpx | **0.27.x** | HTTP 客户端 | 支持同步/异步、超时与重试控制（LLM 调用，`§13.4`） | requests（无异步） | 单次 LLM 调用默认 30s，最多 2 次指数退避重试 |
| LLM SDK | **OpenAI 兼容客户端（`openai` 官方 SDK，版本 1.x）** | LLM 调用 | SSOT 未绑定厂商；**通过可配置 `base_url` + `api_key` 适配任意 OpenAI 兼容服务** | Anthropic SDK、各厂商专有 SDK | 见 §1.3.1 |
| uvicorn | **0.3x** | ASGI 服务器 | 与 FastAPI 标准搭配；支持 `BackgroundTasks`/线程池（`§13.1` 异步执行） | gunicorn + uvicorn worker | **仅内网暴露**（`§13.3`） |
| pytest | **8.x** | 测试 | Python 事实标准；规则与统计模块需充分测试 | unittest | 规则函数覆盖边界用例（`§11.3` 精神） |
| ruff / black | **最新稳定** | Lint / 格式化 | 统一代码风格（对应 `§4.4` 命名规范） | flake8 + isort | 提交前自动执行 |

#### 1.3.1 LLM 接入策略（不绑定单一厂商）

| 策略 | 说明 |
| --- | --- |
| 接口形态 | 采用 **OpenAI 兼容的 Chat Completions 接口**，通过环境变量 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL` 配置 |
| 为何不绑定单一厂商 | `DESIGN_BASIS.md` 未指定 LLM 厂商；小团队需要按成本/可用性切换；可配置 `base_url` 可对接自建/代理/国产兼容服务 |
| 降级 | LLM 失败/超时/Schema 校验失败 → 任务 `DEGRADED`（`§9.7`），`llm_status` 记录 `FAILED` / `TIMEOUT` / `VALIDATION_FAILED`（`§9.8`） |
| 禁止 | **LLM 不得参与任何判定**（`§11` 开篇）；不得直接执行采购/库存/审批（见 `SYSTEM_ARCHITECTURE.md §5` 边界 9） |

---

## 2. 明确不引入的技术

| 不引入 | 理由 | 依据 |
| --- | --- | --- |
| **Redis** | 首期无强缓存/分布式锁刚需；多一个需要运维的组件；分析任务状态用 MySQL + HTTP 轮询即可 | `DESIGN_BASIS.md §2.1`、待决策 `D-07` |
| **Kafka / RabbitMQ** | 首期异步分析用 HTTP + 状态轮询（`§13.1`）；引入 MQ 增加部署、幂等、顺序处理复杂度，收益不匹配 | `DESIGN_BASIS.md §2.1`、`§13.1` |
| **Kubernetes** | 单团队规模用 Docker Compose 单机部署即可；K8s 的运维成本远超收益 | `DESIGN_BASIS.md §2.1` |
| **微服务拆分 / 服务注册中心** | 采用模块化单体（10 个模块），保留拆分可能但不提前支付分布式成本；注册中心（Nacos/Eureka）无必要 | `DESIGN_BASIS.md §2.1`、`§7.1` |
| **LangChain / LangGraph** | 首期用**显式顺序编排 + 步骤记录**，可控、可测、可解释；框架抽象会掩盖实际行为，不利于排障 | `DESIGN_BASIS.md §7.2` 注、ADR-001 |
| **JPA / Hibernate** | 见 §1.2.2；SSOT 已选定 MyBatis-Plus 与租户插件 | `DESIGN_BASIS.md §6 第 4 条`、ADR-001 |
| **前端 SSR（Nuxt 等）** | 本产品是登录后使用的后台工具，无 SEO 与首屏内容需求；SSR 增加部署与调试复杂度 | 本文件判定 |
| **Multi-Agent 框架** | 首期任务无需多智能体协作；引入会显著增加复杂度与不可解释性 | `DESIGN_BASIS.md §2.1` |
| **ML 时间序列框架（Prophet / LSTM 等）** | 小团队数据量与质量不足以支撑模型；确定性移动平均 + 3σ 截尾可解释可测 | `DESIGN_BASIS.md §2.1`、待决策 `D-03` |
| **H2 / 内存数据库做集成测试** | 与 MySQL 8 的 `CHECK` 约束、`DECIMAL` 行为存在差异，会掩盖问题 | 本文件 §1.2 Testcontainers 备注 |
| **移动端 / 小程序框架** | 首期目标是 PC 批量操作 | `DESIGN_BASIS.md §2.1` |

---

## 3. 版本锁定与升级策略

### 3.1 版本锁定策略

| 层面 | 策略 |
| --- | --- |
| Java 依赖 | 通过 `spring-boot-starter-parent` 统一管理 Spring 生态版本；非托管依赖在 `<dependencyManagement>` 显式声明版本；**禁止**在业务模块中散落版本号 |
| Java 运行时 | `maven-compiler-plugin` 设置 `<release>21</release>`；`maven-enforcer-plugin` 强制 JDK 版本，防止环境不一致 |
| Maven 构建 | 使用 `mvnw` / `mvnw.cmd`（Maven Wrapper）锁定 Maven 版本 |
| 前端 | 提交 `package-lock.json`（或 `pnpm-lock.yaml`）；使用 `package.json` 的 `engines` 声明 Node 版本；**禁止**使用 `^` / `~` 之外的浮动范围导致构建不可复现（CI 使用 `npm ci`） |
| Python | 使用 `requirements.txt`（含 `==` 精确版本）或 `pyproject.toml` + 锁文件；虚拟环境隔离 |
| 数据库 | MySQL 使用固定小版本镜像 tag（如 `mysql:8.0.36`），**禁止使用 `mysql:8` 这类浮动 tag** |
| 容器镜像 | 所有基础镜像使用固定 tag 或 digest（详见 `deploy/docker-compose.yml`） |

### 3.2 依赖升级策略

| 项 | 策略 |
| --- | --- |
| 例行升级 | 每季度一次小版本升级窗口；安全补丁（CVE）即时评估，高危及在修复窗口内升级 |
| 主版本升级 | 必须走 **ADR**；需评估兼容性、迁移成本、回滚方案 |
| 升级前置 | 必须有自动化测试（单元 + 集成 + 规则引擎用例）覆盖；升级后全量回归 |
| **强制评审项** | ① **Spring Boot 3.5.x 的 OSS 支持窗口已结束**，M7 前必须完成"升级到 4.x 或购买商业支持"的决策与预案（见 §1.2.1）；② MyBatis-Plus 版本升级需确认与 Spring Boot 版本的 starter 匹配；③ 数据库驱动升级需回归 `DECIMAL` / `CHECK` 行为 |
| 依赖扫描 | 定期扫描依赖漏洞（CVE）；禁止引入无维护、无许可证的开源库 |
| 锁文件冲突 | `pom.xml` / `package-lock.json` / `requirements.txt` 的变更必须在 PR 中说明原因，并在 `docs/06-development/DESIGN_REVIEW.md` 记录 |

---

## 4. 开发环境要求

| 组件 | 最低版本 | 推荐版本 | 说明 |
| --- | --- | --- | --- |
| JDK | 21 | 21 LTS（如 Temurin 21） | `JAVA_HOME` 指向 21；由 enforcer 校验 |
| Node.js | **20+** | 20 LTS 或 22 LTS | 前端构建与开发服务器 |
| 包管理器 | npm 10+（或 pnpm 9+） | 与锁文件一致 | 若用 pnpm 需团队统一 |
| Python | **3.11+** | 3.11.x | AI 服务 |
| Docker Desktop | 最新稳定 | 最新稳定 | 运行 MySQL、Compose 编排、Testcontainers 需要 Docker 守护进程 |
| MySQL 客户端 | 8.0 CLI | 8.0.x | 本地连库排查（`mysql` 命令行 / 图形化客户端） |
| Git | 2.40+ | 最新稳定 | 版本控制 |
| IDE | — | IntelliJ IDEA（Java）、VS Code（前端 / Python） | 统一格式化配置 |

> 环境变量与本地启动说明见 `README.md` 与 `.env.example`；首个可运行版本在 M1 交付。

---

## 5. 与 `DESIGN_BASIS.md §2.1` Out of Scope 的一致性核对

| `§2.1` Out of Scope 项 | 本文档是否引入 | 核对结果 |
| --- | --- | --- |
| 真实电商平台 API 对接 | 未引入 | ✅ 一致（仅预留适配层位，本文档未选型任何平台 SDK） |
| 机器学习时间序列预测（LSTM / Prophet） | 未引入（明确列入 §2 不引入） | ✅ 一致 |
| Multi-Agent 系统 | 未引入（明确列入 §2 不引入） | ✅ 一致 |
| Kubernetes | 未引入（明确列入 §2 不引入） | ✅ 一致 |
| Kafka / RabbitMQ | 未引入（明确列入 §2 不引入） | ✅ 一致 |
| 微服务拆分 / 服务注册中心 | 未引入（明确列入 §2 不引入） | ✅ 一致 |
| SaaS 计费 / 多仓调拨 / 多币种 | 未引入（未选型相关技术） | ✅ 一致 |
| Redis | 未引入（明确列入 §2 不引入） | ✅ 一致 |
| 正式 Flyway 迁移 SQL | 选型了 Flyway，但**M0 不生成迁移 SQL**（`§2.1`） | ✅ 一致（选型 ≠ 生成 SQL） |
| 移动端 / 小程序 | 未引入 | ✅ 一致 |
| LangChain / LangGraph | 未引入（明确列入 §2 不引入） | ✅ 一致 |

> **结论**：本文档技术选型**完全落在 `DESIGN_BASIS.md §2.1` In Scope 范围内**，未与任何 Out of Scope 项冲突。

---

## 6. 与 `DESIGN_BASIS.md` 不一致之处与新增待决策

### 6.1 不一致

| # | 位置 | 现象 | 建议 |
| --- | --- | --- | --- |
| — | — | 本文档**未发现**与 `DESIGN_BASIS.md` 的事实冲突。 | — |

> 说明：`DESIGN_BASIS.md §2` 只固定了"Java 包根 / Python 包根 / 数据库名 / API 版本 / 时区 / 语言"，**未固定任何框架版本**，因此本文档的版本选型属于**新填补的空白**，不构成冲突。

### 6.2 新增待决策

| 编号（建议） | 问题 | 推荐方案 | 可选方案 | 影响 |
| --- | --- | --- | --- | --- |
| **新增待决策（建议编号 D-10）** | Spring Boot 基线是否停留在已过 OSS 支持的 3.5.x，还是直接采用当前 OSS 在支持的 4.x | **推荐：M0–M6 用 3.5.x**（生态成熟、与既定 Java 21 + Security 6 + MyBatis-Plus 3.5 组合最稳），**M7 前完成 4.x 升级评估** | 直接以 4.1.x 起步（获得长期 OSS 支持，但承担 Jakarta EE 11 / Spring Framework 7 生态适配风险） | 影响技术基线、依赖兼容性、迁移成本、安全补丁窗口；若采纳需同步修订 `SYSTEM_ARCHITECTURE.md` 与本文档的 `Spring Boot 3.x` 表述 |
| **新增待决策（建议编号 D-11）** | MySQL 基线是否从 `8.0.x` 提升到 `8.4 LTS` | **推荐：保持 8.0.x**（`DESIGN_BASIS.md` 与任务基线为 MySQL 8.0，且 `CHECK` 约束自 8.0.16 已满足；升级 8.4 收益有限） | 采用 `8.4 LTS`（长期支持至 2032） | 影响数据库生命周期与升级窗口；切换需回归 `CHECK` / `DECIMAL` / 驱动行为 |

> 声明：`D-10`、`D-11` 为本文档新识别的关键决策点，编号为**建议**，最终以 `DESIGN_BASIS.md §15` 修订为准。其中 `D-10` 与安全支持窗口直接相关，优先级高于 `D-11`。

---

## 7. 变更记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-14 | M0 初稿：前后端与 AI 服务选型、不引入技术、锁定与升级策略、环境要求 | 架构 |
