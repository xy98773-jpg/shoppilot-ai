"""补货量计算用例（M4-T10）：`IP` / `SS` / `Q_base` / MOQ / 倍数 / 步长 / 预算。

覆盖 `ANALYSIS_CONTRACT §7.2` 的 GC-11/12/13/14/15/16/18，以及 `§5.30` 裁定的
取整方向（`ceil` / `floor`）、`IP` 为负、`D=0`/`D=null` 的字段集。

★ 断言一律字符串比较（`"6.250"`），禁止 float 近似。
"""
from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

from shoppilot_ai.app.analytics.evaluate import evaluate_items
from shoppilot_ai.app.analytics.sales_stats import SalesStats
from shoppilot_ai.app.rules.replenishment import compute_replenishment, resolve_lead_time
from shoppilot_ai.app.schemas.analysis_request import Item, Params

START = date(2026, 1, 1)
DATE_TO = START + timedelta(days=27)


def make_item(
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
    quantities: list[str] | None = None,
) -> Item:
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


def make_stats(
    avg_daily_sales: str | None = "5.000",
    *,
    valid_days: int = 28,
    cv: str | None = "0.0000",
    total: str = "140.000",
) -> SalesStats:
    return SalesStats(
        sku_id=9002,
        valid_days=valid_days,
        sales_qty_total=total,
        avg_daily_sales=avg_daily_sales,
        cv=cv,
        truncated_count=0,
        insufficient=avg_daily_sales is None,
    )


# --------------------------------------------------------------------------- #
# Golden cases：MOQ / 倍数 / 预算 / 波动 / 覆盖 / 在途 / Q_base<=0
# --------------------------------------------------------------------------- #
def test_gc11_moq_raises_quantity_to_moq() -> None:
    # D=5, L=R=7, SS=20, IP=60 ⇒ Q_base=30；MOQ=50 ⇒ 50
    result = compute_replenishment(
        make_stats("5.000"),
        make_item(on_hand="60.000", moq="50.000"),
        make_params(),
    )
    assert result.q_base == "30.000"
    assert result.moq_applied == "50.000"
    assert result.q_suggest == "50.000"
    assert result.order_multiple_applied is None


def test_gc12_order_multiple_rounds_up() -> None:
    # Q_base=73，multiple=10 ⇒ 80
    result = compute_replenishment(
        make_stats("5.000"),
        make_item(on_hand="17.000", order_multiple="10.000"),
        make_params(),
    )
    assert result.q_base == "73.000"
    assert result.order_multiple_applied == "10.000"
    assert result.q_suggest == "80.000"


def test_gc13_budget_truncates_quantity() -> None:
    # D=5, L=R=30, SS=75, IP=175 ⇒ Q_base=200；预算 150 ⇒ 150 + truncated
    params = make_params(coverage_days=30, budget_limit="150.000")
    items = [make_item(on_hand="175.000", lead_time_days=30)]
    evaluation = evaluate_items(items, params, warehouse_id=100, date_to=DATE_TO)[0]
    assert evaluation.q_base == "200.000"
    assert evaluation.q_suggest == "150.000"
    assert evaluation.truncated_by_budget is True
    assert any("预算" in warning for warning in evaluation.warnings)


def test_gc14_high_volatility_raises_safety_stock_by_half() -> None:
    # cv=1.0742 > 1.0 ⇒ factor 4 → 6；D=3.25 ⇒ SS=19.500（未上浮时应为 13.000）
    result = compute_replenishment(
        make_stats("3.250", cv="1.0742", total="91.000"),
        make_item(on_hand="50.000"),
        make_params(),
    )
    assert result.volatility_adjusted is True
    assert result.safety_stock == "19.500"


def test_gc15_safety_stock_override_wins() -> None:
    result = compute_replenishment(
        make_stats("5.000"),
        make_item(on_hand="50.000", safety_stock_override="100.000"),
        make_params(),
    )
    assert result.safety_stock == "100.000"
    assert result.volatility_adjusted is False
    assert result.q_base == "120.000"
    assert result.q_suggest == "120.000"


def test_gc15_override_disables_volatility_adjustment() -> None:
    # 即使 cv 超过阈值，覆盖绝对量后也不得上浮
    result = compute_replenishment(
        make_stats("3.250", cv="1.0742"),
        make_item(on_hand="50.000", safety_stock_override="100.000"),
        make_params(),
    )
    assert result.volatility_adjusted is False
    assert result.safety_stock == "100.000"


