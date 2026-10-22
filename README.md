# ShopPilot AI

**电商库存决策与采购协同平台**

> 当前状态：**M0–M7 已完成**（M7 = 集成、部署与项目交付：端到端 132 断言、安全 32、可观测性 15、性能 28、Compose 15、新环境复现 18；全量回归 **846/0/0/0**）。
> 已落地：登录/团队隔离、主数据、CSV 导入、库存余额与流水（含盘点与冲销）、销售日数据、Python 确定性分析内核、
> 补货建议状态机与 LLM 解释（可降级）、采购单与部分到货入库、审计查询；前端 4 大业务域页面齐备。
> 验收证据：全量 `mvn verify` **843 用例 / 0 失败 / 0 跳过**（74 个测试类，含 Testcontainers 真库集成），
> 另跑独立真栈探针 28 断言 0 失败（`backend-java/tools/m6-t13/`，详见 `docs/06-development/DESIGN_REVIEW.md §5.79`）。
> 已知缺口：Python 侧 LLM 输出 Schema 校验未实现（`R155`，Java 二次校验兜底），详见 `DESIGN_REVIEW` 风险台账。

---

## 1. 这是什么

ShopPilot AI 面向**小型电商团队**，把"库存数据 → 风险识别 → 补货建议 → 人工审核 → 采购执行 → 到货入库 → 复盘"串成**一条可追溯的业务闭环**。

它不是一个 AI 聊天页面，也不是只放几张图表的管理后台。它的核心设计原则是：

- **确定性程序逻辑对结果负责**：库存、补货量、风险等级全部由 Java 业务逻辑与 Python 规则计算得出，可解释、可测试、可复现。
- **大模型只负责解释**：LLM 把已经算好的结果翻译成运营人员能看懂的话，它不产生业务数字、不改库存、不审批、不创建采购单。
- **Java 是业务数据的唯一权威写入入口**：Python 分析服务不直接写业务库，所有库存变更都必须经过 Java 的业务逻辑。
- **一切可追溯**：每次分析都保存输入快照、计算参数、算法版本、Prompt 版本与模型名。

## 2. 首期范围

**做**：多团队与角色权限、商品/SKU/供应商/仓库、CSV/Excel 数据导入（模板、字段映射、校验、错误报告、批次）、库存余额与流水与盘点、日销售数据、确定性库存风险分析与补货计算、LLM 解释（可降级）、补货建议审核、采购单与部分到货入库、审计日志、Docker Compose 本地部署。

**不做**（首期明确排除）：真实电商平台 API 对接、机器学习时间序列预测、Multi-Agent、Kafka/RabbitMQ、Kubernetes、微服务拆分、Redis、SaaS 计费、跨仓调拨 UI。

完整边界见 [`docs/DESIGN_BASIS.md` §2.1](docs/DESIGN_BASIS.md)。

## 3. 技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router + Axios + ECharts |
| Java 后端 | Java 21 + Spring Boot 3.5.x + Spring Security 6 + MySQL 8 + Maven + Flyway + MyBatis-Plus + JUnit 5 + Testcontainers |
| Python AI 服务 | Python 3.11+ + FastAPI + Pydantic v2 + pandas + httpx |
| 基础设施 | Docker Compose（mysql / backend-java / service-ai / frontend-web / nginx） |

选型理由与版本论证见 [`docs/01-architecture/TECH_STACK.md`](docs/01-architecture/TECH_STACK.md)。

## 4. 目录结构（规划）

```
ShopPilot-AI/
├── README.md                  # 本文件
├── AGENTS.md                  # 开发 必须遵守的项目规则（进入 M1 前必读）
├── docs/                      # 全部设计文档（当前唯一有内容的目录）
├── backend-java/              # Java 后端（模块化单体，11 模块）    [M1 已创建]
├── service-ai/                # Python AI 分析服务（FastAPI）       [M1 已创建]
├── frontend-web/              # Vue 3 前端                         [M1 已创建]
├── deploy/                    # Docker Compose 与部署配置           [M1 已创建]
├── .github/workflows/         # CI 流水线（backend/frontend/service-ai/smoke）
└── scripts/                   # 演示数据、健康检查等辅助脚本        [规划，尚未创建]
```

