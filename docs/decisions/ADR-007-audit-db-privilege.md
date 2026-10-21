# ADR-007：审计日志的数据库级权限策略（D-05）

- **状态**：Accepted
- **日期**：2026-10-21
- **决策人**：项目负责人
- **关联**：`DESIGN_BASIS §15 D-05`、`MODULE_BOUNDARIES E-3`、`M7-T10`、`DESIGN_REVIEW §5.80`

---

## 1. 现状（代码与配置事实）

### 1.1 决策原文与当前口径

`DESIGN_BASIS.md:877` 对 `D-05` 的表述为：

> 审计日志是否使用独立数据库账号（仅 INSERT）——**M3 前采用"应用层禁止 UPDATE/DELETE + 独立 Mapper"，M7 视需要加 DB 级权限**；可选方案为"首期即建独立 DB 账号"；影响为"部署复杂度"。

即：**是否加 DB 级权限被推迟到 M7 评估**，M3 前的过渡口径是应用层约束。本节以下逐项核实该过渡口径的落地程度。

### 1.2 写入通道：两种语义，均已实现

`AuditWriteService` 是审计的**唯一写入口**（`backend-java/audit/src/main/java/com/shoppilot/audit/application/AuditWriteService.java:21`），内部有两条刻意不统一的通道：

| 通道 | 方法 | 事务 | 失败行为 | 适用 |
| --- | --- | --- | --- | --- |
| 解耦（默认） | `record(AuditEvent)`（`AuditWriteService.java:81`）、`recordAll(List)`（`:158`） | `REQUIRES_NEW` 独立事务（`AuditLogWriter.java:50-53`、`:89-101`） | **永不抛异常**，catch 后记 ERROR（`AuditWriteService.java:99-106`） | §2.10 高风险清单之外的动作（登录、成员、主数据、导入、销售等） |
| 同事务（高风险） | `recordInBusinessTx(AuditEvent)`（`AuditWriteService.java:132`） | `MANDATORY`，加入调用方业务事务（`AuditLogWriter.java:68-71`） | **直接抛出**，让业务一并回滚（`AuditWriteService.java:141-142`） | 库存调整、审批、确认到货、取消采购单 |

两点必须记录：

1. 两条通道的差异是**有意设计**，Javadoc 明确写有"两者语义不同是有意的，不要统一掉"（`AuditWriteService.java:26-34`）。同事务通道解决的是"审计成功但业务未生效"与"业务成功但审计缺失"两个中间态。
2. `record` 在 `AuditBufferScope` 内且事务活跃时会改为缓冲、事务提交后批量写出（`AuditWriteService.java:96-98`），但**写出仍是 INSERT**，不改变追加语义。

> 顺带澄清一处文档耦合：`MODULE_BOUNDARIES.md:565` 把 `E-3`（审计事务策略）与 `D-05`（DB 账号权限）写在同一行。二者是不同问题。`E-3` 的事务策略已由 `ADR-004` 及 `M3-B9.5` 落地为上述双通道，**本 ADR 只处理账号权限**，不重新裁定事务策略。

### 1.3 Mapper 与实体：无任何 UPDATE / DELETE

`AuditLogMapper` 是普通 `@Mapper` 接口，**不继承任何父接口**（`AuditLogMapper.java:39-40`），因此不具备 `BaseMapper` 自带的 `updateById` / `deleteById` / `delete*`。接口全部方法如下：

| 方法 | 行号 | 语句类型 |
| --- | --- | --- |
| `insert(AuditLogEntity)` | `AuditLogMapper.java:48-57` | `INSERT` |
| `insertBatch(List)` | `AuditLogMapper.java:81-92` | 多行 `INSERT` |
| `selectRecentByBiz(...)` | `AuditLogMapper.java:106-116` | `SELECT` |
| `selectAuditLogPage(...)` | `AuditLogMapper.java:151-178` | `SELECT` |
| `selectAuditLogCount(...)` | `AuditLogMapper.java:188-206` | `SELECT COUNT` |

