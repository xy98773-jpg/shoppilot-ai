"""B18 独立复核探针（项目负责人自建，非开发测试）。

只依赖 service-ai 源码，不写库、不发网络请求。
每个 Pxx 打印 EXPECT / ACTUAL / PASS|FAIL，末尾给汇总。

运行（宿主机）：
  MSYS_NO_PATHCONV=1 docker run --rm -v "D:/Desktop/ShopPilot-AI/service-ai:/app" -w /app \
    -e PYTHONPATH=/app/src shoppilot-dev-service-ai:latest python tools/rb-b18-review/probe_b18.py
"""
from __future__ import annotations

import traceback
from datetime import date, timedelta
from decimal import Decimal

from pydantic import ValidationError

from shoppilot_ai.app.analytics.evaluate import evaluate_items
from shoppilot_ai.app.schemas.analysis_request import AnalyzeRequest
from shoppilot_ai.app.schemas.analysis_response import ResultItem
from shoppilot_ai.app.validation.data_quality import assess

DATE_FROM = date(2026, 9, 16)
DATE_TO = date(2026, 10, 13)
SKU = 5001

PASS = 0
FAIL = 0
NOTES: list[str] = []


def chk(name: str, expected, actual) -> None:
    global PASS, FAIL
    ok = expected == actual
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"[{'PASS' if ok else 'FAIL'}] {name}\n       EXPECT={expected!r}\n       ACTUAL={actual!r}")


def note(name: str, actual) -> None:
    NOTES.append(f"{name} => {actual}")
    print(f"[NOTE] {name}\n       ACTUAL={actual!r}")


def series_at(pairs, start=DATE_FROM):
    """按 (天数偏移, qty) 构造稀疏序列（用于「基线记录不足」等缺口场景）。"""
    return [
        {"date": (start + timedelta(days=offset)).isoformat(), "qty": qty}
        for offset, qty in pairs
    ]


def series(values, start=DATE_FROM):
    return [
        {"date": (start + timedelta(days=i)).isoformat(), "qty": q}
        for i, q in enumerate(values)
    ]


def mk_item(
    sku=SKU,
    on_hand="8.000",
    committed="0.000",
    transit="0.000",
    values=None,
    ltd=7,
    lts="SKU",
    moq=None,
    mult=None,
    step=None,
    listed=None,
    override=None,
):
    data = {
        "sku_id": sku,
        "on_hand_qty": on_hand,
        "committed_qty": committed,
        "effective_in_transit_qty": transit,
        "lead_time_days": ltd,
        "lead_time_source": lts,
        "sales_series": series(values or []),
    }
    if moq is not None:
        data["moq"] = moq
    if mult is not None:
        data["order_multiple"] = mult
    if step is not None:
        data["qty_step"] = step
    if listed is not None:
        data["listed_date"] = listed
    if override is not None:
        data["safety_stock_override"] = override
    return data


def mk_request(items, window=28, min_days=7, coverage=7, budget=None,
               ss_ratio="0.5000", cv_high="1.0000"):
    return AnalyzeRequest.model_validate(
        {
            "analysis_run_id": 1,
            "team_id": 7,
            "warehouse_id": 100,
            "scope": {"scope_type": "SELECTED_SKUS", "sku_ids": [i["sku_id"] for i in items]},
            "date_from": DATE_FROM.isoformat(),
            "date_to": DATE_TO.isoformat(),
            "params": {
                "window_days": window,
                "min_days": min_days,
                "coverage_days": coverage,
                "lead_time_default": 7,
                "ss_ratio": ss_ratio,
                "cv_high": cv_high,
                "budget_limit": budget,
                "enable_llm": False,
            },
            "algorithm_version": "avg-ma-3sigma-v1",
            "items": items,
        }
    )


def one(item, **kw):
    request = mk_request([item], **kw)
    return evaluate_items(request.items, request.params, warehouse_id=100, date_to=DATE_TO)[0]


def finding(ev, code):
    for f in ev.risk_findings:
        if f.rule_code == code:
            return f
    return None


# ---------------------------------------------------------------- A. 裁定口径
print("\n=== A. §5.30 裁定口径 ===")

