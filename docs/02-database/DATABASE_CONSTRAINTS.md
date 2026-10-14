# ShopPilot AI 数据库约束、索引与并发控制设计

> **文档编号**：`docs/02-database/DATABASE_CONSTRAINTS.md`
> **文档地位**：约束、索引、幂等与并发控制的**权威清单**，服从 `DESIGN_BASIS.md`（SSOT）与 `DATABASE_DESIGN.md`。
> 三份文档的关系：`DATABASE_DESIGN.md`（表与字段设计）→ `DATA_DICTIONARY.md`（字段速查）→ **本文件（约束与索引落地）**。
> **阶段**：M0 设计阶段，仅设计文档。文中 SQL 片段**仅为说明性示例，未在 MySQL 中执行验证**。
> **版本前提**：`CHECK` 约束生效需要 **MySQL 8.0.16+**（`DESIGN_BASIS §4.1`）。

---

## 1. 约束设计总览

### 1.1 三类约束的职责划分

| 类别 | 职责 | 典型约束 | 数量级 |
| --- | --- | --- | --- |
| **数据库层（DB）** | 保证**绝对不可能违反**的不变量；防并发写入竞态与程序 bug | 主键、唯一约束、外键、`CHECK`、`NOT NULL` | 高（每次写入都校验） |
| **应用层（APP）** | 表达**需要读数据才能判断**或**涉及权限/跨表**的规则；提供**用户可读的错误码** | 状态机合法转换、同团队关联校验、超额收货校验、权限判定、`received_qty` 累加 | 中 |
| **两者结合（DB + APP）** | 关键业务不变量，**应用层给友好错误、数据库兜底** | 幂等键（APP 生成 + DB 唯一）、非负库存（APP 先校验 + DB `CHECK`）、`qty_after = qty_before + qty_delta` | 中 |

### 1.2 划分原则（四条）

| # | 原则 | 说明 | 反例（禁止） |
| --- | --- | --- | --- |
| **C1** | **能由单行数据判定的规则 → 放进 DB** | 枚举取值、数量非负、`qty_after = qty_before + qty_delta`、唯一编码 | 把"状态必须合法"只写在 Java 里 |
| **C2** | **需要跨表/跨行读取才能判定的规则 → 放应用层**（可选加 DB 兜底） | 同团队校验、超额收货、`Σ received ≤ ordered` 的跨单据汇总 | 写触发器去查另一张表（触发器难调试、难测试、跨模块耦合） |
| **C3** | **权限与角色判定 → 只在应用层** | 数据库不知道"谁在调用"（连接池复用连接） | 用 DB 视图/`DEFINER` 表达权限（与 `TeamContext` 设计冲突） |
| **C4** | **用户可见错误码必须来自应用层** | 唯一约束冲突需要被翻译成 `CAT-3002` 而不是暴露 MySQL 错误 1062 | 直接把 `DuplicateKeyException` 抛给前端 |

### 1.3 三个"不"

| 不 | 理由 |
| --- | --- |
| **不使用触发器**（`TRIGGER`） | ① 业务逻辑散落在数据库，无法用 JUnit 测试，与 `§4.2` 分层相悖；② 审计需要"谁操作"，触发器拿不到 `TeamContext`；③ 迁移与回滚复杂度激增 |
| **不使用存储过程**（`PROCEDURE`/`FUNCTION`） | 同上；且与 `common` 模块"只能依赖框架"的约束冲突（业务逻辑必须在 Java） |
| **不使用数据库级行级安全（RLS）** | MySQL 8 无原生 RLS（需视图 + `DEFINER` 或企业版），而团队隔离已由"唯一约束 + 复合索引 + 服务层校验 + 租户插件"四层保证（`DESIGN_BASIS §6`） |

---

## 2. 唯一约束与幂等键设计

### 2.1 全部唯一约束清单（24 条）

> 「PK 类型」：`自然唯一`（业务键本身唯一）/ `防重`（防止重复写入）/ `幂等`（吸收重复请求）/ `团队隔离`（带 `team_id` 前缀）。
> 「冲突错误码」指**应用层捕获唯一键冲突后**返回的稳定错误码（`DESIGN_BASIS §12`）。

| # | 约束名 | 表 | 字段（顺序） | 类型 | 业务含义 | 冲突错误码 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `uk_users_username` | `users` | `username` | 自然唯一 | 登录名全局唯一 | `COM-0001`（⚠️ 建议新增 `AUTH-1004`，`D-14`） | `§8`；**无 `team_id`**（`§6` 规则 1 例外） |
| 2 | `uk_users_email` | `users` | `email` | 自然唯一 | 邮箱全局唯一 | `COM-0001`（⚠️ 建议新增 `AUTH-1005`，`D-14`） | 可空列 → 允许多行 `NULL` |
| 3 | `uk_teams_code` | `teams` | `code` | 自然唯一 | 团队编码全局唯一 | `COM-0001`（⚠️ 建议新增 `TEAM-2004`，`D-14`） | `teams` 自身即团队 |
| 4 | `uk_tm_team_user` | `team_members` | `team_id`, `user_id` | 防重 + 团队隔离 | 一个用户在一个团队只有一行 | `COM-0001`（⚠️ 建议新增 `TEAM-2005`，`D-14`） | 重新加入**复活同一行** |
| 5 | `uk_wh_team_code` | `warehouses` | `team_id`, `code` | 团队隔离 | 团队内仓库编码唯一 | `CAT-3002` | `§6` 规则 2 |
| 6 | `uk_sup_team_code` | `suppliers` | `team_id`, `code` | 团队隔离 | 团队内供应商编码唯一 | `CAT-3002` | 同上 |
| 7 | `uk_prod_team_code` | `products` | `team_id`, `product_code` | 团队隔离 | 团队内商品编码唯一 | `CAT-3002` | 同上 |
| 8 | `uk_sku_team_code` | `skus` | `team_id`, `sku_code` | 团队隔离 | 团队内 SKU 编码唯一；**跨团队可重复** | `CAT-3002` | 同上 |
| 9 | `uk_pol_team_sku_wh` | `replenishment_policies` | `team_id`, `sku_id`, `warehouse_id` | 防重 + 团队隔离 | 一 SKU 一仓库至多一条策略 | **不报错**（upsert 吸收） | 保存走 `INSERT ... ON DUPLICATE KEY UPDATE` |
| 10 | `uk_invbal_team_wh_sku` | `inventory_balances` | `team_id`, `warehouse_id`, `sku_id` | 防重 + 团队隔离 | 一 SKU 一仓库一行余额 | **不报错**（并发首建吸收） | 冲突后改为读取 + 走乐观锁路径 |
| 11 | `uk_invtxn_team_idem` | `inventory_transactions` | `team_id`, `idempotency_key` | **幂等** + 团队隔离 | 同一团队同一幂等键只产生一条流水 | `INV-5003`（内容不一致）/ 成功返回原流水（内容一致） | `§8`；5 类幂等之二 |
| 12 | `uk_invtxn_team_reversed` ⚠️ | `inventory_transactions` | `team_id`, `reversed_txn_id` | 防重 + 团队隔离 | **一笔流水只能被冲销一次** | `INV-5003` | ⚠️ **`§8` 未列出，本设计建议新增**（`D-13`）；可空列 → 允许多行 `NULL` |
| 13 | `uk_sales_team_wh_sku_date` | `sales_daily` | `team_id`, `warehouse_id`, `sku_id`, `sale_date` | 防重 + 团队隔离 | 日粒度唯一（**upsert 落点**） | **不报错**（upsert 覆盖） | `§6-D.2` |
| 14 | `uk_imp_team_hash_biz` | `import_batches` | `team_id`, `dedupe_hash`, `biz_type` | **幂等** + 团队隔离 | 同团队同内容同类型的"有效批次"唯一 | `IMP-4004` | ⚠️ 物理列用可空槽位 `dedupe_hash`，见 `INC-1`/`D-09`；5 类幂等之一 |
| 15 | `uk_run_team_no` | `analysis_runs` | `team_id`, `run_no` | 自然唯一 + 团队隔离 | 团队内运行编号唯一 | `COM-0002` | 编号冲突时重新生成（`D-16`） |
| 16 | `uk_run_team_idem` | `analysis_runs` | `team_id`, `idempotency_key` | **幂等** + 团队隔离 | 同一团队同一幂等键只创建一次运行 | `COM-0009` | 可空 → 多行 `NULL` 允许；5 类幂等之三 |
| 17 | `uk_ai_run_sku_wh` | `analysis_items` | `run_id`, `sku_id`, `warehouse_id` | 防重 | 一次运行内每 SKU×仓库一条快照 | `COM-0002`（内部缺陷，不应发生） | `§8`；**未带 `team_id`**（`INC-2`/`D-10`） |
| 18 | `uk_repl_run_sku_wh` | `replenishment_items` | `run_id`, `sku_id`, `warehouse_id` | 防重 | 一次运行对每 SKU×仓库至多一条建议 | `COM-0002`（内部缺陷） | 同上；新 run 不冲突（旧建议置 `SUPERSEDED`） |
| 19 | `uk_po_team_no` | `purchase_orders` | `team_id`, `po_no` | 自然唯一 + 团队隔离 | 团队内采购单号唯一 | `COM-0002` | `D-16` |
| 20 | `uk_po_team_idem` | `purchase_orders` | `team_id`, `idempotency_key` | **幂等** + 团队隔离 | 同一团队同一幂等键只创建一张采购单 | `COM-0009` | 可空；5 类幂等之四 |
| 21 | `uk_poi_po_lino` | `purchase_order_items` | `purchase_order_id`, `line_no` | 防重 | 采购单内行号唯一 | `COM-0002` | `§8` |
| 22 | `uk_gr_team_no` | `goods_receipts` | `team_id`, `receipt_no` | 自然唯一 + 团队隔离 | 团队内到货单号唯一 | `COM-0002` | `D-16` |
| 23 | `uk_gr_team_idem` | `goods_receipts` | `team_id`, `idempotency_key` | **幂等** + 团队隔离 | 同一团队同一幂等键只创建一张到货单 | `COM-0009` | **`NOT NULL`**（收货不可逆）；5 类幂等之五 |
| 24 | `uk_gri_receipt_poi` | `goods_receipt_items` | `goods_receipt_id`, `purchase_order_item_id` | 防重 | 一张到货单内同一条采购明细只有一行 | `COM-0002` | 防止同单重复入库 |