实证结论：**生产代码中不存在任何针对 `audit_logs` 的 UPDATE / DELETE**。取证命令与结果见 §1.7 与 §6。

`AuditLogEntity` 同样只服务 INSERT：无 `updated_at` / `updated_by` / `version` 字段，`createdAt` 标注 `FieldStrategy.NEVER` 由 DB 默认值填充（`AuditLogEntity.java:12-13`、`:86-88`）。

### 1.4 表结构与数据库侧约束

建表脚本为 `backend-java/app/src/main/resources/db/migration/V1.0.4__audit.sql:42-77`：

| 项 | 事实 | 行号 |
| --- | --- | --- |
| 表名 | `audit_logs` | `V1.0.4__audit.sql:42` |
| 主键 | `id BIGINT UNSIGNED AUTO_INCREMENT` | `:43`、`:66` |
| 公共审计列 | 仅 `created_at` / `created_by`；**无 `updated_at` / `updated_by` / `version`** | `:63-65` |
| 索引 | 5 条，全部 `team_id` 最左：`idx_al_team_biz_time`、`idx_al_team_operator_time`、`idx_al_team_action_time`、`idx_al_team_request`、`idx_al_team_result_time` | `:68-72` |
| 约束 | 仅 `PRIMARY KEY` + `CHECK ck_al_result (result IN ('SUCCESS','FAILURE'))`；**零外键、零唯一约束** | `:66`、`:73` |
| 引擎/字符集 | `InnoDB` / `utf8mb4` / `utf8mb4_0900_ai_ci` | `:74-77` |

**数据库侧没有任何阻断 UPDATE / DELETE 的机制**：

- 迁移目录内**不存在 `TRIGGER`**。`grep -rni "trigger" backend-java/app/src/main/resources/db/migration/` 共 3 命中，全部是 `V1.0.9__analysis.sql:56/69/99` 的列名 `triggered_by`，与触发器无关。
- 项目规范**明确禁止使用触发器**：`DATABASE_CONSTRAINTS.md:34`（理由含"触发器拿不到 `TeamContext`""迁移与回滚复杂度激增"）。
- `DATABASE_CONSTRAINTS.md` 中与 `audit_logs` 相关的条目只有索引 `#54–#58`（`:218-222`）与 `ck_al_result`（`:328`），**没有**任何禁止 UPDATE 的触发器、视图或授权约束。

建表脚本自身也声明本批不做 DB 级账号：`V1.0.4__audit.sql:19`——"**不做** DB 级仅 INSERT 账号（D-05，M7 视需要）"。

### 1.5 数据库账号：当前只有一个应用账号

`deploy/docker-compose.dev.yml` 的 MySQL 服务只声明了一组应用凭据：

| 配置 | 值 | 行号 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | `${MYSQL_ROOT_PASSWORD:-changeme_root_password}` | `docker-compose.dev.yml:38` |
| `MYSQL_DATABASE` | `${MYSQL_DATABASE:-shoppilot}` | `:39` |
| `MYSQL_USER` | `${MYSQL_USER:-shoppilot}` | `:40` |
| `MYSQL_PASSWORD` | `${MYSQL_PASSWORD:-changeme_app_password}` | `:41` |
| 端口暴露 | `${MYSQL_PORT:-3306}:3306`（宿主可直连） | `:49-50` |

后端只使用这一组账号：`SPRING_DATASOURCE_USERNAME/PASSWORD` 取自 `${MYSQL_USER}` / `${MYSQL_PASSWORD}`（`docker-compose.dev.yml:95-97`）。本地裸跑时默认账号甚至是 `root`（`backend-java/app/src/main/resources/application.yml:7-11`）。

仓库内**没有任何可执行的授权脚本**：

```
grep -rn "GRANT \|REVOKE " --include=*.sql --include=*.yml --include=*.yaml --include=*.sh deploy backend-java
→ 0 命中
```

