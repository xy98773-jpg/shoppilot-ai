"""Environment-based configuration for the ShopPilot AI service.

Reads ``AI_INTERNAL_TOKEN`` / ``JAVA_BACKEND_BASE_URL`` from the process environment.
The full configuration surface (LLM endpoint, model name, DB, timeouts, ...) is
expanded in later milestones (see M4+ specs).

★ R96 —— 两个「base url」不是一回事，**不许同名**：
  - Python 侧 ``java_backend_base_url``：**本服务回调 Java 的地址**
    （默认 ``http://backend-java:8080``，即 Java 的 Compose 服务名 + 端口）；
  - Java 侧 ``shoppilot.ai.base-url``（环境变量 ``AI_SERVICE_BASE_URL``）：
    **Java 调用 Python 的地址**（默认 ``http://service-ai:8000``）。
  两者语义相反。B13 里 Python 侧也叫 ``AI_SERVICE_BASE_URL``，且默认值写成
  ``http://localhost:8000``（那是 Python 自己的端口）——同名反义 + 自指默认值，
  是复核 R96 认定的缺陷。本批按「改名」处理：旧字段 ``ai_service_base_url``
  **必须消失**，不留别名兼容（留别名等于把歧义继续留在代码里）。

A ``dataclass`` + ``os.environ`` approach is used instead of pydantic-settings
so the skeleton has no extra dependency beyond the approved stack
(FastAPI / Pydantic / httpx).

★ NO startup-time validation: ``AI_INTERNAL_TOKEN`` may legitimately be unset
(``pytest`` and the container healthcheck must boot without it). "Unset" is
handled at the call site as **fail-closed** (see ``core/security.py``), not by
crashing the process on import.
"""
from __future__ import annotations

import os
from dataclasses import dataclass

#: Default address this service uses to call back into the Java backend.
#: This is **Java's** Compose service name + port, NOT this service's own address.
DEFAULT_JAVA_BACKEND_BASE_URL = "http://backend-java:8080"

# --------------------------------------------------------------------------- #
# LLM 接入（M8-T07 / R155；TECH_STACK §1.3.1）
# --------------------------------------------------------------------------- #
#: OpenAI 兼容端点。★ DeepSeek 官方端点（`https://api.deepseek.com`），
#: 客户端会自动拼 `/chat/completions`，**不要**在这里带路径后缀。
DEFAULT_LLM_BASE_URL = "https://api.deepseek.com"
#: DeepSeek 通用对话模型（`deepseek-chat`）。
DEFAULT_LLM_MODEL = "deepseek-chat"
#: 单次请求超时（秒）。★ 必须小于 Java 侧对 Python 的整体读超时，否则 LLM 慢会拖垮整条 run。
DEFAULT_LLM_TIMEOUT_SECONDS = 30.0
#: 网络/5xx/429 的**额外**重试次数（0=不重试，1=最多 2 次尝试）。
#: ★ 只对可恢复错误重试；4xx（鉴权/参数错）重试无意义，直接失败。
DEFAULT_LLM_MAX_RETRIES = 1
#: 单次解释的最大输出 token。
DEFAULT_LLM_MAX_TOKENS = 1500
#: 温度：解释要稳定可复现，取低值。
DEFAULT_LLM_TEMPERATURE = 0.2
#: ★ 单次 run 最多交给 LLM 解释的 SKU 数（控 token/成本/延迟）。
#:   `PROMPT_DESIGN §2.1` 的 `explain_risk.v1` 是**单 SKU** Prompt ⇒ 串行调用，
#:   N 个 SKU 就是 N 次往返，故取较小值（20）作为首期默认。
#: 超出的 SKU 不解释（`llm_explanation=None`），但**规则结果照常返回**。
DEFAULT_LLM_MAX_ITEMS = 20


