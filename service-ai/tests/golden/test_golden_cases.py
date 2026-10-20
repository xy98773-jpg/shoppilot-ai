"""`ANALYSIS_CONTRACT.md §7.2` golden cases 的逐字段断言（M4-T15）。

## 驱动方式（真实生产路径）

每个用例都构造完整的 `AnalyzeRequest`，交给 `orchestration/pipeline.run_analysis`
（`VALIDATING → STATS → RISK_RULES → REPLENISHMENT → LLM_EXPLAIN → DONE`），
再从**响应** `data.items[]` 里取目标 SKU 的 `ResultItem` 逐字段断言——
即覆盖 `validation.data_quality` → `analytics.sales_stats` → `rules.risk_rules` →
`rules.replenishment` → `analytics.evaluate` → `pipeline` 的完整链路，
而不是只测某一个纯函数（纯函数级的 18 条见 `tests/test_item_eval.py`，两者互补）。

## 断言口径（`ANALYSIS_CONTRACT §7.3`）

- 数值：`Decimal3Str` / `Decimal4Str` 的**字符串**精确比较（禁 float 近似）；
- `risk_types`：按**集合**比较（顺序不敏感），另断言无重复；
- `risk_findings`：按 `rule_code` 匹配后比较 `result`，并要求 `inputs` / `threshold` 非空
  （`§5.1` 可追溯性硬要求）；
- `llm_*`：`params.enable_llm=false` 下恒为 `null` / `[]` / `SKIPPED`（`§7.2` 末段）。
"""
from __future__ import annotations

from typing import Any

import pytest

from shoppilot_ai.app.orchestration.pipeline import run_analysis
from shoppilot_ai.app.orchestration.progress import RunRegistry
from shoppilot_ai.app.schemas.analysis_request import AnalyzeRequest
from shoppilot_ai.app.schemas.analysis_response import ResultItem
from tests.golden.cases import (
    CASES,
    DATE_TO,
    START,
    GoldenCase,
    params_of,
)

#: 六条规则的 `rule_code`（`DESIGN_BASIS §11`）。
RULE_CODES = {
    "R-STOCKOUT",
    "R-LOWSTOCK",
    "R-OVERSTOCK",
    "R-SLOWMOVING",
    "R-SALESANOMALY",
    "R-DATAINSUFFICIENT",
}

#: 每条用例在断言时视为「集合比较」的字段。
SET_FIELDS = {"risk_types"}


def build_request(case: GoldenCase) -> AnalyzeRequest:
    """把 golden case 组装成 `AI_SERVICE_CONTRACT §3.2` 的完整请求体。"""
    items = [case.item, *case.companions]
    return AnalyzeRequest.model_validate(
        {
            "analysis_run_id": int(case.case_id.split("-")[1]),
            "team_id": 7,
            "warehouse_id": 100,
            "scope": {
                "scope_type": "SELECTED_SKUS",
                "sku_ids": [item["sku_id"] for item in items],
            },
            "date_from": START.isoformat(),
            "date_to": DATE_TO.isoformat(),
            "params": params_of(case),
            "algorithm_version": "avg-ma-3sigma-v1",
            "items": items,
        }
    )


def run_case(case: GoldenCase) -> list[ResultItem]:
    """跑完整管线，返回 `data.items`（顺序与请求一致）。"""
    request = build_request(case)
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7, total_skus=len(request.items))

    response = run_analysis(request, registry)

    assert response is not None, (
        f"{case.case_id} 未产出结果，run 状态={registry.get(request.analysis_run_id).execution_status}，"
        f"error={registry.get(request.analysis_run_id).error}"
    )
    assert response.data.execution_status == "COMPLETED"
    return response.data.items


def target_item(case: GoldenCase, items: list[ResultItem]) -> ResultItem:
    """取目标 SKU 的条目（GC-09/GC-10 请求里另有一条伴随 SKU）。"""
    sku_id = case.item["sku_id"]
    matched = [item for item in items if item.sku_id == sku_id]
    assert len(matched) == 1, f"{case.case_id} 应恰好有一条 sku_id={sku_id} 的条目"
    return matched[0]


def findings_of(item: ResultItem) -> dict[str, str]:
    return {finding.rule_code: finding.result for finding in item.risk_findings}


# --------------------------------------------------------------------------- #
# 逐条 golden case
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("case", CASES, ids=[case.case_id for case in CASES])
def test_golden_case_matches_contract(case: GoldenCase) -> None:
    items = run_case(case)
    item = target_item(case, items)

    for field_name, expected in case.expected.items():
        actual = getattr(item, field_name)
        if field_name in SET_FIELDS:
            assert set(actual) == set(expected), (
                f"{case.case_id}.{field_name} 期望 {expected}，实际 {actual}"
            )
            assert len(actual) == len(set(actual)), f"{case.case_id}.risk_types 不得重复"
        else:
            assert actual == expected, (
                f"{case.case_id}.{field_name} 期望 {expected!r}，实际 {actual!r}"
            )

    actual_findings = findings_of(item)
    for rule_code, expected_result in case.expect_findings.items():
        assert rule_code in actual_findings, (
            f"{case.case_id} 缺少 {rule_code} 的 finding（实际 {sorted(actual_findings)}）"
        )
        assert actual_findings[rule_code] == expected_result, (
            f"{case.case_id}.{rule_code} 期望 {expected_result}，实际 {actual_findings[rule_code]}"
        )

    for substring in case.warnings_contain:
        assert any(substring in warning for warning in item.warnings), (
            f"{case.case_id}.warnings 应含 {substring!r}，实际 {item.warnings}"
        )


