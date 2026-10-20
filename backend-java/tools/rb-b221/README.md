# rb-b221 — M4-B22.1 开发自证（R148 / R149 / V3）

独立真栈，**不复用**项目负责人 `tools/rb-*` 目录；端口 `3383`（MySQL）/ `18340`（app）/
`18341`（service-ai 容器）/ `18342`（stall 探针）。

## 组成

| 文件 | 作用 |
|---|---|
| `run-rb-b221.sh` | 主探针：起 MySQL + 真实 service-ai 容器 + fat jar，跑 R148/V3/R149 |
| `gen-seed-500.py` | 生成 500 个 ACTIVE SKU + 500 行余额 + 14,000 行销量的种子 SQL |
| `stall_server.py` | 读掉请求但不回包的 TCP 服务（构造**真实读超时** ANL-7003） |
| `probe.py` | JSON 断言辅助（字段取值 / 是否为数组 / 列表元素与详情深度比对） |
| `evidence-b221.txt` | 最近一次完整运行的原始输出（33 项断言，RESULT: PASS=33 FAIL=0） |
| `list.json` / `detail.json` / `body.json` | V3 与 C 系列的原始响应体 |

`seed-500.sql`（约 1 MB）与 `app*.log` 为**生成物**，不入库；由脚本每次重建。

## 复跑

```bash
cd backend-java && ./mvnw -B -q -pl app -am -DskipTests package          # 先产出 fat jar
cd backend-java/tools/rb-b221 && bash run-rb-b221.sh
```

脚本自带 `trap cleanup`：退出时 kill app / stall、`docker rm -f` 两个容器。

## 本次结论（原始行见 `evidence-b221.txt`）

- **R148**：`total_skus=500`，创建 → `SUCCEEDED` 墙钟 **13,203 ms < 60,000 ms**；
  `analysis_items=500`、`suggestion_count=500`。
- **V3**：`data.list/page/pageSize/total` 齐全；列表元素与 `GET /runs/{id}` 的 `data`
  经 `probe.py cmp` 判定 **IDENTICAL**（17 个字段逐字一致；不含 `created_at`）。
- **R149-C1**：停 service-ai ⇒ `502 ANL-7002`，DB 行 `FAILED|ANL-7002`。
- **R149-C2（黑洞 `10.255.255.1:8000`）**：实测 `502 ANL-7002`（**非**任务书预期的
  `504 ANL-7003`）。根因：`AiServiceClient.isReadTimeout` 显式把 `connect timed out`
  排除在 7003 之外，连接类失败统一归 `ANL-7002`。
- **R149-C2 补充（stall 服务）**：真实读超时 ⇒ `504 ANL-7003`，DB 行 `FAILED|ANL-7003`。
  该条用于正证 7003 分支可达，不替代对黑洞实测值的上报。

## 环境坑

- 三个 app 实例串行启动时需显式 `-Xmx512m`：默认堆（宿主 25%）在旧 JVM 未完全释放时会
  触发 native OOM（`hs_err`：`malloc failed for Chunk::new`）。
- stall 服务必须**读尽请求体**（含 chunked）：只 `accept` 不读会让客户端写阻塞，
  `readTimeout` 不覆盖写阶段，表现为请求整体挂死（首轮实测 60s 超时、HTTP 000）。
