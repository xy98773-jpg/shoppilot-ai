# ShopPilot AI 数据字典（Data Dictionary）

> **文档编号**：`docs/02-database/DATA_DICTIONARY.md`
> **文档地位**：数据模型的**字段级速查表**，与 `DATABASE_DESIGN.md` **逐字段严格一致**。
> 两者冲突时以 `DATABASE_DESIGN.md` 为准（它是数据模型权威定义，`DESIGN_BASIS §1.1` P2）。
> **表清单来源**：`DESIGN_BASIS §8`（22 张，权威）。枚举取值来源：`DESIGN_BASIS §9`。
> **阶段**：M0 设计阶段，仅设计文档，不产出 SQL。
> 列含义：`字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明`

---

## 1. 阅读约定

| 记号 | 含义 |
| --- | --- |
| `PK` | 主键 |
| `FK:xxx(id)` | 强外键，指向表 `xxx` 的 `id`（`ON DELETE RESTRICT` / `ON UPDATE RESTRICT`） |
| `UK:xxx` | 唯一约束全名（约束清单见 `DATABASE_CONSTRAINTS.md §3`） |
| `IDX` | 普通/联合索引的组成部分（索引清单见 `DATABASE_CONSTRAINTS.md §4`） |
| `CK` | `CHECK` 约束（MySQL 8.0.16+） |
| `逻辑关联` | 不建数据库外键，仅应用层校验（理由见 `ER_DIAGRAM.md §6.2`） |
| `生成列` | `GENERATED ALWAYS AS (...) STORED`，不可直接写入 |
| `不可变` | 该字段写入后不允许 UPDATE（应用层禁止 + 无更新接口） |

- **所有时间戳为 `DATETIME(3)` 且存 UTC**（`DESIGN_BASIS §14.1`）；业务日期为 `DATE`，代表团队时区自然日。
- **所有数量为 `DECIMAL(18,3)`、单价 `DECIMAL(18,4)`、金额 `DECIMAL(18,2)`**（`§14.2`），禁止 `FLOAT`/`DOUBLE`。
- 表名复数，字段名单数（`§4.1`）。

---

## 2. 命名前缀与后缀约定（引用并展开 `DESIGN_BASIS §4.1`）

### 2.1 表名与字段名

| 对象 | 规则 | 示例 | 展开说明 |
| --- | --- | --- | --- |
| 表名 | 全小写 + 下划线 + **复数** | `purchase_orders`、`analysis_items` | 复数表示"该表存一类实体集合"；不可数名词（`importdata` 作为模块名非表名）不适用 |
| 字段名 | 全小写 + 下划线 + **单数** | `sku_id`、`created_at` | — |
| 主键 | 统一 `id` | `id` | 全库同名，便于通用基础设施（审计、租户插件、乐观锁）统一处理 |
| 外键字段 | `<被引用表单数>_id` | `team_id`、`sku_id`、`purchase_order_id` | **单数 + `_id`**；与表名复数形成"表复数、列单数"的稳定对照 |
| 时间戳 | 后缀 `_at` | `created_at`、`confirmed_at` | 类型一律 `DATETIME(3)`（UTC） |
| 业务日期 | 后缀 `_date` | `sale_date`、`expected_arrival_date` | 类型一律 `DATE`，**不做时区转换** |
| 布尔 | 前缀 `is_` / `has_`，`TINYINT(1)` | `is_default`、`is_enabled` | 本期无 `has_` 开头字段（预留） |
| 状态 | 后缀 `_status` | `llm_status` | `analysis_runs.status` / `import_batches.status` 等用裸 `status` 亦可，但**必须成对定义枚举**（`§4.1`） |
| 数量 | 后缀 `_qty` | `on_hand_qty`、`ordered_qty` | 类型一律 `DECIMAL(18,3)` |
| 金额 | 后缀 `_amount` / `_price` | `total_amount`、`unit_price` | 金额 `_amount` = `DECIMAL(18,2)`；单价 `_price` = `DECIMAL(18,4)` |
| 比率 | 后缀按含义（`cv`、`safety_ratio`） | `cv`、`safety_ratio` | 类型 `DECIMAL(18,4)` |
| 编号 | 后缀 `_no` / `_code` | `po_no`、`sku_code` | `_no` = 系统生成的业务单号；`_code` = 用户可维护的编码 |
| 哈希 | 后缀 `_hash` | `file_hash`、`dedupe_hash` | `CHAR(64)`（SHA-256 十六进制） |

### 2.2 索引与约束命名

| 对象 | 规则 | 示例 |
| --- | --- | --- |
| 普通/联合索引 | `idx_<表简写>_<字段...>` | `idx_sales_team_sku_date` |
| 唯一约束 | `uk_<表简写>_<字段...>` | `uk_sku_team_code` |
| 外键 | `fk_<表简写>_<字段>` | `fk_sku_product` |
| CHECK | `ck_<表简写>_<字段>` | `ck_invbal_on_hand_non_negative` |

**表简写对照**（全文一致）：

| 表 | 简写 | 表 | 简写 |
| --- | --- | --- | --- |
| `users` | `users` | `import_row_errors` | `ire` |
| `teams` | `teams` | `analysis_runs` | `run` |
| `team_members` | `tm` | `analysis_items` | `ai` |
| `warehouses` | `wh` | `replenishment_items` | `repl` |
| `suppliers` | `sup` | `replenishment_reviews` | `rr` |
| `products` | `prod` | `purchase_orders` | `po` |
| `skus` | `sku` | `purchase_order_items` | `poi` |
| `replenishment_policies` | `pol` | `goods_receipts` | `gr` |
| `inventory_balances` | `invbal` | `goods_receipt_items` | `gri` |
| `inventory_transactions` | `invtxn` | `audit_logs` | `al` |
| `sales_daily` | `sales` | `import_batches` | `imp` |

---

## 3. 类型使用规范

| 类型 | 使用场景 | 取值/长度理由 |
| --- | --- | --- |
| `BIGINT UNSIGNED` | 主键、全部 `*_id` 外键、`*_by` 用户引用、`file_size` | ① 无符号：ID 永不为负，取值上限 1.8×10^19，远超单团队数据量；② `BIGINT` 而非 `INT`：避免未来分库/合并时的类型迁移；③ 与 Java `Long` 精确对应 |
| `VARCHAR(16)` | `error_level`、`result` | 固定短常量（`ERROR`/`WARNING`、`SUCCESS`/`FAILURE`），留余量但不浪费 |
| `VARCHAR(32)` | **状态枚举**、`biz_type`、`source`、`role`、`lead_time_source` | `§4.1` 规定枚举用 `VARCHAR(32)` 存**大写常量字符串**。取值最长 `PARTIALLY_RECEIVED`（18 字符）、`VALIDATION_FAILED`（17）、`INVENTORY_SNAPSHOT`（18）。**32 留 1.7 倍余量**：既保证短前缀索引效率，又不会因新增枚举值溢出 |
| `VARCHAR(64)` | 业务编号（`batch_no`/`po_no`/`receipt_no`/`run_no`）、`*_code`、`request_id`、`ip`、`batch_no`、`template_version` | 编号 = 团队前缀 + 日期 + 序列（如 `PO-20261014-000123`，约 20 字符）。64 足以容纳最长的"团队编码 + 时间戳 + 序列 + 分隔符"组合 |
| `CHAR(64)` | `file_hash`、`dedupe_hash` | SHA-256 十六进制**定长 64**，用 `CHAR` 而非 `VARCHAR`（定长无长度前缀开销，且不可能变长） |
| `VARCHAR(128)` | `name`（供应商/仓库/团队）、`email`、`idempotency_key`、`model_name`、`error_message`(见 500 说明) | `idempotency_key` 由 `<txn_type>:<biz_type>:<biz_ref_id>:<sku_id>` 构成，最长约 80 字符；128 留有扩展空间。`email` 遵循 RFC 实践上限 128 |
| `VARCHAR(200)` | `products.name`、`skus.name` | 电商商品名常见 20~80 字符（含中英文混合），200 留足余量（中文按字符计，`utf8mb4` 下单字符最长 4 字节） |
| `VARCHAR(255)` | `address`、`remark`、`storage_path`(512 见下)、`user_agent`、`can = 255`（`reason`/`cancel_reason`/`spec_summary`） | 自由文本的"短备注"上限；不做 `TEXT` 以免影响行内存储与索引 |
| `VARCHAR(500)` | `error_message`、`llm_error_message`、`reason`（建议理由）、`remark`（建议/采购单）、`comment` | 可读错误说明需要较完整上下文（含字段名与期望值）；500 为"人能读懂一段话"的经验上限 |
| `VARCHAR(512)` | `storage_path` | 文件系统路径（含 bucket/租户前缀/日期分区/文件名），512 覆盖主流 OS 路径上限（Windows 260 字符 + URL 编码余量） |
| `VARCHAR(1000)` | `change_summary` | 变更摘要可能含多字段的"旧值 → 新值"拼接 |
| `TEXT` | `llm_explanation` | LLM 解释文本长度不可预测（可达数千字符），必须用 `TEXT`（长文本不参与索引，置于行外存储） |
| `DECIMAL(18,3)` | **全部数量**（库存/销售/采购/到货/建议量） | `DESIGN_BASIS §14.2` 明确：支持拆零到 0.001（电商可拆分销售）。18 位整数部分远超任何数量场景 |
| `DECIMAL(18,4)` | **单价**、比率（`cv`、`safety_ratio`） | `§14.2`：保留采购单价精度 4 位；比率的计算中间量保留 4 位（P12 可复现） |
| `DECIMAL(18,2)` | **金额**（`total_amount`、`amount`、`sales_amount`） | `§14.2`：金额仅两位小数，避免浮点误差。**禁止 `FLOAT`/`DOUBLE`** |
| `INT UNSIGNED` | 天数（`lead_time_days`）、行数、行号、步长、`progress`(见 `TINYINT`) | 天数/行数不会为负；上限 42 亿远超需求 |
| `TINYINT UNSIGNED` | `progress` | 取值域 0–100，`TINYINT UNSIGNED`（0–255）精确匹配 |
| `TINYINT(1)` | **布尔**（`is_default`、`is_enabled`、`volatility_adjusted`、`truncated_by_budget`） | `§4.1` 规定布尔用 `TINYINT(1)`，命名前缀 `is_` |
| `DATE` | **业务日期**（`sale_date`、`receipt_date`、`listed_date`、`expected_arrival_date`、`data_start_date`、`data_end_date`） | `§14.1`：代表团队时区下的自然日，**不做时区转换** |
| `DATETIME(3)` | **全部时间戳**（`*_at`） | `§14.1`：毫秒精度（3 位小数），**存 UTC**；数据库不存时区信息 |
| `JSON` | 结构化参数/摘要/快照/多值（`params_json`、`scope_json`、`summary_json`、`risk_types`、`risk_detail_json`、`data_quality_json`、`spec_json`、`field_mapping_json`、`parse_options_json`、`raw_data`、`before_json`、`after_json`、`converted_po_item_ids`） | 判定原则（J1–J4）见 `DATABASE_DESIGN.md §6-F.3`：**需要筛选/排序/聚合/约束/JOIN 的字段绝不放 JSON** |