# P01 正常 SKU：5 条 finding（含 3 条 NOT_TRIGGERED）、顺序固定
ev = one(mk_item(values=["6.000"] * 21 + ["7.000"] * 7, moq="50.000", mult="10.000"))
chk("P01 findings 条数 = 5（已判定五条，DATAINSUFFICIENT 不参与）", 5, len(ev.risk_findings))
chk(
    "P01 findings 顺序",
    ["R-STOCKOUT", "R-LOWSTOCK", "R-OVERSTOCK", "R-SLOWMOVING", "R-SALESANOMALY"],
    [f.rule_code for f in ev.risk_findings],
)
chk(
    "P01 未命中给 NOT_TRIGGERED（3 条）",
    3,
    sum(1 for f in ev.risk_findings if f.result == "NOT_TRIGGERED"),
)
chk("P01 SS=25.000（ceil(L*0.5)=4）", "25.000", ev.safety_stock)
chk("P01 Q_base=104.500", "104.500", ev.q_base)
chk("P01 q_suggest=110.000（MOQ50/倍数10）", "110.000", ev.q_suggest)
chk("P01 risk_level=HIGH", "HIGH", ev.risk_level)
chk("P01 risk_types 顺序", ["STOCKOUT", "LOW_STOCK"], list(ev.risk_types))

# P02 D=0：库存三条跳过（3 warning），SLOW_MOVING 照常
ev = one(mk_item(values=["0.000"] * 28, on_hand="5.000"))
chk("P02 findings 条数 = 2（SLOW_MOVING + SALES_ANOMALY）", 2, len(ev.risk_findings))
chk("P02 无 STOCKOUT finding（跳过≠未命中）", None, finding(ev, "R-STOCKOUT"))
chk("P02 跳过 warning 数 = 4（三类库存 + 窗口<30）", 4, len(ev.warnings))
chk("P02 SLOW_MOVING=MEDIUM", "MEDIUM", (finding(ev, "R-SLOWMOVING") or None).result)
chk("P02 q_base=0.000", "0.000", ev.q_base)
chk("P02 q_suggest=null", None, ev.q_suggest)

# P03 D=null：只 1 条 UNKNOWN，补货字段全 null，IP/L/R 回显
ev = one(mk_item(values=["5.000"] * 6, on_hand="10.000"))
chk("P03 findings 条数 = 1", 1, len(ev.risk_findings))
chk("P03 finding=DATAINSUFFICIENT/UNKNOWN", ("R-DATAINSUFFICIENT", "UNKNOWN"),
    (ev.risk_findings[0].rule_code, ev.risk_findings[0].result))
chk("P03 risk_level=UNKNOWN", "UNKNOWN", ev.risk_level)
chk("P03 risk_types=[DATA_INSUFFICIENT]", ["DATA_INSUFFICIENT"], list(ev.risk_types))
chk("P03 SS/Q_base/moq/multiple/q_suggest 全 null",
    [None, None, None, None, None],
    [ev.safety_stock, ev.q_base, ev.moq_applied, ev.order_multiple_applied, ev.q_suggest])
chk("P03 IP 回显=10.000", "10.000", ev.inventory_position)
chk("P03 L/R 回显=7/7", [7, 7], [ev.lead_time_days, ev.coverage_days])
chk("P03 data_quality=INSUFFICIENT", "INSUFFICIENT", ev.data_quality)

# P04 高波动上浮：cv>1.0 ⇒ factor 4*1.5=6
ev = one(mk_item(values=["0.000"] * 20 + ["25.000"] * 8, on_hand="100.000"))
chk("P04 volatility_adjusted=True", True, ev.volatility_adjusted)
chk("P04 SS = D(7.143)*6", "42.858", ev.safety_stock)

# P05 override 优先且不上浮
ev = one(
    mk_item(values=["0.000"] * 20 + ["25.000"] * 8, on_hand="100.000", override="100.000")
)
chk("P05 SS=100.000（覆盖）", "100.000", ev.safety_stock)
chk("P05 volatility_adjusted=False（覆盖时不套公式）", False, ev.volatility_adjusted)

# P06 Q_base<=0 ⇒ q_suggest=null，MOQ/倍数也 null（开发偏差 B）
ev = one(mk_item(values=["6.000"] * 21 + ["7.000"] * 7, on_hand="200.000", moq="50.000", mult="10.000"))
chk("P06 Q_base=0.000", "0.000", ev.q_base)
chk("P06 q_suggest=null", None, ev.q_suggest)
chk("P06 moq_applied=null（无建议则无生效约束）", None, ev.moq_applied)
chk("P06 order_multiple_applied=null", None, ev.order_multiple_applied)
chk("P06 truncated_by_budget=False", False, ev.truncated_by_budget)

