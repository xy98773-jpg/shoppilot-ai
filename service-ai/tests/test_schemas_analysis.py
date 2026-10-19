"""M4-T06 Schema 测试：`AI_SERVICE_CONTRACT §3.2` 请求 / `§3.5` 结果。

三类断言：
1. **契约示例互证**（V3）：§3.2 的完整请求示例必须能 `model_validate`；§3.5 的结果示例
   必须能 `model_validate` 且 `model_dump()` 字段名与契约逐项一致。
2. **负例映射**：`date_from>date_to`→`AI-1001`、2001 items→`AI-7002`、缺 `sku_id`→`AI-2001`、
   `enable_llm=true` 缺 `prompt_version`→`AI-1001`、未知字段→Pydantic `ValidationError`
   （FastAPI 层会转成 `400 AI-1001`）。
3. **R112 正例**：缺 `warehouse_id` / `prompt_version`（`enable_llm=false`）必须通过。

★ 数值字段一律断言「字符串原样保留」——一旦某处写成 `float`，`"8.000"` 会变 `8.0`，
既丢精度也会让 LLM 数字溯源失效。
"""
from __future__ import annotations

from copy import deepcopy

import pytest
from pydantic import ValidationError

from shoppilot_ai.app.core.errors import AI_1001, AI_2001, AI_7002, ServiceAIBaseError
from shoppilot_ai.app.schemas.analysis_request import AnalyzeRequest, Params
from shoppilot_ai.app.schemas.analysis_response import RunResultResponse

# --------------------------------------------------------------------------- #
# 契约 §3.2 请求示例（逐字抄自 AI_SERVICE_CONTRACT.md）
# --------------------------------------------------------------------------- #
CREATE_EXAMPLE: dict = {
    "analysis_run_id": 30001,
    "team_id": 7,
    "warehouse_id": 100,
    "scope": {"scope_type": "SELECTED_SKUS", "sku_ids": [5001, 5002, 5003]},
    "date_from": "2026-09-16",
    "date_to": "2026-10-13",
    "params": {
        "window_days": 28,
        "min_days": 7,
        "coverage_days": 7,
        "lead_time_default": 7,
        "ss_ratio": "0.5000",
        "cv_high": "1.0000",
        "budget_limit": None,
        "enable_llm": True,
    },
    "algorithm_version": "avg-ma-3sigma-v1",
    "prompt_version": "explain_risk.v1",
    "model_name": "gpt-4o-mini",
    "items": [
        {
            "sku_id": 5001,
            "on_hand_qty": "8.000",
            "committed_qty": "0.000",
            "effective_in_transit_qty": "0.000",
            "lead_time_days": 7,
            "lead_time_source": "SKU",
            "safety_stock_override": None,
            "moq": "50.000",
            "order_multiple": "10.000",
            "qty_step": "1.000",
            "listed_date": "2026-09-01",
            "sales_series": [
                {"date": "2026-09-16", "qty": "6.000"},
                {"date": "2026-09-17", "qty": "5.000"},
            ],
        }
    ],
    "callback": None,
}