**团队隔离合规检查**：24 条中，含 `team_id` 前缀的 **19 条**；不含的 5 条为 `uk_users_username`、`uk_users_email`、`uk_teams_code`（`§6` 规则 1 显式例外表）与 `uk_ai_run_sku_wh`、`uk_repl_run_sku_wh`（由 `run_id` 派生团队维度，`INC-2`/`D-10`）。`uk_poi_po_lino` 与 `uk_gri_receipt_poi` 由**同团队的父表**派生团队维度（父表已带 `team_id` 唯一约束）。

### 2.2 五类幂等设计（专章）

| # | 幂等类型 | 幂等键 | 唯一约束 | 冲突时行为 | 错误码 |
| --- | --- | --- | --- | --- | --- |
| 1 | **导入幂等** | `(team_id, SHA-256(文件内容), biz_type)` | `uk_imp_team_hash_biz` | 返回已存在批次 ID；允许 `FORCE_REIMPORT` | `IMP-4004` |
| 2 | **库存流水幂等** | `(team_id, idempotency_key)`，键 = `<txn_type>:<biz_type>:<biz_ref_id>:<sku_id>` | `uk_invtxn_team_idem` | 内容一致 → 返回原流水（成功）；内容不一致 → 报错 | `INV-5003` |
| 3 | **分析任务幂等** | `(team_id, idempotency_key)` + **状态守卫**（`§9.7`） | `uk_run_team_idem` + `UPDATE ... WHERE status = 'PENDING'` | 同键同体 → 返回原 run；同键异体 → 报错；范围重叠且有运行中任务 → 报错 | `COM-0009` / `ANL-7001` |
| 4 | **创建采购单幂等** | `(team_id, idempotency_key)` + **建议状态守卫**（`APPROVED → CONVERTED`） | `uk_po_team_idem` + 条件 UPDATE | 同键同体 → 返回原采购单；建议已 `CONVERTED` → 报错 | `COM-0009` / `PO-9001` |
| 5 | **到货单幂等** | `(team_id, idempotency_key)`（**`NOT NULL`**）+ `CONFIRMED` 状态守卫 | `uk_gr_team_idem` | 同键同体 → 返回原到货单（含已确认结果）；重复确认且已 `CONFIRMED` → 返回成功（幂等） | `COM-0009` |

#### 幂等 1：导入幂等（`file_hash`）

| 项 | 规定 |
| --- | --- |
| 幂等依据 | **文件内容的 SHA-256**（流式计算），`CHAR(64)` 十六进制小写 |
| 幂等维度 | `team_id` + `file_hash` + `biz_type` |
| 槽位列 | `dedupe_hash`：**占用** = `file_hash`；**释放** = `NULL` |
| 释放时机 | 状态进入 `DUPLICATE_REJECTED` / `FAILED` / `CANCELLED`，或被 `superseded_by_batch_id` 取代 |
| 重复检测 | 由**唯一约束**在 INSERT 时兜底（无需先 SELECT，天然防并发双提交） |
| 命中行为 | ① INSERT 一条 `status = 'DUPLICATE_REJECTED'`（`dedupe_hash = NULL`）记录，`duplicate_of_batch_id` 指向原批次；② 返回 `IMP-4004`（HTTP 409）+ 已存在批次信息 |
| 强制重导 | ① INSERT 新批次（`dedupe_hash = NULL`，`status = 'UPLOADED'`，`attempt_no + 1`）得 `new_id`；② `UPDATE 旧 SET dedupe_hash = NULL, superseded_by_batch_id = new_id`；③ `UPDATE 新 SET dedupe_hash = file_hash`。**三步同一事务** |
| **不以文件名为依据** | 见 `DATABASE_DESIGN.md §6-E.5`（文件名不可信、可伪造、同名不同内容必须允许、同内容不同名必须拦截） |

#### 幂等 2：库存流水幂等（`idempotency_key`）

| 项 | 规定 |
| --- | --- |
| 键格式 | `<txn_type>:<biz_type>:<biz_ref_id>:<sku_id>[:<suffix>]` |
| 示例 | `PURCHASE_INBOUND:GOODS_RECEIPT:1024:5501`（到货单 1024 的 SKU 5501 入库） |
| 盘点/手工调整 | 追加 `<operator_id>:<yyyyMMddHHmmss>` 后缀（人工操作每次都是一次新事件） |
| **禁止** | 使用 `UUID` / 随机数作幂等键——那样永不冲突，等于没有幂等 |
| 冲突判定 | 命中唯一约束后**比较请求指纹**（`txn_type` + `qty_delta` + `biz_ref_id`）：一致 → 返回原流水（HTTP 200）；不一致 → `INV-5003`（HTTP 409） |
| 与业务单据的关系 | 同一到货单同一 SKU **只能**产生一条 `PURCHASE_INBOUND` 流水；冲销用 `REVERSAL:<receipt_id>:<sku_id>` 也是幂等的 |

#### 幂等 3：分析任务幂等（业务键 + 状态守卫）

| 层 | 手段 |
| --- | --- |
| L1 请求级 | `uk_run_team_idem`：同 `idempotency_key` 只创建一次（返回原 `run_id`，HTTP 202） |
| L2 业务级 | **范围重叠检测**：若存在 `status IN ('PENDING','RUNNING')` 的运行，返回 `ANL-7001`。首期简化为"**每团队同时只允许 1 个进行中的运行**"（避免昂贵的集合交集计算） |
| L3 状态级 | **状态守卫**：`UPDATE analysis_runs SET status = 'RUNNING' WHERE id = ? AND status = 'PENDING'`，影响行数 = 0 → 状态已被他人推进 → `ANL-*` 或幂等返回 |
| L4 落库级 | `uk_ai_run_sku_wh` 防重复快照；落库前检查"该 run 是否已有 `analysis_items`"，有则跳过（幂等） |

#### 幂等 4：创建采购单幂等（建议状态守卫 + `idempotency_key`）

```
① 校验建议状态 = 'APPROVED'                    否则 → RPL-8001
② INSERT purchase_orders（带 idempotency_key）  撞 uk_po_team_idem → 比较指纹 → 返回原采购单 / COM-0009
③ INSERT purchase_order_items（line_no 递增）   撞 uk_poi_po_lino → 内部缺陷 → COM-0002
④ UPDATE replenishment_items
      SET status = 'CONVERTED', converted_at = ?, converted_po_item_ids = ?
      WHERE id = ? AND status = 'APPROVED' AND version = ?
   影响行数 = 0 → 说明已被并发转为采购 → 整个事务回滚 → PO-9001
⑤ INSERT audit_logs（REPLENISHMENT_CONVERT）
```

- **④ 是幂等的核心**：用"建议状态守卫 + `version`"把"一条建议只能被转一次"变成**数据库层可判定**的事实，无需分布式锁。
- 支持 §6-G 的场景 ①②③（多建议合并、一建议分批）；分批场景下第 2 次转采购时建议已是 `CONVERTED`，因此**不走本流程**，而是直接在已有采购单上新增明细（业务允许，见 §6-G.2）。

#### 幂等 5：到货单幂等（`idempotency_key` + `CONFIRMED` 守卫）

| 场景 | 行为 |
| --- | --- |
| 同一 `X-Idempotency-Key` 重复**创建**到货单 | `uk_gr_team_idem` 吸收 → 返回原到货单（HTTP 200） |
| 同一 `X-Idempotency-Key` 但**内容不同** | `COM-0009`（HTTP 409） |
| 已 `CONFIRMED` 的到货单再次收到"确认"请求 | `UPDATE ... WHERE id = ? AND status = 'DRAFT'` 影响行数 = 0 → **返回已确认结果**（幂等成功），不报错、不重复入库 |
| 客户端未传 `X-Idempotency-Key` | 服务端按**内容指纹**生成：`SHA-256(po_id + receipt_date + Σ(poi_id:qty) 排序后拼接)` → `NOT NULL` 保证约束生效 |

> **为什么到货单的幂等键必须 `NOT NULL`**（而采购单可空）：确认收货会**不可逆地改变库存**。若允许 `NULL`，MySQL 唯一索引允许多行 `NULL`，等于"客户端不传键就没有幂等保护"，风险不可接受。

---

## 3. 索引设计说明

### 3.1 三条索引设计原则

| # | 原则 | 说明 |
| --- | --- | --- |
| **I1** | **`team_id` 最左** | 所有团队业务表的主查询路径索引**必须以 `team_id` 为最左列**（`DESIGN_BASIS §6` 规则 6）。理由：① 所有查询天然带 `team_id` 条件（租户插件强制）；② 保证单个团队的查询只扫描自己的索引区间，团队数增加不降低单团队性能 |
| **I2** | **覆盖高频查询的"过滤 + 排序"** | 列表页普遍是"按状态过滤 + 按时间倒序"，因此索引形态为 `(team_id, status, created_at)`；分页用键集分页（keyset）时以最后一位为 `id` |
| **I3** | **不为低价值列建索引** | 不为 `remark`、`address` 等自由文本建索引；不为只有 2 个取值的布尔单独建索引（除 `is_default` 这类用于等值定位的场景）；**不建前缀索引**（`VARCHAR(32)` 以下全部可整列索引） |

### 3.2 全部索引清单

> 「选择性」：`高`（唯一度 > 0.1）、`中`（0.001~0.1）、`低`（< 0.001）。评估基于单团队内的预期数据量。

