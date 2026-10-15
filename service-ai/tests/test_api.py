"""API smoke tests.

The health endpoint is token-exempt (see app/main.py), so this test runs
without setting AI_INTERNAL_TOKEN.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from shoppilot_ai.app.main import app


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def test_health_returns_up(client: TestClient) -> None:
    resp = client.get("/internal/v1/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "UP"}


def test_health_sets_request_id_header(client: TestClient) -> None:
    resp = client.get("/internal/v1/health")
    assert resp.status_code == 200
    assert "X-Request-Id" in resp.headers