> **不使用 `FLOAT` / `DOUBLE` 存金额与数量**（`§14.2` 硬约束）。Java 侧 `BigDecimal`，Python 侧 `Decimal`。

---

## 4. 公共审计字段约定

### 4.1 三种字段集合

| 集合 | 字段 | 出现的表（数量） |
| --- | --- | --- |
| **A：可变业务表** | `created_at`、`updated_at`、`created_by`、`updated_by` | 16 张：`users`、`teams`、`team_members`、`warehouses`、`suppliers`、`products`、`skus`、`replenishment_policies`、`inventory_balances`、`sales_daily`、`import_batches`、`analysis_runs`、`replenishment_items`、`purchase_orders`、`purchase_order_items`、`goods_receipts` |
| **B：仅追加表** | `created_at`、`created_by` | 6 张：`inventory_transactions`、`import_row_errors`、`analysis_items`、`replenishment_reviews`、`goods_receipt_items`、`audit_logs` |
| **`version`（乐观锁）** | `version INT UNSIGNED NOT NULL DEFAULT 0` | **12 张**：`users`、`teams`、`warehouses`、`suppliers`、`products`、`skus`、`replenishment_policies`、`inventory_balances`、`replenishment_items`、`purchase_orders`、`purchase_order_items`、`goods_receipts` |

### 4.2 `version` 出现与否的判定依据

- **出现**（12 张）：命中 `V1` 读-改-写 / `V2` 多方并发改同行 / `V3` 丢失更新后果不可逆 / `V4` 更新不经唯一约束（详见 `DATABASE_DESIGN.md §7.4`）。
- **不出现**（10 张）：`inventory_transactions`、`analysis_items`、`import_row_errors`、`replenishment_reviews`、`audit_logs`（**仅 INSERT，不可变**）；`goods_receipt_items`（确认后不可变 + 唯一约束足够）；`sales_daily`（**upsert 覆盖语义**，不存在"丢失更新"）；`team_members`（唯一约束 `uk_tm_team_user` 保证一行）；`analysis_runs`、`import_batches`（**状态守卫**即 CAS，等价于乐观锁）。
- **明确原则**：**不机械地为所有表加 `version`**。仅追加表与靠唯一约束/状态守卫即可保证正确的表不加，避免无意义的写放大与维护成本。

### 4.3 公共字段行（各表不再重复列出，按集合引用）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | UTC；由数据库填充，应用层不得覆盖 |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | UTC；仅**集合 A** 出现 |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 系统任务为 `NULL` |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 仅**集合 A** 出现 |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | 仅 §4.1 列出的 12 张表出现；每次 UPDATE 必须 `version = version + 1` 且 `WHERE version = ?` |

---

## 5. 逐表字段字典（22 张）

---

### 5.1 `users`（用户）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `username` | 登录名 | `VARCHAR(64)` | 否 | — | `UK:uk_users_username` | 全局唯一；**无 team_id**（`§6` 规则 1 例外） |
| `email` | 邮箱 | `VARCHAR(128)` | 是 | `NULL` | `UK:uk_users_email` | 唯一索引允许多个 `NULL` |
| `password_hash` | 口令哈希 | `VARCHAR(255)` | 否 | — | — | BCrypt；**禁止进入审计日志**（`§6-J` 脱敏） |
| `display_name` | 显示名 | `VARCHAR(64)` | 是 | `NULL` | — | — |
| `phone` | 手机号 | `VARCHAR(32)` | 是 | `NULL` | — | 审计中需掩码 |
| `status` | 用户状态 | `VARCHAR(32)` | 否 | `'ACTIVE'` | `CK:ck_users_status` | `UserStatus`（`§9.1`）：`ACTIVE`/`DISABLED` |
| `last_login_at` | 最近登录时间 | `DATETIME(3)` | 是 | `NULL` | — | UTC |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | — |

**索引**：`idx_users_status (status)`。

---

### 5.2 `teams`（团队）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `code` | 团队编码 | `VARCHAR(32)` | 否 | — | `UK:uk_teams_code` | **不可变**（对外标识） |
| `name` | 团队名称 | `VARCHAR(128)` | 否 | — | — | — |
| `status` | 团队状态 | `VARCHAR(32)` | 否 | `'ACTIVE'` | `CK:ck_teams_status` | `TeamStatus`（`§9.2`）：`ACTIVE`/`DISABLED`；`DISABLED` 后接口返回 `TEAM-2002` |
| `default_timezone` | 默认时区 | `VARCHAR(64)` | 否 | `'Asia/Shanghai'` | — | `§14.1`；前端按此渲染时间 |
| `default_warehouse_id` | 默认仓库 | `BIGINT UNSIGNED` | 是 | `NULL` | **逻辑关联** `warehouses.id` | 不建外键（避免环形外键，见 `ER_DIAGRAM.md §4.1`） |
| `contact_name` | 联系人 | `VARCHAR(64)` | 是 | `NULL` | — | — |
| `contact_phone` | 联系电话 | `VARCHAR(32)` | 是 | `NULL` | — | — |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | — |

**索引**：`idx_teams_status (status)`。

---

### 5.3 `team_members`（团队成员）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `FK:teams(id)`、`UK:uk_tm_team_user`(前缀)、`IDX` | **不可变**；`§6` 规则 1 |
| `user_id` | 用户 ID | `BIGINT UNSIGNED` | 否 | — | `FK:users(id)`、`UK:uk_tm_team_user`(后缀)、`IDX` | **不可变** |
| `role` | 团队内角色 | `VARCHAR(32)` | 否 | `'VIEWER'` | `CK:ck_tm_role`、`IDX` | `TeamRole`（`§9.3`）：`OWNER`/`ADMIN`/`OPERATOR`/`APPROVER`/`VIEWER` |
| `status` | 成员状态 | `VARCHAR(32)` | 否 | `'ACTIVE'` | `CK:ck_tm_status` | `ACTIVE`/`REMOVED`（`§9.3`） |
| `joined_at` | 加入时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 不可变 |
| `removed_at` | 移出时间 | `DATETIME(3)` | 是 | `NULL` | — | 移出时写入；复活时清空 |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |

**索引**：`uk_tm_team_user (team_id, user_id)`、`idx_tm_team_role (team_id, role)`、`idx_tm_user_status (user_id, status)`。
**无 `version`**：唯一约束保证一人一团队一行，状态翻转幂等。

---

### 5.4 `warehouses`（仓库）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_wh_team_code`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `code` | 仓库编码 | `VARCHAR(32)` | 否 | — | `UK:uk_wh_team_code`(后缀) | 团队内唯一 |
| `name` | 仓库名称 | `VARCHAR(128)` | 否 | — | — | — |
| `status` | 状态 | `VARCHAR(32)` | 否 | `'ACTIVE'` | `CK:ck_wh_status`、`IDX` | `MasterDataStatus`（`§9.4`）：`ACTIVE`/`INACTIVE` |
| `is_default` | 是否默认仓 | `TINYINT(1)` | 否 | `0` | `IDX` | 同团队至多一行为 1（服务层保证，不用唯一索引） |
| `address` | 地址 | `VARCHAR(255)` | 是 | `NULL` | — | — |
| `contact_name` | 联系人 | `VARCHAR(64)` | 是 | `NULL` | — | — |
| `contact_phone` | 联系电话 | `VARCHAR(32)` | 是 | `NULL` | — | — |
| `remark` | 备注 | `VARCHAR(255)` | 是 | `NULL` | — | — |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | — |

**索引**：`uk_wh_team_code (team_id, code)`、`idx_wh_team_status (team_id, status)`、`idx_wh_team_default (team_id, is_default)`。

---