| # | 索引名 | 表 | 字段顺序 | 类型 | 支撑的查询/API | 选择性 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `idx_users_status` | `users` | `status` | 普通 | 用户管理列表（`GET /api/v1/admin/users?status=`） | 低 |
| 2 | `idx_teams_status` | `teams` | `status` | 普通 | 团队管理列表 | 低 |
| 3 | `idx_tm_team_role` | `team_members` | `team_id`, `role` | 联合 | 查本团队某角色成员；校验"每团队 1 名 OWNER" | 中 |
| 4 | `idx_tm_user_status` | `team_members` | `user_id`, `status` | 联合 | **登录后**"我的团队列表"（`GET /api/v1/me/teams`） | 高 |
| 5 | `idx_wh_team_status` | `warehouses` | `team_id`, `status` | 联合 | 仓库列表 | 中 |
| 6 | `idx_wh_team_default` | `warehouses` | `team_id`, `is_default` | 联合 | **取默认仓**（登录后高频路径） | 高（每团队 1 行命中） |
| 7 | `idx_sup_team_status` | `suppliers` | `team_id`, `status` | 联合 | 供应商列表 | 中 |
| 8 | `idx_prod_team_status` | `products` | `team_id`, `status` | 联合 | 商品列表 | 中 |
| 9 | `idx_prod_team_category` | `products` | `team_id`, `category`, `status` | 联合 | `scope_type = 'CATEGORY'` 取类目下启用商品（`§9.16`） | 中 |
| 10 | `idx_prod_team_supplier` | `products` | `team_id`, `default_supplier_id` | 联合 | 按供应商查商品 | 中 |
| 11 | `idx_sku_team_product` | `skus` | `team_id`, `product_id`, `status` | 联合 | 商品详情页列出其 SKU；`SELECTED_SKUS` 校验 | 中 |
| 12 | `idx_sku_team_status` | `skus` | `team_id`, `status` | 联合 | `scope_type = 'ALL_ACTIVE'` 取全量启用 SKU | 低 |
| 13 | `idx_sku_team_supplier` | `skus` | `team_id`, `supplier_id` | 联合 | 按供应商查 SKU | 中 |
| 14 | `idx_pol_team_sku` | `replenishment_policies` | `team_id`, `sku_id`, `is_enabled` | 联合 | 分析时批量取一批 SKU 的策略 | 高 |
| 15 | `idx_pol_team_wh` | `replenishment_policies` | `team_id`, `warehouse_id` | 联合 | 按仓库查策略配置 | 中 |
| 16 | `idx_invbal_team_sku` | `inventory_balances` | `team_id`, `sku_id` | 联合 | 某 SKU 在各仓的库存（`GET /api/v1/inventory?skuId=`） | 高 |
| 17 | `idx_invbal_team_available` | `inventory_balances` | `team_id`, `available_qty` | 联合（**生成列**） | **库存预警**：可用库存 ≤ 0 或低于阈值排序（`GET /api/v1/inventory/alerts`） | 中 |
| 18 | `idx_invtxn_team_wh_sku_time` | `inventory_transactions` | `team_id`, `warehouse_id`, `sku_id`, `id` | 联合 | 流水分页；**对账**；某 SKU 时间窗口流水 | 高 |
| 19 | `idx_invtxn_team_biz` | `inventory_transactions` | `team_id`, `biz_type`, `biz_ref_id` | 联合 | **从到货单反查入库流水**；冲销时定位原流水 | 高 |
| 20 | `idx_invtxn_team_occurred` | `inventory_transactions` | `team_id`, `occurred_at` | 联合 | 流水报表按时间筛选 | 中 |
| 21 | `idx_sales_team_sku_date` | `sales_daily` | `team_id`, `sku_id`, `sale_date` | 联合 | **最高频**：`D` 的 28 天窗口扫描（`§10.1`）、滞销（`R-SLOWMOVING`）、异常（`R-SALESANOMALY`） | 高 |
| 22 | `idx_sales_team_date` | `sales_daily` | `team_id`, `sale_date` | 联合 | 团队级日报/月报 | 中 |
| 23 | `idx_sales_team_product_date` | `sales_daily` | `team_id`, `product_id`, `sale_date` | 联合 | 商品/类目维度汇总（免 JOIN `skus`） | 中 |
| 24 | `idx_sales_team_batch` | `sales_daily` | `team_id`, `batch_id` | 联合 | 批次回溯（查某批次写了哪些销售行） | 中 |
| 25 | `idx_imp_team_status_time` | `import_batches` | `team_id`, `status`, `created_at` | 联合 | 批次列表按状态筛选 + 倒序 | 中 |
| 26 | `idx_imp_team_biz_time` | `import_batches` | `team_id`, `biz_type`, `created_at` | 联合 | 按业务类型查导入历史 | 中 |
| 27 | `idx_imp_team_file_hash` | `import_batches` | `team_id`, `file_hash` | 联合（**非唯一**） | 按原始哈希回溯**全部尝试**（唯一键只保证槽位唯一） | 高 |
| 28 | `idx_ire_team_batch_row` | `import_row_errors` | `team_id`, `batch_id`, `row_no` | 联合 | **最高频**：错误报告按行号排序导出 | 高 |
| 29 | `idx_ire_team_code` | `import_row_errors` | `team_id`, `error_code` | 联合 | 错误码分布统计（改进模板） | 中 |
| 30 | `idx_ire_team_level` | `import_row_errors` | `team_id`, `batch_id`, `error_level` | 联合 | 区分阻断错误与警告 | 中 |
| 31 | `idx_run_team_status` | `analysis_runs` | `team_id`, `status`, `created_at` | 联合 | **最高频**：任务列表；`ANL-7001` 的"是否有进行中任务"判定 | 中 |
| 32 | `idx_run_team_created` | `analysis_runs` | `team_id`, `created_at` | 联合 | 运行历史时间线 | 中 |
| 33 | `idx_run_timeout` | `analysis_runs` | `status`, `timeout_at` | 联合 | **系统级超时扫描**（⚠️ **无 `team_id` 最左**，见 §3.3 例外说明） | 中 |
| 34 | `idx_ai_team_run` | `analysis_items` | `team_id`, `run_id`, `risk_level` | 联合 | **最高频**：结果页按运行 + 风险等级筛选 | 高 |
| 35 | `idx_ai_team_sku_time` | `analysis_items` | `team_id`, `sku_id`, `created_at` | 联合 | **历史对比**：某 SKU 的 `D`/`IP` 时间序列 | 高 |
| 36 | `idx_repl_team_status_time` | `replenishment_items` | `team_id`, `status`, `created_at` | 联合 | **最高频**：建议列表；`PENDING_REVIEW` 待办 | 中 |
| 37 | `idx_repl_team_sku_time` | `replenishment_items` | `team_id`, `sku_id`, `created_at` | 联合 | 某 SKU 的建议历史 | 高 |
| 38 | `idx_repl_team_run` | `replenishment_items` | `team_id`, `run_id` | 联合 | 按运行批量拉建议 | 高 |
| 39 | `idx_repl_team_source` | `replenishment_items` | `team_id`, `source_analysis_item_id` | 联合 | **追溯**：由快照反查建议 | 高 |
| 40 | `idx_rr_team_item_time` | `replenishment_reviews` | `team_id`, `replenishment_item_id`, `occurred_at` | 联合 | **最高频**：建议详情页审核时间线 | 高 |
| 41 | `idx_rr_team_reviewer_time` | `replenishment_reviews` | `team_id`, `reviewer_id`, `occurred_at` | 联合 | 审批工作量统计 | 中 |
| 42 | `idx_rr_team_action_time` | `replenishment_reviews` | `team_id`, `action`, `occurred_at` | 联合 | 驳回原因分析 | 中 |
| 43 | `idx_po_team_status_time` | `purchase_orders` | `team_id`, `status`, `created_at` | 联合 | **最高频**：采购单列表 + 待收货待办 | 中 |
| 44 | `idx_po_team_supplier_status` | `purchase_orders` | `team_id`, `supplier_id`, `status` | 联合 | 按供应商统计未完结采购单 | 中 |
| 45 | `idx_po_team_expected` | `purchase_orders` | `team_id`, `expected_arrival_date` | 联合 | **在途窗口查询**（`§10.4` 输入） | 中 |
| 46 | `idx_poi_team_po` | `purchase_order_items` | `team_id`, `purchase_order_id`, `line_no` | 联合 | 采购单详情页；汇总与状态重算 | 高 |
| 47 | `idx_poi_team_sku` | `purchase_order_items` | `team_id`, `sku_id` | 联合 | 按 SKU 查采购历史 | 高 |
| 48 | `idx_poi_team_repl` | `purchase_order_items` | `team_id`, `replenishment_item_id` | 联合 | **追溯**：建议 → 采购明细（§6-G） | 高 |
| 49 | `idx_gr_team_po_status` | `goods_receipts` | `team_id`, `purchase_order_id`, `status` | 联合 | 采购单详情页的到货记录 | 高 |
| 50 | `idx_gr_team_status_time` | `goods_receipts` | `team_id`, `status`, `created_at` | 联合 | 待确认到货待办 | 中 |
| 51 | `idx_gr_team_date` | `goods_receipts` | `team_id`, `receipt_date` | 联合 | 到货统计 | 中 |
| 52 | `idx_gri_team_receipt` | `goods_receipt_items` | `team_id`, `goods_receipt_id` | 联合 | 到货单详情页 | 高 |
| 53 | `idx_gri_team_poi_time` | `goods_receipt_items` | `team_id`, `purchase_order_item_id` | 联合 | **最高频**：某采购明细的到货历史（`received_qty` 重算依据） | 高 |
| 54 | `idx_al_team_biz_time` | `audit_logs` | `team_id`, `biz_type`, `biz_id`, `occurred_at` | 联合 | **最高频**：某对象的操作历史 | 高 |
| 55 | `idx_al_team_operator_time` | `audit_logs` | `team_id`, `operator_id`, `occurred_at` | 联合 | 按人审计 | 中 |
| 56 | `idx_al_team_action_time` | `audit_logs` | `team_id`, `action`, `occurred_at` | 联合 | 按动作类型审计/统计 | 中 |
| 57 | `idx_al_team_request` | `audit_logs` | `team_id`, `request_id` | 联合 | **一次请求的全部审计**（链路排查） | 高 |
| 58 | `idx_al_team_result_time` | `audit_logs` | `team_id`, `result`, `occurred_at` | 联合 | 失败审计巡检 | 低 |

**索引总数**：**82 条** = 24 条唯一索引（§2.1） + **58 条普通/联合查询索引**（上表）。其中 `uk_*` 与 `idx_*` 不重复计数；上表只列出普通/联合查询索引。

### 3.3 `team_id` 最左原则的例外与说明

| 索引 | 例外原因 | 风险与缓解 |
| --- | --- | --- |
| `idx_run_timeout (status, timeout_at)` | **系统级超时扫描任务**需要跨团队扫描"所有已超时但仍在 `RUNNING` 的运行"，若以 `team_id` 最左则无法高效全表扫描 | ① 该索引只被**单个后台任务**使用，且 `status IN ('PENDING','RUNNING')` 的选择性很高（运行中的任务极少），扫描行数极小；② 该索引**不返回业务数据**（只取 `id` / `team_id` / `timeout_at`），取出后按 `team_id` 逐条处理，不泄漏跨团队数据；③ 属 `§6` 规则 6 的**必要例外**，建议在 `§6` 补充"系统级调度查询可豁免" |

