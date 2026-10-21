"""LLM 接入测试（M8-T07 / `R155`）。

口径：`PROMPT_DESIGN.md`（§2.1 模板 / §5 注入防护 / §6 校验 / §7 白名单 / §9 降级矩阵）
+ `DESIGN_BASIS §9.8`（`LlmStatus`）+ 铁律③（LLM 只解释，失败 ⇒ 规则结果必留）。

★ **全程不打真实网络**：客户端用 `httpx.MockTransport` 注入，narrative 用
`monkeypatch` 替换 `complete`。理由：① 不消耗真实额度；② 不把密钥写进任何地方；
③ 测试必须可离线重复跑。

★ 铁律③的守卫在最后一组用例：**无论 LLM 处于何种失败态，`risk_level`/
`q_suggest` 等规则结果都必须原样保留**（`PROMPT_DESIGN §9` 贯穿原则）。
"""
from __future__ import annotations

import json
from datetime import date, timedelta

import httpx
import pytest

from shoppilot_ai.app.analytics.evaluate import ItemEvaluation
from shoppilot_ai.app.core.config import Settings
from shoppilot_ai.app.llm import client as client_module
from shoppilot_ai.app.llm import narrative as narrative_module
from shoppilot_ai.app.llm import validator as validator_module
from shoppilot_ai.app.llm.client import (
    ChatMessage,
    LlmRejected,
    LlmTimeout,
    LlmUnavailable,
    complete,
)
from shoppilot_ai.app.llm.context_builder import (
    FORBIDDEN_SUBSTRINGS,
    assert_no_forbidden,
    build_risk_context,
)
from shoppilot_ai.app.llm.narrative import llm_narrative, select_candidates
from shoppilot_ai.app.llm.prompts.loader import (
    DEFAULT_PROMPT_KEY,
    REGISTRY,
    PromptError,
    get_template,
    render,
)
from shoppilot_ai.app.llm.validator import (
    OutputValidationError,
    strip_code_fence,
    validate_output,
)
from shoppilot_ai.app.schemas.analysis_request import (
    AnalyzeRequest,
    Params,
    Scope,
)

START = date(2026, 1, 1)
DATE_TO = START + timedelta(days=27)

#: 一个通过 `§6.1` 的解释（explanation 需 50–800 字，故这里构造足够长的中文）。
_GOOD_EXPLANATION = (
    "该 SKU 当前库存 8.000，日均销量约 3.500，库存位置为 8.000，"
    "按交期 7 天与覆盖天数 7 天估算，可支撑天数低于安全阈值，"
    "因此被判定为低库存风险。建议结合最小起订量与采购倍数安排补货，"
    "并复核在途到货时间是否覆盖交期；若数据质量长期为 FAIR，建议人工复核销量异常日。"
    "本解释仅基于系统已计算好的结构化结果，未引入任何新的推算。"
)


# --------------------------------------------------------------------------- #
# 夹具
# --------------------------------------------------------------------------- #
def cfg(**overrides: object) -> Settings:
    """构造测试用配置（默认已配好密钥 ⇒ `llm_configured=True`）。"""
    base = {
        "llm_api_key": "test-key-not-real",
        "llm_base_url": "https://api.deepseek.com",
        "llm_model": "deepseek-chat",
        "llm_max_retries": 0,  # ★ 默认不重试：避免测试真 sleep
        "llm_max_items": 20,
    }
    base.update(overrides)
    return Settings(**base)  # type: ignore[arg-type]


def make_eval(
    sku_id: int,
    *,
    risk_level: str = "HIGH",
    risk_types: list[str] | None = None,
) -> ItemEvaluation:
    return ItemEvaluation(
        sku_id=sku_id,
        warehouse_id=100,
        avg_daily_sales="3.500",
        sales_qty_total="100.000",
        valid_days=21,
        data_quality="GOOD",
        risk_level=risk_level,
        risk_types=risk_types or ["LOW_STOCK"],
        on_hand_qty="8.000",
        effective_in_transit_qty="0.000",
        committed_qty="0.000",
        inventory_position="8.000",
        safety_stock="5.000",
        lead_time_days=7,
        lead_time_source="SKU",
        coverage_days=7,
        q_base="10.000",
        q_suggest="10.000",
    )


