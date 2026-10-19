"""运行状态注册表（M4-T11）：内存 + 线程安全。

权威口径：`AI_SERVICE_CONTRACT.md §3.4`（状态查询响应）+
`DESIGN_REVIEW.md §5.32` 裁定 #8/#9/#10。

## ★ 只存内存，进程重启即丢失（§5.32 #10）

本模块**不落库、不写文件、不发网络请求**。Python 实例重启后所有 run 记录消失，
成为「孤儿任务」——由 Java 侧兜底：`analysis_runs.timeout_at`（创建时间 + 300s，
`DESIGN_BASIS §13.4`）到期后把仍在 `PENDING/RUNNING` 的 run 置为超时终态，
见 `AI_SERVICE_CONTRACT §6.2`。因此本模块**不需要**持久化，也**不能**把内存状态
当作结果的唯一来源：`ResultItem` 的权威副本始终由 Java 侧 `analysis_items` 落库。

## 容量与并发（§5.32 #9）

- `MAX_CONCURRENT_RUNS = 4`：**非终态**（`ACCEPTED`/`RUNNING`）记录数上限，
  超出抛 `AI-7001`（HTTP 429 ⇒ Java `ANL-7001` 409 或 `COM-0011` 429）。
- `MAX_RECORDS = 200`：记录总数上限；超出时淘汰**最旧的终态记录**；
  若一条终态都没有可淘汰（全部在跑），同样抛 `AI-7001`。

## 终态不可变（§5.32 #8）

`COMPLETED` / `FAILED` / `CANCELLED` 之后，`update_step`/`complete`/`fail`/
`request_cancel` 一律**不改变**既有记录并原样返回——避免「已完成的 run 被迟到的
异常改写成 FAILED」这类竞态（Java 侧会读到自相矛盾的终态）。

## 线程安全

所有读改写都在同一把 `threading.Lock` 内完成；返回的是记录对象本身
（调用方只读，不回写），结构变更（注册 / 淘汰）受锁保护。
"""
from __future__ import annotations

from datetime import datetime, timezone
from threading import Lock
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from shoppilot_ai.app.core.errors import AI_7001, ServiceAIBaseError
from shoppilot_ai.app.schemas.analysis_response import (
    CurrentStep,
    ExecutionStatus,
    LlmStatus,
    RunResultResponse,
)

#: 单实例允许的**非终态** run 数（§5.32 #9）。超出 ⇒ `AI-7001`。
MAX_CONCURRENT_RUNS = 4

#: 单实例保留的 run 记录总数上限（§5.32 #9）。超出 ⇒ 淘汰最旧终态记录。
MAX_RECORDS = 200

#: 步骤 → `progress` 档位（§5.32 #2）。**只在步骤边界取固定值**，步内不再变化，
#: 步内粒度由 `processed_skus` 表达；契约示例的 `66` 是示意（`R119`）。
STEP_PROGRESS: dict[str, int] = {
    "VALIDATING": 5,
    "STATS": 20,
    "RISK_RULES": 40,
    "REPLENISHMENT": 60,
    "LLM_EXPLAIN": 80,
    "DONE": 100,
}

#: 终态集合：三者之后记录不再变化（§5.32 #8）。
TERMINAL_STATUSES: frozenset[str] = frozenset({"COMPLETED", "FAILED", "CANCELLED"})

#: 非终态（计入并发额度）。
ACTIVE_STATUSES: frozenset[str] = frozenset({"ACCEPTED", "RUNNING"})


def _utc_now() -> datetime:
    """UTC 时间戳（Java 侧按 `DATETIME(3)` UTC 落库，禁止本地时区）。"""
    return datetime.now(timezone.utc)


