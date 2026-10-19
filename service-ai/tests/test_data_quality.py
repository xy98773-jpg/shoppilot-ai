"""M4-T07 数据质量分级测试（`ANALYSIS_CONTRACT §4.1/§4.2`）。

覆盖任务书 §C4 的边界集：
- 天数比例 0.8 / 0.5；异常值比例 5% / 15%；
- `valid_days = min_days − 1`、空序列、恒定序列（std=0）、`window_days` 非 28（14 / 60）；
- 「取更差档」两个方向（天数 GOOD + 异常 10% ⇒ FAIR；天数 POOR + 异常 0% ⇒ POOR）；
- 全 INSUFFICIENT ⇒ `analyzable=false`；混合 ⇒ true；`per_sku` 顺序与输入一致。

★ 异常值样本经过数学核对：`[1.000]×n + [10.000]×k` 在 20 条时恰好 3 个异常（15% 边界）、
22 条时 4 个（18.18% > 15%）。用「同值异常」构造 20% 会落在 `mean+2σ` 的**等号**上
（z 恰好 2.0），不属于「超出」，故不采用。
"""
from __future__ import annotations

from datetime import date, timedelta

from shoppilot_ai.app.schemas.analysis_request import AnalyzeRequest
from shoppilot_ai.app.validation.data_quality import (
    ANOMALY_SIGMA,
    assess,
    count_outliers,
    grade,
)

START = date(2026, 1, 1)


def _qty(values: list[float]) -> list[str]:
    return [f"{value:.3f}" for value in values]


def make_request(
    specs: list[tuple[int, list[float]]],
    *,
    window_days: int = 28,
    min_days: int = 7,
    scope_sku_ids: list[int] | None = None,
) -> AnalyzeRequest:
    """按 `(sku_id, [qty...])` 构造合法 `AnalyzeRequest`（日期连续、可空字段取默认）。

    :param scope_sku_ids: 显式指定 `scope.sku_ids`；缺省取 `specs` 的 SKU 列表。
        用于构造「scope 有 SKU、items 为空」的场景（R113：Java 装配前恒传空数组）。
    """
    items = []
    for sku_id, quantities in specs:
        series = [
            {"date": (START + timedelta(days=index)).isoformat(), "qty": qty}
            for index, qty in enumerate(_qty(quantities))
        ]
        items.append(
            {
                "sku_id": sku_id,
                "on_hand_qty": "0.000",
                "committed_qty": "0.000",
                "effective_in_transit_qty": "0.000",
                "sales_series": series,
            }
        )
    sku_ids = scope_sku_ids if scope_sku_ids is not None else [sku for sku, _ in specs]
    return AnalyzeRequest.model_validate(
        {
            "analysis_run_id": 1,
            "team_id": 7,
            "scope": {"scope_type": "SELECTED_SKUS", "sku_ids": sku_ids},
            "date_from": START.isoformat(),
            "date_to": (START + timedelta(days=400)).isoformat(),
            "params": {
                "window_days": window_days,
                "min_days": min_days,
                "enable_llm": False,
            },
            "algorithm_version": "avg-ma-3sigma-v1",
            "items": items,
        }
    )


# --------------------------------------------------------------------------- #
# 纯函数：异常值计数与分档
# --------------------------------------------------------------------------- #
def test_count_outliers_uses_population_std_and_strict_bounds() -> None:
    assert ANOMALY_SIGMA == 2.0
    assert count_outliers([]) == 0
    assert count_outliers([5.0]) == 0, "单点没有离群概念"
    assert count_outliers([5.0] * 28) == 0, "std=0 时不得判异常"
    assert count_outliers([1.0] * 22 + [100.0]) == 1
    assert count_outliers([1.0] * 22 + [10.0, 10.0]) == 2
    assert count_outliers([1.0] * 17 + [10.0] * 3) == 3
    assert count_outliers([1.0] * 18 + [10.0] * 4) == 4


