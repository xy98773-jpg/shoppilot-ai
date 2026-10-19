"""`GC-01`~`GC-18` 的逐条 per-item 断言（组合器 `analytics/evaluate.py`）。

fixture 按 `DESIGN_REVIEW.md §5.30` 二的**三处修正**：
- `GC-04` 取 `L=0`（原稿 `L=7` 会让 `on_hand/D=5 <= L` 命中 `STOCKOUT`，与「未命中」矛盾）；
- `GC-18` 取 `on_hand=50`（原稿 1000 必触发 `OVERSTOCK`，与「全部规则未命中」矛盾）；
- `GC-03` 只断言 `STOCKOUT` **在集合中**（`IP=10 < 70` 同时命中 `LOW_STOCK`，`§11` 允许共存）。

★ 断言一律字符串比较，禁止 float 近似（`ANALYSIS_CONTRACT §7.3`）。
"""
from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

from shoppilot_ai.app.analytics.evaluate import ItemEvaluation, evaluate_items
from shoppilot_ai.app.schemas.analysis_request import Item, Params

START = date(2026, 1, 1)
DATE_TO = START + timedelta(days=27)

#: `ResultItem` 的 25 个确定性字段（不含 `llm_explanation`/`llm_evidence`/`llm_status`）。
DETERMINISTIC_FIELDS = {
    "sku_id",
    "warehouse_id",
    "avg_daily_sales",
    "sales_qty_total",
    "valid_days",
    "data_quality",
    "risk_level",
    "risk_types",
    "risk_findings",
    "on_hand_qty",
    "effective_in_transit_qty",
    "committed_qty",
    "inventory_position",
    "safety_stock",
    "lead_time_days",
    "lead_time_source",
    "coverage_days",
    "cv",
    "volatility_adjusted",
    "q_base",
    "moq_applied",
    "order_multiple_applied",
    "q_suggest",
    "truncated_by_budget",
    "warnings",
}


def make_item(
    quantities: list[str],
    *,
    sku_id: int = 9002,
    on_hand: str = "50.000",
    committed: str = "0.000",
    transit: str = "0.000",
    lead_time_days: int | None = 7,
    lead_time_source: str | None = "SKU",
    safety_stock_override: str | None = None,
    moq: str | None = None,
    order_multiple: str | None = None,
    qty_step: str | None = None,
) -> Item:
    return Item.model_validate(
        {
            "sku_id": sku_id,
            "on_hand_qty": on_hand,
            "committed_qty": committed,
            "effective_in_transit_qty": transit,
            "lead_time_days": lead_time_days,
            "lead_time_source": lead_time_source,
            "safety_stock_override": safety_stock_override,
            "moq": moq,
            "order_multiple": order_multiple,
            "qty_step": qty_step,
            "sales_series": [
                {"date": (START + timedelta(days=index)).isoformat(), "qty": qty}
                for index, qty in enumerate(quantities)
            ],
        }
    )


def make_params(**overrides: object) -> Params:
    base: dict[str, object] = {
        "window_days": 28,
        "min_days": 7,
        "coverage_days": 7,
        "lead_time_default": 7,
        "enable_llm": False,
    }
    base.update(overrides)
    return Params.model_validate(base)


def evaluate_one(item: Item, params: Params | None = None) -> ItemEvaluation:
    return evaluate_items(
        [item], params or make_params(), warehouse_id=100, date_to=DATE_TO
    )[0]


def result_of(evaluation: ItemEvaluation, rule_code: str) -> str | None:
    for finding in evaluation.risk_findings:
        if finding.rule_code == rule_code:
            return finding.result
    return None


# --------------------------------------------------------------------------- #
# GC-01 ~ GC-06：基线 / 缺货 / 高库存
# --------------------------------------------------------------------------- #
def test_gc01_normal_hot_sku_no_suggestion() -> None:
    evaluation = evaluate_one(make_item(["6.000"] * 21 + ["7.000"] * 7, on_hand="200.000"))
    assert evaluation.avg_daily_sales == "6.250"
    assert evaluation.sales_qty_total == "175.000"
    assert evaluation.valid_days == 28
    assert evaluation.data_quality == "GOOD"
    assert evaluation.risk_level == "NONE"
    assert evaluation.risk_types == []
    assert evaluation.q_base == "0.000"
    assert evaluation.q_suggest is None