def _env_float(name: str, default: float) -> float:
    """读浮点环境变量；缺失或非法 ⇒ 回落 `default`（**不**让非法值拖垮进程启动）。

    ★ 与 `ai_internal_token` 同一哲学：配置问题在**调用点**按语义处理
    （LLM 未配置 ⇒ SKIPPED），而不是 import 期崩溃（`pytest`/健康检查要能起）。
    """
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _env_int(name: str, default: int) -> int:
    """读整数环境变量；缺失或非法 ⇒ 回落 `default`（同 `_env_float`）。"""
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """Process-wide settings loaded from the environment."""

    #: Shared secret expected in ``X-Internal-Token``. ``None``/blank = not configured.
    ai_internal_token: str | None = None
    #: Where **Java** listens, for callbacks from this service.
    #: (Not to be confused with Java's ``AI_SERVICE_BASE_URL``, which points at *us*.)
    java_backend_base_url: str = DEFAULT_JAVA_BACKEND_BASE_URL

    # ---------- LLM 接入（R155）----------
    #: OpenAI 兼容端点（DeepSeek 官方）。客户端自行拼 ``/chat/completions``。
    llm_base_url: str = DEFAULT_LLM_BASE_URL
    #: 模型名（DeepSeek：``deepseek-chat``）。
    llm_model: str = DEFAULT_LLM_MODEL
    #: API 密钥。``None``/空白 = **未配置** ⇒ LLM 步骤按 ``SKIPPED`` 处理（fail-safe）。
    #: ★ 只从环境变量读，**绝不**入仓、绝不写默认值（CODING_STANDARDS：密钥不入仓）。
    llm_api_key: str | None = None
    llm_timeout_seconds: float = DEFAULT_LLM_TIMEOUT_SECONDS
    llm_max_retries: int = DEFAULT_LLM_MAX_RETRIES
    llm_max_tokens: int = DEFAULT_LLM_MAX_TOKENS
    llm_temperature: float = DEFAULT_LLM_TEMPERATURE
    llm_max_items: int = DEFAULT_LLM_MAX_ITEMS

    @property
    def llm_configured(self) -> bool:
        """LLM 是否**可用**：必须有密钥且端点非空。

        ★ 这只是「配置完备」判定，**不**代表调用一定成功。真正失败（超时/5xx/输出非法）
        在调用点分类为 ``TIMEOUT`` / ``FAILED`` / ``VALIDATION_FAILED``，
        由 Java 侧映射成 run 级 ``DEGRADED``，规则结果一律保留（铁律③）。
        """
        return bool(self.llm_api_key and self.llm_api_key.strip()) and bool(
            self.llm_base_url and self.llm_base_url.strip()
        )

    @classmethod
    def load(cls) -> "Settings":
        api_key = os.environ.get("LLM_API_KEY")
        return cls(
            ai_internal_token=os.environ.get("AI_INTERNAL_TOKEN"),
            java_backend_base_url=os.environ.get(
                "JAVA_BACKEND_BASE_URL", DEFAULT_JAVA_BACKEND_BASE_URL
            ),
            llm_base_url=(os.environ.get("LLM_BASE_URL") or DEFAULT_LLM_BASE_URL).strip(),
            llm_model=(os.environ.get("LLM_MODEL") or DEFAULT_LLM_MODEL).strip(),
            # 空串视为未配置（compose 常写成 ${LLM_API_KEY:-}，会传进空串）
            llm_api_key=api_key.strip() if api_key and api_key.strip() else None,
            llm_timeout_seconds=_env_float(
                "LLM_TIMEOUT_SECONDS", DEFAULT_LLM_TIMEOUT_SECONDS
            ),
            llm_max_retries=_env_int("LLM_MAX_RETRIES", DEFAULT_LLM_MAX_RETRIES),
            llm_max_tokens=_env_int("LLM_MAX_TOKENS", DEFAULT_LLM_MAX_TOKENS),
            llm_temperature=_env_float("LLM_TEMPERATURE", DEFAULT_LLM_TEMPERATURE),
            llm_max_items=_env_int("LLM_MAX_ITEMS", DEFAULT_LLM_MAX_ITEMS),
        )


# Module-level singleton loaded once at import time.
settings = Settings.load()
