"""X-Request-Id propagation utilities.

Provides:
- ``REQUEST_ID_HEADER``: the canonical request-id header name.
- ``extract_or_generate_request_id(headers)``: pull the id from a header
  mapping (case-insensitive) or generate a ``uuid4`` when absent.
- ``get_request_id(request)``: a FastAPI dependency that returns the id for
  the current request, generating and stashing one on ``request.state`` when
  missing.
"""
from __future__ import annotations

import uuid
from typing import Mapping, Optional

from fastapi import Request

REQUEST_ID_HEADER = "X-Request-Id"


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
