# `tools/rb-b10` — B10（M3-T18 前端）项目负责人独立复核

复核对象：`0cff527 → 31c0a63`（3 笔提交，35 文件 `5086+/44-`，纯 `frontend-web/`，零 backend/docs 改动）。
复核人：项目负责人。**不采信开发自报的 build/test/联调结论**。

## 与 `frontend-web/tools/b10/` 的分工

| 目录 | 归属 | 证明什么 |
| --- | --- | --- |
| `frontend-web/tools/b10/` | 开发 | 「页面能用」：六页可打开、写操作同键重试不新增（62 项） |
| `backend-java/tools/rb-b10/`（本目录） | 评审 | 「契约逐字成立 + 无反向误杀 + 相邻面未被带走」：33 项断言，其中 6 组为开发未覆盖 |

## 独立性

- 自建 fat jar（`app/target/shoppilot-app-0.1.0.jar`，SHA256 见证据头）+ 独立 `mysql:8.0.36` 容器；
- 端口 `3337` / `18117`，避开历史全部占用（`3319`、`3327–3336`、`18099`、`18107–18116`）；
- 种子 `tools/evi/seed-b95.sql`，另补 `approver01`（`APPROVER`）以覆盖 `adjustments` 白名单；
- `IMPORT_STORAGE_DIR` 指到本目录 `tmp/storage`，不污染仓库根 `data/`。

## 断言分组

| 组 | 内容 | 开发是否覆盖 |
| --- | --- | --- |
| P0 | 五个角色可登录 | 间接 |
| P1 | 余额列表：`list` 分页字段 + 四列齐全 + 数量为字符串 | 部分（只验 200） |
| P2 | `sort=on_hand_qty,desc` 生效；**`sort[]=` 数组形态是否静默失效** | ❌ 未覆盖 |
| P3 | 流水列表/详情字段与类型 | 部分 |
| P4 | 调整**缺** `Idempotency-Key` → `400 COM-0001` | ❌ 未覆盖 |
| P5 | 调整带键 201 → 同键重放 201 + 头 `true` + 流水不增 | ✅ W2 |
| P6 | **`APPROVER` 执行 `COUNT_ADJUST` → 201**（反向误杀检查） | ❌ 未覆盖 |
| P7 | `VIEWER` 调整 → `403 TEAM-2003` | ❌ 未覆盖 |
| P8 | 销售 `dateFrom/dateTo` 边界 + `qty` 原样字符串 | 部分 |
| P9 | **`SALES_DAILY` 全链路 → confirm 可用性** | ❌ 未覆盖（开发主动申报该限制） |
| P10 | **`INVENTORY_SNAPSHOT` 全链路 → confirm 可用性** | ❌ 未覆盖 |
| P11 | 批次列表 `status` 筛选生效 | ❌ 未覆盖（只验 200） |

## 结论

- **33 PASS / 0 FAIL / 2 NOTE**（`evidence-rb10.txt`）。
- 前端 api/types 契约与后端逐字对齐：路径、参数名（`page/pageSize/dateFrom/dateTo`）、分页字段 `list`、数量字符串、错误包络均成立。
- **无反向误杀**：`APPROVER` 能完成 `COUNT_ADJUST`（与后端白名单一致），`VIEWER` 被服务端拦为 `TEAM-2003`。
- 两条 NOTE 均为**同一缺陷的两侧**（P9/P10）：`ImportConfirmService` 只支持 4 类主数据，`SALES_DAILY` / `INVENTORY_SNAPSHOT` 校验可过但确认必 `400 COM-0001`。已登记 **R78（P1）**，发 `B10.5` 回修。
- `P2` 修正了一次**评审自己的错误假设**：原以为 axios 数组序列化（`sort[]=`）会让排序静默失效，实测 Spring 同样绑定 `sort`，**该风险不存在**。

## 复跑

```bash
bash backend-java/tools/rb-b10/run-rb10-evidence.sh
```

产物：`evidence-rb10.txt`（断言，入库）｜`evidence-rb10-app.log`、`evidence-rb10-headers.log`、`tmp/`（中间产物，gitignore）。
