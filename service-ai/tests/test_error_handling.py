"""R101 回归测试（M4-B16）：`ServiceAIBaseError` 通用处理器 + 19 码 + 五条既有路径。

背景：B15 结束时 `ServiceAIBaseError` 只有 ``InternalTokenError`` 一个专用处理器，
``AI-6001``(404) / ``AI-6002``(409) / ``AI-5001``(502) / ``AI-7001``(429) 等业务错误
会落 ``Exception → 500 AI-9001``，码与状态全丢（`DESIGN_REVIEW R101`）。

★ 测试**不**为生产 app 增加调试路由：四条业务码与 400/500 的验证在一个**独立的 FastAPI
实例**上完成，它复用生产 app 已注册的同一批处理器函数（``app.exception_handlers``）；
401/404/405 走生产 app 的真实中间件与真实路由表。
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel

from shoppilot_ai.app.core import config
from shoppilot_ai.app.core.config import Settings
from shoppilot_ai.app.core.errors import (
    AI_0001,
    AI_1001,
    AI_1002,
    AI_1003,
    AI_1004,
    AI_2001,
    AI_2002,
    AI_3001,
    AI_4001,
    AI_5001,
    AI_5002,
    AI_5003,
    AI_5004,
    AI_6001,
    AI_6002,
    AI_6003,
    AI_7001,
    AI_7002,
    AI_9001,
    DEFAULT_MESSAGES,
    HTTP_STATUS_BY_CODE,
    ServiceAIBaseError,
    error_body,
)
from shoppilot_ai.app.main import app, handle_service_ai_error

TOKEN = "b16-test-token"
TOKEN_HEADER = "X-Internal-Token"
REQUEST_ID_HEADER = "X-Request-Id"

ALL_CODES = [
    AI_0001,
    AI_1001,
    AI_1002,
    AI_1003,
    AI_1004,
    AI_2001,
    AI_2002,
    AI_3001,
    AI_4001,
    AI_5001,
    AI_5002,
    AI_5003,
    AI_5004,
    AI_6001,
    AI_6002,
    AI_6003,
    AI_7001,
    AI_7002,
    AI_9001,
]


# --------------------------------------------------------------------------- #
# 独立 FastAPI 实例：复用生产 app 的处理器，不污染生产路由表
# --------------------------------------------------------------------------- #
class _Payload(BaseModel):
    name: str


def _raise_contract_error(code: str) -> dict:
    raise ServiceAIBaseError(code=code, detail={"probe": True})


def _raise_unexpected() -> dict:
    raise RuntimeError("b16-intentional-boom")


def _echo_payload(payload: _Payload) -> dict:
    return {"ok": True}


@pytest.fixture
def probe_client() -> TestClient:
    probe = FastAPI()
    for exc_type, handler in app.exception_handlers.items():
        probe.add_exception_handler(exc_type, handler)
    probe.add_api_route("/__b16/raise/{code}", _raise_contract_error, methods=["POST"])
    probe.add_api_route("/__b16/boom", _raise_unexpected, methods=["POST"])
    probe.add_api_route("/__b16/echo", _echo_payload, methods=["POST"])
    # raise_server_exceptions=False → 500 走信封而不是把异常抛回测试进程
    return TestClient(probe, raise_server_exceptions=False)


@pytest.fixture
def prod_client(monkeypatch: pytest.MonkeyPatch) -> TestClient:
    monkeypatch.setattr(config, "settings", Settings(ai_internal_token=TOKEN))
    return TestClient(app, raise_server_exceptions=False)


def _assert_envelope(body: dict, code: str, detail: dict | None = None) -> None:
    assert set(body) == {"code", "message", "detail", "request_id"}, body
    assert body["code"] == code
    assert isinstance(body["message"], str) and body["message"]
    assert isinstance(body["request_id"], str)
    if detail is not None:
        assert body["detail"] == detail


# --------------------------------------------------------------------------- #
# 1) 19 码表本身（与 ERROR_CODES §2.11 对齐）
# --------------------------------------------------------------------------- #
def test_nineteen_codes_and_status_table_are_complete() -> None:
    assert len(DEFAULT_MESSAGES) == 19, "ERROR_CODES §2.11 是 19 条"
    assert set(DEFAULT_MESSAGES) == set(ALL_CODES)
    assert set(HTTP_STATUS_BY_CODE) == set(ALL_CODES)
    # 抽样核对 HTTP 列（完整映射见 errors.HTTP_STATUS_BY_CODE）
    assert HTTP_STATUS_BY_CODE[AI_0001] == 401
    assert HTTP_STATUS_BY_CODE[AI_1001] == 400
    assert HTTP_STATUS_BY_CODE[AI_1003] == 404
    assert HTTP_STATUS_BY_CODE[AI_1004] == 405
    assert HTTP_STATUS_BY_CODE[AI_2001] == 422
    assert HTTP_STATUS_BY_CODE[AI_5001] == 502
    assert HTTP_STATUS_BY_CODE[AI_5002] == 504
    assert HTTP_STATUS_BY_CODE[AI_6001] == 404
    assert HTTP_STATUS_BY_CODE[AI_6002] == 409
    assert HTTP_STATUS_BY_CODE[AI_7001] == 429
    assert HTTP_STATUS_BY_CODE[AI_7002] == 400
    assert HTTP_STATUS_BY_CODE[AI_9001] == 500


# --------------------------------------------------------------------------- #
# 2) R101 主证据：四条业务码的 HTTP 状态与四字段信封
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    ("code", "expected_status"),
    [
        (AI_6001, 404),
        (AI_6002, 409),
        (AI_5001, 502),
        (AI_7001, 429),
    ],
)
def test_contract_code_controls_http_status(
    probe_client: TestClient, code: str, expected_status: int
) -> None:
    resp = probe_client.post(
        f"/__b16/raise/{code}", headers={REQUEST_ID_HEADER: "req_b16_code"}
    )
    assert resp.status_code == expected_status, resp.text
    body = resp.json()
    _assert_envelope(body, code, detail={"probe": True})
    assert body["message"] == DEFAULT_MESSAGES[code]
    assert body["request_id"] == "req_b16_code"


def test_production_app_registers_generic_handler() -> None:
    """生产 app 必须真的注册了通用处理器（否则 R101 未闭环）。"""
    assert app.exception_handlers.get(ServiceAIBaseError) is handle_service_ai_error


def test_unknown_code_is_rejected_at_construction() -> None:
    """禁止自造错误码：未登记码直接 ValueError，不会被静默映射成 500。"""
    with pytest.raises(ValueError):
        ServiceAIBaseError(code="AI-9999")


def test_error_body_defaults_are_stable() -> None:
    body = error_body(code=AI_1001, message="boom")
    assert body == {
        "code": AI_1001,
        "message": "boom",
        "detail": {},
        "request_id": "",
    }


# --------------------------------------------------------------------------- #
# 3) 五条既有行为回归（401 / 400 / 404 / 405 / 500）
# --------------------------------------------------------------------------- #
def test_missing_token_still_401_ai_0001(prod_client: TestClient) -> None:
    resp = prod_client.post(
        "/internal/v1/analysis/runs", headers={REQUEST_ID_HEADER: "req_b16_401"}
    )
    assert resp.status_code == 401, resp.text
    _assert_envelope(resp.json(), AI_0001)
    assert resp.json()["request_id"] == "req_b16_401"
    assert resp.json()["request_id"] == resp.headers[REQUEST_ID_HEADER]


def test_validation_error_still_400_ai_1001(probe_client: TestClient) -> None:
    resp = probe_client.post(
        "/__b16/echo", json={}, headers={REQUEST_ID_HEADER: "req_b16_400"}
    )
    assert resp.status_code == 400, resp.text
    body = resp.json()
    _assert_envelope(body, AI_1001)
    assert body["request_id"] == "req_b16_400"
    assert "errors" in body["detail"]


def test_unknown_path_still_404_ai_1003(prod_client: TestClient) -> None:
    # ★ B19.2：analysis 路由已有 5 个端点，本条改钉一个**真正未定义**的路径。
    resp = prod_client.post(
        "/internal/v1/analysis/unknown",
        headers={TOKEN_HEADER: TOKEN, REQUEST_ID_HEADER: "req_b16_404"},
    )
    assert resp.status_code == 404, resp.text
    body = resp.json()
    _assert_envelope(body, AI_1003)
    assert body["detail"] == {"path": "/internal/v1/analysis/unknown", "method": "POST"}


def test_wrong_method_still_405_ai_1004(prod_client: TestClient) -> None:
    resp = prod_client.post(
        "/internal/v1/health", headers={REQUEST_ID_HEADER: "req_b16_405"}
    )
    assert resp.status_code == 405, resp.text
    _assert_envelope(resp.json(), AI_1004)
    assert resp.headers.get("allow") == "GET"


def test_unexpected_error_still_500_ai_9001(probe_client: TestClient) -> None:
    resp = probe_client.post(
        "/__b16/boom", headers={REQUEST_ID_HEADER: "req_b16_500"}
    )
    assert resp.status_code == 500, resp.text
    body = resp.json()
    _assert_envelope(body, AI_9001)
    assert body["request_id"] == "req_b16_500"
    assert "b16-intentional-boom" not in resp.text


def test_health_still_exempt_and_healthy(prod_client: TestClient) -> None:
    resp = prod_client.get("/internal/v1/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "UP"}


def test_validate_path_with_token_is_405_now_that_endpoint_exists(
    prod_client: TestClient,
) -> None:
    """`R107` 已由 `B19.2` 解除：`analysis:validate` 注册为 **POST**。

    ⇒ 带令牌的 **GET** 得到 `405 AI-1004`（路径存在、方法不匹配），正是本条
    docstring 在 B16 时预言的演进结果。POST 成功路径与 400/AI-1001 由
    `tests/test_analysis_endpoints.py` 覆盖。
    """
    resp = prod_client.get(
        "/internal/v1/analysis:validate",
        headers={TOKEN_HEADER: TOKEN, REQUEST_ID_HEADER: "req_b16_validate_get"},
    )
    assert resp.status_code == 405, resp.text
    body = resp.json()
    _assert_envelope(body, AI_1004)
    assert body["detail"] == {
        "path": "/internal/v1/analysis:validate",
        "method": "GET",
    }
