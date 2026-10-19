"""Shared, non-business schemas and generic constants.

IMPORTANT: this module MUST NOT contain Java-side business enum values. It only
holds generic models (response envelope, pagination, generic error payload)
usable across endpoints.

★ Error codes are **not** re-defined here (B13-令 §G10 / §F12): the only source is
``shoppilot_ai.app.core.errors``, which mirrors ``ERROR_CODES.md §2.11``. The
former self-invented constants (four English aliases for "service error",
"invalid token", "validation error" and "not found") do not exist in the contract
table and have been deleted.

★ B16（M4-T06）：新增泛型信封 :class:`Envelope`。契约 `AI_SERVICE_CONTRACT §5` 要求
**成功与失败同构**——都是 `{code, data, request_id}`（错误体额外用 `message`/`detail`，
那是四字段错误信封，见 :class:`ErrorDetail`）。本类只覆盖**成功**路径：
`code="OK"`、`data` 为具体业务模型、`request_id` 回显。错误路径仍由
``core.errors.error_body`` 产出（它恒定四键，永不返回 `data`）。
"""
from __future__ import annotations

from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict

# Re-exported for callers that build error payloads from a schema module.
from shoppilot_ai.app.core.errors import (  # noqa: F401  (re-export)
    AI_0001,
    AI_1001,
    AI_1002,
    AI_1003,
    AI_1004,
    AI_2001,
    AI_2002,
    AI_3001,
    AI_4001,
    AI_5001,
    AI_5002,
    AI_5003,
    AI_5004,
    AI_6001,
    AI_6002,
    AI_6003,
    AI_7001,
    AI_7002,
    AI_9001,
)

T = TypeVar("T")


class Envelope(BaseModel, Generic[T]):
    """Contract success envelope: ``{code: "OK", data: <T>, request_id: "..."}``.

    ``extra="ignore"``: 后续批次若给 `data` 增加字段，旧客户端不应整体失败。
    """

    model_config = ConfigDict(extra="ignore")

    code: str = "OK"
    data: T | None = None
    request_id: str = ""


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


__all__ = [
    "Envelope",
    "ErrorDetail",
    "PageMeta",
    "AI_0001",
    "AI_1001",
    "AI_1002",
    "AI_1003",
    "AI_1004",
    "AI_2001",
    "AI_2002",
    "AI_3001",
    "AI_4001",
    "AI_5001",
    "AI_5002",
    "AI_5003",
    "AI_5004",
    "AI_6001",
    "AI_6002",
    "AI_6003",
    "AI_7001",
    "AI_7002",
    "AI_9001",
]
