"""Health probe endpoint.

``GET /internal/v1/health`` -> ``{"status": "UP"}`` (HTTP 200).

This endpoint is intentionally exempt from the X-Internal-Token check so that
liveness/readiness probes can run without credentials. See ``app/main.py``.

★ ``R97`` —— 尾斜杠变体必须**直接 200**，不能靠 307 重定向：
  容器 healthcheck 用 ``curl`` 默认**不跟随**重定向，307 会被判为失败。
  故这里给同一个 handler 同时注册 ``/health`` 与 ``/health/`` 两条路由，
  由路由精确匹配直接命中，而不是让 Starlette 的 ``redirect_slashes`` 去 307。
  （``app/main.py`` 的豁免判定也做了 ``rstrip("/")`` 归一，两者配套。）
"""
from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(prefix="/internal/v1", tags=["internal"])


@router.get("/health")
@router.get("/health/")
async def health() -> dict:
    """Return service liveness status."""
    return {"status": "UP"}
