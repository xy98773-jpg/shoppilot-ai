# ADR-006：Spring Boot 版本基线与升级路径评估

- **状态**：Accepted（本 ADR 由项目负责人签发，作为 `M7-T09` 的最终结论，并替代 `TECH_STACK.md §6.2` 的待决策 `D-10`；结论为"维持现有基线"，不涉及代码变更，签发即生效）
- **日期**：2026-10-21
- **决策人**：项目负责人
- **关联**：`TECH_STACK.md §1.2.1/§3.2`、`DEVELOPMENT_PLAN §9.7 风险 3`、`M7-T09`、`DESIGN_REVIEW §5.80`

---

## 1. 背景与事实

### 1.1 仓库当前实际版本

以锁定文件为准（`TECH_STACK.md:30` 明确"若与本文档不一致，以锁定文件为准"）：

| 项 | 实际值 | 证据位置 |
| --- | --- | --- |
| 父工程坐标 | `com.shoppilot:shoppilot-parent:0.1.0`（`pom` 打包） | `backend-java/pom.xml:7-10` |
| 是否继承 `spring-boot-starter-parent` | **否**。使用自定义父 POM，故 `-parameters` 等编译参数需显式开启 | `backend-java/pom.xml:96-103`（注释原文明确"未继承 spring-boot-starter-parent"） |
| Spring Boot 版本 | **`3.5.16`**（`<spring-boot.version>` 属性） | `backend-java/pom.xml:18` |
| Spring Boot BOM | 以 `spring-boot-dependencies:${spring-boot.version}` 方式 `import`，仅管理版本不引入依赖 | `backend-java/pom.xml:51-57` |
| Boot Maven 插件 | 版本跟随 `${spring-boot.version}` | `backend-java/pom.xml:112-115` |
| Java 编译目标 | **21**（`<maven.compiler.release>21</maven.compiler.release>`） | `backend-java/pom.xml:16` |
| Java 运行时基线 | 21 LTS（开发环境最低与推荐均为 21） | `TECH_STACK.md:54`、`TECH_STACK.md:172` |
| 文档基线建议 | `3.5.x`，示例 patch 为 `3.5.15` | `TECH_STACK.md:55`、`TECH_STACK.md:71` |

> 记录一处已存在的文档与实现偏差：`TECH_STACK.md:55`/`:71` 写作"基线建议 `3.5.15`"，而实际锁定为 `3.5.16`。按 `TECH_STACK.md:30` 的"锁定文件优先"原则，本 ADR 以 `3.5.16` 为准，不修改 `TECH_STACK.md`。

### 1.2 官方支持窗口（外部事实）

Spring 官方支持表（访问日期 2026-10-21，https://spring.io/projects/spring-boot#support ）：

| 分支 | 首发 | OSS 支持结束 | 企业支持结束 |
| --- | --- | --- | --- |
| 4.2.x | 2026-11（计划） | 2027-12 | 2028-12 |
| 4.1.x | 2026-06 | 2027-07 | 2028-07 |
| 4.0.x | 2025-11 | 2026-12 | 2027-12 |
| **3.5.x** | 2025-05 | **2026-06（已结束；精确日期 2026-06-30）** | 2032-06 |
| 3.4.x | 2024-11 | 2025-12（已结束） | 2026-12 |
| 3.3.x | 2024-05 | 2025-06（已结束） | 2026-06 |

