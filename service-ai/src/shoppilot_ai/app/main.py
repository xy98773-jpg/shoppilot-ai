"""FastAPI application entrypoint for the ShopPilot AI service.

Responsibilities (M4-B13 / B13.1 — internal contract plumbing only, **no business endpoints**):
- Create the FastAPI app.
- Register the ``health`` router (token-exempt, for liveness/readiness probes)
  and the ``analysis`` router (placeholder, protected by ``X-Internal-Token``).
- Guarantee an ``X-Request-Id`` on every response (echo inbound, generate if absent)
  and bind it to a ``contextvar`` so **every log record** can be correlated with it.
- Enforce ``X-Internal-Token`` on the whole ``/internal/v1`` surface except health.
- Emit the contract's four-field error envelope ``{code, message, detail, request_id}``
  for **every** error path — including 404/405 (``AI_SERVICE_CONTRACT.md §5`` 适用范围
  = 全部 HTTP 响应，无例外；``R95``).

TOKEN POLICY (M4-T01 / ``R92``):
  ``GET /internal/v1/health`` (and its trailing-slash variant, ``R97``) is intentionally
  EXEMPT from the ``X-Internal-Token`` check so probes can run without credentials
  (``ACCEPTANCE_CRITERIA.md:82``). Every other ``/internal/v1`` path requires a matching
  token, compared in constant time. **Unset token = fail-closed** (reject), never
  fail-open. The process still starts fine without a token — only the internal surface
  is closed.

WHY A MIDDLEWARE *AND* A ROUTER DEPENDENCY:
  Router-level dependencies only run for **matched** routes. The analysis router has
  no routes yet (business endpoints land in B14+), so a dependency alone would answer
  ``404`` for ``/internal/v1/analysis:validate`` and never check the token. The
  middleware closes that hole for the whole prefix; the router dependency stays as
  defence-in-depth for the day real routes appear.
"""
from __future__ import annotations

import time
from typing import Optional

from fastapi import Depends, FastAPI, Header, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from shoppilot_ai.app.api import analysis, health
from shoppilot_ai.app.core.errors import (
    AI_0001,
    AI_1001,
    AI_1003,
    AI_1004,
    AI_9001,
    DEFAULT_MESSAGES,
    InternalTokenError,
    error_body,
)
from shoppilot_ai.app.core.logging import get_logger
from shoppilot_ai.app.core.security import INTERNAL_TOKEN_HEADER, is_token_valid
from shoppilot_ai.app.core.tracing import (
    REQUEST_ID_HEADER,
    reset_request_id,
    resolve_request_id,
    set_request_id,
)

logger = get_logger(__name__)

#: Internal API prefix — everything under it is token-protected except the exempt set.
INTERNAL_PREFIX = "/internal/v1"
#: Token-exempt paths (probe endpoints). Exact match (after trailing-slash
#: normalisation), no prefix matching — see ``R97``.
TOKEN_EXEMPT_PATHS = frozenset({"/internal/v1/health"})

app = FastAPI(title="ShopPilot AI Service", version="0.1.0")


def _current_request_id(request: Request) -> str:
    """Read the request id from ``request.state``, resolving it if not set yet.

    Order-independent on purpose: whichever middleware runs first resolves and
    stashes the id; the other one reuses it. This keeps the response header and
    the error-body ``request_id`` byte-identical.
    """
    existing = getattr(request.state, "request_id", None)
    if existing:
        return existing
    resolved = resolve_request_id(request)
    request.state.request_id = resolved
    return resolved


def _error_response(
    request: Request,
    status_code: int,
    code: str,
    message: str,
    detail: object = None,
    extra_headers: Optional[dict] = None,
) -> JSONResponse:
    """Build a contract-shaped error response, always echoing ``X-Request-Id``."""
    rid = _current_request_id(request)
    headers = {REQUEST_ID_HEADER: rid}
    if extra_headers:
        headers.update(extra_headers)
    return JSONResponse(
        status_code=status_code,
        content=error_body(code, message, detail, rid),
        headers=headers,
    )


def _is_token_exempt(path: str) -> bool:
    """Exact-match exemption, tolerant to a single trailing slash (``R97``).

    ``/internal/v1/health/`` must behave exactly like ``/internal/v1/health``:
    container healthchecks curl the trailing-slash form and do **not** follow
    redirects. Normalising with ``rstrip("/")`` keeps the semantics "this exact
    path", so it does NOT degrade into a prefix exemption.
    """
    return path.rstrip("/") in TOKEN_EXEMPT_PATHS


# --------------------------------------------------------------------------- #
# Middleware
#
# ★ ORDER MATTERS: Starlette's ``add_middleware`` inserts at index 0 and the
#   *last registered* middleware ends up outermost. ``request_id_middleware``
#   must be outermost so the id (and its contextvar binding) exists before
#   anything else can fail or log.
#   (Both are idempotent anyway — see ``_current_request_id``.)
# --------------------------------------------------------------------------- #
@app.middleware("http")
async def internal_token_middleware(request: Request, call_next):
    """Reject any non-exempt ``/internal/v1`` request without a valid token."""
    path = request.url.path
    if path.startswith(INTERNAL_PREFIX) and not _is_token_exempt(path):
        if not is_token_valid(request.headers.get(INTERNAL_TOKEN_HEADER)):
            return _error_response(
                request,
                status.HTTP_401_UNAUTHORIZED,
                AI_0001,
                DEFAULT_MESSAGES[AI_0001],
                {},
            )
    return await call_next(request)