# --------------------------------------------------------------------------- #
# 契约 §3.5 结果示例（逐字抄自 AI_SERVICE_CONTRACT.md）
# --------------------------------------------------------------------------- #
RESULT_EXAMPLE: dict = {
    "code": "OK",
    "data": {
        "analysis_run_id": 30001,
        "execution_status": "COMPLETED",
        "algorithm_version": "avg-ma-3sigma-v1",
        "prompt_version": "explain_risk.v1",
        "model_name": "gpt-4o-mini",
        "llm_status": "TIMEOUT",
        "data_start_date": "2026-09-16",
        "data_end_date": "2026-10-13",
        "summary": {
            "total_skus": 3,
            "risk_sku_count": 2,
            "suggestion_count": 1,
            "truncated_count": 0,
            "by_risk_level": {"HIGH": 1, "UNKNOWN": 1, "NONE": 1},
        },
        "items": [
            {
                "sku_id": 5001,
                "warehouse_id": 100,
                "avg_daily_sales": "6.250",
                "sales_qty_total": "162.500",
                "valid_days": 26,
                "data_quality": "GOOD",
                "risk_level": "HIGH",
                "risk_types": ["STOCKOUT", "LOW_STOCK"],
                "risk_findings": [
                    {
                        "rule_code": "R-STOCKOUT",
                        "inputs": {"on_hand_qty": "8.000", "D": "6.250"},
                        "threshold": "on_hand_qty / D <= 2",
                        "result": "HIGH",
                    },
                    {
                        "rule_code": "R-LOWSTOCK",
                        "inputs": {"IP": "8.000", "D": "6.250", "L": 7, "R": 7},
                        "threshold": "IP < D * (L + R) = 87.500",
                        "result": "MEDIUM",
                    },
                ],
                "on_hand_qty": "8.000",
                "effective_in_transit_qty": "0.000",
                "committed_qty": "0.000",
                "inventory_position": "8.000",
                "safety_stock": "21.875",
                "lead_time_days": 7,
                "lead_time_source": "SKU",
                "coverage_days": 7,
                "cv": "0.4200",
                "volatility_adjusted": False,
                "q_base": "79.375",
                "moq_applied": "50.000",
                "order_multiple_applied": "10.000",
                "q_suggest": "80.000",
                "truncated_by_budget": False,
                "llm_explanation": None,
                "llm_evidence": [],
                "llm_status": "TIMEOUT",
                "warnings": ["LLM 解释超时，已降级为无解释结果"],
            },
            {
                "sku_id": 5003,
                "warehouse_id": 100,
                "avg_daily_sales": None,
                "sales_qty_total": "9.000",
                "valid_days": 3,
                "data_quality": "INSUFFICIENT",
                "risk_level": "UNKNOWN",
                "risk_types": ["DATA_INSUFFICIENT"],
                "risk_findings": [
                    {
                        "rule_code": "R-DATAINSUFFICIENT",
                        "inputs": {"valid_days": 3, "min_days": 7},
                        "threshold": "n < MIN_DAYS(7)",
                        "result": "UNKNOWN",
                    }
                ],
                "on_hand_qty": "0.000",
                "effective_in_transit_qty": "0.000",
                "committed_qty": "0.000",
                "inventory_position": "0.000",
                "safety_stock": None,
                "lead_time_days": 7,
                "lead_time_source": "DEFAULT",
                "coverage_days": 7,
                "cv": None,
                "volatility_adjusted": False,
                "q_base": None,
                "moq_applied": None,
                "order_multiple_applied": None,
                "q_suggest": None,
                "truncated_by_budget": False,
                "llm_explanation": None,
                "llm_evidence": [],
                "llm_status": "SKIPPED",
                "warnings": ["有效销售天数不足，未产出补货建议"],
            },
        ],
    },
    "request_id": "req_anl_003",
}

EXPECTED_DATA_KEYS = {
    "analysis_run_id",
    "execution_status",
    "algorithm_version",
    "prompt_version",
    "model_name",
    "llm_status",
    "data_start_date",
    "data_end_date",
    "summary",
    "items",
}

EXPECTED_SUMMARY_KEYS = {
    "total_skus",
    "risk_sku_count",
    "suggestion_count",
    "truncated_count",
    "by_risk_level",
}

EXPECTED_ITEM_KEYS = {
    "sku_id",
    "warehouse_id",
    "avg_daily_sales",
    "sales_qty_total",
    "valid_days",
    "data_quality",
    "risk_level",
    "risk_types",
    "risk_findings",
    "on_hand_qty",
    "effective_in_transit_qty",
    "committed_qty",
    "inventory_position",
    "safety_stock",
    "lead_time_days",
    "lead_time_source",
    "coverage_days",
    "cv",
    "volatility_adjusted",
    "q_base",
    "moq_applied",
    "order_multiple_applied",
    "q_suggest",
    "truncated_by_budget",
    "llm_explanation",
    "llm_evidence",
    "llm_status",
    "warnings",
}


# --------------------------------------------------------------------------- #
# 1) 契约示例互证（V3）
# --------------------------------------------------------------------------- #
def test_create_example_parses() -> None:
    request = AnalyzeRequest.model_validate(CREATE_EXAMPLE)
    assert request.analysis_run_id == 30001
    assert request.scope.sku_ids == [5001, 5002, 5003]
    assert request.items[0].sales_series[0].qty == "6.000"
    assert request.callback is None


def test_result_example_parses_and_matches_contract_fields() -> None:
    response = RunResultResponse.model_validate(RESULT_EXAMPLE)
    dumped = response.model_dump()

    assert set(dumped) == {"code", "data", "request_id"}
    assert set(dumped["data"]) == EXPECTED_DATA_KEYS
    assert set(dumped["data"]["summary"]) == EXPECTED_SUMMARY_KEYS
    assert len(dumped["data"]["items"]) == 2
    for item in dumped["data"]["items"]:
        assert set(item) == EXPECTED_ITEM_KEYS

    first = dumped["data"]["items"][0]
    assert first["on_hand_qty"] == "8.000"
    assert first["q_suggest"] == "80.000"
    assert first["cv"] == "0.4200"
    assert first["risk_findings"][0]["rule_code"] == "R-STOCKOUT"


