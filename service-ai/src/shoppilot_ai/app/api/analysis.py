"""Analysis API router — SKELETON / placeholder only.

Full implementation (validate, analysis runs, cancel, status query) lands in
M4. This module only registers the router (under ``/internal/v1``) so the app
boots and the routes are reserved. The router is protected by the skeleton
``X-Internal-Token`` gate applied in ``app/main.py``.

TODO(M4): implement
  - POST /internal/v1/analysis/validate
  - POST /internal/v1/analysis/runs
  - POST /internal/v1/analysis/runs/{id}:cancel
  - GET  /internal/v1/analysis/runs/{id}  (status / step results)
"""
from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(prefix="/internal/v1", tags=["analysis"])