- 上表月份取自 https://spring.io/projects/spring-boot#support ；`3.5.x` 的日级日期为 **2026-06-30**，见 https://endoflife.date/spring-boot （访问日期 2026-10-21，该页标注 `3.5` 分支 "Ended 30 Jun 2026"）。
- 支持政策口径：次要版本 OSS 支持**至少 13 个月**；每个主代的**最后一个次要版本**额外获得 5 年企业支持（`3.5` 即 3.x 的最后一个次要版本），见 https://spring.io/support-policy （访问日期 2026-10-21）。
- `3.5` 分支的最后一个发行版为 **3.5.16（2026-06-25）**，此后不再有 OSS 补丁，见 https://versionlog.com/spring-boot/3.5/ 与 https://endoflife.date/spring-boot （访问日期 2026-10-21）。
- 当前 GA 主线：**4.1.x，最新 patch 为 4.1.1（2026-08-20 发布）**；4.0.x 最新为 4.0.8（同日发布）；4.2.0 尚未 GA（计划 2026-11）。见 https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/ 、https://spring.io/blog/2026/08/20/spring-boot-4-0-8-available-now/ 、https://spring.io/projects/spring-boot#support （访问日期 2026-10-21）。
- **4.x 最低 Java 要求：Java 17 或更高**，官方原文 "Spring Boot 4.0 requires Java 17 or later. Using the latest LTS release of Java is encouraged."，见 https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide （访问日期 2026-10-21）。即本项目 Java 21 **满足** 4.x 基线，升级不需要更换 JDK；截至访问日未检索到 4.1 提高该基线的官方声明。

结论性事实：**3.5.x 的免费安全补丁窗口已于 2026-06-30 关闭，距本 ADR 日期已逾 3 个月；4.0 的 OSS 窗口也仅剩至 2026-12-31，真正具备迁移价值的目标是 4.1.x。**

### 1.3 Spring Boot 4.x 的破坏性变更（官方口径）

| # | 变更 | 官方原文 | 来源 |
| --- | --- | --- | --- |
| 1 | 平台基线抬升 | "Spring Boot 4 is based on Jakarta EE 11 and requires a Servlet 6.1 baseline."；"In particular, you must use Spring Framework 7.x." | https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide |
| 2 | 代码库模块化、starter 重排 | "Spring Boot 4.0 has a new modular design and now ships smaller focused modules rather than several large jars. As a result, this release has a more consistent set of 'starter' POMs, and features that only relied on a third-party dependency to work can require an additional starter." | 同上 |
| 3 | 移除 Undertow 支持 | "Spring Boot 4 requires a Servlet 6.1 baseline, with which Undertow is not yet compatible. As a result, Undertow support is dropped, including the Undertow starter and the ability to use Undertow as an embedded server." | 同上 |
| 4 | 3.x 弃用项全部移除 | "Classes, methods and properties that were deprecated in Spring Boot 3.x have been removed in this release." | 同上 |
| 5 | 配置属性重命名/移除 | "Configuration properties were renamed/removed and developers need to update their `application.properties`/`application.yml` accordingly."（官方提供 `spring-boot-properties-migrator` 辅助） | 同上 |
| 6 | 自动配置类不再有公开成员 | "Public members (aside from constants) have been removed from auto-configuration classes."（自定义 starter / 直接引用自动配置类的代码会编译失败） | https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes |
| 7 | Jackson 3 | 依赖升级清单列有 "Jackson 3.0"，并注明 "Jackson 2 support ships in a deprecated form." | 同上 |

> 以上 7 条均为官方 Release Notes / Migration Guide 原文，未作推断性扩写。JUnit 代际变化在 4.0 Release Notes 中未见明确条目，**未获取到权威表述**，故不列入。

### 1.4 本项目的耦合面（决定迁移成本的关键）