`DATABASE_DESIGN.md:1581` 提出的"为审计表建立仅 INSERT 的独立账号（`GRANT INSERT, SELECT`）"仍停留在**文档建议**；`:1584` 亦说明"为什么不首期就上 DB 级权限"。`:1772` 只说明此类脚本将来应放在 `R`（可重复）迁移中，**当前并不存在该迁移**。

### 1.6 文档与实现的三处偏差（据实记录）

1. **D-05 仍未闭环**：`DESIGN_BASIS.md:877` 仍是"视需要"，`MODULE_BOUNDARIES.md:286` 仍写"具体策略见待决策 `D-05`"，`DATABASE_DESIGN.md:917` 仍写"M7 视需要增加 DB 级账号权限"。
2. **任务清单口径与 D-05 原文不一致**：`.workbuddy/M7-任务清单.md:71` 写"结论只能是「新增只读账号 + `GRANT SELECT`」或「维持应用层 …」"。D-05 的原始议题是"**仅 INSERT**"的独立账号——审计写入需要 `INSERT`，查询才需要 `SELECT`；"只读账号"无法承担写入。本 ADR 以 `DESIGN_BASIS.md:877` 为准，不采用任务清单中"只读账号"的措辞。
3. **测试实践与"仅追加"叙事冲突**：测试代码在同一 schema 上直接执行 `DELETE FROM audit_logs` 共 **47 处、分布在 43 个测试文件**（如 `AuditChannelIntegrationTest.java:94`）。这属于测试清理，不代表生产路径，但它客观证明：**持有该库账号者具备 DELETE 能力，当前唯一屏障是代码纪律而非权限**。

### 1.7 "无 UPDATE/DELETE"的取证方式与 0 命中范围

```bash
# G1：生产代码（java + xml）中针对 audit_logs 的写操作
grep -rnE "(@Update\(|@Delete\(|UPDATE[[:space:]]+audit_logs|DELETE[[:space:]]+FROM[[:space:]]+audit_logs)" \
  backend-java --include=*.java --include=*.xml | grep "/src/main/"
→ 0 命中

# G2：审计 Mapper 是否继承带写方法的父接口
grep -rn "AuditLogMapper" backend-java --include=*.java | grep -i "extends"
→ 0 命中

# G3：audit 模块内的 update/delete 字样（仅 1 处，为解释性 Javadoc）
grep -rn "@Update\|@Delete\|UPDATE audit_logs\|DELETE FROM audit_logs\|deleteById\|updateById" \
  backend-java/audit/src
→ 1 命中：AuditLogMapper.java:16（注释原文"BaseMapper 自带 updateById / deleteById"，非调用）
```

0 命中的文件范围：`backend-java/**/src/main/**`（涵盖 `audit`、`app`、`common`、`catalog`、`identity`、`inventory`、`importdata`、`sales`、`analysis`、`replenishment`、`purchasing` 全部模块的 `.java` 与 `.xml`）。另需说明：`grep "@Delete"` 会误命中 6 处 `@DeleteMapping`（如 `catalog/api/ProductController.java:116`），那是主数据的停用接口，与 `audit_logs` 无关；G1 已用 `@Delete\(` 排除。

### 1.8 现有守护：一条跑在 CI 里的反射测试

`backend-java/app/src/test/java/com/shoppilot/audit/AuditChannelIntegrationTest.java:214-240` 的 `auditLogMapperAppendOnly` 断言：

- `AuditLogMapper.class.getInterfaces().length == 0`（不得继承 `BaseMapper`）；
- 声明方法集合**精确等于** `{insert, insertBatch, selectRecentByBiz, selectAuditLogPage, selectAuditLogCount}`；
- 任何方法名不得以 `update` / `delete` / `remove` / `replace` 开头。

这是当前唯一**可执行、每次构建都运行**的"审计不可改"守护。与之相对，数据库层没有任何守护：`ACCEPTANCE_CRITERIA.md:306` 的 `AC-AUD-03` 期望结果也只写"应用层无对应入口"，并未要求数据库拒绝。

---

## 2. 风险分析

### 2.1 谁有能力修改审计

