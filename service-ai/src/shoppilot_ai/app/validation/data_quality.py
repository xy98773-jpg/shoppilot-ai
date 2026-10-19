"""数据质量分级（M4-T07）。

权威口径：`ANALYSIS_CONTRACT.md §4.1`（判定规则）与 `§4.2`（各级影响/warnings 文案）。
阈值与算法**不得自行调整**——`§5.27` 已裁：`0.8 / 0.5`、`5% / 15%` 取自 `§4.1`，
改动须先改文档。

## 计算口径（逐条对应任务书 §C2，勿"优化"）

- `valid_days` = `sales_series` 的**记录条数**：契约 `§1.2` 明确「只包含有记录的日子，
  缺失日不补 0」，所以条数即有效天数，**不**对日期做补齐/去重。
- `missing_days` = `params.window_days − valid_days`，下限 0。
  `window_days` 一律取 `params.window_days`，**不**按 `date_to − date_from + 1` 重算
  （窗口是用户声明的统计口径，不是请求区间的物理长度）。
- 异常值：`qty` **严格**超出 `mean ± ANOMALY_SIGMA × std` 的条数；`len < 2` 或 `std == 0`
  ⇒ 0（单点/恒定序列没有"离群"概念）。
- 标准差统一用**总体标准差**（`statistics.pstdev`）——B17 的 `cv` 必须同口径，
  否则「预检说 GOOD、规则说高波动」会互相矛盾。
- 质量分档：先按 `valid_days < min_days` 判 `INSUFFICIENT`（**覆盖其它档**）；
  否则「天数比例档」与「异常值比例档」**各算一档、取更差者**（GOOD < FAIR < POOR）。

## 汇总

`analyzable` = 至少一个 SKU 非 `INSUFFICIENT`（`§3.1`：全不可分析时 Java 置 run `FAILED`
→ `ANL-7008`）。`valid_days_avg` 仅展示用，按 `ROUND_HALF_UP` 保留 1 位小数。
`warnings` 为中文汇总文案，按「INSUFFICIENT → POOR → FAIR」的严重度顺序拼接
（首句与 `§3.1` 示例逐字一致）。
"""
from __future__ import annotations

import statistics
from decimal import ROUND_HALF_UP, Decimal

from shoppilot_ai.app.schemas.analysis_request import AnalyzeRequest
from shoppilot_ai.app.schemas.analysis_response import PerSkuQuality, ValidateData

#: 异常值阈值倍数（`DESIGN_BASIS §10.6` 的 `ANOMALY_SIGMA`，默认 2.0）。
ANOMALY_SIGMA = 2.0

#: 天数比例阈值（`ANALYSIS_CONTRACT §4.1`）。
DAY_GOOD_RATIO = 0.8
DAY_FAIR_RATIO = 0.5

#: 异常值比例阈值（`ANALYSIS_CONTRACT §4.1`）。
OUTLIER_GOOD_RATIO = 0.05
OUTLIER_FAIR_RATIO = 0.15

GOOD = "GOOD"
FAIR = "FAIR"
POOR = "POOR"
INSUFFICIENT = "INSUFFICIENT"

#: 档位序（数字越大越差），用于「取更差者」。
_GRADE_ORDER: dict[str, int] = {GOOD: 0, FAIR: 1, POOR: 2}


def assess(request: AnalyzeRequest) -> ValidateData:
    """对一次分析请求做数据质量分级，返回 `AI_SERVICE_CONTRACT §3.1` 的 `data` 结构。"""
    window_days = request.params.window_days
    min_days = request.params.min_days

    per_sku: list[PerSkuQuality] = []
    for item in request.items:
        # `AnalyzeRequest` 已保证 `sku_id` 非空且 >0；这里只做类型收敛
        sku_id = int(item.sku_id or 0)
        quantities = [float(point.qty) for point in item.sales_series]
        valid_days = len(quantities)
        missing_days = max(0, window_days - valid_days)
        outlier_count = count_outliers(quantities)
        per_sku.append(
            PerSkuQuality(
                sku_id=sku_id,
                valid_days=valid_days,
                missing_days=missing_days,
                outlier_count=outlier_count,
                quality=grade(valid_days, outlier_count, window_days, min_days),
            )
        )

    total_skus = len(per_sku)
    if total_skus == 0:
        # ★ R113（B16.1）：空 items 合法（Java `toAiRequest` 在逐 SKU 装配落地前恒传空数组）。
        #   返回零值汇总 + 专用中文提示，由端点/Java 决定业务语义（`analyzable=false` ⇒
        #   Java 侧 `ANL-7008`）。此分支**不得**复用其它档位文案。
        return ValidateData(
            analyzable=False,
            window_days=window_days,
            total_skus=0,
            analyzable_skus=0,
            insufficient_skus=0,
            valid_days_avg=0.0,
            missing_days_total=0,
            outlier_count_total=0,
            per_sku=[],
            warnings=["未提供可分析的 SKU"],
        )

    insufficient_skus = sum(1 for row in per_sku if row.quality == INSUFFICIENT)
    analyzable_skus = total_skus - insufficient_skus

    return ValidateData(
        analyzable=analyzable_skus > 0,
        window_days=window_days,
        total_skus=total_skus,
        analyzable_skus=analyzable_skus,
        insufficient_skus=insufficient_skus,
        valid_days_avg=_average_valid_days(per_sku),
        missing_days_total=sum(row.missing_days for row in per_sku),
        outlier_count_total=sum(row.outlier_count for row in per_sku),
        per_sku=per_sku,
        warnings=_warnings(per_sku),
    )


