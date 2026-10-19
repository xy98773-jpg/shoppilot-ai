"""LLM 降级判定（M4-T11）：`needs_degradation` + `summarize_llm_status`。

口径：`DESIGN_REVIEW.md §5.32` 裁定 #7（三个失败态降级、其余 4 值不降级）。
"""
from __future__ import annotations

import pytest

from shoppilot_ai.app.orchestration.degradation import (
    DEGRADED_STATUSES,
    KNOWN_STATUSES,
    needs_degradation,
    summarize_llm_status,
)

ALL_STATUSES = [
    "NOT_REQUESTED",
    "PENDING",
    "SUCCEEDED",
    "FAILED",
    "TIMEOUT",
    "VALIDATION_FAILED",
    "SKIPPED",
]


def test_known_statuses_cover_all_seven_llm_status_values() -> None:
    assert len(KNOWN_STATUSES) == 7
    assert set(ALL_STATUSES) == KNOWN_STATUSES


@pytest.mark.parametrize(
    "status, expected",
    [
        ("NOT_REQUESTED", False),
        ("PENDING", False),
        ("SUCCEEDED", False),
        ("SKIPPED", False),
        ("FAILED", True),
        ("TIMEOUT", True),
        ("VALIDATION_FAILED", True),
    ],
)
def test_needs_degradation_per_status(status: str, expected: bool) -> None:
    assert needs_degradation(status) is expected


def test_degraded_set_is_exactly_three_statuses() -> None:
    assert DEGRADED_STATUSES == {"FAILED", "TIMEOUT", "VALIDATION_FAILED"}


def test_unknown_status_degrades_conservatively() -> None:
    # 未来新增枚举 / 拼写错误都按「需要降级」处理：宁可 DEGRADED，不谎报成功
    assert needs_degradation("WHATEVER") is True
    assert needs_degradation("") is True


@pytest.mark.parametrize(
    "statuses, expected",
    [
        (None, "SKIPPED"),
        ([], "SKIPPED"),
        (["SKIPPED"], "SKIPPED"),
        (["SKIPPED", "SKIPPED"], "SKIPPED"),
        (["SKIPPED", "FAILED"], "FAILED"),
        (["SKIPPED", "VALIDATION_FAILED"], "VALIDATION_FAILED"),
        (["VALIDATION_FAILED", "FAILED"], "FAILED"),
        (["FAILED", "TIMEOUT"], "TIMEOUT"),
        (["TIMEOUT", "VALIDATION_FAILED"], "TIMEOUT"),
        (["NOT_REQUESTED", "PENDING"], "SKIPPED"),
    ],
)
def test_summarize_llm_status(statuses, expected: str) -> None:
    assert summarize_llm_status(statuses) == expected


def test_summarize_never_reports_success() -> None:
    # M4 不接 LLM：没有解释产出就绝不谎报 SUCCEEDED
    assert summarize_llm_status(["SUCCEEDED"]) != "SUCCEEDED"
    assert summarize_llm_status(["SUCCEEDED", "SKIPPED"]) == "SKIPPED"
    assert summarize_llm_status(["SUCCEEDED", "FAILED"]) == "FAILED"


def test_summarize_is_pure() -> None:
    statuses = ["SKIPPED", "FAILED"]
    first = summarize_llm_status(statuses)
    second = summarize_llm_status(statuses)

    assert first == second == "FAILED"
    assert statuses == ["SKIPPED", "FAILED"], "输入列表不得被就地修改"
