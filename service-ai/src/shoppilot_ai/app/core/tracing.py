"""X-Request-Id propagation utilities.

Provides:
- ``REQUEST_ID_HEADER``: the canonical request-id header name.
- ``resolve_request_id(request)``: return the inbound id, or generate a uuid4 and
  log a WARN when the caller did not supply one. The generated id is still echoed
  back (contract ``AI_SERVICE_CONTRACT.md §2:75``: the id is mandatory in the
  response, so a missing inbound id must NOT become an error — ``R91`` ①).
- ``set_request_id`` / ``reset_request_id`` / ``current_request_id``: a
  ``contextvars``-backed holder so **logging** can correlate every record with
  the request that produced it (``R98``).
- ``extract_or_generate_request_id(headers)``: header-mapping variant (kept for
  callers that do not have a ``Request`` object).

★ WHY ``contextvars`` AND NOT ``threading.local`` (R98):
  uvicorn runs all requests on a **single event-loop thread**. A ``threading.local``
  would therefore be shared by every concurrent request on that thread — request A's
  id would leak into request B's logs as soon as either one awaits. ``contextvars``
  is copied per task, so each request keeps its own value across ``await`` points.

★ ``reset_request_id`` MUST be called in a ``finally``: without it the value sticks
  to the task/connection and the next request on the same context inherits a stale id.
"""
from __future__ import annotations

import uuid
from contextvars import ContextVar, Token
from typing import Mapping, Optional

from fastapi import Request

from shoppilot_ai.app.core.logging import get_logger

logger = get_logger(__name__)

REQUEST_ID_HEADER = "X-Request-Id"

#: Per-task request id (see the module docstring for why not threading.local).
_REQUEST_ID_CTX: ContextVar[str] = ContextVar("request_id", default="")


def set_request_id(request_id: str) -> Token:
    """Bind ``request_id`` to the current context; returns the reset token."""
    return _REQUEST_ID_CTX.set(request_id)


def reset_request_id(token: Token) -> None:
    """Restore the previous context value (call in ``finally``)."""
    _REQUEST_ID_CTX.reset(token)


def current_request_id() -> str:
    """Current request id, or ``""`` when outside a request context."""
    return _REQUEST_ID_CTX.get()


def resolve_request_id(request: Request) -> str:
    """Return the inbound ``X-Request-Id`` or generate a uuid4 (with a WARN).

    The WARN carries ``request_id_missing=true`` so log pipelines can alert on
    callers that forget the header without treating it as a failure.
    """
    incoming = request.headers.get(REQUEST_ID_HEADER)
    if incoming:
        return incoming
    generated = str(uuid.uuid4())
    logger.warning(
        "request_id_missing=true generated_request_id=%s method=%s path=%s",
        generated,
        request.method,
        request.url.path,
    )
    return generated


def extract_or_generate_request_id(headers: Optional[Mapping[str, str]]) -> str:
    """Return the request id from ``headers`` or a freshly generated uuid4."""
    if headers:
        for key in (REQUEST_ID_HEADER, REQUEST_ID_HEADER.lower()):
            existing = headers.get(key)
            if existing:
                return existing
    return str(uuid.uuid4())


__all__ = [
    "REQUEST_ID_HEADER",
    "current_request_id",
    "extract_or_generate_request_id",
    "reset_request_id",
    "resolve_request_id",
    "set_request_id",
]
