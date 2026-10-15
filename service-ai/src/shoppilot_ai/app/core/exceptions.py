"""Unified exception hierarchy for the AI service."""
from __future__ import annotations


class ServiceAIBaseError(Exception):
    """Base class for all service-originated errors."""


class InternalTokenError(ServiceAIBaseError):
    """Raised when the ``X-Internal-Token`` header is missing or invalid."""
