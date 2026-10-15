"""Health probe endpoint.

``GET /internal/v1/health`` -> ``{"status": "UP"}`` (HTTP 200).

This endpoint is intentionally exempt from the X-Internal-Token check so that
liveness/readiness probes can run without credentials. See ``app/main.py``.
"""
from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(prefix="/internal/v1", tags=["internal"])


@router.get("/health")
async def health() -> dict:
    """Return service liveness status."""
    return {"status": "UP"}
