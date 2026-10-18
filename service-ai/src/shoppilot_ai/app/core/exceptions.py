"""Unified exception hierarchy for the AI service.

The classes now live in :mod:`shoppilot_ai.app.core.errors` (they carry the
contract error code and structured ``detail``). This module is kept as a thin
re-export so existing imports (``from ...core.exceptions import InternalTokenError``)
keep working and there is exactly **one** class object per exception type
(important: ``app.add_exception_handler`` matches by identity).
"""
from __future__ import annotations

from shoppilot_ai.app.core.errors import (
    InternalTokenError,
    SchemaValidationError,
    ServiceAIBaseError,
)

__all__ = ["ServiceAIBaseError", "InternalTokenError", "SchemaValidationError"]
