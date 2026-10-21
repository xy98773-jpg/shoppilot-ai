"""LLM 上下文白名单（`PROMPT_DESIGN.md §7`）。

★ `§7.3` 实现要求：**白名单在单一模块维护**，禁止各 Prompt 自行拼字段。
本模块是唯一出口：所有进入 Prompt 的字段都必须经 `build_risk_context()`。

## 为什么要有白名单

`§7.1` 的核心结论：**价格/成本一律不发送**（核心商业机密、LLM 也不需要——
补货公式 `DESIGN_BASIS §10` 不含价格）；供应商联系人/电话属 PII，同样不发送。
白名单让「发送了什么」可枚举、可单测（`§7.3`：断言 Prompt 中**不出现**禁止字段）。

## 本模块的边界

只做**字段挑选与格式化**，不做网络、不做模板渲染（渲染在 `llm/prompts.py`）。
"""
from __future__ import annotations

from typing import Any

from shoppilot_ai.app.analytics.evaluate import ItemEvaluation

#: ★ 允许进入 Prompt 的字段（`§7.1`「允许发送」列 + `§2.1` 输入变量表）。
#:
#: ⚠️ 与 `PROMPT_DESIGN §2.1` 的偏差：`sku_code` / `sku_name` / `warehouse_name`
#: 在白名单里**列着但当前取不到**——`AI_SERVICE_CONTRACT §3.3` 裁剪原则下
#: Java 只传 `sku_id`（主数据名不在契约内）。故本实现用 `sku_id` / `warehouse_id`
#: 作标识，并已在模板文件头与 `DESIGN_REVIEW` 登记该偏差。
ALLOWED_FIELDS: frozenset[str] = frozenset(
    {
        # 标识（当前可得）
        "sku_id",
        "warehouse_id",
        # 风险判定（规则结果，用户不可写）
        "risk_level",
        "risk_types",
        "risk_findings",
        "data_quality",
        # 统计与计算值（系统计算，用户不可写）
        "avg_daily_sales",
        "on_hand_qty",
        "inventory_position",
        "lead_time_days",
        "lead_time_source",
        "coverage_days",
        "valid_days",
        "window_days",
    }
)

#: ★ 禁止字段名子串（`§7.1`「禁止发送」列的兜底拦截）。
#:
#: 即便将来有人往上下文里加了新字段，只要名字含这些子串就会被 `assert_no_forbidden`
#: 抓出来（单测断言）。这是**纵深防御**：白名单是主手段，子串匹配是兜底。
FORBIDDEN_SUBSTRINGS: tuple[str, ...] = (
    "price",  # purchase_price / sale_price / unit_price
    "cost",
    "amount",  # total_amount
    "contact",  # contact_name / contact_phone
    "phone",
    "email",
    "address",
    "password",
    "payment_terms",
)


class ContextError(Exception):
    """上下文含禁止字段（防御性；正常路径不应触发）。"""


def _fmt(value: object) -> str:
    """字段值 → Prompt 文本；`None` ⇒ `未提供`（`§2.1` SYSTEM 规则 3）。"""
    if value is None:
        return "未提供"
    return str(value)


def _format_findings(findings: list[Any]) -> str:
    """`risk_findings` 逐行文本（`§2.1` 渲染说明）。

    形如：`- R-STOCKOUT: inputs={on_hand_qty: 8.000}; threshold=...; result=HIGH`。
    """
    if not findings:
        return "（无命中明细）"
    lines: list[str] = []
    for finding in findings:
        rule = getattr(finding, "rule_code", None) or "UNKNOWN"
        inputs = getattr(finding, "inputs", None) or {}
        threshold = getattr(finding, "threshold", None)
        result = getattr(finding, "result", None)
        rendered = ", ".join(f"{k}: {v}" for k, v in dict(inputs).items())
        lines.append(
            f"- {rule}: inputs={{{rendered}}}; threshold={threshold}; result={result}"
        )
    return "\n".join(lines)


def build_risk_context(
    evaluation: ItemEvaluation,
    *,
    window_days: int,
) -> dict[str, str]:
    """构造 `explain_risk.v1` 的渲染上下文（**只含白名单字段**）。

    :param evaluation: 单 SKU 的确定性结果（LLM 只读，不改）
    :param window_days: 统计窗口 `N`（来自 `params`，非 item 字段）
    """
    context: dict[str, str] = {
        "sku_id": _fmt(evaluation.sku_id),
        "warehouse_id": _fmt(evaluation.warehouse_id),
        "risk_level": _fmt(evaluation.risk_level),
        "risk_types": ", ".join(evaluation.risk_types) or "（无）",
        "risk_findings": _format_findings(evaluation.risk_findings),
        "data_quality": _fmt(evaluation.data_quality),
        "avg_daily_sales": _fmt(evaluation.avg_daily_sales),
        "on_hand_qty": _fmt(evaluation.on_hand_qty),
        "inventory_position": _fmt(evaluation.inventory_position),
        "lead_time_days": _fmt(evaluation.lead_time_days),
        "lead_time_source": _fmt(evaluation.lead_time_source),
        "coverage_days": _fmt(evaluation.coverage_days),
        "valid_days": _fmt(evaluation.valid_days),
        "window_days": _fmt(window_days),
    }
    assert_no_forbidden(context)
    return context


def assert_no_forbidden(context: dict[str, str]) -> None:
    """断言上下文**不含**禁止字段（`§7.3`：单测断言点）。

    :raises ContextError: 命中禁止字段名或禁止值模式
    """
    for key in context:
        if key not in ALLOWED_FIELDS:
            raise ContextError(f"字段不在白名单：{key}")
        lowered = key.lower()
        for bad in FORBIDDEN_SUBSTRINGS:
            if bad in lowered:
                raise ContextError(f"字段含禁止内容：{key}")


__all__ = [
    "ALLOWED_FIELDS",
    "FORBIDDEN_SUBSTRINGS",
    "ContextError",
    "assert_no_forbidden",
    "build_risk_context",
]
