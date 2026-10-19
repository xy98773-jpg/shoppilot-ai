"""B19.1 独立复核探针（项目负责人自建，非开发测试）。

不写库、不发网络请求；只用内存中的 RunRegistry + 纯函数。
每个 Pxx 打印 EXPECT / ACTUAL / PASS|FAIL，末尾给汇总。

运行：
  MSYS_NO_PATHCONV=1 docker run --rm -v "D:/Desktop/ShopPilot-AI/service-ai:/app" -w /app \
    -e PYTHONPATH=/app/src shoppilot-dev-service-ai:latest python tools/rb-b191-review/probe_b191.py
"""
from __future__ import annotations

import threading
from datetime import date, timedelta

from shoppilot_ai.app.core.errors import ServiceAIBaseError
from shoppilot_ai.app.orchestration import pipeline as pipeline_mod
from shoppilot_ai.app.orchestration.degradation import needs_degradation, summarize_llm_status
from shoppilot_ai.app.orchestration.pipeline import build_summary, request_hash, run_analysis
from shoppilot_ai.app.orchestration.progress import RunRegistry
from shoppilot_ai.app.schemas.analysis_request import AnalyzeRequest

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


def _outcome(fn):
    try:
        fn()
        return "ACCEPTED"
    except ServiceAIBaseError as exc:
        return exc.code

def series(values):
    return [
        {"date": (DATE_FROM + timedelta(days=i)).isoformat(), "qty": q}
        for i, q in enumerate(values)
    ]


def mk_item(sku, on_hand="8.000", committed="0.000", transit="0.000", values=None, **kw):
    data = {
        "sku_id": sku,
        "on_hand_qty": on_hand,
        "committed_qty": committed,
        "effective_in_transit_qty": transit,
        "lead_time_days": 7,
        "lead_time_source": "SKU",
        "sales_series": series(values or []),
    }
    for key in ("moq", "order_multiple", "qty_step", "safety_stock_override"):
        if kw.get(key) is not None:
            data[key] = kw[key]
    return data


def mk_request(items, warehouse_id=100, budget=None, enable_llm=False, run_id=30001, window=28):
    return AnalyzeRequest.model_validate(
        {
            "analysis_run_id": run_id,
            "team_id": 7,
            "warehouse_id": warehouse_id,
            "scope": {"scope_type": "SELECTED_SKUS", "sku_ids": [i["sku_id"] for i in items]},
            "date_from": DATE_FROM.isoformat(),
            "date_to": DATE_TO.isoformat(),
            "params": {
                "window_days": window,
                "min_days": 7,
                "coverage_days": 7,
                "lead_time_default": 7,
                "enable_llm": enable_llm,
                **({"budget_limit": budget} if budget else {}),
            },
            "algorithm_version": "avg-ma-3sigma-v1",
            **({"prompt_version": "explain_risk.v1"} if enable_llm else {}),
            "items": items,
        }
    )


def spy(registry: RunRegistry):
    """记录 update_step 调用序列（步骤、progress、processed）。"""
    log: list[tuple[str, int, int]] = []
    original = registry.update_step

    def wrapper(run_id, step, processed_skus=None):
        record = original(run_id, step, processed_skus)
        if record is not None:
            log.append((step, record.progress, record.processed_skus))
        return record

    registry.update_step = wrapper  # type: ignore[method-assign]
    return log


def run(items, narrative=None, **kw):
    request = mk_request(items, **kw)
    registry = RunRegistry()
    registry.register(request.analysis_run_id, request.team_id,
                      request_hash(request), len(request.items))
    log = spy(registry)
    result = run_analysis(request, registry, narrative=narrative)
    return registry, result, log, request


HOT = ["6.000"] * 21 + ["7.000"] * 7

print("\n=== A. 步骤 / 进度 / 终态 ===")
registry, result, log, _ = run([mk_item(5001, values=HOT), mk_item(5002, on_hand="200.000", values=HOT)])
first_seen: list[str] = []
for step, _p, _k in log:
    if step not in first_seen:
        first_seen.append(step)
chk("P30 六步顺序", ["VALIDATING", "STATS", "RISK_RULES", "REPLENISHMENT", "LLM_EXPLAIN", "DONE"], first_seen)
step_prog = {s: p for s, p, _k in log}
chk("P30 首次出现的 progress 档位", [5, 20, 40, 60, 80, 100], [step_prog[s] for s in first_seen])
chk("P30 档位映射", {"VALIDATING": 5, "STATS": 20, "RISK_RULES": 40,
                    "REPLENISHMENT": 60, "LLM_EXPLAIN": 80, "DONE": 100}, step_prog)
