"""httpx async client factory.

Skeleton: a thin wrapper that builds an :class:`httpx.AsyncClient` with a
sensible default timeout and base headers. Connection pooling / retries and
service-specific headers are refined in later milestones.
"""
from __future__ import annotations

from typing import Dict, Optional

import httpx

from shoppilot_ai.app.core.config import settings

DEFAULT_TIMEOUT = 10.0


def get_client(
    *,
    timeout: float = DEFAULT_TIMEOUT,
    base_headers: Optional[Dict[str, str]] = None,
) -> httpx.AsyncClient:
    """Create an :class:`httpx.AsyncClient` with default timeout and headers.

    A base ``X-Internal-Token`` header is injected from configuration when
    present, so internal service-to-service calls are authenticated by default.
    """
    headers: Dict[str, str] = dict(base_headers or {})
    if settings.ai_internal_token:
        headers.setdefault("X-Internal-Token", settings.ai_internal_token)
    return httpx.AsyncClient(timeout=timeout, headers=headers)
