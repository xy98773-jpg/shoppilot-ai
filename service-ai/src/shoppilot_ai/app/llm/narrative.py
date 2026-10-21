"""LLM 解释器（narrative）—— `orchestration.pipeline.Narrative` 的真实实现（M8-T07 / `R155`）。

## 签名（与 `pipeline.Narrative` 一致，勿改）

```
(items: list[ItemEvaluation], request: AnalyzeRequest) -> (llm_status, {sku_id: explanation})
```

## ★ 铁律③：LLM 只解释，失败绝不拖垮规则结果

- 本函数**只读** `ItemEvaluation`，**从不修改**任何结构化字段
  （`risk_level` / `q_suggest` / `q_base` 一律原样，`PROMPT_DESIGN §5`）；
- 任何异常都在本函数内收敛为 `llm_status`（`FAILED` / `TIMEOUT` / `VALIDATION_FAILED`），
  **不向外冒泡**——`pipeline._run_narrative` 还有一层兜底，双保险；
- 部分成功也算 `SUCCEEDED`（只要拿到 ≥1 条解释），失败的 SKU 只是没有解释，
  **规则结果照常返回**（`PROMPT_DESIGN §9` 贯穿原则）。

## 调用策略（首期）

`PROMPT_DESIGN §2.1` 的 `explain_risk.v1` 是**单 SKU** Prompt（输出单个对象），
故这里是**逐 SKU 串行调用**。为控成本与延迟：

1. **只解释有风险的 SKU**（`HIGH` > `MEDIUM` > `LOW`；`NONE`/`UNKNOWN` 不解释）；
2. **上限 `LLM_MAX_ITEMS`**（默认 20）；
3. **连续失败 3 次 ⇒ 提前中止**（首个 SKU 就超时时，不把预算耗在必然失败的重试上）。

★ 串行是**首期取舍**：`MAX_ANALYSIS_SKUS=2000` 全量解释既不经济也无必要
（运营真正要看的是有风险的 SKU）。若将来要提吞吐，应改并行 + 总超时预算，
并同步改 `PROMPT_DESIGN`（Prompt 形态与并发模型是耦合决策）。
"""
from __future__ import annotations

import logging

from shoppilot_ai.app.analytics.evaluate import ItemEvaluation
from shoppilot_ai.app.core.config import settings
from shoppilot_ai.app.llm.client import (
    LlmError,
    LlmTimeout,
    complete,
)
from shoppilot_ai.app.llm.context_builder import build_risk_context
from shoppilot_ai.app.llm.prompts.loader import PromptError, get_template
from shoppilot_ai.app.llm.validator import (
    OutputValidationError,
    validate_output,
)
from shoppilot_ai.app.schemas.analysis_request import AnalyzeRequest

logger = logging.getLogger(__name__)

#: 只解释这些风险等级（按优先级排序；`NONE`/`UNKNOWN` 不解释——没有可解释的结论）。
_RISK_ORDER: dict[str, int] = {"HIGH": 0, "MEDIUM": 1, "LOW": 2}

#: 连续失败多少次后提前中止剩余 SKU（防「首个就超时」把整条 run 拖到超时）。
MAX_CONSECUTIVE_FAILURES = 3


def select_candidates(
    items: list[ItemEvaluation], max_items: int
) -> list[ItemEvaluation]:
    """挑选待解释的 SKU：有风险优先（HIGH→MEDIUM→LOW），同级按 `sku_id` 升序。

    ★ 排序**稳定且确定**：同一输入必得同一子集，便于复现与审计。
    """
    candidates = [it for it in items if it.risk_level in _RISK_ORDER]
    candidates.sort(key=lambda it: (_RISK_ORDER[it.risk_level], it.sku_id))
    return candidates[:max_items]