# --------------------------------------------------------------------------- #
# 2) 负例
# --------------------------------------------------------------------------- #
def test_inverted_date_range_maps_to_ai_1001() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload["date_from"] = "2026-10-13"
    payload["date_to"] = "2026-09-16"

    with pytest.raises(ServiceAIBaseError) as info:
        AnalyzeRequest.model_validate(payload)
    assert info.value.code == AI_1001
    assert info.value.http_status == 400


def test_too_many_items_maps_to_ai_7002() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload["items"] = [deepcopy(CREATE_EXAMPLE["items"][0]) for _ in range(2001)]

    with pytest.raises(ServiceAIBaseError) as info:
        AnalyzeRequest.model_validate(payload)
    assert info.value.code == AI_7002
    assert info.value.http_status == 400
    assert info.value.detail["item_count"] == 2001


def test_missing_sku_id_maps_to_ai_2001() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload["items"][0].pop("sku_id")

    with pytest.raises(ServiceAIBaseError) as info:
        AnalyzeRequest.model_validate(payload)
    assert info.value.code == AI_2001
    assert info.value.http_status == 422


def test_missing_prompt_version_with_llm_maps_to_ai_1001() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload.pop("prompt_version")

    with pytest.raises(ServiceAIBaseError) as info:
        AnalyzeRequest.model_validate(payload)
    assert info.value.code == AI_1001
    assert info.value.detail["field"] == "prompt_version"


def test_unknown_field_rejected() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload["unexpected_field"] = "boom"

    with pytest.raises(ValidationError) as info:
        AnalyzeRequest.model_validate(payload)
    assert "unexpected_field" in str(info.value)


def test_sales_point_outside_window_rejected() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload["items"][0]["sales_series"][0]["date"] = "2026-01-01"

    with pytest.raises(ServiceAIBaseError) as info:
        AnalyzeRequest.model_validate(payload)
    assert info.value.code == AI_1001


def test_selected_skus_requires_sku_ids() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload["scope"] = {"scope_type": "SELECTED_SKUS", "sku_ids": []}

    with pytest.raises(ServiceAIBaseError) as info:
        AnalyzeRequest.model_validate(payload)
    assert info.value.code == AI_1001


def test_category_requires_category_value() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload["scope"] = {"scope_type": "CATEGORY"}

    with pytest.raises(ServiceAIBaseError) as info:
        AnalyzeRequest.model_validate(payload)
    assert info.value.code == AI_1001


def test_negative_quantity_string_rejected() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload["items"][0]["on_hand_qty"] = "-1.000"

    with pytest.raises(ValidationError):
        AnalyzeRequest.model_validate(payload)


# --------------------------------------------------------------------------- #
# 3) R112 正例 + 数值字符串原样保留
# --------------------------------------------------------------------------- #
def test_r112_optional_fields_accepted() -> None:
    payload = deepcopy(CREATE_EXAMPLE)
    payload.pop("warehouse_id")
    payload.pop("prompt_version")
    payload.pop("model_name")
    payload["params"]["enable_llm"] = False

    request = AnalyzeRequest.model_validate(payload)
    assert request.warehouse_id is None
    assert request.prompt_version is None
    assert request.model_name is None


def test_numeric_strings_are_preserved_verbatim() -> None:
    request = AnalyzeRequest.model_validate(CREATE_EXAMPLE)
    dumped = request.model_dump()

    assert dumped["items"][0]["on_hand_qty"] == "8.000"
    assert isinstance(dumped["items"][0]["on_hand_qty"], str)
    assert dumped["items"][0]["moq"] == "50.000"
    assert dumped["params"]["ss_ratio"] == "0.5000"
    assert isinstance(dumped["params"]["ss_ratio"], str)


def test_params_defaults_follow_design_basis() -> None:
    params = Params.model_validate({})
    assert params.window_days == 28
    assert params.min_days == 7
    assert params.coverage_days == 7
    assert params.lead_time_default == 7
    assert params.ss_ratio == "0.5000"
    assert params.cv_high == "1.0000"
    assert params.budget_limit is None
    assert params.enable_llm is True