各目录职责边界见 [`docs/01-architecture/PROJECT_STRUCTURE.md`](docs/01-architecture/PROJECT_STRUCTURE.md)。

## 5. 文档索引

### 起点（必读）

| 文档 | 说明 |
| --- | --- |
| [`docs/DESIGN_BASIS.md`](docs/DESIGN_BASIS.md) | **单一事实来源（SSOT）**：术语、命名、状态枚举、错误码、补货公式、风险规则、待决策清单。**所有文档冲突以它为准。** |
| [`AGENTS.md`](AGENTS.md) | 开发 的项目规则（20 条硬性约束） |

### 产品

- [`docs/00-product/PRD.md`](docs/00-product/PRD.md) — 产品需求、P0/P1/P2 功能清单、需求追踪矩阵
- [`docs/00-product/USER_ROLES.md`](docs/00-product/USER_ROLES.md) — 五种团队角色与完整权限矩阵

### 总体架构

- [`docs/01-architecture/SYSTEM_ARCHITECTURE.md`](docs/01-architecture/SYSTEM_ARCHITECTURE.md) — 上下文图、组件图、数据流、调用与写入边界、异步任务设计
- [`docs/01-architecture/MODULE_BOUNDARIES.md`](docs/01-architecture/MODULE_BOUNDARIES.md) — Java 10 模块与 Python 8 模块的职责、依赖方向与事务边界
- [`docs/01-architecture/TECH_STACK.md`](docs/01-architecture/TECH_STACK.md) — 技术选型表与不引入清单
- [`docs/01-architecture/PROJECT_STRUCTURE.md`](docs/01-architecture/PROJECT_STRUCTURE.md) — 完整目录树与代码组织规范

### 数据库

- [`docs/02-database/ER_DIAGRAM.md`](docs/02-database/ER_DIAGRAM.md) — 总览 ER 图 + 6 张分域 ER 图 + 关系总表
- [`docs/02-database/DATABASE_DESIGN.md`](docs/02-database/DATABASE_DESIGN.md) — 22 张表逐表设计、A~J 十项重点审查、事务与并发方案、Flyway 规范
- [`docs/02-database/DATA_DICTIONARY.md`](docs/02-database/DATA_DICTIONARY.md) — 全字段数据字典
- [`docs/02-database/DATABASE_CONSTRAINTS.md`](docs/02-database/DATABASE_CONSTRAINTS.md) — 唯一约束、索引、CHECK、外键、幂等、团队隔离、并发控制

### API 与 AI

- [`docs/03-api/API_CONTRACT.md`](docs/03-api/API_CONTRACT.md) — 对外 REST 契约、统一响应、幂等与追踪、完整接口清单与 JSON 示例
- [`docs/03-api/AI_SERVICE_CONTRACT.md`](docs/03-api/AI_SERVICE_CONTRACT.md) — Java ↔ Python 内部契约
- [`docs/03-api/ERROR_CODES.md`](docs/03-api/ERROR_CODES.md) — 完整错误码表
- [`docs/04-ai/AI_ARCHITECTURE.md`](docs/04-ai/AI_ARCHITECTURE.md) — 职责划分、Workflow 步骤、为何不用 LangGraph
- [`docs/04-ai/ANALYSIS_CONTRACT.md`](docs/04-ai/ANALYSIS_CONTRACT.md) — 分析输入/输出契约、数据质量分级、golden cases
- [`docs/04-ai/PROMPT_DESIGN.md`](docs/04-ai/PROMPT_DESIGN.md) — Prompt 模板、注入防护、模型配置与降级矩阵