### 5.5 `suppliers`（供应商）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_sup_team_code`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `code` | 供应商编码 | `VARCHAR(32)` | 否 | — | `UK:uk_sup_team_code`(后缀) | 团队内唯一 |
| `name` | 供应商名称 | `VARCHAR(128)` | 否 | — | — | — |
| `status` | 状态 | `VARCHAR(32)` | 否 | `'ACTIVE'` | `CK:ck_sup_status`、`IDX` | `MasterDataStatus` |
| `contact_name` | 联系人 | `VARCHAR(64)` | 是 | `NULL` | — | — |
| `contact_phone` | 联系电话 | `VARCHAR(32)` | 是 | `NULL` | — | — |
| `email` | 邮箱 | `VARCHAR(128)` | 是 | `NULL` | — | — |
| `address` | 地址 | `VARCHAR(255)` | 是 | `NULL` | — | — |
| `lead_time_days` | 供应商级交期 `L` | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_sup_lead_time` | `NULL` = 未配置（触发回退）。**不可用 0 表示未配置** |
| `moq` | 供应商级最小起订量 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_sup_moq` | `NULL` = 未配置 |
| `order_multiple` | 供应商级采购倍数 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_sup_multiple` | `NULL` = 未配置 |
| `payment_terms` | 付款条件 | `VARCHAR(64)` | 是 | `NULL` | — | 仅展示，不参与计算 |
| `remark` | 备注 | `VARCHAR(255)` | 是 | `NULL` | — | — |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | — |

**索引**：`uk_sup_team_code (team_id, code)`、`idx_sup_team_status (team_id, status)`。

---

### 5.6 `products`（商品）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_prod_team_code`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `product_code` | 商品编码 | `VARCHAR(64)` | 否 | — | `UK:uk_prod_team_code`(后缀) | 团队内唯一 |
| `name` | 商品名称 | `VARCHAR(200)` | 否 | — | — | — |
| `category` | 类目 | `VARCHAR(64)` | 是 | `NULL` | `IDX` | 支撑 `AnalysisScopeType.CATEGORY`（`§9.16`） |
| `brand` | 品牌 | `VARCHAR(64)` | 是 | `NULL` | — | — |
| `unit` | 计量单位 | `VARCHAR(16)` | 是 | `NULL` | — | — |
| `status` | 状态 | `VARCHAR(32)` | 否 | `'ACTIVE'` | `CK:ck_prod_status`、`IDX` | `MasterDataStatus` |
| `default_supplier_id` | 默认供应商 | `BIGINT UNSIGNED` | 是 | `NULL` | `FK:suppliers(id)`、`IDX` | 须同团队 |
| `spec_summary` | 规格摘要 | `VARCHAR(255)` | 是 | `NULL` | — | 展示用 |
| `remark` | 备注 | `VARCHAR(255)` | 是 | `NULL` | — | — |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | — |

**索引**：`uk_prod_team_code (team_id, product_code)`、`idx_prod_team_status (team_id, status)`、`idx_prod_team_category (team_id, category, status)`、`idx_prod_team_supplier (team_id, default_supplier_id)`。

---

### 5.7 `skus`（SKU）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_sku_team_code`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `product_id` | 所属商品 | `BIGINT UNSIGNED` | 否 | — | `FK:products(id)`、`IDX` | **不可变**（换商品须新建 SKU，§6-B） |
| `sku_code` | SKU 编码 | `VARCHAR(64)` | 否 | — | `UK:uk_sku_team_code`(后缀) | 团队内唯一；**跨团队可重复** |
| `name` | SKU 展示名 | `VARCHAR(200)` | 是 | `NULL` | — | 空则用 商品名 + 规格 |
| `barcode` | 条码 | `VARCHAR(64)` | 是 | `NULL` | — | — |
| `spec_json` | 规格键值 | `JSON` | 是 | `NULL` | — | 颜色/尺码等；结构不定（J3） |
| `status` | 状态 | `VARCHAR(32)` | 否 | `'ACTIVE'` | `CK:ck_sku_status`、`IDX` | `MasterDataStatus`；`INACTIVE` 不参与新分析与采购 |
| `supplier_id` | SKU 级供应商 | `BIGINT UNSIGNED` | 是 | `NULL` | `FK:suppliers(id)`、`IDX` | 须同团队；`L`/MOQ 二级来源 |
| `default_lead_time_days` | SKU 级交期 `L` | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_sku_lead_time` | `NULL` = 未配置 |
| `moq` | SKU 级最小起订量 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_sku_moq` | `NULL` = 未配置 |
| `order_multiple` | SKU 级采购倍数 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_sku_multiple` | `NULL` = 未配置 |
| `qty_step` | 最小起订精度 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_sku_qty_step` | `§10.5` |
| `purchase_price` | 参考采购单价 | `DECIMAL(18,4)` | 是 | `NULL` | `CK:ck_sku_purchase_price` | 精度 4 位（`§14.2`） |
| `sale_price` | 参考售价 | `DECIMAL(18,4)` | 是 | `NULL` | `CK:ck_sku_sale_price` | — |
| `weight_gram` | 重量（克） | `DECIMAL(18,3)` | 是 | `NULL` | — | 首期仅记录 |
| `listed_date` | 上架日期 | `DATE` | 是 | `NULL` | — | `R-SLOWMOVING` 的输入（`§11`） |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | — |

**索引**：`uk_sku_team_code (team_id, sku_code)`、`idx_sku_team_product (team_id, product_id, status)`、`idx_sku_team_status (team_id, status)`、`idx_sku_team_supplier (team_id, supplier_id)`。

---

### 5.8 `replenishment_policies`（补货策略参数）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_pol_team_sku_wh`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `sku_id` | SKU ID | `BIGINT UNSIGNED` | 否 | — | `FK:skus(id)`、`UK:uk_pol_team_sku_wh` | **不可变** |
| `warehouse_id` | 仓库 ID | `BIGINT UNSIGNED` | 否 | — | `FK:warehouses(id)`、`UK:uk_pol_team_sku_wh`、`IDX` | **不可变** |
| `lead_time_days` | 交期 `L` | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_pol_lead_time` | 策略级第一来源（`§10.2`） |
| `coverage_days` | 覆盖天数 `R` | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_pol_coverage` | `NULL` 时取默认 7（`§10.6`） |
| `safety_stock_qty` | 安全库存绝对值 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_pol_safety_qty` | 优先于公式（`§10.3`） |
| `safety_ratio` | `SAFETY_RATIO` | `DECIMAL(18,4)` | 是 | `NULL` | `CK:ck_pol_safety_ratio` | `NULL` 时取默认 0.5 |
| `moq` | 最小起订量 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_pol_moq` | 策略级第一来源 |
| `order_multiple` | 采购倍数 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_pol_multiple` | 策略级第一来源 |
| `qty_step` | 数量精度步长 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_pol_qty_step` | `§10.5` |
| `window_days` | 统计窗口 `N` | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_pol_window` | `NULL` 时取默认 28 |
| `min_days` | 最少有效天数 | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_pol_min_days` | `NULL` 时取默认 7 |
| `cv_high` | 高波动阈值 | `DECIMAL(18,4)` | 是 | `NULL` | `CK:ck_pol_cv_high` | `NULL` 时取默认 1.0 |
| `slow_moving_days` | 滞销判定窗口 | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_pol_slow` | `NULL` 时取默认 30 |
| `overstock_days` | 过高库存窗口 | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_pol_overstock` | `NULL` 时取默认 90 |
| `is_enabled` | 是否启用 | `TINYINT(1)` | 否 | `1` | `IDX` | 停用走 0，不删除 |
| `remark` | 备注 | `VARCHAR(255)` | 是 | `NULL` | — | — |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | — |

**索引**：`uk_pol_team_sku_wh (team_id, sku_id, warehouse_id)`、`idx_pol_team_sku (team_id, sku_id, is_enabled)`、`idx_pol_team_wh (team_id, warehouse_id)`。

---

