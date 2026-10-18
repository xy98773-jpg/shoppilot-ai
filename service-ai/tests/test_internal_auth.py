"""Internal authentication contract tests (``X-Internal-Token`` + ``X-Request-Id``).

Covers ``AI_SERVICE_CONTRACT.md §2:74-80`` (token mandatory, constant-time compare)
and the ``R91``/``R92`` rulings:
  - ``R92`` ①: an **unset/blank** token must *reject* (fail-closed), never skip the
    check (the old skeleton used ``if expected and ...`` — fail-open);
  - ``R91`` ①: a **missing** inbound ``X-Request-Id`` is not an error — the service
    generates one, echoes it, and logs a WARN.

The token is injected by monkeypatching the module-level ``config.settings``
singleton; ``core.security`` reads it through the module attribute on every call,
so no import-order tricks are needed.
"""
from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from shoppilot_ai.app.core import config
from shoppilot_ai.app.core.config import Settings
from shoppilot_ai.app.main import app

TOKEN = "b13-test-token"
PROTECTED_PATH = "/internal/v1/analysis:validate"
REQUEST_ID_HEADER = "X-Request-Id"
TOKEN_HEADER = "X-Internal-Token"


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


@pytest.fixture
def token_configured(monkeypatch: pytest.MonkeyPatch) -> str:
    """Configure a usable token for the duration of the test."""
    monkeypatch.setattr(config, "settings", Settings(ai_internal_token=TOKEN))
    return TOKEN


@pytest.fixture
def token_unset(monkeypatch: pytest.MonkeyPatch) -> None:
    """Leave the token unconfigured (``None``)."""
    monkeypatch.setattr(config, "settings", Settings(ai_internal_token=None))


@pytest.fixture
def token_blank(monkeypatch: pytest.MonkeyPatch) -> None:
    """Configure a whitespace-only token — must behave like "unconfigured"."""
    monkeypatch.setattr(config, "settings", Settings(ai_internal_token="   "))


def _assert_contract_envelope(payload: dict, expected_code: str) -> None:
    """Assert the four-field envelope required by ``AI_SERVICE_CONTRACT §5``."""
    assert set(payload.keys()) == {"code", "message", "detail", "request_id"}, payload
    assert payload["code"] == expected_code
    assert isinstance(payload["message"], str) and payload["message"]
    assert isinstance(payload["detail"], (dict, list))
    assert isinstance(payload["request_id"], str)


# --------------------------------------------------------------------------- #
# 1) Token not configured at all → fail-closed 401 AI-0001
# --------------------------------------------------------------------------- #
def test_token_not_configured_rejects_with_ai_0001(
    client: TestClient, token_unset: None
) -> None:
    resp = client.post(PROTECTED_PATH)
    assert resp.status_code == 401, resp.text
    body = resp.json()
    _assert_contract_envelope(body, "AI-0001")


# --------------------------------------------------------------------------- #
# 2) Blank token is equivalent to unconfigured (still fail-closed)
# --------------------------------------------------------------------------- #
def test_blank_token_is_treated_as_unconfigured(
    client: TestClient, token_blank: None
) -> None:
    resp = client.post(PROTECTED_PATH, headers={TOKEN_HEADER: "   "})
    assert resp.status_code == 401, resp.text
    assert resp.json()["code"] == "AI-0001"


# --------------------------------------------------------------------------- #
# 3) Configured + header missing → 401
# --------------------------------------------------------------------------- #
def test_missing_header_rejected(client: TestClient, token_configured: str) -> None:
    resp = client.post(PROTECTED_PATH)
    assert resp.status_code == 401, resp.text
    _assert_contract_envelope(resp.json(), "AI-0001")


# --------------------------------------------------------------------------- #
# 4) Configured + wrong value → 401
# --------------------------------------------------------------------------- #
def test_wrong_token_rejected(client: TestClient, token_configured: str) -> None:
    resp = client.post(PROTECTED_PATH, headers={TOKEN_HEADER: "definitely-wrong"})
    assert resp.status_code == 401, resp.text
    assert resp.json()["code"] == "AI-0001"


# --------------------------------------------------------------------------- #
# 5) Correct token passes the gate (no business route yet ⇒ 404/405, not 401)
# --------------------------------------------------------------------------- #
def test_correct_token_passes_gate(
    client: TestClient, token_configured: str
) -> None:
    resp = client.post(PROTECTED_PATH, headers={TOKEN_HEADER: token_configured})
    assert resp.status_code != 401, resp.text
    # The analysis router has no routes in this batch; 404 is the expected outcome.
    assert resp.status_code in (404, 405), resp.text


# --------------------------------------------------------------------------- #
# 6) Health stays token-exempt (ACCEPTANCE_CRITERIA:82 relies on it)
# --------------------------------------------------------------------------- #
def test_health_is_token_exempt(client: TestClient, token_configured: str) -> None:
    resp = client.get("/internal/v1/health")
    assert resp.status_code == 200, resp.text
    assert resp.json() == {"status": "UP"}


def test_health_is_token_exempt_even_when_unconfigured(
    client: TestClient, token_unset: None
) -> None:
    resp = client.get("/internal/v1/health")
    assert resp.status_code == 200, resp.text
    assert resp.json() == {"status": "UP"}


# --------------------------------------------------------------------------- #
# 7) 401 responses always carry X-Request-Id; an inbound id is echoed verbatim
# --------------------------------------------------------------------------- #
def test_error_response_echoes_request_id_header(
    client: TestClient, token_configured: str
) -> None:
    resp = client.post(PROTECTED_PATH)
    assert resp.status_code == 401
    assert resp.headers.get(REQUEST_ID_HEADER), "401 must echo X-Request-Id"

    resp2 = client.post(PROTECTED_PATH, headers={REQUEST_ID_HEADER: "req_test_abc"})
    assert resp2.status_code == 401
    assert resp2.headers.get(REQUEST_ID_HEADER) == "req_test_abc"


# --------------------------------------------------------------------------- #
# 8) body.request_id is byte-identical to the response header
# --------------------------------------------------------------------------- #
def test_body_request_id_equals_header(
    client: TestClient, token_configured: str
) -> None:
    resp = client.post(PROTECTED_PATH, headers={REQUEST_ID_HEADER: "req_test_abc"})
    assert resp.status_code == 401
    assert resp.json()["request_id"] == resp.headers[REQUEST_ID_HEADER]
    assert resp.json()["request_id"] == "req_test_abc"


# --------------------------------------------------------------------------- #
# 9) Missing request-id is generated, not an error (R91 ①)
# --------------------------------------------------------------------------- #
def test_missing_request_id_is_generated_not_an_error(
    client: TestClient, token_configured: str
) -> None:
    resp = client.post(PROTECTED_PATH)
    assert resp.status_code == 401, resp.text
    generated = resp.json()["request_id"]
    assert generated, "a missing inbound id must be generated, not left empty"
    # uuid4() is a valid UUID string; parse it to prove it is well-formed.
    assert uuid.UUID(generated)
    assert resp.headers[REQUEST_ID_HEADER] == generated
