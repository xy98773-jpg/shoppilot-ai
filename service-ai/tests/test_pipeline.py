"""编排管道（M4-T11）：步骤顺序 / 异常映射 / 取消 / 结果组装 / 幂等哈希。

口径：`DESIGN_REVIEW.md §5.32` 裁定 #1~#14。
"""
from __future__ import annotations

from datetime import date, timedelta

import pytest

from shoppilot_ai.app.orchestration import pipeline as pipeline_module
from shoppilot_ai.app.orchestration.pipeline import (
    STEPS,
    build_summary,
    null_narrative,
    request_hash,
    run_analysis,
)
from shoppilot_ai.app.orchestration.progress import RunRegistry
from shoppilot_ai.app.schemas.analysis_request import AnalyzeRequest, Item, Params

START = date(2026, 1, 1)
DATE_TO = START + timedelta(days=27)


# --------------------------------------------------------------------------- #
# 夹具
# --------------------------------------------------------------------------- #
def make_item(
    sku_id: int,
    *,
    on_hand: str = "200.000",
    moq: str | None = None,
    order_multiple: str | None = None,
    committed: str = "0.000",
    transit: str = "0.000",
    lead_time_days: int = 7,
    quantities: list[str] | None = None,
) -> Item:
    if quantities is None:
        quantities = ["6.000"] * 21 + ["7.000"] * 7
    return Item.model_validate(
        {
            "sku_id": sku_id,
            "on_hand_qty": on_hand,
            "committed_qty": committed,
            "effective_in_transit_qty": transit,
            "lead_time_days": lead_time_days,
            "lead_time_source": "SKU",
            "moq": moq,
            "order_multiple": order_multiple,
            "sales_series": [
                {"date": (START + timedelta(days=index)).isoformat(), "qty": qty}
                for index, qty in enumerate(quantities)
            ],
        }
    )


def make_request(
    items: list[Item] | None = None,
    *,
    enable_llm: bool = False,
    prompt_version: str | None = None,
    warehouse_id: int | None = 100,
    budget_limit: str | None = None,
    coverage_days: int = 7,
    window_days: int = 28,
    run_id: int = 30001,
) -> AnalyzeRequest:
    payload = {
        "analysis_run_id": run_id,
        "team_id": 7,
        "scope": {"scope_type": "SELECTED_SKUS", "sku_ids": [item.sku_id for item in (items or [])]},
        "date_from": START.isoformat(),
        "date_to": DATE_TO.isoformat(),
        "params": Params.model_validate(
            {
                "window_days": window_days,
                "min_days": 7,
                "coverage_days": coverage_days,
                "enable_llm": enable_llm,
                "budget_limit": budget_limit,
            }
        ).model_dump(),
        "algorithm_version": "avg-ma-3sigma-v1",
        "prompt_version": prompt_version,
        "model_name": "gpt-4o-mini",
        "items": [item.model_dump() for item in (items or [])],
    }
    if warehouse_id is not None:
        payload["warehouse_id"] = warehouse_id
    return AnalyzeRequest.model_validate(payload)


def default_items() -> list[Item]:
    # 9002：库存充足 ⇒ NONE / 无建议；9003：缺货 ⇒ HIGH / 建议 110
    return [
        make_item(9002, on_hand="200.000"),
        make_item(9003, on_hand="8.000", moq="50.000", order_multiple="10.000"),
    ]


class SpyRegistry(RunRegistry):
    """记录每次 `update_step` 调用（步骤、progress、processed_skus）。"""

    def __init__(self) -> None:
        super().__init__()
        self.calls: list[tuple[str, int, int | None]] = []

    def update_step(self, run_id, step, processed_skus=None):
        record = super().update_step(run_id, step, processed_skus)
        self.calls.append((step, record.progress if record else -1, processed_skus))
        return record


class CancelAtStatsRegistry(RunRegistry):
    """进入 STATS 之前打取消标记（模拟 B19.2 的 cancel 端点）。"""

    def update_step(self, run_id, step, processed_skus=None):
        if step == "STATS":
            self.request_cancel(run_id)
        return super().update_step(run_id, step, processed_skus)


# --------------------------------------------------------------------------- #
# D1 步骤与进度
# --------------------------------------------------------------------------- #
def test_steps_are_reported_in_fixed_order_with_fixed_progress() -> None:
    request = make_request(default_items())
    registry = SpyRegistry()
    registry.register(request.analysis_run_id, team_id=7, total_skus=len(request.items))

    result = run_analysis(request, registry)

    assert result is not None
    assert STEPS == (
        "VALIDATING",
        "STATS",
        "RISK_RULES",
        "REPLENISHMENT",
        "LLM_EXPLAIN",
        "DONE",
    )
    first_seen = list(dict.fromkeys(step for step, _, _ in registry.calls))
    assert first_seen == list(STEPS)
    assert [progress for _, progress, _ in registry.calls][:1] == [5]
    progresses_of_first_seen = [next(p for s, p, _ in registry.calls if s == step) for step in STEPS]
    assert progresses_of_first_seen == [5, 20, 40, 60, 80, 100]