| 角色 | 能力 | 依据 |
| --- | --- | --- |
| 应用代码（正常路径） | **不能**。Mapper 只有 INSERT/SELECT，且有反射测试 | `AuditLogMapper.java:40-206`、`AuditChannelIntegrationTest.java:214-240` |
| 应用代码（旁路） | **能**。`JdbcTemplate`、原生 SQL、新模块直连均可发 `UPDATE/DELETE`，编译期与测试期都不拦 | G1 只证明"当前没有"，不证明"不可能" |
| 拿到应用账号口令者 | **能**。账号对 `shoppilot` 库拥有库级全权（见 §2.2） | `docker-compose.dev.yml:40-41`；仓库 0 条 `GRANT/REVOKE` |
| 运维 / DBA | **能，且不可拦**。compose 同时下发 root 口令并暴露 3306 | `docker-compose.dev.yml:38`、`:49-50` |
| 测试代码 | **能**。47 处 `DELETE FROM audit_logs` 即为例证 | `AuditChannelIntegrationTest.java:94` 等 43 个文件 |

### 2.2 残余风险的具体形态

1. **误操作**：新同事用 `JdbcTemplate` 写一段"修数据"的脚本，顺手清理了审计表；或测试清理脚本被复制到生产运维手册。当前无任何机制阻止，也无告警。
2. **SQL 注入**：MyBatis 的 `#{}` 占位符已消除常规注入面，但一旦出现 `${}` 拼接或原生 SQL，注入者可用同一账号执行 `UPDATE audit_logs SET operator_id = ...`，直接改写归因。
3. **运维直连**：开发态 3306 对宿主暴露（`docker-compose.dev.yml:49-50`），任何能连上该端口且知道口令的人都可以改。即使将来加了表级 `REVOKE`，root 仍可改——**DB 级权限约束的是应用账号，不是人**。
4. **权限模型本身的限制**：MySQL 的权限是**叠加**的。当前 `shoppilot` 账号由镜像 entrypoint 以库级 `GRANT ALL ON shoppilot.*` 创建（仓库无自定义授权脚本，见 §1.5 的 0 命中），因此在表级 `REVOKE UPDATE, DELETE ON shoppilot.audit_logs` **不会生效**——库级授权仍然覆盖。要真正收紧，必须先放弃库级 `ALL`，改为逐表授权。这一事实决定了选项 B 的成本结构。

### 2.3 审计被篡改的影响

`audit_logs` 承载 35 类动作（`DATABASE_DESIGN.md:1509-1544`），其中高风险清单（库存调整、盘点调整、冲销、审批、确认到货、取消采购单）是**事后无法用业务数据反推**的操作（`AuditWriteService.java:110-113`）。一旦这些行被改写或删除：

- 无法回答"谁在何时把库存从 100 改成 80"，责任无法归属；
- 审批链（`REPLENISHMENT_APPROVE`、`PURCHASE_ORDER_CONFIRM`）失去不可抵赖性；
- 对账与纠纷处理缺少第三方可采信的证据；
- `occurred_at` 与 `id` 的连续性一旦被破坏，连"是否发生过删除"都无法判断。

### 2.4 检测能力的现状

当前**没有**哈希链、校验位、WORM 存储或异地副本。`grep "篡改|防篡改|合规|WORM|哈希链"` 在 `PRD.md` / `ACCEPTANCE_CRITERIA.md` / `docs/01-architecture/*.md` 中 **0 命中**（唯一相近命中是 `ACCEPTANCE_CRITERIA.md:132` 的"结果不合规"，与审计无关）。这意味着：

- 项目当前声明的目标是**运营可追溯**，不是**法律级防篡改举证**；
- 在这个目标下，DB 级 `REVOKE` 的边际价值有限——它挡不住 root/DBA，也不产生任何"被改过"的证据；
- 真要达到防篡改举证，需要的是**哈希链 + 异地日志留存**，其成本远高于一次 `REVOKE`，且不在 `D-05` 的议题范围内。

---

## 3. 选项与权衡