def test_gc02_stockout_high_with_low_stock() -> None:
    evaluation = evaluate_one(
        make_item(
            ["6.000"] * 21 + ["7.000"] * 7,
            on_hand="8.000",
            moq="50.000",
            order_multiple="10.000",
        )
    )
    assert evaluation.risk_level == "HIGH"
    assert evaluation.risk_types == ["STOCKOUT", "LOW_STOCK"]
    assert evaluation.safety_stock == "25.000"
    assert evaluation.q_base == "104.500"
    assert evaluation.q_suggest == "110.000"


def test_gc03_stockout_boundary_two_days() -> None:
    evaluation = evaluate_one(make_item(["5.000"] * 28, on_hand="10.000"))
    assert evaluation.risk_level == "HIGH"
    # `§11` 允许 STOCKOUT + LOW_STOCK 共存，只断言包含
    assert "STOCKOUT" in evaluation.risk_types
    assert result_of(evaluation, "R-STOCKOUT") == "HIGH"


def test_gc04_low_stock_only_with_zero_lead_time() -> None:
    evaluation = evaluate_one(
        make_item(["2.000"] * 28, on_hand="10.000", lead_time_days=0)
    )
    assert evaluation.risk_level == "MEDIUM"
    assert evaluation.risk_types == ["LOW_STOCK"]
    assert result_of(evaluation, "R-STOCKOUT") == "NOT_TRIGGERED"


def test_gc05_overstock_medium() -> None:
    evaluation = evaluate_one(make_item(["1.000"] * 28, on_hand="95.000"))
    assert evaluation.risk_level == "MEDIUM"
    assert evaluation.risk_types == ["OVERSTOCK"]
    assert evaluation.q_suggest is None


def test_gc06_overstock_high() -> None:
    evaluation = evaluate_one(make_item(["1.000"] * 28, on_hand="181.000"))
    assert evaluation.risk_level == "HIGH"
    assert evaluation.risk_types == ["OVERSTOCK"]


# --------------------------------------------------------------------------- #
# GC-07 ~ GC-10：滞销 / 异常 / 数据不足
# --------------------------------------------------------------------------- #
def test_gc07_slow_moving() -> None:
    evaluation = evaluate_one(make_item(["0.000"] * 28, on_hand="50.000"))
    assert evaluation.avg_daily_sales == "0.000"
    assert evaluation.risk_level == "MEDIUM"
    assert evaluation.risk_types == ["SLOW_MOVING"]
    assert evaluation.q_suggest is None


def test_gc08_sales_anomaly() -> None:
    series = ["1.000"] * 10 + ["3.000"] * 10 + ["2.000"] + ["10.000"] * 7
    evaluation = evaluate_one(make_item(series, on_hand="100.000"))
    assert evaluation.risk_level == "LOW"
    assert evaluation.risk_types == ["SALES_ANOMALY"]
    assert result_of(evaluation, "R-SALESANOMALY") == "LOW"


def test_gc09_data_insufficient_six_days() -> None:
    evaluation = evaluate_one(make_item(["5.000"] * 6, on_hand="10.000"))
    assert evaluation.data_quality == "INSUFFICIENT"
    assert evaluation.valid_days == 6
    assert evaluation.avg_daily_sales is None
    assert evaluation.risk_level == "UNKNOWN"
    assert evaluation.risk_types == ["DATA_INSUFFICIENT"]
    assert evaluation.q_suggest is None
    assert evaluation.safety_stock is None
    assert evaluation.q_base is None


def test_gc10_no_sales_data() -> None:
    evaluation = evaluate_one(make_item([], on_hand="10.000"))
    assert evaluation.valid_days == 0
    assert evaluation.sales_qty_total == "0.000"
    assert evaluation.data_quality == "INSUFFICIENT"
    assert evaluation.risk_level == "UNKNOWN"
    assert evaluation.risk_types == ["DATA_INSUFFICIENT"]
    assert evaluation.q_suggest is None


# --------------------------------------------------------------------------- #
# GC-11 ~ GC-18：补货约束与边界
# --------------------------------------------------------------------------- #
def test_gc11_moq_applied() -> None:
    evaluation = evaluate_one(make_item(["5.000"] * 28, on_hand="60.000", moq="50.000"))
    assert evaluation.q_base == "30.000"
    assert evaluation.moq_applied == "50.000"
    assert evaluation.q_suggest == "50.000"