def make_request(*, enable_llm: bool = True) -> AnalyzeRequest:
    return AnalyzeRequest(
        analysis_run_id=1,
        team_id=7,
        warehouse_id=100,
        scope=Scope(scope_type="SELECTED_SKUS", sku_ids=[1]),
        date_from=START,
        date_to=DATE_TO,
        params=Params(enable_llm=enable_llm, window_days=28),
        algorithm_version="avg-ma-3sigma-v1",
        prompt_version="explain_risk.v1" if enable_llm else None,
        items=[],
    )


def llm_payload(explanation: str = _GOOD_EXPLANATION, **extra: object) -> str:
    body = {
        "explanation": explanation,
        "evidence": ["on_hand_qty"],
        "confidence": "HIGH",
        "uncertainties": [],
        "checklist": [],
    }
    body.update(extra)
    return json.dumps(body, ensure_ascii=False)


def transport(
    handler,
) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


# --------------------------------------------------------------------------- #
# 1) 客户端：端点 / 鉴权 / 错误分类
# --------------------------------------------------------------------------- #
def test_complete_returns_assistant_content() -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("Authorization")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": "hello"}}]})

    result = complete(
        [ChatMessage(role="user", content="hi")],
        cfg=cfg(),
        client=transport(handler),
    )

    assert result == "hello"
    # ★ 端点由 DEFAULT_LLM_BASE_URL + /chat/completions 拼出
    assert seen["url"] == "https://api.deepseek.com/chat/completions"
    # ★ 鉴权头存在且用的是 LLM 专用 key
    assert seen["auth"] == "Bearer test-key-not-real"
    # ★ 安全红线：绝不能把 Java↔Python 的内部令牌带出去
    assert "X-Internal-Token" not in json.dumps(seen["body"])
    assert seen["body"]["model"] == "deepseek-chat"


def test_complete_never_sends_internal_token() -> None:
    """安全红线：请求头里不得出现 `X-Internal-Token`（那是内部服务间凭据）。"""

    def handler(request: httpx.Request) -> httpx.Response:
        assert "X-Internal-Token" not in request.headers
        return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})

    complete(
        [ChatMessage(role="user", content="hi")],
        cfg=cfg(),
        client=transport(handler),
    )


def test_timeout_maps_to_llm_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.TimeoutException("too slow", request=request)

    with pytest.raises(LlmTimeout):
        complete(
            [ChatMessage(role="user", content="hi")],
            cfg=cfg(llm_max_retries=0),
            client=transport(handler),
        )


def test_http_500_maps_to_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    with pytest.raises(LlmUnavailable):
        complete(
            [ChatMessage(role="user", content="hi")],
            cfg=cfg(llm_max_retries=0),
            client=transport(handler),
        )


def test_http_401_maps_to_rejected_without_retry() -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(401, text="invalid api key")

    # ★ 即便 max_retries=2，4xx 也不得重试（重试无意义）
    with pytest.raises(LlmRejected):
        complete(
            [ChatMessage(role="user", content="hi")],
            cfg=cfg(llm_max_retries=2),
            client=transport(handler),
        )
    assert calls["n"] == 1


def test_http_429_retries_then_unavailable() -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(429, text="rate limited")

    # 退避已 monkeypatch 掉，避免测试真 sleep
    original = client_module._backoff
    client_module._backoff = lambda attempt: None
    try:
        with pytest.raises(LlmUnavailable):
            complete(
                [ChatMessage(role="user", content="hi")],
                cfg=cfg(llm_max_retries=2),
                client=transport(handler),
            )
    finally:
        client_module._backoff = original
    assert calls["n"] == 3  # 首次 + 2 次重试


def test_non_json_body_maps_to_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<html>not json</html>")

    with pytest.raises(LlmUnavailable):
        complete(
            [ChatMessage(role="user", content="hi")],
            cfg=cfg(),
            client=transport(handler),
        )


def test_unexpected_payload_structure_maps_to_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"unexpected": True})

    with pytest.raises(LlmUnavailable):
        complete(
            [ChatMessage(role="user", content="hi")],
            cfg=cfg(),
            client=transport(handler),
        )


def test_missing_api_key_maps_to_rejected() -> None:
    """未配置密钥 ⇒ 拒绝（而不是当成成功，也不是崩溃）。"""
    with pytest.raises(LlmRejected):
        complete(
            [ChatMessage(role="user", content="hi")],
            cfg=cfg(llm_api_key=None),
            client=transport(lambda r: httpx.Response(200, json={})),
        )