def test_processed_skus_reported_per_item_inside_replenishment() -> None:
    request = make_request(default_items())
    registry = SpyRegistry()
    registry.register(
        request.analysis_run_id, team_id=7, total_skus=len(request.items)
    )

    run_analysis(request, registry)

    replenishment_calls = [processed for step, _, processed in registry.calls if step == "REPLENISHMENT"]
    assert replenishment_calls == [0, 1, 2]
    record = registry.get(request.analysis_run_id)
    assert record.processed_skus == len(request.items)
    assert record.total_skus == len(request.items)


def test_progress_does_not_change_inside_a_step() -> None:
    request = make_request(default_items())
    registry = SpyRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    run_analysis(request, registry)

    progresses = {step: {p for s, p, _ in registry.calls if s == step} for step in STEPS}
    for step, values in progresses.items():
        assert len(values) == 1, f"{step} 步内 progress 不得变化：{values}"


def test_registry_ends_in_completed_with_result() -> None:
    request = make_request(default_items())
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry)

    record = registry.get(request.analysis_run_id)
    assert record.execution_status == "COMPLETED"
    assert record.progress == 100
    assert record.current_step == "DONE"
    assert record.error is None
    assert record.result is not None
    assert record.result.data.analysis_run_id == request.analysis_run_id


# --------------------------------------------------------------------------- #
# D2 全部不可分析
# --------------------------------------------------------------------------- #
def test_all_items_insufficient_fails_with_ai_2002() -> None:
    items = [make_item(9002, on_hand="10.000", quantities=[])]
    request = make_request(items)
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry)

    assert result is None
    record = registry.get(request.analysis_run_id)
    assert record.execution_status == "FAILED"
    assert record.error["code"] == "AI-2002"
    assert record.error["message"] == "窗口内销售数据不足"
    assert record.error["detail"]["stage"] == "VALIDATING"
    assert record.error["detail"]["total_skus"] == 1
    assert record.error["detail"]["min_days"] == 7
    assert record.result is None


def test_mixed_insufficient_items_still_produce_results() -> None:
    # §5.32 #4 只在「**全部**不可分析」时失败；部分不足照常出结果，
    # 不足的那条 item 自己带 `DATA_INSUFFICIENT` 且 `q_suggest=null`
    items = [make_item(9002, quantities=[]), make_item(9003, on_hand="8.000")]
    request = make_request(items)
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    data = run_analysis(request, registry).data

    assert data.execution_status == "COMPLETED"
    assert data.items[0].risk_types == ["DATA_INSUFFICIENT"]
    assert data.items[0].q_suggest is None
    assert data.items[0].data_quality == "INSUFFICIENT"
    assert data.items[1].risk_level == "HIGH"
    assert data.summary.total_skus == 2


# --------------------------------------------------------------------------- #
# D3/D4 异常映射
# --------------------------------------------------------------------------- #
def test_rule_exception_maps_to_ai_3001(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(*args, **kwargs):
        raise RuntimeError("规则计算炸了")

    monkeypatch.setattr(pipeline_module, "evaluate_items", boom)
    request = make_request(default_items())
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry)

    assert result is None
    error = registry.get(request.analysis_run_id).error
    assert error["code"] == "AI-3001"
    assert error["detail"]["stage"] == "RISK_RULES"
    assert "规则计算炸了" in error["detail"]["reason"]


def test_assembly_exception_maps_to_ai_4001(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(*args, **kwargs):
        raise ValueError("结果组装炸了")

    monkeypatch.setattr(pipeline_module, "ResultItem", boom)
    request = make_request(default_items())
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry)

    assert result is None
    error = registry.get(request.analysis_run_id).error
    assert error["code"] == "AI-4001"
    assert error["detail"]["stage"] == "REPLENISHMENT"
    assert "结果组装炸了" in error["detail"]["reason"]


def test_data_quality_exception_maps_to_ai_3001(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(*args, **kwargs):
        raise RuntimeError("预检炸了")

    monkeypatch.setattr(pipeline_module, "assess", boom)
    request = make_request(default_items())
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    assert run_analysis(request, registry) is None
    error = registry.get(request.analysis_run_id).error
    assert error["code"] == "AI-3001"
    assert error["detail"]["stage"] == "VALIDATING"


def test_missing_warehouse_id_fails_closed() -> None:
    request = make_request(default_items(), warehouse_id=None)
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry)

    assert result is None
    error = registry.get(request.analysis_run_id).error
    assert error["code"] == "AI-1001"
    assert error["detail"]["field"] == "warehouse_id"
    assert "默认仓" in error["detail"]["reason"]