# P07 MOQ 默认 1 + qty_step 向上取整（q_base=31, step=6 ⇒ 36）
ev = one(mk_item(values=["5.000"] * 28, on_hand="59.000", step="6.000"))
chk("P07 Q_base=31.000", "31.000", ev.q_base)
chk("P07 moq_applied=1.000（默认）", "1.000", ev.moq_applied)
chk("P07 q_suggest=36.000（ceil(31/6)*6）", "36.000", ev.q_suggest)

# P08 交期来源兜底：lead_time_days=None + source=POLICY ⇒ DEFAULT
ev = one(mk_item(values=["5.000"] * 28, ltd=None, lts="POLICY"))
chk("P08 L=7（默认）", 7, ev.lead_time_days)
chk("P08 source=DEFAULT（忽略 item 的 POLICY）", "DEFAULT", ev.lead_time_source)

# ---------------------------------------------------------------- B. 边界
print("\n=== B. SSOT 边界（开发未覆盖） ===")

# P09 变化率恰 50% ⇒ 不命中（严格 >）
ev = one(mk_item(values=["2.000"] * 21 + ["3.000"] * 7, on_hand="100.000"))
chk("P09 变化率恰 50% ⇒ NOT_TRIGGERED", "NOT_TRIGGERED", finding(ev, "R-SALESANOMALY").result)

# P10 变化率 50.05% ⇒ 命中 LOW
ev = one(mk_item(values=["2.000"] * 21 + ["3.001"] * 7, on_hand="100.000"))
chk("P10 变化率 50.05% ⇒ LOW", "LOW", finding(ev, "R-SALESANOMALY").result)

# P11 自然日补零：近 7 天只有 1 条 7.000 ⇒ 日均 1.000（不是 7.000）
item_p11 = mk_item(values=["2.000"] * 21, on_hand="100.000")
item_p11["sales_series"].append({"date": DATE_TO.isoformat(), "qty": "7.000"})
ev = one(item_p11)
chk("P11 补零后 last7_avg=1.000", "1.000", finding(ev, "R-SALESANOMALY").inputs["last7_avg"])
chk("P11 变化率仍为 50% ⇒ NOT_TRIGGERED", "NOT_TRIGGERED", finding(ev, "R-SALESANOMALY").result)

# P12 基线记录 < min_days ⇒ anomaly 跳过（不输出 finding）
# 基线窗口 = 偏移 0..20，近期 = 21..27；这里基线仅 2 条记录
sparse = mk_item(values=[], on_hand="100.000")
sparse["sales_series"] = series_at([(0, "2.000"), (1, "2.000")] + [(o, "5.000") for o in range(21, 28)])
ev = one(sparse)
chk("P12 基线记录不足 ⇒ 无 R-SALESANOMALY finding", None, finding(ev, "R-SALESANOMALY"))
chk("P12 有跳过 warning（含「基线窗口」）", 1,
    sum(1 for w in ev.warnings if "基线窗口" in w))

# P13 STOCKOUT 边界：on_hand == 2*D ⇒ HIGH
ev = one(mk_item(values=["5.000"] * 28, on_hand="10.000"))
chk("P13 on_hand/D==2.000 ⇒ HIGH", "HIGH", finding(ev, "R-STOCKOUT").result)

# P14 OVERSTOCK 边界：==90 ⇒ 不命中；90.001 ⇒ MEDIUM（严格 >）
ev = one(mk_item(values=["1.000"] * 28, on_hand="90.000"))
chk("P14 on_hand/D==90 ⇒ NOT_TRIGGERED", "NOT_TRIGGERED", finding(ev, "R-OVERSTOCK").result)
ev = one(mk_item(values=["1.000"] * 28, on_hand="90.001"))
chk("P14 on_hand/D=90.001 ⇒ MEDIUM", "MEDIUM", finding(ev, "R-OVERSTOCK").result)

# ---------------------------------------------------------------- C. 组合器
print("\n=== C. 组合器 / 预算 / Schema ===")

# P15 data_quality 与 assess() 同源
items = [
    mk_item(sku=6001, values=["5.000"] * 28, on_hand="100.000"),
    mk_item(sku=6002, values=["5.000"] * 10, on_hand="100.000"),
    mk_item(sku=6003, values=[], on_hand="100.000"),
]
request = mk_request(items)
evs = evaluate_items(request.items, request.params, warehouse_id=100, date_to=DATE_TO)
per_sku = {row.sku_id: row.quality for row in assess(request).per_sku}
chk("P15 data_quality 与 assess() 逐 SKU 一致",
    per_sku, {ev.sku_id: ev.data_quality for ev in evs})

