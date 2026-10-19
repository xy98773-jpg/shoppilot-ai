"""5 个内部端点的端到端测试（M4-B19.2，TestClient 打真实路由表）。

口径：`AI_SERVICE_CONTRACT §3.1/§3.2/§3.4/§3.5/§3.6/§6.2` +
`DESIGN_REVIEW §5.34` 裁定 1~12。

★ 成功路径**必须**手工构造带 `items` 且每条 ≥ `min_days` 天销售记录的合成请求：
  Java 侧当前恒发空 `items`（`R120`），真实端到端 `202 → COMPLETED` 要等装配落地。
"""
from __future__ import annotations

from datetime import date, timedelta

import pytest
from fastapi.testclient import TestClient

from shoppilot_ai.app.api import analysis as analysis_api
from shoppilot_ai.app.core import config
from shoppilot_ai.app.core.config import Settings
from shoppilot_ai.app.main import app
from shoppilot_ai.app.orchestration.progress import RunRegistry

TOKEN = "b192-test-token"
AUTH = {"X-Internal-Token": TOKEN}
RID = {"X-Request-Id": "req_b192_001"}

START = date(2026, 1, 1)
DATE_TO = START + timedelta(days=27)

BASE = "/internal/v1"


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):
    """带内部令牌的生产 app + **每个测试独立**的注册表单例。"""
    original = config.settings
    config.settings = Settings(ai_internal_token=TOKEN)
    monkeypatch.setattr(analysis_api, "registry", RunRegistry())
    with TestClient(app) as test_client:
        yield test_client
    config.settings = original


# --------------------------------------------------------------------------- #
# 夹具
# --------------------------------------------------------------------------- #
def make_item(sku_id: int, days: int = 28, on_hand: str = "200.000") -> dict:
    return {
        "sku_id": sku_id,
        "on_hand_qty": on_hand,
        "committed_qty": "0.000",
        "effective_in_transit_qty": "0.000",
        "lead_time_days": 7,
        "lead_time_source": "SKU",
        "sales_series": [
            {
                "date": (START + timedelta(days=index)).isoformat(),
                "qty": "6.000" if index < 21 else "7.000",
            }
            for index in range(days)
        ],
    }


def validate_payload(items: list[dict] | None = None, **overrides) -> dict:
    payload = {
        "team_id": 7,
        "warehouse_id": 100,
        "scope": {"scope_type": "SELECTED_SKUS", "sku_ids": [5001, 5002]},
        "date_from": START.isoformat(),
        "date_to": DATE_TO.isoformat(),
        "params": {"window_days": 28, "min_days": 7},
        "items": items if items is not None else [make_item(5001, 28), make_item(5002, 3)],
    }
    payload.update(overrides)
    return payload


def run_payload(run_id: int = 30001, items: list[dict] | None = None, **overrides) -> dict:
    payload = {
        "analysis_run_id": run_id,
        "team_id": 7,
        "warehouse_id": 100,
        "scope": {"scope_type": "SELECTED_SKUS", "sku_ids": [5001, 5002]},
        "date_from": START.isoformat(),
        "date_to": DATE_TO.isoformat(),
        "params": {"window_days": 28, "min_days": 7, "enable_llm": False},
        "algorithm_version": "avg-ma-3sigma-v1",
        "items": items if items is not None else [make_item(5001, 28), make_item(5002, 28)],
    }
    payload.update(overrides)
    return payload


def assert_envelope(response, expected_code: str = "OK") -> dict:
    body = response.json()
    assert body["code"] == expected_code
    assert body["request_id"] == response.headers["X-Request-Id"]
    assert body["request_id"] != ""
    return body


# --------------------------------------------------------------------------- #
# C1 预检
# --------------------------------------------------------------------------- #
def test_validate_returns_quality_summary(client: TestClient) -> None:
    response = client.post(f"{BASE}/analysis:validate", json=validate_payload(), headers=AUTH)

    assert response.status_code == 200
    body = assert_envelope(response)
    data = body["data"]
    assert data["analyzable"] is True
    assert data["total_skus"] == 2
    assert data["analyzable_skus"] == 1
    assert data["insufficient_skus"] == 1
    assert data["window_days"] == 28
    assert [row["sku_id"] for row in data["per_sku"]] == [5001, 5002]
    assert data["per_sku"][1]["quality"] == "INSUFFICIENT"
    assert data["warnings"]