# --------------------------------------------------------------------------- #
# D5 正常路径
# --------------------------------------------------------------------------- #
def test_normal_path_builds_expected_result() -> None:
    request = make_request(default_items())
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry)

    assert result.code == "OK"
    data = result.data
    assert data.execution_status == "COMPLETED"
    assert data.algorithm_version == "avg-ma-3sigma-v1"
    assert data.prompt_version is None
    assert data.model_name == "gpt-4o-mini"
    assert data.llm_status == "SKIPPED"
    assert data.data_start_date == request.date_from
    assert data.data_end_date == request.date_to

    assert data.summary.total_skus == 2
    assert data.summary.risk_sku_count == 1
    assert data.summary.suggestion_count == 1
    assert data.summary.truncated_count == 0
    assert data.summary.by_risk_level == {"NONE": 1, "HIGH": 1}

    assert [item.sku_id for item in data.items] == [9002, 9003]
    assert all(item.llm_status == "SKIPPED" for item in data.items)
    assert all(item.llm_explanation is None for item in data.items)
    assert all(item.llm_evidence == [] for item in data.items)
    assert data.items[1].q_suggest == "110.000"


def test_by_risk_level_only_contains_present_levels() -> None:
    items = [make_item(9002, on_hand="200.000"), make_item(9003, on_hand="8.000")]
    request = make_request(items)
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    summary = run_analysis(request, registry).data.summary

    assert set(summary.by_risk_level) == {"NONE", "HIGH"}
    assert all(count > 0 for count in summary.by_risk_level.values())


def test_summary_counts_risk_and_suggestion_levels() -> None:
    items = [
        make_item(9002, on_hand="200.000"),  # NONE，无建议
        make_item(9003, on_hand="8.000"),  # HIGH，有建议
        make_item(9004, on_hand="95.000", quantities=["1.000"] * 28),  # MEDIUM(OVERSTOCK)，无建议
    ]
    request = make_request(items)
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    summary = run_analysis(request, registry).data.summary

    assert summary.total_skus == 3
    assert summary.risk_sku_count == 2
    assert summary.suggestion_count == 1
    assert summary.by_risk_level == {"NONE": 1, "HIGH": 1, "MEDIUM": 1}


def test_build_summary_is_reusable_and_pure() -> None:
    request = make_request(default_items())
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)
    items = run_analysis(request, registry).data.items

    first = build_summary(items)
    second = build_summary(items)

    assert first.model_dump() == second.model_dump()


# --------------------------------------------------------------------------- #
# D6 LLM 步骤
# --------------------------------------------------------------------------- #
def test_llm_disabled_has_no_run_warning() -> None:
    request = make_request(default_items(), enable_llm=False)
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry)

    assert result.data.llm_status == "SKIPPED"
    assert registry.get(request.analysis_run_id).warnings == []


def test_llm_enabled_but_unimplemented_adds_warning() -> None:
    """`§5.32 #3`：声明要解释却没产出 ⇒ run 级 warning（**不静默**）。

    ★ M8-T07（R155）文案订正：原断言写死「LLM 解释未实现（M5）」已过时——
      LLM 解释现已实现，此处 `SKIPPED` 的原因是**未注入 narrative**（用默认
      `null_narrative`）。新文案改为中性表述，故这里按片段匹配而非整句。
    """
    request = make_request(default_items(), enable_llm=True, prompt_version="explain_risk.v1")
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry)

    record = registry.get(request.analysis_run_id)
    assert result.data.llm_status == "SKIPPED"
    assert any("LLM 解释未产出" in w for w in record.warnings), record.warnings


def test_custom_narrative_is_called_and_explanations_are_written() -> None:
    request = make_request(default_items(), enable_llm=True, prompt_version="explain_risk.v1")
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)
    calls: list[tuple[int, int]] = []

    def narrative(items, req):
        calls.append((len(items), req.analysis_run_id))
        return "SUCCEEDED", {"9003": "9003 近 28 天日均 6.25，库存仅够 1.3 天"}

    result = run_analysis(request, registry, narrative)

    assert calls == [(2, request.analysis_run_id)]
    assert result.data.llm_status == "SUCCEEDED"
    assert result.data.items[1].llm_explanation.startswith("9003 近 28 天")
    assert result.data.items[0].llm_explanation is None
    assert all(item.llm_status == "SUCCEEDED" for item in result.data.items)
    # 真实 narrative 成功 ⇒ 不得再写「未实现（M5）」的假 warning
    assert "LLM 解释未实现（M5）" not in registry.get(request.analysis_run_id).warnings


