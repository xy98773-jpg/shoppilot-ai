"""逐 SKU 确定性组合器（M4-T09/T10 的编排层，纯函数）。

职责：把 B17 销量统计、B16 数据质量、六条风险规则、补货计算与 **run 级预算** 串成
一条 `ItemEvaluation`。供本批 golden-case 测试与 `B19` 的 pipeline 复用。

本模块**不**做：`summary` 汇总、`progress` 推进、端点、LLM 解释、落库、任何 IO。
这些属于 `M4-T11` / `B19`（`§5.30` 范围裁定）。

## 预算语义（`§5.30 #12`）

`params.budget_limit` 是 **本次运行的总量上限（不是金额）**，按请求 `items` 顺序
**顺序扣减**：前一个 SKU 用掉的额度会让后面 SKU 可用的额度变小。因此预算不能在
`rules/replenishment.py` 里按单 SKU 计算，必须在这里持有跨 item 的 `remaining`。

截断规则：`q > remaining` ⇒ `q = floor(remaining / step) × step`（保持整数倍，
`step = qty_step > 0 ? qty_step : 1`）、`truncated_by_budget=True`；截断为 0 时
`q_suggest="0.000"`（三态中的第二态）并追加 warning。

## 精度

全程 `Decimal`。唯一允许 `float` 的地方是调用 B16 的 `count_outliers`（其签名是
`list[float]`，且只用于**分类档位**，不参与任何输出数值的舍入）。
"""
from __future__ import annotations

from datetime import date
from decimal import ROUND_FLOOR, ROUND_HALF_UP, Decimal

from pydantic import BaseModel, ConfigDict

from shoppilot_ai.app.analytics.sales_stats import compute_sales_stats
from shoppilot_ai.app.rules.replenishment import compute_replenishment
from shoppilot_ai.app.rules.risk_rules import evaluate_risks
from shoppilot_ai.app.schemas.analysis_request import (
    Decimal3Str,
    Decimal4Str,
    Item,
    Params,
    SignedDecimal3Str,
)
from shoppilot_ai.app.schemas.analysis_response import (
    DataQuality,
    LeadTimeSourceValue,
    RiskFinding,
    RiskLevel,
    RiskType,
)
from shoppilot_ai.app.validation.data_quality import count_outliers, grade

_QUANT_3 = Decimal("0.001")
_ZERO = Decimal("0")
_ONE = Decimal("1")


class ItemEvaluation(BaseModel):
    """单 SKU 的确定性结果（`ResultItem` 的 25 个确定性字段，不含 `llm_*`）。

    `inventory_position` 用 `SignedDecimal3Str`：`IP` 允许为负（超卖）。
    该类型已上移到 `schemas/analysis_request.py`（`R118`），与 `ResultItem` 同源，
    因此 `ResultItem(**evaluation.model_dump())` 可直接组装（`B19` 的真实路径）。
    """

    model_config = ConfigDict(extra="ignore")

    sku_id: int
    warehouse_id: int
    avg_daily_sales: Decimal3Str | None = None
    sales_qty_total: Decimal3Str
    valid_days: int
    data_quality: DataQuality
    risk_level: RiskLevel
    risk_types: list[RiskType] = []
    risk_findings: list[RiskFinding] = []
    on_hand_qty: Decimal3Str
    effective_in_transit_qty: Decimal3Str
    committed_qty: Decimal3Str
    inventory_position: SignedDecimal3Str
    safety_stock: Decimal3Str | None = None
    lead_time_days: int | None = None
    lead_time_source: LeadTimeSourceValue = "DEFAULT"
    coverage_days: int | None = None
    cv: Decimal4Str | None = None
    volatility_adjusted: bool = False
    q_base: Decimal3Str | None = None
    moq_applied: Decimal3Str | None = None
    order_multiple_applied: Decimal3Str | None = None
    q_suggest: Decimal3Str | None = None
    truncated_by_budget: bool = False
    warnings: list[str] = []


def evaluate_items(
    items: list[Item],
    params: Params,
    *,
    warehouse_id: int,
    date_to: date,
) -> list[ItemEvaluation]:
    """按输入顺序逐 SKU 计算，并在 run 级顺序扣减预算。

    :param items: 请求中的 SKU 列表（顺序即预算扣减顺序）
    :param params: 运行参数（含 `budget_limit`）
    :param warehouse_id: 回显到每个 item 的仓库 id
    :param date_to: 分析窗口右端点（风险规则的自然日分桶基准）
    """
    remaining: Decimal | None = (
        Decimal(params.budget_limit) if params.budget_limit is not None else None
    )
    results: list[ItemEvaluation] = []

    for item in items:
        stats = compute_sales_stats(item, params)

        data_quality = grade(
            stats.valid_days,
            count_outliers([float(point.qty) for point in item.sales_series]),
            params.window_days,
            params.min_days,
        )

        risk = evaluate_risks(stats, item, params, date_to)
        replenishment = compute_replenishment(stats, item, params)

        warnings = list(risk.warnings)
        q_suggest = replenishment.q_suggest
        truncated_by_budget = False

        if q_suggest is not None:
            quantity = Decimal(q_suggest)
            if remaining is not None:
                if quantity > remaining:
                    step = _budget_step(item)
                    quantity = _floor_to(remaining, step)
                    truncated_by_budget = True
                    warnings.append(
                        "受预算限制，建议补货量已截断为 0"
                        if quantity == 0
                        else "受预算限制，建议补货量已截断"
                    )
                remaining -= quantity
            q_suggest = _fmt(quantity)

        results.append(
            ItemEvaluation(
                sku_id=int(item.sku_id or 0),
                warehouse_id=warehouse_id,
                avg_daily_sales=stats.avg_daily_sales,
                sales_qty_total=stats.sales_qty_total,
                valid_days=stats.valid_days,
                data_quality=data_quality,
                risk_level=risk.risk_level,
                risk_types=list(risk.risk_types),
                risk_findings=list(risk.risk_findings),
                on_hand_qty=_fmt(Decimal(item.on_hand_qty)),
                effective_in_transit_qty=_fmt(Decimal(item.effective_in_transit_qty)),
                committed_qty=_fmt(Decimal(item.committed_qty)),
                inventory_position=replenishment.inventory_position,
                safety_stock=replenishment.safety_stock,
                lead_time_days=replenishment.lead_time_days,
                lead_time_source=replenishment.lead_time_source,
                coverage_days=replenishment.coverage_days,
                cv=stats.cv,
                volatility_adjusted=replenishment.volatility_adjusted,
                q_base=replenishment.q_base,
                moq_applied=replenishment.moq_applied,
                order_multiple_applied=replenishment.order_multiple_applied,
                q_suggest=q_suggest,
                truncated_by_budget=truncated_by_budget,
                warnings=warnings,
            )
        )

    return results


def _budget_step(item: Item) -> Decimal:
    """预算截断的向下取整步长（`§5.30 #12`：`qty_step`，缺省 1）。"""
    if item.qty_step is None:
        return _ONE
    step = Decimal(item.qty_step)
    return step if step > 0 else _ONE


def _floor_to(value: Decimal, step: Decimal) -> Decimal:
    """向下取整到 `step` 的整数倍（预算截断；与补货的 `ceil` 方向相反）。"""
    return (value / step).to_integral_value(rounding=ROUND_FLOOR) * step


def _fmt(value: Decimal) -> str:
    if value == 0:
        value = _ZERO
    return format(value.quantize(_QUANT_3, rounding=ROUND_HALF_UP), "f")


__all__ = ["ItemEvaluation", "evaluate_items"]