replen = [p for s, p, _k in log if s == "REPLENISHMENT"]
chk("P31 步内 progress 恒为 60（不插值）", {60}, set(replen))
chk("P31 步内 processed_skus 递增到 len(items)", [0, 1, 2], [k for s, _p, k in log if s == "REPLENISHMENT"])
rec = registry.get(30001)
chk("P31 终态 progress=100 / DONE / processed=2",
    ["COMPLETED", 100, "DONE", 2], [rec.execution_status, rec.progress, rec.current_step, rec.processed_skus])
chk("P31 时间戳为 UTC", "UTC", str(rec.started_at.tzinfo))
chk("P31 started_at/finished_at 非空", [True, True], [rec.started_at is not None, rec.finished_at is not None])

# P32 终态不可变
registry.fail(30001, "AI-3001", "迟到异常", {"stage": "RISK_RULES"})
chk("P32 COMPLETED 后 fail 不改状态", "COMPLETED", registry.get(30001).execution_status)
registry.request_cancel(30001)
chk("P32 COMPLETED 后 request_cancel 不生效", False, registry.get(30001).cancel_requested)

# P33 并发上限
reg2 = RunRegistry()
for i in range(4):
    reg2.register(1000 + i, 7, "h", 1)
try:
    reg2.register(1004, 7, "h", 1)
    chk("P33 第 5 个非终态 register 被拒", "AI-7001", "ACCEPTED")
except ServiceAIBaseError as exc:
    chk("P33 第 5 个非终态 register 被拒", ["AI-7001", 429, 4],
        [exc.code, exc.http_status, exc.detail.get("max")])

# P34 多线程竞态：8 线程同时注册，只允许 4 个成功
reg3 = RunRegistry()
results: list[str] = []
lock = threading.Lock()
barrier = threading.Barrier(8)


def worker(idx: int) -> None:
    barrier.wait()
    try:
        reg3.register(2000 + idx, 7, "h", 1)
        outcome = "OK"
    except ServiceAIBaseError as exc:
        outcome = exc.code
    with lock:
        results.append(outcome)


threads = [threading.Thread(target=worker, args=(i,)) for i in range(8)]
for t in threads:
    t.start()
for t in threads:
    t.join()
chk("P34 8 线程并发注册：成功数恰为 4", 4, results.count("OK"))
chk("P34 其余全部 AI-7001", 4, results.count("AI-7001"))

# P35 容量淘汰最旧终态
reg4 = RunRegistry()
for i in range(199):
    reg4.register(3000 + i, 7, "h", 1)
    reg4.fail(3000 + i, "AI-2002", "数据不足", {})
reg4.register(3999, 7, "h", 1)
size_before = len(reg4.snapshot())
reg4.fail(3999, "AI-2002", "数据不足", {})
extra = reg4.register(4000, 7, "h", 1)
chk("P35 容量上限 200（淘汰最旧终态）", [200, 200], [size_before, len(reg4.snapshot())])
chk("P35 新记录仍在", True, reg4.get(4000) is not None)
chk("P35 最旧记录 3000 被淘汰", None, reg4.get(3000))
chk("P35 次旧记录 3001 仍在", True, reg4.get(3001) is not None)
chk("P35 容量满时先淘汰最旧终态（AI-7001 分支在并发上限下不可达）",
    ["ACCEPTED", 200, None],
    [_outcome(lambda: reg4.register(4001, 7, "h", 1)), len(reg4.snapshot()), reg4.get(3001)])

print("\n=== B. 结果组装 ===")
# P37 summary 口径（预算截断）
registry, result, log, _ = run(
    [
        mk_item(5001, values=HOT),                       # HIGH + q_suggest>0
        mk_item(5002, on_hand="200.000", values=HOT),    # NONE + q_suggest null
        mk_item(5003, values=["5.000"] * 6),             # UNKNOWN（数据不足）
    ],
    budget="50.000",
)
summary = result.data.summary
chk("P37 total_skus", 3, summary.total_skus)
chk("P37 risk_sku_count 排除 NONE/UNKNOWN", 1, summary.risk_sku_count)
chk("P37 suggestion_count（仅 >0）", 1, summary.suggestion_count)
chk("P37 truncated_count", 1, summary.truncated_count)
chk("P37 by_risk_level 只含有值的键", {"HIGH": 1, "NONE": 1, "UNKNOWN": 1}, dict(summary.by_risk_level))
chk("P38 data_start/end 回显请求窗口", [DATE_FROM, DATE_TO],
    [result.data.data_start_date, result.data.data_end_date])