def test_gc16_effective_in_transit_enters_ip() -> None:
    result = compute_replenishment(
        make_stats("5.000"),
        make_item(on_hand="10.000", transit="8.000"),
        make_params(),
    )
    assert result.inventory_position == "18.000"
    assert result.q_base == "72.000"
    assert result.q_suggest == "72.000"


def test_gc18_q_base_non_positive_gives_null_suggestion() -> None:
    # D=1, on_hand=50, L=R=0, SS=0 ⇒ Q_base=-50 ⇒ 0.000 且无建议
    result = compute_replenishment(
        make_stats("1.000", total="28.000"),
        make_item(on_hand="50.000", lead_time_days=0),
        make_params(coverage_days=0),
    )
    assert result.q_base == "0.000"
    assert result.q_suggest is None
    assert result.moq_applied is None
    assert result.order_multiple_applied is None
    assert result.truncated_by_budget is False


# --------------------------------------------------------------------------- #
# 取整方向与精度
# --------------------------------------------------------------------------- #
def test_qty_step_rounds_up() -> None:
    # Q_base=29，step=10 ⇒ 30（ceil，不是 20）
    result = compute_replenishment(
        make_stats("5.000"),
        make_item(on_hand="61.000", qty_step="10.000"),
        make_params(),
    )
    assert result.q_base == "29.000"
    assert result.q_suggest == "30.000"


def test_order_multiple_takes_precedence_over_qty_step() -> None:
    result = compute_replenishment(
        make_stats("5.000"),
        make_item(on_hand="17.000", order_multiple="10.000", qty_step="3.000"),
        make_params(),
    )
    assert result.q_suggest == "80.000"
    assert result.order_multiple_applied == "10.000"


def test_safety_stock_factor_uses_ceiling() -> None:
    # L=5, ratio=0.5 ⇒ 2.5 ⇒ ceil 3；D=2 ⇒ SS=6.000（截断取整会得 5.000）
    result = compute_replenishment(
        make_stats("2.000", total="56.000"),
        make_item(on_hand="0.000", lead_time_days=5),
        make_params(),
    )
    assert result.safety_stock == "6.000"


def test_safety_stock_rounds_half_up_not_bankers() -> None:
    # D=0.333、factor=1 上浮 50% ⇒ 1.5 ⇒ SS=0.4995 ⇒ ROUND_HALF_UP 给 0.500
    result = compute_replenishment(
        make_stats("0.333", cv="1.5000", total="9.324"),
        make_item(on_hand="0.000", lead_time_days=1),
        make_params(),
    )
    assert result.safety_stock == "0.500"


def test_budget_floor_respects_qty_step() -> None:
    # q=30（step=10），预算 25 ⇒ floor(25/10)*10 = 20
    params = make_params(budget_limit="25.000")
    items = [make_item(on_hand="61.000", qty_step="10.000")]
    evaluation = evaluate_items(items, params, warehouse_id=100, date_to=DATE_TO)[0]
    assert evaluation.q_suggest == "20.000"
    assert evaluation.truncated_by_budget is True


# --------------------------------------------------------------------------- #
# 预算：run 级顺序扣减
# --------------------------------------------------------------------------- #
def test_budget_is_deducted_sequentially_across_items() -> None:
    # 两个 SKU 各建议 50；预算 60 ⇒ 第一个 50、第二个只剩 10
    params = make_params(budget_limit="60.000")
    items = [
        make_item(sku_id=9001, on_hand="60.000", moq="50.000"),
        make_item(sku_id=9002, on_hand="60.000", moq="50.000"),
    ]
    first, second = evaluate_items(items, params, warehouse_id=100, date_to=DATE_TO)
    assert first.q_suggest == "50.000"
    assert first.truncated_by_budget is False
    assert second.q_suggest == "10.000"
    assert second.truncated_by_budget is True
    assert any("预算" in warning for warning in second.warnings)


def test_budget_zero_truncates_to_zero_with_warning() -> None:
    params = make_params(budget_limit="0.000")
    items = [make_item(on_hand="60.000", moq="50.000")]
    evaluation = evaluate_items(items, params, warehouse_id=100, date_to=DATE_TO)[0]
    assert evaluation.q_suggest == "0.000"
    assert evaluation.truncated_by_budget is True
    assert any("截断为 0" in warning for warning in evaluation.warnings)


def test_budget_not_applied_when_within_limit() -> None:
    params = make_params(budget_limit="1000.000")
    items = [make_item(on_hand="60.000", moq="50.000")]
    evaluation = evaluate_items(items, params, warehouse_id=100, date_to=DATE_TO)[0]
    assert evaluation.q_suggest == "50.000"
    assert evaluation.truncated_by_budget is False