### 5.9 `inventory_balances`（库存余额）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_invbal_team_wh_sku`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `warehouse_id` | 仓库 ID | `BIGINT UNSIGNED` | 否 | — | `FK:warehouses(id)`、`UK:uk_invbal_team_wh_sku`、`IDX` | **不可变** |
| `sku_id` | SKU ID | `BIGINT UNSIGNED` | 否 | — | `FK:skus(id)`、`UK:uk_invbal_team_wh_sku`、`IDX` | **不可变** |
| `on_hand_qty` | 现有库存 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_invbal_on_hand_non_negative` | 由流水驱动；`§3`：已实际在仓可出库 |
| `in_transit_qty` | 在途库存 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_invbal_in_transit_non_negative` | 由采购单状态驱动；**无对应流水** |
| `committed_qty` | 已承诺库存 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_invbal_committed_non_negative` | 首期默认 0，仅保留写入通道 |
| `available_qty` | 可用库存 | `DECIMAL(18,3)` | 否 | 生成列 | `IDX`、**生成列** | `on_hand_qty − committed_qty`（`§3`，**不含在途**）；**仅展示，禁止参与补货判断** |
| `last_txn_id` | 最近流水 ID | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `inventory_transactions.id` | 写流水时同步更新；对账定位用 |
| `last_txn_at` | 最近流水时间 | `DATETIME(3)` | 是 | `NULL` | — | UTC |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | **核心并发控制字段**（§6-C） |

**索引**：`uk_invbal_team_wh_sku (team_id, warehouse_id, sku_id)`、`idx_invbal_team_sku (team_id, sku_id)`、`idx_invbal_team_available (team_id, available_qty)`。

---

### 5.10 `inventory_transactions`（库存流水）

> **本表写入后永不 UPDATE / DELETE**（集合 B，无 `updated_at`、无 `version`）。

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_invtxn_team_idem`(前缀)、`IDX`、逻辑关联 `teams.id` | 不可变 |
| `warehouse_id` | 仓库 ID | `BIGINT UNSIGNED` | 否 | — | `FK:warehouses(id)`、`IDX` | 不可变 |
| `sku_id` | SKU ID | `BIGINT UNSIGNED` | 否 | — | `FK:skus(id)`、`IDX` | 不可变 |
| `txn_type` | 流水类型 | `VARCHAR(32)` | 否 | — | `CK:ck_invtxn_type`、`IDX` | `InventoryTxnType`（`§9.6`）：`INIT_IMPORT`/`PURCHASE_INBOUND`/`SALE_OUTBOUND`/`COUNT_ADJUST`/`MANUAL_ADJUST`/`REVERSAL` |
| `qty_delta` | 变更量 | `DECIMAL(18,3)` | 否 | — | `CK:ck_invtxn_delta_non_zero` | 带符号（`+` / `−`）；不得为 0 |
| `qty_before` | 变更前数量 | `DECIMAL(18,3)` | 否 | — | `CK:ck_invtxn_before_non_negative` | 与余额当时的 `on_hand_qty` 一致 |
| `qty_after` | 变更后数量 | `DECIMAL(18,3)` | 否 | — | `CK:ck_invtxn_after_non_negative` | 与余额更新后的 `on_hand_qty` 一致 |
| `biz_type` | 来源单据类型 | `VARCHAR(32)` | 否 | — | `CK:ck_invtxn_biz_type`、`IDX` | `GOODS_RECEIPT`/`COUNT`/`MANUAL`/`IMPORT`/`SALE`/`REVERSAL`（见 `D-14`） |
| `biz_ref_id` | 来源单据 ID | `BIGINT UNSIGNED` | 是 | `NULL` | `IDX`、**逻辑关联（多态）** | 目标表由 `biz_type` 决定，无法建外键 |
| `biz_ref_no` | 来源单据编号 | `VARCHAR(64)` | 是 | `NULL` | — | 冗余，免 JOIN 展示 |
| `idempotency_key` | 幂等键 | `VARCHAR(128)` | 否 | — | `UK:uk_invtxn_team_idem`(后缀) | `<txn_type>:<biz_type>:<biz_ref_id>:<sku_id>[:<suffix>]`；**禁止用 UUID** |
| `reason` | 变更原因 | `VARCHAR(255)` | 是 | `NULL` | — | `COUNT_ADJUST` / `MANUAL_ADJUST` 必填 |
| `reversed_txn_id` | 被冲销原流水 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `inventory_transactions.id` | 仅 `REVERSAL` 有值；建议加唯一索引（`D-13`） |
| `operator_id` | 操作人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 系统任务为 `NULL` |
| `occurred_at` | 业务发生时间 | `DATETIME(3)` | 否 | — | `IDX` | UTC |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 B |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 集合 B |

**CK 补充**：`ck_invtxn_consistency`：`qty_after = qty_before + qty_delta`。
**索引**：`uk_invtxn_team_idem (team_id, idempotency_key)`、`idx_invtxn_team_wh_sku_time (team_id, warehouse_id, sku_id, id)`、`idx_invtxn_team_biz (team_id, biz_type, biz_ref_id)`、`idx_invtxn_team_occurred (team_id, occurred_at)`。

---

### 5.11 `sales_daily`（日销售）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_sales_team_wh_sku_date`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `warehouse_id` | 仓库 ID | `BIGINT UNSIGNED` | 否 | — | `FK:warehouses(id)`、`UK`、`IDX` | **不可变** |
| `sku_id` | SKU ID | `BIGINT UNSIGNED` | 否 | — | `FK:skus(id)`、`UK`、`IDX` | **不可变** |
| `product_id` | 商品 ID（冗余） | `BIGINT UNSIGNED` | 否 | — | **逻辑关联** `products.id`、`IDX` | 冗余；由不可变 `skus.product_id` 保证安全（§6-B.4） |
| `sale_date` | 销售日期 | `DATE` | 否 | — | `UK`、`IDX` | 团队时区自然日，**不做时区转换** |
| `qty` | 净销售数量 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_sales_qty_non_negative` | **upsert 覆盖**（非累加，§6-D） |
| `return_qty` | 退货数量 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_sales_return_non_negative` | upsert 覆盖 |
| `sales_amount` | 销售金额 | `DECIMAL(18,2)` | 是 | `NULL` | `CK:ck_sales_amount_non_negative` | 金额两位小数 |
| `order_count` | 订单数 | `INT UNSIGNED` | 是 | `NULL` | — | 可选字段 |
| `source` | 数据来源 | `VARCHAR(32)` | 否 | `'IMPORT'` | `CK:ck_sales_source` | `IMPORT`/`MANUAL`/`API`；**仅诊断，不参与统计口径** |
| `batch_id` | 来源导入批次 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `import_batches.id`、`IDX` | upsert 覆盖时更新为最新批次 |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |

**索引**：`uk_sales_team_wh_sku_date (team_id, warehouse_id, sku_id, sale_date)`、`idx_sales_team_sku_date (team_id, sku_id, sale_date)`、`idx_sales_team_date (team_id, sale_date)`、`idx_sales_team_product_date (team_id, product_id, sale_date)`、`idx_sales_team_batch (team_id, batch_id)`。
**无 `version`**：upsert 覆盖是**设计意图**，不存在"丢失更新"。

---

### 5.12 `import_batches`（导入批次）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_imp_team_hash_biz`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `batch_no` | 批次号 | `VARCHAR(64)` | 否 | — | — | **不可变** |
| `biz_type` | 业务类型 | `VARCHAR(32)` | 否 | — | `UK:uk_imp_team_hash_biz`、`CK:ck_imp_biz_type`、`IDX` | `ImportBizType`（`§9.15`）：`PRODUCT`/`SKU`/`SUPPLIER`/`WAREHOUSE`/`INVENTORY_SNAPSHOT`/`SALES_DAILY` |
| `file_name` | 文件名 | `VARCHAR(255)` | 否 | — | — | **仅展示，不作幂等依据**（§6-E.5） |
| `file_hash` | 文件内容 SHA-256 | `CHAR(64)` | 否 | — | `IDX` | 十六进制小写；**不可变**；幂等的真实依据 |
| `dedupe_hash` | 幂等槽位 | `CHAR(64)` | 是 | `NULL` | `UK:uk_imp_team_hash_biz` | 占用时 = `file_hash`；释放时 = `NULL`（规则见 §6-E.4）⚠️ 见 `INC-1` / `D-09` |
| `file_size` | 文件字节数 | `BIGINT UNSIGNED` | 是 | `NULL` | — | — |
| `storage_path` | 文件存储路径 | `VARCHAR(512)` | 是 | `NULL` | — | 原始文件是可重新下载的唯一真相 |
| `status` | 批次状态 | `VARCHAR(32)` | 否 | `'UPLOADED'` | `CK:ck_imp_status`、`IDX` | `ImportBatchStatus`（`§9.5`，10 值） |
| `total_rows` | 数据行总数 | `INT UNSIGNED` | 否 | `0` | `CK:ck_imp_total_rows` | `PARSED` 后固定 |
| `success_rows` | 成功行数 | `INT UNSIGNED` | 否 | `0` | `CK:ck_imp_success_rows` | 分批累加 |
| `failed_rows` | 失败行数 | `INT UNSIGNED` | 否 | `0` | `CK:ck_imp_failed_rows` | 分批累加 |
| `attempt_no` | 尝试序号 | `INT UNSIGNED` | 否 | `1` | `CK:ck_imp_attempt_no` | `FORCE_REIMPORT` 时递增 |
| `field_mapping_json` | 字段映射 | `JSON` | 是 | `NULL` | — | `MAPPED` 后不可变 |
| `parse_options_json` | 解析选项 | `JSON` | 是 | `NULL` | — | 工作表/表头行/编码/日期格式 |
| `template_version` | 模板版本 | `VARCHAR(32)` | 是 | `NULL` | — | 诊断用 |
| `error_code` | 批次级错误码 | `VARCHAR(32)` | 是 | `NULL` | — | `IMP-xxxx` |
| `error_message` | 批次级错误说明 | `VARCHAR(500)` | 是 | `NULL` | — | — |
| `duplicate_of_batch_id` | 重复来源批次 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `import_batches.id` | `DUPLICATE_REJECTED` 时写入 |
| `superseded_by_batch_id` | 被取代为批次 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `import_batches.id` | `FORCE_REIMPORT` 时写入 |
| `started_at` | 开始落库时间 | `DATETIME(3)` | 是 | `NULL` | — | — |
| `finished_at` | 结束时间 | `DATETIME(3)` | 是 | `NULL` | — | 终态时写入 |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 上传人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |

**索引**：`uk_imp_team_hash_biz (team_id, dedupe_hash, biz_type)`、`idx_imp_team_status_time (team_id, status, created_at)`、`idx_imp_team_biz_time (team_id, biz_type, created_at)`、`idx_imp_team_file_hash (team_id, file_hash)`。
**无 `version`**：状态守卫 + 同批次单执行者。

---

### 5.13 `import_row_errors`（导入行错误）

> **本表仅追加**（集合 B）。

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `IDX`、逻辑关联 `teams.id` | 不可变 |
| `batch_id` | 所属批次 | `BIGINT UNSIGNED` | 否 | — | `FK:import_batches(id)`、`IDX` | 不可变 |
| `row_no` | 数据行号 | `INT UNSIGNED` | 否 | — | `IDX`、`CK:ck_ire_row_no` | 1 起，不含表头 |
| `error_level` | 错误级别 | `VARCHAR(16)` | 否 | `'ERROR'` | `CK:ck_ire_level`、`IDX` | `ERROR`（阻断该行）/ `WARNING`（仅提示） |
| `error_code` | 错误码 | `VARCHAR(32)` | 否 | — | `IDX` | `IMP-4003` / `CAT-3002` / `COM-0001` … |
| `error_field` | 出错字段 | `VARCHAR(64)` | 是 | `NULL` | — | 多字段错误写首个 |
| `error_message` | 错误说明 | `VARCHAR(500)` | 否 | — | — | 中文可读 |
| `raw_data` | 原始行数据 | `JSON` | 是 | `NULL` | — | **仅失败行**（§6-E.2）；成功行不冗余存原文 |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 B |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 集合 B |