### 业务流程

- [`docs/05-workflows/IMPORT_WORKFLOW.md`](docs/05-workflows/IMPORT_WORKFLOW.md) — 数据导入
- [`docs/05-workflows/INVENTORY_WORKFLOW.md`](docs/05-workflows/INVENTORY_WORKFLOW.md) — 库存管理
- [`docs/05-workflows/REPLENISHMENT_WORKFLOW.md`](docs/05-workflows/REPLENISHMENT_WORKFLOW.md) — AI 分析 + 补货审核
- [`docs/05-workflows/PURCHASING_WORKFLOW.md`](docs/05-workflows/PURCHASING_WORKFLOW.md) — 采购与到货
- [`docs/05-workflows/INVENTORY_RULES.md`](docs/05-workflows/INVENTORY_RULES.md) — 补货公式与风险规则（含算例与验收基线）

### 开发规范与计划

- [`docs/06-development/DEVELOPMENT_PLAN.md`](docs/06-development/DEVELOPMENT_PLAN.md) — M0~M7 里程碑与 109 个子任务
- [`docs/06-development/CODING_STANDARDS.md`](docs/06-development/CODING_STANDARDS.md) — Git / Java / Python / 前端 / 数据库 / 文档同步规范
- [`docs/06-development/TEST_STRATEGY.md`](docs/06-development/TEST_STRATEGY.md) — 测试分层、用例清单、Mock LLM 策略
- [`docs/06-development/ACCEPTANCE_CRITERIA.md`](docs/06-development/ACCEPTANCE_CRITERIA.md) — 里程碑验收、业务不变量、证据要求
- [`docs/06-development/DESIGN_REVIEW.md`](docs/06-development/DESIGN_REVIEW.md) — 跨文档一致性检查结果（20 项）

### 架构决策

- [`docs/decisions/ADR-001-ARCHITECTURE.md`](docs/decisions/ADR-001-ARCHITECTURE.md) — 15 项关键技术决策及重新评估触发条件

## 6. 核心业务闭环

```
① 导入基础数据（商品/SKU/供应商/仓库/库存快照/历史销售）
        ↓
② 库存余额与流水形成（余额可由流水推导）
        ↓
③ 创建分析任务 → Java 组装最小数据集 → Python 确定性计算（日均销量/风险/补货量）
        ↓
④ LLM 解释结果（失败则降级，规则结果照常保留）
        ↓
⑤ 生成补货建议 → 运营修改/提交 → 审批人审批
        ↓
⑥ 审批通过 → 创建采购单（此时库存不变）→ 确认下单
        ↓
⑦ 登记到货（支持部分到货）→ 确认 → 单事务内写入库存流水与余额 → 采购单状态推导
        ↓
⑧ 全程审计留痕，分析结果可追溯到输入快照与算法/Prompt 版本
```

## 7. 本地运行（M1–M7 已可用）

### 前置条件
- Docker Desktop（含 Docker Compose v2）。
- 数据库须 **MySQL 8.0.16+**。Compose 内已固定 `mysql:8.0.36`；**请勿用 phpStudy 自带的 8.0.12**——版本守卫会在启动时直接阻断（`DATABASE_CONSTRAINTS.md` C-11）。

### 开发栈（推荐先跑这个）
```bash
cp deploy/.env.example deploy/.env          # 按需修改口令；.env 已被 gitignore，不会入库
docker compose -f deploy/docker-compose.dev.yml up -d --build
```
启动后（默认 `NGINX_PORT=80`；宿主 80 被占用时可设 `NGINX_PORT=8081` 覆盖，compose 默认值不变）：
- 前端：http://localhost:8081/
- 后端 API 健康：`http://localhost:8081/api/v1/health` → 200 `{"status":"UP"}`
- 后端 Actuator：**仅容器内可达**（nginx 只代理 `/api/`，不代理 `/actuator`，属最小暴露设计）：
  `docker exec shoppilot-dev-backend-java-1 curl -s http://127.0.0.1:8080/actuator/health` → `{"status":"UP"}`