def test_validate_works_without_warehouse_id(client: TestClient) -> None:
    payload = validate_payload()
    payload.pop("warehouse_id")

    response = client.post(f"{BASE}/analysis:validate", json=payload, headers=AUTH)

    assert response.status_code == 200
    assert_envelope(response)


def test_validate_rejects_bad_body(client: TestClient) -> None:
    response = client.post(
        f"{BASE}/analysis:validate", json=validate_payload(team_id=0), headers=AUTH
    )

    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "AI-1001"
    assert set(body) == {"code", "message", "detail", "request_id"}
    assert body["request_id"] == response.headers["X-Request-Id"]


def test_validate_rejects_too_many_items(client: TestClient) -> None:
    items = [make_item(9000 + index, 0) for index in range(2001)]

    response = client.post(f"{BASE}/analysis:validate", json=validate_payload(items), headers=AUTH)

    assert response.status_code == 400
    assert response.json()["code"] == "AI-7002"


def test_validate_requires_internal_token(client: TestClient) -> None:
    response = client.post(f"{BASE}/analysis:validate", json=validate_payload())

    assert response.status_code == 401
    assert response.json()["code"] == "AI-0001"


# --------------------------------------------------------------------------- #
# C2 创建成功路径
# --------------------------------------------------------------------------- #
def test_create_run_returns_202_and_completes(client: TestClient) -> None:
    response = client.post(f"{BASE}/analysis/runs", json=run_payload(), headers=AUTH)

    assert response.status_code == 202
    body = assert_envelope(response)
    assert body["data"]["analysis_run_id"] == 30001
    assert body["data"]["execution_status"] == "ACCEPTED"
    assert body["data"]["accepted_at"].endswith("+00:00") or body["data"]["accepted_at"].endswith("Z")

    status = client.get(f"{BASE}/analysis/runs/30001", headers=AUTH)
    assert status.status_code == 200
    data = assert_envelope(status)["data"]
    assert data["execution_status"] == "COMPLETED"
    assert data["progress"] == 100
    assert data["current_step"] == "DONE"
    assert data["processed_skus"] == 2
    assert data["llm_status"] == "SKIPPED"
    assert data["error"] is None


def test_result_endpoint_returns_full_result(client: TestClient) -> None:
    client.post(f"{BASE}/analysis/runs", json=run_payload(), headers=AUTH)

    response = client.get(f"{BASE}/analysis/runs/30001/result", headers=AUTH)

    assert response.status_code == 200
    data = assert_envelope(response)["data"]
    assert data["analysis_run_id"] == 30001
    assert data["execution_status"] == "COMPLETED"
    assert data["data_start_date"] == START.isoformat()
    assert data["data_end_date"] == DATE_TO.isoformat()
    assert data["summary"]["total_skus"] == 2
    assert len(data["items"]) == 2
    assert all(item["llm_status"] == "SKIPPED" for item in data["items"])


def test_status_request_id_echoes_inbound_header(client: TestClient) -> None:
    client.post(f"{BASE}/analysis/runs", json=run_payload(), headers=AUTH)

    response = client.get(f"{BASE}/analysis/runs/30001", headers={**AUTH, **RID})

    assert response.json()["request_id"] == "req_b192_001"
    assert response.headers["X-Request-Id"] == "req_b192_001"


# --------------------------------------------------------------------------- #
# C3 幂等
# --------------------------------------------------------------------------- #
def test_same_body_replay_returns_202_without_rerun(client: TestClient) -> None:
    first = client.post(f"{BASE}/analysis/runs", json=run_payload(), headers=AUTH)
    assert first.status_code == 202
    before = client.get(f"{BASE}/analysis/runs/30001", headers=AUTH).json()["data"]

    second = client.post(f"{BASE}/analysis/runs", json=run_payload(), headers=AUTH)

    assert second.status_code == 202
    replay = assert_envelope(second)["data"]
    assert replay["execution_status"] == "COMPLETED"

    after = client.get(f"{BASE}/analysis/runs/30001", headers=AUTH).json()["data"]
    assert after["started_at"] == before["started_at"], "重放不得重新执行"
    assert after["finished_at"] == before["finished_at"]
    assert len(analysis_api.get_registry().snapshot()) == 1


