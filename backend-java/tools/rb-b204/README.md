# rb-b204 —— M4-B20.4 开发自证探针（V5 / V6 原始响应）

> 用途：任务书 §V5/§V6 要求「贴一次真实响应」，而集成测试只断言不打印 body。
> 本目录用**独立** MySQL 容器 + 独立 fat jar + 独立端口（3381 / 18321），
> 走真实 HTTP 抓原始 JSON，**不复用**任何交付环境、**不依赖** `service-ai`。

## 怎么跑

```bash
# 前置：先产出 fat jar（任务书 §V3）
cd backend-java && ./mvnw -B -q -pl app -am -DskipTests package
# 再跑探针（Git Bash）
bash backend-java/tools/rb-b204/run-rb-b204.sh
```

生命周期：`trap cleanup EXIT` —— 容器与 java 进程全回收；跑前自检端口占用。

## 覆盖项（24 条断言，实测 ALL PASS）

| # | 断言 | 期望 |
| --- | --- | --- |
| V5-1 | `POST /api/v1/analysis/runs/{id}/cancel`（PENDING） | `200` + `code=OK` |
| V5-2 | `data.run_id / status / cancelled_at` 三字段 | `1 / CANCELLED / ISO-8601 UTC 毫秒` |
| V5-3 | DB `status / current_step / finished_at` | `CANCELLED / CANCELLED / 非空` |
| V5-4 | 审计 `ANALYSIS_RUN_CANCEL`（`result=SUCCESS`） | **恰 1 条** |
| V5-5 | 不额外记 `ANALYSIS_RUN_FINISH` | **0 条** |
| V5-6 | 远端取消不可达（`AI_SERVICE_BASE_URL=127.0.0.1:1`） | 仍 `200`（§A5 尽力而为，日志 1 行 WARN） |
| 幂等 | 再取消一次 | `200`，`cancelled_at` 与首次**逐字相同**，审计仍 1 条 |
| 终态 | 取消 `SUCCEEDED` | `422 ANL-7011`，DB 状态不变 |
| V6-1 | `GET .../items?page=abc` | `400 COM-0001`，`details[0].field=page`，`rejectedValue=abc` |
| V6-2 | `GET .../items?pageSize=abc` | `400 COM-0001`，`details[0].field=page_size` |

## 实测结果

见同目录 [`evidence-b204.txt`](evidence-b204.txt)（脚本逐条 `[PASS]`/`[FAIL]` + 原始响应体）。

- 本轮：`RESULT: PASS=24 FAIL=0 / ALL PASS`
- fat jar：`60,033,690 B`，`sha256=c567540faf7918a94ce280d50ff25a028049e455437ed4391efe7dcbfdbefbbe`
- 关键原始响应（摘自证据文件）：
  - V5：`{"code":"OK","message":"success","data":{"run_id":1,"status":"CANCELLED","cancelled_at":"2026-10-20T02:15:22.896Z"},"requestId":"req_764c…","timestamp":"2026-10-20T02:15:22.949Z"}`
  - V6：`{"code":"COM-0001","message":"请求参数校验失败","data":null,"details":[{"field":"page","message":"参数类型不合法","rejectedValue":"abc"}],"requestId":"req_1613…","timestamp":"2026-10-20T02:15:45.751Z"}`

## 首轮两处自纠（如实记录）

1. `JWT_SECRET` 首版 31 字节 → 启动即失败（`JwtProperties` 要求 ≥ 32 字节 HS256）；
   改为 35 字节后正常。**不是产品缺陷**，是探针夹具问题。
2. `q()` 首版漏写库名 → `No database selected`，且 `stderr` 被丢弃导致 20 条假 FAIL；
   补库名 + `stderr` 落 `q.err` 后 24/24 通过。