| 选项 | 做法 | 成本 | 收益 | 风险 |
| --- | --- | --- | --- | --- |
| **A. 维持现状（应用层约束）** | 保持单账号；仅追加由 `AuditLogMapper` 白名单 + 反射测试保证；数据库不做任何限制 | **零**（无代码、无迁移、无 compose 改动） | ① CI 每次构建验证"无 update/delete"；② 不触碰 Flyway 权限与 13 个既有迁移；③ 不破坏 `E-3` 的同事务原子性 | 持有应用账号或 root 者可改；无检测手段；依赖代码审查纪律 |
| **B-1. 同账号表级最小授权** | 放弃库级 `GRANT ALL`，改为逐表 `GRANT SELECT,INSERT,UPDATE,DELETE`，并对 `audit_logs` 仅授 `SELECT,INSERT`；Flyway 另用具备 DDL 权限的迁移账号 | **中高**：需枚举 23 张表、区分 runtime/migrate 两套账号、每次新增表都要同步授权 | 应用账号即使被注入或误用，也无法改审计；不需要第二数据源，`E-3` 原子性保持 | 新表漏授权 → 运行期 `1142` 错误；Flyway 启动失败风险；CI 用 Testcontainers 自有账号，权限改动**不被任何测试覆盖** |
| **B-2. 独立审计账号 + 第二数据源** | 审计模块单独配置 `DataSource`（仅 `INSERT,SELECT ON audit_logs`），业务走原账号 | **高**：双数据源、双事务管理器、连接池翻倍 | 账号级隔离最彻底；审计账号泄露也改不了业务表 | **直接破坏 `E-3`**：`recordInBusinessTx` 依赖 `MANDATORY` 加入业务事务（`AuditWriteService.java:132-143`），跨数据源后只能退化为解耦或引入 XA；而"同事务"正是 `M3-B9.5` 要保住的东西 |
| **C. 触发器阻断** | `BEFORE UPDATE` / `BEFORE DELETE` 触发器 `SIGNAL SQLSTATE '45000'` | 中：新增迁移 + 回滚脚本 | 对任何 DML 生效，包括原生 SQL | ① 违反 `DATABASE_CONSTRAINTS.md:34`「不使用触发器」；② 触发器可被 DBA 一条 `DROP TRIGGER` 移除，防不住真正的篡改者；③ `TRUNCATE` 不触发 `BEFORE DELETE`；④ 与"迁移可测试、可回滚"的项目约束冲突 |

补充说明：B-1 与 B-2 常被合并称作"加 DB 级权限"，但两者代价差一个数量级，故分列。无论哪一种，都**无法约束 root/DBA**，这是所有 DB 级方案的共同上限。

---

## 4. 决策

**采用选项 A：维持现状，不新增数据库级权限。**

`audit_logs` 的仅追加约束，继续由应用层作为**唯一强制手段**：`AuditLogMapper` 不继承 `BaseMapper`、只声明 `insert` / `insertBatch` / `select*`，并由 `AuditChannelIntegrationTest#auditLogMapperAppendOnly` 在每次构建时反射校验。数据库侧不新增账号、不新增 `GRANT/REVOKE`、不新增触发器。

### 4.1 理由