def test_different_body_same_run_id_conflicts(client: TestClient) -> None:
    client.post(f"{BASE}/analysis/runs", json=run_payload(), headers=AUTH)

    changed = run_payload()
    changed["date_from"] = (START - timedelta(days=1)).isoformat()
    response = client.post(f"{BASE}/analysis/runs", json=changed, headers=AUTH)

    assert response.status_code == 409
    body = response.json()
    assert body["code"] == "AI-1002"
    assert len(body["detail"]["existing_hash"]) == 8
    assert len(body["detail"]["incoming_hash"]) == 8
    assert body["detail"]["existing_hash"] != body["detail"]["incoming_hash"]
    assert len(analysis_api.get_registry().snapshot()) == 1


# --------------------------------------------------------------------------- #
# C4 状态端点
# --------------------------------------------------------------------------- #
def test_status_unknown_run_returns_404(client: TestClient) -> None:
    response = client.get(f"{BASE}/analysis/runs/999", headers=AUTH)

    assert response.status_code == 404
    assert response.json()["code"] == "AI-6001"


def test_status_accepted_phase_has_null_step_and_zero_progress(client: TestClient) -> None:
    analysis_api.get_registry().register(4242, team_id=7, request_hash="h", total_skus=3)

    data = client.get(f"{BASE}/analysis/runs/4242", headers=AUTH).json()["data"]

    assert data["execution_status"] == "ACCEPTED"
    assert data["current_step"] is None
    assert data["progress"] == 0
    assert data["processed_skus"] == 0
    assert data["total_skus"] == 3
    assert data["started_at"] is None
    assert data["finished_at"] is None
    assert data["error"] is None


# --------------------------------------------------------------------------- #
# C5 结果端点四分支
# --------------------------------------------------------------------------- #
def test_result_unknown_run_returns_404(client: TestClient) -> None:
    response = client.get(f"{BASE}/analysis/runs/999/result", headers=AUTH)

    assert response.status_code == 404
    assert response.json()["code"] == "AI-6001"


def test_result_not_ready_returns_409_ai_6002(client: TestClient) -> None:
    analysis_api.get_registry().register(5150, team_id=7, total_skus=1)

    response = client.get(f"{BASE}/analysis/runs/5150/result", headers=AUTH)

    assert response.status_code == 409
    body = response.json()
    assert body["code"] == "AI-6002"
    assert body["detail"]["execution_status"] == "ACCEPTED"


def test_result_cancelled_returns_409_ai_6003(client: TestClient) -> None:
    run_registry = analysis_api.get_registry()
    run_registry.register(6160, team_id=7, total_skus=1)
    run_registry.request_cancel(6160)
    run_registry.mark_cancelled(6160)

    response = client.get(f"{BASE}/analysis/runs/6160/result", headers=AUTH)

    assert response.status_code == 409
    assert response.json()["code"] == "AI-6003"


def test_result_failed_run_returns_409_ai_6002_with_error(client: TestClient) -> None:
    run_registry = analysis_api.get_registry()
    run_registry.register(7170, team_id=7, total_skus=1)
    run_registry.fail(7170, "AI-2002", "窗口内销售数据不足", {"stage": "VALIDATING"})

    response = client.get(f"{BASE}/analysis/runs/7170/result", headers=AUTH)

    assert response.status_code == 409
    body = response.json()
    assert body["code"] == "AI-6002"
    assert body["detail"]["error"]["code"] == "AI-2002"


# --------------------------------------------------------------------------- #
# C6 取消
# --------------------------------------------------------------------------- #
def test_cancel_returns_receipt(client: TestClient) -> None:
    analysis_api.get_registry().register(8180, team_id=7, total_skus=2)

    response = client.post(
        f"{BASE}/analysis/runs/8180:cancel", json={"reason": "用户主动取消"}, headers=AUTH
    )

    assert response.status_code == 200
    data = assert_envelope(response)["data"]
    assert data["analysis_run_id"] == 8180
    assert data["execution_status"] == "CANCELLED"
    assert data["cancelled_at"].endswith("+00:00") or data["cancelled_at"].endswith("Z")
    assert analysis_api.get_registry().is_cancel_requested(8180) is True