| 耦合点 | 现状 | 对 4.x 的影响 |
| --- | --- | --- |
| Spring Security | 6.5.x，随 Boot 3.5 管理 | 4.x 管理 Security 7，过滤器链与 `AuthorizationManager` 用法需复核（`TECH_STACK.md:56`） |
| MyBatis-Plus | 3.5.x，明确要求 `mybatis-plus-spring-boot3-starter` | starter 名称即绑定 Boot 3；**截至访问日未获取到 MyBatis-Plus 对 Boot 4 的官方兼容声明**，属升级前置未知项（`TECH_STACK.md:60`、`TECH_STACK.md:77`） |
| Jackson 2 | 承载全部 API 的 JSON 序列化 | Boot 4 升 Jackson 3，包名与 `ObjectMapper` 定制点变更（见 §1.3 第 7 条） |
| 手工锁版依赖 | `jjwt 0.12.6`、`poi 5.3.0`、`commons-compress 1.26.2` | Boot BOM 均不管理，升级不改变锁版策略，但需全量回归（`backend-java/pom.xml:20-31`） |
| 测试基线 | 全量 `verify 843/0/0/0`；端到端 132 断言 / 0 失败 | 该基线是升级的安全网，但其断言面向 3.5 行为语义，框架语义变更后需重建期望值（`DESIGN_REVIEW.md:5165`、`DESIGN_REVIEW.md:5170-5173`） |

### 1.5 当前项目阶段与遗留风险口径

- M1–M6 已完成，M7 处于收口阶段；`DESIGN_REVIEW §5.80` 记录了 M7-T01 端到端 20 步脚本通过（132 断言 / 0 失败），并新登 `R188`（`DESIGN_REVIEW.md:5170`、`DESIGN_REVIEW.md:5202`）。
- 遗留清单中 **`R155` 仍为 P1 未闭环**（Python 侧 LLM 模块与降级链路未实现，仅 Java 二次校验兜底），见 `DESIGN_REVIEW.md:669`、`DESIGN_REVIEW.md:5165`；`R186` 已在 M7-T00 闭环（`DESIGN_REVIEW.md:5165`）。
- 该口径说明：收口期的人力与验证资源已被 P1 遗留占用，任何跨代框架迁移都会与既有收口任务直接竞争同一资源池。

---

## 2. 选项

| 选项 | 内容 | 工作量 | 风险 | 触发条件 |
| --- | --- | --- | --- | --- |
| **A 维持 3.5.x** | 冻结 `3.5.16`，M7 内不改动 `spring-boot.version`；生产化以"商业支持（3.5 企业支持至 2032-06）"或"后续版本周期升级"作为闸门 | 零代码改动；新增商业支持采购与合规评估（跨部门流程） | 中—高：无 OSS 安全补丁，CVE 响应完全依赖商业支持或临时规避；与 4.x 差距随时间单调扩大 | 见 §4.2 的 T1–T5 |
| **B 升级 4.x** | 直接迁移至 **4.1.x**（跳过 4.0：其 OSS 支持仅至 2026-12-31） | 11 个模块全量迁移：Jackson 3、Security 7、starter 与自动配置重构、配置属性迁移、全量回归与端到端重建 | 高：`MyBatis-Plus` 对 Boot 4 的兼容性未获权威确认；`132` 条端到端断言的期望值需重建；收口期引入大范围回归 | 决定在 M7 交付 4.x 基线（本项目当前不满足） |
| C 分阶段 | M7 冻结 `3.5.16`，M8 独立窗口升级至 4.1（或 4.2） | 拆为兼容性 spike + 全量迁移两段 | 中：迁移窗口后移，差距扩大；M8 是否立项尚无依据 | 存在已立项的 M8 迭代窗口 |
| （否决）升级 4.0.x | 迁至 4.0 线 | 同 B | 高：OSS 仅至 2026-12-31，升级后不足三个月再次迁移 | 不具备合理性 |

---

## 3. 决策

**采纳选项 A：维持 Spring Boot `3.5.16`，M7 内不升级；生产化以商业支持或经批准的后续升级为前置闸门。**

### 3.1 理由

