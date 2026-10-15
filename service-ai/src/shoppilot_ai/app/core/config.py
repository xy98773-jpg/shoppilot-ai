"""Environment-based configuration for the ShopPilot AI service.

Skeleton only. Reads ``AI_INTERNAL_TOKEN`` from the process environment.
The full configuration surface (LLM endpoint, model name, DB, timeouts, ...)
is expanded in later milestones (see M4+ specs).

A ``dataclass`` + ``os.environ`` approach is used instead of pydantic-settings
so the skeleton has no extra dependency beyond the approved stack
(FastAPI / Pydantic / httpx).
"""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    """Process-wide settings loaded from the environment.

    Only ``AI_INTERNAL_TOKEN`` is required for the skeleton. Later milestones
    add additional fields without changing the load contract.
    """

    ai_internal_token: str | None = None

    @classmethod
    def load(cls) -> "Settings":
        return cls(ai_internal_token=os.environ.get("AI_INTERNAL_TOKEN"))


# Module-level singleton loaded once at import time.
settings = Settings.load()