@pytest.mark.parametrize("case", CASES, ids=[case.case_id for case in CASES])
def test_golden_case_llm_fields_are_empty_when_llm_disabled(case: GoldenCase) -> None:
    # `ANALYSIS_CONTRACT §7.2`：enable_llm=false 下 18 条全部可断言，llm_* 恒空/SKIPPED
    items = run_case(case)
    item = target_item(case, items)

    assert item.llm_explanation is None
    assert item.llm_evidence == []
    assert item.llm_status == "SKIPPED"


@pytest.mark.parametrize("case", CASES, ids=[case.case_id for case in CASES])
def test_golden_case_findings_are_traceable(case: GoldenCase) -> None:
    # `ANALYSIS_CONTRACT §5.1`：每条 finding 必须回显 inputs，threshold 必须含实际数值
    item = target_item(case, run_case(case))

    assert item.risk_findings, f"{case.case_id} 至少应有一条 finding（数据不足也有一条）"
    for finding in item.risk_findings:
        assert finding.rule_code in RULE_CODES, f"未知 rule_code：{finding.rule_code}"
        assert finding.inputs, f"{case.case_id}.{finding.rule_code} 缺 inputs"
        assert any(char.isdigit() for char in finding.threshold), (
            f"{case.case_id}.{finding.rule_code} 的 threshold 未含实际数值：{finding.threshold}"
        )


@pytest.mark.parametrize("case", CASES, ids=[case.case_id for case in CASES])
def test_golden_case_summary_is_consistent(case: GoldenCase) -> None:
    # 单条 run 的摘要必须与条目自洽（`ANALYSIS_CONTRACT §2.2`）
    request = build_request(case)
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)
    data = run_analysis(request, registry).data

    assert data.summary.total_skus == len(data.items)
    assert data.summary.risk_sku_count == sum(
        1 for item in data.items if item.risk_level in {"HIGH", "MEDIUM", "LOW"}
    )
    assert data.summary.truncated_count == sum(
        1 for item in data.items if item.truncated_by_budget
    )
    assert data.summary.by_risk_level == {
        level: sum(1 for item in data.items if item.risk_level == level)
        for level in data.summary.by_risk_level
    }


# --------------------------------------------------------------------------- #
# 用例集自身的完备性
# --------------------------------------------------------------------------- #
def test_case_ids_cover_gc01_to_gc18_exactly_once() -> None:
    expected = {f"GC-{index:02d}" for index in range(1, 19)}
    actual = [case.case_id for case in CASES]

    assert len(actual) == 18
    assert set(actual) == expected
    assert len(actual) == len(set(actual)), "用例 id 重复"


def test_expected_fields_are_result_item_fields() -> None:
    # 防止期望字典写错字段名导致「断言了不存在的字段」这类假绿
    valid = set(ResultItem.model_fields)
    for case in CASES:
        unknown = set(case.expected) - valid
        assert not unknown, f"{case.case_id} 断言了 ResultItem 不存在的字段：{unknown}"


def test_expected_fields_cover_the_contract_key_fields() -> None:
    # 任务书要求的九个关键字段：每条用例都必须断言（数据不足时 D/CV/Q_base 期望 null 也是断言）
    required = {
        "avg_daily_sales",  # D
        "cv",  # CV
        "risk_level",
        "risk_types",
        "q_base",  # Q_base
        "q_suggest",  # Q_suggest
        "moq_applied",  # MOQ
        "order_multiple_applied",  # 倍数
        "truncated_by_budget",  # 预算截断
        "data_quality",  # data_quality
    }
    for case in CASES:
        missing = required - set(case.expected)
        assert not missing, f"{case.case_id} 缺少关键字段断言：{missing}"


def test_all_six_rules_are_exercised() -> None:
    covered = {code for case in CASES for code in case.expect_findings}
    assert covered == RULE_CODES


def test_cases_are_deterministic() -> None:
    # 固定输入 → 固定输出（`ANALYSIS_CONTRACT §7.1`）
    case: GoldenCase = CASES[1]
    first = target_item(case, run_case(case)).model_dump()
    second = target_item(case, run_case(case)).model_dump()

    assert first == second


def test_cases_do_not_mutate_their_fixtures() -> None:
    # 夹具是模块级常量：任何用例都不得就地改写（否则用例间互相污染）
    before: list[Any] = [
        {"item": dict(case.item), "params": dict(case.params)} for case in CASES
    ]
    for case in CASES:
        run_case(case)

    after = [{"item": dict(case.item), "params": dict(case.params)} for case in CASES]
    assert before == after
