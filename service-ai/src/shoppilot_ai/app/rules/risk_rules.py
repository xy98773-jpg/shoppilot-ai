"""库存风险规则（M4-T09）：六条确定性规则 + 等级合成。

权威来源（按 SSOT 优先级）：
- `DESIGN_BASIS.md §11` —— 六条规则的判定逻辑与 `§11.1` 等级合成；
- `DESIGN_REVIEW.md §5.30` —— **本批的 15 条口径裁定，优先于 `ANALYSIS_CONTRACT §7.2` 的示例字面**
  （示例有四处自相矛盾，见 `R116`）；
- `ANALYSIS_CONTRACT.md §5.1` —— `risk_findings` 四字段（`rule_code`/`inputs`/`threshold`/`result`）
  与「`inputs` 必须回显实际参与计算的值」；
- `ANALYSIS_CONTRACT.md §5.2` —— `risk_types` / `risk_level` 语义。

## 与「跳过 / 未命中」有关的硬口径（`§5.30 #1/#2/#4/#5/#6/#8`）

1. **前置条件不满足 ⇒ 不输出该规则 `finding`**，只在 `warnings` 写中文原因；
   前置条件满足 ⇒ **必须输出** `finding`：命中给等级，未命中给 `NOT_TRIGGERED`。
2. `D == 0`（数据充足但窗口零销量）⇒ `R-STOCKOUT`/`R-LOWSTOCK`/`R-OVERSTOCK` 因 `D > 0`
   前置不满足而**跳过 + 各一条 warning**；`R-SLOWMOVING` 照常判定。
3. 只有 `D is None` 才输出 `R-DATAINSUFFICIENT`，且此时 `risk_level="UNKNOWN"`、
   `risk_types=["DATA_INSUFFICIENT"]`，其余五条不输出（`§5.30 #13`）。
4. `R-SALESANOMALY` 的「近 7 天」按**自然日**分桶、缺失日补 0，不是「最后 7 条记录」；
   分母固定 7 / 21。

## 与其他批次的边界

- 本模块**不**做补货计算（`rules/replenishment.py`）、不写库、不调 LLM、不发网络请求；
- 阈值常量全部是本模块的**判定阈值**，与 B16 `data_quality.ANOMALY_SIGMA`（质量分级用的 2σ）
  和 B17 `sales_stats.TRIM_SIGMA`（截尾用的 3σ）**互不复用**。
"""
from __future__ import annotations

import statistics
from collections import defaultdict
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal

from pydantic import BaseModel, ConfigDict

from shoppilot_ai.app.analytics.sales_stats import SalesStats
from shoppilot_ai.app.rules.replenishment import resolve_lead_time
from shoppilot_ai.app.schemas.analysis_request import Item, Params
from shoppilot_ai.app.schemas.analysis_response import RiskFinding, RiskLevel, RiskType

#: 缺货 2 天警戒线（`DESIGN_BASIS §11`：`on_hand/D <= 2` ⇒ `HIGH`）。
STOCKOUT_DAYS = Decimal("2")

#: 过高库存判定（`DESIGN_BASIS §10.6` 的 `OVERSTOCK_DAYS`）。
OVERSTOCK_DAYS = Decimal("90")

#: 过高库存 HIGH 阈值（`2 × OVERSTOCK_DAYS`）。
OVERSTOCK_HIGH_DAYS = OVERSTOCK_DAYS * Decimal("2")

#: 滞销判定窗口（`DESIGN_BASIS §10.6` 的 `SLOW_MOVING_DAYS`）。
#: 实际观察窗口 = `min(30, window_days)`（`§5.30 #5` / `R117`）。
SLOW_MOVING_DAYS = 30

#: 销量异常的 σ 倍数（`DESIGN_BASIS §11`：`ANOMALY_SIGMA = 2.0`）。
#: ★ 与 B16 `data_quality.ANOMALY_SIGMA` 数值相同但用途不同：此处判「近期 vs 基线」的偏移，
#: 那里判「单点是否离群」。两者**不得互相复用**。
ANOMALY_SIGMA = Decimal("2.0")

#: 销量异常的最小变化率（`DESIGN_BASIS §11`：「变化率 > 50%」）。
ANOMALY_CHANGE_RATE = Decimal("0.5")

#: 销量异常所需的最小窗口（近 7 + 基线 21 = 28 天）。
ANOMALY_WINDOW_DAYS = 28
RECENT_DAYS = 7
BASELINE_DAYS = 21