- AI 服务内部健康：仅容器内 `service-ai:8000/internal/v1/health`，**不暴露到宿主**（PROJECT_STRUCTURE §7.1）

### 首跑：全新库没有用户（必须先建第一个团队与 OWNER）

Flyway 迁移**只建表结构、不建业务数据** ⇒ 全新库 `teams` / `users` 为空，此时登录会得到
`401 AUTH-1002`（这是正确行为，不是故障）。系统**不提供自助注册端点**（`/api/v1/auth` 只有
`login` / `refresh` / `logout` / `me`），第一个团队与 OWNER 只能由 DBA/运维直接入库：

```bash
# 开发用种子：团队 7（星海优选）+ 团队 8 + 5 个用户 + team_members；口令统一 1234
docker exec -i shoppilot-dev-mysql-1 mysql -ushoppilot -pchangeme_app_password shoppilot \
  --default-character-set=utf8mb4 < backend-java/tools/evi/seed-b7.sql
```

> 生产首跑请自行准备「首个团队 + 首个 OWNER」的插入脚本（BCrypt 哈希可用
> `backend-java/tools/evi/GenHash.java` 生成），**不要**把任何真实口令写进入仓文件
> （CODING_STANDARDS：密钥不入仓）。

### 部署验证（M7-T05，可复跑）
```bash
bash backend-java/tools/m7-t05/verify-compose.sh                 # 6 项：服务数/健康/nginx/内网可达/不对外/迁移
bash backend-java/tools/m7-t05/verify-compose.sh --with-recreate # 追加第 7 项：down 后 up 数据保留
```

### 重置与回滚（仅开发环境）
```bash
docker compose -f deploy/docker-compose.dev.yml down       # 停栈，保留数据卷
docker compose -f deploy/docker-compose.dev.yml down -v    # 停栈并删除数据卷（清库重来）
docker compose -f deploy/docker-compose.dev.yml logs -f backend-java   # 跟踪后端日志
```
> Flyway 会在 backend 启动时自动迁移；`down -v` 后再 `up` 会重跑全部 14 个迁移（`V1.0.0`–`V1.0.13`）。

### 集成栈
```bash
docker compose -f deploy/docker-compose.yml up -d --build   # 仅 nginx 对外（默认 80）
```

### 关键事实
- Flyway 已基线化（`V1.0.0__baseline.sql`，业务 DDL 留待 M2），`baseline-on-migrate=false`（指向非空库直接报错，防结构漂移）。
- 未认证请求受 `AUTH-1001` JSON 拦截（HTTP 401），健康端点匿名放行。
- 停栈：`docker compose -f deploy/docker-compose.dev.yml down -v`

> ✅ M1–M6 已完成：登录/团队、六类数据导入、库存与销售、分析服务、LLM 解释（**未实现，见 `R155`**）、补货建议与审批、采购单与到货入库均已落地。
> 已实现的端到端闭环见 `backend-java/tools/e2e/run-e2e.sh`（20 步 / 132 断言）。
> M7 做集成与交付收口，进度见 `docs/06-development/DESIGN_REVIEW.md §5.79–§5.80`。

## 8. 给协作者的话

1. **先读 [`docs/DESIGN_BASIS.md`](docs/DESIGN_BASIS.md)**，它是所有约定的唯一出处。
2. **动代码前先读 [`AGENTS.md`](AGENTS.md)**，它规定了哪些事绝对不能做。
3. 设计文档之间如果有冲突，按 DESIGN_BASIS §1.2 的优先级规则处理，并登记到 `DESIGN_REVIEW.md`。
4. 修改数据库必须走 Flyway；修改 API 必须同步更新契约文档；修改业务流程必须同步更新流程文档与测试。
5. **不要虚构测试结果**。没跑过的测试就是没跑过。
