"""Analysis API router —— 5 个内部端点（M4-B19.2）。

权威口径：`AI_SERVICE_CONTRACT.md §3.1`（预检）/`§3.2`（创建）/`§3.4`（状态）/
`§3.5`（结果）/`§3.6`（取消）/`§6.2`（幂等）+ `DESIGN_REVIEW.md §5.34` 裁定 12 条。

★ `analysis:validate` 与 `runs/{id}:cancel` 使用**冒号动作后缀**（`R93`）：
写成 `/analysis/validate` 会让 Java 调用方 404。

- ``POST /internal/v1/analysis:validate``
- ``POST /internal/v1/analysis/runs``（202）
- ``GET  /internal/v1/analysis/runs/{analysis_run_id}``
- ``GET  /internal/v1/analysis/runs/{analysis_run_id}/result``
- ``POST /internal/v1/analysis/runs/{analysis_run_id}:cancel``

## 本模块负责什么

- 路径常量（顶部集中定义，便于与 Java `AiContract.PATH_*` 逐条对账）；
- **模块级单例** `RunRegistry`（裁定 #1：状态端点要能查到别的请求注册的 run，
  每请求新建实例会让状态查询永远 404）；
- 成功信封与 `request_id` 回显（与响应头 `X-Request-Id` 逐字节一致，`R98`）；
- 幂等比对（**必须**在 `register()` 之前，否则重发会撞并发上限而不是回首次 202）；
- 用 `BackgroundTasks` 跑**同步**编排函数（`async def` 会阻塞事件循环）。

鉴权、错误信封、404/405、请求体校验错误全部由 `app/main.py` 既有中间件与处理器
统一处理，本模块不重复实现（也不新增任何错误码字面量——一律用 `core.errors` 常量）。

## R120 约束（裁定 #12，必须知道）

Python **不猜默认仓**、**不装配** items：
1. `warehouse_id` 缺省 ⇒ 编排 `AI_1001`(400) fail closed（`M4-B19.1` 守卫）；
2. `items=[]` ⇒ 全不可分析 ⇒ `AI_2002`(422)；
3. 因此**端到端 `202 → COMPLETED` 在 Java 侧装配 items 之前不可达**，成功路径只能用
   手工构造的带 `items` 合成请求验证（测试即如此）。
"""
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, BackgroundTasks, Body, Request, status

from shoppilot_ai.app.core.errors import (
    AI_1002,
    AI_6001,
    AI_6002,
    AI_6003,
    AI_9001,
    DEFAULT_MESSAGES,
    ServiceAIBaseError,
)
from shoppilot_ai.app.core.tracing import resolve_request_id
from shoppilot_ai.app.orchestration.pipeline import request_hash, run_analysis
from shoppilot_ai.app.orchestration.progress import RunRecord, RunRegistry
from shoppilot_ai.app.schemas.analysis_request import (
    AnalyzeRequest,
    CancelRequest,
    Params,
    ValidateRequest,
)
from shoppilot_ai.app.schemas.analysis_response import (
    RunAcceptedData,
    RunCancelledData,
    RunError,
    RunResultResponse,
    RunStatusData,
    ValidateData,
    ValidateResponse,
)
from shoppilot_ai.app.schemas.common import Envelope
from shoppilot_ai.app.validation.data_quality import assess

router = APIRouter(prefix="/internal/v1", tags=["analysis"])

# --------------------------------------------------------------------------- #
# 路径常量（与 Java `AiContract.PATH_*` 一一对应；冒号后缀是契约的一部分，R93）
# --------------------------------------------------------------------------- #
PATH_VALIDATE = "/analysis:validate"
PATH_RUNS = "/analysis/runs"
PATH_RUN = "/analysis/runs/{analysis_run_id}"
PATH_RESULT = "/analysis/runs/{analysis_run_id}/result"
PATH_CANCEL = "/analysis/runs/{analysis_run_id}:cancel"

#: 预检内部合成的 `analysis_run_id`：`assess()` 只读 `params` 与 `items`，
#: 该对象不落库、不回传、请求结束即丢，所以取固定占位值即可（`§3.1` 请求体无此字段）。
_PREFLIGHT_RUN_ID = 1
#: 同上：仅为通过 `AnalyzeRequest` 的 `algorithm_version` 格式校验。
_PREFLIGHT_ALGORITHM_VERSION = "preflight-v1"

#: 模块级单例注册表（裁定 #1）。`get_registry()` 在**调用时**读它，测试可直接替换。
registry = RunRegistry()


def get_registry() -> RunRegistry:
    """返回进程级注册表单例（跨请求共享，状态端点据此查得到别的请求注册的 run）。"""
    return registry


# --------------------------------------------------------------------------- #
# 工具
# --------------------------------------------------------------------------- #
def _utc_now() -> datetime:
    """UTC 时间戳（带时区；裁定 #10 禁止无时区裸时间）。"""
    return datetime.now(timezone.utc)