class RunRecord(BaseModel):
    """单个分析 run 的内存状态（`§3.4` 状态响应 + 内部编排所需的额外字段）。"""

    model_config = ConfigDict(extra="ignore")

    analysis_run_id: int
    team_id: int
    request_hash: str = ""
    execution_status: ExecutionStatus = "ACCEPTED"
    progress: int = Field(default=0, ge=0, le=100)
    current_step: CurrentStep | None = None
    total_skus: int = 0
    processed_skus: int = 0
    llm_status: LlmStatus = "NOT_REQUESTED"
    started_at: datetime | None = None
    finished_at: datetime | None = None
    #: `{code, message, detail}`（与四字段错误信封同构，§5.32 #5 要求记录 `stage`）。
    error: dict[str, Any] | None = None
    cancel_requested: bool = False
    warnings: list[str] = []
    #: 仅 `B19.2` 的 result 端点会读；`B19.1` 由 `complete(run_id, result)` 写入。
    result: RunResultResponse | None = None


class RunRegistry:
    """线程安全的内存注册表（单实例内共享；不跨实例、不持久化）。"""

    def __init__(self) -> None:
        self._lock = Lock()
        self._records: dict[int, RunRecord] = {}

    # ----------------------------------------------------------------- #
    # 查询
    # ----------------------------------------------------------------- #
    def get(self, run_id: int) -> RunRecord | None:
        """取记录；未注册返回 `None`（对应 `AI-6001` 的语义，由 `B19.2` 转成 404）。"""
        with self._lock:
            return self._records.get(run_id)

    def is_cancel_requested(self, run_id: int) -> bool:
        """是否已请求取消；未注册的 run 视为「未请求」。"""
        with self._lock:
            record = self._records.get(run_id)
            return bool(record and record.cancel_requested)

    def snapshot(self) -> list[RunRecord]:
        """按注册顺序返回全部记录（诊断用；仅在锁内做浅拷贝）。"""
        with self._lock:
            return list(self._records.values())

    # ----------------------------------------------------------------- #
    # 生命周期
    # ----------------------------------------------------------------- #
    def register(
        self,
        run_id: int,
        team_id: int,
        request_hash: str = "",
        total_skus: int = 0,
    ) -> RunRecord:
        """注册新 run（`ACCEPTED` / `progress=0`）；已注册则原样返回既有记录。

        :raises ServiceAIBaseError: `AI-7001`（并发超限，或容量已满且无可淘汰的终态记录）
        """
        with self._lock:
            existing = self._records.get(run_id)
            if existing is not None:
                return existing

            active = self._count_active()
            if active >= MAX_CONCURRENT_RUNS:
                raise ServiceAIBaseError(
                    code=AI_7001,
                    detail={"active": active, "max": MAX_CONCURRENT_RUNS},
                )

            self._evict_for_capacity()

            record = RunRecord(
                analysis_run_id=run_id,
                team_id=team_id,
                request_hash=request_hash,
                execution_status="ACCEPTED",
                progress=0,
                total_skus=total_skus,
            )
            self._records[run_id] = record
            return record

    def start(self, run_id: int) -> RunRecord | None:
        """`ACCEPTED → RUNNING` 并写 `started_at`（UTC）。"""
        with self._lock:
            record = self._records.get(run_id)
            if record is None or _is_terminal(record):
                return record
            record.execution_status = "RUNNING"
            if record.started_at is None:
                record.started_at = _utc_now()
            return record

    def update_step(
        self,
        run_id: int,
        step: CurrentStep,
        processed_skus: int | None = None,
    ) -> RunRecord | None:
        """写 `current_step` + 对应档位 `progress`（+ 可选 `processed_skus`）。"""
        with self._lock:
            record = self._records.get(run_id)
            if record is None or _is_terminal(record):
                return record
            record.current_step = step
            record.progress = STEP_PROGRESS[step]
            if processed_skus is not None:
                record.processed_skus = processed_skus
            return record

    def set_llm_status(
        self,
        run_id: int,
        llm_status: LlmStatus,
        warnings: list[str] | None = None,
    ) -> RunRecord | None:
        """写 run 级 `llm_status` 与 `warnings`（§5.32 #3 的 run 级 warning）。

        ★ A2 的方法清单里没有这一条，但 `llm_status`/`warnings` 必须落在记录上
        （`B19.2` 的状态端点要回显），且 `complete()` 的签名只收 `result`，
        故补一个最小写入方法；终态后同样不生效（§5.32 #8）。
        """
        with self._lock:
            record = self._records.get(run_id)
            if record is None or _is_terminal(record):
                return record
            record.llm_status = llm_status
            if warnings is not None:
                record.warnings = list(warnings)
            return record

    def complete(self, run_id: int, result: RunResultResponse) -> RunRecord | None:
        """`→ COMPLETED`：`progress=100`、`current_step=DONE`、写 `finished_at` 与结果。"""
        with self._lock:
            record = self._records.get(run_id)
            if record is None or _is_terminal(record):
                return record
            record.execution_status = "COMPLETED"
            record.progress = STEP_PROGRESS["DONE"]
            record.current_step = "DONE"
            record.finished_at = _utc_now()
            record.result = result
            return record

    def fail(
        self,
        run_id: int,
        code: str,
        message: str,
        detail: dict[str, Any] | None = None,
    ) -> RunRecord | None:
        """`→ FAILED` 并落 `{code, message, detail}`（`detail.stage` 记步骤名）。"""
        with self._lock:
            record = self._records.get(run_id)
            if record is None or _is_terminal(record):
                return record
            record.execution_status = "FAILED"
            record.finished_at = _utc_now()
            record.error = {"code": code, "message": message, "detail": detail or {}}
            return record

    def request_cancel(self, run_id: int) -> RunRecord | None:
        """请求取消；**终态上不生效**（§5.32 #13：已完成的 run 不能被取消）。"""
        with self._lock:
            record = self._records.get(run_id)
            if record is None or _is_terminal(record):
                return record
            record.cancel_requested = True
            return record

    def mark_cancelled(self, run_id: int) -> RunRecord | None:
        """`→ CANCELLED`（保留 `processed_skus`）。

        ★ A2 的方法清单只列了 `request_cancel`（打标记），但 §5.32 #13 要求管道
        在检测到取消标记后把 run 置 `CANCELLED`；两者是不同动作，故单列。
        """
        with self._lock:
            record = self._records.get(run_id)
            if record is None or _is_terminal(record):
                return record
            record.execution_status = "CANCELLED"
            record.finished_at = _utc_now()
            return record

    # ----------------------------------------------------------------- #
    # 内部（调用方必须已持有锁）
    # ----------------------------------------------------------------- #
    def _count_active(self) -> int:
        return sum(
            1
            for record in self._records.values()
            if record.execution_status in ACTIVE_STATUSES
        )

    def _evict_for_capacity(self) -> None:
        """容量超限时淘汰**最旧终态**；无终态可淘汰 ⇒ `AI-7001`。"""
        while len(self._records) >= MAX_RECORDS:
            oldest_terminal = next(
                (
                    run_id
                    for run_id, record in self._records.items()
                    if record.execution_status in TERMINAL_STATUSES
                ),
                None,
            )
            if oldest_terminal is None:
                raise ServiceAIBaseError(
                    code=AI_7001,
                    detail={
                        "active": self._count_active(),
                        "max": MAX_CONCURRENT_RUNS,
                        "records": len(self._records),
                        "max_records": MAX_RECORDS,
                        "reason": "no_terminal_record_to_evict",
                    },
                )
            del self._records[oldest_terminal]


def _is_terminal(record: RunRecord) -> bool:
    return record.execution_status in TERMINAL_STATUSES


__all__ = [
    "ACTIVE_STATUSES",
    "MAX_CONCURRENT_RUNS",
    "MAX_RECORDS",
    "RunRecord",
    "RunRegistry",
    "STEP_PROGRESS",
    "TERMINAL_STATUSES",
]
