"""M4-T08 销量统计测试：`D` / `CV` / 3σ 截尾（`DESIGN_BASIS §10.1`）。

★ 本文件替换 B13 的骨架占位（原 `test_sales_stats_placeholder` 恒 skip）——按 `B17` 令
G6 的例外条款，本批实现的正是该模块，因此占位测试被真实用例取代（skip 数因此 -1）。

覆盖：
- golden cases `GC-01 / GC-09 / GC-10 / GC-17`（`ANALYSIS_CONTRACT §7.2`）；
- 边界：最多剔 2 条、3σ 严格边界（z == 3.0 不剔）、零销量记录计入 `n`、
  `mean=0 ⇒ cv=null`、`std=0`、超窗口 `AI-1001`、`ROUND_HALF_UP` 舍入。

★ 所有数值断言用**字符串**比较（`"6.250"`）：用 float 近似会把 `6.2505 → 6.251`
这类舍入方向问题掩盖掉。
"""
from __future__ import annotations

import statistics
from datetime import date, timedelta
from decimal import Decimal

import pytest

from shoppilot_ai.app.analytics.sales_stats import (
    MAX_TRUNCATED,
    TRIM_SIGMA,
    compute_sales_stats,
    trim_3sigma,
)
from shoppilot_ai.app.core.errors import AI_1001, ServiceAIBaseError
from shoppilot_ai.app.schemas.analysis_request import Item, Params

START = date(2026, 1, 1)


def make_item(sku_id: int, quantities: list[float]) -> Item:
    return Item.model_validate(
        {
            "sku_id": sku_id,
            "on_hand_qty": "0.000",
            "committed_qty": "0.000",
            "effective_in_transit_qty": "0.000",
            "sales_series": [
                {
                    "date": (START + timedelta(days=index)).isoformat(),
                    "qty": f"{value:.3f}",
                }
                for index, value in enumerate(quantities)
            ],
        }
    )


def make_params(window_days: int = 28, min_days: int = 7) -> Params:
    return Params.model_validate(
        {"window_days": window_days, "min_days": min_days, "enable_llm": False}
    )


def stats_for(quantities: list[float], *, window_days: int = 28, min_days: int = 7):
    return compute_sales_stats(
        make_item(9002, quantities), make_params(window_days=window_days, min_days=min_days)
    )


# --------------------------------------------------------------------------- #
# 常量（防止有人把 3σ 改成 B16 的 2σ）
# --------------------------------------------------------------------------- #
def test_sigma_constants_are_distinct_from_data_quality() -> None:
    assert TRIM_SIGMA == 3
    assert MAX_TRUNCATED == 2


# --------------------------------------------------------------------------- #
# Golden cases
# --------------------------------------------------------------------------- #
def test_gc01_normal_hot_seller() -> None:
    """GC-01：28 天 = 21×6 + 7×7（合计 175），无极端值 ⇒ D=6.250。"""
    stats = stats_for([6.0] * 21 + [7.0] * 7)

    assert stats.valid_days == 28
    assert stats.sales_qty_total == "175.000"
    assert stats.avg_daily_sales == "6.250"
    assert stats.truncated_count == 0
    assert stats.insufficient is False
    assert stats.cv is not None


def test_gc09_insufficient_data() -> None:
    """GC-09：6 条记录、min_days=7 ⇒ insufficient，D/cv 均为 null，原始总和照常。"""
    stats = stats_for([5.0] * 6)

    assert stats.valid_days == 6
    assert stats.sales_qty_total == "30.000"
    assert stats.avg_daily_sales is None
    assert stats.cv is None
    assert stats.truncated_count == 0
    assert stats.insufficient is True


def test_gc10_no_sales_data() -> None:
    """GC-10：空序列 ⇒ 同 GC-09，且 total 为 0.000。"""
    stats = stats_for([])

    assert stats.valid_days == 0
    assert stats.sales_qty_total == "0.000"
    assert stats.avg_daily_sales is None
    assert stats.cv is None
    assert stats.truncated_count == 0
    assert stats.insufficient is True


def test_gc17_trim_single_outlier() -> None:
    """GC-17：27×5 + 1×100 ⇒ 剔除 1 条，D=5.000，cv=0.0000。"""
    stats = stats_for([5.0] * 27 + [100.0])

    assert stats.valid_days == 28
    assert stats.sales_qty_total == "235.000"
    assert stats.truncated_count == 1
    assert stats.avg_daily_sales == "5.000"
    assert stats.cv == "0.0000"


