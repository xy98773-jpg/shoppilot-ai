"""销量统计（M4-T08）：`D` / `CV` / 3σ 截尾。

权威来源：
- `DESIGN_BASIS.md §10.1` —— 截尾移动平均：`n` 含 0 销量记录；`n < MIN_DAYS` ⇒ `D=null`；
  `D = round(截尾后均值, 3)`；`CV = std/mean`（`mean=0` 时 `null`）；
- `ANALYSIS_CONTRACT.md §8` —— 精度：`D` 3 位、`cv` 4 位、`ROUND_HALF_UP`、禁 `float`；
- `ANALYSIS_CONTRACT.md §7.2` —— GC-01 / GC-09 / GC-10 / GC-17（本模块的 golden cases）。

★ **与 B16 数据质量的口径差异（禁止互相复用）**：

============  ==========================  ==============================
模块          阈值                        用途
============  ==========================  ==============================
B16 `validation/data_quality.py`  **2σ**（`ANOMALY_SIGMA=2.0`）  判「异常值比例」，影响 GOOD/FAIR/POOR
本模块        **3σ**（`TRIM_SIGMA=3`）    剔除极端日，影响 `D` 的取值
============  ==========================  ==============================

两者目的不同：2σ 是**质量分级**（宁可敏感），3σ 是**计算截尾**（宁可保守）。
把 2σ 的结果拿来截尾，或把 3σ 的比例拿去分级，都会与 SSOT 不符。

★ **精度纪律（B16 `R114` 教训）**：全程 `Decimal`。任何 `float` 参与最终舍入都会把
`0.15` 变成 `0.149999…`，`ROUND_HALF_UP` 随即给出错误结果。

本模块为**纯函数**：无 IO、无全局状态、不读写数据库/文件、不发网络请求。
"""
from __future__ import annotations

import statistics
from collections import Counter
from decimal import ROUND_HALF_UP, Decimal

from pydantic import BaseModel, ConfigDict

from shoppilot_ai.app.core.errors import AI_1001, ServiceAIBaseError
from shoppilot_ai.app.schemas.analysis_request import (
    Decimal3Str,
    Decimal4Str,
    Item,
    Params,
)

#: 3σ 截尾倍数（`DESIGN_BASIS §10.1`）。**不是** B16 的 `ANOMALY_SIGMA=2.0`。
TRIM_SIGMA = Decimal("3")

#: 单次最多剔除的天数（`DESIGN_BASIS §10.1`：「最多截尾 2 天」）。
MAX_TRUNCATED = 2

_QUANT_3 = Decimal("0.001")
_QUANT_4 = Decimal("0.0001")
_ZERO = Decimal("0")


class SalesStats(BaseModel):
    """单 SKU 的销量统计结果（`D` 相关字段，`ANALYSIS_CONTRACT §2.1`）。

    字段类型用 `Decimal3Str` / `Decimal4Str`（字符串），与响应契约「数量/比率用字符串传输」
    一致；`None` 表示「数据不足 / 无法计算」，**不是** 0。
    """

    model_config = ConfigDict(extra="ignore")

    sku_id: int
    valid_days: int
    sales_qty_total: Decimal3Str
    avg_daily_sales: Decimal3Str | None = None
    cv: Decimal4Str | None = None
    truncated_count: int = 0
    insufficient: bool = False


