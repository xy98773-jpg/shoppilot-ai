"""Environment-based configuration for the ShopPilot AI service.

Reads ``AI_INTERNAL_TOKEN`` / ``AI_SERVICE_BASE_URL`` from the process environment.
The full configuration surface (LLM endpoint, model name, DB, timeouts, ...) is
expanded in later milestones (see M4+ specs).

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

#: Default base URL for callbacks to the Java backend in local development.
DEFAULT_AI_SERVICE_BASE_URL = "http://localhost:8000"


@dataclass(frozen=True)
class Settings:
    """Process-wide settings loaded from the environment."""

    #: Shared secret expected in ``X-Internal-Token``. ``None``/blank = not configured.
    ai_internal_token: str | None = None
    #: Base URL used when this service needs to call back to the Java backend.
    ai_service_base_url: str = DEFAULT_AI_SERVICE_BASE_URL

    @classmethod
    def load(cls) -> "Settings":
        return cls(
            ai_internal_token=os.environ.get("AI_INTERNAL_TOKEN"),
            ai_service_base_url=os.environ.get(
                "AI_SERVICE_BASE_URL", DEFAULT_AI_SERVICE_BASE_URL
            ),
        )


# Module-level singleton loaded once at import time.
settings = Settings.load()