# --------------------------------------------------------------------------- #
# 边界
# --------------------------------------------------------------------------- #
def test_at_most_two_records_are_trimmed() -> None:
    """3 个极端日只剔 2 条（`|qty-mean|` 最大的两条），第 3 条仍参与均值。

    用 100 天窗口构造：97×5 + 3×100。3σ 下三个 100 都是候选（上界约 56.5），
    但 `MAX_TRUNCATED=2` ⇒ 保留 1 个 100。
    """
    stats = stats_for([5.0] * 97 + [100.0] * 3, window_days=100)

    assert stats.valid_days == 100
    assert stats.truncated_count == 2
    # 截尾后 97×5 + 1×100 = 585，/98 = 5.96938… → 5.969
    assert stats.avg_daily_sales == "5.969"


def test_sigma_boundary_is_strict() -> None:
    """9×1 + 1×101 的 z 恰好为 3.0（mean=11、std=30、上界=101）⇒ 不剔除。"""
    stats = stats_for([1.0] * 9 + [101.0], min_days=7)

    assert stats.truncated_count == 0
    assert stats.avg_daily_sales == "11.000"


def test_zero_quantity_rows_count_towards_valid_days() -> None:
    """零销量记录计入 n（`§10.1` 第 1 步）：21×0 + 7×1 ⇒ n=28、D=0.250。"""
    stats = stats_for([0.0] * 21 + [1.0] * 7)

    assert stats.valid_days == 28
    assert stats.truncated_count == 0
    assert stats.avg_daily_sales == "0.250"
    assert stats.sales_qty_total == "7.000"


def test_zero_mean_yields_null_cv() -> None:
    """全 0 序列：D=0.000，cv=null（`mean=0` 时 CV 无定义）。"""
    stats = stats_for([0.0] * 28)

    assert stats.avg_daily_sales == "0.000"
    assert stats.cv is None
    assert stats.truncated_count == 0
    assert stats.insufficient is False


def test_constant_series_has_zero_std_and_zero_cv() -> None:
    """恒定序列：std=0 ⇒ 不截尾；cv=0.0000。"""
    stats = stats_for([5.0] * 28)

    assert stats.avg_daily_sales == "5.000"
    assert stats.cv == "0.0000"
    assert stats.truncated_count == 0


def test_series_longer_than_window_is_rejected() -> None:
    """29 条 + window_days=28 ⇒ AI-1001（`ANALYSIS_CONTRACT §1.3`）。"""
    with pytest.raises(ServiceAIBaseError) as info:
        stats_for([1.0] * 29, window_days=28)

    assert info.value.code == AI_1001
    assert info.value.http_status == 400
    assert "不得超过" in info.value.detail["reason"]
    assert info.value.detail["series_length"] == 29
    assert info.value.detail["window_days"] == 28


def test_rounding_is_half_up_not_bankers() -> None:
    """14×6.250 + 14×6.251（均值 6.2505）⇒ 6.251（HALF_UP；银行家舍入会给 6.250）。"""
    stats = stats_for([6.250] * 14 + [6.251] * 14)

    assert stats.avg_daily_sales == "6.251"
    assert stats.truncated_count == 0


# --------------------------------------------------------------------------- #
# 截尾函数单元测试
# --------------------------------------------------------------------------- #
def test_trim_3sigma_returns_original_when_std_is_zero() -> None:
    values = [Decimal("5.000")] * 28

    kept, count = trim_3sigma(values, Decimal("5.000"), Decimal("0"))

    assert count == 0
    assert kept == values


def test_trim_3sigma_keeps_input_order() -> None:
    # 12 条中 1 个极端值：z = sqrt(11) ≈ 3.32 > 3 ⇒ 必被剔除（3 条样本时 z 只有 1.41，剔不动）
    values = (
        [Decimal("1.000"), Decimal("1.000"), Decimal("100.000")]
        + [Decimal("1.000")] * 9
    )
    mean = sum(values, Decimal("0")) / Decimal(len(values))

    kept, count = trim_3sigma(values, mean, statistics.pstdev(values))

    assert count == 1
    assert kept == [Decimal("1.000")] * 11
