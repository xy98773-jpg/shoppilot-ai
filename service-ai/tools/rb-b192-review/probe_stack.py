"""B19.2 真实容器真栈探针（项目负责人自建，用于解除 R107）。

只用标准库 urllib，直打真实 uvicorn 容器（非 TestClient）。
每个 Txx 打印 EXPECT / ACTUAL / PASS|FAIL，末尾给汇总。
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = "http://127.0.0.1:18395"
TOKEN = "rb192-token"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

DATE_FROM = date(2026, 9, 16)
DATE_TO = date(2026, 10, 13)

PASS = 0
FAIL = 0


def chk(name: str, expected, actual) -> None:
    global PASS, FAIL
    ok = expected == actual
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"[{'PASS' if ok else 'FAIL'}] {name}\n       EXPECT={expected!r}\n       ACTUAL={actual!r}")


def call(method: str, path: str, body=None, token=TOKEN, rid: str = "req_rb192"):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Internal-Token", token)
    req.add_header("X-Request-Id", rid)
    try:
        with OPENER.open(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8")), dict(resp.headers)
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8")), dict(exc.headers)


def hget(headers, name: str):
    """大小写不敏感地取响应头（uvicorn 发的是小写键）。"""
    lowered = {str(k).lower(): v for k, v in headers.items()}
    return lowered.get(name.lower())


def series(values, start=DATE_FROM):
    return [
        {"date": (start + timedelta(days=i)).isoformat(), "qty": q}
        for i, q in enumerate(values)
    ]


HOT = ["6.000"] * 21 + ["7.000"] * 7


def make_run(run_id: int, warehouse_id=100, items=None):
    default_items = [
        {"sku_id": 5001, "on_hand_qty": "8.000", "committed_qty": "0.000",
         "effective_in_transit_qty": "0.000", "lead_time_days": 7,
         "lead_time_source": "SKU", "sales_series": series(HOT)},
        {"sku_id": 5002, "on_hand_qty": "200.000", "committed_qty": "0.000",
         "effective_in_transit_qty": "0.000", "lead_time_days": 7,
         "lead_time_source": "SKU", "sales_series": series(HOT)},
    ]
    item_list = default_items if items is None else items
    payload = {
        "analysis_run_id": run_id,
        "team_id": 7,
        "scope": {"scope_type": "SELECTED_SKUS",
                  "sku_ids": [i["sku_id"] for i in item_list] or [5001]},
        "date_from": DATE_FROM.isoformat(),
        "date_to": DATE_TO.isoformat(),
        "params": {
            "window_days": 28,
            "min_days": 7,
            "coverage_days": 7,
            "lead_time_default": 7,
            "enable_llm": False,
        },
        "algorithm_version": "avg-ma-3sigma-v1",
        "items": item_list,
    }
    if warehouse_id is not None:
        payload["warehouse_id"] = warehouse_id
    return payload


# 等待就绪
for _ in range(60):
    try:
        status, body, _ = call("GET", "/internal/v1/health", token=None, rid="req_rb192_health")
        if status == 200:
            break
    except Exception:
        pass
    time.sleep(1)
chk("T1 health 免令牌 200", [200, {"status": "UP"}], [status, body])

# T2 validate：无令牌 401
status, body, _ = call("POST", "/internal/v1/analysis:validate",
                       body={"team_id": 7, "scope": {"scope_type": "ALL_ACTIVE"},
                             "date_from": DATE_FROM.isoformat(), "date_to": DATE_TO.isoformat(),
                             "items": []}, token=None, rid="req_rb192_t2")
chk("T2 validate 无令牌 ⇒ 401 AI-0001", [401, "AI-0001"], [status, body.get("code")])

# T3 validate：带令牌 + 合成 items
status, body, headers = call("POST", "/internal/v1/analysis:validate", body={
    "team_id": 7,
    "scope": {"scope_type": "SELECTED_SKUS", "sku_ids": [5001, 5002]},
    "date_from": DATE_FROM.isoformat(), "date_to": DATE_TO.isoformat(),
    "params": {"window_days": 28, "min_days": 7},
    "items": [
        {"sku_id": 5001, "on_hand_qty": "8.000", "committed_qty": "0.000",
         "effective_in_transit_qty": "0.000", "sales_series": series(HOT)},
        {"sku_id": 5002, "on_hand_qty": "0.000", "committed_qty": "0.000",
         "effective_in_transit_qty": "0.000", "sales_series": []},
    ],
}, rid="req_rb192_t3")
chk("T3 validate 200 OK", [200, "OK", True], [status, body.get("code"), body["data"]["analyzable"]])
chk("T3 validate total_skus=2 / analyzable_skus=1",
    [2, 1], [body["data"]["total_skus"], body["data"]["analyzable_skus"]])
chk("T3 request_id 体==响应头", hget(headers, "X-Request-Id"), body.get("request_id"))

# T4 create：无令牌 401
status, body, _ = call("POST", "/internal/v1/analysis/runs", body=make_run(7001), token=None,
                       rid="req_rb192_t4")
chk("T4 create 无令牌 ⇒ 401 AI-0001", [401, "AI-0001"], [status, body.get("code")])

# T5 create：202 ACCEPTED
status, body, headers = call("POST", "/internal/v1/analysis/runs", body=make_run(7001),
                             rid="req_rb192_t5")
print("T5_RAW", status, json.dumps(body, ensure_ascii=False)[:400])
chk("T5 create 202 ACCEPTED", [202, "OK", "ACCEPTED", 7001],
    [status, body.get("code"), body["data"]["execution_status"], body["data"]["analysis_run_id"]])
chk("T5 accepted_at 带时区", True, str(body["data"]["accepted_at"])[-6] in ("+00:00", "Z") or str(body["data"]["accepted_at"]).endswith("Z"))
chk("T5 request_id 体==响应头", hget(headers, "X-Request-Id"), body.get("request_id"))

# T6 轮询到终态
deadline = time.time() + 30
final = {}
while time.time() < deadline:
    status, body, _ = call("GET", "/internal/v1/analysis/runs/7001", rid="req_rb192_t6")
    final = body.get("data", {})
    if final.get("execution_status") in {"COMPLETED", "FAILED", "CANCELLED"}:
        break
    time.sleep(0.5)
chk("T6 终态 COMPLETED/100/DONE", ["COMPLETED", 100, "DONE"],
    [final.get("execution_status"), final.get("progress"), final.get("current_step")])
print("T6_KEYS", sorted(final.keys()))
chk("T6 字段齐备（契约 §3.4）",
    sorted(["analysis_run_id", "execution_status", "progress", "current_step", "total_skus",
            "processed_skus", "llm_status", "started_at", "finished_at", "error"]),
    sorted(final.keys()))

# T7 result
status, body, _ = call("GET", "/internal/v1/analysis/runs/7001/result", rid="req_rb192_t7")
summary = body.get("data", {}).get("summary", {})
chk("T7 result 200 + summary", [200, 2, 1], [status, summary.get("total_skus"), summary.get("risk_sku_count")])
chk("T7 by_risk_level 只含有值的键", {"HIGH": 1, "NONE": 1}, dict(summary.get("by_risk_level", {})))

# T8 幂等：同体重发
status, body, _ = call("POST", "/internal/v1/analysis/runs", body=make_run(7001), rid="req_rb192_t8")
status2, body2, _ = call("GET", "/internal/v1/analysis/runs/7001", rid="req_rb192_t8b")
chk("T8 同体重发 ⇒ 202 + 当前状态",
    [202, "COMPLETED"], [status, body["data"]["execution_status"]])
chk("T8 未重复执行（started_at/finished_at 未变）", True,
    body2["data"]["started_at"] == final.get("started_at"))

# T9 幂等：异体重发 ⇒ 409 AI-1002
payload = make_run(7001)
payload["params"]["coverage_days"] = 14
status, body, _ = call("POST", "/internal/v1/analysis/runs", body=payload, rid="req_rb192_t9")
print("T9_RAW", status, json.dumps(body, ensure_ascii=False)[:300])
chk("T9 异体重发 ⇒ 409 AI-1002", [409, "AI-1002"], [status, body.get("code")])
chk("T9 detail 带两个哈希", True,
    bool(body.get("detail", {}).get("existing_hash")) and bool(body.get("detail", {}).get("incoming_hash")))

# T10 未注册
status, body, _ = call("GET", "/internal/v1/analysis/runs/99999", rid="req_rb192_t10")
chk("T10 状态端点未注册 ⇒ 404 AI-6001", [404, "AI-6001"], [status, body.get("code")])
status, body, _ = call("GET", "/internal/v1/analysis/runs/99999/result", rid="req_rb192_t10b")
chk("T10 结果端点未注册 ⇒ 404 AI-6001", [404, "AI-6001"], [status, body.get("code")])

# T11 方法与路径
status, body, _ = call("GET", "/internal/v1/analysis:validate", rid="req_rb192_t11")
chk("T11 GET :validate ⇒ 405 AI-1004", [405, "AI-1004"], [status, body.get("code")])
status, body, _ = call("POST", "/internal/v1/analysis/unknown", body={}, rid="req_rb192_t11b")
chk("T11 未定义路径 ⇒ 404 AI-1003", [404, "AI-1003"], [status, body.get("code")])

# T12 取消
status, body, _ = call("POST", "/internal/v1/analysis/runs/99999:cancel", body={"reason": "x"},
                       rid="req_rb192_t12")
chk("T12 未注册取消 ⇒ 404 AI-6001", [404, "AI-6001"], [status, body.get("code")])
status, body, _ = call("POST", "/internal/v1/analysis/runs/7001:cancel", body={"reason": "终态再取消"},
                       rid="req_rb192_t12b")
chk("T12 终态再取消 ⇒ 409 AI-6002", [409, "AI-6002"], [status, body.get("code")])

# T13 R120 实证：空 items ⇒ 编排 FAILED(AI-2002)
status, body, _ = call("POST", "/internal/v1/analysis/runs", body=make_run(7002, items=[]),
                       rid="req_rb192_t13")
deadline = time.time() + 20
rec = {}
while time.time() < deadline:
    _, rb, _ = call("GET", "/internal/v1/analysis/runs/7002", rid="req_rb192_t13b")
    rec = rb.get("data", {})
    if rec.get("execution_status") in {"COMPLETED", "FAILED", "CANCELLED"}:
        break
    time.sleep(0.5)
chk("T13 R120：空 items ⇒ 202 后编排 FAILED + AI-2002",
    [202, "FAILED", "AI-2002"], [status, rec.get("execution_status"), (rec.get("error") or {}).get("code")])

# T14 R120 实证：缺 warehouse_id ⇒ 编排 FAILED(AI-1001)
status, body, _ = call("POST", "/internal/v1/analysis/runs", body=make_run(7003, warehouse_id=None),
                       rid="req_rb192_t14")
deadline = time.time() + 20
rec = {}
while time.time() < deadline:
    _, rb, _ = call("GET", "/internal/v1/analysis/runs/7003", rid="req_rb192_t14b")
    rec = rb.get("data", {})
    if rec.get("execution_status") in {"COMPLETED", "FAILED", "CANCELLED"}:
        break
    time.sleep(0.5)
chk("T14 R120：缺 warehouse_id ⇒ FAILED + AI-1001",
    [202, "FAILED", "AI-1001"], [status, rec.get("execution_status"), (rec.get("error") or {}).get("code")])

# T15 取消：数据量小，run 往往在 cancel 之前就完成 ⇒ 接受两种结局并如实记录
status, body, _ = call("POST", "/internal/v1/analysis/runs", body=make_run(7004), rid="req_rb192_t15")
status2, body2, _ = call("POST", "/internal/v1/analysis/runs/7004:cancel", body={}, rid="req_rb192_t15b")
print("T15_CANCEL_RAW", status2, json.dumps(body2, ensure_ascii=False)[:220])
if status2 == 200:
    chk("T15a 取消受理回执 200 + CANCELLED", [200, "CANCELLED"],
        [status2, body2["data"]["execution_status"]])
else:
    chk("T15a 执行太快时取消 ⇒ 409 AI-6002（终态不可取消）", [409, "AI-6002"],
        [status2, body2.get("code")])
_, rb, _ = call("GET", "/internal/v1/analysis/runs/7004", rid="req_rb192_t15c")
final_status = rb["data"]["execution_status"]
chk("T15b run 终态为 COMPLETED 或 CANCELLED", True, final_status in {"COMPLETED", "CANCELLED"})
if final_status == "CANCELLED":
    st, rb3, _ = call("GET", "/internal/v1/analysis/runs/7004/result", rid="req_rb192_t15d")
    chk("T15c 已取消取结果 ⇒ 409 AI-6003", [409, "AI-6003"], [st, rb3.get("code")])
else:
    st, rb4, _ = call("GET", "/internal/v1/analysis/runs/7004/result", rid="req_rb192_t15e")
    chk("T15c 已完成取结果 ⇒ 200", [200, 2], [st, rb4["data"]["summary"]["total_skus"]])

print("\n=== SUMMARY ===")
print(f"PASS={PASS} FAIL={FAIL}")
print("RB192R_DONE")
sys.exit(0 if FAIL == 0 else 1)