chk("P38 execution_status=COMPLETED", "COMPLETED", result.data.execution_status)
chk("P37 截断项 q_suggest=50.000", "50.000", result.data.items[0].q_suggest)
chk("P37 无建议项 q_suggest=null", None, result.data.items[1].q_suggest)
chk("P37 数据不足项 risk_level=UNKNOWN", "UNKNOWN", result.data.items[2].risk_level)

# P36 request_hash
req_a = mk_request([mk_item(5001, values=HOT)], run_id=4001)
req_b = mk_request([mk_item(5001, values=HOT)], run_id=4001)
chk("P36 同体同哈希", request_hash(req_a), request_hash(req_b))
req_c = mk_request([mk_item(5001, values=["6.000"] * 27 + ["9.000"])], run_id=4001)
chk("P36 改 sales_series ⇒ 异哈希", False, request_hash(req_a) == request_hash(req_c))
req_d = mk_request([mk_item(5001, values=HOT)], run_id=4001, budget="99.000")
chk("P36 改 budget_limit ⇒ 异哈希", False, request_hash(req_a) == request_hash(req_d))
req_e = mk_request([mk_item(5001, values=HOT)], run_id=4001, warehouse_id=None)
chk("P36 warehouse_id 省略 ⇒ 异哈希（区分缺省与显式 null）",
    False, request_hash(req_a) == request_hash(req_e))

print("\n=== C. 异常 / 守卫 / 取消 / LLM ===")

# P39 analyzable=false
registry, result, log, _ = run([mk_item(5001, values=[]), mk_item(5002, values=[])])
rec = registry.get(30001)
chk("P39 全部不可分析 ⇒ FAILED + AI-2002 + stage=VALIDATING",
    ["FAILED", "AI-2002", "VALIDATING", None],
    [rec.execution_status, rec.error["code"], rec.error["detail"]["stage"], result])

# P40 warehouse_id 缺省 ⇒ fail closed
registry, result, log, _ = run([mk_item(5001, values=HOT)], warehouse_id=None)
rec = registry.get(30001)
chk("P40 缺 warehouse_id ⇒ AI-1001 fail closed",
    ["FAILED", "AI-1001", "warehouse_id", None],
    [rec.execution_status, rec.error["code"], rec.error["detail"]["field"], result])

# P41 assess 异常 ⇒ AI-3001 / VALIDATING
orig_assess = pipeline_mod.assess
pipeline_mod.assess = lambda request: (_ for _ in ()).throw(RuntimeError("assess 炸了"))
try:
    registry, result, log, _ = run([mk_item(5001, values=HOT)])
finally:
    pipeline_mod.assess = orig_assess
rec = registry.get(30001)
chk("P41 assess 异常 ⇒ AI-3001 + stage=VALIDATING",
    ["FAILED", "AI-3001", "VALIDATING"], [rec.execution_status, rec.error["code"], rec.error["detail"]["stage"]])

# P42 evaluate_items 异常 ⇒ AI-3001 / RISK_RULES
orig_eval = pipeline_mod.evaluate_items
pipeline_mod.evaluate_items = lambda *a, **k: (_ for _ in ()).throw(RuntimeError("规则炸了"))
try:
    registry, result, log, _ = run([mk_item(5001, values=HOT)])
finally:
    pipeline_mod.evaluate_items = orig_eval
rec = registry.get(30001)
chk("P42 规则异常 ⇒ AI-3001 + stage=RISK_RULES",
    ["FAILED", "AI-3001", "RISK_RULES"], [rec.execution_status, rec.error["code"], rec.error["detail"]["stage"]])

# P43 ResultItem 组装异常 ⇒ AI-4001 / REPLENISHMENT
orig_item = pipeline_mod.ResultItem


def boom(**kwargs):
    raise RuntimeError("组装炸了")


pipeline_mod.ResultItem = boom
try:
    registry, result, log, _ = run([mk_item(5001, values=HOT)])
finally:
    pipeline_mod.ResultItem = orig_item
