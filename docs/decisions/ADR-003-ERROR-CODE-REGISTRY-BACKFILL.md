# ADR-003：错误码固化清单（`DESIGN_BASIS §12.3`）M2 / M3 遗留缺口一次性纳入（21 码）

- **状态**：Accepted（P0）
- **日期**：2026-10-17
- **决策人**：项目负责人（项目方授权口径同 `ADR-002`）
- **关联**：`ADR-002`（成员资格规则）、`DESIGN_REVIEW` R26（同类缺口的首个实例）、**R35**（本次登记）、`DESIGN_BASIS §12.3`、`ERROR_CODES.md §2`

---

## 1. 背景

`ADR-002`（2026-10-16）为 `DESIGN_BASIS §12.3` 确立了**可判定的成员资格规则**，其中：

- **规则 2（纳入条件）**：「凡被**已实现代码**或**已发布里程碑验收项**所依赖的错误码，**必须**纳入本节清单；新码一经里程碑实测使用，须在**同一里程碑内**同步纳入。」
- **规则 4（变更门槛）**：「本节清单的增删属 **P0 变更**，须走 ADR 并在 `DESIGN_REVIEW.md` 登记。」

`ADR-002` 当时只处理了 `R26` 暴露的**两个实例**（`AUTH-1005` / `AUTH-1006`），并未执行"按规则 2 做全量核对"这一动作。

**本次（M3-T03/T04/T05 独立复核）首次执行全量机器核对**，结论：缺口远不止 2 个，而是 **21 个**。

## 2. 核对口径（可复跑）

> 该口径同时是**后续每批次收口时应复跑**的核对方法，用于防止同类缺口再次累积。

1. **常量集合**：解析 `ErrorCodes.java` 中全部 `public static final String X = "PREFIX-NNNN"`。
2. **真实依赖**：在 `backend-java/*/src/main/java/**` 中搜索常量名的引用（`\b常量名\b`），**排除**纯映射/定义文件自身（`ErrorCodes.java`、`ErrorCodeHttpStatus.java`）——即只统计**业务代码的真实依赖**，避免把"映射表列全量码"误算成依赖。
3. **清单集合**：解析 `DESIGN_BASIS §12.3` 的**表格行**（形如 `` | `PREFIX-NNNN` | ``）。★ 必须**只解析表格行**：该节说明文字里也会出现码名（如本次的反例 `IMP-4009` / `SAL-6005`），按整节正则抓取会把它们误算进清单（本次核对已实测踩到该坑并修正）。
4. **差集**：`真实依赖 − 清单` = 应当纳入却未纳入的码。

## 3. 核对结果（21 码）

### 3.1 M2 期间即被实现依赖、但从未纳入（14 码）

| 错误码 | 证据（首个依赖文件） |
| --- | --- |
| `AUTH-1010` | `identity/UserStatusService.java` |
| `AUTH-1011` | `identity/JwtAuthenticationFilter.java` |
| `COM-0003` | `common/GlobalExceptionHandler.java` |
| `COM-0007` | `catalog/CatalogPageQuery.java` |
| `TEAM-2004` | `identity/TeamMemberService.java` |
| `TEAM-2005` | `identity/TeamMemberService.java` |
| `TEAM-2006` | `identity/TeamMemberService.java` |
| `TEAM-2007` | `identity/TeamMemberService.java` |
| `TEAM-2009` | `identity/TeamMemberService.java` |
| `CAT-3003` | `catalog/WarehouseQueryService.java` |
| `CAT-3004` | `catalog/CatalogWriteService.java` |
| `CAT-3005` | `catalog/CatalogWriteService.java` |
| `CAT-3009` | `catalog/CatalogWriteService.java` |
| `CAT-3011` | `catalog/CatalogWriteService.java` |

### 3.2 M3-T03/T04/T05 本批新增依赖（7 码）

