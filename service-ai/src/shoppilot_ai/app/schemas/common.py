"""Shared, non-business schemas and generic constants.

IMPORTANT: this module MUST NOT contain Java-side business enum values. It only
holds generic models (response envelope, pagination, generic error payload)
usable across endpoints.

★ Error codes are **not** re-defined here (``R94``): the only source is
``shoppilot_ai.app.core.errors``, which mirrors ``ERROR_CODES.md §2.11``. The
former self-invented constants (four English aliases for "service error",
"invalid token", "validation error" and "not found") do not exist in the contract
table and have been deleted.
"""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel

# Re-exported for callers that build error payloads from a schema module.
from shoppilot_ai.app.core.errors import (  # noqa: F401  (re-export)
    AI_0001,
    AI_1001,
    AI_9001,
)


class ErrorDetail(BaseModel):
    """Contract error payload (``AI_SERVICE_CONTRACT.md §5:639-660``).

    Four fields: ``code`` / ``message`` / ``detail`` / ``request_id``. ``detail``
    defaults to an empty object so the shape is stable for Java-side binding.
    """

    code: str
    message: str
    detail: Any = {}
    request_id: str = ""


class PageMeta(BaseModel):
    """Generic pagination metadata."""

    page: int = 1
    size: int = 20
    total: int = 0


__all__ = ["ErrorDetail", "PageMeta", "AI_0001", "AI_1001", "AI_9001"]
