# ShopPilot AI 对外 API 契约（API_CONTRACT）

## 文档元信息

| 项 | 内容 |
| --- | --- |
| 文档名称 | 对外 REST API 契约 |
| 文档路径 | `docs/03-api/API_CONTRACT.md` |
| 文档优先级 | P2（见 `DESIGN_BASIS.md §1.1`） |
| 所属阶段 | M0 设计阶段 |
| 版本 | v1.0（M0 定稿） |
| 上游权威来源 | `docs/DESIGN_BASIS.md`（SSOT）、`docs/02-database/DATA_DICTIONARY.md`（字段权威）、`docs/00-product/USER_ROLES.md`（权限矩阵权威） |
| 主要读者 | 前端开发、后端开发、测试、接口联调方 |

### 本文档负责什么

- 定义 **前端（`frontend-web`）与 Java 后端（`backend-java`）之间的全部 HTTP 契约**：URL 规范、HTTP 语义、状态码、统一响应结构、参数校验、认证授权、幂等、请求追踪、版本演进。
- 给出**首期全部对外接口清单**（方法 / 路径 / 说明 / 所需角色 / 幂等 / 功能编号 / 主要错误码）。
- 给出**关键接口的完整请求与响应示例**。
- 给出**接口字段 ↔ 数据字典字段**的一致性核对表，作为后续自动检查的依据。

### 本文档不负责什么

- **不定义角色代码与权限语义**。角色代码 `OWNER`/`ADMIN`/`OPERATOR`/`APPROVER`/`VIEWER` 的权威定义在 `DESIGN_BASIS.md §5`，完整权限矩阵在 `USER_ROLES.md §2.2`。本文档"所需角色"列是对该矩阵的**引用**。
- **不定义字段名与类型**。字段名与类型以 `DATA_DICTIONARY.md` 为准；本文档出现在 JSON 中的字段名必须与其一致。
- **不定义状态枚举值**。枚举取值见 `DESIGN_BASIS.md §9`；本文档只引用（如 `AnalysisRunStatus.SUCCEEDED`）。
- **不定义业务公式与阈值**。补货公式见 `DESIGN_BASIS.md §10`，风险规则见 `§11`。
- **不定义错误码完整表**。错误码格式与分段见 `DESIGN_BASIS.md §12`，完整表见 `docs/03-api/ERROR_CODES.md`；本文档只引用错误码常量。
- **不定义 Java↔Python 内部契约**。见 `docs/03-api/AI_SERVICE_CONTRACT.md`。

> **禁止**在本文档中重新定义上述任一事实。若发现本文档与 SSOT 冲突，以 SSOT 为准，并在 §13 记录。

---

## 1. 通用规范

### 1.1 URL 命名规范

| 规则 | 说明 | 示例 |
| --- | --- | --- |
| 前缀 | 对外 API 统一前缀 `/api/v1`；内部 API 统一前缀 `/internal/v1`（见 `AI_SERVICE_CONTRACT.md`） | `/api/v1/products` |
| 资源名 | 小写 + 连字符（kebab-case）+ **复数** | `/api/v1/analysis/runs`、`/api/v1/replenishment/items` |
| 子资源 | 父资源 ID 后接子资源复数名 | `/api/v1/products/{id}/skus` |
| 路径参数 | 驼峰 `{teamId}` / `{userId}` 或 `{id}`；**一律指资源主键 `id`** | `/api/v1/teams/{teamId}/members/{userId}` |
| 查询参数 | 小驼峰 | `?page=1&pageSize=20&sort=createdAt,desc` |
| 请求体/响应体字段 | **snake_case**，与 `DATA_DICTIONARY.md` 字段名严格一致 | `on_hand_qty`、`sku_code`、`replenishment_item_id` |

**动作（Action）后缀的统一选择（重要决策）**：

- **对外 API 使用 `POST /资源路径/{id}/动作`（子路径式）**，动作作为 URL 路径段，例如：
  - `POST /api/v1/replenishment/items/{id}/submit`
  - `POST /api/v1/purchasing/orders/{id}/confirm`
  - `POST /api/v1/analysis/runs/{id}/cancel`
- **内部 API 使用 `POST /内部资源:动作`（冒号式，Google AIP-136 风格）**，例如：
  - `POST /internal/v1/analysis:validate`
  - `POST /internal/v1/analysis/runs/{analysis_run_id}:cancel`

**为什么对外用子路径、对内用冒号？**

1. **对外面向浏览器与前端框架、网关、日志系统**：`/` 路径段对 Nginx/网关路由、浏览器历史、可读性、REST 直觉最友好；前端开发者无需理解冒号语义。
2. **对内面向 Java 的 `RestClient`**：冒号式是"动词作用于资源集合"的行业惯例，且与 `AI_SERVICE_CONTRACT.md`、`DESIGN_BASIS.md §13.1` 的既定写法一致（`analysis:validate`），避免两套内部路径风格。
3. **一致性优先**：同一层内**只有一种**动作风格，杜绝"同一接口两种写法"。
4. 首期**不使用** `PATCH /resource/{id}` 表达状态流转动作（如审批），因为动作携带语义（谁在何时对什么做了什么），且需要写 `replenishment_reviews` 审计记录，`POST .../action` 更贴合"命令"语义；`PATCH` 仅用于**字段局部更新**（如改数量、改备注）。

> **注**：`PRD.md §10` 与 `SYSTEM_ARCHITECTURE.md` 中曾出现的 `/api/v1/imports`、`/api/v1/purchase-orders`、`POST .../{id}:cancel` 等写法，**已在 M0 一致性审查中统一修正为本文档的路径**（历史记录见 §13.1）。本文档是 API 路径的**唯一权威**。

### 1.2 HTTP 方法与语义

| 方法 | 语义 | 幂等 | 安全 | 本项目用法 |
| --- | --- | --- | --- | --- |
| `GET` | 读取资源，不改状态 | ✅ | ✅ | 列表（分页/筛选/排序）、详情、导出（导出为 `GET`，只读） |
| `POST` | 创建资源，或触发一个动作 | ❌（故需幂等键，见 §6） | ❌ | 创建（登录、建商品、建分析任务）、动作（submit/approve/confirm/cancel） |
| `PUT` | **全量替换**资源（或 upsert） | ✅ | ❌ | `PUT /api/v1/replenishment-policies`（按 `sku_id+warehouse_id` 全量 upsert） |
| `PATCH` | **局部更新**指定字段 | ❌ | ❌ | 改状态（`PATCH .../status`）、改建议数量/备注 |
| `DELETE` | **软删除**（置非活跃状态），不物理删除 | ✅ | ❌ | `DELETE /api/v1/warehouses/{id}` → `status=INACTIVE` |

**硬规则**：

1. **`DELETE` 一律软删**。本项目 22 张表中，主数据（`warehouses`/`suppliers`/`products`/`skus`）用 `MasterDataStatus.INACTIVE` 表达停用；建议/采购单据不使用 `DELETE`（走状态流转 `CANCELLED`）。物理删除仅由运维在数据库层执行，**不提供 API**。
2. **`GET` 绝不产生副作用**（不写库、不写审计、不触发分析）。
3. **`PUT` 与 `PATCH` 不得混用**表达同一更新意图。

### 1.3 HTTP 状态码使用表

| 状态码 | 业务含义 | 本项目典型场景 |
| --- | --- | --- |
| `200 OK` | 请求成功（读取、更新、动作完成、幂等重放返回首次结果） | 列表/详情查询、`PATCH` 更新、`POST .../approve` 成功、幂等键重放命中 |
| `201 Created` | 资源已创建 | `POST /api/v1/products`、`POST /api/v1/purchasing/orders`、`POST /api/v1/analysis/runs` 之外的新建类接口 |
| `202 Accepted` | 请求已受理，**处理尚未完成** | `POST /api/v1/analysis/runs`（异步分析任务，返回 `runId`） |
| `204 No Content` | 成功且无响应体 | 首期**不使用**（统一响应体要求始终返回 JSON 信封），保留说明 |
| `400 Bad Request` | 参数语法/格式错误、必填缺失、枚举非法、`COM-0001` | 校验失败、导入文件类型错误（`IMP-4001`）、内部 Schema 错误 |
| `401 Unauthorized` | 未认证或凭证失效 | `AUTH-1001`、`AUTH-1002`、`AUTH-1005`、`AUTH-1006` |
| `403 Forbidden` | 已认证但**无权限** | `TEAM-2001`/`2002`/`2003`、`AUTH-1003`、`RPL-8007` |
| `404 Not Found` | 资源不存在或不属于当前团队（**不区分**，防枚举） | `COM-0003`、`CAT-3001`、`ANL-7005`、`PO-9004` |
| `409 Conflict` | 与当前资源状态/并发/幂等冲突 | `COM-0009`、`CAT-3002`、`IMP-4004`、`INV-5001`/`5002`、`ANL-7001`、`RPL-8002`/`8003`、`PO-9001`/`9002` |
| `410 Gone` | 资源曾存在但已不可用 | `IMP-4011`（导入原始文件已被清理） |
| `413 Payload Too Large` | 请求体超过限制 | `COM-0008`、`IMP-4001`（>20MB） |
| `415 Unsupported Media Type` | `Content-Type` 不支持 | `COM-0006` |
| `422 Unprocessable Entity` | 语法正确但**语义/业务前置条件不满足** | `IMP-4005`、`RPL-8001`、`ANL-7004`、`PO-9003` |
| `429 Too Many Requests` | 触发限流 | `COM-0011` |
| `500 Internal Server Error` | 服务端内部错误（含 DB 异常） | `COM-0002`、`COM-0013` |
| `502 Bad Gateway` | 下游/内部依赖不可用 | `ANL-7002`（AI 服务不可用）、`COM-0014` |
| `503 Service Unavailable` | 服务暂不可用（维护/过载） | `COM-0012` |
| `504 Gateway Timeout` | 下游/内部依赖超时 | `ANL-7003`（AI 服务超时） |

> **`409` 与 `422` 的边界**：`409` 表示"**与当前状态冲突**，重试/刷新后可能成功"（并发、幂等、重复）;`422` 表示"**前置条件不满足**，当前请求在本状态下永不合法"（如对 `SUCCEEDED` 的 run 再取消）。

---

## 2. 统一响应结构

### 2.1 成功响应（单对象）

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "id": 1001,
    "sku_code": "SKU-A-001"
  },
  "requestId": "req_2f8a1c4e9b7d4a13",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | string | **同时服务于成功与失败**。成功恒为 `"OK"`；失败为重载的错误码常量（如 `"INV-5002"`） |
| `message` | string | 成功为 `"success"`；失败为**面向运营人员的中文可读提示**（不含堆栈/SQL，见 `ERROR_CODES.md §使用规范`） |
| `data` | object \| array \| null | 业务数据；失败时为 `null` |
| `requestId` | string | 链路追踪 ID，等同响应头 `X-Request-Id`（见 §7） |
| `timestamp` | string | 服务端响应时间，ISO-8601 UTC，形如 `2026-10-14T08:00:00.000Z`（`DESIGN_BASIS §14.1`） |

### 2.2 成功响应（分页）