# --------------------------------------------------------------------------- #
# 2) 校验器（§6）
# --------------------------------------------------------------------------- #
def test_validate_accepts_good_output() -> None:
    # ★ 用**真实**白名单上下文（含交期/覆盖天数等），否则数字溯源会把解释里
    #   正常引用的「7 天」判成疑似编造——那是夹具太瘦，不是实现问题。
    context = build_risk_context(make_eval(1), window_days=28)
    result = validate_output(
        llm_payload(), context=context, risk_level="HIGH"
    )
    assert result.ok
    assert result.explanation is not None
    assert result.evidence == ["on_hand_qty"]
    assert result.warnings == [], f"不应有告警：{result.warnings}"


def test_strip_code_fence() -> None:
    assert strip_code_fence('```json\n{"a": 1}\n```') == '{"a": 1}'
    assert strip_code_fence('{"a": 1}') == '{"a": 1}'


def test_invalid_json_raises() -> None:
    with pytest.raises(OutputValidationError):
        validate_output("not json at all")


def test_too_short_explanation_fails() -> None:
    result = validate_output(llm_payload("太短"), context={"on_hand_qty": "8.000"})
    assert not result.ok
    assert "Schema" in (result.reason or "")


def test_evidence_whitelist_filters_illegal_items() -> None:
    """§6.1：evidence 必须是输入字段名；非法的**剔除**，而非整体失败。"""
    payload = llm_payload(evidence=["on_hand_qty", "purchase_price"])
    result = validate_output(payload, context={"on_hand_qty": "8.000"})
    assert result.ok
    assert result.evidence == ["on_hand_qty"]  # purchase_price 被剔除
    assert any("剔除" in w for w in result.warnings)


def test_evidence_all_illegal_fails() -> None:
    result = validate_output(
        llm_payload(evidence=["secret_field"]), context={"on_hand_qty": "8.000"}
    )
    assert not result.ok
    assert "evidence" in (result.reason or "")


def test_contradiction_with_high_risk_fails() -> None:
    """§6.4：`risk_level=HIGH` 却说"无风险" ⇒ 硬失败（会误导运营）。"""
    bad = _GOOD_EXPLANATION + "综合判断该 SKU 无风险，库存充足。"
    result = validate_output(
        llm_payload(bad), context={"on_hand_qty": "8.000"}, risk_level="HIGH"
    )
    assert not result.ok
    assert "矛盾" in (result.reason or "")


def test_number_trace_only_warns() -> None:
    """D-31：数字溯源是启发式 ⇒ **软失败**（记 warning，不丢解释）。"""
    context = {"on_hand_qty": "8.000"}
    text = _GOOD_EXPLANATION + "另有 9999.000 的隐藏库存。"
    result = validate_output(text and llm_payload(text), context=context)
    assert result.ok, "溯源命中不应丢弃解释（软失败）"
    assert any("疑似编造" in w for w in result.warnings)


# --------------------------------------------------------------------------- #
# 3) 白名单与注入防护（§5 / §7）
# --------------------------------------------------------------------------- #
def test_context_contains_only_allowed_fields() -> None:
    context = build_risk_context(make_eval(1), window_days=28)
    assert_no_forbidden(context)
    for key in context:
        lowered = key.lower()
        for bad in FORBIDDEN_SUBSTRINGS:
            assert bad not in lowered, f"上下文出现禁止字段：{key}"


def test_context_has_no_price_or_contact() -> None:
    """§7.1：价格/成本/联系人**绝不**进入 Prompt。"""
    context = build_risk_context(make_eval(1), window_days=28)
    blob = json.dumps(context, ensure_ascii=False).lower()
    for bad in ("price", "cost", "amount", "contact", "phone", "email"):
        assert bad not in blob, f"Prompt 上下文泄漏了敏感字段：{bad}"


def test_render_escapes_template_delimiters() -> None:
    """§5.1：数据里的 `{{ }}` 必须转义，防二次渲染。"""
    out = render("值={{ v }}", {"v": "{{ evil }}"})
    assert "{{" not in out.replace("［［", "")
    assert "［［" in out and "］］" in out