**索引**：`idx_ire_team_batch_row (team_id, batch_id, row_no)`、`idx_ire_team_code (team_id, error_code)`、`idx_ire_team_level (team_id, batch_id, error_level)`。
**无唯一约束**：同一行可多条不同错误（`§8`）。

---

### 5.14 `analysis_runs`（分析任务）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键（业务 ID） | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 由 Java 生成后传给 Python（`§13.2`）；不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_run_team_no`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `run_no` | 运行编号 | `VARCHAR(64)` | 否 | — | `UK:uk_run_team_no`(后缀) | **不可变** |
| `status` | 运行状态 | `VARCHAR(32)` | 否 | `'PENDING'` | `CK:ck_run_status`、`IDX` | `AnalysisRunStatus`（`§9.7`，7 值） |
| `scope_type` | 范围类型 | `VARCHAR(32)` | 否 | — | `CK:ck_run_scope_type` | `AnalysisScopeType`（`§9.16`）：`ALL_ACTIVE`/`SELECTED_SKUS`/`CATEGORY` |
| `scope_json` | 范围明细 | `JSON` | 是 | `NULL` | — | SKU 列表 / 类目列表；不可变 |
| `warehouse_id` | 限定仓库 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `warehouses.id` | `NULL` = 默认仓；不可变 |
| `params_json` | 运行参数 | `JSON` | 是 | `NULL` | — | `window_days`/`min_days`/`budget_limit`…（`§10.6`）；不可变 |
| `algorithm_version` | 算法版本 | `VARCHAR(64)` | 否 | — | — | 如 `avg-ma-3sigma-v1`（`§10.1`）；不可变 |
| `prompt_version` | Prompt 版本 | `VARCHAR(64)` | 是 | `NULL` | — | 不可变 |
| `model_name` | 模型名 | `VARCHAR(128)` | 是 | `NULL` | — | **不写密钥**；不可变 |
| `llm_status` | LLM 状态 | `VARCHAR(32)` | 否 | `'NOT_REQUESTED'` | `CK:ck_run_llm_status` | `LlmStatus`（`§9.8`，7 值） |
| `llm_error_message` | LLM 失败原因 | `VARCHAR(500)` | 是 | `NULL` | — | `DEGRADED` 时非空 |
| `data_start_date` | 输入数据起始 | `DATE` | 是 | `NULL` | — | 执行期写入后固定 |
| `data_end_date` | 输入数据结束 | `DATE` | 是 | `NULL` | — | 同上 |
| `summary_json` | 结果摘要 | `JSON` | 是 | `NULL` | — | 各风险等级计数、建议数、截断数 |
| `total_skus` | 计划 SKU 数 | `INT UNSIGNED` | 否 | `0` | `CK:ck_run_total_skus` | 执行期更新 |
| `processed_skus` | 已完成 SKU 数 | `INT UNSIGNED` | 否 | `0` | `CK:ck_run_processed_skus` | 执行期更新 |
| `risk_sku_count` | 风险 SKU 数 | `INT UNSIGNED` | 否 | `0` | `CK:ck_run_risk_count` | 终态写入 |
| `suggestion_count` | 生成建议数 | `INT UNSIGNED` | 否 | `0` | `CK:ck_run_suggestion_count` | 终态写入 |
| `progress` | 进度 | `TINYINT UNSIGNED` | 否 | `0` | `CK:ck_run_progress` | 0–100（`§9.7`） |
| `current_step` | 当前步骤 | `VARCHAR(64)` | 是 | `NULL` | — | 执行期更新 |
| `error_code` | 错误码 | `VARCHAR(32)` | 是 | `NULL` | — | `ANL-7002`/`ANL-7003`/`ANL-7004`… |
| `error_message` | 错误说明 | `VARCHAR(500)` | 是 | `NULL` | — | — |
| `request_id` | 链路追踪 ID | `VARCHAR(64)` | 是 | `NULL` | — | `X-Request-Id`（`§13.3`）；不可变 |
| `idempotency_key` | 创建幂等键 | `VARCHAR(128)` | 是 | `NULL` | `UK:uk_run_team_idem`(后缀) | 可空 → 允许多行 `NULL` |
| `triggered_by` | 触发人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 不可变 |
| `started_at` | 开始执行时间 | `DATETIME(3)` | 是 | `NULL` | — | `RUNNING` 时写入 |
| `finished_at` | 结束时间 | `DATETIME(3)` | 是 | `NULL` | — | 终态时写入 |
| `timeout_at` | 超时截止 | `DATETIME(3)` | 是 | `NULL` | `IDX` | 默认创建 + 300s（`§13.4`）；不可变 |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |

**索引**：`uk_run_team_no (team_id, run_no)`、`uk_run_team_idem (team_id, idempotency_key)`、`idx_run_team_status (team_id, status, created_at)`、`idx_run_team_created (team_id, created_at)`、`idx_run_timeout (status, timeout_at)`。
**无 `version`**：状态守卫即 CAS。

---

### 5.15 `analysis_items`（分析明细 · 计算事实快照）