_QUANT_2 = Decimal("0.01")
_QUANT_3 = Decimal("0.001")
_ZERO = Decimal("0")

#: 等级序（`§11.1`：`risk_level = MAX(命中等级)`；`UNKNOWN` 不是 MAX 的产物）。
_LEVEL_ORDER: dict[str, int] = {"LOW": 1, "MEDIUM": 2, "HIGH": 3}

#: `risk_types` 的固定顺序（`§5.30 #3`）。
_TYPE_ORDER: tuple[tuple[str, str], ...] = (
    ("R-STOCKOUT", "STOCKOUT"),
    ("R-LOWSTOCK", "LOW_STOCK"),
    ("R-OVERSTOCK", "OVERSTOCK"),
    ("R-SLOWMOVING", "SLOW_MOVING"),
    ("R-SALESANOMALY", "SALES_ANOMALY"),
    ("R-DATAINSUFFICIENT", "DATA_INSUFFICIENT"),
)


class RiskEvaluation(BaseModel):
    """单 SKU 的风险判定结果（`ANALYSIS_CONTRACT §2.1` 的三个字段 + `warnings`）。"""

    model_config = ConfigDict(extra="ignore")

    risk_level: RiskLevel
    risk_types: list[RiskType] = []
    risk_findings: list[RiskFinding] = []
    warnings: list[str] = []


def evaluate_risks(
    stats: SalesStats,
    item: Item,
    params: Params,
    date_to: date,
) -> RiskEvaluation:
    """按 `DESIGN_BASIS §11` 逐条判定六规则并合成等级（纯函数）。

    :param stats: B17 的销量统计结果（`D` / `cv` / `valid_days`）
    :param item: 单 SKU 输入（库存快照、交期、上架日、销售流水）
    :param params: 运行参数（`min_days` / `window_days` / `coverage_days` / `lead_time_default`）
    :param date_to: 分析窗口的右端点（`R-SALESANOMALY` 的自然日分桶基准）
    """
    on_hand = Decimal(item.on_hand_qty)
    inventory_position = (
        on_hand + Decimal(item.effective_in_transit_qty) - Decimal(item.committed_qty)
    )
    lead_time, _ = resolve_lead_time(item, params)
    coverage = params.coverage_days

    d_text = stats.avg_daily_sales
    findings: list[RiskFinding] = []
    warnings: list[str] = []

    # —— 前置：数据不足（唯一输出 R-DATAINSUFFICIENT 的分支） ——
    if d_text is None:
        findings.append(
            RiskFinding(
                rule_code="R-DATAINSUFFICIENT",
                inputs={
                    "valid_days": stats.valid_days,
                    "min_days": params.min_days,
                },
                threshold=f"valid_days({stats.valid_days}) < MIN_DAYS({params.min_days})",
                result="UNKNOWN",
            )
        )
        warnings.append(
            f"有效销售天数 {stats.valid_days} 天，少于最少 {params.min_days} 天，"
            "数据不足，未生成补货建议"
        )
        return RiskEvaluation(
            risk_level="UNKNOWN",
            risk_types=["DATA_INSUFFICIENT"],
            risk_findings=findings,
            warnings=warnings,
        )

    d = Decimal(d_text)

    # —— 1) R-STOCKOUT（前置 D > 0） ——
    if d > 0:
        findings.append(_stockout(on_hand, d, lead_time))
    else:
        warnings.append("D=0（窗口内无销量），R-STOCKOUT 缺货规则不适用，已跳过")

    # —— 2) R-LOWSTOCK（前置 D > 0） ——
    if d > 0:
        findings.append(_lowstock(inventory_position, d, lead_time, coverage))
    else:
        warnings.append("D=0（窗口内无销量），R-LOWSTOCK 低库存规则不适用，已跳过")

    # —— 3) R-OVERSTOCK（前置 D > 0） ——
    if d > 0:
        findings.append(_overstock(on_hand, d))
    else:
        warnings.append("D=0（窗口内无销量），R-OVERSTOCK 高库存规则不适用，已跳过")

    # —— 4) R-SLOWMOVING（前置 D 非 None；新品豁免；窗口不足 30 天仍判定） ——
    slow_moving, slow_warnings = _slowmoving(item, params, date_to, on_hand)
    if slow_moving is not None:
        findings.append(slow_moving)
    warnings.extend(slow_warnings)

    # —— 5) R-SALESANOMALY（前置：窗口 >= 28 天 且 基线桶记录数 >= min_days） ——
    anomaly, anomaly_warnings = _sales_anomaly(item, params, date_to)
    if anomaly is not None:
        findings.append(anomaly)
    warnings.extend(anomaly_warnings)

    # —— 合成（§11.1 / §5.30 #3） ——
    triggered = [finding.result for finding in findings if finding.result in _LEVEL_ORDER]
    risk_level: str = "NONE"
    if triggered:
        risk_level = max(triggered, key=lambda level: _LEVEL_ORDER[level])

    triggered_codes = {
        finding.rule_code for finding in findings if finding.result in _LEVEL_ORDER
    }
    risk_types = [
        risk_type for rule_code, risk_type in _TYPE_ORDER if rule_code in triggered_codes
    ]

    return RiskEvaluation(
        risk_level=risk_level,  # type: ignore[arg-type]
        risk_types=risk_types,  # type: ignore[arg-type]
        risk_findings=findings,
        warnings=warnings,
    )


