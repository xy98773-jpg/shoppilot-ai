"""Shared, non-business schemas and generic constants.

IMPORTANT: this module MUST NOT contain Java-side business enum values. It only
holds generic models (response envelope, pagination, generic error-code string
constants) usable across endpoints.
"""
from __future__ import annotations

from pydantic import BaseModel


class ErrorDetail(BaseModel):
    """Generic error payload."""

    code: str
    message: str


class PageMeta(BaseModel):
    """Generic pagination metadata."""

    page: int = 1
    size: int = 20
    total: int = 0


# Generic error-code string constants (NOT business enums).
ERROR_CODE_SERVICE_ERROR = "SERVICE_ERROR"
ERROR_CODE_INTERNAL_TOKEN_INVALID = "INTERNAL_TOKEN_INVALID"
ERROR_CODE_VALIDATION = "VALIDATION_ERROR"
ERROR_CODE_NOT_FOUND = "NOT_FOUND"
