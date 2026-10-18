"""Analysis API router — SKELETON / placeholder only.

Full implementation (validate, analysis runs, cancel, status query) lands in
M4 (B14+). This module only registers the router (under ``/internal/v1``) so the
app boots and the routes are reserved. The router is protected by the
``X-Internal-Token`` gate (prefix middleware + router dependency in
``app/main.py``).

TODO(M4): implement the five endpoints below. Paths are authoritative from
``AI_SERVICE_CONTRACT.md §3:100`` / ``DESIGN_BASIS.md §13.1``.

★ ``analysis:validate`` and ``runs/{id}:cancel`` use a **colon** action suffix,
  NOT a sub-path slash (``R93``). Writing ``/analysis/validate`` here used to be
  wrong and would 404 against the Java caller.

  - POST /internal/v1/analysis:validate
  - POST /internal/v1/analysis/runs
  - GET  /internal/v1/analysis/runs/{analysis_run_id}
  - GET  /internal/v1/analysis/runs/{analysis_run_id}/result
  - POST /internal/v1/analysis/runs/{analysis_run_id}:cancel
"""
from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(prefix="/internal/v1", tags=["analysis"])
