"""AnalyzeResponse and per-step result schemas — SKELETON / placeholder only.

Concrete result shapes (validate result, stats, per-rule findings, suggestion,
LLM narrative) are defined in M4. Placeholder models are provided for clean
imports; no business semantics yet.
"""
from __future__ import annotations

from pydantic import BaseModel


class AnalyzeResponse(BaseModel):
    """Placeholder response for an analysis run.

    TODO(M4): add run id, status, and per-step results.
    """