1. **迁移目标不成立为"一次到位"**。3.5.x 的 OSS 已于 2026-06-30 结束（https://endoflife.date/spring-boot ），4.0 的 OSS 又仅至 2026-12-31，唯一合理目标是 4.1.x；而 4.2.0 计划于 2026-11 GA（https://spring.io/projects/spring-boot#support ），现时点升级到 4.1 后仍需在短期内二次迁移，收益被摊薄。
2. **破坏面覆盖项目全部关键层**。4.x 要求 Jakarta EE 11 / Servlet 6.1 与 Spring Framework 7（Migration Guide），同时引入模块化 starter、Jackson 3、自动配置公开成员移除（Release Notes）。本项目的 JSON、安全、持久化三层均在其中，属"全栈语义变更"，不是补丁级升级。
3. **关键依赖存在未知项**。`TECH_STACK.md:60` 明确使用 `mybatis-plus-spring-boot3-starter`；截至访问日**未获取到 MyBatis-Plus 对 Boot 4 的官方兼容声明**（未获取到权威来源）。在该未知项消除前，选项 B 的工程量与风险无法收敛。
4. **M7 的资源已被 P1 遗留占用**。`R155` 仍为 P1 未闭环（`DESIGN_REVIEW.md:669`、`DESIGN_REVIEW.md:5165`），收口期不宜并行跨代迁移。
5. **存在合法的长期支持路径**。3.5 是 3.x 最后一个次要版本，享有额外 5 年企业支持至 **2032-06**（https://spring.io/support-policy 、https://spring.io/projects/spring-boot#support ），使"维持"不等于"永久失修"。
6. **当前无生产流量暴露**。项目尚处 M7 收口、未对外交付，短期不存在"运行中系统无补丁可用"的现实缺口；这为商业支持谈判或后续升级留出了时间窗。

### 3.2 选择 A 的代价（明示，不作淡化）

- **无免费安全补丁**：3.5.16 是 3.5 线最后一个 OSS 发行版（https://versionlog.com/spring-boot/3.5/ ），此后 3.5 的修复仅通过企业支持渠道提供。
- **自建补丁不可行**：本项目为小团队、单机 Compose 部署，不具备 fork Spring Boot 并持续回移补丁的维护能力。因此 A 的可持续形态**只有"商业支持"一种**；若商业支持未获批，A 退化为"带风险接受"的临时状态，必须由 §4.2 的 T1 强制转为升级。
- **差距单调扩大**：3.5 与 4.1 之间已跨越 Spring Framework 7、Jakarta EE 11、Jackson 3 三代基线；迁移成本随业务代码增量上升。
- **风险必须登记**：本 ADR 不新造风险编号；建议在 `DESIGN_REVIEW.md` 按既有 `R` 编号体系登记"3.5.16 无 OSS 补丁，依赖商业支持"的残余风险（编号以实际登记为准），并纳入 `DEVELOPMENT_PLAN §14` 风险 4 的跟踪口径（`DEVELOPMENT_PLAN.md:887`）。

### 3.3 不选 B / C 的原因

- **不选 B**：迁移的技术前提（MyBatis-Plus Boot 4 兼容性）未证实，且与 M7 收口、`R155` 闭环直接争抢资源；在验收期引入全栈回归，风险收益比不成立。
- **不选 C**：其成立前提是"M8 已立项并预留独立升级窗口"，当前无此依据。将决策挂在一个不存在的里程碑上，等同于"待定"，不符合 ADR 的确定性要求；故 C 的实质内容被吸收为 A 的触发条件与升级预案（§4.3），而非独立选项。

---

## 4. 后果与触发条件

### 4.1 立即后果

1. `backend-java/pom.xml:18` 的 `3.5.16` **冻结**；M7 内任何改动该属性的 PR 必须引用本 ADR 并说明理由。
2. `TECH_STACK.md §3.2` 的强制评审项①（"M7 前必须完成升级或商业支持决策"）由本 ADR 关闭（`TECH_STACK.md:162`）；`TECH_STACK.md:219` 的 `D-10` 由本 ADR 结论替代。
3. `DEVELOPMENT_PLAN §9.7 风险 3`（`DEVELOPMENT_PLAN.md:701`）与 `§14 风险 4`（`DEVELOPMENT_PLAN.md:887`）的缓解措施更新为"已决策维持，按 §4.2 触发复评"，风险等级由"高"调整为"中（有商业支持路径）"，直至 T1 触发。
4. 文档偏差保留：`TECH_STACK.md:55` 的 `3.5.15` 示例不再代表实际基线，以 `pom.xml` 的 `3.5.16` 为准；本 ADR 不修改该文件。
5. 本 ADR **不产生任何代码、依赖或配置变更**。