def test_render_ignores_undeclared_context_keys() -> None:
    """§1.3 理由 4：上下文里多出来的键不得进入渲染结果。"""
    out = render("{{ a }}", {"a": "A", "b": "B"})
    assert out == "A"
    assert "B" not in out


def test_render_missing_value_is_explicit() -> None:
    """§2.1 SYSTEM 规则 3：缺失必须明确写"未提供"，不能留空或猜。"""
    assert render("{{ x }}", {}) == "未提供"


def test_default_template_registered() -> None:
    assert DEFAULT_PROMPT_KEY in REGISTRY
    template = get_template(None)
    assert template.key == "explain_risk.v1"
    assert "只解释，不计算" in template.system  # SYSTEM 全文未改


def test_unknown_prompt_version_fails_fast() -> None:
    with pytest.raises(PromptError):
        get_template("no_such.v9")


def test_system_prompt_forbids_calculation() -> None:
    """安全边界：SYSTEM 必须明确"只解释不计算"（`PROMPT_DESIGN` 安全边界）。"""
    system = get_template(None).system
    assert "只解释，不计算" in system
    assert "禁止编造" in system


# --------------------------------------------------------------------------- #
# 4) narrative：状态机（§9 降级矩阵）
# --------------------------------------------------------------------------- #
def test_narrative_success(monkeypatch) -> None:
    monkeypatch.setattr(narrative_module, "settings", cfg())
    monkeypatch.setattr(
        narrative_module, "complete", lambda messages, **kw: llm_payload()
    )

    status, explanations = llm_narrative([make_eval(1)], make_request())

    assert status == "SUCCEEDED"
    assert explanations[1] == _GOOD_EXPLANATION


def test_narrative_skipped_when_disabled(monkeypatch) -> None:
    """§9：`enable_llm=false` ⇒ SKIPPED（run 仍 SUCCEEDED，**不是**降级）。"""
    monkeypatch.setattr(narrative_module, "settings", cfg())
    called = {"n": 0}

    def boom(messages, **kw):
        called["n"] += 1
        raise AssertionError("未启用时不得调用 LLM")

    monkeypatch.setattr(narrative_module, "complete", boom)

    status, explanations = llm_narrative(
        [make_eval(1)], make_request(enable_llm=False)
    )
    assert status == "SKIPPED"
    assert explanations == {}
    assert called["n"] == 0


def test_narrative_skipped_when_unconfigured(monkeypatch) -> None:
    """未配 `LLM_API_KEY` ⇒ 安全跳过（不崩、不伪造成功）。"""
    monkeypatch.setattr(narrative_module, "settings", cfg(llm_api_key=None))
    monkeypatch.setattr(
        narrative_module, "complete", lambda messages, **kw: llm_payload()
    )

    status, explanations = llm_narrative([make_eval(1)], make_request())
    assert status == "SKIPPED"
    assert explanations == {}


def test_narrative_skipped_when_no_risky_sku(monkeypatch) -> None:
    """无风险 SKU（NONE）不解释 ⇒ 省钱且不产生无意义的解释。"""
    monkeypatch.setattr(narrative_module, "settings", cfg())
    monkeypatch.setattr(
        narrative_module, "complete", lambda messages, **kw: llm_payload()
    )

    status, explanations = llm_narrative(
        [make_eval(1, risk_level="NONE", risk_types=[])], make_request()
    )
    assert status == "SKIPPED"
    assert explanations == {}


def test_narrative_timeout_status(monkeypatch) -> None:
    monkeypatch.setattr(narrative_module, "settings", cfg())

    def boom(messages, **kw):
        raise LlmTimeout("slow")

    monkeypatch.setattr(narrative_module, "complete", boom)

    status, explanations = llm_narrative([make_eval(1)], make_request())
    assert status == "TIMEOUT"
    assert explanations == {}


def test_narrative_failed_status(monkeypatch) -> None:
    monkeypatch.setattr(narrative_module, "settings", cfg())

    def boom(messages, **kw):
        raise LlmUnavailable("502")

    monkeypatch.setattr(narrative_module, "complete", boom)

    status, _ = llm_narrative([make_eval(1)], make_request())
    assert status == "FAILED"


def test_narrative_validation_failed_on_bad_json(monkeypatch) -> None:
    monkeypatch.setattr(narrative_module, "settings", cfg())
    monkeypatch.setattr(narrative_module, "complete", lambda messages, **kw: "纯文本")

    status, explanations = llm_narrative([make_eval(1)], make_request())
    assert status == "VALIDATION_FAILED"
    assert explanations == {}