# P16 预算顺序扣减（budget=20；A 先则 A=20/B=0，B 先则 B=18/A=2）
a = mk_item(sku=7001, values=["5.000"] * 28, on_hand="0.000")
b = mk_item(sku=7002, values=["1.000"] * 28, on_hand="0.000")
request = mk_request([a, b], budget="20.000")
evs = evaluate_items(request.items, request.params, warehouse_id=100, date_to=DATE_TO)
chk("P16 A 先：q_suggest=[20.000, 0.000]",
    ["20.000", "0.000"], [ev.q_suggest for ev in evs])
chk("P16 A 先：truncated=[True, True]",
    [True, True], [ev.truncated_by_budget for ev in evs])
request = mk_request([b, a], budget="20.000")
evs = evaluate_items(request.items, request.params, warehouse_id=100, date_to=DATE_TO)
chk("P16 B 先：q_suggest=[18.000, 2.000]",
    ["18.000", "2.000"], [ev.q_suggest for ev in evs])

# P17 负 IP：计算正确，但 ResultItem 校验拒绝（R118）
neg = mk_item(values=["5.000"] * 28, on_hand="5.000", committed="10.000")
ev = one(neg)
chk("P17 IP=-5.000", "-5.000", ev.inventory_position)
chk("P17 Q_base=95.000（-5 计入）", "95.000", ev.q_base)
try:
    built = ResultItem(**ev.model_dump(), llm_explanation=None, llm_evidence=[], llm_status="SKIPPED")
    chk("P17 ResultItem 可组装负 IP（B19 真实路径）", "-5.000", built.inventory_position)
except ValidationError as exc:
    chk("P17 ResultItem 可组装负 IP（B19 真实路径）", "-5.000", f"ValidationError: {exc.errors()[0]['msg']}")

# P18 预算截断为 0：q_suggest="0.000"（非 null）+ truncated + warning
zero = mk_item(values=["5.000"] * 28, on_hand="0.000", step="10.000")
request = mk_request([zero], budget="5.000")
ev = evaluate_items(request.items, request.params, warehouse_id=100, date_to=DATE_TO)[0]
chk("P18 截断为 0 ⇒ q_suggest=0.000", "0.000", ev.q_suggest)
chk("P18 truncated_by_budget=True", True, ev.truncated_by_budget)
chk("P18 有「截断为 0」warning", 1, sum(1 for w in ev.warnings if "截断为 0" in w))

# P19 倍数路径：Q_base=73, multiple=10 ⇒ 80
ev = one(mk_item(values=["5.000"] * 28, on_hand="17.000", mult="10.000"))
chk("P19 Q_base=73.000", "73.000", ev.q_base)
chk("P19 q_suggest=80.000（ceil(73/10)*10）", "80.000", ev.q_suggest)
chk("P19 order_multiple_applied=10.000", "10.000", ev.order_multiple_applied)


# P20 只有 inventory_position 放宽：其余数量字段仍拒绝负值
dump = ev.model_dump()
for field in ("on_hand_qty", "effective_in_transit_qty", "committed_qty",
              "safety_stock", "q_base", "q_suggest"):
    payload = dict(dump, llm_explanation=None, llm_evidence=[], llm_status="SKIPPED")
    payload[field] = "-1.000"
    try:
        ResultItem(**payload)
        chk(f"P20 {field} 仍拒绝负值", "REJECTED", "ACCEPTED")
    except ValidationError:
        chk(f"P20 {field} 仍拒绝负值", "REJECTED", "REJECTED")

# P21 滞销 threshold 文案无双层括号（偏差 D）
text = finding(one(mk_item(values=["0.000"] * 28, on_hand="5.000")), "R-SLOWMOVING").threshold
chk("P21 滞销 threshold 含「可用窗口 28 天 < 30」", True, "可用窗口 28 天 < 30" in text)
chk("P21 滞销 threshold 不再含双层括号", False, "（< 30）" in text)
chk("P21 滞销 threshold 语义前缀不变", True, text.startswith("近 28 天（"))

print("\n=== SUMMARY ===")
print(f"PASS={PASS} FAIL={FAIL}")
for n in NOTES:
    print("NOTE:", n)
print("RB18R_DONE")