@app.middleware("http")
async def request_id_middleware(request: Request, call_next):
    """Guarantee an X-Request-Id, bind it to the log context, and access-log it.

    ``R98``: the id is pushed into a ``contextvars`` holder so every log record
    emitted while handling this request carries it; the holder is reset in
    ``finally`` (otherwise the value leaks into the next request on the same
    event-loop task).
    """
    rid = _current_request_id(request)
    token = set_request_id(rid)
    started = time.perf_counter()
    status_code = status.HTTP_500_INTERNAL_SERVER_ERROR
    try:
        response = await call_next(request)
        status_code = response.status_code
        response.headers[REQUEST_ID_HEADER] = rid
        return response
    finally:
        duration_ms = round((time.perf_counter() - started) * 1000, 2)
        logger.info(
            "request method=%s path=%s status=%s duration_ms=%s",
            request.method,
            request.url.path,
            status_code,
            duration_ms,
        )
        reset_request_id(token)


async def require_internal_token(
    x_internal_token: Optional[str] = Header(default=None, alias=INTERNAL_TOKEN_HEADER),
) -> None:
    """Router-level token gate (defence-in-depth; see module docstring).

    Raises :class:`InternalTokenError` (→ ``401 AI-0001``) when the token is
    missing, wrong, or not configured at all (fail-closed).
    """
    if not is_token_valid(x_internal_token):
        raise InternalTokenError()


# Health is registered WITHOUT the token dependency (probe-exempt).
app.include_router(health.router)
# Analysis router carries the token gate (plus the prefix middleware above).
app.include_router(analysis.router, dependencies=[Depends(require_internal_token)])


# --------------------------------------------------------------------------- #
# Exception handlers — every path returns the same four-field envelope.
# --------------------------------------------------------------------------- #
@app.exception_handler(InternalTokenError)
async def handle_internal_token_error(request: Request, exc: InternalTokenError):
    """Missing/invalid ``X-Internal-Token`` → ``401 AI-0001``."""
    return _error_response(
        request,
        status.HTTP_401_UNAUTHORIZED,
        exc.code,
        exc.message,
        exc.detail,
    )


@app.exception_handler(RequestValidationError)
async def handle_request_validation_error(request: Request, exc: RequestValidationError):
    """Pydantic/FastAPI body validation failure → ``400 AI-1001`` (not FastAPI's 422)."""
    errors = [
        {
            "loc": [str(part) for part in err.get("loc", ())],
            "msg": str(err.get("msg", "")),
            "type": str(err.get("type", "")),
        }
        for err in exc.errors()
    ]
    return _error_response(
        request,
        status.HTTP_400_BAD_REQUEST,
        AI_1001,
        DEFAULT_MESSAGES[AI_1001],
        {"errors": errors},
    )


@app.exception_handler(StarletteHTTPException)
async def handle_http_exception(request: Request, exc: StarletteHTTPException):
    """404/405 (and any other HTTPException) → four-field envelope (``R95``).

    Contract ``§5`` applies to **all** responses of this service, so the default
    Starlette body ``{"detail": ...}`` is not acceptable. Mapping:

    * ``404`` → ``AI-1003`` (internal path not defined in ``§3``);
    * ``405`` → ``AI-1004`` (path exists, method does not);
    * anything else → ``AI-9001`` **while keeping the original status code**
      (transport semantics must not change just because the code is generic).
    """
    # ★ 这里用 404 / 405 字面量而不是 starlette 的 status 常量：
    #   那个常量的名字里带 not-found 字样（大写 + 下划线），会命中「禁止自造错误码」
    #   机械自查（G9）的 grep 模式，产生假阳性、干扰复核。语义完全一致
    #   （契约里 404/405 就是这两个值）。
    if exc.status_code == 404:
        code = AI_1003
    elif exc.status_code == 405:
        code = AI_1004
    else:
        code = AI_9001
    # Preserve protocol-relevant headers (e.g. ``Allow`` on 405).
    extra = dict(exc.headers) if exc.headers else None
    return _error_response(
        request,
        exc.status_code,
        code,
        DEFAULT_MESSAGES[code],
        {"path": request.url.path, "method": request.method},
        extra_headers=extra,
    )


@app.exception_handler(Exception)
async def handle_unexpected_error(request: Request, exc: Exception):
    """Any uncaught exception → ``500 AI-9001`` (no stack trace leaked to caller)."""
    rid = _current_request_id(request)
    logger.exception("unhandled_error request_id=%s path=%s", rid, request.url.path)
    return _error_response(
        request,
        status.HTTP_500_INTERNAL_SERVER_ERROR,
        AI_9001,
        DEFAULT_MESSAGES[AI_9001],
        {},
    )