> **其余 57 条索引全部以 `team_id` 为最左列**，符合 `DESIGN_BASIS §6` 规则 6。

### 3.4 典型查询与索引匹配对照表（22 条）

| # | 典型查询（API / 场景） | 关键 SQL 条件与排序 | 命中索引 | 是否覆盖排序 |
| --- | --- | --- | --- | --- |
| 1 | `GET /api/v1/skus?keyword=&status=ACTIVE&page=` | `team_id=? AND status=? ORDER BY id DESC` | `idx_sku_team_status (team_id, status)` | 部分（`id` 回表后排序，配合 keyset 分页） |
| 2 | 取默认仓 | `team_id=? AND is_default=1 LIMIT 1` | `idx_wh_team_default` | 是 |
| 3 | `scope_type='ALL_ACTIVE'` 取分析范围 | `team_id=? AND status='ACTIVE'` | `idx_sku_team_status` | — |
| 4 | `scope_type='CATEGORY'` 取范围 | `team_id=? AND category IN (...) AND status='ACTIVE'` | `idx_prod_team_category` | — |
| 5 | 商品详情页列 SKU | `team_id=? AND product_id=? AND status='ACTIVE'` | `idx_sku_team_product` | — |
| 6 | 计算 `D`：28 天窗口销量 | `team_id=? AND sku_id=? AND sale_date BETWEEN ? AND ?` | `idx_sales_team_sku_date` | 是 |
| 7 | 批量计算：一次 run 的全部 SKU×日期区间 | `team_id=? AND sale_date BETWEEN ? AND ?`（按 SKU 聚合） | `idx_sales_team_date` / `idx_sales_team_sku_date` | 是 |
| 8 | 商品/类目维度销量汇总 | `team_id=? AND product_id=? AND sale_date BETWEEN ?` | `idx_sales_team_product_date` | 是 |
| 9 | 库存总览（分页） | `team_id=? AND warehouse_id=? ORDER BY sku_id` | `uk_invbal_team_wh_sku`（前缀 `team_id, warehouse_id`） | 是 |
| 10 | 库存预警：可用库存排序 | `team_id=? AND available_qty <= ? ORDER BY available_qty` | `idx_invbal_team_available` | 是 |
| 11 | 某 SKU 流水（分页） | `team_id=? AND warehouse_id=? AND sku_id=? ORDER BY id DESC` | `idx_invtxn_team_wh_sku_time` | 是 |
| 12 | 由到货单反查入库流水 | `team_id=? AND biz_type='GOODS_RECEIPT' AND biz_ref_id=?` | `idx_invtxn_team_biz` | 是（按 `biz_ref_id` 等值） |
| 13 | **余额对账**（全量比对） | `GROUP BY team_id, warehouse_id, sku_id` 全表 | `uk_invbal_team_wh_sku` + `idx_invtxn_team_wh_sku_time` | 低效但低频（每日一次） |
| 14 | 导入批次列表 | `team_id=? AND status=? ORDER BY created_at DESC` | `idx_imp_team_status_time` | 是 |
| 15 | 错误报告导出 | `team_id=? AND batch_id=? ORDER BY row_no` | `idx_ire_team_batch_row` | 是 |
| 16 | 分析运行列表 | `team_id=? AND status=? ORDER BY created_at DESC` | `idx_run_team_status` | 是 |
| 17 | `ANL-7001` 判定：是否有进行中任务 | `team_id=? AND status IN ('PENDING','RUNNING')` | `idx_run_team_status` | — |
| 18 | 分析结果页 | `team_id=? AND run_id=? AND risk_level=? ORDER BY id` | `idx_ai_team_run` | 部分 |
| 19 | 某 SKU 的历史 `D`/`IP` 趋势 | `team_id=? AND sku_id=? ORDER BY created_at` | `idx_ai_team_sku_time` | 是 |
| 20 | 建议待办（`PENDING_REVIEW`） | `team_id=? AND status='PENDING_REVIEW' ORDER BY created_at` | `idx_repl_team_status_time` | 是 |
| 21 | 建议 → 采购明细追溯 | `team_id=? AND replenishment_item_id=?` | `idx_poi_team_repl` | — |
| 22 | 某采购明细的到货历史 | `team_id=? AND purchase_order_item_id=?` | `idx_gri_team_poi_time` | — |
| 23 | **在途查窗口**（`§10.4`） | `team_id=? AND status IN ('PENDING','ORDERED','PARTIALLY_RECEIVED') AND (expected_arrival_date IS NULL OR expected_arrival_date <= ?)` | `idx_po_team_status_time` + `idx_poi_team_po` | — |
| 24 | 审计：某对象的操作历史 | `team_id=? AND biz_type=? AND biz_id=? ORDER BY occurred_at` | `idx_al_team_biz_time` | 是 |

### 3.5 索引的反模式（明确不做）

| 反模式 | 为什么不做 |
| --- | --- |
| 为每个外键单独建索引 | 外键的校验查询使用**被引用表**的主键索引；`*_id` 列在本设计中的查询场景均由**复合索引的首列/次列**覆盖（如 `team_id, sku_id`），不需额外单列索引 |
| 建 `(created_at)` 单列索引 | 所有列表页都有 `team_id` 条件，单列 `created_at` 索引几乎不会被选中 |
| 建 `(status)` 单列索引（团队业务表） | 同上；且 `status` 选择性低，必须与 `team_id` 组合 |
| 建 `JSON` 列的表达式索引 | 首期无"按 JSON 内部值查询"的需求（`risk_level` 已承担筛选）。若未来出现（如按 `risk_types` 包含某值筛选），再评估 `CAST(... AS CHAR)` 表达式索引或改为关系型列 |
| 建全文索引（`FULLTEXT`） | 首期商品/供应商搜索数据量小（单团队数千条），`LIKE '%kw%'` 可接受；引入 `FULLTEXT` 会增加维护与中文分词复杂度 |
| 同一列建多个重复索引 | 如 `uk_sku_team_code (team_id, sku_code)` 已覆盖 `team_id` 前缀查询，不重复建 `idx_sku_team_id` |

---

## 4. `CHECK` 约束清单（MySQL 8.0.16+）

> `DESIGN_BASIS §4.1` 明确依赖 `CHECK` 约束与"应用层枚举"双重保证。**约束值必须与 `§9` 完全一致**。
> ⚠️ 以下 SQL 片段**仅为说明性示例，未在 MySQL 中执行验证**。

### 4.1 枚举取值类

```sql
-- ⚠️ 以下为示例片段，未在 MySQL 中执行验证
ALTER TABLE users
  ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','DISABLED'));

ALTER TABLE teams
  ADD CONSTRAINT ck_teams_status CHECK (status IN ('ACTIVE','DISABLED'));

ALTER TABLE team_members
  ADD CONSTRAINT ck_tm_role CHECK (role IN ('OWNER','ADMIN','OPERATOR','APPROVER','VIEWER')),
  ADD CONSTRAINT ck_tm_status CHECK (status IN ('ACTIVE','REMOVED'));

-- warehouses / suppliers / products / skus 共用 MasterDataStatus
ALTER TABLE skus
  ADD CONSTRAINT ck_sku_status CHECK (status IN ('ACTIVE','INACTIVE'));
```

| 约束名 | 表 | 字段 | 取值集合 | SSOT |
| --- | --- | --- | --- | --- |
| `ck_users_status` | `users` | `status` | `ACTIVE`,`DISABLED` | `§9.1` |
| `ck_teams_status` | `teams` | `status` | `ACTIVE`,`DISABLED` | `§9.2` |
| `ck_tm_role` | `team_members` | `role` | `OWNER`,`ADMIN`,`OPERATOR`,`APPROVER`,`VIEWER` | `§9.3` |
| `ck_tm_status` | `team_members` | `status` | `ACTIVE`,`REMOVED` | `§9.3` |
| `ck_wh_status` / `ck_sup_status` / `ck_prod_status` / `ck_sku_status` | 对应 4 表 | `status` | `ACTIVE`,`INACTIVE` | `§9.4` |
| `ck_imp_status` | `import_batches` | `status` | `UPLOADED`,`PARSED`,`MAPPED`,`VALIDATED`,`IMPORTING`,`COMPLETED`,`PARTIAL_SUCCESS`,`FAILED`,`CANCELLED`,`DUPLICATE_REJECTED` | `§9.5` |
| `ck_imp_biz_type` | `import_batches` | `biz_type` | `PRODUCT`,`SKU`,`SUPPLIER`,`WAREHOUSE`,`INVENTORY_SNAPSHOT`,`SALES_DAILY` | `§9.15` |
| `ck_invtxn_type` | `inventory_transactions` | `txn_type` | `INIT_IMPORT`,`PURCHASE_INBOUND`,`SALE_OUTBOUND`,`COUNT_ADJUST`,`MANUAL_ADJUST`,`REVERSAL` | `§9.6` |
| `ck_invtxn_biz_type` | `inventory_transactions` | `biz_type` | `GOODS_RECEIPT`,`COUNT`,`MANUAL`,`IMPORT`,`SALE`,`REVERSAL` ⚠️（`§9` 未定义，`D-14`） | — |
| `ck_run_status` | `analysis_runs` | `status` | `PENDING`,`RUNNING`,`SUCCEEDED`,`DEGRADED`,`FAILED`,`TIMEOUT`,`CANCELLED` | `§9.7` |
| `ck_run_llm_status` | `analysis_runs` | `llm_status` | `NOT_REQUESTED`,`PENDING`,`SUCCEEDED`,`FAILED`,`TIMEOUT`,`VALIDATION_FAILED`,`SKIPPED` | `§9.8` |
| `ck_run_scope_type` | `analysis_runs` | `scope_type` | `ALL_ACTIVE`,`SELECTED_SKUS`,`CATEGORY` | `§9.16` |
| `ck_ai_risk_level` | `analysis_items` | `risk_level` | `NONE`,`LOW`,`MEDIUM`,`HIGH`,`UNKNOWN` | `§9.9` |
| `ck_ai_lead_time_source` | `analysis_items` | `lead_time_source` | `POLICY`,`SKU`,`SUPPLIER`,`DEFAULT` ⚠️（`§9` 未定义，`D-14`） | `§10.2` |
| `ck_repl_status` | `replenishment_items` | `status` | `SUGGESTED`,`PENDING_REVIEW`,`APPROVED`,`CONVERTED`,`REJECTED`,`SUPERSEDED`,`CANCELLED` | `§9.11` |
| `ck_repl_risk_level` | `replenishment_items` | `risk_level` | 同 `RiskLevel` | `§9.9` |
| `ck_rr_action` | `replenishment_reviews` | `action` | `SUBMIT`,`WITHDRAW`,`APPROVE`,`REJECT`,`CANCEL` | `§9.12` |
| `ck_rr_from_status` / `ck_rr_to_status` | `replenishment_reviews` | `from_status`/`to_status` | 同 `ReplenishmentItemStatus` | `§9.11` |
| `ck_rr_role` | `replenishment_reviews` | `reviewer_role` | 同 `TeamRole` | `§9.3` |
| `ck_po_status` | `purchase_orders` | `status` | `DRAFT`,`PENDING`,`ORDERED`,`PARTIALLY_RECEIVED`,`RECEIVED`,`CANCELLED` | `§9.13` |
| `ck_po_source` | `purchase_orders` | `source` | `MANUAL`,`REPLENISHMENT` ⚠️（`D-14`） | — |
| `ck_gr_status` | `goods_receipts` | `status` | `DRAFT`,`CONFIRMED`,`CANCELLED` | `§9.14` |
| `ck_sales_source` | `sales_daily` | `source` | `IMPORT`,`MANUAL`,`API` ⚠️（`D-14`） | — |
| `ck_ire_level` | `import_row_errors` | `error_level` | `ERROR`,`WARNING` ⚠️（`D-14`） | — |
| `ck_al_result` | `audit_logs` | `result` | `SUCCESS`,`FAILURE` ⚠️（`D-14`） | — |