def count_outliers(quantities: list[float]) -> int:
    """`qty` 严格超出 `mean ± ANOMALY_SIGMA × pstdev` 的条数（`len<2` 或 `std=0` ⇒ 0）。"""
    if len(quantities) < 2:
        return 0
    mean = statistics.fmean(quantities)
    std = statistics.pstdev(quantities)
    if std == 0:
        return 0
    low = mean - ANOMALY_SIGMA * std
    high = mean + ANOMALY_SIGMA * std
    return sum(1 for value in quantities if value < low or value > high)


def grade(valid_days: int, outlier_count: int, window_days: int, min_days: int) -> str:
    """按 `§4.1` 分档：`INSUFFICIENT` 优先，否则「天数档 / 异常值档」取更差者。"""
    if valid_days < min_days:
        return INSUFFICIENT

    day_ratio = valid_days / window_days if window_days else 0.0
    day_grade = _grade_by_ratio(
        day_ratio, DAY_GOOD_RATIO, DAY_FAIR_RATIO, higher_is_better=True
    )

    outlier_ratio = outlier_count / valid_days if valid_days else 0.0
    outlier_grade = _grade_by_ratio(
        outlier_ratio, OUTLIER_GOOD_RATIO, OUTLIER_FAIR_RATIO, higher_is_better=False
    )

    return day_grade if _GRADE_ORDER[day_grade] >= _GRADE_ORDER[outlier_grade] else outlier_grade


def _grade_by_ratio(
    ratio: float,
    good_threshold: float,
    fair_threshold: float,
    *,
    higher_is_better: bool,
) -> str:
    """按单一维度分档。

    :param higher_is_better: 天数比例用 ``True``（≥阈值更好）；异常值比例用 ``False``
        （≤阈值更好）。两个维度的比较方向相反，必须显式区分，否则会把「异常值 20%」
        误判成 GOOD。
    """
    if higher_is_better:
        if ratio >= good_threshold:
            return GOOD
        if ratio >= fair_threshold:
            return FAIR
        return POOR

    if ratio <= good_threshold:
        return GOOD
    if ratio <= fair_threshold:
        return FAIR
    return POOR


def _average_valid_days(per_sku: list[PerSkuQuality]) -> float:
    """平均有效天数，`ROUND_HALF_UP` 保留 1 位（`§3.1` 允许 1 位小数）。

    ★ R114（B16.1）：必须**先在 Decimal 域做除法**再 `quantize`。
    B16 的写法 `Decimal(total / len(...))` 会先把除法落成 float：`3 / 20 == 0.15` 在二进制里
    是 `0.1499999…`，`ROUND_HALF_UP` 于是给出 `0.1`（应为 `0.2`）。
    这是「构造 Decimal 的时机」错误，不是舍入模式错误。
    """
    if not per_sku:
        return 0.0
    total = sum(row.valid_days for row in per_sku)
    average = Decimal(total) / Decimal(len(per_sku))
    return float(average.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def _warnings(per_sku: list[PerSkuQuality]) -> list[str]:
    """按 `§4.2` 文案生成中文汇总提示，严重度从高到低。"""
    warnings: list[str] = []
    insufficient = sum(1 for row in per_sku if row.quality == INSUFFICIENT)
    if insufficient:
        warnings.append(
            f"{insufficient} 个 SKU 有效销售天数不足，将标记 DATA_INSUFFICIENT 且不产出补货建议"
        )
    poor = sum(1 for row in per_sku if row.quality == POOR)
    if poor:
        warnings.append(f"{poor} 个 SKU 数据质量较差，建议人工复核")
    fair = sum(1 for row in per_sku if row.quality == FAIR)
    if fair:
        warnings.append(f"{fair} 个 SKU 数据覆盖一般")
    return warnings


__all__ = [
    "ANOMALY_SIGMA",
    "DAY_FAIR_RATIO",
    "DAY_GOOD_RATIO",
    "OUTLIER_FAIR_RATIO",
    "OUTLIER_GOOD_RATIO",
    "assess",
    "count_outliers",
    "grade",
]