# --------------------------------------------------------------------------- #
# 单规则实现
# --------------------------------------------------------------------------- #
def _stockout(on_hand: Decimal, d: Decimal, lead_time: int) -> RiskFinding:
    """`R-STOCKOUT`：零库存 / `on_hand/D <= 2` 天 ⇒ HIGH；`<= L` ⇒ MEDIUM。

    ★ 判定用「乘法比较」（`on_hand <= D * 阈值`）而非「除法比值」：
    `Decimal` 除法遇到除不尽的 `D` 会截断到 28 位有效数字，边界值可能翻转。
    """
    ratio_text = _ratio(on_hand, d)
    inputs = {"on_hand_qty": _fmt(on_hand), "D": _fmt(d), "L": lead_time}

    if on_hand <= 0:
        return RiskFinding(
            rule_code="R-STOCKOUT",
            inputs=inputs,
            threshold=f"on_hand_qty({_fmt(on_hand)}) <= 0，零库存",
            result="HIGH",
        )
    if on_hand <= d * STOCKOUT_DAYS:
        return RiskFinding(
            rule_code="R-STOCKOUT",
            inputs=inputs,
            threshold=(
                f"on_hand_qty({_fmt(on_hand)}) / D({_fmt(d)}) = {ratio_text} 天 "
                "<= STOCKOUT_DAYS(2)"
            ),
            result="HIGH",
        )
    if on_hand <= d * Decimal(lead_time):
        return RiskFinding(
            rule_code="R-STOCKOUT",
            inputs=inputs,
            threshold=(
                f"on_hand_qty({_fmt(on_hand)}) / D({_fmt(d)}) = {ratio_text} 天 "
                f"<= L({lead_time})"
            ),
            result="MEDIUM",
        )
    return RiskFinding(
        rule_code="R-STOCKOUT",
        inputs=inputs,
        threshold=(
            f"on_hand_qty({_fmt(on_hand)}) / D({_fmt(d)}) = {ratio_text} 天 "
            f"> L({lead_time})"
        ),
        result="NOT_TRIGGERED",
    )


def _lowstock(
    inventory_position: Decimal,
    d: Decimal,
    lead_time: int,
    coverage: int,
) -> RiskFinding:
    """`R-LOWSTOCK`：`IP < D × (L + R)` ⇒ MEDIUM（与 `R-STOCKOUT` 可共存）。"""
    target = d * Decimal(lead_time + coverage)
    inputs = {
        "IP": _fmt(inventory_position),
        "D": _fmt(d),
        "L": lead_time,
        "R": coverage,
    }
    if inventory_position < target:
        return RiskFinding(
            rule_code="R-LOWSTOCK",
            inputs=inputs,
            threshold=(
                f"IP({_fmt(inventory_position)}) < D({_fmt(d)}) * "
                f"(L({lead_time}) + R({coverage})) = {_fmt(target)}"
            ),
            result="MEDIUM",
        )
    return RiskFinding(
        rule_code="R-LOWSTOCK",
        inputs=inputs,
        threshold=(
            f"IP({_fmt(inventory_position)}) >= D({_fmt(d)}) * "
            f"(L({lead_time}) + R({coverage})) = {_fmt(target)}"
        ),
        result="NOT_TRIGGERED",
    )