分页响应把 `list` 与分页元信息**一起放在 `data` 内**：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "list": [
      {
        "id": 1001,
        "sku_code": "SKU-A-001",
        "on_hand_qty": "120.000"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 123
  },
  "requestId": "req_2f8a1c4e9b7d4a13",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

分页规范：

| 参数 | 默认 | 约束 |
| --- | --- | --- |
| `page` | 1 | ≥1，**从 1 开始** |
| `pageSize` | 20 | 1..200，**最大 200**，超过则返回 `COM-0007` |
| `sort` | 各接口默认 | 形如 `sort=created_at,desc`；多字段用逗号分隔：`sort=status,asc&sort=created_at,desc`；字段白名单外返回 `COM-0007` |
| `dateFrom`/`dateTo` | 各接口默认 | `YYYY-MM-DD`（团队时区自然日）；`dateFrom > dateTo` 返回 `COM-0007` |

分页字段说明：`total` 为**满足筛选条件的总记录数**（int64），`page`/`pageSize` 回显请求值。

### 2.3 失败响应

```json
{
  "code": "INV-5002",
  "message": "库存数据已被同时更新，请刷新后重试",
  "data": null,
  "details": [
    {
      "field": "version",
      "message": "期望版本 3，实际版本 4",
      "rejectedValue": 3
    }
  ],
  "requestId": "req_2f8a1c4e9b7d4a13",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | string | 错误码常量（见 `ERROR_CODES.md`） |
| `message` | string | 面向用户的中文提示，**不含技术细节** |
| `data` | null | 恒为 `null` |
| `details` | array | **仅用于字段级校验错误**（`COM-0001` 等）；无细节时为 `null` 或省略 |
| `requestId` / `timestamp` | 同成功响应 | — |

`details[]` 元素结构（**字段级校验错误**）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `field` | string | ✅ | 出错的字段路径，snake_case，支持嵌套：`params.window_days` |
| `message` | string | ✅ | 该字段的中文错误说明 |
| `rejectedValue` | any | ❌ | 被拒绝的原始值（**不得回显敏感值**，如口令恒为 `null` 或 `"***"`） |

> **约定**：`code` 是**统一入口**——前端只需判断 `code === "OK"` 即成功，否则按错误码分支处理；`message` 直接可展示给运营人员；`details` 用于表单字段级红字提示。

---

## 3. 参数校验规范

- 校验实现：Java Bean Validation（`jakarta.validation`）注解落在**请求 DTO**上，由 `@Valid` 触发。
- 常用注解映射：

| 注解 | 用途 | 示例 |
| --- | --- | --- |
| `@NotNull` | 非空 | `@NotNull Long productId` |
| `@NotBlank` | 字符串非空且非空白 | `@NotBlank String skuCode` |
| `@Size(min,max)` | 长度 | `@Size(max=200) String name` |
| `@Min`/`@Max` | 数值范围 | `@Min(1) @Max(365) Integer windowDays` |
| `@Positive`/`@PositiveOrZero` | 正数/非负 | `@PositiveOrZero BigDecimal qtyDelta` |
| `@DecimalMin`/`@DecimalMax` | 小数范围 | `@DecimalMin("0.001") BigDecimal orderedQty` |
| `@Pattern` | 正则 | `@Pattern(regexp="^[A-Z0-9_-]{1,64}$") String skuCode` |
| `@Email` | 邮箱 | `@Email String email` |
| `@Valid` | 级联校验嵌套对象 | `@Valid List<OrderItemRequest> items` |
| `@Max`/`@Min` on collection | 集合大小 | `@Size(min=1,max=500) List<...> items` |

- 枚举与日期使用 `@Pattern` 或自定义校验器；日期统一 `@JsonFormat(pattern="yyyy-MM-dd")`（业务日期）或 ISO-8601（时间戳）。
- **校验失败统一返回 `COM-0001` + 400 + `details[]`**。示例：

```json
{
  "code": "COM-0001",
  "message": "请求参数校验失败",
  "data": null,
  "details": [
    { "field": "sku_code", "message": "不能为空", "rejectedValue": null },
    { "field": "ordered_qty", "message": "必须大于 0", "rejectedValue": -5 },
    { "field": "items[2].unit_price", "message": "小数位不能超过 4 位", "rejectedValue": "12.34567" }
  ],
  "requestId": "req_2f8a1c4e9b7d4a13",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

- 校验顺序：**先结构后业务**——Bean Validation（`COM-0001`）→ 团队隔离（`TEAM-2001`）→ 角色（`TEAM-2003`）→ 业务前置条件（`409`/`422`）。与 `USER_ROLES.md §4.1` 的判定顺序一致。

---

## 4. 错误码规范

- 格式：`<DOMAIN>-<4位数字>`（`DESIGN_BASIS §12.1`）。
- 分段：`COM`/`AUTH`/`TEAM`/`CAT`/`IMP`/`INV`/`SAL`/`ANL`/`RPL`/`PO`（`DESIGN_BASIS §12.2`）。
- 完整错误码表、用户可见提示、处理建议、可重试性见 **`docs/03-api/ERROR_CODES.md`**。
- 本文档接口清单中"主要错误码"列只列**该接口最相关**的错误；通用错误（`COM-0001`/`COM-0002`/`AUTH-1001`/`TEAM-2001`/`TEAM-2002`/`TEAM-2003`）对所有接口生效，不逐条重复。

---

## 5. 认证与授权

### 5.1 认证方式

- 除 `POST /api/v1/auth/login`、`POST /api/v1/auth/refresh`、`GET /api/v1/health` 外，**所有接口**要求请求头：

```
Authorization: Bearer <JWT_Access_Token>
```

### 5.2 JWT Claim 结构

| Claim | 类型 | 说明 |
| --- | --- | --- |
| `sub` | string | **`userId`**（`users.id`），字符串形式 |
| `team_id` | number | **当前上下文团队 ID**（`teams.id`）。单次登录会话绑定一个团队；切换团队需重新登录/换票（首期） |
| `role` | string | 当前团队内的 `TeamRole`（`DESIGN_BASIS §9.3`）：`OWNER`/`ADMIN`/`OPERATOR`/`APPROVER`/`VIEWER` |
| `iat` | number | 签发时间（epoch 秒） |
| `exp` | number | 过期时间（epoch 秒）。Access Token 建议 **2h** |
| `jti` | string | 令牌唯一 ID（用于审计与潜在吊销） |
| `iss` | string | 签发者（固定 `shoppilot-ai`） |
| `aud` | string | 受众（固定 `frontend-web`） |

Example（解码后）：

```json
{
  "sub": "1001",
  "team_id": 7,
  "role": "OPERATOR",
  "jti": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "iss": "shoppilot-ai",
  "aud": "frontend-web",
  "iat": 1760428800,
  "exp": 1760436000
}
```

### 5.3 `team_id` 红线（引用 `DESIGN_BASIS §6` 规则 3）

- **`team_id` 绝不接受客户端传入**。任何请求体/查询参数中出现 `team_id` 一律**忽略**（不报错），团队上下文**只**来自 JWT 的 `team_id` claim，由 `TeamContext` 统一提供。
- 路径中的 `{teamId}`（如 `/api/v1/teams/{teamId}/members`）是**资源定位符**，服务端必须校验 `{teamId} == JWT.team_id`，不等则返回 `TEAM-2001`。即 `{teamId}` **不是**"客户端选择团队"的入口，而是"断言当前团队"。
- MyBatis-Plus 租户插件自动追加 `team_id` 条件作为兜底，业务层仍需显式校验（`DESIGN_BASIS §6` 规则 4，双保险）。
- **Controller 方法签名中出现 `teamId` 作为 `@RequestParam`/`@RequestBody` 字段视为违规**（`USER_ROLES.md §6.4`）。

### 5.4 继承的授权判定顺序（引用 `USER_ROLES.md §4.1`）

```
认证(AUTH-1001) → 用户状态(AUTH-1003) → 团队解析 → 团队归属(TEAM-2001)
→ 团队状态(TEAM-2002) → 成员状态/角色授权(TEAM-2003) → 高风险二次校验 → 业务规则
```

### 5.5 无状态 JWT 与登出（决策结论）

**结论：首期采用「短有效期 Access Token + Refresh Token + 客户端清除」，不引入服务端 jti 黑名单。**

理由：

1. **引入黑名单需要共享存储**（Redis/DB），而 `DESIGN_BASIS §2.1` 与 `ADR-001` 明确首期**不引入 Redis**（`D-07`）；用 MySQL 存黑名单会为核心认证路径引入额外写放大与单点。
2. **Access Token 有效期短（2h）**，泄露窗口有限；Refresh Token 7d 且可被服务端在登录时轮换/覆盖（首期 Refresh Token 存储位置见 `D-01`，M2 决定）。
3. **用户/成员即时失效可通过每次请求的成员状态校验兜底**：`team_members.status` 与 `users.status` 在授权判定第 2、6 步实时查询，被禁用/移出的用户在下一个请求即被拒绝（`AUTH-1003`/`TEAM-2003`），无需吊销 Access Token。
4. **登出语义**：`POST /api/v1/auth/logout` 使**Refresh Token 失效**（服务端删除/标记），Access Token 由客户端清除；Access Token 在其剩余 TTL 内仍技术上可用，但因其 `role`/`team_id` 会经服务端实时校验，越权风险受控。
5. **可演进**：若未来引入 Redis（超出首期范围），可无缝加入 jti 黑名单，接口语义不变。

> 该决策与待决策 `D-01`（JWT vs Session）一致（采用 JWT），并补充了"首期不引入 jti 黑名单"的明确结论。

### 5.6 跨团队越权

- 任何跨团队访问（资源 `team_id != JWT.team_id`）→ **`TEAM-2001`**（403）。**先于角色判定**（`USER_ROLES.md §4.1` 第 4 步）。
- 资源不存在与资源属于他人团队**均返回 404 的错误码语义**（`CAT-3001` 等）或 `TEAM-2001`，二者不得泄露"该资源在别的团队存在"。实现约定：**先按 `team_id` 过滤查询，查不到即 404**；只有当路径显式携带 `{teamId}` 且与 JWT 不符时才返回 `TEAM-2001`。

---

## 6. 幂等规范

### 6.1 请求头

```
Idempotency-Key: <客户端生成的全局唯一字符串，长度 1..128>
```

- 服务端以 `(team_id, idempotency_key)` 作为幂等槽位（对齐数据库唯一约束 `uk_invtxn_team_idem`、`uk_run_team_idem`、`uk_po_team_idem`、`uk_gr_team_idem`）。
- 幂等键**禁止使用 UUID 作为业务语义**，但**允许**作为客户端生成的随机串（`DATA_DICTIONARY.md §5.10` 的"禁止用 UUID"针对 `inventory_transactions.idempotency_key` 的**业务构造规则**，即服务端必须用 `<txn_type>:<biz_type>:<biz_ref_id>:<sku_id>` 构造；对外 API 允许客户端传任意随机串，服务端映射为该业务构造键）。

### 6.2 必填幂等键的接口（**强制**）

| 接口 | 理由 |
| --- | --- |
| `POST /api/v1/import/batches/{id}/confirm` | 落库不可逆，重复确认会重复写入 |
| `POST /api/v1/analysis/runs` | 避免重复创建分析任务（`uk_run_team_idem`） |
| `POST /api/v1/purchasing/orders`（含 `from-replenishment`） | 避免重复建单（`uk_po_team_idem`） |
| `POST /api/v1/purchasing/receipts/{id}/confirm` | 确认到货不可逆，触发库存入库（`uk_gr_team_idem`） |
| `POST /api/v1/inventory/adjustments` | 库存变更不可逆（`uk_invtxn_team_idem`） |

- 上述接口**缺少 `Idempotency-Key`** → `COM-0010`（400）。
- 其余接口**可选**携带；携带时同样生效。

### 6.3 重复请求的行为

| 情形 | 行为 |
| --- | --- |
| 同幂等键 + **请求体一致** | **返回首次结果**（`200` 或 `201`，与首次一致），响应体 `data` 为首次创建/处理结果，**不重复执行**。响应可带 `Idempotent-Replayed: true` 头 |
| 同幂等键 + **请求体不一致** | 返回 **`COM-0009`（409）**"幂等键冲突但请求体不一致" |
| 同幂等键 + 首次**正在处理中** | 返回 `409` 或 `202`（按接口而定）；分析任务返回既有 `runId` 与当前状态 |

**请求体一致性判定**：对请求体做规范化（字段排序、去除空白、统一数值精度）后计算哈希，与首次存储的哈希比较。**不比较**与业务无关的字段（如客户端时间戳）。

---

## 7. 请求追踪

| 头 | 方向 | 说明 |
| --- | --- | --- |
| `X-Request-Id` | 请求（客户端可传）/ 响应（服务端必回） | 客户端可传入用于跨系统排查；**未传时服务端生成**（ULID 或 `req_` 前缀随机串），贯穿日志、`audit_logs.request_id`、`analysis_runs.request_id`、以及对 Python 的内部调用头（`AI_SERVICE_CONTRACT.md §认证`） |
| `Idempotent-Replayed` | 响应 | 幂等重放命中时为 `true` |

- 响应体 `data` 同级的 `requestId` 必须等于响应头 `X-Request-Id`。
- 服务端日志（Logback MDC）必须包含 `request_id`、`user_id`、`team_id`、`uri`、`method`、`duration_ms`。
- **审计与追踪分离**：`X-Request-Id` 用于**技术链路追踪**（可含技术细节，不进用户响应）；`audit_logs` 用于**业务审计**（面向治理，见 `AI_ARCHITECTURE.md §Trace 边界表`）。

---

## 8. 版本演进策略

### 8.1 版本表达

- **路径版本**：`/api/v1`。不做 Header 版本协商（首期）。
- Java 包根 `com.shoppilot`、Python 包根 `shoppilot_ai`（`DESIGN_BASIS §2`）。

### 8.2 兼容性规则

| 变更 | 兼容性 | 是否允许在 v1 内 |
| --- | --- | --- |
| **新增可选响应字段** | 兼容 | ✅ 允许 |
| **新增可选请求字段**（有默认值） | 兼容 | ✅ 允许 |
| **新增枚举值**（状态/类型） | **条件兼容** | ✅ 允许，但**前端必须对未知枚举值容错**（显示原始值而非崩溃），且必须在 `ERROR_CODES.md`/`DESIGN_BASIS §9` 同步 |
| **修改字段类型**（如 number → string） | **不兼容** | ❌ 需 v2 |
| **删除响应字段 / 重命名字段** | **不兼容** | ❌ 需 v2 |
| **新增必填请求字段** | **不兼容** | ❌ 需 v2（或提供默认值转为可选） |
| **收紧校验（如 pageSize 上限 200→100）** | **不兼容** | ❌ 需 v2 |
| **放宽校验** | 兼容 | ✅ 允许 |
| **修改错误码含义 / 复用错误码** | **不兼容** | ❌ 禁止（见 `ERROR_CODES.md §使用规范`） |

### 8.3 废弃流程

1. 在文档与响应中加入 `Deprecated: <字段/接口>` 响应头 + `API_CONTRACT.md` 标注 `⚠️ 已废弃（自 vX.Y，计划移除 vX.Z）`。
2. 至少保留**一个小版本周期**（≥ 1 个里程碑）双写/兼容。
3. 移除时**必须**记 ADR，并更新 `ERROR_CODES.md` 与前端 `types/`。

---

## 9. REST 之外的约定

### 9.1 批量操作

- 批量写操作使用 `POST /资源路径/batch-动作`（如 `POST /api/v1/replenishment/items/batch-submit`）。
- 请求体：`{ "ids": [1,2,3] }`；响应返回**逐条结果**，允许部分成功：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "total": 3,
    "succeeded": 2,
    "failed": 1,
    "results": [
      { "id": 1, "success": true, "errorCode": null, "message": null },
      { "id": 2, "success": true, "errorCode": null, "message": null },
      { "id": 3, "success": false, "errorCode": "RPL-8001", "message": "当前状态不允许提交" }
    ]
  },
  "requestId": "req_2f8a1c4e9b7d4a13",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

- 批量操作**逐条独立事务**（一条失败不影响其他条），但**整体在团队隔离校验通过后**才执行。
- 批量上限：`ids` 数量 ≤ 200，超过返回 `COM-0007`。

### 9.2 导出

- 导出使用 `GET`，响应 `Content-Type: text/csv; charset=utf-8`（首期仅 CSV），带 `Content-Disposition: attachment; filename="<资源>-<yyyyMMddHHmmss>.csv"`。
- 导出仍受团队隔离与角色校验；导出行为写 `audit_logs`（如 `AUDIT_LOG_EXPORT`，P1）。
- 首期导出接口：`GET /api/v1/import/batches/{id}/errors/export`、`GET /api/v1/analysis/runs/{id}/items/export`、`GET /api/v1/audit/logs/export`（均 P1/P2）。

### 9.3 文件上传

| 项 | 约定 |
| --- | --- |
| `Content-Type` | `multipart/form-data` |
| 表单字段名 | **`file`**（固定，且仅一个文件） |
| 其他表单字段 | `bizType`（`ImportBizType`）、`templateVersion`（可选）、`parseOptions`（可选 JSON 字符串） |
| 允许扩展名 | `.csv`、`.xlsx`、`.xls` |
| 最大大小 | **20 MB**（`file_size` 超过即 `IMP-4001`，HTTP 413 或 400） |
| 校验 | 先校验大小/扩展名/MIME，再计算 SHA-256（`file_hash`）做文件级幂等（`uk_imp_team_hash_biz`） |
| 幂等 | 同团队 + 同 `file_hash` + 同 `biz_type` 已存在成功批次 → `IMP-4004`（409），响应 `data` 携带已存在批次 `batchId`，允许强制重导（`POST .../reimport`） |

### 9.4 其他

- **时间**：时间戳 ISO-8601 UTC（`Z` 后缀），业务日期 `YYYY-MM-DD`（`DESIGN_BASIS §14.1`）。
- **数量与金额传输格式**：见 `AI_SERVICE_CONTRACT.md §数值一致性`。**对外 API 结论**：数量与金额以**字符串**传输（`"120.000"`），避免 JSON number 的 IEEE-754 精度丢失，前端按字符串渲染/展示、需要计算时用 decimal 库。**与本文档所有 JSON 示例一致**。
- **枚举**：以大写字符串传输（`"RUNNING"`）。
- **空值与缺省**：可空字段明确返回 `null`，不使用"字段缺失"表达"无值"。

---

## 10. 首期 API 清单

> 图例：**幂等**列 —— ✅=天然幂等（GET/PUT/DELETE）；⚠️=需 `Idempotency-Key`（§6.2）；❌=非幂等但无强制键。
> **角色**列 —— 引用 `USER_ROLES.md §2.2`；`任何已登录成员` 表示 VIEWER 及以上（只读接口含 VIEWER）。
> **功能**列 —— 引用 `PRD.md §10` 功能编号。
> 通用错误码（`COM-0001`/`COM-0002`/`AUTH-1001`/`AUTH-1003`/`TEAM-2001`/`TEAM-2002`/`TEAM-2003`）对所有接口生效，不再逐行重复。

### 10.1 identity（认证与用户）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | 用户名/邮箱 + 密码登录，返回 Access/Refresh Token 与当前角色 | 匿名（免认证） | ❌ | F-AUTH-01 | `AUTH-1002`、`AUTH-1003`、`COM-0001` |
| POST | `/api/v1/auth/refresh` | 用 Refresh Token 换取新 Access Token | 持有效 Refresh Token | ✅ | F-AUTH-02 | `AUTH-1006`、`AUTH-1001` |
| POST | `/api/v1/auth/logout` | 登出，使 Refresh Token 失效 | 已登录 | ✅ | F-AUTH-01 | `AUTH-1001` |
| GET | `/api/v1/auth/me` | 返回当前用户资料 + 当前团队 + 当前角色 + 权限位 | 任何已登录成员 | ✅ | F-AUTH-01 | `AUTH-1001` |
| GET | `/api/v1/users` | 用户列表（用于成员管理选人） | OWNER/ADMIN | ✅ | F-TEAM-01 | `TEAM-2003` |
| PATCH | `/api/v1/users/{userId}/status` | 启用/禁用用户（`UserStatus`） | OWNER/ADMIN | ❌ | F-AUTH-03 | `TEAM-2003`、`AUTH-1004`、`AUTH-1010` |

### 10.2 team（团队与成员）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/teams/{teamId}` | 团队配置查询（名称/时区/状态/默认仓） | 任何已登录成员 | ✅ | F-TEAM-03 | `TEAM-2001` |
| PATCH | `/api/v1/teams/{teamId}` | 团队配置修改（名称、`default_timezone`、`default_warehouse_id`） | OWNER/ADMIN | ❌ | F-TEAM-03 | `TEAM-2003`、`CAT-3005` |
| GET | `/api/v1/teams/{teamId}/members` | 成员列表（含角色与状态） | OWNER/ADMIN | ✅ | F-TEAM-01 | `TEAM-2003` |
| POST | `/api/v1/teams/{teamId}/members` | 添加成员并指定角色 | OWNER/ADMIN | ❌ | F-TEAM-01 | `TEAM-2005`、`TEAM-2003` |
| PATCH | `/api/v1/teams/{teamId}/members/{userId}` | 修改成员角色 | OWNER/ADMIN | ❌ | F-TEAM-01 | `TEAM-2006`、`TEAM-2004`、`TEAM-2007` |
| DELETE | `/api/v1/teams/{teamId}/members/{userId}` | 移出成员（`REMOVED`，软删） | OWNER/ADMIN | ✅ | F-TEAM-01 | `TEAM-2006`、`TEAM-2009` |
| GET | `/api/v1/teams` | 当前用户所属团队列表（登录后选择/展示） | 任何已登录用户 | ✅ | F-TEAM-01 | `AUTH-1001` |

### 10.3 catalog（商品与基础资料）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/warehouses` | 仓库列表（分页） | 任何已登录成员 | ✅ | F-CAT-01 | — |
| POST | `/api/v1/warehouses` | 新建仓库 | OPERATOR+ | ❌ | F-CAT-01 | `CAT-3002`、`CAT-3012` |
| GET | `/api/v1/warehouses/{id}` | 仓库详情 | 任何已登录成员 | ✅ | F-CAT-01 | `CAT-3003` |
| PATCH | `/api/v1/warehouses/{id}` | 修改仓库 | OPERATOR+ | ❌ | F-CAT-01 | `CAT-3003`、`CAT-3008` |
| DELETE | `/api/v1/warehouses/{id}` | 停用仓库（→`INACTIVE`） | OPERATOR+ | ✅ | F-CAT-05 | `CAT-3003`、`CAT-3007` |
| PATCH | `/api/v1/warehouses/{id}/status` | 显式改状态（启用/停用） | OPERATOR+ | ❌ | F-CAT-05 | `CAT-3007` |
| GET | `/api/v1/suppliers` | 供应商列表（分页） | 任何已登录成员 | ✅ | F-CAT-02 | — |
| POST | `/api/v1/suppliers` | 新建供应商 | OPERATOR+ | ❌ | F-CAT-02 | `CAT-3002` |
| GET | `/api/v1/suppliers/{id}` | 供应商详情 | 任何已登录成员 | ✅ | F-CAT-02 | `CAT-3001` |
| PATCH | `/api/v1/suppliers/{id}` | 修改供应商（含 `lead_time_days`/`moq`/`order_multiple`） | OPERATOR+ | ❌ | F-CAT-02 | `CAT-3001`、`CAT-3011` |
| DELETE | `/api/v1/suppliers/{id}` | 停用供应商（→`INACTIVE`） | OPERATOR+ | ✅ | F-CAT-05 | `CAT-3007` |
| GET | `/api/v1/products` | 商品列表（可按 `category`/`status` 筛选） | 任何已登录成员 | ✅ | F-CAT-03 | — |
| POST | `/api/v1/products` | 新建商品 | OPERATOR+ | ❌ | F-CAT-03 | `CAT-3002`、`CAT-3005` |
| GET | `/api/v1/products/{id}` | 商品详情 | 任何已登录成员 | ✅ | F-CAT-03 | `CAT-3001` |
| PATCH | `/api/v1/products/{id}` | 修改商品 | OPERATOR+ | ❌ | F-CAT-03 | `CAT-3001`、`CAT-3005` |
| DELETE | `/api/v1/products/{id}` | 停用商品（→`INACTIVE`） | OPERATOR+ | ✅ | F-CAT-05 | `CAT-3009` |
| GET | `/api/v1/products/{id}/skus` | 商品下 SKU 列表 | 任何已登录成员 | ✅ | F-CAT-04 | `CAT-3001` |
| POST | `/api/v1/products/{id}/skus` | 在指定商品下新建 SKU | OPERATOR+ | ❌ | F-CAT-04 | `CAT-3002`、`CAT-3004` |
| GET | `/api/v1/skus` | SKU 列表（可按 `productId`/`status`/关键字筛选） | 任何已登录成员 | ✅ | F-CAT-04 | — |
| POST | `/api/v1/skus` | 新建 SKU（须带 `product_id`） | OPERATOR+ | ❌ | F-CAT-04 | `CAT-3002`、`CAT-3004` |
| GET | `/api/v1/skus/{id}` | SKU 详情 | 任何已登录成员 | ✅ | F-CAT-04 | `CAT-3001` |
| PATCH | `/api/v1/skus/{id}` | 修改 SKU（采购属性、交期、MOQ 等） | OPERATOR+ | ❌ | F-CAT-04 | `CAT-3001`、`CAT-3005` |
| DELETE | `/api/v1/skus/{id}` | 停用 SKU（→`INACTIVE`） | OPERATOR+ | ✅ | F-CAT-05 | `CAT-3007` |
| PATCH | `/api/v1/skus/{id}/status` | 显式改 SKU 状态 | OPERATOR+ | ❌ | F-CAT-05 | — |

### 10.4 inventory（库存与补货策略）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/inventory/balances` | 库存余额列表（按 `sku_id`/`warehouse_id` 筛选，返回 `on_hand_qty`/`available_qty`/`in_transit_qty`） | 任何已登录成员 | ✅ | F-INV-01 | — |
| GET | `/api/v1/inventory/transactions` | 库存流水列表（按类型/时间/SKU 筛选） | 任何已登录成员 | ✅ | F-INV-02 | — |
| POST | `/api/v1/inventory/adjustments` | 盘点调整（`COUNT_ADJUST`）或手工调整（`MANUAL_ADJUST`），二合一入口按 `txn_type` 区分 | ⚠️ 见 §11.4（`MANUAL_ADJUST` 需 ADMIN+） | ⚠️ | F-INV-03/F-INV-04 | `INV-5002`/`5003`/`5005`/`5006`/`5007`/`5011`、`TEAM-2003` |
| GET | `/api/v1/inventory/transactions/{id}` | 单笔流水详情 | 任何已登录成员 | ✅ | F-INV-02 | `INV-5008` |
| POST | `/api/v1/inventory/transactions/{id}/reverse` | 冲销某笔流水（`REVERSAL`，P1） | ADMIN+ | ⚠️ | F-INV-06 | `INV-5009`/`5010`、`TEAM-2003` |
| GET | `/api/v1/inventory/alerts` | 库存预警列表（缺货/低库存提示） | 任何已登录成员 | ✅ | F-INV-05 | — |
| GET | `/api/v1/replenishment-policies` | 补货策略列表（按 `sku_id`/`warehouse_id` 筛选） | 任何已登录成员 | ✅ | F-CAT-06 | — |
| PUT | `/api/v1/replenishment-policies` | 按 `(sku_id, warehouse_id)` **全量 upsert** 策略参数 | OPERATOR+ | ✅ | F-CAT-06 | `CAT-3010`、`CAT-3004` |
| GET | `/api/v1/replenishment-policies/{id}` | 策略详情 | 任何已登录成员 | ✅ | F-CAT-06 | `CAT-3001` |
| DELETE | `/api/v1/replenishment-policies/{id}` | 停用策略（`is_enabled=0`） | OPERATOR+ | ✅ | F-CAT-06 | — |

### 10.5 sales（销售数据）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/sales/daily` | 日销售查询（按 `sku_id`/`warehouse_id`/`dateFrom`/`dateTo` 筛选；`groupBy=sku\|warehouse\|date` 聚合） | 任何已登录成员 | ✅ | F-SAL-02 | `SAL-6006` |
| POST | `/api/v1/sales/daily` | 手工写入/覆盖单条日销售（`source=MANUAL`，upsert 语义） | OPERATOR+ | ❌ | F-SAL-01 | `SAL-6002`/`6003`/`6005` |

### 10.6 importdata（数据导入）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/import/templates` | 下载模板清单与模板文件（按 `bizType`） | OPERATOR+ | ✅ | F-IMP-02 | — |
| POST | `/api/v1/import/batches` | 上传文件（multipart，字段 `file`），创建批次，返回批次与预览 | OPERATOR+ | ❌ | F-IMP-01/F-IMP-05 | `IMP-4001`/`4002`/`4004`/`4012`/`4013` |
| GET | `/api/v1/import/batches` | 批次列表（按状态/类型/时间筛选） | OPERATOR+ | ✅ | F-IMP-04 | — |
| GET | `/api/v1/import/batches/{id}` | 批次详情（状态、行数统计、错误统计） | OPERATOR+ | ✅ | F-IMP-04 | `IMP-4007` |
| PUT | `/api/v1/import/batches/{id}/mapping` | 提交字段映射（全量），状态 `PARSED→MAPPED` | OPERATOR+ | ✅ | F-IMP-02 | `IMP-4005`/`4008` |
| GET | `/api/v1/import/batches/{id}/preview` | 预览解析结果（前 N 行 + 列清单） | OPERATOR+ | ✅ | F-IMP-02 | `IMP-4007`/`4005` |
| POST | `/api/v1/import/batches/{id}/validate` | 全量校验，产出错误报告，状态 `MAPPED→VALIDATED`（或 `FAILED`） | OPERATOR+ | ✅ | F-IMP-03 | `IMP-4005`/`4009`/`4010` |
| POST | `/api/v1/import/batches/{id}/confirm` | 确认导入落库，状态 `VALIDATED→IMPORTING→COMPLETED/PARTIAL_SUCCESS/FAILED` | OPERATOR+ | ⚠️ | F-IMP-03 | `IMP-4005`/`4009`、`COM-0009` |
| GET | `/api/v1/import/batches/{id}/errors` | 错误/警告行列表（分页，按 `error_level`/`row_no` 筛选） | OPERATOR+ | ✅ | F-IMP-03 | `IMP-4007` |
| GET | `/api/v1/import/batches/{id}/errors/export` | 导出错误报告 CSV（P1） | OPERATOR+ | ✅ | F-IMP-03 | `IMP-4007` |
| POST | `/api/v1/import/batches/{id}/reimport` | 修正后重导（`FORCE_REIMPORT`，仅 `PARTIAL_SUCCESS`/`FAILED`/`DUPLICATE_REJECTED` 可用） | OPERATOR+ | ⚠️ | F-IMP-06 | `IMP-4005`、`IMP-4004` |

### 10.7 analysis（分析任务）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/analysis/runs` | 创建分析任务（同步预检 + 异步执行），返回 **202 + `runId`** | OPERATOR+（VIEWER 不可） | ⚠️ | F-ANL-01 | `ANL-7001`/`7007`/`7008`/`7009`/`7013`、`TEAM-2003` |
| GET | `/api/v1/analysis/runs` | 运行列表（历史，按状态/时间筛选）——支撑 F-ANL-09 历史对比 | 任何已登录成员（含 VIEWER） | ✅ | F-ANL-09 | — |
| GET | `/api/v1/analysis/runs/{id}` | 运行状态与进度（轮询入口，返回 `status`/`progress`/`current_step`/`llm_status`） | 任何已登录成员 | ✅ | F-ANL-02 | `ANL-7005` |
| GET | `/api/v1/analysis/runs/{id}/items` | 分析明细（`analysis_items` 快照，含风险与 LLM 解释，分页） | 任何已登录成员 | ✅ | F-ANL-06 | `ANL-7005`/`7012` |
| GET | `/api/v1/analysis/runs/{id}/items/export` | 导出分析明细 CSV（P2） | 任何已登录成员 | ✅ | F-ANL-10 | `ANL-7005`/`7012` |
| POST | `/api/v1/analysis/runs/{id}/cancel` | 取消运行（`RUNNING`/`PENDING` → `CANCELLED`） | OPERATOR+ | ✅ | F-ANL-07 | `ANL-7005`/`7011`/`7010` |

### 10.8 replenishment（补货建议与审核）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/replenishment/items` | 建议列表（按状态/风险/`run_id` 筛选） | 任何已登录成员 | ✅ | F-RPL-01 | — |
| GET | `/api/v1/replenishment/items/{id}` | 建议详情（含来源分析快照追溯） | 任何已登录成员 | ✅ | F-RPL-01 | `RPL-8004` |
| PATCH | `/api/v1/replenishment/items/{id}` | 修改建议（数量 `qty`、`supplier_id`、`expected_arrival_date`、`remark`；仅 `SUGGESTED`/`PENDING_REVIEW`） | OPERATOR+ | ❌ | F-RPL-02 | `RPL-8001`/`8003`/`8006`/`8009`/`8010`/`8011` |
| POST | `/api/v1/replenishment/items/{id}/submit` | 提交审核（`SUGGESTED→PENDING_REVIEW`） | OPERATOR+ | ✅ | F-RPL-03 | `RPL-8001`/`8002` |
| POST | `/api/v1/replenishment/items/{id}/withdraw` | 撤回提交（`PENDING_REVIEW→SUGGESTED`） | OPERATOR+ | ✅ | F-RPL-03 | `RPL-8001` |
| POST | `/api/v1/replenishment/items/{id}/approve` | 审批通过（`PENDING_REVIEW→APPROVED`） | APPROVER/ADMIN/OWNER | ✅ | F-RPL-04 | `RPL-8001`/`8002`/`8003`/`8007`、`TEAM-2003` |
| POST | `/api/v1/replenishment/items/{id}/reject` | 驳回（`PENDING_REVIEW→REJECTED`，`comment` 必填） | APPROVER/ADMIN/OWNER | ✅ | F-RPL-04 | `RPL-8001`/`8002`/`8005` |
| POST | `/api/v1/replenishment/items/{id}/cancel` | 作废建议（→`CANCELLED`） | OPERATOR+ | ✅ | F-RPL-05 | `RPL-8001` |
| GET | `/api/v1/replenishment/items/{id}/reviews` | 审核记录时间线（`replenishment_reviews`） | 任何已登录成员 | ✅ | F-RPL-06 | `RPL-8004` |
| POST | `/api/v1/replenishment/items/batch-submit` | 批量提交（P1） | OPERATOR+ | ❌ | F-RPL-07 | `RPL-8001` |
| POST | `/api/v1/replenishment/items/batch-approve` | 批量审批（P1） | APPROVER/ADMIN/OWNER | ❌ | F-RPL-07 | `RPL-8001`/`8007` |

### 10.9 purchasing（采购与到货）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/purchasing/orders` | 采购单列表（按状态/供应商/时间筛选） | 任何已登录成员 | ✅ | F-PO-06 | — |
| POST | `/api/v1/purchasing/orders` | 手工创建采购单（草稿 `DRAFT`） | OPERATOR+（仅能起 `DRAFT`） | ⚠️ | F-PO-01 | `PO-9006`/`9007`/`9011`、`TEAM-2003` |
| POST | `/api/v1/purchasing/orders/from-replenishment` | 由已审批建议生成采购单（`source=REPLENISHMENT`，草稿） | APPROVER/ADMIN/OWNER | ⚠️ | F-PO-01 | `PO-9001`/`9007`/`9011`、`TEAM-2003` |
| GET | `/api/v1/purchasing/orders/{id}` | 采购单详情（含明细与到货进度） | 任何已登录成员 | ✅ | F-PO-06 | `PO-9004` |
| PATCH | `/api/v1/purchasing/orders/{id}` | 修改草稿（供应商、仓库、明细、备注；仅 `DRAFT`） | OPERATOR+ | ❌ | F-PO-01 | `PO-9003`/`9011`/`9007` |
| POST | `/api/v1/purchasing/orders/{id}/confirm` | 确认采购单（`DRAFT→PENDING`，视为审批） | APPROVER/ADMIN/OWNER | ✅ | F-PO-02 | `PO-9003`、`TEAM-2003` |
| POST | `/api/v1/purchasing/orders/{id}/order` | 标记已下单（`PENDING→ORDERED`，写入 `ordered_at`，在途生效） | APPROVER/ADMIN/OWNER | ✅ | F-PO-02 | `PO-9003` |
| POST | `/api/v1/purchasing/orders/{id}/cancel` | 取消采购单（`DRAFT`/`PENDING`/`ORDERED`/`PARTIALLY_RECEIVED`→`CANCELLED`） | APPROVER/ADMIN/OWNER | ✅ | F-PO-03 | `PO-9003`/`9009` |
| GET | `/api/v1/purchasing/receipts` | 到货单列表 | 任何已登录成员 | ✅ | F-PO-06 | — |
| POST | `/api/v1/purchasing/receipts` | 登记到货（草稿 `DRAFT`，支持部分到货） | OPERATOR+ | ❌ | F-PO-04 | `PO-9002`/`9003`/`9012`/`9005` |
| GET | `/api/v1/purchasing/receipts/{id}` | 到货单详情 | 任何已登录成员 | ✅ | F-PO-06 | `PO-9005` |
| POST | `/api/v1/purchasing/receipts/{id}/confirm` | 确认到货（`DRAFT→CONFIRMED`，触发 `PURCHASE_INBOUND` 入库） | OPERATOR+ | ⚠️ | F-PO-05 | `PO-9002`/`9003`/`9005`、`INV-5002`、`COM-0009` |
| POST | `/api/v1/purchasing/receipts/{id}/cancel` | 作废到货单（仅 `DRAFT→CANCELLED`；P1） | OPERATOR+ | ✅ | F-PO-07 | `PO-9008`/`9003` |

### 10.10 audit（审计）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/audit/logs` | 审计日志查询（按对象/操作人/动作/结果/时间筛选） | OWNER/ADMIN | ✅ | F-AUD-02 | `TEAM-2003` |
| GET | `/api/v1/audit/logs/export` | 导出审计 CSV（P1） | OWNER/ADMIN | ✅ | F-AUD-03 | `TEAM-2003` |

### 10.11 system（系统）

| 方法 | 路径 | 说明 | 所需角色 | 幂等 | 功能 | 主要错误码 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/health` | 存活探针（liveness），返回服务与版本 | 匿名 | ✅ | — | — |
| GET | `/api/v1/health/ready` | 就绪探针（readiness），检测 DB 连通性 | 匿名 | ✅ | — | `COM-0012` |

> Spring Boot Actuator 若启用，建议将 `/actuator/health` 映射为上述语义，且 `/actuator/*` 仅内网可达（`SYSTEM_ARCHITECTURE.md §8.1`）。

**接口数量统计（本节）**：identity 6 + team 7 + catalog 24 + inventory 10 + sales 2 + importdata 11 + analysis 6 + replenishment 11 + purchasing 11 + audit 2 + system 2 = **92 个接口**（含 P1/P2 标注项）。P0 接口约 **78** 个。

---

## 11. 关键接口完整示例

> 以下示例中：`X-Request-Id` 为可选传入；`Authorization` 除登录/刷新外必填。**数量与金额以字符串传输**（§9.4）。

### 11.1 `POST /api/v1/auth/login`

**请求**

```
POST /api/v1/auth/login
Content-Type: application/json
X-Request-Id: req_login_001
```

```json
{
  "username": "operator01",
  "password": "S3cret!Pass"
}
```

- `username`：必填，登录名或邮箱。
- `password`：必填，明文（HTTPS 传输，服务端 BCrypt 校验）。

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMDAxIn0.signature",
    "refreshToken": "rt_9f3a2c1e7b4d",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": {
      "user_id": 1001,
      "username": "operator01",
      "display_name": "小王",
      "status": "ACTIVE"
    },
    "team": {
      "team_id": 7,
      "team_name": "星海优选",
      "default_timezone": "Asia/Shanghai"
    },
    "role": "OPERATOR"
  },
  "requestId": "req_login_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 1 · 用户名或密码错误 `401`**

```json
{
  "code": "AUTH-1002",
  "message": "用户名或密码错误",
  "data": null,
  "details": null,
  "requestId": "req_login_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

> **防枚举**：用户不存在与密码错误**返回同一错误码与提示**。

**失败响应 2 · 账号已禁用 `403`**

```json
{
  "code": "AUTH-1003",
  "message": "账号已被禁用，请联系管理员",
  "data": null,
  "details": null,
  "requestId": "req_login_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 3 · 参数校验失败 `400`**

```json
{
  "code": "COM-0001",
  "message": "请求参数校验失败",
  "data": null,
  "details": [
    { "field": "password", "message": "不能为空", "rejectedValue": null }
  ],
  "requestId": "req_login_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**业务行为与权限**：匿名接口（无需 `Authorization`）。校验密码 → 校验 `UserStatus.ACTIVE` → 若用户属于多个团队，`team_id` 取默认团队或由后续 `switch-team` 决定（首期取最早加入的 `ACTIVE` 团队）。登录成功/失败写 `audit_logs`（`AUTH_LOGIN_SUCCESS`/`AUTH_LOGIN_FAILED`，`team_id = 0` 系统域，见 `DATABASE_DESIGN.md §6-J.1`、`D-11`）。

---

### 11.2 `POST /api/v1/products` 与 `POST /api/v1/products/{id}/skus`

**请求 A · 新建商品**

```
POST /api/v1/products
Authorization: Bearer <token>
Content-Type: application/json
X-Request-Id: req_prod_001
```

```json
{
  "product_code": "P-BAG-001",
  "name": "轻旅行双肩包",
  "category": "箱包",
  "brand": "UPack",
  "unit": "个",
  "default_supplier_id": 3001,
  "spec_summary": "多色可选",
  "remark": ""
}
```

**成功响应 `201`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "id": 2001,
    "product_code": "P-BAG-001",
    "name": "轻旅行双肩包",
    "category": "箱包",
    "brand": "UPack",
    "unit": "个",
    "status": "ACTIVE",
    "default_supplier_id": 3001,
    "spec_summary": "多色可选",
    "created_at": "2026-10-14T08:00:00.000Z"
  },
  "requestId": "req_prod_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 1 · 编码重复 `409`**

```json
{
  "code": "CAT-3002",
  "message": "商品编码在该团队内已存在",
  "data": null,
  "details": [
    { "field": "product_code", "message": "已存在相同编码", "rejectedValue": "P-BAG-001" }
  ],
  "requestId": "req_prod_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 2 · 默认供应商不存在或跨团队 `403`**

```json
{
  "code": "CAT-3005",
  "message": "关联的供应商不属于当前团队",
  "data": null,
  "details": [
    { "field": "default_supplier_id", "message": "该供应商不存在或不属于当前团队", "rejectedValue": 9999 }
  ],
  "requestId": "req_prod_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**请求 B · 在商品下新建 SKU**

```
POST /api/v1/products/2001/skus
Authorization: Bearer <token>
Content-Type: application/json
X-Request-Id: req_sku_001
```

```json
{
  "sku_code": "SKU-BAG-001-BLK",
  "name": "轻旅行双肩包 黑色",
  "barcode": "6901234567890",
  "spec_json": { "color": "黑色", "size": "均码" },
  "supplier_id": 3001,
  "default_lead_time_days": 7,
  "moq": "50.000",
  "order_multiple": "10.000",
  "qty_step": "1.000",
  "purchase_price": "35.5000",
  "sale_price": "89.0000",
  "listed_date": "2026-09-01"
}
```

**成功响应 `201`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "id": 5001,
    "team_id": 7,
    "product_id": 2001,
    "sku_code": "SKU-BAG-001-BLK",
    "name": "轻旅行双肩包 黑色",
    "status": "ACTIVE",
    "supplier_id": 3001,
    "default_lead_time_days": 7,
    "moq": "50.000",
    "order_multiple": "10.000",
    "qty_step": "1.000",
    "listed_date": "2026-09-01",
    "created_at": "2026-10-14T08:00:00.000Z"
  },
  "requestId": "req_sku_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 1 · SKU 编码重复 `409`**

```json
{
  "code": "CAT-3002",
  "message": "SKU 编码在该团队内已存在",
  "data": null,
  "details": [
    { "field": "sku_code", "message": "已存在相同编码", "rejectedValue": "SKU-BAG-001-BLK" }
  ],
  "requestId": "req_sku_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 2 · 商品不存在 `404`**

```json
{
  "code": "CAT-3001",
  "message": "商品不存在",
  "data": null,
  "details": null,
  "requestId": "req_sku_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**业务行为与权限**：`OPERATOR` 及以上可写（`USER_ROLES.md §2.2` #2）。`team_id` 由 `TeamContext` 注入；`product_id` 校验属于当前团队；`supplier_id` 校验属于当前团队（否则 `CAT-3005`）。`product_code`/`sku_code` 团队内唯一（`uk_prod_team_code`/`uk_sku_team_code`）。写审计 `MASTER_DATA_STATUS_CHANGE`（新建时 `biz_type=PRODUCT`/`SKU`）。

> **注意（跨文档一致性）**：`DATA_DICTIONARY.md §5.7` 中 SKU 字段名为 `default_lead_time_days`（SKU 级交期），API 字段沿用该名；`DESIGN_BASIS §10.2` 描述回退链时写作 `skus.default_lead_time_days`，一致。

---

### 11.3 导入四步：上传 → 映射 → 校验 → 确认

#### 步骤 1 · `POST /api/v1/import/batches`（上传）

**请求**

```
POST /api/v1/import/batches
Authorization: Bearer <token>
Content-Type: multipart/form-data; boundary=----bnd
X-Request-Id: req_imp_001
```

```
------bnd
Content-Disposition: form-data; name="bizType"

SALES_DAILY
------bnd
Content-Disposition: form-data; name="templateVersion"

v1
------bnd
Content-Disposition: form-data; name="file"; filename="sales_20261013.xlsx"
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet

<二进制文件内容>
------bnd--
```

**成功响应 `201`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "batch_id": 9001,
    "batch_no": "IMP-20261014-000001",
    "biz_type": "SALES_DAILY",
    "file_name": "sales_20261013.xlsx",
    "file_hash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "file_size": 48210,
    "status": "PARSED",
    "total_rows": 3200,
    "columns": ["sku_code", "warehouse_code", "sale_date", "qty", "return_qty", "sales_amount"],
    "preview": [
      { "row_no": 1, "sku_code": "SKU-BAG-001-BLK", "warehouse_code": "WH-MAIN", "sale_date": "2026-10-13", "qty": "12.000" }
    ],
    "created_at": "2026-10-14T08:00:00.000Z"
  },
  "requestId": "req_imp_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 1 · 重复导入 `409`**

```json
{
  "code": "IMP-4004",
  "message": "该文件已导入过，如需重新导入请使用强制重导",
  "data": {
    "existing_batch_id": 8800,
    "existing_batch_no": "IMP-20261013-000007"
  },
  "details": null,
  "requestId": "req_imp_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 2 · 文件类型或大小不合法 `400`**

```json
{
  "code": "IMP-4001",
  "message": "仅支持 CSV 或 Excel 文件，且不超过 20MB",
  "data": null,
  "details": [
    { "field": "file", "message": "文件大小 24.5MB 超过上限 20MB", "rejectedValue": null }
  ],
  "requestId": "req_imp_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**业务行为与权限**：`OPERATOR` 及以上（`USER_ROLES.md §2.2` #1）。文件级幂等：`(team_id, file_hash, biz_type)`（`uk_imp_team_hash_biz`）命中已存在**成功批次** → `IMP-4004`。创建批次写审计 `IMPORT_BATCH_CREATE`。

#### 步骤 2 · `PUT /api/v1/import/batches/{id}/mapping`

```
PUT /api/v1/import/batches/9001/mapping
Authorization: Bearer <token>
Content-Type: application/json
X-Request-Id: req_imp_002
```

```json
{
  "field_mapping_json": {
    "sku_code": "SKU编码",
    "warehouse_code": "仓库编码",
    "sale_date": "销售日期",
    "qty": "净销量",
    "return_qty": "退货量",
    "sales_amount": "销售金额"
  },
  "parse_options_json": {
    "sheet_name": "Sheet1",
    "header_row": 1,
    "encoding": "UTF-8",
    "date_format": "yyyy-MM-dd"
  }
}
```

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "batch_id": 9001,
    "status": "MAPPED",
    "field_mapping_json": {
      "sku_code": "SKU编码",
      "warehouse_code": "仓库编码",
      "sale_date": "销售日期",
      "qty": "净销量",
      "return_qty": "退货量",
      "sales_amount": "销售金额"
    }
  },
  "requestId": "req_imp_002",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 1 · 必填列缺失 `400`**

```json
{
  "code": "IMP-4003",
  "message": "缺少必填列的字段映射",
  "data": null,
  "details": [
    { "field": "field_mapping_json.qty", "message": "日销售导入必须映射净销量列", "rejectedValue": null }
  ],
  "requestId": "req_imp_002",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 2 · 批次状态不允许 `422`**

```json
{
  "code": "IMP-4005",
  "message": "当前批次状态不允许提交字段映射",
  "data": null,
  "details": [
    { "field": "status", "message": "期望状态 PARSED，当前状态 VALIDATED", "rejectedValue": "VALIDATED" }
  ],
  "requestId": "req_imp_002",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**业务行为与权限**：`OPERATOR`+。状态守卫 `PARSED → MAPPED`；`field_mapping_json` 在 `MAPPED` 后不可变（`DATA_DICTIONARY.md §5.12`）。

#### 步骤 3 · `POST /api/v1/import/batches/{id}/validate`

```
POST /api/v1/import/batches/9001/validate
Authorization: Bearer <token>
X-Request-Id: req_imp_003
```

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "batch_id": 9001,
    "status": "VALIDATED",
    "total_rows": 3200,
    "error_rows": 12,
    "warning_rows": 5,
    "errors": [
      { "row_no": 45, "error_level": "ERROR", "error_code": "CAT-3001", "error_field": "sku_code", "error_message": "SKU 编码 SKU-XXX 不存在" },
      { "row_no": 78, "error_level": "WARNING", "error_code": "IMP-4006", "error_field": "sale_date", "error_message": "该日期已存在销售数据，确认导入后将覆盖" }
    ]
  },
  "requestId": "req_imp_003",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 1 · 全部行校验失败 `422`**

```json
{
  "code": "IMP-4010",
  "message": "所有数据行均未通过校验，请修正后重新导入",
  "data": {
    "batch_id": 9001,
    "status": "FAILED",
    "total_rows": 3200,
    "error_rows": 3200
  },
  "details": null,
  "requestId": "req_imp_003",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 2 · 批次状态不允许 `422`**

```json
{
  "code": "IMP-4005",
  "message": "当前批次状态不允许校验",
  "data": null,
  "details": [
    { "field": "status", "message": "期望状态 MAPPED，当前状态 PARSED", "rejectedValue": "PARSED" }
  ],
  "requestId": "req_imp_003",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**业务行为与权限**：`OPERATOR`+。状态守卫 `MAPPED → VALIDATED`。校验内容：必填/类型/编码存在性/同团队关联/日期合法性；错误写 `import_row_errors`（仅失败行存 `raw_data`）。`ERROR` 行阻断该行，`WARNING` 行仅提示。

#### 步骤 4 · `POST /api/v1/import/batches/{id}/confirm`

```
POST /api/v1/import/batches/9001/confirm
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: imp-confirm-9001-a1b2c3
X-Request-Id: req_imp_004
```

```json
{
  "skip_warning_rows": false,
  "expected_total_rows": 3200
}
```

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "batch_id": 9001,
    "status": "PARTIAL_SUCCESS",
    "total_rows": 3200,
    "success_rows": 3188,
    "failed_rows": 12,
    "started_at": "2026-10-14T08:00:01.000Z",
    "finished_at": "2026-10-14T08:00:04.500Z"
  },
  "requestId": "req_imp_004",
  "timestamp": "2026-10-14T08:00:05.000Z"
}
```

**失败响应 1 · 幂等键冲突（请求体不一致）`409`**

```json
{
  "code": "COM-0009",
  "message": "幂等键已被使用且本次请求内容与原请求不一致",
  "data": null,
  "details": [
    { "field": "Idempotency-Key", "message": "同一幂等键的请求体必须一致", "rejectedValue": "imp-confirm-9001-a1b2c3" }
  ],
  "requestId": "req_imp_004",
  "timestamp": "2026-10-14T08:00:05.000Z"
}
```

**失败响应 2 · 校验未通过不可导入 `422`**

```json
{
  "code": "IMP-4009",
  "message": "批次存在错误行，请先修正或跳过错误行后再导入",
  "data": {
    "batch_id": 9001,
    "status": "VALIDATED",
    "error_rows": 12
  },
  "details": null,
  "requestId": "req_imp_004",
  "timestamp": "2026-10-14T08:00:05.000Z"
}
```

**业务行为与权限**：`OPERATOR`+，**必须携带 `Idempotency-Key`**。状态守卫 `VALIDATED → IMPORTING → COMPLETED/PARTIAL_SUCCESS/FAILED`。落库经 `inventory`/`sales`/`catalog` 的 application 层服务接口（**库存走 `InventoryWriteService`，不直写余额表**）。写审计 `IMPORT_BATCH_COMPLETE`；销售 upsert 写 `SALES_DAILY_UPSERT`。

---

### 11.4 `GET /api/v1/inventory/balances` 与 `POST /api/v1/inventory/adjustments`

**请求 A · 库存余额查询**

```
GET /api/v1/inventory/balances?warehouse_id=100&sku_id=5001&page=1&pageSize=20&sort=on_hand_qty,asc
Authorization: Bearer <token>
X-Request-Id: req_inv_001
```

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "list": [
      {
        "id": 70001,
        "warehouse_id": 100,
        "sku_id": 5001,
        "sku_code": "SKU-BAG-001-BLK",
        "on_hand_qty": "120.000",
        "in_transit_qty": "300.000",
        "committed_qty": "0.000",
        "available_qty": "120.000",
        "last_txn_at": "2026-10-13T10:00:00.000Z",
        "version": 6
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  },
  "requestId": "req_inv_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

> `sku_code` 为**展示字段，来源 `skus.sku_code`**（JOIN 得出），非 `inventory_balances` 字段；`available_qty` 为**生成列**（`on_hand_qty − committed_qty`，不含在途），仅展示，**禁止参与补货判断**（`DESIGN_BASIS §3.1`）。

**失败响应 1 · 分页参数非法 `400`**

```json
{
  "code": "COM-0007",
  "message": "分页或排序参数不合法",
  "data": null,
  "details": [
    { "field": "pageSize", "message": "pageSize 不能超过 200", "rejectedValue": 500 }
  ],
  "requestId": "req_inv_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 2 · 跨团队查询 `403`**

```json
{
  "code": "TEAM-2001",
  "message": "无权访问该团队数据",
  "data": null,
  "details": null,
  "requestId": "req_inv_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**请求 B · 库存调整（盘点）**

```
POST /api/v1/inventory/adjustments
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: inv-adj-20261014-sku5001-01
X-Request-Id: req_inv_002
```

```json
{
  "warehouse_id": 100,
  "sku_id": 5001,
  "txn_type": "COUNT_ADJUST",
  "target_on_hand_qty": "112.000",
  "expected_version": 6,
  "reason": "2026-10-14 月度盘点差异",
  "occurred_at": "2026-10-14T08:00:00.000Z"
}
```

- `txn_type` ∈ `COUNT_ADJUST` / `MANUAL_ADJUST`。
- `MANUAL_ADJUST` 需 `ADMIN` 及以上（`USER_ROLES.md §2.3`），否则 `TEAM-2003`。

**成功响应 `201`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "transaction_id": 88001,
    "warehouse_id": 100,
    "sku_id": 5001,
    "txn_type": "COUNT_ADJUST",
    "qty_delta": "-8.000",
    "qty_before": "120.000",
    "qty_after": "112.000",
    "balance_version": 7,
    "occurred_at": "2026-10-14T08:00:00.000Z"
  },
  "requestId": "req_inv_002",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 1 · 并发更新冲突 `409`**

```json
{
  "code": "INV-5002",
  "message": "库存数据已被同时更新，请刷新后重试",
  "data": null,
  "details": [
    { "field": "expected_version", "message": "期望版本 6，实际版本 7", "rejectedValue": 6 }
  ],
  "requestId": "req_inv_002",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 2 · 出库将导致负库存 `409`**

```json
{
  "code": "INV-5006",
  "message": "调整后库存不能小于 0",
  "data": null,
  "details": [
    { "field": "target_on_hand_qty", "message": "调整后为 -5.000，不允许负库存", "rejectedValue": "-5.000" }
  ],
  "requestId": "req_inv_002",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 3 · 角色不足 `403`**

```json
{
  "code": "TEAM-2003",
  "message": "当前角色无权执行该操作",
  "data": null,
  "details": null,
  "requestId": "req_inv_002",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**业务行为与权限**：所有库存变更**唯一入口** `InventoryWriteService`（`SYSTEM_ARCHITECTURE.md §5` 边界 8）。同一事务内写 `inventory_transactions` 并更新 `inventory_balances`，且 `qty_after == balance.on_hand_qty`。校验：`on_hand_qty ≥ 0`（`D-08`）、乐观锁 `version`、幂等键 `uk_invtxn_team_idem`。写审计 `INVENTORY_COUNT_ADJUST` / `INVENTORY_MANUAL_ADJUST`。

---

### 11.5 `POST /api/v1/analysis/runs` 与 `GET /api/v1/analysis/runs/{id}`（RUNNING → DEGRADED）

**请求 · 创建分析任务**

```
POST /api/v1/analysis/runs
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: anl-20261014-001
X-Request-Id: req_anl_001
```

```json
{
  "scope_type": "SELECTED_SKUS",
  "scope_json": { "sku_ids": [5001, 5002, 5003] },
  "warehouse_id": 100,
  "date_from": "2026-09-16",
  "date_to": "2026-10-13",
  "params": {
    "window_days": 28,
    "min_days": 7,
    "coverage_days": 7,
    "lead_time_default": 7,
    "ss_ratio": "0.5000",
    "cv_high": "1.0000",
    "budget_limit": null,
    "enable_llm": true
  },
  "algorithm_version": "avg-ma-3sigma-v1",
  "prompt_version": "explain_risk.v1"
}
```

**成功响应 `202`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "run_id": 30001,
    "run_no": "ANL-20261014-000001",
    "status": "RUNNING",
    "scope_type": "SELECTED_SKUS",
    "total_skus": 3,
    "processed_skus": 0,
    "progress": 5,
    "current_step": "VALIDATING",
    "llm_status": "PENDING",
    "timeout_at": "2026-10-14T08:05:00.000Z",
    "created_at": "2026-10-14T08:00:00.000Z"
  },
  "requestId": "req_anl_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 1 · 同 SKU 范围已有运行中任务 `409`**

```json
{
  "code": "ANL-7001",
  "message": "该 SKU 范围已有正在运行的分析任务，请等待完成或取消后再试",
  "data": {
    "existing_run_id": 30000,
    "existing_run_no": "ANL-20261014-000000"
  },
  "details": null,
  "requestId": "req_anl_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 2 · 数据质量预检未通过 `422`**

```json
{
  "code": "ANL-7008",
  "message": "所选数据不满足分析条件：有效销售天数不足",
  "data": {
    "run_id": 30001,
    "status": "FAILED",
    "data_quality": {
      "total_skus": 3,
      "analyzable_skus": 1,
      "insufficient_skus": 2,
      "reason": "SKU 5002/5003 窗口内有效销售天数 < min_days"
    }
  },
  "details": null,
  "requestId": "req_anl_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**失败响应 3 · 角色不足 `403`**

```json
{
  "code": "TEAM-2003",
  "message": "当前角色无权创建分析任务",
  "data": null,
  "details": null,
  "requestId": "req_anl_001",
  "timestamp": "2026-10-14T08:00:00.000Z"
}
```

**轮询 · `GET /api/v1/analysis/runs/30001`（RUNNING）**

```
GET /api/v1/analysis/runs/30001
Authorization: Bearer <token>
X-Request-Id: req_anl_002
```

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "run_id": 30001,
    "run_no": "ANL-20261014-000001",
    "status": "RUNNING",
    "scope_type": "SELECTED_SKUS",
    "warehouse_id": 100,
    "total_skus": 3,
    "processed_skus": 2,
    "progress": 66,
    "current_step": "LLM_EXPLAIN",
    "llm_status": "PENDING",
    "timeout_at": "2026-10-14T08:05:00.000Z",
    "started_at": "2026-10-14T08:00:01.000Z",
    "finished_at": null
  },
  "requestId": "req_anl_002",
  "timestamp": "2026-10-14T08:00:08.000Z"
}
```

**轮询 · 终态（DEGRADED：规则成功、LLM 失败）**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "run_id": 30001,
    "run_no": "ANL-20261014-000001",
    "status": "DEGRADED",
    "scope_type": "SELECTED_SKUS",
    "total_skus": 3,
    "processed_skus": 3,
    "progress": 100,
    "current_step": "DONE",
    "llm_status": "TIMEOUT",
    "llm_error_message": "LLM 调用超时（30s），已降级为无解释结果",
    "risk_sku_count": 2,
    "suggestion_count": 1,
    "data_start_date": "2026-09-16",
    "data_end_date": "2026-10-13",
    "timeout_at": "2026-10-14T08:05:00.000Z",
    "started_at": "2026-10-14T08:00:01.000Z",
    "finished_at": "2026-10-14T08:00:12.000Z"
  },
  "requestId": "req_anl_002",
  "timestamp": "2026-10-14T08:00:12.500Z"
}
```

**业务行为与权限**：创建需 `OPERATOR`/`APPROVER`/`ADMIN`/`OWNER`；`VIEWER` 取 `TEAM-2003`（`DESIGN_BASIS §5.1` 第 3 条）。`run_id` 由 Java 生成（`analysis_runs.id`，`§13.2`）；Java 建 `PENDING` 记录 → 调 `analysis:validate`（5s）→ 调 `POST /internal/v1/analysis/runs` 得 202 → 置 `RUNNING`。**Java 是 `AnalysisRunStatus` 权威**；`now > timeout_at` 且仍 `RUNNING` → Java 推进 `TIMEOUT`。`DEGRADED` 时业务可用，`llm_explanation` 为 `NULL`。写审计 `ANALYSIS_RUN_CREATE` / `ANALYSIS_RUN_FINISH`。

---

### 11.6 `GET /api/v1/analysis/runs/{id}/items`（含风险与 LLM 解释）

```
GET /api/v1/analysis/runs/30001/items?risk_level=HIGH&page=1&pageSize=20&sort=avg_daily_sales,desc
Authorization: Bearer <token>
X-Request-Id: req_anl_003
```

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "list": [
      {
        "id": 41001,
        "run_id": 30001,
        "sku_id": 5001,
        "sku_code": "SKU-BAG-001-BLK",
        "warehouse_id": 100,
        "on_hand_qty": "8.000",
        "in_transit_qty": "0.000",
        "effective_in_transit_qty": "0.000",
        "committed_qty": "0.000",
        "inventory_position_qty": "8.000",
        "available_qty": "8.000",
        "avg_daily_sales": "6.250",
        "sales_window_days": 28,
        "valid_days": 26,
        "total_sales_qty": "162.500",
        "cv": "0.4200",
        "lead_time_days": 7,
        "lead_time_source": "SKU",
        "coverage_days": 7,
        "safety_stock_qty": "21.875",
        "volatility_adjusted": false,
        "qty_base": "79.375",
        "qty_suggest": "80.000",
        "moq_applied": "50.000",
        "order_multiple_applied": "10.000",
        "truncated_by_budget": false,
        "risk_level": "HIGH",
        "risk_types": ["STOCKOUT", "LOW_STOCK"],
        "risk_findings": [
          {
            "rule_code": "R-STOCKOUT",
            "inputs": { "on_hand_qty": "8.000", "D": "6.250" },
            "threshold": "on_hand_qty / D <= 2 天",
            "result": "HIGH"
          },
          {
            "rule_code": "R-LOWSTOCK",
            "inputs": { "IP": "8.000", "D": "6.250", "L": 7, "R": 7 },
            "threshold": "IP < D × (L + R) = 87.500",
            "result": "MEDIUM"
          }
        ],
        "data_quality_json": { "level": "GOOD", "valid_days": 26, "missing_days": 2, "outlier_days": 0 },
        "llm_explanation": "SKU-BAG-001-BLK 当前现有库存 8 件，近 28 天日均销量约 6.25 件，仅够支撑约 1.3 天销售（低于 2 天警戒线），判定为缺货高风险。按采购交期 7 天与覆盖天数 7 天计算，理论补货需求约 79.4 件，叠加最小起订量后建议补货 80 件。数据覆盖 26/28 天，质量良好，结论可信。",
        "llm_evidence": ["on_hand_qty", "avg_daily_sales", "lead_time_days", "coverage_days", "qty_suggest"],
        "llm_status": "SUCCEEDED",
        "warnings": []
      },
      {
        "id": 41002,
        "run_id": 30001,
        "sku_id": 5003,
        "sku_code": "SKU-BAG-002-RED",
        "warehouse_id": 100,
        "on_hand_qty": "0.000",
        "avg_daily_sales": null,
        "valid_days": 3,
        "lead_time_days": 7,
        "lead_time_source": "DEFAULT",
        "coverage_days": 7,
        "qty_base": null,
        "qty_suggest": null,
        "risk_level": "UNKNOWN",
        "risk_types": ["DATA_INSUFFICIENT"],
        "risk_findings": [
          {
            "rule_code": "R-DATAINSUFFICIENT",
            "inputs": { "valid_days": 3, "min_days": 7 },
            "threshold": "n < MIN_DAYS(7)",
            "result": "UNKNOWN"
          }
        ],
        "data_quality_json": { "level": "INSUFFICIENT", "valid_days": 3, "missing_days": 25, "outlier_days": 0 },
        "llm_explanation": null,
        "llm_evidence": [],
        "llm_status": "SKIPPED",
        "warnings": ["有效销售天数不足，未产出补货建议"]
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 2
  },
  "requestId": "req_anl_003",
  "timestamp": "2026-10-14T08:00:13.000Z"
}
```

**失败响应 1 · 运行不存在 `404`**

```json
{
  "code": "ANL-7005",
  "message": "分析任务不存在",
  "data": null,
  "details": null,
  "requestId": "req_anl_003",
  "timestamp": "2026-10-14T08:00:13.000Z"
}
```

**失败响应 2 · 结果尚未就绪 `409`**

```json
{
  "code": "ANL-7012",
  "message": "分析结果尚未生成，请等待任务完成",
  "data": { "run_id": 30001, "status": "RUNNING", "progress": 40 },
  "details": null,
  "requestId": "req_anl_003",
  "timestamp": "2026-10-14T08:00:13.000Z"
}
```

**业务行为与权限**：所有已登录成员（含 `VIEWER`）可查。数据来源 `analysis_items`（**不可变快照**）。`risk_findings` 对应 `analysis_items.risk_detail_json`；`data_quality_json` 为 `analysis_items.data_quality_json`；`llm_evidence` 与 `llm_status` 来源于 Python 返回并经 Java 落库（`llm_explanation` → `TEXT` 列）。查询 `RUNNING` 状态下的 items 返回部分结果或 `ANL-7012`（按 `processed_skus` 决定，建议 `progress<100` 时返回 `ANL-7012`）。

---

### 11.7 `POST /api/v1/replenishment/items/{id}/submit` 与 `.../approve`

**请求 A · 提交审核**

```
POST /api/v1/replenishment/items/60001/submit
Authorization: Bearer <token>
X-Request-Id: req_rpl_001
```

（无请求体，或可传 `{ "comment": "已核对，请审批" }`）

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "replenishment_item_id": 60001,
    "status": "PENDING_REVIEW",
    "qty": "80.000",
    "submitted_at": "2026-10-14T08:10:00.000Z",
    "review_id": 70001,
    "action": "SUBMIT",
    "from_status": "SUGGESTED",
    "to_status": "PENDING_REVIEW"
  },
  "requestId": "req_rpl_001",
  "timestamp": "2026-10-14T08:10:00.000Z"
}
```

**失败响应 1 · 状态不允许 `422`**

```json
{
  "code": "RPL-8001",
  "message": "当前状态不允许提交审核",
  "data": { "replenishment_item_id": 60001, "status": "APPROVED" },
  "details": null,
  "requestId": "req_rpl_001",
  "timestamp": "2026-10-14T08:10:00.000Z"
}
```

**失败响应 2 · 重复提交 `409`**

```json
{
  "code": "RPL-8002",
  "message": "该建议已提交，请勿重复提交",
  "data": { "replenishment_item_id": 60001, "status": "PENDING_REVIEW" },
  "details": null,
  "requestId": "req_rpl_001",
  "timestamp": "2026-10-14T08:10:00.000Z"
}
```

**请求 B · 审批通过**

```
POST /api/v1/replenishment/items/60001/approve
Authorization: Bearer <token>
Content-Type: application/json
X-Request-Id: req_rpl_002
```

```json
{
  "expected_version": 3,
  "comment": "同意补货，按现有供应商执行"
}
```

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "replenishment_item_id": 60001,
    "status": "APPROVED",
    "qty": "80.000",
    "approved_by": 1005,
    "approved_at": "2026-10-14T08:12:00.000Z",
    "version": 4,
    "review_id": 70002,
    "action": "APPROVE",
    "from_status": "PENDING_REVIEW",
    "to_status": "APPROVED"
  },
  "requestId": "req_rpl_002",
  "timestamp": "2026-10-14T08:12:00.000Z"
}
```

**失败响应 1 · 角色不足（OPERATOR 审批）`403`**

```json
{
  "code": "TEAM-2003",
  "message": "当前角色无权审批补货建议",
  "data": null,
  "details": null,
  "requestId": "req_rpl_002",
  "timestamp": "2026-10-14T08:12:00.000Z"
}
```

**失败响应 2 · 乐观锁冲突 `409`**

```json
{
  "code": "RPL-8003",
  "message": "建议已被他人修改，请刷新后重试",
  "data": { "replenishment_item_id": 60001, "current_version": 4 },
  "details": [
    { "field": "expected_version", "message": "期望版本 3，实际版本 4", "rejectedValue": 3 }
  ],
  "requestId": "req_rpl_002",
  "timestamp": "2026-10-14T08:12:00.000Z"
}
```

**失败响应 3 · 驳回未填意见（针对 reject）`400`**

```json
{
  "code": "RPL-8005",
  "message": "驳回补货建议必须填写审核意见",
  "data": null,
  "details": [
    { "field": "comment", "message": "驳回时 comment 不能为空", "rejectedValue": null }
  ],
  "requestId": "req_rpl_002",
  "timestamp": "2026-10-14T08:12:00.000Z"
}
```

**业务行为与权限**：`submit`/`withdraw` 需 `OPERATOR`+；`approve`/`reject` 需 `APPROVER`/`ADMIN`/`OWNER`（`USER_ROLES.md §2.2` #7/#8）。状态机 `ReplenishmentItemStatus`（`DESIGN_BASIS §9.11`）。每次动作写 `replenishment_reviews`（含 `reviewer_role` 快照、`from_status`/`to_status`、`qty_before`/`qty_after`）。写审计 `REPLENISHMENT_SUBMIT`/`REPLENISHMENT_APPROVE`/`REPLENISHMENT_REJECT`。**四眼原则**（`D-09` 建议）：若启用，提交人不得作为同一建议的审批人，否则 `RPL-8007`。

---

### 11.8 `POST /api/v1/purchasing/orders/from-replenishment` 与 `.../{id}/confirm`

**请求 A · 由建议生成采购单**

```
POST /api/v1/purchasing/orders/from-replenishment
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: po-from-repl-60001-60002
X-Request-Id: req_po_001
```

```json
{
  "replenishment_item_ids": [60001, 60002],
  "warehouse_id": 100,
  "expected_arrival_date": "2026-10-21",
  "remark": "10 月第二周常规补货"
}
```

**成功响应 `201`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "purchase_order_id": 80001,
    "po_no": "PO-20261014-000001",
    "status": "DRAFT",
    "source": "REPLENISHMENT",
    "supplier_id": 3001,
    "warehouse_id": 100,
    "expected_arrival_date": "2026-10-21",
    "total_qty": "160.000",
    "total_amount": "5680.00",
    "items": [
      { "line_no": 1, "sku_id": 5001, "replenishment_item_id": 60001, "ordered_qty": "80.000", "unit_price": "35.5000", "amount": "2840.00" },
      { "line_no": 2, "sku_id": 5002, "replenishment_item_id": 60002, "ordered_qty": "80.000", "unit_price": "35.5000", "amount": "2840.00" }
    ],
    "created_at": "2026-10-14T08:15:00.000Z"
  },
  "requestId": "req_po_001",
  "timestamp": "2026-10-14T08:15:00.000Z"
}
```

**失败响应 1 · 建议已生成采购单 `409`**

```json
{
  "code": "PO-9001",
  "message": "该补货建议已生成采购单，不能重复转采购",
  "data": { "replenishment_item_id": 60001, "existing_po_item_id": 95001 },
  "details": null,
  "requestId": "req_po_001",
  "timestamp": "2026-10-14T08:15:00.000Z"
}
```

**失败响应 2 · 建议状态不允许（未审批）`422`**

```json
{
  "code": "RPL-8001",
  "message": "仅已审批通过的补货建议可转为采购单",
  "data": { "replenishment_item_id": 60002, "status": "SUGGESTED" },
  "details": null,
  "requestId": "req_po_001",
  "timestamp": "2026-10-14T08:15:00.000Z"
}
```

**请求 B · 确认采购单（DRAFT→PENDING）**

```
POST /api/v1/purchasing/orders/80001/confirm
Authorization: Bearer <token>
Content-Type: application/json
X-Request-Id: req_po_002
```

```json
{
  "expected_version": 1
}
```

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "purchase_order_id": 80001,
    "po_no": "PO-20261014-000001",
    "status": "PENDING",
    "confirmed_by": 1005,
    "confirmed_at": "2026-10-14T08:16:00.000Z",
    "version": 2
  },
  "requestId": "req_po_002",
  "timestamp": "2026-10-14T08:16:00.000Z"
}
```

**失败响应 1 · 角色不足（OPERATOR 确认）`403`**

```json
{
  "code": "TEAM-2003",
  "message": "当前角色无权确认采购单",
  "data": null,
  "details": null,
  "requestId": "req_po_002",
  "timestamp": "2026-10-14T08:16:00.000Z"
}
```

**失败响应 2 · 状态不允许 `409`**

```json
{
  "code": "PO-9003",
  "message": "当前采购单状态不允许该操作",
  "data": { "purchase_order_id": 80001, "status": "RECEIVED" },
  "details": null,
  "requestId": "req_po_002",
  "timestamp": "2026-10-14T08:16:00.000Z"
}
```

**业务行为与权限**：`from-replenishment` 与 `confirm` 均需 `APPROVER`/`ADMIN`/`OWNER`（`DESIGN_BASIS §5.1` 第 2 条；`OPERATOR` 仅能起草 `DRAFT`）。生成采购单时校验 `sku.team_id == po.team_id`、`supplier.team_id == po.team_id`（`§6` 规则 4）。建议 `APPROVED → CONVERTED`，写 `converted_po_item_ids`。`PENDING→ORDERED` 由 `POST .../{id}/order` 触发，写入 `ordered_at`，此时该 PO 的 `ordered_qty − received_qty` 进入 `effective_in_transit_qty` 口径（`DESIGN_BASIS §10.4`）。写审计 `PURCHASE_ORDER_CREATE`/`PURCHASE_ORDER_CONFIRM`/`REPLENISHMENT_CONVERT`。

---

### 11.9 `POST /api/v1/purchasing/receipts` + `.../{id}/confirm`（部分到货）

**请求 A · 登记到货**

```
POST /api/v1/purchasing/receipts
Authorization: Bearer <token>
Content-Type: application/json
X-Request-Id: req_gr_001
```

```json
{
  "purchase_order_id": 80001,
  "warehouse_id": 100,
  "receipt_date": "2026-10-21",
  "remark": "首批到货，剩余下周",
  "items": [
    { "purchase_order_item_id": 95001, "received_qty": "50.000", "unit_price": "35.5000" },
    { "purchase_order_item_id": 95002, "received_qty": "0.000", "unit_price": "35.5000" }
  ]
}
```

**成功响应 `201`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "goods_receipt_id": 120001,
    "receipt_no": "GR-20261021-000001",
    "purchase_order_id": 80001,
    "status": "DRAFT",
    "receipt_date": "2026-10-21",
    "total_qty": "50.000",
    "items": [
      { "goods_receipt_item_id": 130001, "purchase_order_item_id": 95001, "sku_id": 5001, "received_qty": "50.000" }
    ],
    "created_at": "2026-10-14T08:20:00.000Z"
  },
  "requestId": "req_gr_001",
  "timestamp": "2026-10-14T08:20:00.000Z"
}
```

**失败响应 1 · 到货数量超过待收 `409`**

```json
{
  "code": "PO-9002",
  "message": "到货数量超过该明细的待收数量",
  "data": null,
  "details": [
    { "field": "items[0].received_qty", "message": "待收数量为 80.000，本次登记 90.000", "rejectedValue": "90.000" }
  ],
  "requestId": "req_gr_001",
  "timestamp": "2026-10-14T08:20:00.000Z"
}
```

**失败响应 2 · 采购单状态不允许 `409`**

```json
{
  "code": "PO-9003",
  "message": "当前采购单状态不允许登记到货",
  "data": { "purchase_order_id": 80001, "status": "DRAFT" },
  "details": null,
  "requestId": "req_gr_001",
  "timestamp": "2026-10-14T08:20:00.000Z"
}
```

**请求 B · 确认到货（DRAFT→CONFIRMED，触发入库）**

```
POST /api/v1/purchasing/receipts/120001/confirm
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: gr-confirm-120001-x9y8z7
X-Request-Id: req_gr_002
```

```json
{
  "expected_version": 0
}
```

**成功响应 `200`**

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "goods_receipt_id": 120001,
    "receipt_no": "GR-20261021-000001",
    "status": "CONFIRMED",
    "purchase_order_id": 80001,
    "po_status": "PARTIALLY_RECEIVED",
    "confirmed_at": "2026-10-14T08:21:00.000Z",
    "inventory_transactions": [
      { "transaction_id": 89001, "sku_id": 5001, "txn_type": "PURCHASE_INBOUND", "qty_delta": "50.000", "qty_after": "58.000" }
    ]
  },
  "requestId": "req_gr_002",
  "timestamp": "2026-10-14T08:21:00.000Z"
}
```

**失败响应 1 · 幂等键冲突（请求体不一致）`409`**

```json
{
  "code": "COM-0009",
  "message": "幂等键已被使用且本次请求内容与原请求不一致",
  "data": null,
  "details": [
    { "field": "Idempotency-Key", "message": "同一幂等键的请求体必须一致", "rejectedValue": "gr-confirm-120001-x9y8z7" }
  ],
  "requestId": "req_gr_002",
  "timestamp": "2026-10-14T08:21:00.000Z"
}
```

**失败响应 2 · 并发更新冲突 `409`**

```json
{
  "code": "INV-5002",
  "message": "库存数据已被同时更新，请刷新后重试",
  "data": null,
  "details": [
    { "field": "expected_version", "message": "期望版本 6，实际版本 7", "rejectedValue": 6 }
  ],
  "requestId": "req_gr_002",
  "timestamp": "2026-10-14T08:21:00.000Z"
}
```

**业务行为与权限**：登记与确认均需 `OPERATOR`+（`USER_ROLES.md §2.2` #10）；确认到货在 `USER_ROLES.md §4.4` 属高风险操作，需额外校验数量/状态/幂等/同团队。确认动作：`goods_receipts.DRAFT → CONFIRMED`；同事务内按 SKU 写 `inventory_transactions`（`txn_type=PURCHASE_INBOUND`，`biz_type=GOODS_RECEIPT`）并经 `InventoryWriteService` 更新余额；累加 `purchase_order_items.received_qty`；重算 `purchase_orders.received_qty` 与状态（全部到齐 → `RECEIVED`，否则 `PARTIALLY_RECEIVED`）。**一项到货明细 → 一条流水**（`DATABASE_DESIGN.md §6-G`）。写审计 `GOODS_RECEIPT_CONFIRM`。

---

## 12. 契约一致性核对表（接口字段 ↔ 数据字典）

> 用途：后续 CI 中做**字段名一致性检查**。左列为 API JSON 字段，右列为权威来源。**可为展示字段**但必须标注来源，且不得与权威字段同名异义。

| # | API 字段名 | 类型 | 权威来源（表.字段） | 说明 |
| --- | --- | --- | --- | --- |
| 1 | `user_id` | number | `users.id` | — |
| 2 | `username` | string | `users.username` | — |
| 3 | `display_name` | string | `users.display_name` | — |
| 4 | `status`（用户） | string | `users.status` | `UserStatus` |
| 5 | `team_id` | number | `teams.id` / 各表 `team_id` | **仅服务端注入** |
| 6 | `team_name` | string | `teams.name` | — |
| 7 | `default_timezone` | string | `teams.default_timezone` | — |
| 8 | `default_warehouse_id` | number | `teams.default_warehouse_id` | 逻辑关联 |
| 9 | `role` | string | `team_members.role` | `TeamRole` |
| 10 | `product_id` | number | `products.id` / `skus.product_id` | — |
| 11 | `product_code` | string | `products.product_code` | — |
| 12 | `category` | string | `products.category` | — |
| 13 | `sku_id` | number | `skus.id` | — |
| 14 | `sku_code` | string | `skus.sku_code` | — |
| 15 | `spec_json` | object | `skus.spec_json` | JSON |
| 16 | `default_lead_time_days` | number | `skus.default_lead_time_days` | SKU 级 `L` |
| 17 | `moq` | string | `skus.moq` / `suppliers.moq` / `replenishment_policies.moq` | 三级来源 |
| 18 | `order_multiple` | string | `skus.order_multiple` / `suppliers.order_multiple` / `replenishment_policies.order_multiple` | 三级来源 |
| 19 | `qty_step` | string | `skus.qty_step` / `replenishment_policies.qty_step` | 精度步长 |
| 20 | `purchase_price` | string | `skus.purchase_price` | 单价 4 位 |
| 21 | `listed_date` | string | `skus.listed_date` | `DATE` |
| 22 | `lead_time_days`（供应商） | number | `suppliers.lead_time_days` | 供应商级 `L` |
| 23 | `warehouse_id` | number | 各表 `warehouse_id` / `warehouses.id` | — |
| 24 | `warehouse_code` | string | `warehouses.code` | — |
| 25 | `is_default` | boolean | `warehouses.is_default` | `TINYINT(1)` |
| 26 | `on_hand_qty` | string | `inventory_balances.on_hand_qty` | 数量 3 位 |
| 27 | `in_transit_qty` | string | `inventory_balances.in_transit_qty` | — |
| 28 | `committed_qty` | string | `inventory_balances.committed_qty` | — |
| 29 | `available_qty` | string | `inventory_balances.available_qty`（生成列） | 仅展示 |
| 30 | `last_txn_at` | string | `inventory_balances.last_txn_at` | ISO-8601 UTC |
| 31 | `txn_type` | string | `inventory_transactions.txn_type` | `InventoryTxnType` |
| 32 | `qty_delta` | string | `inventory_transactions.qty_delta` | 带符号 |
| 33 | `qty_before` / `qty_after` | string | `inventory_transactions.qty_before` / `qty_after` | — |
| 34 | `biz_type`（流水） | string | `inventory_transactions.biz_type` | `InventoryBizType` ⚠️§9 未定义 |
| 35 | `biz_ref_id` | number | `inventory_transactions.biz_ref_id` | 多态 |
| 36 | `idempotency_key` | string | `inventory_transactions.idempotency_key` 等 | ≤128 |
| 37 | `reversed_txn_id` | number | `inventory_transactions.reversed_txn_id` | 冲销 |
| 38 | `sale_date` | string | `sales_daily.sale_date` | `DATE` |
| 39 | `qty`（销售） | string | `sales_daily.qty` | 净销量 |
| 40 | `return_qty` | string | `sales_daily.return_qty` | — |
| 41 | `sales_amount` | string | `sales_daily.sales_amount` | 金额 2 位 |
| 42 | `batch_no` | string | `import_batches.batch_no` | — |
| 43 | `file_hash` | string | `import_batches.file_hash` | SHA-256 |
| 44 | `total_rows` / `success_rows` / `failed_rows` | number | `import_batches.*` | — |
| 45 | `field_mapping_json` | object | `import_batches.field_mapping_json` | JSON |
| 46 | `parse_options_json` | object | `import_batches.parse_options_json` | JSON |
| 47 | `error_level` | string | `import_row_errors.error_level` | `ERROR`/`WARNING` |
| 48 | `row_no` | number | `import_row_errors.row_no` | 1 起 |
| 49 | `run_id` / `run_no` | number/string | `analysis_runs.id` / `run_no` | — |
| 50 | `scope_type` | string | `analysis_runs.scope_type` | `AnalysisScopeType` |
| 51 | `scope_json` | object | `analysis_runs.scope_json` | JSON |
| 52 | `params_json`（请求 `params`） | object | `analysis_runs.params_json` | 见 `ANALYSIS_CONTRACT.md` |
| 53 | `algorithm_version` | string | `analysis_runs.algorithm_version` | — |
| 54 | `prompt_version` | string | `analysis_runs.prompt_version` | — |
| 55 | `llm_status` | string | `analysis_runs.llm_status` | `LlmStatus` |
| 56 | `llm_error_message` | string | `analysis_runs.llm_error_message` | `DEGRADED` 时非空 |
| 57 | `progress` / `current_step` | number/string | `analysis_runs.progress` / `current_step` | 0–100 |
| 58 | `total_skus` / `processed_skus` | number | `analysis_runs.*` | — |
| 59 | `risk_sku_count` / `suggestion_count` | number | `analysis_runs.*` | 终态 |
| 60 | `data_start_date` / `data_end_date` | string | `analysis_runs.*` | `DATE` |
| 61 | `timeout_at` | string | `analysis_runs.timeout_at` | 超时兜底 |
| 62 | `avg_daily_sales` | string | `analysis_items.avg_daily_sales` | `D`，3 位 |
| 63 | `sales_window_days` | number | `analysis_items.sales_window_days` | `N` |
| 64 | `valid_days` | number | `analysis_items.valid_days` | `n` |
| 65 | `total_sales_qty` | string | `analysis_items.total_sales_qty` | — |
| 66 | `cv` | string | `analysis_items.cv` | 4 位 |
| 67 | `lead_time_days`（分析） | number | `analysis_items.lead_time_days` | 采用值 `L` |
| 68 | `lead_time_source` | string | `analysis_items.lead_time_source` | `LeadTimeSource` ⚠️§9 未定义 |
| 69 | `coverage_days` | number | `analysis_items.coverage_days` | `R` |
| 70 | `safety_stock_qty` | string | `analysis_items.safety_stock_qty` | `SS` |
| 71 | `effective_in_transit_qty` | string | `analysis_items.effective_in_transit_qty` | 有效在途 |
| 72 | `inventory_position_qty` | string | `analysis_items.inventory_position_qty` | `IP` |
| 73 | `volatility_adjusted` | boolean | `analysis_items.volatility_adjusted` | — |
| 74 | `qty_base`（`Q_base`） | string | `analysis_items.qty_base` | 3 位 |
| 75 | `qty_suggest`（`Q_suggest`） | string | `analysis_items.qty_suggest` | 3 位 |
| 76 | `moq_applied` / `order_multiple_applied` | string | `analysis_items.*` | — |
| 77 | `truncated_by_budget` | boolean | `analysis_items.truncated_by_budget` | — |
| 78 | `risk_level` | string | `analysis_items.risk_level` / `replenishment_items.risk_level` | `RiskLevel` |
| 79 | `risk_types` | array | `analysis_items.risk_types` | `RiskType[]` |
| 80 | `risk_findings` | array | `analysis_items.risk_detail_json` | 映射见 `ANALYSIS_CONTRACT.md` |
| 81 | `data_quality_json` | object | `analysis_items.data_quality_json` | — |
| 82 | `llm_explanation` | string | `analysis_items.llm_explanation` | `TEXT` |
| 83 | `source_analysis_item_id` | number | `replenishment_items.source_analysis_item_id` | 追溯链 |
| 84 | `suggested_qty` | string | `replenishment_items.suggested_qty` | 不可变 |
| 85 | `qty`（建议） | string | `replenishment_items.qty` | 当前生效量 |
| 86 | `supplier_id` | number | `replenishment_items.supplier_id` / `purchase_orders.supplier_id` | — |
| 87 | `expected_arrival_date` | string | `replenishment_items.expected_arrival_date` / `purchase_orders.expected_arrival_date` / `purchase_order_items.expected_arrival_date` | `DATE` |
| 88 | `converted_po_item_ids` | array | `replenishment_items.converted_po_item_ids` | JSON 仅展示 |
| 89 | `submitted_at` / `approved_at` / `approved_by` | string/number | `replenishment_items.*` | — |
| 90 | `review_id` / `action` / `from_status` / `to_status` / `comment` | number/string | `replenishment_reviews.*` | `ReviewAction` |
| 91 | `reviewer_id` / `reviewer_role` | number/string | `replenishment_reviews.*` | 角色快照 |
| 92 | `po_no` | string | `purchase_orders.po_no` | — |
| 93 | `source`（采购单） | string | `purchase_orders.source` | `PurchaseOrderSource` ⚠️§9 未定义 |
| 94 | `total_qty` / `received_qty` | string | `purchase_orders.total_qty` / `received_qty` | — |
| 95 | `total_amount` | string | `purchase_orders.total_amount` | 金额 2 位 |
| 96 | `confirmed_at` / `confirmed_by` / `ordered_at` / `cancelled_at` / `cancel_reason` | string/number | `purchase_orders.*` | — |
| 97 | `line_no` | number | `purchase_order_items.line_no` | 1 起 |
| 98 | `replenishment_item_id` | number | `purchase_order_items.replenishment_item_id` | 跨模块逻辑关联 |
| 99 | `ordered_qty` | string | `purchase_order_items.ordered_qty` | — |
| 100 | `received_qty`（明细） | string | `purchase_order_items.received_qty` | 只增不减 |
| 101 | `unit_price` | string | `purchase_order_items.unit_price` / `goods_receipt_items.unit_price` | 4 位 |
| 102 | `amount` | string | `purchase_order_items.amount` / `goods_receipt_items.amount` | 2 位 |
| 103 | `receipt_no` | string | `goods_receipts.receipt_no` | — |
| 104 | `receipt_date` | string | `goods_receipts.receipt_date` | `DATE` |
| 105 | `purchase_order_item_id` | number | `goods_receipt_items.purchase_order_item_id` | — |
| 106 | `version` | number | 各表 `version` | 乐观锁 |
| 107 | `created_at` / `updated_at` | string | 各表 `*_at` | ISO-8601 UTC |
| 108 | `created_by` / `updated_by` | number | 各表 `*_by` | — |
| 109 | `requestId`（响应） | string | `analysis_runs.request_id` 等 | = `X-Request-Id` |
| 110 | `error_code` / `error_message`（批次级） | string | `import_batches.error_code` / `error_message` | — |

> **展示字段标注**：`sku_code`（余额/流水/分析接口）= JOIN `skus.sku_code`；`warehouse_code`（导入响应）= JOIN `warehouses.code`；`existing_batch_id`、`existing_run_id`、`current_version`、`po_status`、`balance_version`、`inventory_transactions`（到货确认响应内聚合）均为**响应聚合/展示字段**，非直接列。

---

## 13. 与其它文档的不一致与待决策

> 依 `DESIGN_BASIS §1.2 第 4 条`：发现冲突必须记录，**不改动他人文档**，在本文档标注。

### 13.1 与 `PRD.md §10` 的 API 路径差异（**M0 审查中已统一，本条已关闭**）

> **处置结果**：在 M0 跨文档一致性审查中，`PRD.md` 与 `SYSTEM_ARCHITECTURE.md` 中的规划路径
> **已全部修正为本文档的权威路径**（修正记录见 `docs/06-development/DESIGN_REVIEW.md` 检查项 14）。
> 下表保留为历史记录，说明当时的差异与最终裁定，供追溯：

| # | 本文档（权威，已统一） | 原 `PRD.md §10` 规划路径 | 说明与裁定 |
| --- | --- | --- | --- |
| 1 | `POST /api/v1/import/batches` | ~~`POST /api/v1/imports`~~ | 资源名统一为 `import/batches`（体现"批次"语义） |
| 2 | `POST /api/v1/purchasing/orders` | ~~`POST /api/v1/purchase-orders`~~ | 统一为模块前缀风格 `purchasing/orders` |
| 3 | `POST /api/v1/purchasing/receipts` | ~~`POST /api/v1/goods-receipts`~~ | 同上，统一为 `purchasing/receipts` |
| 4 | `GET /api/v1/teams/{teamId}/members` | ~~`GET /api/v1/team/members`~~ | 统一为复数 + 团队层级显式化 |
| 5 | `POST /api/v1/analysis/runs/{id}/cancel` | ~~`POST /api/v1/analysis/runs/{id}:cancel`~~ | 对外统一用 `/cancel` 子路径，不使用 `:action` 风格 |
| 6 | `POST /api/v1/replenishment/items/{id}/submit` | ~~`POST /api/v1/replenishment/items/{id}:submit`~~ | 同上 |
| 7 | `PATCH /api/v1/users/{userId}/status` | ~~`PATCH /api/v1/users/{id}/status`~~ | 路径参数命名统一为 `{userId}`，语义一致 |
| 8 | `GET /api/v1/audit/logs` | ~~`GET /api/v1/audit-logs`~~ | 统一为 `audit/logs` 子资源风格 |
| 9 | `POST /api/v1/inventory/adjustments`（二合一） | ~~`POST /api/v1/inventory/count-adjustments` + `POST /api/v1/inventory/manual-adjustments`~~ | **保留设计取舍**：合并为一个入口，以 `txn_type` 区分，减少重复契约；权限差异在服务层按 `txn_type` 收窄（`MANUAL_ADJUST` 需 `ADMIN`+） |
| 10 | `POST /api/v1/inventory/adjustments` | ~~`POST /api/v1/inventory/adjustments` + `/manual-adjustments`~~ | 与 #9 同一处置 |

> **结论**：本项差异**已解决**，不再属于未决冲突。表中划掉部分仅作历史留档。

### 13.2 任务书给定路径 vs PRD（本文档采用任务书路径）

本文档采用任务书给定的资源路径（`/api/v1/import/batches`、`/api/v1/purchasing/orders`、`/api/v1/purchasing/receipts`、`/api/v1/teams/{teamId}/members`、`/api/v1/audit/logs`、`/api/v1/inventory/adjustments`），与 §13.1 一致。

### 13.3 `DESIGN_BASIS §9` 未定义的枚举（沿用 `DATA_DICTIONARY.md §6` 的提示）

以下枚举被本文档字段引用，但 `DESIGN_BASIS §9` 未定义，建议补充（`D-14`）：

| 枚举 | 出现位置 | 取值 |
| --- | --- | --- |
| `LeadTimeSource` | `analysis_items.lead_time_source` | `POLICY`/`SKU`/`SUPPLIER`/`DEFAULT` |
| `InventoryBizType` | `inventory_transactions.biz_type` | `GOODS_RECEIPT`/`COUNT`/`MANUAL`/`IMPORT`/`SALE`/`REVERSAL` |
| `SalesSource` | `sales_daily.source` | `IMPORT`/`MANUAL`/`PLATFORM` |
| `PurchaseOrderSource` | `purchase_orders.source` | `MANUAL`/`REPLENISHMENT` |
| 导入错误级别 | `import_row_errors.error_level` | `ERROR`/`WARNING` |
| 审计常量 | `audit_logs.action` | `AuditAction` 34 条，成员资格见 `DESIGN_BASIS §9.17`（语义见 `DATABASE_DESIGN.md §6-J.1`） |

### 13.4 新增待决策（本文档发现）

| 编号（建议） | 问题 | 推荐方案 | 影响 |
| --- | --- | --- | --- |
| **D-16（建议）** | 对外 API 是否允许一个登录会话绑定单一团队（`team_id` 固定在 JWT） | **首期绑定单团队**；多团队成员在登录时选择或使用默认团队；切换团队需重新登录 | 影响 `POST /api/v1/auth/login` 返回结构与是否需要 `switch-team` 接口；`USER_ROLES.md §6.3`（角色变更即时生效）依赖短 TTL |
| **D-17（建议）** | 库存调整是否合并为单一入口 `POST /api/v1/inventory/adjustments`（以 `txn_type` 区分） | **推荐合并**（见 §13.1 #9） | 影响前端表单与权限收窄实现（服务层按 `txn_type` 判角色） |
| **D-18（建议）** | `withdraw`（撤回提交）动作是否需要独立接口 | **推荐需要**（`ReplenishmentItemStatus.PENDING_REVIEW → SUGGESTED` 是合法转换，`DESIGN_BASIS §9.11`），路径 `POST /api/v1/replenishment/items/{id}/withdraw` | 影响 F-RPL-03 完整性（任务书未列该路径） |
| **D-19（建议）** | 采购单 `PENDING → ORDERED` 是否需要独立动作接口 `/order` | **推荐需要**（`PurchaseOrderStatus` 状态机要求，且 `ordered_at` 与在途口径生效时点不同） | 影响 F-PO-02 与 `effective_in_transit_qty` 生效时点 |

---

## 14. 变更记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-10-14 | M0 初稿：通用规范、统一响应、认证授权、幂等、92 接口清单、9 组完整示例、契约一致性核对表、跨文档差异记录 | API 契约 / 架构 |