def llm_narrative(
    items: list[ItemEvaluation],
    request: AnalyzeRequest,
) -> tuple[str, dict[int, str]]:
    """生成逐 SKU 的中文风险解释；返回 `(llm_status, {sku_id: explanation})`。

    `llm_status` 取值（`LlmStatus` 7 值，不新造）：

    | 情形 | 返回 |
    | --- | --- |
    | `enable_llm=false` / 未配 `LLM_API_KEY` / 无有风险 SKU | `SKIPPED` |
    | 拿到 ≥1 条解释 | `SUCCEEDED` |
    | 全部失败 | 首个失败的类型（`TIMEOUT` / `FAILED` / `VALIDATION_FAILED`） |
    """
    conf = settings

    # ① 请求未要求解释 ⇒ 不调用（PROMPT_DESIGN §9：run 仍 SUCCEEDED，非降级）
    if not request.params.enable_llm:
        return "SKIPPED", {}

    # ② 全局未配置 ⇒ fail-safe 跳过（不崩进程、不伪造成功）
    if not conf.llm_configured:
        logger.warning(
            "LLM 未配置（缺 LLM_API_KEY 或 LLM_BASE_URL）⇒ 跳过解释，规则结果保留"
        )
        return "SKIPPED", {}

    try:
        template = get_template(request.prompt_version)
    except PromptError as exc:
        logger.error("取 Prompt 模板失败 ⇒ 跳过解释：%s", exc)
        return "FAILED", {}

    candidates = select_candidates(items, conf.llm_max_items)
    if not candidates:
        # 没有任何有风险的 SKU ⇒ 无需解释（非失败）
        return "SKIPPED", {}

    explanations: dict[int, str] = {}
    failures: list[str] = []
    consecutive_failures = 0

    for evaluation in candidates:
        if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
            logger.warning(
                "LLM 连续失败 %d 次 ⇒ 提前中止，剩余 %d 个 SKU 不解释（规则结果保留）",
                consecutive_failures,
                len(candidates) - len(explanations) - len(failures),
            )
            break

        sku_id = evaluation.sku_id
        try:
            context = build_risk_context(
                evaluation, window_days=request.params.window_days
            )
            raw = complete(template.messages(context))
            result = validate_output(
                raw, context=context, risk_level=evaluation.risk_level
            )
        except LlmTimeout as exc:
            logger.warning("LLM 超时（sku_id=%s）：%s", sku_id, exc)
            failures.append("TIMEOUT")
            consecutive_failures += 1
            continue
        except OutputValidationError as exc:
            logger.warning("LLM 输出非法（sku_id=%s）：%s", sku_id, exc)
            failures.append("VALIDATION_FAILED")
            consecutive_failures += 1
            continue
        except LlmError as exc:
            logger.warning("LLM 调用失败（sku_id=%s）：%s", sku_id, exc)
            failures.append("FAILED")
            consecutive_failures += 1
            continue
        except Exception as exc:  # noqa: BLE001 —— 兜底：绝不冒泡
            logger.warning("LLM 解释异常（sku_id=%s）：%s", sku_id, exc)
            failures.append("FAILED")
            consecutive_failures += 1
            continue

        for warning in result.warnings:
            logger.info("LLM 输出告警（sku_id=%s）：%s", sku_id, warning)

        if not result.ok or result.explanation is None:
            logger.warning(
                "LLM 输出未通过校验（sku_id=%s）：%s", sku_id, result.reason
            )
            failures.append("VALIDATION_FAILED")
            consecutive_failures += 1
            continue

        explanations[sku_id] = result.explanation
        consecutive_failures = 0

    if explanations:
        logger.info(
            "LLM 解释完成：成功 %d / 目标 %d，失败 %d",
            len(explanations),
            len(candidates),
            len(failures),
        )
        return "SUCCEEDED", explanations

    if failures:
        # 全部失败：以**首次**失败类型为准（最能反映根因，如鉴权错 vs 超时）
        return failures[0], {}

    return "SKIPPED", {}


__all__ = [
    "MAX_CONSECUTIVE_FAILURES",
    "llm_narrative",
    "select_candidates",
]
