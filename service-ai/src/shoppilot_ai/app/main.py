"""FastAPI application entrypoint for the ShopPilot AI service.

Skeleton responsibilities (no business logic):
- Create the FastAPI app.
- Register the ``health`` router (token-exempt, for liveness/readiness probes)
  and the ``analysis`` router (placeholder, protected by ``X-Internal-Token``).
- Add an ``X-Request-Id`` response-header middleware.
- Wire unified exception handlers.

TOKEN POLICY (skeleton-level):
  ``GET /internal/v1/health`` is intentionally EXEMPT from the
  ``X-Internal-Token`` check so probes can run without credentials. Every
  other endpoint (today: the analysis router) requires a matching
  ``X-Internal-Token`` header, compared against ``config.AI_INTERNAL_TOKEN``.
  When ``AI_INTERNAL_TOKEN`` is unset (local/dev skeleton), enforcement is
  skipped. Full authentication/authorization is deferred to M4-T01; this is
  the agreed "read + compare" skeleton check.
"""
from __future__ import annotations

import uuid
from typing import Optional

from fastapi import Depends, FastAPI, Header, Request, status
from fastapi.responses import JSONResponse

from shoppilot_ai.app.api import analysis, health
from shoppilot_ai.app.core.config import settings
from shoppilot_ai.app.core.exceptions import (
    InternalTokenError,
    ServiceAIBaseError,
)
from shoppilot_ai.app.core.logging import get_logger
from shoppilot_ai.app.core.tracing import REQUEST_ID_HEADER

logger = get_logger(__name__)

app = FastAPI(title="ShopPilot AI Service", version="0.1.0")


@app.middleware("http")
async def request_id_middleware(request: Request, call_next):
    """Guarantee an X-Request-Id on every request and echo it back."""
    rid = request.headers.get(REQUEST_ID_HEADER) or str(uuid.uuid4())
    request.state.request_id = str(rid)
    response = await call_next(request)
    response.headers[REQUEST_ID_HEADER] = request.state.request_id
    return response


async def require_internal_token(
    x_internal_token: Optional[str] = Header(default=None, alias="X-Internal-Token"),
) -> None:
    """Skeleton token gate: compare header against configured token.

    Applied to the analysis router only (health is exempt). Raises
    :class:`InternalTokenError` on mismatch. When no expected token is
    configured, the check is skipped (local/dev convenience).
    """
    expected = settings.ai_internal_token
    if expected and x_internal_token != expected:
        raise InternalTokenError()


# Health is registered WITHOUT the token dependency (probe-exempt).
app.include_router(health.router)
# Analysis router carries the skeleton token gate.
app.include_router(analysis.router, dependencies=[Depends(require_internal_token)])


@app.exception_handler(InternalTokenError)
async def _token_error_handler(request: Request, exc: InternalTokenError):
    return JSONResponse(
        status_code=status.HTTP_401_UNAUTHORIZED,
        content={
            "code": "INTERNAL_TOKEN_INVALID",
            "message": "Invalid or missing X-Internal-Token",
        },
    )


@app.exception_handler(ServiceAIBaseError)
async def _base_error_handler(request: Request, exc: ServiceAIBaseError):
    logger.error("Unhandled service error: %s", exc)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={"code": "SERVICE_ERROR", "message": str(exc)},
    )
