"""补货量计算（M4-T10）：`IP` / `SS` / `Q_base` / MOQ / 倍数 / 步长。

权威来源：
- `DESIGN_BASIS.md §10.2` —— `Q_base = max(0, D × (L + R) + SS − IP)`，`IP = on_hand + eff_in_transit − committed`；
- `DESIGN_BASIS.md §10.3` —— `SS = D × SS_FACTOR`，`SS_FACTOR = ceil(L × SAFETY_RATIO)`，
  `CV > CV_HIGH` 时上浮 50%，`safety_stock_qty` 可直接覆盖（绝对量优先）；
- `DESIGN_BASIS.md §10.5` —— `Q_base <= 0 ⇒ Q_suggest = null`（`q_suggest` 三态），
  MOQ / 采购倍数 / 最小起订精度的取整；
- `DESIGN_REVIEW.md §5.30` —— 15 条裁定（本批最高优先级）：#9 `SS` 用 `ceil`、#10 `Q_base<=0 ⇒ null`、
  #11 `MOQ`/倍数/步长、#13 数据不足字段集、#14 `L` 与来源、#15 精度。

## 精度纪律

全程 `Decimal`：`float` 只允许出现在「B16 质量分级的异常值计数」这类**分类**场景，
不得参与任何最终舍入（`R114` 教训：`Decimal(total / n)` 会先把除法落成二进制浮点）。
本模块的 `ceil` 用 `ROUND_CEILING`、格式化用 `ROUND_HALF_UP`。

## 本模块不处理的

预算截断（`params.budget_limit`）是 **run 级顺序扣减**，需要跨 item 状态，
由 `analytics/evaluate.py` 统一处理；本模块只回答「不考虑预算时的建议量」。
"""
from __future__ import annotations

from decimal import ROUND_CEILING, ROUND_HALF_UP, Decimal
from typing import Annotated

from pydantic import BaseModel, ConfigDict, StringConstraints

from shoppilot_ai.app.analytics.sales_stats import SalesStats
from shoppilot_ai.app.schemas.analysis_request import Decimal3Str, Item, Params
from shoppilot_ai.app.schemas.analysis_response import LeadTimeSourceValue

#: 带符号的 3 位小数字符串。
#:
#: ★ 为什么不复用 `Decimal3Str`：`IP = on_hand + eff_in_transit − committed` **允许为负**
#: （超卖/已承诺大于在手+在途，`§5.30` 补货口径 B2 明示；`ANALYSIS_CONTRACT §2.1` 对
#: `inventory_position` 只要求「与三项一致」，**未**要求 `>= 0`）。
#: 而 `schemas.analysis_response.Decimal3Str` 的正则不含负号——这是 B16 Schema 的收窄，
#: 本批按 G6 不改 `schemas/`，故在规则层声明带符号类型并在交付汇报中申报，待项目负责人裁定。
SignedDecimal3Str = Annotated[
    str, StringConstraints(pattern=r"^-?(0|[1-9][0-9]*)(\.[0-9]{1,3})?$")
]

_QUANT_3 = Decimal("0.001")
_ZERO = Decimal("0")
_ONE = Decimal("1")
_ONE_AND_HALF = Decimal("1.5")


class Replenishment(BaseModel):
    """单 SKU 的补货计算结果（`ANALYSIS_CONTRACT §2.1` 的 `SS`~`q_suggest` 字段）。

    所有数量字段都是 3 位小数字符串；`None` 表示「数据不足 / 无建议」，**不是** 0。
    """

    model_config = ConfigDict(extra="ignore")

    inventory_position: SignedDecimal3Str
    safety_stock: Decimal3Str | None = None
    lead_time_days: int
    lead_time_source: LeadTimeSourceValue
    coverage_days: int
    volatility_adjusted: bool = False
    q_base: Decimal3Str | None = None
    moq_applied: Decimal3Str | None = None
    order_multiple_applied: Decimal3Str | None = None
    q_suggest: Decimal3Str | None = None
    truncated_by_budget: bool = False


