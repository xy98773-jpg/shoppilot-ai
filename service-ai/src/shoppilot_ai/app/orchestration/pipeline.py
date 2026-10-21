"""分析编排管道（M4-T11）：6 步顺序编排 + 结果组装。

权威口径：`AI_SERVICE_CONTRACT.md §3.4`（状态查询）/`§3.5`（结果）+
`DEVELOPMENT_PLAN.md §6.9`（M4-T11 验收＝「步骤状态可查；规则层不受 LLM 影响」）
+ `DESIGN_REVIEW.md §5.32` 的 14 条裁定。

## 步骤序列（§5.32 #1/#2，顺序不可改）

`VALIDATING → STATS → RISK_RULES → REPLENISHMENT → LLM_EXPLAIN → DONE`，
`progress` **只在步骤边界**取固定档位（5/20/40/60/80/100），步内粒度由
`processed_skus` 表达（契约示例的 `66` 是示意值，`R119`）。每步**开始前**先查
`cancel_requested`，命中即置 `CANCELLED` 且不产出结果（#13）。

## 本模块的边界

- **纯逻辑**：不碰 FastAPI、不注册路由（端点属 `B19.2`）、不连数据库、不写文件、
  不发网络请求、不接 LLM（`DEVELOPMENT_PLAN §6.8` No-Go，LLM 解释是 M5）。
- 上游计算全部复用 B16–B18：`validation.data_quality.assess`、
  `analytics.evaluate.evaluate_items`（已把「统计/规则/补货」三步合并为一个纯函数，
  本模块仍按契约把三步的进度上报出去）。
- **规则层不受 LLM 影响**：LLM 只通过 `narrative` 产出 `llm_explanation`；
  它抛异常或返回失败态时，规则结果照常返回（`DESIGN_BASIS §12`）。
- 幂等只提供 `request_hash`（规范化 JSON 的稳定哈希，§5.32 #14）；**比对与
  409 `AI-1002` 由 `B19.2` 实现**，本批不落地。

## 两个补充守卫（§5.32 未覆盖，已在交付汇报申报）

1. `request.warehouse_id is None` ⇒ **fail closed**（`AI-1001`）。Python 不猜测
   「默认仓」（`R112` 尚待确认）；若凭空填 `0` 会在 `analysis_items` 落库时留下
   假 warehouse。
2. `narrative` 抛异常 / 返回非 `LlmStatus` 取值 ⇒ `llm_status=FAILED` + run 级
   warning，**规则结果保留**（不冒泡、不伪造成功）。
"""
from __future__ import annotations

import hashlib
import json
from collections import Counter
from decimal import Decimal
from typing import Callable, get_args

from shoppilot_ai.app.analytics.evaluate import ItemEvaluation, evaluate_items
from shoppilot_ai.app.core.errors import AI_1001, AI_2002, AI_3001, AI_4001
from shoppilot_ai.app.orchestration.progress import RunRegistry
from shoppilot_ai.app.schemas.analysis_request import AnalyzeRequest
from shoppilot_ai.app.schemas.analysis_response import (
    LlmStatus,
    ResultItem,
    RunResultData,
    RunResultResponse,
    RunSummary,
)
from shoppilot_ai.app.validation.data_quality import assess

#: 固定 6 步（§5.32 #1）。
STEPS: tuple[str, ...] = (
    "VALIDATING",
    "STATS",
    "RISK_RULES",
    "REPLENISHMENT",
    "LLM_EXPLAIN",
    "DONE",
)

#: `risk_level` 中算「有风险」的三档（`NONE`/`UNKNOWN` 不算，§5.32 #12）。
RISK_LEVELS: frozenset[str] = frozenset({"HIGH", "MEDIUM", "LOW"})

#: 合法的 `llm_status` 取值（复用 schemas 的 Literal，不新造枚举值）。
LLM_STATUS_VALUES: frozenset[str] = frozenset(get_args(LlmStatus))

#: 可注入的解释函数：`(items, request) -> (llm_status, {sku_id: explanation})`。
#: 本批只定义接口（M5 提供真实实现），默认用 `null_narrative`（恒 `SKIPPED`）。
Narrative = Callable[[list[ItemEvaluation], AnalyzeRequest], tuple[str, dict[str, str]]]


def null_narrative(
    items: list[ItemEvaluation],
    request: AnalyzeRequest,
) -> tuple[str, dict[str, str]]:
    """默认解释器：M4 不接 LLM，恒返回 `("SKIPPED", {})`（§5.32 #3）。"""
    return "SKIPPED", {}


#: 兼容令文中的 `NullNarrative` 写法（同一实现）。
NullNarrative = null_narrative