def _request_id(request: Request) -> str:
    """取当前请求 id：与 `app/main.py` 中间件同一份逻辑 ⇒ 与响应头逐字节一致。"""
    existing = getattr(request.state, "request_id", None)
    if existing:
        return existing
    resolved = resolve_request_id(request)
    request.state.request_id = resolved
    return resolved


def _require_run(analysis_run_id: int) -> RunRecord:
    """取 run 记录；未注册 ⇒ `404 AI_6001`（**不是** `AI_1002`）。"""
    record = get_registry().get(analysis_run_id)
    if record is None:
        raise ServiceAIBaseError(
            code=AI_6001,
            detail={"analysis_run_id": analysis_run_id},
        )
    return record


def _is_terminal(record: RunRecord) -> bool:
    return record.execution_status in {"COMPLETED", "FAILED", "CANCELLED"}


def _to_status_data(record: RunRecord) -> RunStatusData:
    """`RunRecord` → `§3.4` 的 `data`（时间转 ISO-8601 字符串，`error` 转结构体）。"""
    error = None
    if record.error:
        error = RunError(
            code=str(record.error.get("code", AI_9001)),
            message=str(record.error.get("message", DEFAULT_MESSAGES[AI_9001])),
            detail=record.error.get("detail") or {},
        )
    return RunStatusData(
        analysis_run_id=record.analysis_run_id,
        execution_status=record.execution_status,
        progress=record.progress,
        current_step=record.current_step,
        total_skus=record.total_skus,
        processed_skus=record.processed_skus,
        llm_status=record.llm_status,
        started_at=None if record.started_at is None else record.started_at.isoformat(),
        finished_at=None if record.finished_at is None else record.finished_at.isoformat(),
        error=error,
    )


def to_analyze_request(payload: ValidateRequest) -> AnalyzeRequest:
    """把预检请求体升格成 `AnalyzeRequest`（`assess()` 的入参形态）。

    只做形态适配：`params.enable_llm` 强制置 `False`（预检不需要 LLM，且
    `AnalyzeRequest` 在 `enable_llm=true` 时强制要求 `prompt_version`），其余原样透传。
    """
    params = Params.model_validate({**payload.params.model_dump(), "enable_llm": False})
    return AnalyzeRequest(
        analysis_run_id=_PREFLIGHT_RUN_ID,
        team_id=payload.team_id,
        warehouse_id=payload.warehouse_id,
        scope=payload.scope,
        date_from=payload.date_from,
        date_to=payload.date_to,
        params=params,
        algorithm_version=_PREFLIGHT_ALGORITHM_VERSION,
        prompt_version=None,
        items=payload.items,
    )


def _execute_run(payload: AnalyzeRequest) -> None:
    """后台执行体（**同步函数**：Starlette 自动丢线程池，不阻塞事件循环）。

    管道内部已按阶段捕获异常并落 run 状态；这里是最后一道防线——任何漏网异常
    都必须变成 run 的 `FAILED`，**不得**冒泡（响应早已返回 202）。
    """
    run_registry = get_registry()
    try:
        run_analysis(payload, run_registry)
    except Exception as exc:  # noqa: BLE001 —— 最后防线，异常绝不外泄
        run_registry.fail(
            payload.analysis_run_id,
            AI_9001,
            "分析执行异常",
            {"stage": "PIPELINE", "reason": str(exc)},
        )


# --------------------------------------------------------------------------- #
# §3.1 预检
# --------------------------------------------------------------------------- #
@router.post(PATH_VALIDATE, response_model=ValidateResponse)
async def validate(payload: ValidateRequest, request: Request) -> ValidateResponse:
    """数据质量预检：不注册 run、不要求 warehouse_id、不跑编排（裁定 #5）。"""
    data: ValidateData = assess(to_analyze_request(payload))
    return ValidateResponse(code="OK", data=data, request_id=_request_id(request))