### 4.2 数量非负与一致性类

```sql
-- ⚠️ 以下为示例片段，未在 MySQL 中执行验证
ALTER TABLE inventory_balances
  ADD CONSTRAINT ck_invbal_on_hand_non_negative     CHECK (on_hand_qty    >= 0),
  ADD CONSTRAINT ck_invbal_in_transit_non_negative  CHECK (in_transit_qty >= 0),
  ADD CONSTRAINT ck_invbal_committed_non_negative   CHECK (committed_qty  >= 0);

ALTER TABLE inventory_transactions
  ADD CONSTRAINT ck_invtxn_consistency           CHECK (qty_after = qty_before + qty_delta),
  ADD CONSTRAINT ck_invtxn_before_non_negative   CHECK (qty_before >= 0),
  ADD CONSTRAINT ck_invtxn_after_non_negative    CHECK (qty_after  >= 0),
  ADD CONSTRAINT ck_invtxn_delta_non_zero        CHECK (qty_delta <> 0);

ALTER TABLE purchase_order_items
  ADD CONSTRAINT ck_poi_ordered_positive  CHECK (ordered_qty > 0),
  ADD CONSTRAINT ck_poi_received_range    CHECK (received_qty >= 0 AND received_qty <= ordered_qty);
```

| 约束名 | 表 | 表达式 | 依据 | 备注 |
| --- | --- | --- | --- | --- |
| `ck_invbal_on_hand_non_negative` | `inventory_balances` | `on_hand_qty >= 0` | `D-08`（不允许负库存） | 应用层先校验（`INV-5001`），DB 兜底 |
| `ck_invbal_in_transit_non_negative` | `inventory_balances` | `in_transit_qty >= 0` | `§3` | — |
| `ck_invbal_committed_non_negative` | `inventory_balances` | `committed_qty >= 0` | `§3` | — |
| `ck_invtxn_consistency` | `inventory_transactions` | `qty_after = qty_before + qty_delta` | `§6-C.2` | **流水自证**，对账的第一道防线 |
| `ck_invtxn_before_non_negative` | `inventory_transactions` | `qty_before >= 0` | `D-08` | — |
| `ck_invtxn_after_non_negative` | `inventory_transactions` | `qty_after >= 0` | `D-08` | — |
| `ck_invtxn_delta_non_zero` | `inventory_transactions` | `qty_delta <> 0` | 业务 | 0 变更不应产生流水 |
| `ck_poi_ordered_positive` | `purchase_order_items` | `ordered_qty > 0` | 业务 | 0 数量的明细无意义 |
| `ck_poi_received_range` | `purchase_order_items` | `received_qty >= 0 AND received_qty <= ordered_qty` | `§6-I.3`（不允许超额收货） | 应用层校验返回 `PO-9002`，DB 兜底 |
| `ck_gri_received_positive` | `goods_receipt_items` | `received_qty > 0` | 业务 | 到货明细数量必须为正 |
| `ck_po_total_qty` | `purchase_orders` | `total_qty >= 0` | 业务 | — |
| `ck_po_received_qty` | `purchase_orders` | `received_qty >= 0` | 业务 | 单头汇总非负 |
| `ck_gr_total_qty` | `goods_receipts` | `total_qty >= 0` | 业务 | — |
| `ck_sales_qty_non_negative` | `sales_daily` | `qty >= 0` | 业务 | 退货用 `return_qty` |
| `ck_sales_return_non_negative` | `sales_daily` | `return_qty >= 0` | 业务 | — |
| `ck_sales_amount_non_negative` | `sales_daily` | `sales_amount IS NULL OR sales_amount >= 0` | 业务 | 可空列需先判 `NULL` |
| `ck_repl_suggested_qty` | `replenishment_items` | `suggested_qty >= 0` | `§10.5` | `Q_suggest` 非负 |
| `ck_repl_qty` | `replenishment_items` | `qty >= 0` | 业务 | — |

### 4.3 区间合法性类

| 约束名 | 表 | 表达式 | 依据 |
| --- | --- | --- | --- |
| `ck_wh_status_default` | `warehouses` | `is_default IN (0,1)` | `§4.1` 布尔 |
| `ck_sup_lead_time` | `suppliers` | `lead_time_days IS NULL OR lead_time_days >= 0` | `§10.2`（0 合法：即时交付） |
| `ck_sku_lead_time` | `skus` | 同上 | 同上 |
| `ck_pol_lead_time` | `replenishment_policies` | 同上 | 同上 |
| `ck_pol_coverage` | `replenishment_policies` | `coverage_days IS NULL OR coverage_days > 0` | `§10.6`（R 为正） |
| `ck_pol_window` | `replenishment_policies` | `window_days IS NULL OR window_days > 0` | `§10.6` |
| `ck_pol_min_days` | `replenishment_policies` | `min_days IS NULL OR min_days > 0` | `§10.6` |
| `ck_run_progress` | `analysis_runs` | `progress <= 100` | `§9.7`（0–100） |
| `ck_run_date_range` | `analysis_runs` | `data_start_date IS NULL OR data_end_date IS NULL OR data_start_date <= data_end_date` | 日期区间合法 |
| `ck_ai_d` | `analysis_items` | `avg_daily_sales IS NULL OR avg_daily_sales >= 0` | `§10.1` |
| `ck_ai_qbase` / `ck_ai_qsuggest` | `analysis_items` | `IS NULL OR >= 0` | `§10.2` / `§10.5` |
| `ck_ai_ss` | `analysis_items` | `safety_stock_qty IS NULL OR safety_stock_qty >= 0` | `§10.3` |
| `ck_rr_comment_required` | `replenishment_reviews` | `action <> 'REJECT' OR (comment IS NOT NULL AND comment <> '')` | `§9.11`（驳回必填原因） |
| `ck_gri_receipt_qty` | `goods_receipt_items` | `received_qty > 0` | 业务 |
| `ck_ire_row_no` | `import_row_errors` | `row_no >= 1` | 行号 1 起 |
| `ck_imp_total_rows` / `ck_imp_success_rows` / `ck_imp_failed_rows` | `import_batches` | `>= 0` | 业务 |
| `ck_imp_attempt_no` | `import_batches` | `attempt_no >= 1` | `§6-E.4` |

### 4.4 为什么某些不变式**不用** `CHECK`

| 不变式 | 为什么不用 `CHECK` | 替代方案 |
| --- | --- | --- |
| `success_rows + failed_rows = total_rows` | `IMPORTING` 中间态**不满足**该等式（分批累加中） | 应用层在进入终态**前**断言；不一致则不允许进入终态 |
| `received_qty <= ordered_qty`（跨到货单汇总） | 单行 `CHECK` 只能看本行；跨单据汇总需读其他行 | 已在 `ck_poi_received_range` 上做**单行**兜底；跨单据的"Σ 到货 ≤ ordered"由服务层在确认时校验（返回 `PO-9002`） |
| `Σ goods_receipt_items.received_qty = purchase_order_items.received_qty` | 跨表聚合，MySQL `CHECK` 不支持子查询 | 同事务内累加保证；每日对账脚本巡检 |
| `replenishment_items.qty <= suggested_qty × k` | 业务上人工可任意改量，无硬上限（属人工决策） | 不做约束；超出时仅在前端给出提示 |
| `replenishment_items.status` 的**合法转换** | `CHECK` 只能校验"取值在集合内"，不能表达"从 A 到 B 的转换是否合法"（需要旧值） | 应用层状态机 + **状态守卫式 UPDATE**（`WHERE status = 旧值`），见 §2.2 幂等 4 |

---

## 5. 外键策略

### 5.1 策略总览

