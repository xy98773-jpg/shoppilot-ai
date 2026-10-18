# B13 真栈取证归档（M4-T01：Java ↔ Python 的认证头 + 链路追踪头贯通）

本目录是 **B13 的唯一真栈证据来源**。它证明的不是「单测能过」，而是**打包后的两个进程**在
真实 HTTP 上行为一致：Java（fat jar）按契约回显 `X-Request-Id`，Python（uvicorn）按契约
拒绝无令牌请求、并用四字段错误体回显同一个 request id。

| 项 | 值 |
|---|---|
| 端口 | MySQL `3365` / Java `18290` / Python `18090`（启动前 `netstat -ano` 实测空闲） |
| 容器 | `rb-b13-mysql`（`mysql:8.0.36`，`--default-time-zone=+00:00`） |
| Java | 自建 fat jar（`./mvnw -B clean verify` 顺带产出），`JAR_SHA256` 见 `evidence-rb-b13.txt` 首几行 |
| Python | `uvicorn shoppilot_ai.app.main:app`，注入 `AI_INTERNAL_TOKEN=rb-b13-token` |
| 种子 | **不需要**：本批只探 `/internal/v1/health` 与 `/api/v1/health`，不碰业务表 |

**重跑方式**：

```bash
cd backend-java && ./mvnw -B clean verify
PYTHON_BIN=/path/to/venv/python bash backend-java/tools/rb-b13/run-rb-b13.sh
# 可覆盖： RB13_MYSQL_PORT / RB13_APP_PORT / RB13_PY_PORT
```

> `PYTHON_BIN` 必须指向**已安装 fastapi/uvicorn/httpx** 的解释器（本仓未内置 venv）。
> 本机实测用的是受管 venv：`C:/Users/Shu/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`。

产物：`evidence-rb-b13.txt`（断言日志，入库）、`java.log` / `python.log` / `last-*.log`
（进程 stdout 与最近一次响应，`*.log` 已 gitignore）。

## 一、探针清单（对应任务书 §5 的 P1~P9）

| 探针 | 请求 | 期望 | 断言数 |
|---|---|---|---|
| **P1** | `GET /internal/v1/health`（不带 token） | `200` + `{"status":"UP"}` | 2 |
| **P2** | `POST /internal/v1/analysis:validate`（不带 token） | `401` + `AI-0001` | 2 |
| **P3** | 同上（带错 token） | `401` + `AI-0001` | 2 |
| **P4** | 同上（带对 token） | **非 401**（本批无业务端点，实测 `404`） | 1 |
| **P5** | 任一 401 | 必带 `X-Request-Id`；`body.request_id` 与响应头逐字相等；四键齐全 | 3 |
| **P6** | 显式 `X-Request-Id: req_b13_probe_0001` | 响应头与 body 均原样回显 | 2 |
| **P7** | 重启 Python 且 `AI_INTERNAL_TOKEN` 为空 | `P2` 仍 `401 AI-0001`；`P1` 仍 `200` | 4 |
| **P8** | Java `GET /api/v1/health` + `req_b13_probe_0002` | 响应头回显 + `body.requestId` 一致 | 3 |
| **P9** | 非法 JSON body + 对 token | 期望 `400 AI-1001` | **N/A（见下）** |

**P9 为什么是 N/A**：本批按 `G3` **不实现任何** `/internal/v1/analysis*` 业务端点，
因此没有路由能触发 Pydantic body 校验，`POST` 只会落到 `404`。该路径改由
`tests/test_error_envelope.py::test_validation_error_returns_400_ai_1001` 覆盖（真实
`RequestValidationError` → `400 AI-1001`，四键 + `detail.errors[]` 均断言）。
P9 在任务书中标注为「加分，不强制」，故此处如实记 N/A，不伪造通过。

## 二、为什么必须真栈（单测替代不了的三件事）

1. **中间件顺序**：`internal_token_middleware` 与 `request_id_middleware` 的注册顺序决定
   401 响应体里能不能拿到 request id。TestClient 与 uvicorn 都会走这条链，但**只有真栈**能
   证明 ASGI 栈在 `--host 127.0.0.1` 的实际行为（含 `Host` 头、`--noproxy` 等）。
2. **fail-closed 的进程级验证**：`P7` 是**重启进程**并清空环境变量 —— 单测里 `monkeypatch`
   只改内存里的 `settings`，证不了「进程在无令牌时仍能启动、且内部端点全关」。
3. **双端一致性**：`P5`/`P6`/`P8` 断言的是「Java 与 Python 对同一个头名的处理是否一致」，
   跨进程、跨语言，只能在真栈上比对。

## 三、踩坑记录（判据，供后续复用）

1. **`exec` 起 uvicorn**：`( cd ... && uvicorn ... ) &` 里若不写 `exec`，`$!` 是**外层 subshell**
   的 pid；`kill $!` 只杀掉 subshell，uvicorn 变孤儿继续占端口 ⇒ `P7` 重启必然失败。
   正确写法：`( cd ... && VAR=... exec python -m uvicorn ... ) &`。
2. **`curl` 一律 `--noproxy '*'`**：本机有代理环境变量，不排除会把 `127.0.0.1` 也走代理。
3. **四键断言要用排序后比较**：`sorted(keys())` 得到 `code,detail,message,request_id`，
   直接写声明顺序（`code,message,detail,...`）会得到假 FAIL。
4. **`X-Request-Id` 的取值必须从响应头文件里取**（`-D headers.log`），不要从 body 里反推，
   否则「body 与响应头一致」这条断言会变成同义反复。

## 四、实测结果（2026-10-18）

```text
RB13_CHECK_TOTAL=19
RB13_FAIL_COUNT=0
RB13_RESULT=ALL PASS
```

关键原始响应（摘自 `evidence-rb-b13.txt`）：

```text
P1  http=200 body={"status":"UP"}
P2  http=401 body={"code":"AI-0001","message":"Invalid or missing X-Internal-Token","detail":{},"request_id":"eacf4a7c-..."}
P3  http=401 body={"code":"AI-0001",...}
P5  P2.header=[eacf4a7c-...]  P2.body.request_id=[eacf4a7c-...]   ← 逐字相等
P6  http=401 header=[req_b13_probe_0001] body.request_id=[req_b13_probe_0001]
P4  http=404 body={"detail":"Not Found"}                        ← 对 token 未被鉴权拦下
P8  http=200 header=[req_b13_probe_0002]
    body={"code":"OK","message":"success","data":{...},"requestId":"req_b13_probe_0002",...}
P9  http=404 → N/A（无业务端点）
P7  PYTHON_RESTART_UP=1 (token=<empty>) → POST 仍 401 AI-0001、GET health 仍 200 UP
```

**首轮 18 PASS / 1 FAIL 的成因（已归档，供后续复用判据）**：`P5-body.request_id==响应头`
拿到的是 **P3 的响应头** 去比 **P2 的 body** —— `$HDR` 是「最近一次响应的头文件」，被 P3 覆盖了。
不是产品缺陷（`P6` 同一请求内头/体一致已证明）。修法：P2 当场把 `hdr_of` 结果存进 `P2_H`，
P5 只用 `P2_H` 比较。**未改任何 main 代码、未放宽任何断言。**