def request_hash(request: AnalyzeRequest) -> str:
    """请求体的稳定哈希（§5.32 #14）：同体必同、异体必异。

    做法：``model_dump(mode="json", exclude_none=False)`` 把 `date` 等原生类型转成
    JSON 可序列化的字符串，再 ``json.dumps(sort_keys=True, separators=(",", ":"))``
    消掉「字段顺序 / 空白」差异，最后 sha256 取前 16 位十六进制。
    ★ 保留 `None`（``exclude_none=False``）：缺省字段与显式 `null` 在契约里是
    **不同**的输入（`warehouse_id` 省略＝默认仓），抹掉会破坏幂等语义。
    """
    payload = json.dumps(
        request.model_dump(mode="json", exclude_none=False),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


def build_summary(items: list[ResultItem]) -> RunSummary:
    """组装 `summary`（§5.32 #12；`by_risk_level` **只输出出现过的等级**）。"""
    return RunSummary(
        total_skus=len(items),
        risk_sku_count=sum(1 for item in items if item.risk_level in RISK_LEVELS),
        suggestion_count=sum(
            1
            for item in items
            if item.q_suggest is not None and Decimal(item.q_suggest) > 0
        ),
        truncated_count=sum(1 for item in items if item.truncated_by_budget),
        by_risk_level=dict(Counter(item.risk_level for item in items)),
    )


def run_analysis(
    request: AnalyzeRequest,
    registry: RunRegistry,
    narrative: Narrative | None = None,
) -> RunResultResponse | None:
    """跑完 6 步并组装结果；`None` 表示未产出结果（`FAILED` / `CANCELLED`）。

    调用方（`B19.2` 的端点）**必须先 `registry.register(...)`**——否则状态写入
    全部落空（`RunRegistry` 对未注册的 run 一律静默返回 `None`，不抛异常）。

    :param narrative: 可注入的解释函数；缺省 `null_narrative`（恒 `SKIPPED`）
    """
    run_id = request.analysis_run_id
    explain = narrative or null_narrative
    warnings: list[str] = []

    registry.start(run_id)

    # ---------------- VALIDATING ----------------
    if _enter(registry, run_id, "VALIDATING", 0):
        return None

    if request.warehouse_id is None:
        # fail closed：不猜默认仓（守卫 1，详见模块 docstring）
        registry.fail(
            run_id,
            AI_1001,
            "缺少 warehouse_id",
            {
                "stage": "VALIDATING",
                "field": "warehouse_id",
                "reason": "默认仓库解析属 B19.2；Python 不猜测默认仓",
                "run_id": run_id,
            },
        )
        return None

    try:
        quality = assess(request)
    except Exception as exc:  # noqa: BLE001 —— 异常必须落 run 状态，不得冒泡（#5）
        registry.fail(
            run_id,
            AI_3001,
            "数据质量预检失败",
            {"stage": "VALIDATING", "reason": str(exc)},
        )
        return None

    if not quality.analyzable:
        # 全部 SKU 数据不足 ⇒ 不产出部分结果（§5.32 #4）
        registry.fail(
            run_id,
            AI_2002,
            "窗口内销售数据不足",
            {
                "stage": "VALIDATING",
                "total_skus": quality.total_skus,
                "insufficient_skus": quality.insufficient_skus,
                "window_days": quality.window_days,
                "min_days": request.params.min_days,
            },
        )
        return None

    # ---------------- STATS / RISK_RULES / REPLENISHMENT ----------------
    # B18 已把三步合并为 `evaluate_items` 一个纯函数；这里仍按契约分三段上报进度。
    if _enter(registry, run_id, "STATS", 0):
        return None
    if _enter(registry, run_id, "RISK_RULES", 0):
        return None

    try:
        evaluations = evaluate_items(
            request.items,
            request.params,
            warehouse_id=request.warehouse_id,
            date_to=request.date_to,
        )
    except Exception as exc:  # noqa: BLE001
        registry.fail(
            run_id,
            AI_3001,
            "规则引擎执行失败",
            {"stage": "RISK_RULES", "reason": str(exc)},
        )
        return None

    if _enter(registry, run_id, "REPLENISHMENT", 0):
        return None

    result_items: list[ResultItem] = []
    for index, evaluation in enumerate(evaluations, start=1):
        try:
            result_items.append(
                ResultItem(
                    **evaluation.model_dump(),
                    llm_explanation=None,
                    llm_evidence=[],
                    llm_status="NOT_REQUESTED",
                )
            )
        except Exception as exc:  # noqa: BLE001
            registry.fail(
                run_id,
                AI_4001,
                "补货计算失败",
                {"stage": "REPLENISHMENT", "sku_id": evaluation.sku_id, "reason": str(exc)},
            )
            return None
        # 步内粒度只体现于 processed_skus；progress 保持 60（§5.32 #2）
        registry.update_step(run_id, "REPLENISHMENT", index)

    # ---------------- LLM_EXPLAIN ----------------
    if _enter(registry, run_id, "LLM_EXPLAIN", len(result_items)):
        return None

    llm_status, explanations = _run_narrative(explain, evaluations, request, warnings)

    result_items = [
        item.model_copy(
            update={
                "llm_explanation": _lookup(explanations, item.sku_id),
                "llm_status": llm_status,
            }
        )
        for item in result_items
    ]
    registry.set_llm_status(run_id, llm_status, warnings=warnings)

    # ---------------- DONE ----------------
    if _enter(registry, run_id, "DONE", len(result_items)):
        return None

    try:
        response = RunResultResponse(
            data=RunResultData(
                analysis_run_id=request.analysis_run_id,
                execution_status="COMPLETED",
                algorithm_version=request.algorithm_version,
                prompt_version=request.prompt_version,
                model_name=request.model_name,
                llm_status=llm_status,
                # R111：回显创建期声明的取数窗口，不是「实际有销售记录的日期区间」
                data_start_date=request.date_from,
                data_end_date=request.date_to,
                summary=build_summary(result_items),
                items=result_items,
            )
        )
    except Exception as exc:  # noqa: BLE001
        registry.fail(
            run_id,
            AI_4001,
            "补货计算失败",
            {"stage": "REPLENISHMENT", "reason": str(exc)},
        )
        return None

    registry.complete(run_id, response)
    return response


# --------------------------------------------------------------------------- #
# 内部工具
# --------------------------------------------------------------------------- #
def _enter(registry: RunRegistry, run_id: int, step: str, processed: int) -> bool:
    """进入步骤前：先查取消标记，命中即置 `CANCELLED` 并返回 `True`（表示已中止）。

    未注册 / 已终态时 `update_step` 是静默 no-op（`§5.32 #8`），管道照常返回 `None`
    或既有结果，不额外抛异常。
    """
    if registry.is_cancel_requested(run_id):
        registry.mark_cancelled(run_id)
        return True
    registry.update_step(run_id, step, processed)
    return False


def _run_narrative(
    explain: Narrative,
    evaluations: list[ItemEvaluation],
    request: AnalyzeRequest,
    warnings: list[str],
) -> tuple[str, dict[str, str]]:
    """调用解释器并把异常/非法取值收敛为 `FAILED`（守卫 2：不冒泡、不伪造成功）。"""
    try:
        llm_status, explanations = explain(evaluations, request)
    except Exception as exc:  # noqa: BLE001
        warnings.append(f"LLM 解释生成异常，规则结果保留：{exc}")
        return "FAILED", {}

    if llm_status not in LLM_STATUS_VALUES:
        warnings.append(f"LLM 返回非法状态 {llm_status!r}，按 FAILED 处理")
        return "FAILED", {}
    if not isinstance(explanations, dict):
        warnings.append("LLM 返回的解释不是对象，按无解释处理")
        explanations = {}

    # ★ M8-T07（R155）：真实 narrative 在**内部**收敛异常后返回失败态，
    #   不会再冒泡到上面的 except ⇒ 这里必须补一条 run 级 warning，
    #   否则「LLM 失败」对调用方是**静默**的（用户看到 run 成功却没解释，
    #   没有任何线索）。可观测性是降级契约的一部分（PROMPT_DESIGN §9）。
    if llm_status in {"FAILED", "TIMEOUT", "VALIDATION_FAILED"} and not any(
        "LLM" in w for w in warnings
    ):
        warnings.append(f"LLM 解释未生成（{llm_status}），规则结果完整保留")

    # §5.32 #3：声明要用解释但没解释时，写 run 级 warning；enable_llm=false 则不加。
    # 真实 narrative 成功时**不**加（否则是假声明）。
    # ★ M8-T07（R155）措辞订正：原写「LLM 解释未实现（M5）」已过时——
    #   现已实现，SKIPPED 只可能是「未配 LLM_API_KEY」或「没有有风险的 SKU」。
    if request.params.enable_llm and llm_status == "SKIPPED":
        warnings.append(
            "LLM 解释未产出（enable_llm=true 但 llm_status=SKIPPED："
            "可能未配置 LLM_API_KEY，或本次没有有风险的 SKU）"
        )
    return llm_status, explanations


def _lookup(explanations: dict[str, str], sku_id: int) -> str | None:
    """解释按 `sku_id` 取；兼容 str / int 两种键，并强制转 `str`。"""
    for key in (str(sku_id), sku_id):
        value = explanations.get(key)
        if value:
            return str(value)
    return None


__all__ = [
    "LLM_STATUS_VALUES",
    "Narrative",
    "NullNarrative",
    "RISK_LEVELS",
    "STEPS",
    "build_summary",
    "null_narrative",
    "request_hash",
    "run_analysis",
]