| 类别 | 是否建真外键 | 关系 | 理由 |
| --- | --- | --- | --- |
| 同模块主从 | ✅ **是** | `purchase_orders → purchase_order_items`；`analysis_runs → analysis_items`；`import_batches → import_row_errors`；`goods_receipts → goods_receipt_items`；`replenishment_items → replenishment_reviews` | 子行离开父行无意义；防止"孤儿明细" |
| 稳定主数据引用 | ✅ **是** | `skus.product_id/supplier_id`、`products.default_supplier_id`、`warehouses/suppliers/skus → 相关主数据`、`*_items.sku_id` | 主数据软停用（永不物理删除），`RESTRICT` 不会误伤正常业务 |
| 追溯链 | ✅ **是** | `replenishment_items.source_analysis_item_id`、`.run_id`、`.sku_id`、`.warehouse_id`、`.supplier_id` | 审计刚需：建议必须指向存在的快照 |
| 多态关联 | ❌ **否** | `audit_logs.biz_id`、`inventory_transactions.biz_ref_id`、`inventory_transactions.biz_type` 指向的表 | MySQL 无多态外键 |
| 账本 ↔ 快照 | ❌ **否** | `inventory_transactions` ↔ `inventory_balances` | 流水不可变，绑定外键会阻碍未来重建余额 |
| 跨模块 | ❌ **否** | `purchase_order_items.replenishment_item_id` | 保持模块依赖单向，避免表级双向引用 |
| 审计/操作者类 | ❌ **否** | 所有 `created_by` / `updated_by` / `operator_id` / `reviewer_id` / `approved_by` / `confirmed_by` / `triggered_by` / `uploaded_by` | 数量大、需记录已停用用户、不应成为全库写入瓶颈 |
| 冗余快照列 | ❌ **否** | `product_id`（4 张表）、`biz_ref_no`、`operator_name`、`operator_role`、`reviewer_role` | 冗余列的价值是"免 JOIN"与"保留当时值"，与"快照"语义一致 |
| 统一 `team_id` | ❌ **否** | 20 张表 → `teams` | 20 条同质外键收益低；团队隔离由唯一约束 + 复合索引 + 服务层 + 租户插件保证；`audit_logs.team_id = 0` 需哨兵值 |
| 环形/自关联 | ❌ **否** | `teams.default_warehouse_id`、`import_batches.duplicate_of_batch_id` / `superseded_by_batch_id`、`replenishment_items.superseded_by_item_id`、`inventory_transactions.reversed_txn_id` | 环形外键导致无法首插；自关联的两阶段写入与批量流转会被 `RESTRICT` 破坏 |

### 5.2 外键清单（全部真外键）

> 全部 `ON DELETE RESTRICT` / `ON UPDATE RESTRICT`。外键字段上必须有索引（本设计的复合索引已满足或以父表主键索引校验）。

| # | 外键名 | 从表.字段 | 到表.字段 | 团队维度说明 |
| --- | --- | --- | --- | --- |
| 1 | `fk_tm_user` | `team_members.user_id` | `users.id` | 用户全局，无团队维度 |
| 2 | `fk_tm_team` | `team_members.team_id` | `teams.id` | — |
| 3 | `fk_prod_supplier` | `products.default_supplier_id` | `suppliers.id` | 服务层校验同团队 |
| 4 | `fk_sku_product` | `skus.product_id` | `products.id` | 服务层校验同团队 |
| 5 | `fk_sku_supplier` | `skus.supplier_id` | `suppliers.id` | 服务层校验同团队 |
| 6 | `fk_pol_sku` | `replenishment_policies.sku_id` | `skus.id` | 服务层校验 `sku.team_id == wh.team_id == team_id` |
| 7 | `fk_pol_warehouse` | `replenishment_policies.warehouse_id` | `warehouses.id` | 同上 |
| 8 | `fk_invbal_warehouse` | `inventory_balances.warehouse_id` | `warehouses.id` | 服务层校验 |
| 9 | `fk_invbal_sku` | `inventory_balances.sku_id` | `skus.id` | 服务层校验 |
| 10 | `fk_invtxn_warehouse` | `inventory_transactions.warehouse_id` | `warehouses.id` | 服务层校验 |
| 11 | `fk_invtxn_sku` | `inventory_transactions.sku_id` | `skus.id` | 服务层校验 |
| 12 | `fk_sales_warehouse` | `sales_daily.warehouse_id` | `warehouses.id` | 服务层校验 |
| 13 | `fk_sales_sku` | `sales_daily.sku_id` | `skus.id` | 服务层校验 |
| 14 | `fk_ire_batch` | `import_row_errors.batch_id` | `import_batches.id` | 同模块 |
| 15 | `fk_ai_run` | `analysis_items.run_id` | `analysis_runs.id` | 同模块 |
| 16 | `fk_ai_sku` | `analysis_items.sku_id` | `skus.id` | 服务层校验 |
| 17 | `fk_ai_warehouse` | `analysis_items.warehouse_id` | `warehouses.id` | 服务层校验 |
| 18 | `fk_repl_source` | `replenishment_items.source_analysis_item_id` | `analysis_items.id` | **追溯链核心** |
| 19 | `fk_repl_run` | `replenishment_items.run_id` | `analysis_runs.id` | 服务层校验 |
| 20 | `fk_repl_sku` | `replenishment_items.sku_id` | `skus.id` | 服务层校验 |
| 21 | `fk_repl_warehouse` | `replenishment_items.warehouse_id` | `warehouses.id` | 服务层校验 |
| 22 | `fk_repl_supplier` | `replenishment_items.supplier_id` | `suppliers.id` | 服务层校验 |
| 23 | `fk_rr_item` | `replenishment_reviews.replenishment_item_id` | `replenishment_items.id` | 同模块 |
| 24 | `fk_po_supplier` | `purchase_orders.supplier_id` | `suppliers.id` | **`§6` 规则 4 的显式例子：必须校验 `supplier.team_id == po.team_id`** |
| 25 | `fk_po_warehouse` | `purchase_orders.warehouse_id` | `warehouses.id` | 同上 |
| 26 | `fk_poi_po` | `purchase_order_items.purchase_order_id` | `purchase_orders.id` | 同模块 |
| 27 | `fk_poi_sku` | `purchase_order_items.sku_id` | `skus.id` | 服务层校验 `sku.team_id == po.team_id` |
| 28 | `fk_gr_po` | `goods_receipts.purchase_order_id` | `purchase_orders.id` | 同模块 |
| 29 | `fk_gr_warehouse` | `goods_receipts.warehouse_id` | `warehouses.id` | 服务层校验 |
| 30 | `fk_gri_receipt` | `goods_receipt_items.goods_receipt_id` | `goods_receipts.id` | 同模块 |
| 31 | `fk_gri_poi` | `goods_receipt_items.purchase_order_item_id` | `purchase_order_items.id` | 服务层校验 |
| 32 | `fk_gri_sku` | `goods_receipt_items.sku_id` | `skus.id` | 服务层校验 |

**外键总数：32 条**（另有 0 条指向 `teams`、0 条自关联外键、0 条多态外键）。

### 5.3 为什么 `team_id` 不建外键（在 `DATABASE_DESIGN.md §6-A` 之外补充"代价层面"的理由）

| 维度 | 建外键 | 不建外键（本设计选择） |
| --- | --- | --- |
| 约束数量 | +20 条外键 | 0 |
| 写入成本 | 每次 INSERT 多一次父表索引查找（20 张表全部受益？否——只有新建行受益） | 0 额外成本 |
| 隔离保障 | 只保证"团队存在"，**不保证**"查询只返回本团队数据" | 隔离保障来自 `team_id` 最左索引 + 服务层 + 租户插件（真正的保障） |
| 特例处理 | `audit_logs.team_id = 0`（系统域）必须**排除**外键，被迫形成"19 张表有外键、1 张没有"的不一致 | 全库一致 |
| 归档/分库 | `teams` 表成为所有归档操作的阻塞点 | 无阻塞 |
| 结论 | 收益（防孤儿团队）低、成本高、且**不解决真正的隔离问题** | ✅ |

---

## 6. 团队隔离的数据库级保障

### 6.1 六条硬规则 × 落地手段（逐条对应 `DESIGN_BASIS §6`）

| # | `§6` 规则原文要点 | 数据库层落地 | 应用层落地 | 测试 |
| --- | --- | --- | --- | --- |
| **1** | 团队业务表必须含 `team_id BIGINT UNSIGNED NOT NULL`；例外 `users`、`teams` | 20 张表均为 `NOT NULL`（无默认值，强制写入时必须提供） | `TeamContext` 从 JWT claim 解析，**绝不接受客户端传入** | T-1.1 ~ T-1.3 |
| **2** | 唯一约束必须带 `team_id` 前缀 | 24 条唯一约束中 19 条带前缀；5 条例外（`users`/`teams` 3 条 + 派生键 2 条）在 `§2.1` 显式声明 | 冲突翻译为 `CAT-3002` 等业务错误码 | T-2.1 ~ T-2.3 |
| **3** | `team_id` 绝不接受客户端传入 | **无默认值**：若应用层忘记注入，INSERT 直接失败（`NOT NULL` 无默认值） | `TeamContext`（ThreadLocal / RequestScope）统一提供 | T-3.1 ~ T-3.2 |
| **4** | 跨表关联写入必须做同团队校验 | 真外键保证"父子都存在"，但**不保证同团队**（这是 DB 无法表达的部分） | `TeamScopeValidator.assertSameTeam(...)`；`§6` 规则 4 的示例（`sku.team_id == po.team_id`） | T-4.1 ~ T-4.4 |
| **5** | 关联查询必须携带 `team_id`，不得通过"外键成立"推断隔离成立 | 无 | 所有查询方法强制 `team_id` 参数；租户插件追加 `team_id = ?` | T-5.1 ~ T-5.2 |
| **6** | 主查询路径索引必须以 `team_id` 为最左列 | 57/58 条索引以 `team_id` 最左；1 条例外（`idx_run_timeout`，系统级调度）在 `§3.3` 声明 | 代码评审检查新索引 | T-6.1 |

### 6.2 纵深防御的五层

```
L1 数据模型   team_id NOT NULL 无默认值          ← 写漏了直接失败
L2 唯一约束   带 team_id 前缀，跨团队可重复       ← 跨团队不冲突、同团队不重复
L3 复合索引   team_id 最左                        ← 查询天然分片，无全表扫描
L4 服务层     TeamScopeValidator 显式同团队校验   ← 关联校验（DB 无法表达）
L5 租户插件   TenantLineInnerInterceptor 自动追加 ← 兜底（防手写 SQL 漏条件）
```

> **L5 是兜底不是唯一防线**：插件对原生 SQL、`JOIN` 别名、`UNION` 的解析存在盲区，且**无法**表达 L4 的关联校验（`sku.team_id == po.team_id`）。`§6` 规则 4 原文已明确："**不能只因为参数带了 `team_id` 就认为隔离已完成**"。

### 6.3 跨团队越权测试场景（5 个）

> 全部以"团队 A 的用户（Token 中 `team_id = A`）访问团队 B 的数据"为基准。**对象不存在与不属于本团队返回同一错误码**（防对象探测）。