def compute_replenishment(
    stats: SalesStats,
    item: Item,
    params: Params,
) -> Replenishment:
    """计算 `IP` / `SS` / `Q_base` / `Q_suggest`（不含预算截断）。

    :param stats: B17 的销量统计结果（`D` / `cv`）
    :param item: 单 SKU 输入（库存快照、交期、MOQ/倍数/步长、安全库存覆盖）
    :param params: 运行参数（`coverage_days` / `ss_ratio` / `cv_high` / `lead_time_default`）
    """
    on_hand = Decimal(item.on_hand_qty)
    inventory_position = (
        on_hand + Decimal(item.effective_in_transit_qty) - Decimal(item.committed_qty)
    )
    lead_time, lead_time_source = resolve_lead_time(item, params)
    coverage = params.coverage_days

    common = {
        "inventory_position": _fmt(inventory_position),
        "lead_time_days": lead_time,
        "lead_time_source": lead_time_source,
        "coverage_days": coverage,
    }

    d_text = stats.avg_daily_sales
    if d_text is None:
        # `§5.30 #13`：数据不足 ⇒ SS/Q_base/MOQ/倍数/q_suggest 全 null，
        # 但 IP / L / R 仍按事实回显（它们是事实，不是估计）。
        return Replenishment(**common)

    d = Decimal(d_text)

    # —— SS（§10.3） ——
    factor = _ceil_int(Decimal(lead_time) * Decimal(params.ss_ratio))
    volatility_adjusted = False
    if stats.cv is not None and Decimal(stats.cv) > Decimal(params.cv_high):
        factor *= _ONE_AND_HALF
        volatility_adjusted = True

    if item.safety_stock_override is not None:
        # 绝对量优先；覆盖时不再上浮（`§5.30` B2 明示 `volatility_adjusted=False`）
        safety_stock = Decimal(item.safety_stock_override)
        volatility_adjusted = False
    else:
        safety_stock = d * factor
    safety_stock = _quantize3(safety_stock)

    # —— Q_base（§10.2） ——
    q_base = max(_ZERO, d * Decimal(lead_time + coverage) + safety_stock - inventory_position)
    q_base = _quantize3(q_base)

    if q_base <= 0:
        # `q_suggest` 三态（`DESIGN_BASIS §10.5`）：null = 无建议；"0.000" = 被预算截断为 0。
        # 这里没有任何建议产出，故 MOQ / 倍数也一并留空（"生效" 的前提是存在建议量）。
        return Replenishment(
            safety_stock=_fmt(safety_stock),
            q_base=_fmt(q_base),
            volatility_adjusted=volatility_adjusted,
            **common,
        )

    # —— MOQ / 倍数 / 步长（§10.5 + `§5.30 #11`） ——
    moq = Decimal(item.moq) if item.moq is not None else _ONE
    if moq <= 0:
        moq = _ONE
    base_qty = max(q_base, moq)

    multiple_text: str | None = None
    if item.order_multiple is not None and Decimal(item.order_multiple) > 1:
        multiple = Decimal(item.order_multiple)
        q_suggest = _ceil_to(base_qty, multiple)
        multiple_text = _fmt(multiple)
    else:
        step = Decimal(item.qty_step) if item.qty_step is not None else _ONE
        if step <= 0:
            step = _ONE
        q_suggest = _ceil_to(base_qty, step)

    return Replenishment(
        safety_stock=_fmt(safety_stock),
        q_base=_fmt(q_base),
        moq_applied=_fmt(moq),
        order_multiple_applied=multiple_text,
        q_suggest=_fmt(_quantize3(q_suggest)),
        volatility_adjusted=volatility_adjusted,
        **common,
    )


def resolve_lead_time(item: Item, params: Params) -> tuple[int, str]:
    """解析采用的交期 `L` 与其来源（`§5.30 #14`）。

    - `item.lead_time_days` 为空 ⇒ `params.lead_time_default`，来源恒 `"DEFAULT"`
      （此时忽略 `item.lead_time_source`，否则会出现「值来自默认、来源却写 POLICY」）；
    - 非空 ⇒ 回显 item 值；`lead_time_source` 为空时按 `"DEFAULT"` 兜底。
    """
    if item.lead_time_days is None:
        return params.lead_time_default, "DEFAULT"
    return item.lead_time_days, (item.lead_time_source or "DEFAULT")


def _ceil_int(value: Decimal) -> Decimal:
    """向上取整到整数（`§10.3` 的 `SS_FACTOR = ceil(L × SAFETY_RATIO)`）。"""
    return value.to_integral_value(rounding=ROUND_CEILING)


def _ceil_to(value: Decimal, step: Decimal) -> Decimal:
    """向上取整到 `step` 的整数倍（`§10.5`：倍数 / 步长）。"""
    return (value / step).to_integral_value(rounding=ROUND_CEILING) * step


def _quantize3(value: Decimal) -> Decimal:
    return value.quantize(_QUANT_3, rounding=ROUND_HALF_UP)


def _fmt(value: Decimal) -> str:
    """3 位定点字符串；`-0.000` 归一为 `0.000`。"""
    if value == 0:
        value = _ZERO
    return format(_quantize3(value), "f")


__all__ = [
    "Replenishment",
    "SignedDecimal3Str",
    "compute_replenishment",
    "resolve_lead_time",
]
