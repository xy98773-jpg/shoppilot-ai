# rb-b2041 —— M4-B20.4.1 开发自证探针（V2：R143 读时同步真栈）

> 用途：任务书 §V2 要求证明「关闭定时轮询后，仅靠 `GET /api/v1/analysis/runs/{id}` 的读时同步
> 就能把 run 推进到 `SUCCEEDED` 并落 `analysis_items`」。本目录用**独立** MySQL + **真实**
> `service-ai` 容器 + 独立 fat jar + 独立端口（3382 / 18331 / 18330）复现该链路。

## 怎么跑

```bash
# 前置：fat jar（含 R143 修复）
cd backend-java && ./mvnw -B -q -pl app -am -DskipTests package
# 探针（Git Bash）
bash backend-java/tools/rb-b2041/run-rb-b2041.sh
```

关键环境：`ANALYSIS_POLL_ENABLED=false`（**显式关闭**定时轮询/超时扫描）、
`AI_SERVICE_BASE_URL=http://127.0.0.1:18330`（真实 Python 容器）、`enable_llm=false`（不产生 LLM 费用）。

## 覆盖项（15 条断言，实测 ALL PASS）

| 组 | 断言 |
| --- | --- |
| 前置 | 种子导入 0；迁移版本数 10；operator01 登录 |
| 创建 | `POST /runs` → `202`；创建后 `RUNNING/5/VALIDATING`（未落终态） |
| 读时同步 | **只发 1 次** `GET /runs/{id}` → `SUCCEEDED / progress=100 / DONE` |
| 落库 | DB `status=SUCCEEDED`、`progress=100`、`analysis_items=5`（`total_skus=5`） |
| 出站证据 | service-ai access log：`GET /internal/v1/analysis/runs/1 → 200`、`POST analysis%3Avalidate → 200`、`POST /runs → 202`、`GET .../result → 200` |
| 反向证据 | 出站状态查询次数 **== 我方轮询次数（1）** ⇒ 无定时轮询掺入；app.log 无扫描日志 |

## 实测结果

见 [`evidence-b2041.txt`](evidence-b2041.txt)：`RESULT: PASS=15 FAIL=0 / ALL PASS`。

关键原始行（摘自证据文件）：

```
CREATE BODY {"code":"OK",...,"data":{"run_id":1,"run_no":"RUN-20261020-0001","status":"RUNNING",
  "total_skus":5,"progress":5,"current_step":"VALIDATING","llm_status":"NOT_REQUESTED",...}}
  poll#1 status=SUCCEEDED progress=100 step=DONE
INFO: 172.17.0.1:57870 - "GET /internal/v1/analysis/runs/1 HTTP/1.1" 200 OK
INFO: 172.17.0.1:57854 - "POST /internal/v1/analysis%3Avalidate HTTP/1.1" 200 OK
INFO: 172.17.0.1:57854 - "POST /internal/v1/analysis/runs HTTP/1.1" 202 Accepted
INFO: 172.17.0.1:57870 - "GET /internal/v1/analysis/runs/1/result HTTP/1.1" 200 OK
出站状态查询次数=1 ；我方轮询 GET 次数=1
```

## 与项目负责人 `rb-b204-review` 的差异（刻意）

1. 不复用其脚本/端口/库（本目录 3382/18331/18330，独立容器名）；
2. **不挂抓包反代**，直接用 service-ai 的 uvicorn access log 作服务端视角证据；
3. 额外断言「出站状态查询次数 == 我方轮询次数」——若定时轮询偷偷开着，次数会多出来。

## 首轮一处自纠

断言 `POST /internal/v1/analysis:validate` 首版按字面冒号 grep，而 uvicorn 的 access log 把冒号
编码为 `%3A`（`analysis%3Avalidate`）⇒ 1 条假 FAIL；改为 `(%3A|:)` 双形态后 15/15 通过。