def test_narrative_partial_success_is_succeeded(monkeypatch) -> None:
    """只要拿到 ≥1 条解释就算 SUCCEEDED（失败的 SKU 仅缺解释，规则结果照常）。"""
    monkeypatch.setattr(narrative_module, "settings", cfg())

    def flaky(messages, **kw):
        # 第 1 个 SKU 失败、其余成功（用 messages 内容无法区分，故用计数器）
        flaky.n += 1
        if flaky.n == 1:
            raise LlmTimeout("slow")
        return llm_payload()

    flaky.n = 0
    monkeypatch.setattr(narrative_module, "complete", flaky)

    items = [make_eval(1), make_eval(2)]
    status, explanations = llm_narrative(items, make_request())
    assert status == "SUCCEEDED"
    assert set(explanations) == {2}


def test_narrative_stops_after_consecutive_failures(monkeypatch) -> None:
    """连续失败 3 次 ⇒ 提前中止（不把预算耗在必然失败的重试上）。"""
    monkeypatch.setattr(narrative_module, "settings", cfg())
    calls = {"n": 0}

    def boom(messages, **kw):
        calls["n"] += 1
        raise LlmTimeout("slow")

    monkeypatch.setattr(narrative_module, "complete", boom)

    items = [make_eval(i) for i in range(1, 11)]  # 10 个高风险 SKU
    status, _ = llm_narrative(items, make_request())
    assert status == "TIMEOUT"
    assert calls["n"] == 3, "应只试 3 次就中止，而不是把 10 个都跑完"


def test_select_candidates_orders_by_risk() -> None:
    items = [
        make_eval(9, risk_level="LOW"),
        make_eval(5, risk_level="HIGH"),
        make_eval(7, risk_level="MEDIUM"),
        make_eval(1, risk_level="NONE", risk_types=[]),
    ]
    selected = select_candidates(items, max_items=10)
    assert [it.sku_id for it in selected] == [5, 7, 9]  # HIGH → MEDIUM → LOW


def test_select_candidates_respects_max_items() -> None:
    items = [make_eval(i) for i in range(1, 11)]
    assert len(select_candidates(items, max_items=3)) == 3


# --------------------------------------------------------------------------- #
# 5) ★ 铁律③：LLM 失败时规则结果必须完整保留
# --------------------------------------------------------------------------- #
def test_rule_results_preserved_when_llm_fails(monkeypatch) -> None:
    """`PROMPT_DESIGN §9` 贯穿原则——LLM 任何失败都不得让规则结果丢失。

    这是本次接入**最重要**的一条守卫：解释只是锦上添花，
    `risk_level` / `q_suggest` / `q_base` 才是业务的命脉。
    """
    from shoppilot_ai.app.orchestration.pipeline import _run_narrative

    monkeypatch.setattr(narrative_module, "settings", cfg())

    def boom(messages, **kw):
        raise LlmUnavailable("503")

    monkeypatch.setattr(narrative_module, "complete", boom)

    items = [make_eval(1)]
    request = make_request()
    warnings: list[str] = []
    status, explanations = _run_narrative(llm_narrative, items, request, warnings)

    assert status == "FAILED"
    assert explanations == {}

    # ★ 规则结果本身**未被触碰**（evaluation 是只读入参，narrative 从不修改）
    evaluation = items[0]
    assert evaluation.risk_level == "HIGH"
    assert evaluation.q_suggest == "10.000"
    assert evaluation.q_base == "10.000"
    assert evaluation.on_hand_qty == "8.000"
    # 且失败原因进了 run 级 warning（可观测，不静默）
    assert any("LLM" in w for w in warnings)


def test_narrative_never_mutates_evaluation(monkeypatch) -> None:
    """解释过程**不得**改动任何结构化字段（`PROMPT_DESIGN §5`）。"""
    monkeypatch.setattr(narrative_module, "settings", cfg())
    monkeypatch.setattr(
        narrative_module, "complete", lambda messages, **kw: llm_payload()
    )

    evaluation = make_eval(1)
    before = evaluation.model_dump()
    llm_narrative([evaluation], make_request())
    assert evaluation.model_dump() == before