rec = registry.get(30001)
chk("P43 组装异常 ⇒ AI-4001 + stage=REPLENISHMENT",
    ["FAILED", "AI-4001", "REPLENISHMENT"], [rec.execution_status, rec.error["code"], rec.error["detail"]["stage"]])

# P44 取消：VALIDATING 之后打标记 ⇒ STATS 边界生效
request = mk_request([mk_item(5001, values=HOT)], run_id=5001)
reg5 = RunRegistry()
reg5.register(request.analysis_run_id, request.team_id, "h", 1)
reg5.update_step(5001, "VALIDATING", 0)
reg5.request_cancel(5001)
res5 = run_analysis(request, reg5)
rec5 = reg5.get(5001)
chk("P44 步边界取消 ⇒ CANCELLED + 无结果 + 无 error",
    ["CANCELLED", None, None], [rec5.execution_status, res5, rec5.error])
chk("P44 取消时保留已处理数", 0, rec5.processed_skus)

# P45 LLM 开关
registry, result, log, _ = run([mk_item(5001, values=HOT)], enable_llm=False)
chk("P45 enable_llm=false ⇒ SKIPPED 且无 run warning",
    ["SKIPPED", []], [registry.get(30001).llm_status, registry.get(30001).warnings])
registry, result, log, _ = run([mk_item(5001, values=HOT)], enable_llm=True)
chk("P45 enable_llm=true ⇒ SKIPPED + 有 warning", ["SKIPPED", True],
    [registry.get(30001).llm_status, len(registry.get(30001).warnings) >= 1])

# P46 注入 narrative ⇒ SUCCEEDED + 解释写入，规则结果不变
registry, result, log, _ = run([mk_item(5001, values=HOT), mk_item(5002, on_hand="200.000", values=HOT)],
                               narrative=lambda items, request: ("SUCCEEDED", {5001: "缺货高风险"}))
chk("P46 注入 narrative ⇒ item 解释写入", "缺货高风险", result.data.items[0].llm_explanation)
chk("P46 run/item 的 llm_status=SUCCEEDED", ["SUCCEEDED", "SUCCEEDED"],
    [result.data.llm_status, result.data.items[0].llm_status])
chk("P46 规则结果不受 LLM 影响（summary 不变）", [2, 1], [result.data.summary.total_skus, result.data.summary.risk_sku_count])


# P47 narrative 抛异常 ⇒ FAILED + warning + 结果保留（不变量）
def bad_narrative(items, request):
    raise RuntimeError("LLM 挂了")


registry, result, log, _ = run([mk_item(5001, values=HOT)], narrative=bad_narrative)
rec = registry.get(30001)
chk("P47 narrative 异常 ⇒ COMPLETED + llm_status=FAILED + 有 warning",
    ["COMPLETED", "FAILED", True], [rec.execution_status, rec.llm_status, len(rec.warnings) >= 1])
chk("P47 规则结果仍保留", 1, result.data.summary.total_skus)

# P48 降级判定
chk("P48 needs_degradation 七值",
    {"FAILED": True, "TIMEOUT": True, "VALIDATION_FAILED": True,
     "SUCCEEDED": False, "SKIPPED": False, "NOT_REQUESTED": False, "PENDING": False},
    {s: needs_degradation(s) for s in
     ["FAILED", "TIMEOUT", "VALIDATION_FAILED", "SUCCEEDED", "SKIPPED", "NOT_REQUESTED", "PENDING"]})
chk("P48 未知取值保守判 True", True, needs_degradation("WHAT_IS_THIS"))
chk("P48 summarize 全 SKIPPED", "SKIPPED", summarize_llm_status(["SKIPPED", "SKIPPED"]))
chk("P48 summarize 取最严重", "FAILED", summarize_llm_status(["SKIPPED", "FAILED"]))
chk("P48 summarize 空列表", "SKIPPED", summarize_llm_status([]))
chk("P48 summarize None", "SKIPPED", summarize_llm_status(None))

# P49 未注册直接跑：不抛异常（docstring 明示由 B19.2 负责 register）
req49 = mk_request([mk_item(5001, values=HOT)], run_id=6001)
reg49 = RunRegistry()
res49 = run_analysis(req49, reg49)
chk("P49 未注册直接执行 ⇒ 不抛异常且返回结果", [True, True],
    [res49 is not None, reg49.get(6001) is None])

print("\n=== SUMMARY ===")
print(f"PASS={PASS} FAIL={FAIL}")
print("RB191R_DONE")