| # | 场景 | 请求 | 期望 HTTP | 期望错误码 | 期望响应要点 |
| --- | --- | --- | --- | --- | --- |
| **1** | **路径越权（读）**：A 的 Token 请求 B 的 SKU 详情 | `GET /api/v1/skus/{b_sku_id}` | **403** | **`TEAM-2001`** | 响应体不含任何 B 的数据字段（连字段名都不出现） |
| **2** | **请求体伪造**：A 的 Token 创建采购明细，显式传 `teamId = B`，且 `sku_id` 属 B | `POST /api/v1/purchasing/orders`（body 含 `teamId: B`, `skuId: b_sku_id`） | **403** | **`TEAM-2001`** | ① `teamId` 被**完全忽略**（以 `TeamContext` 为准）；② 记录 `audit_logs`（`action='TEAM_CROSS_ACCESS_DENIED'`，`result='FAILURE'`） |
| **3** | **关联校验越权**：A 创建采购明细，`sku_id` 属 A 但 `purchase_order_id` 属 B | `POST /api/v1/purchasing/orders`（body 含 `purchaseOrderId: b_po_id` 与 `sku_id`） | **403** | **`TEAM-2001`** | 校验在**进入 DB 前**失败；不得产生任何写入 |
| **4** | **状态操作越权**：A 审批 B 的补货建议 | `POST /api/v1/replenishment-items/{b_item_id}/approve` | **403** | **`TEAM-2001`** | 不泄漏 B 建议的任何信息（数量、SKU、供应商均不可见） |
| **5** | **团队停用**：A 的团队被停用（`teams.status='DISABLED'`）后任意业务接口 | `GET /api/v1/inventory?skuId={a_sku_id}` | **403** | **`TEAM-2002`** | 优先级：**先判团队状态（2002）再判权限（2003）**，先判停用再判越权（2001） |

**补充的数据库层等价验证**（在集成测试中直接对 DB 断言）：

| # | 验证 | 期望 |
| --- | --- | --- |
| D1 | 用团队 A 的 `team_id` 与团队 B 的 `sku_id` 直接 INSERT `inventory_transactions` | **成功**（外键只校验 `skus.id` 存在，不校验团队）→ **说明必须有 L4** |
| D2 | 用团队 A 的 `team_id` 与团队 B 的 `sku_code` 建 `skus` | **成功**（`uk_sku_team_code` 允许跨团队重复编码）→ 这是**期望行为** |
| D3 | 在同一团队内重复 `sku_code` | 失败（`uk_sku_team_code`）→ 翻译为 `CAT-3002` |
| D4 | 同一 `(team_id, idempotency_key)` 插入两条流水 | 第二条失败（`uk_invtxn_team_idem`）→ 翻译为幂等语义 |

> **D1 的结论非常重要**：它证明"外键不能替代团队校验"，因此 L4（`TeamScopeValidator`）是**必须存在**的，而不是可选优化。

---

## 7. 并发控制方案对照表

### 7.1 操作 → 冲突类型 → 方案 → 行为与错误码

| # | 操作 | 冲突类型 | 方案 | 冲突时行为 | 错误码 |
| --- | --- | --- | --- | --- | --- |
| 1 | **库存入库**（到货确认，余额更新） | 同一 SKU 余额并发修改 | **乐观锁 `version`** | 重试 3 次（20/50/120ms）；仍失败 → 报错 | `INV-5002` |
| 2 | **库存入库**（`poi.received_qty` 累加） | 读-改-写累加 | **悲观锁 `SELECT ... FOR UPDATE`** | 串行等待，超时（`innodb_lock_wait_timeout`）→ 报错 | `COM-0002` |
| 3 | **库存入库**（`goods_receipts` 状态） | 重复确认 | **状态守卫**（`WHERE status='DRAFT'`） | 影响行数 0 → 返回已确认结果（幂等成功） | —（幂等） |
| 4 | **库存入库**（重复提交收货） | 重复请求 | **唯一约束 `uk_gr_team_idem`** | 同键同体 → 返回原到货单；同键异体 → 报错 | `COM-0009` |
| 5 | **库存入库**（超额收货） | 业务校验 | **应用层校验 + `CHECK` 兜底** | 直接拒绝 | `PO-9002` |
| 6 | **库存出库** | 并发扣减 | **乐观锁 `version`** | 重试 3 次；仍失败 → 报错 | `INV-5002` |
| 7 | **库存出库**（库存不足） | 业务校验 | **应用层校验 + `CHECK` 兜底** | 直接拒绝 | `INV-5001` |
| 8 | **盘点调整**（同 SKU 批量） | 同一行多次更新 | **悲观锁**（按 `sku_id` 升序加锁防死锁） | 串行执行 | `COM-0002`（超时） |
| 9 | **盘点调整**（差额为 0） | 无意义写入 | **应用层幂等** | 不写流水，返回成功 | — |
| 10 | **手工调整** | 并发扣减/增加 | **乐观锁 `version`** | 重试 3 次 | `INV-5002` |
| 11 | **库存冲销**（重复冲销同一流水） | 重复请求 | **唯一约束 `uk_invtxn_team_reversed`**（`D-13`）+ 幂等键 | 报错（或返回既有冲销流水） | `INV-5003` |
| 12 | **导入落库**（余额首次建行） | 并发插同一行 | **唯一约束 `uk_invbal_team_wh_sku`** | 捕获冲突 → 改为读取 + 乐观锁路径 | —（静默吸收） |
| 13 | **导入落库**（销售 upsert） | 同 `(wh, sku, date)` 并发写 | **唯一约束 `uk_sales_team_wh_sku_date` + upsert** | 覆盖（最后写入者胜，符合设计语义） | — |
| 14 | **导入落库**（批次重复上传） | 并发同文件上传 | **唯一约束 `uk_imp_team_hash_biz`** | 一个成功、一个报重复 | `IMP-4004` |
| 15 | **导入落库**（批次状态推进） | 并发推进状态 | **状态守卫** | 影响行数 0 → 报状态不允许 | `IMP-4005` |
| 16 | **分析结果落库**（重复落库同一 run） | 重复请求 | **唯一约束 `uk_ai_run_sku_wh` / `uk_repl_run_sku_wh`** + 应用层"是否已落库"检查 | 已落库 → 跳过，返回既有结果 | —（幂等） |
| 17 | **分析结果落库**（状态回写） | 并发状态更新 | **状态守卫**（`WHERE status='RUNNING'`） | 影响行数 0 → 忽略（终态不可变） | — |
| 18 | **分析任务创建**（重复创建） | 重复请求 | **唯一约束 `uk_run_team_idem`** | 同键同体 → 返回原 `run_id`；异体 → 报错 | `COM-0009` |
| 19 | **分析任务创建**（范围重叠） | 业务并发 | **应用层查询**（`idx_run_team_status`） | 直接拒绝 | `ANL-7001` |
| 20 | **建议提交审批** | 重复提交 | **状态守卫 + `version`** | 影响行数 0 → 报重复提交 | `RPL-8002` |
| 21 | **建议提交审批**（数量被并发修改） | 丢失更新 | **乐观锁 `version`** | 报冲突，提示刷新 | `RPL-8003` |
| 22 | **审批通过/驳回** | **双审批并发** | **状态守卫**（`WHERE status='PENDING_REVIEW'`） | **只有 1 条成功**；另一条影响行数 0 → 报错 | `RPL-8001` / `RPL-8002` |
| 23 | **审批通过/驳回**（无权限） | 权限 | **应用层 RBAC** | 直接拒绝 | `TEAM-2003` |
| 24 | **创建采购单**（重复提交） | 重复请求 | **唯一约束 `uk_po_team_idem`** | 同键同体 → 返回原采购单；异体 → 报错 | `COM-0009` |
| 25 | **创建采购单**（建议被并发转采购） | 重复转换 | **建议状态守卫 + `version`** | 影响行数 0 → 整个事务回滚 | `PO-9001` |
| 26 | **创建采购单**（建议状态非法） | 业务校验 | **应用层状态机** | 直接拒绝 | `RPL-8001` |
| 27 | **创建采购单**（明细行号冲突） | 内部缺陷 | **唯一约束 `uk_poi_po_lino`** | 报错（不应发生） | `COM-0002` |
| 28 | **确认到货**（同单内重复行） | 重复请求 | **唯一约束 `uk_gri_receipt_poi`** | 报错 | `COM-0002` |
| 29 | **策略保存** | 重复保存 | **唯一约束 + upsert** | 覆盖 | — |
| 30 | **主数据编辑** | 并发编辑 | **乐观锁 `version`** | 报冲突 | `COM-0002`（建议细化） |
| 31 | **状态字段并发推进**（所有单据） | 双写状态 | **状态守卫**（条件 UPDATE） | 影响行数 0 → 报状态不允许 | 各域码（`IMP-4005`/`RPL-8001`/`PO-9003`） |
| 32 | **流水写入**（重复请求） | 重复 | **唯一约束 `uk_invtxn_team_idem`** | 同键同体 → 返回原流水；异体 → 报错 | `INV-5003` |

### 7.2 方案选型速查：什么时候用哪种

| 场景特征 | 推荐方案 | 本项目实例 |
| --- | --- | --- |
| 只需防"新增重复" | **唯一约束** | 所有幂等键、业务编码、业务键 |
| 需要"读-改-写"，冲突概率低、事务要短 | **乐观锁 `version`** | 库存余额、建议改量、主数据编辑 |
| 需要"读-改-写累加"，冲突概率高或**低频但逻辑复杂** | **悲观锁 `FOR UPDATE`** | `poi.received_qty` 累加、盘点批量调整 |
| 只需防"状态并发推进" | **状态守卫**（条件 UPDATE，等价 CAS） | 所有状态机（比乐观锁更轻，无需额外列） |
| 语义本身就是"最后一次写入为准" | **无锁覆盖（upsert）** | `sales_daily`、策略保存 |
| 跨服务/跨模块的互斥 | **本设计不需要**（无 MQ、无分布式锁；`§2.1` 未引入 Redis，`D-07`） | 全部关键写操作都在同一本地事务内 |

### 7.3 事务隔离级别与死锁预防

| 项 | 规定 |
| --- | --- |
| 隔离级别 | **`READ COMMITTED`**（理由见 `DATABASE_DESIGN.md §7.1`：避免 `REPEATABLE READ` 的间隙锁在"范围查询 + 插入"路径上造成死锁） |
| 死锁预防 | ① 所有**批量加锁**按主键（`sku_id` / `id`）**升序**；② 事务内**先写流水再更新余额**（固定顺序）；③ 导入分批（每 500 行）缩短持锁时间；④ 单事务行数上限 500 |
| 死锁处理 | 捕获 `DeadlockLoserDataAccessException` → **整事务重试**（最多 3 次，指数退避）→ 仍失败 → `COM-0002` |
| 锁等待超时 | `innodb_lock_wait_timeout = 10s`（默认 50s 过长）；超时 → `COM-0002` |
| 长事务监控 | 记录 > 1s 的事务并告警（`DATABASE_DESIGN.md §7.1` 要求单事务 < 100ms 量级） |