def test_narrative_failure_keeps_rule_results() -> None:
    request = make_request(default_items(), enable_llm=True, prompt_version="explain_risk.v1")
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    def narrative(items, req):
        raise RuntimeError("LLM 502")

    result = run_analysis(request, registry, narrative)

    assert result.data.llm_status == "FAILED"
    assert result.data.items[1].q_suggest == "110.000"
    assert result.data.summary.suggestion_count == 1
    assert any("规则结果保留" in w for w in registry.get(request.analysis_run_id).warnings)


def test_invalid_llm_status_is_normalized_to_failed() -> None:
    request = make_request(default_items())
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry, lambda items, req: ("GREAT", {}))

    assert result.data.llm_status == "FAILED"
    assert any("非法状态" in w for w in registry.get(request.analysis_run_id).warnings)


def test_default_narrative_is_null_narrative() -> None:
    assert null_narrative([], make_request(default_items())) == ("SKIPPED", {})
    assert pipeline_module.NullNarrative is null_narrative


# --------------------------------------------------------------------------- #
# D7 取消
# --------------------------------------------------------------------------- #
def test_cancel_before_stats_produces_cancelled_without_result() -> None:
    request = make_request(default_items())
    registry = CancelAtStatsRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    result = run_analysis(request, registry)

    assert result is None
    record = registry.get(request.analysis_run_id)
    assert record.execution_status == "CANCELLED"
    assert record.result is None
    assert record.error is None
    assert record.finished_at is not None


def test_cancel_requested_mid_step_takes_effect_at_next_step_boundary() -> None:
    # §5.32 #13：取消在「每步开始前」检查。步内（REPLENISHMENT 逐条上报）被打的
    # 标记要等下一个步骤边界才生效 ⇒ 本步剩余 item 仍会处理完，processed_skus=2
    request = make_request(default_items())

    class CancelAfterFirstItem(RunRegistry):
        def update_step(self, run_id, step, processed_skus=None):
            record = super().update_step(run_id, step, processed_skus)
            if step == "REPLENISHMENT" and processed_skus == 1:
                self.request_cancel(run_id)
            return record

    registry = CancelAfterFirstItem()
    registry.register(request.analysis_run_id, team_id=7)

    assert run_analysis(request, registry) is None
    record = registry.get(request.analysis_run_id)
    assert record.execution_status == "CANCELLED"
    assert record.processed_skus == 2
    assert record.current_step == "REPLENISHMENT"
    assert record.progress == 60


# --------------------------------------------------------------------------- #
# D10 幂等哈希
# --------------------------------------------------------------------------- #
def test_request_hash_is_stable_for_identical_requests() -> None:
    first = make_request(default_items())
    second = make_request(default_items())

    assert request_hash(first) == request_hash(second)
    assert len(request_hash(first)) == 16


def test_request_hash_differs_when_body_differs() -> None:
    base = make_request(default_items())
    baseline = request_hash(base)

    changed_date = make_request(default_items())
    changed_date.date_from = date(2025, 12, 1)

    changed_budget = make_request(default_items(), budget_limit="500.000")

    changed_series = make_request(
        [make_item(9002, on_hand="200.000"), make_item(9003, on_hand="9.000", moq="50.000", order_multiple="10.000")]
    )

    assert request_hash(changed_date) != baseline
    assert request_hash(changed_budget) != baseline
    assert request_hash(changed_series) != baseline


def test_request_hash_distinguishes_omitted_and_explicit_null() -> None:
    # `warehouse_id` 省略 = 默认仓；显式 null 不是同一请求（幂等语义不能抹平）
    omitted = make_request(default_items())
    explicit_null = make_request(default_items(), warehouse_id=None)
    with_value = make_request(default_items())

    assert request_hash(omitted) == request_hash(with_value)
    assert request_hash(explicit_null) != request_hash(omitted)


# --------------------------------------------------------------------------- #
# 调用约定
# --------------------------------------------------------------------------- #
def test_pipeline_without_registration_still_returns_result_but_records_nothing() -> None:
    # 调用方（B19.2）必须先 register；未注册时状态写入全部落空（不抛异常）
    request = make_request(default_items())
    registry = RunRegistry()

    result = run_analysis(request, registry)

    assert result is not None
    assert registry.get(request.analysis_run_id) is None


def test_pipeline_does_not_mutate_the_request() -> None:
    request = make_request(default_items())
    before = request.model_dump()
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    run_analysis(request, registry)

    assert request.model_dump() == before


def test_budget_truncation_flows_into_summary() -> None:
    items = [make_item(9002, on_hand="200.000"), make_item(9003, on_hand="8.000", moq="50.000", order_multiple="10.000")]
    request = make_request(items, budget_limit="50.000")
    registry = RunRegistry()
    registry.register(request.analysis_run_id, team_id=7)

    data = run_analysis(request, registry).data

    assert data.items[1].q_suggest == "50.000"
    assert data.items[1].truncated_by_budget is True
    assert data.summary.truncated_count == 1
    assert data.summary.suggestion_count == 1
