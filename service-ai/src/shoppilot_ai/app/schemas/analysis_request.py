"""AnalyzeRequest schema — SKELETON / placeholder only.

The concrete fields (store/sku scope, analysis options, time window, ...) are
defined in M4. A minimal placeholder model is provided so the package imports
cleanly; it is intentionally empty of business semantics.
"""
from __future__ import annotations

from pydantic import BaseModel


class AnalyzeRequest(BaseModel):
    """Placeholder request body for analysis runs.

    TODO(M4): add business fields (scope, options, window, ...). Do NOT add
    business enums here — keep them out of the skeleton.
    """