1. **收益边界有限**：DB 级权限只能约束"应用账号"，约束不了 root 与 DBA。而 `docker-compose.dev.yml:38` 与 `:49-50` 表明 root 口令与 3306 端口就在同一份部署配置里。真正的篡改者用 root 即可绕过任何表级授权。选项 B 的净收益只剩"应用账号凭据单独泄露"与"注入/误写"两类场景。
2. **B-1 的成本被 MySQL 权限模型放大**：权限只叠加不可减，当前账号是库级 `ALL`，表级 `REVOKE` 无效；必须改为逐表授权。项目已有 13 个迁移（`V1.0.0`–`V1.0.12`）、23 张表，未来每加一张表都要同步授权，否则运行期报 `1142`。这是把一次安全加固变成一项**长期的、易漏的运维义务**。
3. **B-2 与 `E-3` 直接冲突**：`recordInBusinessTx` 的 `MANDATORY` 语义要求审计与业务共用同一事务/连接（`AuditWriteService.java:132-143`、`AuditLogWriter.java:68-71`）。换成独立数据源后，"业务提交则审计提交、业务回滚则审计撤销"不再成立，只能退化解耦或上 XA。为降低一个"需要 DB 凭证才能实施"的风险，去破坏一个"每次高风险操作都依赖"的原子性保证，不划算。
4. **现有防线是可验证的，新防线不可验证**：CI 的集成测试使用 Testcontainers 自建账号（`AnalysisApiIntegrationTest.java:54-57` 未显式指定受限账号），生产授权脚本不会在测试中被执行。也就是说，B 实施后**没有任何自动化测试能证明它生效**；而 A 的反射测试每次构建都跑。用不可验证的防线替换可验证的防线，整体安全性未必上升。
5. **合规口径不支持过度投入**：仓库无 WORM、哈希链、取证要求（§2.4 的 0 命中）。当前目标是运营可追溯。若将来出现法律级举证需求，正确解法是哈希链 + 异地留存，单加一个 `REVOKE` 并不能满足，届时会连同 B 一起重做。

### 4.2 本决策明确不做什么

- 不新增 MySQL 账号，不修改 `docker-compose.dev.yml` 的 `MYSQL_USER` / `MYSQL_PASSWORD`。
- 不新增 `GRANT` / `REVOKE` 语句，不新增 `R__grant_*.sql`。
- 不新增触发器，不修改 `V1.0.4__audit.sql` 及任何既有迁移（校验和红线）。
- 不引入第二数据源，不改动 `AuditWriteService` / `AuditLogWriter` 的事务传播。
- 不把本结论回写到 `DESIGN_BASIS` / `DATABASE_DESIGN` / `MODULE_BOUNDARIES`（列入 §5.3 待实施清单，另行提交）。

### 4.3 决策的适用边界

本结论适用于**当前单租户部署形态 + 开发/试运行阶段 + 无外部合规举证要求**的前提。前提变化即触发 §5.1 的重新评估，不视为对 B 的永久否决。

---

## 5. 触发重新评估的条件与待实施清单

### 5.1 触发重新评估的条件（任一命中即重开 D-05）

| # | 触发条件 | 为什么它会推翻本决策 |
| --- | --- | --- |
| 1 | 合同、招标或监管要求把 `audit_logs` 纳入"不可抵赖"证据范围（等保、SOC 2、客户审计权） | 此时单靠 B 也不够，需整体升级为"权限 + 哈希链 + 异地留存"；评估范围必须扩大 |
| 2 | 生产环境出现"应用账号与运维账号共用"，或必须长期开放 DBA 直连 | 账号边界本身失效，B 的前提（账号可区分）才成立 |
| 3 | 审计表进入归档 / 分区 / 清理流程（`D-24` 被触发） | 会出现**合法的 DELETE**，必须重新定义"谁可删、删哪个时间段、删除行为如何留证" |
| 4 | 生产代码出现 Mapper 之外的 `audit_logs` 写入路径（`JdbcTemplate`、原生 SQL、新模块直连），或 Mapper 新增 `update*` / `delete*` | 应用层唯一防线被绕过；若同时有人修改了反射测试，防线将彻底失守 |
| 5 | 发生一次实际的审计缺失、被改或注入命中审计表的安全事件 | 从"理论风险"变为"已发生"，风险偏好必须下调 |
| 6 | 迁移到托管数据库（RDS / 云 MySQL），账号与授权由平台集中管理 | B-1 的边际成本显著下降，"逐表授权易漏"的主要顾虑减弱 |

### 5.2 重开评估时必须先回答的三个前提

上一轮评估暴露的三个技术事实，下一轮不得重复踩空：