def _overstock(on_hand: Decimal, d: Decimal) -> RiskFinding:
    """`R-OVERSTOCK`：`on_hand/D > 180` ⇒ HIGH；`> 90` ⇒ MEDIUM。"""
    ratio_text = _ratio(on_hand, d)
    inputs = {"on_hand_qty": _fmt(on_hand), "D": _fmt(d)}

    if on_hand > d * OVERSTOCK_HIGH_DAYS:
        return RiskFinding(
            rule_code="R-OVERSTOCK",
            inputs=inputs,
            threshold=(
                f"on_hand_qty({_fmt(on_hand)}) / D({_fmt(d)}) = {ratio_text} 天 "
                "> 2 * OVERSTOCK_DAYS(180)"
            ),
            result="HIGH",
        )
    if on_hand > d * OVERSTOCK_DAYS:
        return RiskFinding(
            rule_code="R-OVERSTOCK",
            inputs=inputs,
            threshold=(
                f"on_hand_qty({_fmt(on_hand)}) / D({_fmt(d)}) = {ratio_text} 天 "
                "> OVERSTOCK_DAYS(90)"
            ),
            result="MEDIUM",
        )
    return RiskFinding(
        rule_code="R-OVERSTOCK",
        inputs=inputs,
        threshold=(
            f"on_hand_qty({_fmt(on_hand)}) / D({_fmt(d)}) = {ratio_text} 天 "
            "<= OVERSTOCK_DAYS(90)"
        ),
        result="NOT_TRIGGERED",
    )


def _slowmoving(
    item: Item,
    params: Params,
    date_to: date,
    on_hand: Decimal,
) -> tuple[RiskFinding | None, list[str]]:
    """`R-SLOWMOVING`：最后 `min(30, window_days)` 天总销量为 0 且 `on_hand > 0` ⇒ MEDIUM。

    :return: `(finding 或 None, warnings)`；`None` 表示**跳过**（新品豁免），此时写明原因。
    """
    observed = min(SLOW_MOVING_DAYS, params.window_days)
    warnings: list[str] = []

    if item.listed_date is not None:
        listed_days = (date_to - item.listed_date).days + 1
        if listed_days < observed:
            warnings.append(
                f"上架仅 {listed_days} 天（< 观察窗口 {observed} 天），"
                "新品不判定滞销，R-SLOWMOVING 已跳过"
            )
            return None, warnings

    if observed < SLOW_MOVING_DAYS:
        warnings.append(
            f"可用窗口仅 {observed} 天（< {SLOW_MOVING_DAYS} 天），"
            f"滞销判定按 {observed} 天窗口执行"
        )

    start = date_to - timedelta(days=observed - 1)
    total = sum(
        (Decimal(point.qty) for point in item.sales_series if start <= point.date <= date_to),
        _ZERO,
    )

    scope_text = (
        f"近 {observed} 天（可用窗口 {observed} 天（< {SLOW_MOVING_DAYS}））"
        if observed < SLOW_MOVING_DAYS
        else f"近 {observed} 天"
    )
    threshold = f"{scope_text}总销量 = 0 且 on_hand_qty > 0"

    inputs = {
        "last_observed_sales_qty": _fmt(total),
        "on_hand_qty": _fmt(on_hand),
        "observed_days": observed,
    }

    if total == 0 and on_hand > 0:
        return (
            RiskFinding(
                rule_code="R-SLOWMOVING",
                inputs=inputs,
                threshold=threshold,
                result="MEDIUM",
            ),
            warnings,
        )
    return (
        RiskFinding(
            rule_code="R-SLOWMOVING",
            inputs=inputs,
            threshold=threshold,
            result="NOT_TRIGGERED",
        ),
        warnings,
    )