> **本表不可变**（集合 B）。

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `IDX`、逻辑关联 `teams.id` | 不可变 |
| `run_id` | 所属运行 | `BIGINT UNSIGNED` | 否 | — | `FK:analysis_runs(id)`、`UK:uk_ai_run_sku_wh`、`IDX` | 不可变 |
| `sku_id` | SKU ID | `BIGINT UNSIGNED` | 否 | — | `FK:skus(id)`、`UK:uk_ai_run_sku_wh`、`IDX` | 不可变 |
| `product_id` | 商品 ID（冗余） | `BIGINT UNSIGNED` | 否 | — | 逻辑关联 `products.id` | 冗余（类目聚合） |
| `warehouse_id` | 仓库 ID | `BIGINT UNSIGNED` | 否 | — | `FK:warehouses(id)`、`UK:uk_ai_run_sku_wh` | 不可变 |
| `on_hand_qty` | 快照：现有库存 | `DECIMAL(18,3)` | 否 | — | `CK:ck_ai_on_hand` | 不可变 |
| `in_transit_qty` | 快照：在途库存 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_ai_in_transit` | 不可变 |
| `effective_in_transit_qty` | 快照：有效在途 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_ai_eff_in_transit` | `§10.4` 窗口过滤后 |
| `committed_qty` | 快照：已承诺 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_ai_committed` | 不可变 |
| `inventory_position_qty` | 快照：`IP` | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_ai_ip` | `on_hand + effective_in_transit − committed`（`§3`） |
| `available_qty` | 快照：可用库存 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_ai_available` | `on_hand − committed`（展示口径） |
| `avg_daily_sales` | `D` | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_ai_d` | `§10.1`；数据不足时为 `NULL` |
| `sales_window_days` | 统计窗口 `N` | `INT UNSIGNED` | 是 | `NULL` | — | 默认 28 |
| `valid_days` | 有效销售天数 `n` | `INT UNSIGNED` | 是 | `NULL` | — | `§10.1` |
| `total_sales_qty` | 窗口总销量 | `DECIMAL(18,3)` | 是 | `NULL` | — | — |
| `cv` | 波动率 `CV` | `DECIMAL(18,4)` | 是 | `NULL` | — | `mean = 0` 时为 `NULL` |
| `lead_time_days` | 采用值 `L` | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_ai_lead_time` | — |
| `lead_time_source` | `L` 来源 | `VARCHAR(32)` | 是 | `NULL` | `CK:ck_ai_lead_time_source` | `POLICY`/`SKU`/`SUPPLIER`/`DEFAULT`（`§11.2`；`D-14`） |
| `coverage_days` | 采用值 `R` | `INT UNSIGNED` | 是 | `NULL` | `CK:ck_ai_coverage` | — |
| `safety_stock_qty` | 采用值 `SS` | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_ai_ss` | `§10.3` |
| `volatility_adjusted` | 高波动上浮标记 | `TINYINT(1)` | 否 | `0` | `CK:ck_ai_vol_adj` | `§10.3` |
| `qty_base` | `Q_base` | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_ai_qbase` | `§10.2` |
| `qty_suggest` | `Q_suggest` | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_ai_qsuggest` | `§10.5` |
| `moq_applied` | 生效 MOQ | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_ai_moq` | — |
| `order_multiple_applied` | 生效采购倍数 | `DECIMAL(18,3)` | 是 | `NULL` | `CK:ck_ai_multiple` | — |
| `truncated_by_budget` | 预算截断标记 | `TINYINT(1)` | 否 | `0` | `CK:ck_ai_truncated` | `§10.5` |
| `risk_level` | 风险等级 | `VARCHAR(16)` | 否 | `'NONE'` | `CK:ck_ai_risk_level`、`IDX` | `RiskLevel`（`§9.9`，5 值）；`MAX` 合成（`§11.1`） |
| `risk_types` | 风险类型数组 | `JSON` | 是 | `NULL` | — | `RiskType` 数组（`§9.10`，6 值）；用 JSON 的理由见 §6-F.3 |
| `risk_detail_json` | 规则命中明细 | `JSON` | 是 | `NULL` | — | 规则代码 → 判定值 |
| `data_quality_json` | 数据质量结果 | `JSON` | 是 | `NULL` | — | 缺口/异常值/样本量 |
| `llm_explanation` | LLM 解释文本 | `TEXT` | 是 | `NULL` | — | 降级时为 `NULL` |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 B |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 集合 B |

**索引**：`uk_ai_run_sku_wh (run_id, sku_id, warehouse_id)`、`idx_ai_team_run (team_id, run_id, risk_level)`、`idx_ai_team_sku_time (team_id, sku_id, created_at)`。
**无 `updated_at` / 无 `version`**：**物理上不存在"更新"语义**（这是不可变性的实现保障之一）。

---

### 5.16 `replenishment_items`（补货建议）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `IDX`、逻辑关联 `teams.id` | **不可变** |
| `source_analysis_item_id` | 来源分析快照 | `BIGINT UNSIGNED` | 否 | — | `FK:analysis_items(id)`、`IDX` | **追溯链核心**；不可变 |
| `run_id` | 来源运行（冗余） | `BIGINT UNSIGNED` | 否 | — | `FK:analysis_runs(id)`、`IDX` | **不可变** |
| `sku_id` | SKU ID | `BIGINT UNSIGNED` | 否 | — | `FK:skus(id)`、`IDX` | **不可变** |
| `product_id` | 商品 ID（冗余） | `BIGINT UNSIGNED` | 否 | — | 逻辑关联 `products.id` | **不可变** |
| `warehouse_id` | 仓库 ID | `BIGINT UNSIGNED` | 否 | — | `FK:warehouses(id)`、`IDX` | **不可变** |
| `status` | 建议状态 | `VARCHAR(32)` | 否 | `'SUGGESTED'` | `CK:ck_repl_status`、`IDX` | `ReplenishmentItemStatus`（`§9.11`，7 值） |
| `suggested_qty` | 系统建议量 | `DECIMAL(18,3)` | 否 | — | `CK:ck_repl_suggested_qty` | = 快照 `qty_suggest`；**不可变** |
| `qty` | 当前生效数量 | `DECIMAL(18,3)` | 否 | — | `CK:ck_repl_qty` | 仅 `SUGGESTED`/`PENDING_REVIEW` 可改 |
| `risk_level` | 风险等级（冗余快照） | `VARCHAR(16)` | 否 | `'NONE'` | `CK:ck_repl_risk_level` | **不可变** |
| `reason` | 建议理由摘要 | `VARCHAR(500)` | 是 | `NULL` | — | 仅 `SUGGESTED` 可改 |
| `supplier_id` | 指定供应商 | `BIGINT UNSIGNED` | 是 | `NULL` | `FK:suppliers(id)` | `APPROVED` 前可改 |
| `purchase_price` | 参考采购单价 | `DECIMAL(18,4)` | 是 | `NULL` | `CK:ck_repl_price` | 4 位精度 |
| `expected_arrival_date` | 期望到货日 | `DATE` | 是 | `NULL` | — | `APPROVED` 前可改 |
| `converted_po_item_ids` | 已生成采购明细 ID | `JSON` | 是 | `NULL` | — | **仅展示**（§6-G）；可从明细表重建 |
| `converted_at` | 转采购时间 | `DATETIME(3)` | 是 | `NULL` | — | `CONVERTED` 时写入 |
| `superseded_by_item_id` | 被取代为新建议 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `replenishment_items.id` | `SUPERSEDED` 时写入 |
| `submitted_at` | 提交审核时间 | `DATETIME(3)` | 是 | `NULL` | — | 状态流转写入 |
| `approved_at` | 审批通过时间 | `DATETIME(3)` | 是 | `NULL` | — | 状态流转写入 |
| `approved_by` | 审批人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 须 `APPROVER`（`§5.1`） |
| `closed_at` | 进入终态时间 | `DATETIME(3)` | 是 | `NULL` | — | 状态流转写入 |
| `remark` | 备注 | `VARCHAR(500)` | 是 | `NULL` | — | 终态前可改 |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | 人工改量 + 状态机并发（`RPL-8003`） |

**索引**：`uk_repl_run_sku_wh (run_id, sku_id, warehouse_id)`、`idx_repl_team_status_time (team_id, status, created_at)`、`idx_repl_team_sku_time (team_id, sku_id, created_at)`、`idx_repl_team_run (team_id, run_id)`、`idx_repl_team_source (team_id, source_analysis_item_id)`。

---

### 5.17 `replenishment_reviews`（审核记录）

> **本表仅追加**（集合 B）。

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `IDX`、逻辑关联 `teams.id` | 不可变 |
| `replenishment_item_id` | 被操作建议 | `BIGINT UNSIGNED` | 否 | — | `FK:replenishment_items(id)`、`IDX` | 不可变 |
| `action` | 动作 | `VARCHAR(32)` | 否 | — | `CK:ck_rr_action`、`IDX` | `ReviewAction`（`§9.12`）：`SUBMIT`/`WITHDRAW`/`APPROVE`/`REJECT`/`CANCEL` |
| `from_status` | 变更前状态 | `VARCHAR(32)` | 否 | — | `CK:ck_rr_from_status` | `ReplenishmentItemStatus` |
| `to_status` | 变更后状态 | `VARCHAR(32)` | 否 | — | `CK:ck_rr_to_status` | `ReplenishmentItemStatus` |
| `comment` | 审核意见 | `VARCHAR(500)` | 是 | `NULL` | `CK:ck_rr_comment_required` | `REJECT` 时**必填**（`§9.11`） |
| `qty_before` | 变更前数量 | `DECIMAL(18,3)` | 是 | `NULL` | — | 数量改动时的轨迹 |
| `qty_after` | 变更后数量 | `DECIMAL(18,3)` | 是 | `NULL` | — | 同上 |
| `reviewer_id` | 操作人 | `BIGINT UNSIGNED` | 否 | — | 逻辑关联 `users.id`、`IDX` | 不可变 |
| `reviewer_role` | 操作时角色 | `VARCHAR(32)` | 否 | — | `CK:ck_rr_role` | `TeamRole` **快照**（角色可能已变） |
| `request_id` | 链路追踪 ID | `VARCHAR(64)` | 是 | `NULL` | — | — |
| `occurred_at` | 操作发生时间 | `DATETIME(3)` | 否 | — | `IDX` | UTC |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 B |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 集合 B |

**索引**：`idx_rr_team_item_time (team_id, replenishment_item_id, occurred_at)`、`idx_rr_team_reviewer_time (team_id, reviewer_id, occurred_at)`、`idx_rr_team_action_time (team_id, action, occurred_at)`。
**无唯一约束**：同一条建议会被多次操作，每步必须留痕（`§8`）。

---

### 5.18 `purchase_orders`（采购单）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_po_team_no`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `po_no` | 采购单号 | `VARCHAR(64)` | 否 | — | `UK:uk_po_team_no`(后缀) | **不可变** |
| `supplier_id` | 供应商 | `BIGINT UNSIGNED` | 否 | — | `FK:suppliers(id)`、`IDX` | 仅 `DRAFT` 可改 |
| `warehouse_id` | 到货仓库 | `BIGINT UNSIGNED` | 否 | — | `FK:warehouses(id)` | 仅 `DRAFT` 可改 |
| `status` | 采购单状态 | `VARCHAR(32)` | 否 | `'DRAFT'` | `CK:ck_po_status`、`IDX` | `PurchaseOrderStatus`（`§9.13`，6 值） |
| `source` | 来源 | `VARCHAR(32)` | 否 | `'MANUAL'` | `CK:ck_po_source` | `MANUAL`/`REPLENISHMENT`；**不可变** |
| `expected_arrival_date` | 期望到货日 | `DATE` | 是 | `NULL` | `IDX` | `§10.4` 窗口判定输入 |
| `total_qty` | 下单总数量 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_po_total_qty` | 明细变更时同事务重算 |
| `received_qty` | 已到货数量 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_po_received_qty` | 确认到货时同事务累加 |
| `total_amount` | 总金额 | `DECIMAL(18,2)` | 否 | `0` | `CK:ck_po_total_amount` | 明细变更时同事务重算 |
| `remark` | 备注 | `VARCHAR(500)` | 是 | `NULL` | — | 仅 `DRAFT` 可改 |
| `idempotency_key` | 创建幂等键 | `VARCHAR(128)` | 是 | `NULL` | `UK:uk_po_team_idem`(后缀) | 可空 → 多行 `NULL` 允许 |
| `confirmed_at` | 确认时间 | `DATETIME(3)` | 是 | `NULL` | — | `DRAFT → PENDING` 时写入 |
| `confirmed_by` | 确认人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 须 `APPROVER` |
| `ordered_at` | 下单时间 | `DATETIME(3)` | 是 | `NULL` | — | `PENDING → ORDERED` 时写入 |
| `cancelled_at` | 取消时间 | `DATETIME(3)` | 是 | `NULL` | — | — |
| `cancel_reason` | 取消原因 | `VARCHAR(255)` | 是 | `NULL` | — | — |
| `closed_at` | 终态时间 | `DATETIME(3)` | 是 | `NULL` | — | — |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | — |

**索引**：`uk_po_team_no (team_id, po_no)`、`uk_po_team_idem (team_id, idempotency_key)`、`idx_po_team_status_time (team_id, status, created_at)`、`idx_po_team_supplier_status (team_id, supplier_id, status)`、`idx_po_team_expected (team_id, expected_arrival_date)`。

---

