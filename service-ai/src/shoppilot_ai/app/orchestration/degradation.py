"""LLM 降级判定（M4-T11）：纯函数，只判状态、不改结果。

权威口径：`ERROR_CODES.md §2.11`（`AI-5001~5004` ⇒ 「**不影响 run**」，
Java 侧映射 `llm_status ∈ {FAILED, TIMEOUT, VALIDATION_FAILED}` → run `DEGRADED`）
+ `DESIGN_REVIEW.md §5.32` 裁定 #7。

## ★ 不变量（`DESIGN_BASIS.md §12`）

**任何降级情形下，规则结果必须保留。** LLM 只是解释器：它失败、超时或输出非法，
都只影响 `llm_explanation` / `llm_status`，**不得**影响 `D`、`IP`、`SS`、`Q_base`、
`Q_suggest`、`risk_level`、`risk_types`。因此本模块只提供两个纯函数：

- `needs_degradation(llm_status)`：判定 run 是否应标 `DEGRADED`（由 Java 侧写入 `analysis_runs`）；
- `summarize_llm_status(statuses)`：把逐 item 的 `llm_status` 汇总成 run 级取值。

本模块**不做**任何兜底数据填充、**不**重试、**不**吞异常——重试与降级落地由 `B20`
（LLM 客户端）与 Java 侧的 run 状态机负责。

## 严重度序（`summarize_llm_status` 取「最严重者」的依据）

`VALIDATION_FAILED < FAILED < TIMEOUT`：校验失败说明「模型给了格式错误的内容」，
失败说明「调用本身没成」，超时说明「连失败都拿不到」——后者最需要人工介入，
故排最后。若某次 LLM 改动引入更细的分级，只需改这一个字典。
"""
from __future__ import annotations

from typing import get_args

from shoppilot_ai.app.schemas.analysis_response import LlmStatus

#: 需要降级的三个取值（`§5.32 #7`；其余 4 值不降级）。
DEGRADED_STATUSES: frozenset[str] = frozenset(
    {"FAILED", "TIMEOUT", "VALIDATION_FAILED"}
)

#: 合法的 `llm_status` 全集（复用 schemas 的 Literal，不新造枚举值）。
KNOWN_STATUSES: frozenset[str] = frozenset(get_args(LlmStatus))

#: run 级默认取值：本服务（M4 不接 LLM）不会产出解释。
SKIPPED = "SKIPPED"

#: 严重度序，数字越大越严重（仅用于「取最严重者」）。
_SEVERITY: dict[str, int] = {
    "VALIDATION_FAILED": 1,
    "FAILED": 2,
    "TIMEOUT": 3,
}


def needs_degradation(llm_status: str) -> bool:
    """`llm_status ∈ {FAILED, TIMEOUT, VALIDATION_FAILED}` ⇒ 需要降级。

    ★ 未知取值（未来新增枚举 / 拼写错误）**同样**返回 `True`：保守方向是「宁可让
    Java 标 `DEGRADED`（结果照常可用），也不要把失败当成功展示」。
    """
    return llm_status in DEGRADED_STATUSES or llm_status not in KNOWN_STATUSES


def summarize_llm_status(item_statuses: list[str] | None) -> str:
    """把逐 item 的 `llm_status` 汇总成 run 级取值。

    - 空列表 / `None` ⇒ `SKIPPED`；
    - 全部 `SKIPPED` ⇒ `SKIPPED`；
    - 存在失败态 ⇒ 取**最严重**的那个；
    - 其余组合 ⇒ `SKIPPED`（★ **永不返回 `SUCCEEDED`**：M4 不接 LLM，
      没有解释产出就不谎报成功；M5 接入真实 narrative 后由调用方决定 run 级取值）。
    """
    if not item_statuses:
        return SKIPPED
    failures = [status for status in item_statuses if needs_degradation(status)]
    if failures:
        return max(failures, key=lambda status: _SEVERITY.get(status, 0))
    return SKIPPED


__all__ = [
    "DEGRADED_STATUSES",
    "KNOWN_STATUSES",
    "SKIPPED",
    "needs_degradation",
    "summarize_llm_status",
]
