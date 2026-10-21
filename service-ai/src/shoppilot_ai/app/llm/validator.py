"""LLM 输出校验（`PROMPT_DESIGN.md §6`）。

## 三道检查

| 检查 | 出处 | 失败后果 |
| --- | --- | --- |
| **结构/Schema**：合法 JSON + 字段齐全 + 长度 50–800 + `evidence` 白名单 | `§6.1` | `VALIDATION_FAILED` |
| **数字溯源**（启发式，防编造） | `§6.3` | **软失败**：记 `warnings`，**不**丢解释 |
| **一致性**（解释 vs 结构化结论矛盾） | `§6.4` | `VALIDATION_FAILED` |

### ★ 为什么数字溯源是「软失败」（D-31）

`§6.3` 明说溯源是**启发式**，会误判（如"约 80 件"的粗略表述、序号、年份）。
`§10` 的 D-31 建议取**软失败**：首次命中→重试 1 次；仍命中→记 `warnings`。
这里再退一步：**只记 warning，不丢弃解释**——本批每个 SKU 只有一次调用预算
（没有「重试 1 次」的第二轮），硬失败会让解释可用率显著下降；而编造数字的
**真正防线**是「前端以结构化结果为主、解释为辅」（`§5.3`）。该取舍已登记
`DESIGN_REVIEW`。

### 一致性检查为什么是硬失败

`risk_level=HIGH` 却说"无风险/安全"，属**解释与结构化结论直接矛盾**，
比"多一个数字"严重得多——留着会误导运营。故按 `§6.4` 硬失败。

## 边界

只做校验，**不**修改结构化结果（解释永远不改 `risk_level` / `q_suggest`，`§5`）。
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

#: `explanation` 长度区间（`§6.1`）。`run_summary.summary` 是 80–1000（首期未启用）。
EXPLANATION_MIN_LEN = 50
EXPLANATION_MAX_LEN = 800

#: 数字 token（含小数、负数）。
_NUMBER_PATTERN = re.compile(r"-?\d+(?:\.\d+)?")

#: ★ 与结构化结论矛盾的危险表述（`§6.4`）。
#:
#: ⚠️ **不要**在这里放裸词「安全」「充足」——它们是本领域的**正常术语**
#: （「安全阈值」「安全库存」「库存安全水位」），放进去会把大量正常解释
#: 误判为矛盾而丢弃（实测：`低于安全阈值` 就被裸词命中）。
#: 故只保留**语义完整**的否定/安抚短语，宁可漏判也不可误伤
#: （漏判的代价＝留一段措辞欠妥的解释；误伤的代价＝丢掉整段有效解释）。
_CONTRADICTION_PATTERNS: tuple[str, ...] = (
    "无风险",
    "没有风险",
    "不存在风险",
    "风险较低，无需",
    "可以放心",
    "无需补货",
    "不需要补货",
    "无需处理",
    "库存充足",
)

#: 不必溯源的「通用数字」：年份、以及 0/1/100 这类常规计数/百分比。
#: ★ 这是启发式的**误判豁免**，不是放宽校验——目的只是避免把"2026 年"判成编造。
_GENERIC_NUMBERS: frozenset[str] = frozenset({"0", "1", "2", "3", "100", "2025", "2026"})


class LlmExplanation(BaseModel):
    """`§6.1` 的输出 Schema（`explain_risk.v1` / `explain_replenishment.v1` 同构）。"""

    model_config = ConfigDict(extra="ignore")

    explanation: str = Field(
        min_length=EXPLANATION_MIN_LEN, max_length=EXPLANATION_MAX_LEN
    )
    evidence: list[str] = Field(min_length=1)
    confidence: Literal["HIGH", "MEDIUM", "LOW"]
    uncertainties: list[str] = []
    checklist: list[str] = []


@dataclass(frozen=True)
class ValidationResult:
    """校验产物。

    :param ok: 是否通过（不通过 ⇒ 调用方置 `VALIDATION_FAILED` 且丢弃解释）
    :param explanation: 通过时为解释文本；否则 `None`
    :param evidence: `evidence`（已剔除非法项）
    :param warnings: 软失败警告（**不**影响 `ok`）
    :param reason: 失败原因（`ok=False` 时非空）
    """

    ok: bool
    explanation: str | None = None
    evidence: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    reason: str | None = None


class OutputValidationError(Exception):
    """输出无法解析为 JSON（调用方按 `VALIDATION_FAILED` 处理）。"""


def strip_code_fence(text: str) -> str:
    """剥离 ```json ... ``` 代码块围栏（模型常这么包）。

    ★ 只剥**首尾**围栏；正文中间的 ``` 不动（那可能是内容的一部分）。
    """
    stripped = text.strip()
    if not stripped.startswith("```"):
        return stripped
    first_newline = stripped.find("\n")
    if first_newline == -1:
        return stripped
    body = stripped[first_newline + 1 :]
    if body.rstrip().endswith("```"):
        body = body.rstrip()[: -3]
    return body.strip()