### 4.2 重启评估的触发条件（任一命中即重启，不必等待里程碑）

| 编号 | 触发信号 | 判定标准 | 处置 |
| --- | --- | --- | --- |
| **T1** | 首次生产上线 / 对外交付 | 上线评审会立项，且最迟在上线前一个迭代启动 | 先决条件二选一：完成商业支持采购，或完成 4.1.x 迁移；二者均未达成则**阻断上线** |
| **T2** | 3.5 线出现高危漏洞 | CVSS ≥ 7.0 且无官方 3.5 补丁（含企业渠道） | 立即启动应急评估；无企业支持则升级为强制迁移 |
| **T3** | 日历节点 | 2026-12-31（4.0 OSS 结束）或 2027-07-31（4.1 OSS 结束） | 在节点前完成一次正式复评并记录结论 |
| **T4** | 依赖障碍消除 | MyBatis-Plus 发布 Boot 4 官方 starter 或兼容声明 | 启动兼容性 spike，重估选项 B |
| **T5** | 商业支持未获批 | 预算/采购在 T1 前未完成审批 | A 失去可持续形态，自动转为"必须升级" |

### 4.3 商业支持预案（A 的支撑条件）

- 采购对象：Tanzu Spring 企业支持（3.5 企业支持窗口至 **2032-06**，https://spring.io/projects/spring-boot#support ）。
- 交付要求：明确 3.5 线的补丁 SLA、CVE 响应时限、可获得的补丁构件来源，并与 `TECH_STACK.md §3.2` 的"安全补丁即时评估"条款对齐（`TECH_STACK.md:159`）。
- 若采购未成：按 T5 处理，不得以"暂无漏洞"为由延期。

### 4.4 升级预案（T1/T4 触发时执行，供后续 ADR 细化）

1. **前置**：先升至 3.5 线最新可用版本（当前即 `3.5.16`），开启并清空全部弃用告警（官方 Migration Guide 要求 "make sure to upgrade to the latest available `3.5.x` version"）。
2. **兼容性验证**：以 spike 验证 MyBatis-Plus、Spring Security 7、Jackson 3 三项；三项任一不通过则暂停并回到 A。
3. **迁移顺序**：`common` → `identity` → 业务模块 → `app`；每步以模块级测试通过为门禁，禁止一次性全量切换。
4. **配置迁移**：引入 `spring-boot-properties-migrator` 生成属性迁移报告，迁移完成后按官方要求移除该模块。
5. **回归**：全量 `verify`（当前基线 843 用例）+ 端到端 20 步脚本（当前基线 132 断言，`DESIGN_REVIEW.md:5170-5173`）；断言期望值需按 4.x 语义重校，不得直接复用。
6. **回滚**：以 Git tag 固化 `3.5.16` 基线；升级分支独立开发，回滚即回退 tag 并恢复 `spring-boot.version`，数据库与业务契约不变（本次升级不含 schema 变更）。

---

## 5. 证据来源

### 5.1 外部来源（访问日期均为 2026-10-21）

