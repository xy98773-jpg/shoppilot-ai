"""Error-envelope contract tests (``AI_SERVICE_CONTRACT.md §5:639-660``).

Every error path must return exactly four fields: ``code`` / ``message`` /
``detail`` / ``request_id``. Two paths are exercised end-to-end through real
FastAPI machinery:

* ``RequestValidationError`` (Pydantic body validation) → ``400 AI-1001``
  — FastAPI's default is 422, which the contract forbids;
* an uncaught exception → ``500 AI-9001``.

Because this batch ships **no business endpoints**, the two routes below are
registered on the app inside a fixture and removed afterwards. They live under
``/internal/v1/__b13_*`` so they also exercise the token middleware with a valid
token (i.e. the handler is reached through the real middleware chain, not called
directly).
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from pydantic import BaseModel

from shoppilot_ai.app.core import config
from shoppilot_ai.app.core.config import Settings
from shoppilot_ai.app.main import app

TOKEN = "b13-test-token"
TOKEN_HEADER = "X-Internal-Token"
REQUEST_ID_HEADER = "X-Request-Id"
VALIDATE_PATH = "/internal/v1/__b13_validate__"
BOOM_PATH = "/internal/v1/__b13_boom__"


class _Payload(BaseModel):
    """Minimal body model: sending ``{}`` must trigger a validation error."""

    name: str


def _echo_payload(payload: _Payload) -> dict:
    return {"ok": True, "name": payload.name}


async def _raise_unexpected() -> dict:
    raise RuntimeError("b13-intentional-boom")


@pytest.fixture
def client() -> TestClient:
    # raise_server_exceptions=False: we want the 500 envelope, not a re-raise.
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def temp_routes(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(config, "settings", Settings(ai_internal_token=TOKEN))
    app.add_api_route(VALIDATE_PATH, _echo_payload, methods=["POST"])
    app.add_api_route(BOOM_PATH, _raise_unexpected, methods=["POST"])
    try:
        yield
    finally:
        app.router.routes[:] = [
            route
            for route in app.router.routes
            if not getattr(route, "path", "").startswith("/internal/v1/__b13_")
        ]


def _assert_envelope(payload: dict, expected_code: str) -> None:
    assert set(payload.keys()) == {"code", "message", "detail", "request_id"}, payload
    assert payload["code"] == expected_code
    assert isinstance(payload["message"], str) and payload["message"]
    assert isinstance(payload["request_id"], str) and payload["request_id"]


# --------------------------------------------------------------------------- #
# 1) Body validation failure → 400 AI-1001 (NOT FastAPI's default 422)
# --------------------------------------------------------------------------- #
def test_validation_error_returns_400_ai_1001(
    client: TestClient, temp_routes: None
) -> None:
    resp = client.post(
        VALIDATE_PATH,
        json={},
        headers={TOKEN_HEADER: TOKEN, REQUEST_ID_HEADER: "req_b13_validate"},
    )
    assert resp.status_code == 400, resp.text
    body = resp.json()
    _assert_envelope(body, "AI-1001")
    assert body["request_id"] == "req_b13_validate"
    assert body["request_id"] == resp.headers[REQUEST_ID_HEADER]


# --------------------------------------------------------------------------- #
# 2) Uncaught exception → 500 AI-9001
# --------------------------------------------------------------------------- #
def test_unexpected_error_returns_500_ai_9001(
    client: TestClient, temp_routes: None
) -> None:
    resp = client.post(
        BOOM_PATH,
        headers={TOKEN_HEADER: TOKEN, REQUEST_ID_HEADER: "req_b13_boom"},
    )
    assert resp.status_code == 500, resp.text
    body = resp.json()
    _assert_envelope(body, "AI-9001")
    assert body["request_id"] == "req_b13_boom"
    assert body["request_id"] == resp.headers[REQUEST_ID_HEADER]
    # The internal message must not leak the exception text / stack trace.
    assert "b13-intentional-boom" not in resp.text


# --------------------------------------------------------------------------- #
# 3) detail is a structured object (not null, not a bare string)
# --------------------------------------------------------------------------- #
def test_detail_is_structured_object(
    client: TestClient, temp_routes: None
) -> None:
    resp = client.post(
        VALIDATE_PATH, json={}, headers={TOKEN_HEADER: TOKEN}
    )
    assert resp.status_code == 400, resp.text
    detail = resp.json()["detail"]
    assert isinstance(detail, dict), detail
    assert "errors" in detail
    assert isinstance(detail["errors"], list) and detail["errors"]
    first = detail["errors"][0]
    assert set(first.keys()) == {"loc", "msg", "type"}


# --------------------------------------------------------------------------- #
# R95 —— 404 / 405 也必须走四字段信封（AI_SERVICE_CONTRACT §5 适用范围 = 全部响应）
#
# 这两条走的是**真实路由**（不注册临时路由）：
#   · /internal/v1/analysis:validate 在 B13/B14 之间尚无业务端点 ⇒ Starlette 抛 404；
#   · /internal/v1/health 只注册了 GET ⇒ 对它发 POST 触发 405。
# B13 时这两条会透出 Starlette 默认的 {"detail": "..."}（只有 1 个键），
# 项目负责人复核 R6/R7 因此判 FAIL。
# --------------------------------------------------------------------------- #
def test_unknown_internal_path_returns_404_ai_1003(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(config, "settings", Settings(ai_internal_token=TOKEN))
    # ★ B19.2：analysis 路由已注册 5 个端点，本条改钉**斜杠变体**
    #   `/analysis/validate`（Java 侧最常见的路径写错，`R93`）仍是「未定义路径」⇒ 404 AI-1003。
    #   端点存在性由 tests/test_analysis_endpoints.py 覆盖。
    resp = client.post(
        "/internal/v1/analysis/validate",
        headers={TOKEN_HEADER: TOKEN, REQUEST_ID_HEADER: "req_b131_404"},
    )
    assert resp.status_code == 404, resp.text
    body = resp.json()
    _assert_envelope(body, "AI-1003")
    # request_id 回显：响应体 == 响应头 == 入站值
    assert body["request_id"] == "req_b131_404"
    assert body["request_id"] == resp.headers[REQUEST_ID_HEADER]
    # detail 给定位信息（path/method），便于 Java 侧定位「路径常量写错」
    assert body["detail"] == {
        "path": "/internal/v1/analysis/validate",
        "method": "POST",
    }


def test_wrong_method_returns_405_ai_1004(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(config, "settings", Settings(ai_internal_token=TOKEN))
    resp = client.post(
        "/internal/v1/health", headers={REQUEST_ID_HEADER: "req_b131_405"}
    )
    assert resp.status_code == 405, resp.text
    body = resp.json()
    _assert_envelope(body, "AI-1004")
    assert body["request_id"] == "req_b131_405"
    assert body["detail"]["method"] == "POST"
    # 405 必须保留 Allow 头（协议语义，不被信封改造吞掉）
    assert resp.headers.get("allow") == "GET"