# --------------------------------------------------------------------------- #
# §3.2 创建（202）
# --------------------------------------------------------------------------- #
@router.post(
    PATH_RUNS,
    response_model=Envelope[RunAcceptedData],
    status_code=status.HTTP_202_ACCEPTED,
)
async def create_run(
    payload: AnalyzeRequest,
    request: Request,
    background_tasks: BackgroundTasks,
) -> Envelope[RunAcceptedData]:
    """受理创建请求：幂等比对 → 注册 → 后台跑编排 → 202 回执。

    顺序**不可调换**（提醒 3）：先比对哈希再 `register()`，否则重发会撞并发上限
    `AI_7001`(429)，而不是回首次 `202`。
    """
    run_registry = get_registry()
    incoming_hash = request_hash(payload)

    existing = run_registry.get(payload.analysis_run_id)
    if existing is not None:
        if existing.request_hash == incoming_hash:
            # 同 run_id + 同体 ⇒ 回 202 + **当前**状态，不重复执行（裁定 #4）
            return Envelope[RunAcceptedData](
                data=RunAcceptedData(
                    analysis_run_id=existing.analysis_run_id,
                    execution_status=existing.execution_status,
                    accepted_at=_utc_now(),
                ),
                request_id=_request_id(request),
            )
        raise ServiceAIBaseError(
            code=AI_1002,
            # ★ 显式覆盖 HTTP 状态：`ERROR_CODES §2.11` 给 `AI_1002` 登记的是 400
            #   （触发条件＝「analysis_run_id 缺失或非法」），而**幂等冲突**这一触发场景
            #   在 `AI_SERVICE_CONTRACT §6.2` 明确写 409（`§5.34` 裁定 #4 同）。
            #   同一个码在两个场景语义不同，故此处按场景显式给 409，**不改**全局映射表。
            http_status=status.HTTP_409_CONFLICT,
            detail={
                "existing_hash": existing.request_hash[:8],
                "incoming_hash": incoming_hash[:8],
                "analysis_run_id": payload.analysis_run_id,
            },
        )

    # 可能抛 `AI_7001`（并发超限），由既有通用处理器出四字段错误信封
    run_registry.register(
        payload.analysis_run_id,
        payload.team_id,
        incoming_hash,
        len(payload.items),
    )
    background_tasks.add_task(_execute_run, payload)

    return Envelope[RunAcceptedData](
        data=RunAcceptedData(
            analysis_run_id=payload.analysis_run_id,
            execution_status="ACCEPTED",
            accepted_at=_utc_now(),
        ),
        request_id=_request_id(request),
    )


# --------------------------------------------------------------------------- #
# §3.4 状态查询
# --------------------------------------------------------------------------- #
@router.get(PATH_RUN, response_model=Envelope[RunStatusData])
async def get_run(analysis_run_id: int, request: Request) -> Envelope[RunStatusData]:
    """轮询执行状态；未注册 ⇒ `404 AI_6001`。"""
    record = _require_run(analysis_run_id)
    return Envelope[RunStatusData](
        data=_to_status_data(record), request_id=_request_id(request)
    )


# --------------------------------------------------------------------------- #
# §3.5 取结果
# --------------------------------------------------------------------------- #
@router.get(PATH_RESULT, response_model=RunResultResponse)
async def get_result(analysis_run_id: int, request: Request) -> RunResultResponse:
    """取结果：`COMPLETED` ⇒ 200；其余状态按裁定 #7 分支。"""
    record = _require_run(analysis_run_id)

    if record.execution_status == "CANCELLED":
        raise ServiceAIBaseError(code=AI_6003, detail={"analysis_run_id": analysis_run_id})
    if record.execution_status != "COMPLETED":
        raise ServiceAIBaseError(
            code=AI_6002,
            detail={
                "analysis_run_id": analysis_run_id,
                "execution_status": record.execution_status,
                "error": record.error or {},
            },
        )
    if record.result is None:  # pragma: no cover —— 防御：COMPLETED 必有结果
        raise ServiceAIBaseError(
            code=AI_6002,
            detail={"analysis_run_id": analysis_run_id, "error": "result_missing"},
        )

    # 只换 request_id：结果体是首跑时算好的，不重算（否则数值会随时间漂移）
    return RunResultResponse(
        code="OK", data=record.result.data, request_id=_request_id(request)
    )


# --------------------------------------------------------------------------- #
# §3.6 取消
# --------------------------------------------------------------------------- #
@router.post(PATH_CANCEL, response_model=Envelope[RunCancelledData])
async def cancel_run(
    analysis_run_id: int,
    request: Request,
    payload: CancelRequest | None = Body(default=None),
) -> Envelope[RunCancelledData]:
    """受理取消：打标记并回**受理回执**；真实终态以状态端点为准（裁定 #8）。"""
    record = _require_run(analysis_run_id)
    if _is_terminal(record):
        raise ServiceAIBaseError(
            code=AI_6002,
            detail={
                "analysis_run_id": analysis_run_id,
                "execution_status": record.execution_status,
                "reason": "run 已终态，不能取消",
            },
        )

    get_registry().request_cancel(analysis_run_id)

    return Envelope[RunCancelledData](
        data=RunCancelledData(analysis_run_id=analysis_run_id, cancelled_at=_utc_now()),
        request_id=_request_id(request),
    )


__all__ = [
    "PATH_CANCEL",
    "PATH_RESULT",
    "PATH_RUN",
    "PATH_RUNS",
    "PATH_VALIDATE",
    "cancel_run",
    "create_run",
    "get_registry",
    "get_result",
    "get_run",
    "registry",
    "router",
    "to_analyze_request",
    "validate",
]