| # | 内容 | URL |
| --- | --- | --- |
| 1 | Spring Boot 官方支持表（3.5/4.0/4.1/4.2 的 OSS 与企业支持月份） | https://spring.io/projects/spring-boot#support |
| 2 | Spring 支持政策（次要版本至少 13 个月；主代最后次要版本额外 5 年企业支持） | https://spring.io/support-policy |
| 3 | 3.5 分支支持窗口（OSS 结束 2026-06-30；最新 3.5.16 于 2026-06-25） | https://endoflife.date/spring-boot |
| 4 | 3.5 分支发行历史与支持生命周期（3.5.16 为最新） | https://versionlog.com/spring-boot/3.5/ |
| 5 | 4.1.1 发布公告（2026-08-20） | https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/ |
| 6 | 4.0.8 发布公告（2026-08-20） | https://spring.io/blog/2026/08/20/spring-boot-4-0-8-available-now/ |
| 7 | 4.0 迁移指南（Java 17+、Jakarta EE 11 / Servlet 6.1、Spring Framework 7、模块化、Undertow 移除、弃用项移除、配置属性迁移） | https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide |
| 8 | 4.0 Release Notes（自动配置公开成员移除、Jackson 3.0、Jackson 2 以弃用形式提供） | https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes |

### 5.2 仓库来源

| # | 事实 | 位置 |
| --- | --- | --- |
| 1 | 父工程坐标与打包方式 | `backend-java/pom.xml:7-10` |
| 2 | Java 编译目标 21 | `backend-java/pom.xml:16` |
| 3 | Spring Boot 版本 `3.5.16` | `backend-java/pom.xml:18` |
| 4 | 手工锁版依赖（jjwt / POI / commons-compress） | `backend-java/pom.xml:20-31` |
| 5 | Boot BOM 以 `import` 方式引入 | `backend-java/pom.xml:51-57` |
| 6 | 未继承 `spring-boot-starter-parent` | `backend-java/pom.xml:96-103` |
| 7 | Boot Maven 插件版本跟随属性 | `backend-java/pom.xml:112-115` |
| 8 | Java 21 LTS 基线 | `TECH_STACK.md:54` |
| 9 | Spring Boot 基线建议 `3.5.x` / 示例 `3.5.15` | `TECH_STACK.md:55`、`TECH_STACK.md:71` |
| 10 | 3.3/3.4 OSS 已结束的论证 | `TECH_STACK.md:78` |
| 11 | 暂不选 4.x 的理由 | `TECH_STACK.md:79` |
| 12 | 3.5 OSS 已于 2026-06-30 结束、M7 前评估 | `TECH_STACK.md:80` |
| 13 | 基线结论段 | `TECH_STACK.md:82` |
| 14 | 强制评审项①（Boot 3.5 OSS 窗口） | `TECH_STACK.md:162` |
| 15 | 待决策 `D-10`（维持 3.5.x / 直上 4.1.x） | `TECH_STACK.md:219` |
| 16 | M1 风险表第 1 条（OSS 支持窗口问题） | `DEVELOPMENT_PLAN.md:211` |
| 17 | `M7-T09` 任务定义（输出"评估结论（ADR 或修订）"） | `DEVELOPMENT_PLAN.md:721` |
| 18 | §9.7 风险 3（3.5.x OSS 已结束的安全债） | `DEVELOPMENT_PLAN.md:701` |
| 19 | §14 风险 4（安全补丁缺失，概率"高"） | `DEVELOPMENT_PLAN.md:887` |
| 20 | `R155` P1 未闭环 | `DESIGN_REVIEW.md:669`、`DESIGN_REVIEW.md:5165` |
| 21 | §5.80 M7-T01 端到端 132 断言 / 0 失败 | `DESIGN_REVIEW.md:5170-5173` |
| 22 | 全量 `verify 843/0/0/0` | `DESIGN_REVIEW.md:5165` |
| 23 | `R188` 新登（§5.80.3） | `DESIGN_REVIEW.md:5202` |

---

## 6. 变更记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-21 | 首版：核实仓库实际版本与官方支持窗口，给出"维持 3.5.16 + 触发式复评"的明确结论，替代待决策 `D-10` | 项目负责人 |