def test_gc12_order_multiple_applied() -> None:
    evaluation = evaluate_one(
        make_item(["5.000"] * 28, on_hand="17.000", order_multiple="10.000")
    )
    assert evaluation.q_base == "73.000"
    assert evaluation.order_multiple_applied == "10.000"
    assert evaluation.q_suggest == "80.000"


def test_gc13_budget_truncation() -> None:
    evaluation = evaluate_one(
        make_item(["5.000"] * 28, on_hand="175.000", lead_time_days=30),
        make_params(coverage_days=30, budget_limit="150.000"),
    )
    assert evaluation.q_base == "200.000"
    assert evaluation.q_suggest == "150.000"
    assert evaluation.truncated_by_budget is True


def test_gc14_volatility_adjusted() -> None:
    evaluation = evaluate_one(make_item(["0.000"] * 15 + ["7.000"] * 13, on_hand="50.000"))
    assert evaluation.cv is not None
    assert Decimal(evaluation.cv) > Decimal("1.0")
    assert evaluation.volatility_adjusted is True
    assert evaluation.safety_stock == "19.500"


def test_gc15_safety_stock_override() -> None:
    evaluation = evaluate_one(
        make_item(["5.000"] * 28, on_hand="50.000", safety_stock_override="100.000")
    )
    assert evaluation.safety_stock == "100.000"
    assert evaluation.volatility_adjusted is False
    assert evaluation.q_base == "120.000"
    assert evaluation.q_suggest == "120.000"


def test_gc16_effective_in_transit() -> None:
    evaluation = evaluate_one(
        make_item(["5.000"] * 28, on_hand="10.000", transit="8.000")
    )
    assert evaluation.inventory_position == "18.000"
    assert evaluation.q_base == "72.000"
    assert evaluation.q_suggest == "72.000"


def test_gc17_trimmed_daily_average() -> None:
    evaluation = evaluate_one(
        make_item(["5.000"] * 27 + ["100.000"], on_hand="8.000")
    )
    assert evaluation.avg_daily_sales == "5.000"
    assert evaluation.sales_qty_total == "235.000"
    assert evaluation.q_base == "82.000"
    assert evaluation.q_suggest == "82.000"
    assert evaluation.data_quality == "GOOD"


def test_gc18_all_rules_miss_and_no_suggestion() -> None:
    evaluation = evaluate_one(
        make_item(["1.000"] * 28, on_hand="50.000", lead_time_days=0),
        make_params(coverage_days=0),
    )
    assert evaluation.risk_level == "NONE"
    assert evaluation.risk_types == []
    assert evaluation.safety_stock == "0.000"
    assert evaluation.q_base == "0.000"
    assert evaluation.q_suggest is None


# --------------------------------------------------------------------------- #
# 结构约束
# --------------------------------------------------------------------------- #
def test_item_evaluation_has_exactly_the_25_deterministic_fields() -> None:
    assert set(ItemEvaluation.model_fields) == DETERMINISTIC_FIELDS
    assert len(DETERMINISTIC_FIELDS) == 25


def test_order_and_warehouse_id_are_preserved() -> None:
    items = [
        make_item(["5.000"] * 28, sku_id=9101),
        make_item(["1.000"] * 28, sku_id=9102),
        make_item(["0.000"] * 28, sku_id=9103),
    ]
    evaluations = evaluate_items(items, make_params(), warehouse_id=777, date_to=DATE_TO)
    assert [evaluation.sku_id for evaluation in evaluations] == [9101, 9102, 9103]
    assert {evaluation.warehouse_id for evaluation in evaluations} == {777}


def test_echoed_quantities_are_normalized_to_three_decimals() -> None:
    item = make_item(["5.000"] * 28, on_hand="8", committed="1", transit="2")
    evaluation = evaluate_one(item)
    assert evaluation.on_hand_qty == "8.000"
    assert evaluation.committed_qty == "1.000"
    assert evaluation.effective_in_transit_qty == "2.000"
    assert evaluation.inventory_position == "9.000"


def test_risk_findings_are_not_empty_for_adequate_data() -> None:
    evaluation = evaluate_one(make_item(["5.000"] * 28, on_hand="8.000"))
    assert len(evaluation.risk_findings) == 5
    assert all(finding.threshold for finding in evaluation.risk_findings)