def _sales_anomaly(
    item: Item,
    params: Params,
    date_to: date,
) -> tuple[RiskFinding | None, list[str]]:
    """`R-SALESANOMALY`：近 7 天日均 vs 前 21 天日均，按自然日补 0 分桶。

    :return: `(finding 或 None, warnings)`；`None` 表示**跳过**（窗口或基线数据不足）。
    """
    warnings: list[str] = []

    if params.window_days < ANOMALY_WINDOW_DAYS:
        warnings.append(
            f"窗口仅 {params.window_days} 天（< {ANOMALY_WINDOW_DAYS} 天），"
            "无法比较近 7 天与前 21 天，R-SALESANOMALY 已跳过"
        )
        return None, warnings

    recent_start = date_to - timedelta(days=RECENT_DAYS - 1)
    baseline_start = date_to - timedelta(days=ANOMALY_WINDOW_DAYS - 1)
    baseline_end = date_to - timedelta(days=RECENT_DAYS)

    baseline_records = [
        point for point in item.sales_series if baseline_start <= point.date <= baseline_end
    ]
    if len(baseline_records) < params.min_days:
        warnings.append(
            f"基线窗口（前 {BASELINE_DAYS} 天）仅 {len(baseline_records)} 条销售记录，"
            f"少于 {params.min_days} 条，R-SALESANOMALY 已跳过"
        )
        return None, warnings

    # 自然日分桶：同一天多条记录求和（正常数据一天一条，这里做防御）
    buckets: dict[date, Decimal] = defaultdict(lambda: _ZERO)
    for point in item.sales_series:
        buckets[point.date] += Decimal(point.qty)

    recent_values = [
        buckets.get(recent_start + timedelta(days=offset), _ZERO)
        for offset in range(RECENT_DAYS)
    ]
    baseline_values = [
        buckets.get(baseline_start + timedelta(days=offset), _ZERO)
        for offset in range(BASELINE_DAYS)
    ]

    recent_avg = sum(recent_values, _ZERO) / Decimal(RECENT_DAYS)
    baseline_avg = sum(baseline_values, _ZERO) / Decimal(BASELINE_DAYS)
    baseline_std = _pstdev(baseline_values)
    delta = abs(recent_avg - baseline_avg)

    if baseline_avg > 0:
        change_rate: Decimal | None = delta / baseline_avg
        rate_text = _fmt(change_rate)
        rate_hit = change_rate > ANOMALY_CHANGE_RATE
    elif recent_avg > 0:
        # 基线为 0、近期有量：变化率视为 ∞（`§5.30 #8`），可命中
        change_rate = None
        rate_text = "∞"
        rate_hit = True
    else:
        change_rate = _ZERO
        rate_text = _fmt(_ZERO)
        rate_hit = False

    sigma_limit = ANOMALY_SIGMA * baseline_std
    hit = delta > sigma_limit and rate_hit

    inputs = {
        "last7_avg": _fmt(recent_avg),
        "prev21_avg": _fmt(baseline_avg),
        "prev21_std": _fmt(baseline_std),
        "change_rate": rate_text,
    }
    threshold = (
        f"|Δ|({_fmt(delta)}) > ANOMALY_SIGMA(2.0) * std({_fmt(baseline_std)}) "
        f"= {_fmt(sigma_limit)} 且变化率({rate_text}) > 50%"
    )

    return (
        RiskFinding(
            rule_code="R-SALESANOMALY",
            inputs=inputs,
            threshold=threshold,
            result="LOW" if hit else "NOT_TRIGGERED",
        ),
        warnings,
    )


# --------------------------------------------------------------------------- #
# 数值工具（只用于展示与回显；判定一律用未舍入值或乘法比较）
# --------------------------------------------------------------------------- #
def _pstdev(values: list[Decimal]) -> Decimal:
    """总体标准差（`statistics.pstdev`，B16/B17 同口径）。"""
    if not values:
        return _ZERO
    result = statistics.pstdev(values)
    return result if isinstance(result, Decimal) else Decimal(str(result))


def _fmt(value: Decimal, quantum: Decimal = _QUANT_3) -> str:
    """`ROUND_HALF_UP` 量化后转定点字符串；`-0.000` 归一为 `0.000`。"""
    if value == 0:
        value = _ZERO
    return format(value.quantize(quantum, rounding=ROUND_HALF_UP), "f")


def _ratio(numerator: Decimal, denominator: Decimal) -> str:
    """展示用比值（2 位小数）。判定不用它，避免除法截断影响边界。"""
    if denominator == 0:
        return "∞"
    return _fmt(numerator / denominator, _QUANT_2)


__all__ = [
    "ANOMALY_SIGMA",
    "ANOMALY_WINDOW_DAYS",
    "BASELINE_DAYS",
    "OVERSTOCK_DAYS",
    "RECENT_DAYS",
    "RiskEvaluation",
    "SLOW_MOVING_DAYS",
    "STOCKOUT_DAYS",
    "evaluate_risks",
]
