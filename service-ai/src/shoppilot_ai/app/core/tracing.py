"""X-Request-Id propagation utilities.

Provides:
- ``REQUEST_ID_HEADER``: the canonical request-id header name.
- ``resolve_request_id(request)``: return the inbound id, or generate a uuid4 and
  log a WARN when the caller did not supply one. The generated id is still echoed
  back (contract ``AI_SERVICE_CONTRACT.md §2:75``: the id is mandatory in the
  response, so a missing inbound id must NOT become an error — ``R91`` ①).
- ``extract_or_generate_request_id(headers)``: header-mapping variant (kept for
  callers that do not have a ``Request`` object).
- ``get_request_id(request)``: a FastAPI dependency wrapper.
"""
from __future__ import annotations

import uuid
from typing import Mapping, Optional

from fastapi import Request

from shoppilot_ai.app.core.logging import get_logger

logger = get_logger(__name__)

REQUEST_ID_HEADER = "X-Request-Id"


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


async def get_request_id(request: Request) -> str:
    """FastAPI dependency: resolve the X-Request-Id for the request.

    Stores the resolved id on ``request.state.request_id`` so downstream code
    and the response header middleware can reuse it.
    """
    rid = request.headers.get(REQUEST_ID_HEADER)
    if not rid:
        rid = str(uuid.uuid4())
    request.state.request_id = rid
    return rid
