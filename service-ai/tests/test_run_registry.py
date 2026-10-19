"""`RunRegistry`（M4-T11）：生命周期、终态不可变、容量与并发。

口径：`DESIGN_REVIEW.md §5.32` 裁定 #8（终态不可变）/#9（`MAX_CONCURRENT_RUNS=4`、
`MAX_RECORDS=200`，超限淘汰最旧终态）/ #10（只存内存）。
"""
from __future__ import annotations

from datetime import timezone
from threading import Thread

import pytest

from shoppilot_ai.app.core.errors import ServiceAIBaseError
from shoppilot_ai.app.orchestration import progress as progress_module
from shoppilot_ai.app.orchestration.progress import (
    MAX_CONCURRENT_RUNS,
    MAX_RECORDS,
    STEP_PROGRESS,
    RunRegistry,
)
from shoppilot_ai.app.schemas.analysis_response import (
    RunResultData,
    RunResultResponse,
    RunSummary,
)


def empty_result(run_id: int = 1) -> RunResultResponse:
    """构造一个最小合法结果（`complete()` 的入参）。"""
    return RunResultResponse(
        data=RunResultData(
            analysis_run_id=run_id,
            execution_status="COMPLETED",
            algorithm_version="avg-ma-3sigma-v1",
            llm_status="SKIPPED",
            summary=RunSummary(
                total_skus=0,
                risk_sku_count=0,
                suggestion_count=0,
                truncated_count=0,
            ),
            items=[],
        )
    )


# --------------------------------------------------------------------------- #
# 注册 / 查询 / 启动
# --------------------------------------------------------------------------- #
def test_register_creates_accepted_record() -> None:
    registry = RunRegistry()
    record = registry.register(30001, team_id=7, request_hash="abc123", total_skus=12)

    assert record.analysis_run_id == 30001
    assert record.team_id == 7
    assert record.request_hash == "abc123"
    assert record.execution_status == "ACCEPTED"
    assert record.progress == 0
    assert record.current_step is None
    assert record.total_skus == 12
    assert record.processed_skus == 0
    assert record.llm_status == "NOT_REQUESTED"
    assert record.cancel_requested is False
    assert record.error is None
    assert record.result is None


def test_get_unknown_run_returns_none() -> None:
    assert RunRegistry().get(999) is None
    assert RunRegistry().is_cancel_requested(999) is False


def test_register_same_run_id_twice_returns_existing() -> None:
    registry = RunRegistry()
    first = registry.register(1, team_id=7, request_hash="h1", total_skus=3)
    second = registry.register(1, team_id=8, request_hash="h2", total_skus=9)

    assert second is first
    assert first.team_id == 7
    assert len(registry.snapshot()) == 1


def test_start_switches_to_running_with_utc_timestamp() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7)

    record = registry.start(1)

    assert record.execution_status == "RUNNING"
    assert record.started_at is not None
    assert record.started_at.tzinfo == timezone.utc


def test_update_step_writes_step_and_progress() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7, total_skus=10)
    registry.start(1)

    for step in ("VALIDATING", "STATS", "RISK_RULES", "REPLENISHMENT", "LLM_EXPLAIN"):
        registry.update_step(1, step)
        record = registry.get(1)
        assert record.current_step == step
        assert record.progress == STEP_PROGRESS[step]

    assert STEP_PROGRESS == {
        "VALIDATING": 5,
        "STATS": 20,
        "RISK_RULES": 40,
        "REPLENISHMENT": 60,
        "LLM_EXPLAIN": 80,
        "DONE": 100,
    }


def test_update_step_accepts_processed_skus() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7)

    registry.update_step(1, "REPLENISHMENT", 7)

    assert registry.get(1).processed_skus == 7
    assert registry.get(1).progress == 60


def test_update_step_keeps_processed_skus_when_not_given() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7)
    registry.update_step(1, "REPLENISHMENT", 7)

    registry.update_step(1, "LLM_EXPLAIN")

    assert registry.get(1).processed_skus == 7
    assert registry.get(1).current_step == "LLM_EXPLAIN"


def test_mutations_on_unknown_run_are_noops() -> None:
    registry = RunRegistry()

    assert registry.start(404) is None
    assert registry.update_step(404, "STATS") is None
    assert registry.complete(404, empty_result()) is None
    assert registry.fail(404, "AI-9001", "x", {}) is None
    assert registry.request_cancel(404) is None
    assert registry.mark_cancelled(404) is None
    assert registry.set_llm_status(404, "SKIPPED") is None


# --------------------------------------------------------------------------- #
# 终态不可变（§5.32 #8）
# --------------------------------------------------------------------------- #
def test_completed_run_is_not_overwritten_by_fail() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7)
    registry.complete(1, empty_result())

    registry.fail(1, "AI-3001", "迟到的异常", {"stage": "RISK_RULES"})

    record = registry.get(1)
    assert record.execution_status == "COMPLETED"
    assert record.error is None
    assert record.progress == 100
    assert record.current_step == "DONE"


