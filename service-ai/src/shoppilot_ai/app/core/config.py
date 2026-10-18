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


@dataclass(frozen=True)
class Settings:
    """Process-wide settings loaded from the environment."""

    #: Shared secret expected in ``X-Internal-Token``. ``None``/blank = not configured.
    ai_internal_token: str | None = None
    #: Where **Java** listens, for callbacks from this service.
    #: (Not to be confused with Java's ``AI_SERVICE_BASE_URL``, which points at *us*.)
    java_backend_base_url: str = DEFAULT_JAVA_BACKEND_BASE_URL

    @classmethod
    def load(cls) -> "Settings":
        return cls(
            ai_internal_token=os.environ.get("AI_INTERNAL_TOKEN"),
            java_backend_base_url=os.environ.get(
                "JAVA_BACKEND_BASE_URL", DEFAULT_JAVA_BACKEND_BASE_URL
            ),
        )


# Module-level singleton loaded once at import time.
settings = Settings.load()
