"""`R-STOCKOUT` / `R-LOWSTOCK` / `R-OVERSTOCK` / `R-SLOWMOVING` / `R-SALESANOMALY` /
`R-DATAINSUFFICIENT` 六规则的定向用例（M4-T09）。

用例表对齐 `TEST_STRATEGY.md §11` 的 21 行（每条规则 ≥3：命中 / 边界 / 未命中 / 数据不足），
口径以 `DESIGN_REVIEW.md §5.30` 的 15 条裁定为准。

★ 断言方式：数值一律用**字符串**比较（`"6.250"`），禁止 float 近似（`ANALYSIS_CONTRACT §7.3`）。
★ `threshold` 只断言「包含关键数字」（§7.3 允许规范化后比较）。
"""
from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

from shoppilot_ai.app.analytics.sales_stats import SalesStats, compute_sales_stats
from shoppilot_ai.app.rules.risk_rules import (
    ANOMALY_SIGMA,
    SLOW_MOVING_DAYS,
    STOCKOUT_DAYS,
    evaluate_risks,
)
from shoppilot_ai.app.schemas.analysis_request import Item, Params
from shoppilot_ai.app.schemas.analysis_response import RiskFinding

START = date(2026, 1, 1)
DATE_TO = START + timedelta(days=27)


# --------------------------------------------------------------------------- #
# 夹具
# --------------------------------------------------------------------------- #
def make_item(
    quantities: list[str] | None = None,
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
    listed_date: date | None = None,
    start: date = START,
) -> Item:
    """构造合法 `Item`；`quantities` 省略时为 28 天 `5.000`（让 SLOW_MOVING 不命中）。"""
    if quantities is None:
        quantities = ["5.000"] * 28
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
            "listed_date": listed_date,
            "sales_series": [
                {"date": (start + timedelta(days=index)).isoformat(), "qty": qty}
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


def make_stats(
    avg_daily_sales: str | None = "5.000",
    *,
    valid_days: int = 28,
    cv: str | None = "0.0000",
    total: str = "140.000",
    truncated_count: int = 0,
) -> SalesStats:
    """直接构造 `SalesStats`：规则边界用例不应被 3σ 截尾与样本构造干扰。"""
    return SalesStats(
        sku_id=9002,
        valid_days=valid_days,
        sales_qty_total=total,
        avg_daily_sales=avg_daily_sales,
        cv=cv,
        truncated_count=truncated_count,
        insufficient=avg_daily_sales is None,
    )


def finding_of(evaluation, rule_code: str) -> RiskFinding | None:
    """按 `rule_code` 取 finding；`None` = 该规则被跳过（未输出）。"""
    for finding in evaluation.risk_findings:
        if finding.rule_code == rule_code:
            return finding
    return None


def results(evaluation) -> dict[str, str]:
    return {finding.rule_code: finding.result for finding in evaluation.risk_findings}


def evaluate(
    stats: SalesStats,
    item: Item | None = None,
    params: Params | None = None,
    date_to: date = DATE_TO,
):
    return evaluate_risks(stats, item or make_item(), params or make_params(), date_to)


# --------------------------------------------------------------------------- #
# R-STOCKOUT
# --------------------------------------------------------------------------- #
def test_stockout_zero_onhand_is_high() -> None:
    evaluation = evaluate(make_stats("5.000"), make_item(on_hand="0.000"))
    finding = finding_of(evaluation, "R-STOCKOUT")
    assert finding is not None
    assert finding.result == "HIGH"
    assert finding.inputs["on_hand_qty"] == "0.000"
    assert finding.inputs["D"] == "5.000"
    assert finding.inputs["L"] == 7
    assert "0.000" in finding.threshold


def test_stockout_boundary_two_days_is_high() -> None:
    evaluation = evaluate(make_stats("5.000"), make_item(on_hand="10.000"))
    finding = finding_of(evaluation, "R-STOCKOUT")
    assert finding is not None
    # on_hand/D == 2.000 恰好命中 `<= 2`
    assert finding.result == "HIGH"
    assert "2.00" in finding.threshold
    assert "STOCKOUT_DAYS(2)" in finding.threshold


def test_stockout_within_leadtime_is_medium() -> None:
    # D=5, on_hand=30 ⇒ 6 天：> 2 且 <= L(7)
    evaluation = evaluate(make_stats("5.000"), make_item(on_hand="30.000"))
    finding = finding_of(evaluation, "R-STOCKOUT")
    assert finding is not None
    assert finding.result == "MEDIUM"
    assert "L(7)" in finding.threshold


def test_stockout_not_hit() -> None:
    # D=2, on_hand=100 ⇒ 50 天 > L(7)
    evaluation = evaluate(
        make_stats("2.000", total="56.000"),
        make_item(on_hand="100.000"),
    )
    finding = finding_of(evaluation, "R-STOCKOUT")
    assert finding is not None
    assert finding.result == "NOT_TRIGGERED"
    assert "L(7)" in finding.threshold


def test_stockout_skipped_when_data_insufficient() -> None:
    evaluation = evaluate(
        make_stats(None, valid_days=6, cv=None, total="30.000"),
        make_item(["5.000"] * 6, on_hand="0.000"),
    )
    assert finding_of(evaluation, "R-STOCKOUT") is None
    assert results(evaluation) == {"R-DATAINSUFFICIENT": "UNKNOWN"}


# --------------------------------------------------------------------------- #
# R-LOWSTOCK
# --------------------------------------------------------------------------- #
def test_lowstock_hit() -> None:
    # D=2, L=R=7 ⇒ 阈值 28；IP=20 < 28
    evaluation = evaluate(
        make_stats("2.000", total="56.000"),
        make_item(on_hand="20.000"),
    )
    finding = finding_of(evaluation, "R-LOWSTOCK")
    assert finding is not None
    assert finding.result == "MEDIUM"
    assert finding.inputs["IP"] == "20.000"
    assert finding.inputs["L"] == 7
    assert finding.inputs["R"] == 7
    assert "28.000" in finding.threshold


def test_lowstock_not_hit() -> None:
    evaluation = evaluate(
        make_stats("2.000", total="56.000"),
        make_item(on_hand="100.000"),
    )
    finding = finding_of(evaluation, "R-LOWSTOCK")
    assert finding is not None
    assert finding.result == "NOT_TRIGGERED"


def test_lowstock_inventory_position_includes_transit_and_committed() -> None:
    # IP = 10 + 8 - 5 = 13；阈值 28 ⇒ 命中
    evaluation = evaluate(
        make_stats("2.000", total="56.000"),
        make_item(on_hand="10.000", transit="8.000", committed="5.000"),
    )
    finding = finding_of(evaluation, "R-LOWSTOCK")
    assert finding is not None
    assert finding.inputs["IP"] == "13.000"
    assert finding.result == "MEDIUM"


# --------------------------------------------------------------------------- #
# R-OVERSTOCK
# --------------------------------------------------------------------------- #
def test_overstock_medium() -> None:
    # D=1, on_hand=95 ⇒ 95 天 > 90
    evaluation = evaluate(
        make_stats("1.000", total="28.000"),
        make_item(on_hand="95.000"),
    )
    finding = finding_of(evaluation, "R-OVERSTOCK")
    assert finding is not None
    assert finding.result == "MEDIUM"
    assert "90" in finding.threshold


def test_overstock_high() -> None:
    # D=1, on_hand=181 ⇒ 181 天 > 180
    evaluation = evaluate(
        make_stats("1.000", total="28.000"),
        make_item(on_hand="181.000"),
    )
    finding = finding_of(evaluation, "R-OVERSTOCK")
    assert finding is not None
    assert finding.result == "HIGH"
    assert "180" in finding.threshold


def test_overstock_not_hit() -> None:
    evaluation = evaluate(
        make_stats("1.000", total="28.000"),
        make_item(on_hand="30.000"),
    )
    finding = finding_of(evaluation, "R-OVERSTOCK")
    assert finding is not None
    assert finding.result == "NOT_TRIGGERED"


def test_overstock_boundary_ninety_days_not_hit() -> None:
    # 严格大于：恰好 90 天不命中
    evaluation = evaluate(
        make_stats("1.000", total="28.000"),
        make_item(on_hand="90.000"),
    )
    assert finding_of(evaluation, "R-OVERSTOCK").result == "NOT_TRIGGERED"


def test_overstock_boundary_one_hundred_eighty_days_is_medium_not_high() -> None:
    # 严格大于：恰好 180 天是 MEDIUM（不是 HIGH）
    evaluation = evaluate(
        make_stats("1.000", total="28.000"),
        make_item(on_hand="180.000"),
    )
    assert finding_of(evaluation, "R-OVERSTOCK").result == "MEDIUM"


# --------------------------------------------------------------------------- #
# R-SLOWMOVING
# --------------------------------------------------------------------------- #
def test_slowmoving_hit() -> None:
    # 28 天全 0（D=0，数据充足）⇒ 滞销 MEDIUM；库存类三条被跳过
    evaluation = evaluate(
        make_stats("0.000", cv=None, total="0.000"),
        make_item(["0.000"] * 28, on_hand="50.000"),
    )
    finding = finding_of(evaluation, "R-SLOWMOVING")
    assert finding is not None
    assert finding.result == "MEDIUM"
    assert finding.inputs["last_observed_sales_qty"] == "0.000"
    assert finding.inputs["observed_days"] == 28


def test_slowmoving_not_hit_when_any_sales() -> None:
    quantities = ["0.000"] * 27 + ["3.000"]
    evaluation = evaluate(
        make_stats("0.107", total="3.000"),
        make_item(quantities, on_hand="50.000"),
    )
    finding = finding_of(evaluation, "R-SLOWMOVING")
    assert finding is not None
    assert finding.result == "NOT_TRIGGERED"
    assert finding.inputs["last_observed_sales_qty"] == "3.000"


def test_slowmoving_no_stock_not_hit() -> None:
    # 无销量但也没库存：`on_hand > 0` 不满足 ⇒ 未命中（不是跳过）
    evaluation = evaluate(
        make_stats("0.000", cv=None, total="0.000"),
        make_item(["0.000"] * 28, on_hand="0.000"),
    )
    finding = finding_of(evaluation, "R-SLOWMOVING")
    assert finding is not None
    assert finding.result == "NOT_TRIGGERED"


def test_slowmoving_skipped_for_new_product() -> None:
    # 上架 10 天 < 观察窗口 28 天 ⇒ 不判定，只写 warning
    evaluation = evaluate(
        make_stats("0.000", cv=None, total="0.000"),
        make_item(
            ["0.000"] * 28,
            on_hand="50.000",
            listed_date=DATE_TO - timedelta(days=9),
        ),
    )
    assert finding_of(evaluation, "R-SLOWMOVING") is None
    assert any("新品" in warning for warning in evaluation.warnings)


def test_slowmoving_uses_min_of_thirty_and_window_days() -> None:
    # window_days=14 ⇒ observed=14；threshold 必须写明「可用窗口 14 天（< 30）」
    evaluation = evaluate(
        make_stats("0.000", cv=None, total="0.000"),
        make_item(["0.000"] * 14, on_hand="50.000"),
        make_params(window_days=14, min_days=7),
    )
    finding = finding_of(evaluation, "R-SLOWMOVING")
    assert finding is not None
    assert finding.result == "MEDIUM"
    assert finding.inputs["observed_days"] == 14
    assert "14" in finding.threshold and "< 30" in finding.threshold
    assert any("14 天" in warning for warning in evaluation.warnings)


def test_slowmoving_counts_only_last_observed_days() -> None:
    # 销量全部发生在窗口之前（date_to 右移 40 天）⇒ 观察窗口内为 0 ⇒ 命中
    item = make_item(["5.000"] * 10, on_hand="50.000", start=START)
    evaluation = evaluate(
        make_stats("0.000", cv=None, total="50.000"),
        item,
        date_to=START + timedelta(days=40),
    )
    finding = finding_of(evaluation, "R-SLOWMOVING")
    assert finding is not None
    assert finding.inputs["last_observed_sales_qty"] == "0.000"
    assert finding.result == "MEDIUM"


def test_slowmoving_constant_is_thirty() -> None:
    assert SLOW_MOVING_DAYS == 30


# --------------------------------------------------------------------------- #
# R-SALESANOMALY
# --------------------------------------------------------------------------- #
def test_anomaly_hit() -> None:
    # 前 21 天：10×1 + 10×3 + 1×2 = 42（均值 2、std≈0.976）；近 7 天 7×10 ⇒ 日均 10
    quantities = ["1.000"] * 10 + ["3.000"] * 10 + ["2.000"] + ["10.000"] * 7
    evaluation = evaluate(
        make_stats("4.000", total="112.000"),
        make_item(quantities, on_hand="100.000"),
    )
    finding = finding_of(evaluation, "R-SALESANOMALY")
    assert finding is not None
    assert finding.result == "LOW"
    assert finding.inputs["last7_avg"] == "10.000"
    assert finding.inputs["prev21_avg"] == "2.000"
    assert "50%" in finding.threshold


def test_anomaly_not_hit() -> None:
    # 近 7 天 2.5、前 21 天 2、std 大（0 与 4 交替 ⇒ std≈1）⇒ 不命中
    quantities = ["0.000", "4.000"] * 10 + ["0.000"] + ["2.500"] * 7
    evaluation = evaluate(
        make_stats("2.000", total="57.500"),
        make_item(quantities, on_hand="100.000"),
    )
    finding = finding_of(evaluation, "R-SALESANOMALY")
    assert finding is not None
    assert finding.result == "NOT_TRIGGERED"


def test_anomaly_skipped_when_window_too_short() -> None:
    evaluation = evaluate(
        make_stats("5.000"),
        make_item(["5.000"] * 14, on_hand="100.000"),
        make_params(window_days=14),
    )
    assert finding_of(evaluation, "R-SALESANOMALY") is None
    assert any("R-SALESANOMALY" in warning for warning in evaluation.warnings)


def test_anomaly_skipped_when_baseline_records_insufficient() -> None:
    # 只有近 7 天有记录（days 21..27），基线桶 0 条 < min_days(7) ⇒ 跳过（尽管 D 可算）
    evaluation = evaluate(
        make_stats("2.500", total="70.000"),
        make_item(
            ["10.000"] * 7,
            on_hand="100.000",
            start=DATE_TO - timedelta(days=6),
        ),
    )
    assert finding_of(evaluation, "R-SALESANOMALY") is None
    assert any("基线窗口" in warning for warning in evaluation.warnings)


def test_anomaly_zero_baseline_positive_recent_hits() -> None:
    # 基线 21 天记录存在但 qty=0，近 7 天日均 10 ⇒ 变化率 ∞（可命中）
    quantities = ["0.000"] * 21 + ["10.000"] * 7
    evaluation = evaluate(
        make_stats("2.500", total="70.000"),
        make_item(quantities, on_hand="100.000"),
    )
    finding = finding_of(evaluation, "R-SALESANOMALY")
    assert finding is not None
    assert finding.result == "LOW"
    assert finding.inputs["change_rate"] == "∞"
    assert "∞" in finding.threshold


def test_anomaly_zero_baseline_zero_recent_not_hit() -> None:
    # 前后都是 0 ⇒ 变化率不成立（`§5.30 #8`）
    quantities = ["0.000"] * 28
    evaluation = evaluate(
        make_stats("0.000", cv=None, total="0.000"),
        make_item(quantities, on_hand="50.000"),
    )
    finding = finding_of(evaluation, "R-SALESANOMALY")
    assert finding is not None
    assert finding.result == "NOT_TRIGGERED"
    assert finding.inputs["change_rate"] == "0.000"


def test_anomaly_uses_natural_day_buckets_not_last_records() -> None:
    # 近 7 天只有 1 条记录（qty=70），缺失 6 天补 0 ⇒ 日均 70/7=10（不是 70）
    item = make_item(["2.000"] * 21, on_hand="100.000")
    item = item.model_copy(
        update={
            "sales_series": list(item.sales_series)
            + [
                item.sales_series[0].model_copy(update={"date": DATE_TO, "qty": "70.000"})
            ]
        }
    )
    evaluation = evaluate(make_stats("4.000", total="112.000"), item)
    finding = finding_of(evaluation, "R-SALESANOMALY")
    assert finding is not None
    assert finding.inputs["last7_avg"] == "10.000"
    assert finding.result == "LOW"


# --------------------------------------------------------------------------- #
# R-DATAINSUFFICIENT
# --------------------------------------------------------------------------- #
def test_datainsufficient_hit() -> None:
    evaluation = evaluate(
        make_stats(None, valid_days=6, cv=None, total="30.000"),
        make_item(["5.000"] * 6, on_hand="10.000"),
    )
    finding = finding_of(evaluation, "R-DATAINSUFFICIENT")
    assert finding is not None
    assert finding.result == "UNKNOWN"
    assert finding.inputs == {"valid_days": 6, "min_days": 7}
    assert evaluation.risk_level == "UNKNOWN"
    assert evaluation.risk_types == ["DATA_INSUFFICIENT"]
    assert len(evaluation.risk_findings) == 1
    assert any("数据不足" in warning for warning in evaluation.warnings)


def test_datainsufficient_zero_sales_series() -> None:
    evaluation = evaluate(
        make_stats(None, valid_days=0, cv=None, total="0.000"),
        make_item([], on_hand="10.000"),
    )
    assert results(evaluation) == {"R-DATAINSUFFICIENT": "UNKNOWN"}
    assert evaluation.risk_level == "UNKNOWN"


def test_datainsufficient_not_hit_at_min_days_boundary() -> None:
    # n == min_days == 7：不判数据不足，正常计算
    evaluation = evaluate(
        make_stats("5.000", valid_days=7, total="35.000"),
        make_item(["5.000"] * 7, on_hand="100.000"),
    )
    assert finding_of(evaluation, "R-DATAINSUFFICIENT") is None
    assert evaluation.risk_level != "UNKNOWN"


# --------------------------------------------------------------------------- #
# 合成与输出结构
# --------------------------------------------------------------------------- #
def test_risk_level_is_max_of_hit_levels() -> None:
    # STOCKOUT=HIGH（on_hand/D=1.6）+ LOWSTOCK=MEDIUM ⇒ HIGH
    evaluation = evaluate(make_stats("5.000"), make_item(on_hand="8.000"))
    assert results(evaluation) == {
        "R-STOCKOUT": "HIGH",
        "R-LOWSTOCK": "MEDIUM",
        "R-OVERSTOCK": "NOT_TRIGGERED",
        "R-SLOWMOVING": "NOT_TRIGGERED",
        "R-SALESANOMALY": "NOT_TRIGGERED",
    }
    assert evaluation.risk_level == "HIGH"
    assert evaluation.risk_types == ["STOCKOUT", "LOW_STOCK"]


def test_findings_order_is_fixed() -> None:
    evaluation = evaluate(make_stats("5.000"), make_item(on_hand="8.000"))
    assert [finding.rule_code for finding in evaluation.risk_findings] == [
        "R-STOCKOUT",
        "R-LOWSTOCK",
        "R-OVERSTOCK",
        "R-SLOWMOVING",
        "R-SALESANOMALY",
    ]


def test_not_triggered_findings_are_kept() -> None:
    # `§5.30 #2`：前置满足就必须输出 finding，未命中写 NOT_TRIGGERED
    evaluation = evaluate(make_stats("5.000"), make_item(on_hand="200.000"))
    assert "NOT_TRIGGERED" in results(evaluation).values()
    assert all(finding.threshold for finding in evaluation.risk_findings)


def test_every_finding_carries_inputs_and_numeric_threshold() -> None:
    evaluation = evaluate(make_stats("5.000"), make_item(on_hand="8.000"))
    for finding in evaluation.risk_findings:
        assert finding.inputs, f"{finding.rule_code} 缺 inputs"
        assert any(char.isdigit() for char in finding.threshold), (
            f"{finding.rule_code} 的 threshold 未含实际数值"
        )


def test_d_zero_skips_three_stock_rules_with_warnings() -> None:
    # `§5.30 #4`：D=0 ⇒ 库存类三条跳过（无 finding）+ 各一条 warning；滞销照常判定
    evaluation = evaluate(
        make_stats("0.000", cv=None, total="0.000"),
        make_item(["0.000"] * 28, on_hand="50.000"),
    )
    assert set(results(evaluation)) == {"R-SLOWMOVING", "R-SALESANOMALY"}
    assert results(evaluation)["R-SLOWMOVING"] == "MEDIUM"
    skipped = [
        warning
        for warning in evaluation.warnings
        if "已跳过" in warning and "D=0" in warning
    ]
    assert len(skipped) == 3
    assert evaluation.risk_level == "MEDIUM"
    assert evaluation.risk_types == ["SLOW_MOVING"]


def test_sigma_constants_are_not_shared_with_data_quality_or_trim() -> None:
    from shoppilot_ai.app.analytics.sales_stats import TRIM_SIGMA
    from shoppilot_ai.app.validation.data_quality import ANOMALY_SIGMA as QUALITY_SIGMA

    assert Decimal("2.0") == QUALITY_SIGMA
    assert ANOMALY_SIGMA == Decimal("2.0")
    assert STOCKOUT_DAYS == Decimal("2")
    assert TRIM_SIGMA == Decimal("3")


def test_risk_rules_are_pure_functions() -> None:
    # 同一输入重复调用结果稳定（无全局状态 / 无副作用）
    item = make_item(on_hand="8.000")
    params = make_params()
    stats = compute_sales_stats(item, params)
    first = evaluate_risks(stats, item, params, DATE_TO)
    second = evaluate_risks(stats, item, params, DATE_TO)
    assert first.model_dump() == second.model_dump()