def test_failed_run_is_not_overwritten_by_complete() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7)
    registry.fail(1, "AI-2002", "窗口内销售数据不足", {"stage": "VALIDATING"})

    registry.complete(1, empty_result())

    record = registry.get(1)
    assert record.execution_status == "FAILED"
    assert record.result is None
    assert record.error["code"] == "AI-2002"
    assert record.error["detail"]["stage"] == "VALIDATING"


def test_update_step_ignored_after_terminal() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7)
    registry.update_step(1, "STATS")
    registry.mark_cancelled(1)

    registry.update_step(1, "DONE", 99)

    record = registry.get(1)
    assert record.execution_status == "CANCELLED"
    assert record.current_step == "STATS"
    assert record.progress == 20
    assert record.processed_skus == 0


def test_request_cancel_has_no_effect_on_terminal() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7)
    registry.complete(1, empty_result())

    registry.request_cancel(1)

    record = registry.get(1)
    assert record.cancel_requested is False
    assert record.execution_status == "COMPLETED"


def test_mark_cancelled_preserves_processed_skus() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7)
    registry.update_step(1, "REPLENISHMENT", 4)
    registry.request_cancel(1)

    registry.mark_cancelled(1)

    record = registry.get(1)
    assert record.execution_status == "CANCELLED"
    assert record.processed_skus == 4
    assert record.finished_at is not None


def test_finished_at_is_utc() -> None:
    registry = RunRegistry()
    registry.register(1, team_id=7)

    registry.complete(1, empty_result())

    assert registry.get(1).finished_at.tzinfo == timezone.utc


# --------------------------------------------------------------------------- #
# 容量与并发（§5.32 #9）
# --------------------------------------------------------------------------- #
def test_concurrency_limit_is_four() -> None:
    registry = RunRegistry()
    for run_id in range(1, MAX_CONCURRENT_RUNS + 1):
        registry.register(run_id, team_id=7)

    assert MAX_CONCURRENT_RUNS == 4
    with pytest.raises(ServiceAIBaseError) as excinfo:
        registry.register(99, team_id=7)

    assert excinfo.value.code == "AI-7001"
    assert excinfo.value.http_status == 429
    assert excinfo.value.detail == {"active": 4, "max": 4}


def test_terminal_runs_do_not_consume_concurrency_slots() -> None:
    registry = RunRegistry()
    for run_id in range(1, 9):
        registry.register(run_id, team_id=7)
        registry.complete(run_id, empty_result(run_id))

    # 8 个已终态 ⇒ 并发额度仍可用
    registry.register(100, team_id=7)
    assert registry.get(100).execution_status == "ACCEPTED"


def test_capacity_evicts_oldest_terminal_record() -> None:
    registry = RunRegistry()
    for run_id in range(1, MAX_RECORDS + 1):
        registry.register(run_id, team_id=7)
        registry.complete(run_id, empty_result(run_id))

    assert len(registry.snapshot()) == MAX_RECORDS

    registry.register(9999, team_id=7)

    assert registry.get(1) is None, "最旧的终态记录应被淘汰"
    assert registry.get(2) is not None
    assert registry.get(9999) is not None
    assert len(registry.snapshot()) == MAX_RECORDS


def test_capacity_raises_when_no_terminal_record_can_be_evicted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # 把并发上限临时放大到 ≥ 容量，才能造出「全是非终态」的满库场景
    monkeypatch.setattr(progress_module, "MAX_CONCURRENT_RUNS", MAX_RECORDS + 1)
    registry = RunRegistry()
    for run_id in range(1, MAX_RECORDS + 1):
        registry.register(run_id, team_id=7)

    with pytest.raises(ServiceAIBaseError) as excinfo:
        registry.register(9999, team_id=7)

    assert excinfo.value.code == "AI-7001"
    assert excinfo.value.detail["reason"] == "no_terminal_record_to_evict"
    assert excinfo.value.detail["max_records"] == MAX_RECORDS
    assert len(registry.snapshot()) == MAX_RECORDS


def test_concurrent_register_never_oversells_slots() -> None:
    """10 个线程同时注册：只允许 4 个成功，其余必须抛 `AI-7001`。"""
    registry = RunRegistry()
    outcomes: dict[int, str] = {}
    barrier_results: list[str] = []

    def worker(run_id: int) -> None:
        try:
            registry.register(run_id, team_id=7)
            outcomes[run_id] = "ok"
        except ServiceAIBaseError as exc:
            outcomes[run_id] = exc.code

    threads = [Thread(target=worker, args=(run_id,)) for run_id in range(1, 11)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    barrier_results.extend(outcomes.values())
    assert len(outcomes) == 10
    assert barrier_results.count("ok") == MAX_CONCURRENT_RUNS
    assert barrier_results.count("AI-7001") == 10 - MAX_CONCURRENT_RUNS
    assert len(registry.snapshot()) == MAX_CONCURRENT_RUNS