### 5.19 `purchase_order_items`（采购明细）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `IDX`、逻辑关联 `teams.id` | **不可变** |
| `purchase_order_id` | 所属采购单 | `BIGINT UNSIGNED` | 否 | — | `FK:purchase_orders(id)`、`UK:uk_poi_po_lino`、`IDX` | **不可变** |
| `line_no` | 行号 | `INT UNSIGNED` | 否 | — | `UK:uk_poi_po_lino`、`CK:ck_poi_line_no` | **不可变**；1 起 |
| `sku_id` | 采购 SKU | `BIGINT UNSIGNED` | 否 | — | `FK:skus(id)`、`IDX` | 仅 `DRAFT` 可改 |
| `product_id` | 商品 ID（冗余） | `BIGINT UNSIGNED` | 否 | — | 逻辑关联 `products.id` | **不可变** |
| `replenishment_item_id` | 来源补货建议 | `BIGINT UNSIGNED` | 是 | `NULL` | **逻辑关联（跨模块）**、`IDX` | **不可变**；§6-G |
| `ordered_qty` | 下单数量 | `DECIMAL(18,3)` | 否 | — | `CK:ck_poi_ordered_positive` | 仅 `DRAFT` 可改；不得低于 `received_qty` |
| `received_qty` | 累计已收数量 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_poi_received_range` | **只增不减**（除冲销） |
| `unit_price` | 单价 | `DECIMAL(18,4)` | 是 | `NULL` | `CK:ck_poi_unit_price` | 4 位精度；仅 `DRAFT` 可改 |
| `amount` | 行金额 | `DECIMAL(18,2)` | 是 | `NULL` | `CK:ck_poi_amount` | `ordered_qty × unit_price` 同事务重算 |
| `expected_arrival_date` | 行级期望到货日 | `DATE` | 是 | `NULL` | — | 覆盖单头 |
| `remark` | 备注 | `VARCHAR(255)` | 是 | `NULL` | — | 仅 `DRAFT` 可改 |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | `received_qty` 累加防丢失更新 |

**索引**：`uk_poi_po_lino (purchase_order_id, line_no)`、`idx_poi_team_po (team_id, purchase_order_id, line_no)`、`idx_poi_team_sku (team_id, sku_id)`、`idx_poi_team_repl (team_id, replenishment_item_id)`。
**无行级状态字段**：行状态由 `ordered_qty` / `received_qty` 派生（避免引入 `§9` 之外的枚举）。
**`pending_qty` 不落库**：派生值 `ordered_qty − received_qty`（§6-I.1）。

---

### 5.20 `goods_receipts`（到货单）

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `UK:uk_gr_team_no`(前缀)、`IDX`、逻辑关联 `teams.id` | **不可变** |
| `receipt_no` | 到货单号 | `VARCHAR(64)` | 否 | — | `UK:uk_gr_team_no`(后缀) | **不可变** |
| `purchase_order_id` | 关联采购单 | `BIGINT UNSIGNED` | 否 | — | `FK:purchase_orders(id)`、`IDX` | **不可变** |
| `warehouse_id` | 入库仓库 | `BIGINT UNSIGNED` | 否 | — | `FK:warehouses(id)` | **不可变** |
| `status` | 到货单状态 | `VARCHAR(32)` | 否 | `'DRAFT'` | `CK:ck_gr_status`、`IDX` | `GoodsReceiptStatus`（`§9.14`，3 值） |
| `receipt_date` | 到货日期 | `DATE` | 否 | — | `IDX` | **不可变** |
| `total_qty` | 到货总数量 | `DECIMAL(18,3)` | 否 | `0` | `CK:ck_gr_total_qty` | 明细变更时同事务重算 |
| `total_amount` | 到货总金额 | `DECIMAL(18,2)` | 是 | `NULL` | `CK:ck_gr_total_amount` | — |
| `idempotency_key` | 幂等键 | `VARCHAR(128)` | **否** | — | `UK:uk_gr_team_idem`(后缀) | **`NOT NULL`**：确认收货不可逆，强制幂等 |
| `remark` | 备注 | `VARCHAR(500)` | 是 | `NULL` | — | 仅 `DRAFT` 可改 |
| `operator_id` | 录入人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | **不可变** |
| `confirmed_at` | 确认时间 | `DATETIME(3)` | 是 | `NULL` | — | `CONFIRMED` 时写入 |
| `confirmed_by` | 确认人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | `CONFIRMED` 时写入 |
| `cancelled_at` | 作废时间 | `DATETIME(3)` | 是 | `NULL` | — | 仅 `DRAFT → CANCELLED` |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `updated_at` | 更新时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` `ON UPDATE CURRENT_TIMESTAMP(3)` | — | 集合 A |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `updated_by` | 更新人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | — |
| `version` | 乐观锁版本 | `INT UNSIGNED` | 否 | `0` | 乐观锁 | — |

**索引**：`uk_gr_team_no (team_id, receipt_no)`、`uk_gr_team_idem (team_id, idempotency_key)`、`idx_gr_team_po_status (team_id, purchase_order_id, status)`、`idx_gr_team_status_time (team_id, status, created_at)`、`idx_gr_team_date (team_id, receipt_date)`。

---

### 5.21 `goods_receipt_items`（到货明细）

> 到货单 `CONFIRMED` 后本表**不可变**（集合 B）。

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `IDX`、逻辑关联 `teams.id` | 不可变 |
| `goods_receipt_id` | 所属到货单 | `BIGINT UNSIGNED` | 否 | — | `FK:goods_receipts(id)`、`UK:uk_gri_receipt_poi`、`IDX` | 不可变 |
| `purchase_order_item_id` | 对应采购明细 | `BIGINT UNSIGNED` | 否 | — | `FK:purchase_order_items(id)`、`UK:uk_gri_receipt_poi`、`IDX` | 不可变 |
| `sku_id` | SKU ID（冗余） | `BIGINT UNSIGNED` | 否 | — | `FK:skus(id)` | 冗余自采购明细（不可变），入账免 JOIN |
| `received_qty` | 本次到货数量 | `DECIMAL(18,3)` | 否 | — | `CK:ck_gri_received_positive` | 仅到货单 `DRAFT` 可改 |
| `unit_price` | 本次到货单价 | `DECIMAL(18,4)` | 是 | `NULL` | `CK:ck_gri_unit_price` | 仅 `DRAFT` 可改 |
| `amount` | 本行金额 | `DECIMAL(18,2)` | 是 | `NULL` | `CK:ck_gri_amount` | 仅 `DRAFT` 可改 |
| `batch_no` | 供应方批号 | `VARCHAR(64)` | 是 | `NULL` | — | 仅记录，不参与计算 |
| `remark` | 备注 | `VARCHAR(255)` | 是 | `NULL` | — | 仅 `DRAFT` 可改 |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 B |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 集合 B |

**索引**：`uk_gri_receipt_poi (goods_receipt_id, purchase_order_item_id)`、`idx_gri_team_receipt (team_id, goods_receipt_id)`、`idx_gri_team_poi_time (team_id, purchase_order_item_id)`。
**无 `version`**：唯一约束 `uk_gri_receipt_poi` 足够防重。

---

### 5.22 `audit_logs`（审计日志）

> **本表仅追加**（集合 B）。应用层禁止 UPDATE / DELETE（§6-J.4）。

| 字段名 | 中文名 | 类型 | 可空 | 默认值 | 键/约束 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | 主键 | `BIGINT UNSIGNED` | 否 | `AUTO_INCREMENT` | `PK` | 不可变 |
| `team_id` | 团队 ID | `BIGINT UNSIGNED` | 否 | — | `IDX`、逻辑关联 `teams.id` | `0` = **系统域**（无团队上下文，`D-11`）；**不接受客户端传入** |
| `action` | 动作常量 | `VARCHAR(64)` | 否 | — | `IDX` | 27 个审计点见 §6-J.1 |
| `biz_type` | 业务对象类型 | `VARCHAR(64)` | 否 | — | `IDX` | 表名或领域名（如 `REPLENISHMENT_ITEM`） |
| `biz_id` | 业务对象 ID | `BIGINT UNSIGNED` | 是 | `NULL` | `IDX`、**逻辑关联（多态）** | 目标表由 `biz_type` 决定 |
| `biz_no` | 业务编号快照 | `VARCHAR(64)` | 是 | `NULL` | — | 对象可能已软停用 |
| `operator_id` | 操作人 | `BIGINT UNSIGNED` | 是 | `NULL` | `IDX`、逻辑关联 `users.id` | 系统任务为 `NULL` |
| `operator_name` | 操作人显示名 | `VARCHAR(64)` | 是 | `NULL` | — | 快照 |
| `operator_role` | 操作时角色 | `VARCHAR(32)` | 是 | `NULL` | — | `TeamRole` 快照 |
| `http_method` | HTTP 方法 | `VARCHAR(8)` | 是 | `NULL` | — | 系统任务为空 |
| `request_uri` | 请求路径 | `VARCHAR(255)` | 是 | `NULL` | — | **必须剥离查询串**（防 `?token=` 泄漏） |
| `request_id` | 链路追踪 ID | `VARCHAR(64)` | 是 | `NULL` | `IDX` | `X-Request-Id`（`§13.3`） |
| `ip` | 客户端 IP | `VARCHAR(64)` | 是 | `NULL` | — | — |
| `user_agent` | 客户端 UA | `VARCHAR(255)` | 是 | `NULL` | — | 截断至 255 |
| `result` | 结果 | `VARCHAR(16)` | 否 | `'SUCCESS'` | `CK:ck_al_result`、`IDX` | `SUCCESS` / `FAILURE` |
| `error_code` | 失败错误码 | `VARCHAR(32)` | 是 | `NULL` | — | `§12.2` 中的稳定常量 |
| `change_summary` | 变更摘要 | `VARCHAR(1000)` | 是 | `NULL` | — | 如"数量 100 → 80" |
| `before_json` | 变更前值 | `JSON` | 是 | `NULL` | — | **必须脱敏**（§6-J.3） |
| `after_json` | 变更后值 | `JSON` | 是 | `NULL` | — | **必须脱敏** |
| `occurred_at` | 操作发生时间 | `DATETIME(3)` | 否 | — | `IDX` | UTC |
| `created_at` | 创建时间 | `DATETIME(3)` | 否 | `CURRENT_TIMESTAMP(3)` | — | 集合 B；用于排查审计写入延迟 |
| `created_by` | 创建人 | `BIGINT UNSIGNED` | 是 | `NULL` | 逻辑关联 `users.id` | 集合 B |