def test_cancel_accepts_empty_body(client: TestClient) -> None:
    analysis_api.get_registry().register(8181, team_id=7, total_skus=2)

    response = client.post(f"{BASE}/analysis/runs/8181:cancel", headers=AUTH)

    assert response.status_code == 200
    assert response.json()["data"]["execution_status"] == "CANCELLED"


def test_cancel_terminal_run_returns_409(client: TestClient) -> None:
    run_registry = analysis_api.get_registry()
    run_registry.register(8182, team_id=7, total_skus=2)
    run_registry.fail(8182, "AI-2002", "窗口内销售数据不足", {"stage": "VALIDATING"})

    response = client.post(f"{BASE}/analysis/runs/8182:cancel", json={}, headers=AUTH)

    assert response.status_code == 409
    assert response.json()["code"] == "AI-6002"


def test_cancel_unknown_run_returns_404(client: TestClient) -> None:
    response = client.post(f"{BASE}/analysis/runs/999:cancel", json={}, headers=AUTH)

    assert response.status_code == 404
    assert response.json()["code"] == "AI-6001"


def test_cancel_reason_length_is_capped(client: TestClient) -> None:
    analysis_api.get_registry().register(8183, team_id=7, total_skus=1)

    response = client.post(
        f"{BASE}/analysis/runs/8183:cancel", json={"reason": "x" * 201}, headers=AUTH
    )

    assert response.status_code == 400
    assert response.json()["code"] == "AI-1001"


# --------------------------------------------------------------------------- #
# C7 方法与路径
# --------------------------------------------------------------------------- #
def test_validate_path_rejects_get(client: TestClient) -> None:
    response = client.get(f"{BASE}/analysis:validate", headers=AUTH)

    assert response.status_code == 405
    assert response.json()["code"] == "AI-1004"
    assert "Allow" in response.headers


def test_undefined_internal_path_returns_ai_1003(client: TestClient) -> None:
    response = client.get(f"{BASE}/analysis/unknown", headers=AUTH)

    assert response.status_code == 404
    assert response.json()["code"] == "AI-1003"


def test_runs_path_rejects_delete(client: TestClient) -> None:
    response = client.delete(f"{BASE}/analysis/runs", headers=AUTH)

    assert response.status_code == 405
    assert response.json()["code"] == "AI-1004"


# --------------------------------------------------------------------------- #
# C8 注册表单例 + 其它契约细节
# --------------------------------------------------------------------------- #
def test_registry_is_shared_across_requests(client: TestClient) -> None:
    client.post(f"{BASE}/analysis/runs", json=run_payload(30001), headers=AUTH)
    client.post(f"{BASE}/analysis/runs", json=run_payload(30002), headers=AUTH)

    snapshot = analysis_api.get_registry().snapshot()
    assert [record.analysis_run_id for record in snapshot] == [30001, 30002]

    first = client.get(f"{BASE}/analysis/runs/30001", headers=AUTH)
    assert first.status_code == 200
    assert first.json()["data"]["analysis_run_id"] == 30001


def test_all_internal_endpoints_require_token(client: TestClient) -> None:
    calls = [
        ("POST", f"{BASE}/analysis:validate", validate_payload()),
        ("POST", f"{BASE}/analysis/runs", run_payload()),
        ("GET", f"{BASE}/analysis/runs/30001", None),
        ("GET", f"{BASE}/analysis/runs/30001/result", None),
        ("POST", f"{BASE}/analysis/runs/30001:cancel", {}),
    ]
    for method, path, body in calls:
        response = client.request(method, path, json=body)
        assert response.status_code == 401, path
        assert response.json()["code"] == "AI-0001"


def test_path_constants_match_the_contract() -> None:
    assert analysis_api.PATH_VALIDATE == "/analysis:validate"
    assert analysis_api.PATH_RUNS == "/analysis/runs"
    assert analysis_api.PATH_RUN == "/analysis/runs/{analysis_run_id}"
    assert analysis_api.PATH_RESULT == "/analysis/runs/{analysis_run_id}/result"
    assert analysis_api.PATH_CANCEL == "/analysis/runs/{analysis_run_id}:cancel"


def test_error_responses_never_carry_data(client: TestClient) -> None:
    response = client.get(f"{BASE}/analysis/runs/999", headers=AUTH)

    body = response.json()
    assert set(body) == {"code", "message", "detail", "request_id"}
    assert "data" not in body