def extract_numbers(text: str) -> set[str]:
    """提取文本中的数字 token 字符串集合（溯源用）。"""
    return set(_NUMBER_PATTERN.findall(text))


def _numbers_of_context(context: dict[str, Any]) -> set[str]:
    """输入上下文中出现过的所有数字（溯源白名单）。"""
    allowed: set[str] = set()
    for value in context.values():
        allowed |= extract_numbers(str(value))
    return allowed


def validate_output(
    raw_text: str,
    *,
    context: dict[str, Any] | None = None,
    risk_level: str | None = None,
) -> ValidationResult:
    """校验模型输出。

    :param raw_text: 模型返回的 `content`
    :param context: 本次调用的渲染上下文（用于 `evidence` 白名单与数字溯源）
    :param risk_level: 结构化结论的风险等级（用于一致性检查）
    """
    ctx = context or {}
    warnings: list[str] = []

    # ---------- 1) 解析 JSON ----------
    try:
        payload = json.loads(strip_code_fence(raw_text))
    except (json.JSONDecodeError, ValueError) as exc:
        raise OutputValidationError(f"输出不是合法 JSON：{exc}") from exc
    if not isinstance(payload, dict):
        raise OutputValidationError("输出不是 JSON 对象")

    # ---------- 2) Schema 校验 ----------
    try:
        parsed = LlmExplanation.model_validate(payload)
    except ValidationError as exc:
        return ValidationResult(ok=False, reason=f"Schema 校验失败：{_short(exc)}")

    # ---------- 3) evidence 白名单（§6.1 / §6.4）----------
    allowed_fields = set(ctx.keys())
    evidence = parsed.evidence
    if allowed_fields:
        illegal = [e for e in evidence if e not in allowed_fields]
        if illegal:
            warnings.append(f"evidence 引用了非输入字段，已剔除：{illegal}")
            evidence = [e for e in evidence if e in allowed_fields]
        if not evidence:
            return ValidationResult(
                ok=False, warnings=warnings, reason="evidence 全部非法（无有效引用）"
            )

    # ---------- 4) 数字溯源（软失败，D-31）----------
    if ctx:
        input_numbers = _numbers_of_context(ctx)
        suspicious = sorted(
            n
            for n in extract_numbers(parsed.explanation)
            if n not in input_numbers and n not in _GENERIC_NUMBERS
        )
        if suspicious:
            warnings.append(
                f"解释中出现输入里没有的数字（疑似编造，请人工留意）：{suspicious}"
            )

    # ---------- 5) 一致性检查（硬失败，§6.4）----------
    if risk_level in {"HIGH", "MEDIUM"}:
        hit = [p for p in _CONTRADICTION_PATTERNS if p in parsed.explanation]
        if hit:
            return ValidationResult(
                ok=False,
                warnings=warnings,
                reason=f"解释与结构化结论矛盾（risk_level={risk_level} 却出现 {hit}）",
            )

    return ValidationResult(
        ok=True,
        explanation=parsed.explanation,
        evidence=evidence,
        warnings=warnings,
    )


def _short(exc: ValidationError) -> str:
    """把 Pydantic 错误压成一行（**不**回显模型输出全文，防敏感内容进日志）。"""
    try:
        errors = exc.errors()
        first = errors[0] if errors else {}
        return f"{first.get('loc')} {first.get('msg')}"
    except Exception:  # noqa: BLE001 —— 兜底，校验器自身不得再抛
        return "字段校验失败"


__all__ = [
    "EXPLANATION_MAX_LEN",
    "EXPLANATION_MIN_LEN",
    "LlmExplanation",
    "OutputValidationError",
    "ValidationResult",
    "extract_numbers",
    "strip_code_fence",
    "validate_output",
]