def test_grade_boundaries() -> None:
    # 天数比例：0.8 恰好 GOOD；0.76 FAIR；0.5 恰好 FAIR；0.45 POOR
    assert grade(valid_days=20, outlier_count=0, window_days=25, min_days=7) == "GOOD"
    assert grade(valid_days=19, outlier_count=0, window_days=25, min_days=7) == "FAIR"
    assert grade(valid_days=10, outlier_count=0, window_days=20, min_days=7) == "FAIR"
    assert grade(valid_days=9, outlier_count=0, window_days=20, min_days=7) == "POOR"
    # INSUFFICIENT 覆盖其它档
    assert grade(valid_days=6, outlier_count=0, window_days=28, min_days=7) == "INSUFFICIENT"
    assert grade(valid_days=0, outlier_count=0, window_days=28, min_days=7) == "INSUFFICIENT"


def test_grade_outlier_boundaries() -> None:
    # 23 天 1 个异常 = 4.35% ≤ 5% → GOOD（天数 23/28=0.82 也 GOOD）
    assert grade(valid_days=23, outlier_count=1, window_days=28, min_days=7) == "GOOD"
    # 20 天 3 个异常 = 15% ≤ 15% → FAIR
    assert grade(valid_days=20, outlier_count=3, window_days=28, min_days=7) == "FAIR"
    # 22 天 4 个异常 = 18.18% > 15% → POOR（天数 0.786 只有 FAIR，取更差）
    assert grade(valid_days=22, outlier_count=4, window_days=28, min_days=7) == "POOR"


# --------------------------------------------------------------------------- #
# 端到端：assess()
# --------------------------------------------------------------------------- #
def test_day_ratio_boundary_at_eighty_percent() -> None:
    result = assess(make_request([(9002, [1.0] * 20)], window_days=25))
    row = result.per_sku[0]
    assert row.valid_days == 20
    assert row.missing_days == 5
    assert row.quality == "GOOD"


def test_day_ratio_just_below_eighty_percent_is_fair() -> None:
    result = assess(make_request([(9002, [1.0] * 19)], window_days=25))
    assert result.per_sku[0].quality == "FAIR"


def test_day_ratio_at_fifty_percent_is_fair() -> None:
    result = assess(make_request([(9002, [1.0] * 10)], window_days=20))
    assert result.per_sku[0].quality == "FAIR"


def test_day_ratio_below_fifty_percent_is_poor() -> None:
    result = assess(make_request([(9002, [1.0] * 9)], window_days=20))
    assert result.per_sku[0].quality == "POOR"


def test_outlier_ratio_at_five_percent_is_good() -> None:
    result = assess(make_request([(9002, [1.0] * 22 + [100.0])]))
    row = result.per_sku[0]
    assert row.valid_days == 23
    assert row.outlier_count == 1
    assert row.quality == "GOOD"


def test_outlier_ratio_at_fifteen_percent_is_fair() -> None:
    result = assess(make_request([(9002, [1.0] * 17 + [10.0] * 3)]))
    row = result.per_sku[0]
    assert row.outlier_count == 3
    assert row.quality == "FAIR"


def test_outlier_ratio_above_fifteen_percent_is_poor() -> None:
    result = assess(make_request([(9002, [1.0] * 18 + [10.0] * 4)]))
    row = result.per_sku[0]
    assert row.outlier_count == 4
    assert row.quality == "POOR"


def test_below_min_days_is_insufficient() -> None:
    result = assess(make_request([(9002, [1.0] * 6)], min_days=7))
    row = result.per_sku[0]
    assert row.valid_days == 6
    assert row.missing_days == 22
    assert row.quality == "INSUFFICIENT"


def test_empty_series_is_insufficient() -> None:
    result = assess(make_request([(9002, [])]))
    row = result.per_sku[0]
    assert row.valid_days == 0
    assert row.missing_days == 28
    assert row.outlier_count == 0
    assert row.quality == "INSUFFICIENT"
    assert result.analyzable is False


def test_constant_series_has_zero_outliers() -> None:
    result = assess(make_request([(9002, [5.0] * 28)]))
    row = result.per_sku[0]
    assert row.outlier_count == 0
    assert row.quality == "GOOD"


def test_window_days_other_than_default() -> None:
    short = assess(make_request([(9002, [1.0] * 14)], window_days=14))
    assert short.window_days == 14
    assert short.per_sku[0].missing_days == 0
    assert short.per_sku[0].quality == "GOOD"

    long = assess(make_request([(9002, [1.0] * 48)], window_days=60))
    assert long.window_days == 60
    assert long.per_sku[0].missing_days == 12
    assert long.per_sku[0].quality == "GOOD", "48/60 = 0.8 恰好 GOOD"