def test_budget_skips_items_without_suggestion() -> None:
    # 数据不足的 SKU 不消耗预算；后续 SKU 仍可用满额度
    params = make_params(budget_limit="60.000")
    items = [
        make_item(sku_id=9001, on_hand="10.000", quantities=["5.000"] * 6),
        make_item(sku_id=9002, on_hand="60.000", moq="50.000"),
    ]
    insufficient, normal = evaluate_items(items, params, warehouse_id=100, date_to=DATE_TO)
    assert insufficient.q_suggest is None
    assert normal.q_suggest == "50.000"
    assert normal.truncated_by_budget is False


# --------------------------------------------------------------------------- #
# 边界：负 IP / D=0 / D=null / 交期来源
# --------------------------------------------------------------------------- #
def test_negative_inventory_position_is_allowed() -> None:
    # 超卖：committed=5 > on_hand=0 ⇒ IP=-5；Q_base 按 IP 计算（90 - (-5) = 95）
    result = compute_replenishment(
        make_stats("5.000"),
        make_item(on_hand="0.000", committed="5.000"),
        make_params(),
    )
    assert result.inventory_position == "-5.000"
    assert result.q_base == "95.000"
    assert result.q_suggest == "95.000"


def test_d_zero_yields_zero_safety_stock_and_no_suggestion() -> None:
    result = compute_replenishment(
        make_stats("0.000", cv=None, total="0.000"),
        make_item(on_hand="50.000"),
        make_params(),
    )
    assert result.safety_stock == "0.000"
    assert result.q_base == "0.000"
    assert result.q_suggest is None


def test_data_insufficient_keeps_factual_fields_and_nulls_the_rest() -> None:
    # §5.30 #13：D=null ⇒ SS/Q_base/MOQ/倍数/q_suggest 全 null，IP/L/R 照常回显
    result = compute_replenishment(
        make_stats(None, valid_days=6, cv=None, total="30.000"),
        make_item(
            on_hand="10.000",
            transit="8.000",
            committed="2.000",
            lead_time_days=5,
        ),
        make_params(),
    )
    assert result.inventory_position == "16.000"
    assert result.lead_time_days == 5
    assert result.lead_time_source == "SKU"
    assert result.coverage_days == 7
    assert result.safety_stock is None
    assert result.q_base is None
    assert result.moq_applied is None
    assert result.order_multiple_applied is None
    assert result.q_suggest is None
    assert result.volatility_adjusted is False
    assert result.truncated_by_budget is False


def test_lead_time_falls_back_to_param_default() -> None:
    item = make_item(lead_time_days=None, lead_time_source="SKU")
    params = make_params(lead_time_default=9)
    lead_time, source = resolve_lead_time(item, params)
    assert lead_time == 9
    assert source == "DEFAULT"

    result = compute_replenishment(make_stats("5.000"), item, params)
    assert result.lead_time_days == 9
    assert result.lead_time_source == "DEFAULT"


def test_lead_time_source_defaults_when_item_source_missing() -> None:
    item = make_item(lead_time_days=5, lead_time_source=None)
    lead_time, source = resolve_lead_time(item, make_params())
    assert lead_time == 5
    assert source == "DEFAULT"


def test_moq_zero_falls_back_to_one() -> None:
    result = compute_replenishment(
        make_stats("5.000"),
        make_item(on_hand="61.000", moq="0.000"),
        make_params(),
    )
    assert result.moq_applied == "1.000"
    assert result.q_suggest == "29.000"


def test_amounts_are_three_decimal_strings() -> None:
    # 输入用整数写法（Schema 允许 "50"）时，输出必须规范成 3 位
    result = compute_replenishment(
        make_stats("5"),
        make_item(on_hand="50", moq="50", order_multiple="10"),
        make_params(),
    )
    assert result.inventory_position == "50.000"
    assert result.safety_stock == "20.000"
    assert result.q_base == "40.000"
    assert result.moq_applied == "50.000"
    assert result.order_multiple_applied == "10.000"
    assert result.q_suggest == "50.000"
    assert Decimal(result.q_suggest) == Decimal("50")


def test_replenishment_is_pure() -> None:
    item = make_item(on_hand="60.000", moq="50.000")
    params = make_params()
    first = compute_replenishment(make_stats("5.000"), item, params)
    second = compute_replenishment(make_stats("5.000"), item, params)
    assert first.model_dump() == second.model_dump()