**索引**：`idx_al_team_biz_time (team_id, biz_type, biz_id, occurred_at)`、`idx_al_team_operator_time (team_id, operator_id, occurred_at)`、`idx_al_team_action_time (team_id, action, occurred_at)`、`idx_al_team_request (team_id, request_id)`、`idx_al_team_result_time (team_id, result, occurred_at)`。
**无唯一约束、无外键**：多态关联 + 只追加 + 需记录已停用对象（`ER_DIAGRAM.md §4.6`）。

---

## 6. 枚举字段取值速查（引用 `DESIGN_BASIS §9`，不重新定义）

| # | 枚举名 | 取值列表 | 引用位置 | 出现字段 |
| --- | --- | --- | --- | --- |
| 1 | `UserStatus` | `ACTIVE`、`DISABLED` | `§9.1` | `users.status` |
| 2 | `TeamStatus` | `ACTIVE`、`DISABLED` | `§9.2` | `teams.status` |
| 3 | `TeamRole` | `OWNER`、`ADMIN`、`OPERATOR`、`APPROVER`、`VIEWER` | `§9.3`（定义见 `§5`） | `team_members.role`、`replenishment_reviews.reviewer_role`、`audit_logs.operator_role` |
| 4 | 成员状态（`§9.3` 内联） | `ACTIVE`、`REMOVED` | `§9.3` | `team_members.status` |
| 5 | `MasterDataStatus` | `ACTIVE`、`INACTIVE` | `§9.4` | `warehouses.status`、`suppliers.status`、`products.status`、`skus.status` |
| 6 | `ImportBatchStatus` | `UPLOADED`、`PARSED`、`MAPPED`、`VALIDATED`、`IMPORTING`、`COMPLETED`、`PARTIAL_SUCCESS`、`FAILED`、`CANCELLED`、`DUPLICATE_REJECTED` | `§9.5` | `import_batches.status` |
| 7 | `ImportBizType` | `PRODUCT`、`SKU`、`SUPPLIER`、`WAREHOUSE`、`INVENTORY_SNAPSHOT`、`SALES_DAILY` | `§9.15` | `import_batches.biz_type` |
| 8 | `InventoryTxnType` | `INIT_IMPORT`、`PURCHASE_INBOUND`、`SALE_OUTBOUND`、`COUNT_ADJUST`、`MANUAL_ADJUST`、`REVERSAL` | `§9.6` | `inventory_transactions.txn_type` |
| 9 | `AnalysisRunStatus` | `PENDING`、`RUNNING`、`SUCCEEDED`、`DEGRADED`、`FAILED`、`TIMEOUT`、`CANCELLED` | `§9.7` | `analysis_runs.status` |
| 10 | `LlmStatus` | `NOT_REQUESTED`、`PENDING`、`SUCCEEDED`、`FAILED`、`TIMEOUT`、`VALIDATION_FAILED`、`SKIPPED` | `§9.8` | `analysis_runs.llm_status` |
| 11 | `RiskLevel` | `NONE`、`LOW`、`MEDIUM`、`HIGH`、`UNKNOWN` | `§9.9` | `analysis_items.risk_level`、`replenishment_items.risk_level` |
| 12 | `RiskType` | `STOCKOUT`、`LOW_STOCK`、`OVERSTOCK`、`SLOW_MOVING`、`SALES_ANOMALY`、`DATA_INSUFFICIENT` | `§9.10` | `analysis_items.risk_types`（JSON 数组元素） |
| 13 | `ReplenishmentItemStatus` | `SUGGESTED`、`PENDING_REVIEW`、`APPROVED`、`CONVERTED`、`REJECTED`、`SUPERSEDED`、`CANCELLED` | `§9.11` | `replenishment_items.status`、`replenishment_reviews.from_status`/`to_status` |
| 14 | `ReviewAction` | `SUBMIT`、`WITHDRAW`、`APPROVE`、`REJECT`、`CANCEL` | `§9.12` | `replenishment_reviews.action` |
| 15 | `PurchaseOrderStatus` | `DRAFT`、`PENDING`、`ORDERED`、`PARTIALLY_RECEIVED`、`RECEIVED`、`CANCELLED` | `§9.13` | `purchase_orders.status` |
| 16 | `GoodsReceiptStatus` | `DRAFT`、`CONFIRMED`、`CANCELLED` | `§9.14` | `goods_receipts.status` |
| 17 | `AnalysisScopeType` | `ALL_ACTIVE`、`SELECTED_SKUS`、`CATEGORY` | `§9.16` | `analysis_runs.scope_type` |
| 18 | `LeadTimeSource` ⚠️ | `POLICY`、`SKU`、`SUPPLIER`、`DEFAULT` | ⚠️ **`§9` 未定义**；`§10.2`/`§11.2` 要求标注。建议补充（`D-14`） | `analysis_items.lead_time_source` |
| 19 | `InventoryBizType` ⚠️ | `GOODS_RECEIPT`、`COUNT`、`MANUAL`、`IMPORT`、`SALE`、`REVERSAL` | ⚠️ **`§9` 未定义**；建议补充（`D-14`） | `inventory_transactions.biz_type` |
| 20 | `SalesSource` ⚠️ | `IMPORT`、`MANUAL`、`API` | ⚠️ **`§9` 未定义**；建议补充（`D-14`） | `sales_daily.source` |
| 21 | `PurchaseOrderSource` ⚠️ | `MANUAL`、`REPLENISHMENT` | ⚠️ **`§9` 未定义**；建议补充（`D-14`） | `purchase_orders.source` |
| 22 | 审计常量（`action` / `result` / `biz_type`）⚠️ | `action` 27 个常量见 `DATABASE_DESIGN.md §6-J.1`；`result`：`SUCCESS`/`FAILURE` | ⚠️ **`§9` 未定义**；建议由 `audit` 模块维护并在 `§9` 备案（`D-14`） | `audit_logs.*` |
| 23 | 导入错误级别 ⚠️ | `ERROR`、`WARNING` | ⚠️ **`§9` 未定义**；建议补充（`D-14`） | `import_row_errors.error_level` |

---

## 7. 字段统计（完整性校验）

| # | 表 | 业务字段数 | 审计字段（集合） | 含 `version` | 字段合计 |
| --- | --- | --- | --- | --- | --- |
| 1 | `users` | 8 | A（4） | 是 | 13 |
| 2 | `teams` | 8 | A（4） | 是 | 13 |
| 3 | `team_members` | 7 | A（4） | 否 | 11 |
| 4 | `warehouses` | 10 | A（4） | 是 | 15 |
| 5 | `suppliers` | 14 | A（4） | 是 | 19 |
| 6 | `products` | 11 | A（4） | 是 | 16 |
| 7 | `skus` | 17 | A（4） | 是 | 22 |
| 8 | `replenishment_policies` | 18 | A（4） | 是 | 23 |
| 9 | `inventory_balances` | 10 | A（4） | 是 | 15 |
| 10 | `inventory_transactions` | 16 | B（2） | 否 | 18 |
| 11 | `sales_daily` | 12 | A（4） | 否 | 16 |
| 12 | `import_batches` | 23 | A（4） | 否 | 27 |
| 13 | `import_row_errors` | 9 | B（2） | 否 | 11 |
| 14 | `analysis_runs` | 30 | A（4） | 否 | 34 |
| 15 | `analysis_items` | 32 | B（2） | 否 | 34 |
| 16 | `replenishment_items` | 23 | A（4） | 是 | 28 |
| 17 | `replenishment_reviews` | 13 | B（2） | 否 | 15 |
| 18 | `purchase_orders` | 19 | A（4） | 是 | 24 |
| 19 | `purchase_order_items` | 13 | A（4） | 是 | 18 |
| 20 | `goods_receipts` | 15 | A（4） | 是 | 20 |
| 21 | `goods_receipt_items` | 10 | B（2） | 否 | 12 |
| 22 | `audit_logs` | 20 | B（2） | 否 | 22 |
| | **合计** | **338** | A×16 表 / B×6 表 | **12 张** | **426** |

> 「业务字段数」含 `id` 主键，不含公共审计字段（按集合 A/B 单列）。合计口径：`338 + 16×4 + 6×2 + 12（version）= 426`。

> **完整性声明**：本表覆盖 `DESIGN_BASIS §8` 全部 **22 张表**与全部字段，无省略。任何字段变更必须同步 `DATABASE_DESIGN.md` 与 `DATABASE_CONSTRAINTS.md`（`§16`）。

---

## 8. 变更记录

| 日期 | 变更 | 依据 |
| --- | --- | --- |
| M0 | 初版创建（22 张表全字段字典、命名与类型规范、审计字段约定、枚举速查） | `DESIGN_BASIS.md §4.1 / §8 / §9 / §14`、`DATABASE_DESIGN.md` |