def test_missing_days_never_negative() -> None:
    result = assess(make_request([(9002, [1.0] * 20)], window_days=14))
    assert result.per_sku[0].missing_days == 0


def test_worse_grade_wins_both_directions() -> None:
    # 天数 GOOD（24/28=0.857）+ 异常 8.33%（FAIR）⇒ FAIR
    good_days_fair_outliers = assess(make_request([(9002, [1.0] * 22 + [10.0, 10.0])]))
    assert good_days_fair_outliers.per_sku[0].quality == "FAIR"

    # 天数 POOR（12/28=0.43）+ 异常 0% ⇒ POOR
    poor_days_no_outliers = assess(make_request([(9002, [1.0] * 12)]))
    assert poor_days_no_outliers.per_sku[0].quality == "POOR"


def test_aggregate_counts_and_average() -> None:
    result = assess(
        make_request(
            [
                (9002, [1.0] * 28),  # GOOD, missing 0
                (9003, []),          # INSUFFICIENT, missing 28
                (9005, [1.0] * 20),  # 20/28 = 0.714 → FAIR, missing 8
            ]
        )
    )
    assert result.total_skus == 3
    assert result.analyzable is True
    assert result.analyzable_skus == 2
    assert result.insufficient_skus == 1
    assert result.missing_days_total == 36
    assert result.valid_days_avg == 16.0


def test_all_insufficient_is_not_analyzable() -> None:
    result = assess(make_request([(9002, []), (9003, [1.0] * 2)]))
    assert result.analyzable is False
    assert result.analyzable_skus == 0
    assert result.insufficient_skus == 2


def test_per_sku_order_follows_input() -> None:
    result = assess(
        make_request([(9005, [1.0] * 28), (9001, []), (9003, [1.0] * 20)])
    )
    assert [row.sku_id for row in result.per_sku] == [9005, 9001, 9003]


def test_valid_days_avg_uses_half_up_rounding() -> None:
    # (1+1+1+2)/4 = 1.25 → HALF_UP → 1.3（银行家舍入会得 1.2）
    result = assess(
        make_request(
            [
                (9001, [1.0]),
                (9002, [1.0]),
                (9003, [1.0]),
                (9005, [1.0, 2.0]),
            ],
            min_days=1,
        )
    )
    assert result.valid_days_avg == 1.3


def test_warnings_text_and_order() -> None:
    result = assess(
        make_request(
            [
                (9001, []),          # INSUFFICIENT
                (9002, [1.0] * 12),  # POOR
                (9003, [1.0] * 20),  # FAIR
            ]
        )
    )
    assert len(result.warnings) == 3
    assert result.warnings[0].startswith("1 个 SKU 有效销售天数不足")
    assert "DATA_INSUFFICIENT" in result.warnings[0]
    assert "数据质量较差" in result.warnings[1]
    assert "数据覆盖一般" in result.warnings[2]


# --------------------------------------------------------------------------- #
# R113 / R114（B16.1）
# --------------------------------------------------------------------------- #
def test_empty_items_returns_zeroed_summary() -> None:
    """R113：`items=[]` 是合法输入（Java 装配前恒传空数组），返回零值汇总 + 专用提示。"""
    result = assess(make_request([], scope_sku_ids=[9002]))

    assert result.analyzable is False
    assert result.window_days == 28
    assert result.total_skus == 0
    assert result.analyzable_skus == 0
    assert result.insufficient_skus == 0
    assert result.valid_days_avg == 0.0
    assert result.missing_days_total == 0
    assert result.outlier_count_total == 0
    assert result.per_sku == []
    assert result.warnings == ["未提供可分析的 SKU"]


def test_valid_days_avg_rounds_point_one_five_to_point_two() -> None:
    """R114：20 个 SKU、合计 3 个有效日 = 0.15，`ROUND_HALF_UP` 必须给 0.2（不是 0.1）。"""
    specs = [(9000 + index, [1.0] if index < 3 else []) for index in range(20)]

    result = assess(make_request(specs))

    assert sum(row.valid_days for row in result.per_sku) == 3
    assert result.valid_days_avg == 0.2