1. **授权模型**：必须先放弃库级 `GRANT ALL ON shoppilot.*`。MySQL 权限只叠加，表级 `REVOKE` 对库级授权无效；B-1 的真实工作量是"逐表重建授权 + 新增表同步机制"，不是一条 `REVOKE`。
2. **迁移账号与运行账号分离**：Flyway 需要 `CREATE / ALTER / DROP / INDEX / REFERENCES`，与"运行期仅 DML"不是同一套权限。`DATABASE_DESIGN.md:1772` 已指明此类脚本应放 `R` 迁移，但需明确由哪个账号执行。
3. **`E-3` 原子性**：B-1 能保住 `E-3`，是因为它**不换账号**，只在同一账号上收窄表级权限。一旦换成独立账号（B-2），即使同库也必须是另一条连接，`MANDATORY` 无法跨连接加入业务事务——此时只能退化解耦、引入 XA，或修改 `MODULE_BOUNDARIES §2.10` 的承诺。

### 5.3 本 ADR 衍生的待实施清单（文档同步，均不在本批执行）

| # | 事项 | 目标位置 | 状态 |
| --- | --- | --- | --- |
| 1 | 将 `D-05` 标记为已裁定，结论指向本 ADR | `docs/DESIGN_BASIS.md:877` | 待实施 |
| 2 | 将"DB 层（可选，M7）"改为"评估结论：不实施，理由见 ADR-007" | `docs/02-database/DATABASE_DESIGN.md:1581` | 待实施 |
| 3 | 将"具体策略见待决策 `D-05`"改为"见 `ADR-007`"，并拆分 `E-3`（事务）与 `D-05`（权限） | `docs/01-architecture/MODULE_BOUNDARIES.md:286`、`:565` | 待实施 |
| 4 | 将 `AC-AUD-03` 的期望结果补注"数据库层不设防，残余风险见 ADR-007" | `docs/06-development/ACCEPTANCE_CRITERIA.md:306` | 待实施 |
| 5 | 统一 `M7-T10` 的任务口径（"只读账号 + `GRANT SELECT`"与 D-05 的"仅 INSERT"不一致） | `.workbuddy/M7-任务清单.md:71` | 待实施 |

### 5.4 不新增代码的补偿性措施（建议，非本批交付）

以下均为运维纪律，不改变代码与迁移，供部署时参考：

- 生产环境不对外暴露 3306，管理通道走堡垒机；
- 开启 binlog 并异地留存，使"删除/改写"至少在数据库日志层留有痕迹；
- 定期导出审计表快照并归档，作为独立于数据库的副本；
- 将"不得直连修改 `audit_logs`"写入运维手册，与 `DATABASE_CONSTRAINTS.md:667` 对 `inventory_transactions` 的同类纪律保持一致。

---

## 6. 证据来源