---

## 8. 约束相关的测试清单

> 对应 `docs/06-development/TEST_STRATEGY.md` 的**约束测试**部分。全部为**集成测试**（`@SpringBootTest` + 真实 MySQL 容器），因为约束语义必须由真实数据库验证。

### 8.1 唯一约束测试（24 条）

| # | 用例 | 期望 |
| --- | --- | --- |
| U-1 | 同团队内插入重复 `sku_code` | 抛唯一冲突 → 应用层翻译 `CAT-3002` |
| U-2 | **不同团队**插入相同 `sku_code` | **成功**（`§6` 规则 2 的核心验证） |
| U-3 | 同一 `(team_id, warehouse_id, sku_id)` 插入两条余额 | 第二条冲突 → 被并发首建路径吸收（不报错） |
| U-4 | 同一 `(team_id, idempotency_key)` 插入两条流水（内容一致） | 第二条冲突 → 返回原流水（幂等成功） |
| U-5 | 同上，但 `qty_delta` 不同 | 抛 `INV-5003` |
| U-6 | 同一 `(wh, sku, date)` 两次 upsert 销售 | 第二次覆盖，行数仍为 1，`qty` 为新值 |
| U-7 | 同团队同 `file_hash` 同 `biz_type` 第二次上传 | `IMP-4004`，且 `DUPLICATE_REJECTED` 记录写入成功（**槽位为 `NULL`**） |
| U-8 | 同文件 `FORCE_REIMPORT` | 新批次创建成功，旧批次 `superseded_by_batch_id` 已回填，**旧批次 `dedupe_hash` 为 `NULL`** |
| U-9 | 同团队同 `file_hash` **不同 `biz_type`** | 两次都成功（`biz_type` 参与幂等维度） |
| U-10 | 同一 `run_id` 下插入两条同 `(sku, wh)` 的 `analysis_items` | 第二条冲突 |
| U-11 | 两条不同 `run_id` 的同 `(sku, wh)` 快照 | 都成功（历史快照必须保留） |
| U-12 | 同一采购单两条同 `line_no` 明细 | 第二条冲突 |
| U-13 | 同一到货单两条同 `purchase_order_item_id` 明细 | 第二条冲突 |
| U-14 | 已知被冲销的流水再次冲销 | 冲突 → `INV-5003`（`D-13` 生效后） |
| U-15 | `idempotency_key = NULL` 的 `purchase_orders` 多行插入 | 都成功（可空唯一索引允许多 `NULL`） |

### 8.2 `CHECK` 约束测试

| # | 用例 | 期望 |
| --- | --- | --- |
| C-1 | 写 `skus.status = 'DRAFT'`（非法枚举） | 抛 `CHECK` 违反 |
| C-2 | 写 `inventory_balances.on_hand_qty = -1` | 抛 `CHECK` 违反 |
| C-3 | 写 `inventory_transactions` 使 `qty_after <> qty_before + qty_delta` | 抛 `CHECK` 违反 |
| C-4 | 写 `purchase_order_items.received_qty > ordered_qty` | 抛 `CHECK` 违反 |
| C-5 | 写 `replenishment_reviews`，`action='REJECT'` 且 `comment=NULL` | 抛 `CHECK` 违反 |
| C-6 | 写 `analysis_runs.progress = 101` | 抛 `CHECK` 违反 |
| C-7 | 写 `analysis_runs`，`data_start_date > data_end_date` | 抛 `CHECK` 违反 |
| C-8 | 写 `inventory_transactions.qty_delta = 0` | 抛 `CHECK` 违反 |
| C-9 | 写 `suppliers.lead_time_days = 0` | **成功**（0 是合法交期） |
| C-10 | 写 `suppliers.lead_time_days = NULL` | **成功**（表示未配置） |
| C-11 | **版本检查**：`SELECT VERSION()` < 8.0.16 时启动 | CI 阶段**断言失败并阻断**（防 `CHECK` 被静默忽略） |

### 8.3 外键与删除策略测试

| # | 用例 | 期望 |
| --- | --- | --- |
| F-1 | 删除仍有明细的 `purchase_orders` | 抛外键 `RESTRICT` |
| F-2 | 删除被流水引用的 `skus` | 抛外键 `RESTRICT` |
| F-3 | 尝试 `DELETE FROM inventory_transactions`（应用层） | **无对应方法**（编译期即阻止）；原生 SQL 直接 DELETE 在集成测试中不覆盖（属运维纪律） |
| F-4 | 停用 `skus`（`INACTIVE`）后查询历史 `sales_daily` | **成功返回**（历史数据必须可查，`§9.4`） |
| F-5 | 停用 `skus` 后新建分析任务 | 该 SKU **不进入**分析范围 |

### 8.4 团队隔离测试

| # | 用例 | 期望 |
| --- | --- | --- |
| T-1.1 | A 的 Token 读 B 的 SKU | 403 `TEAM-2001` |
| T-1.2 | A 的 Token 读 B 的补货建议 | 403 `TEAM-2001` |
| T-2.1 | A 与 B 使用相同 `sku_code` | 均成功（跨团队可重复） |
| T-3.1 | 应用层不注入 `team_id` 直接 INSERT | **失败**（`NOT NULL` 无默认值） |
| T-3.2 | 请求体显式传 `teamId=B`，Token 为 A | 以 Token 为准；并记录 `TEAM_CROSS_ACCESS_DENIED` 审计 |
| T-4.1 | A 的 PO + B 的 `sku_id` 创建明细 | 403 `TEAM-2001`（**进入 DB 前失败**） |
| T-4.2 | A 的 `team_id` + B 的 `sku_id` 直接 INSERT 流水 | **DB 层成功** → 断言脚本必须检测并告警（说明 L4 缺失） |
| T-4.3 | A 的 `team_id` + B 的 `warehouse_id` 建策略 | 服务层拒绝 |
| T-5.1 | 不带 `team_id` 的查询方法 | **编译期不存在**（接口强制参数） |
| T-6.1 | 新增索引评审 | 断言索引首列为 `team_id`（白名单：`idx_run_timeout`） |
| T-7.1 | 团队停用后任意接口 | 403 `TEAM-2002` |

### 8.5 并发与幂等测试

| # | 用例 | 期望 |
| --- | --- | --- |
| P-1 | 10 线程并发对同一 SKU 入库 | 全部成功（乐观锁重试）或部分 `INV-5002`；**最终余额 = Σ qty_delta** |
| P-2 | 10 线程并发出库，库存刚好够 5 次 | 5 次成功、5 次 `INV-5001`；**余额不为负** |
| P-3 | 2 个线程同时审批同一条建议 | **恰好 1 条成功**，另一条 `RPL-8001`/`RPL-8002`；`replenishment_reviews` 无重复 `APPROVE` |
| P-4 | 2 个线程同时"建议转采购" | 恰好 1 张采购单；另一条 `PO-9001` |
| P-5 | 2 个线程同时确认同一到货单 | 恰好 1 次库存入库；另一条返回已确认结果（幂等） |
| P-6 | 同 `idempotency_key` 并发 10 次确认到货 | 只有 1 条流水；其余返回同一结果 |
| P-7 | 2 个线程同时上传同一文件 | 1 个成功、1 个 `IMP-4004` |
| P-8 | 并发导入 1000 行同一 SKU 的初始库存 | 余额 = 1000 行之和（无丢失更新） |
| P-9 | 重复落库同一 `analysis_run` 的结果 | 幂等：快照与建议不重复 |
| P-10 | **对账用例**：随机 100 次库存操作后跑对账 SQL | 差异行数 = 0 |

### 8.6 事务与回滚测试

| # | 用例 | 期望 |
| --- | --- | --- |
| X-1 | 确认到货时在第 5 步（`purchase_orders` 更新）抛异常 | **全部回滚**：到货单仍 `DRAFT`、无流水、余额不变、`poi.received_qty` 不变 |
| X-2 | 导入某批 500 行中第 300 行触发 DB 异常 | 该 500 行整体回滚；之前批次已落库；最终 `PARTIAL_SUCCESS` |
| X-3 | 分析结果落库中途异常 | 整个 run 的 `analysis_items` 与 `replenishment_items` 全部回滚；`analysis_runs.status` 保持 `RUNNING`（等超时或重试） |
| X-4 | 创建采购单第 ④ 步（建议状态守卫）失败 | 采购单与明细**全部回滚**（不留孤儿采购单） |

---

## 9. 与 SSOT 的一致性声明

| 检查项 | 结论 |
| --- | --- |
| 唯一约束 | 与 `DESIGN_BASIS §8` 一致（23 条）；新增 1 条 `uk_invtxn_team_reversed` 已登记 `D-13` |
| 状态枚举取值 | `CHECK` 取值与 `§9` **逐字符一致**；`§9` 未定义的 5 类常量已在 §4.1 标注 ⚠️ 并登记 `D-14` |
| 错误码 | 只使用 `§12.3` 已列出的错误码；建议新增的 5 个码（`AUTH-1004`/`AUTH-1005`/`TEAM-2004`/`TEAM-2005`/`IMP-4006` 等）登记 `D-14` |
| 团队隔离 | 6 条硬规则逐条落地（§6.1）；2 处必要例外（`idx_run_timeout`、5 条无 `team_id` 前缀的唯一键）已显式声明 |
| 金额/数量/时间精度 | 与 `§14` 一致 |
| 不一致与待决策 | 见 `DATABASE_DESIGN.md §11`（`INC-1`~`INC-3`、`D-09`~`D-16`） |

## 10. 变更记录

| 日期 | 变更 | 依据 |
| --- | --- | --- |
| M0 | 初版创建（24 条唯一约束 + 5 类幂等 + 58 条索引 + 24 条典型查询 + CHECK 清单 + 32 条外键 + 5 层隔离与 5 个越权场景 + 并发对照表 + 测试清单） | `DESIGN_BASIS.md`、`DATABASE_DESIGN.md`、任务书 §5.4 |