| 错误码 | 证据（首个依赖文件） |
| --- | --- |
| `IMP-4008` | `importdata/ImportMappingService.java` |
| `IMP-4010` | `importdata/ImportBatchController.java` |
| `IMP-4011` | `importdata/ImportParseService.java` |
| `IMP-4012` | `importdata/ImportParser/ParseFailureException.java` |
| `SAL-6002` | `importdata/ImportValidationService.java` |
| `SAL-6003` | `importdata/ImportValidationService.java` |
| `SAL-6004` | `importdata/ImportValidationService.java` |

### 3.3 核对中**确认不纳入**的码（反例，避免误扩张）

| 错误码 | 不纳入理由 |
| --- | --- |
| `IMP-4009` | 常量已定义，但**主源码无任何依赖点**（`confirm` 阶段 M3-T06 才会用到）→ 待其被依赖时按规则 2 纳入 |
| `SAL-6005` | 同上（跨团队关联校验在销售侧落库时才用到） |

## 4. 决策

**采用方案 B：一次性纳入全部 21 码**（M2 遗留 14 + M3 新增 7）。

1. `DESIGN_BASIS §12.3` 清单**追加 21 行**（表格末尾，按域排序），并在表后注明本次追加依据。
2. `ERROR_CODES.md` 中该 21 码的标记由 `➕ 新增` 改为 `✅ 已固化`（**派生视图对齐**，符合规则 3 方向）。
3. `DESIGN_REVIEW.md` 登记 **R35**（本 ADR 的执行记录 + 根因说明）。
4. **不改变**任何错误码的号码、语义、HTTP 状态；**不改** `§12.2` 号码段划分。

## 5. 备选方案与否决理由

| 方案 | 内容 | 结论 |
| --- | --- | --- |
| A. 只纳入本批新增的 7 码 | 缩小本次变更面 | **否决**：留下 14 个 M2 同类缺口，直接违反规则 2；且下次核对必然再次暴露 |
| **B. 全部纳入 21 码（采纳）** | 按规则 2 一次性补齐 | **采纳**：规则本就是可判定且强制性的；一次做完可消除同类反复 |
| C. 反向放宽规则 2（仅"核心码"纳入） | 让清单保持短小 | **否决**：规则 2 是 `ADR-002` 的核心产物，放宽等于承认"代码依赖与文档清单可以长期不一致"，与 SSOT 定位冲突；清单是索引，长度不是问题 |

## 6. 根因（与 `R26` 的关系）

- `R26` 记录的 `AUTH-1005` / `AUTH-1006` **不是孤例**，而是"从未做过全量核对"这一**流程缺失**的首次显影。
- 因此本 ADR 的产出不只是"加 21 行"，还包括**把核对口径固化**（见 §2），供后续批次收口时复跑。
- **不追溯归因到个人/某次笔误**：`M2-T17`（`ADR-002`）已把规则写清楚，本 ADR 是规则生效后的**首次全量执行**。

## 7. 影响与代价

| 项 | 内容 |
| --- | --- |
| 文档 | `DESIGN_BASIS §12.3` +21 行；`ERROR_CODES.md` 21 处标记；`DESIGN_REVIEW` R35 |
| 代码 | **零**（不改任何 Java 文件、不改任何错误码常量） |
| 迁移 | **零** |
| 风险 | 清单变长（属索引性质，可接受）；后续若再次遗漏，由 §2 口径的复跑兜住 |

## 8. 验证

- 复跑 §2 口径脚本 → `真实依赖 − 清单 = ∅`。
- `ERROR_CODES.md` 中 `✅ 已固化` 集合与 `§12.3` 清单**逐码一致**（规则 1）。
- 反向核对：清单内但主源码未依赖的码（如 `INV-*` / `ANL-*` / `RPL-*` / `PO-*`，属未实现里程碑）**保持不动**。

## 9. 变更记录

| 版本 | 日期 | 说明 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-17 | 首次定稿：全量核对发现 21 码缺口，一次性纳入清单（R35） | 项目负责人 |