| # | 事实 | 证据 |
| --- | --- | --- |
| 1 | `D-05` 原文与"M7 视需要"口径 | `docs/DESIGN_BASIS.md:866`（§15 标题）、`docs/DESIGN_BASIS.md:877`（D-05 行） |
| 2 | 审计为唯一写入口，两条通道语义不同 | `backend-java/audit/src/main/java/com/shoppilot/audit/application/AuditWriteService.java:21-34` |
| 3 | `record` 独立事务、永不抛异常、缓冲分支 | 同上 `:81`、`:96-98`、`:99-106` |
| 4 | `recordInBusinessTx` 同事务、失败即抛 | 同上 `:132-143` |
| 5 | `recordAll` 批量、一次提交 | 同上 `:158-198` |
| 6 | `REQUIRES_NEW` / `MANDATORY` / `insertAll` 实现 | `backend-java/audit/src/main/java/com/shoppilot/audit/infrastructure/persistence/AuditLogWriter.java:50-53`、`:68-71`、`:89-101` |
| 7 | Mapper 不继承父接口，仅 insert 与 select | `backend-java/audit/src/main/java/com/shoppilot/audit/infrastructure/persistence/mapper/AuditLogMapper.java:13-18`、`:39-40`、`:48-57`、`:81-92`、`:106-116`、`:151-178`、`:188-206` |
| 8 | 实体无 `updated_*` / `version`，仅被 INSERT 使用 | `backend-java/audit/src/main/java/com/shoppilot/audit/infrastructure/persistence/entity/AuditLogEntity.java:12-13`、`:86-88` |
| 9 | `audit_logs` DDL：列、索引、CHECK、无外键/唯一/触发器 | `backend-java/app/src/main/resources/db/migration/V1.0.4__audit.sql:42-77` |
| 10 | 建表脚本明示"不做 DB 级仅 INSERT 账号" | 同上 `:19` |
| 11 | 迁移中无 `TRIGGER`（3 处 `trigger` 均为列名 `triggered_by`） | `backend-java/app/src/main/resources/db/migration/V1.0.9__analysis.sql:56`、`:69`、`:99` |
| 12 | 项目禁止触发器 | `docs/02-database/DATABASE_CONSTRAINTS.md:34` |
| 13 | 审计表相关约束仅索引与 CHECK | `docs/02-database/DATABASE_CONSTRAINTS.md:218-222`、`:328` |
| 14 | 更新规则为"仅 INSERT，应用层禁止 UPDATE/DELETE" | `docs/02-database/DATABASE_DESIGN.md:917` |
| 15 | DB 层仅 INSERT 账号仍为"可选、视需要" | `docs/02-database/DATABASE_DESIGN.md:1581`、`:1584` |
| 16 | 授权脚本应放 `R` 迁移（当前不存在） | `docs/02-database/DATABASE_DESIGN.md:1772` |
| 17 | 单应用账号与 root 口令、3306 暴露 | `deploy/docker-compose.dev.yml:38`、`:40-41`、`:49-50`、`:95-97` |
| 18 | 本地默认以 root 连接 | `backend-java/app/src/main/resources/application.yml:7-11` |
| 19 | 仓库内 0 条可执行 `GRANT` / `REVOKE` | `deploy/` + `backend-java/` 的 `*.sql` / `*.yml` / `*.yaml` / `*.sh`，命令见 §1.5 → 0 命中 |
| 20 | 生产代码 0 处 `audit_logs` 的 UPDATE/DELETE | 取证命令 G1 见 §1.7，范围 `backend-java/**/src/main/**` → 0 命中 |
| 21 | `AuditLogMapper` 无 `extends` | 取证命令 G2 见 §1.7 → 0 命中 |
| 22 | 仅追加反射测试 | `backend-java/app/src/test/java/com/shoppilot/audit/AuditChannelIntegrationTest.java:214-240` |
| 23 | 测试中 47 处 `DELETE FROM audit_logs`（43 个文件） | `grep -rn "DELETE FROM audit_logs" backend-java/app/src/test/java` → 47 命中 / `grep -rl` → 43 个文件；例：`AuditChannelIntegrationTest.java:94` |
| 24 | 集成测试用 Testcontainers 自建账号，未指定受限账号 | `backend-java/app/src/test/java/com/shoppilot/analysis/AnalysisApiIntegrationTest.java:54-57`、`:74-80` |
| 25 | 审计查询为只读接口，仅 `OWNER` / `ADMIN` | `backend-java/audit/src/main/java/com/shoppilot/audit/api/AuditLogController.java:38-39`、`:61-62` |
| 26 | 验收口径仅要求"应用层无对应入口" | `docs/06-development/ACCEPTANCE_CRITERIA.md:306`、`:345` |
| 27 | 产品口径为"应用层禁止 UPDATE/DELETE" | `docs/00-product/PRD.md:328` |
| 28 | `E-3` 与 `D-05` 被写在同一行 | `docs/01-architecture/MODULE_BOUNDARIES.md:286`、`:565` |
| 29 | `M7-T10` 任务口径与 D-05 原文不一致 | `.workbuddy/M7-任务清单.md:71` |
| 30 | 审计事务策略已由 ADR-004 裁定 | `docs/decisions/ADR-004-AUDIT-WRITE-GRANULARITY.md:1-6`、`:33-38` |