def compute_sales_stats(item: Item, params: Params) -> SalesStats:
    """计算单 SKU 的 `D` / `CV` / 截尾条数（`DESIGN_BASIS §10.1` 全流程）。

    :param item: 单个 SKU 的输入（`sales_series` 已由 B16 Schema 校验格式）
    :param params: 运行参数（用 `window_days` 做上限校验、`min_days` 判数据不足）
    :raises ServiceAIBaseError: `AI-1001`（`sales_series` 条数 > `window_days`）
    """
    quantities = [Decimal(point.qty) for point in item.sales_series]
    sku_id = int(item.sku_id or 0)
    valid_days = len(quantities)

    if valid_days > params.window_days:
        # `ANALYSIS_CONTRACT §1.3`：单 SKU 的 sales_series 不得超过 window_days 条
        raise ServiceAIBaseError(
            code=AI_1001,
            detail={
                "reason": "sales_series 条数不得超过 window_days",
                "sku_id": sku_id,
                "series_length": valid_days,
                "window_days": params.window_days,
            },
        )

    total_raw = sum(quantities, _ZERO)
    total_text = _format(total_raw, _QUANT_3)

    if valid_days < params.min_days:
        # `§10.1` 第 2 步：数据不足只影响 `D`/`cv`，**原始总和照常给出**（它是事实，不是估计）
        return SalesStats(
            sku_id=sku_id,
            valid_days=valid_days,
            sales_qty_total=total_text,
            avg_daily_sales=None,
            cv=None,
            truncated_count=0,
            insufficient=True,
        )

    mean_raw = total_raw / Decimal(valid_days)
    std_raw = _pstdev(quantities)
    kept, truncated_count = trim_3sigma(quantities, mean_raw, std_raw)

    kept_mean = sum(kept, _ZERO) / Decimal(len(kept))
    avg_text = _format(kept_mean, _QUANT_3)
    cv_text = None if kept_mean == 0 else _format(_pstdev(kept) / kept_mean, _QUANT_4)

    return SalesStats(
        sku_id=sku_id,
        valid_days=valid_days,
        sales_qty_total=total_text,
        avg_daily_sales=avg_text,
        cv=cv_text,
        truncated_count=truncated_count,
        insufficient=False,
    )


def trim_3sigma(
    quantities: list[Decimal],
    mean: Decimal,
    std: Decimal,
    *,
    max_truncated: int = MAX_TRUNCATED,
) -> tuple[list[Decimal], int]:
    """3σ 截尾：剔除严格超出 `mean ± 3σ` 的记录，最多 `max_truncated` 条。

    - **严格比较**：`qty == mean ± 3σ` 的记录**不**剔除（浮点世界里「边界」最容易出歧义，
      这里用 Decimal 精确比较，边界值行为是确定的）；
    - `std == 0`：无离群概念，返回原集合；
    - 多条候选按 `|qty - mean|` **从大到小**排序，只取前 `max_truncated` 条；
    - 返回值顺序保持输入顺序（供 `cv`/`D` 使用；顺序不影响均值，但便于调试）。

    :return: `(保留集合, 实际剔除条数)`
    """
    if std == 0 or not quantities:
        return list(quantities), 0

    lower = mean - TRIM_SIGMA * std
    upper = mean + TRIM_SIGMA * std
    candidates = [value for value in quantities if value < lower or value > upper]
    if not candidates:
        return list(quantities), 0

    candidates.sort(key=lambda value: abs(value - mean), reverse=True)
    dropped = candidates[:max_truncated]

    remaining = Counter(dropped)
    kept: list[Decimal] = []
    for value in quantities:
        if remaining.get(value, 0) > 0:
            remaining[value] -= 1
        else:
            kept.append(value)
    return kept, len(dropped)


def _pstdev(values: list[Decimal]) -> Decimal:
    """总体标准差（`statistics.pstdev`，与 B16 同口径）。

    `statistics.pstdev` 对 `Decimal` 输入返回 `Decimal`（已验证）；
    防御性地处理「未来某版本退化为 float」的情况——转 `Decimal(str(x))` 而不是
    `Decimal(float)`，避免把二进制尾巴带进来。
    """
    if not values:
        return _ZERO
    result = statistics.pstdev(values)
    return result if isinstance(result, Decimal) else Decimal(str(result))


def _format(value: Decimal, quantum: Decimal) -> str:
    """`ROUND_HALF_UP` 量化后转定点字符串（`6.250` / `0.0000`，不带科学计数法）。"""
    return format(value.quantize(quantum, rounding=ROUND_HALF_UP), "f")


__all__ = [
    "MAX_TRUNCATED",
    "SalesStats",
    "TRIM_SIGMA",
    "compute_sales_stats",
    "trim_3sigma",
]
