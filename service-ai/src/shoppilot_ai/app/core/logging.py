"""Structured logging factory for the AI service.

Skeleton: emits field-style log lines via the standard :mod:`logging` module
(no external JSON dependency required). Each record carries a ``request_id``
field so logs can be correlated with ``X-Request-Id``.

★ R98 —— ``request_id`` 不再恒为 ``"-"``：
  B13 的 ``_RequestIdFilter`` 只在**缺失时补默认值**，全仓没有任何代码把当前请求 id
  写进 ``record.request_id``，于是所有日志都是 ``req_id=-``，§2:75「记入自身日志」
  无从验证。本批改为从 ``core.tracing`` 的 ``contextvars`` 里取**真实值**，
  取不到（例如启动期、后台任务）才补 ``"-"``。

  过滤器挂在 **logger** 上而不是 handler 上：这样无论后续加多少个 handler
  （stdout / 文件 / 测试的 caplog），拿到的都是同一个已填充的 record。
"""
from __future__ import annotations

import logging
import sys

_LOG_FORMAT = (
    "%(asctime)s %(levelname)s %(name)s "
    "req_id=%(request_id)s %(message)s"
)


class _RequestIdFilter(logging.Filter):
    """Populate ``record.request_id`` from the per-task contextvar."""

    def filter(self, record: logging.LogRecord) -> bool:  # noqa: A003
        if not getattr(record, "request_id", ""):
            # 延迟导入：tracing 依赖本模块的 get_logger，模块级导入会成环。
            from shoppilot_ai.app.core.tracing import current_request_id

            record.request_id = current_request_id() or "-"
        return True


def get_logger(name: str) -> logging.Logger:
    """Return a configured logger for ``name``.

    The logger writes to stdout with a field-style format and carries a
    request-id filter. Handlers are attached only once per name.
    """
    logger = logging.getLogger(name)
    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(logging.Formatter(_LOG_FORMAT))
        # ★ 过滤器挂两处：
        #   1) logger 上——record 在离开本 logger 前就带好 request_id，任何后续
        #      handler（含测试的 caplog）都读得到；
        #   2) handler 上——兜底。子 logger（如 core.tracing）的记录不会经过本 logger
        #      的 filter，只会进到 handler；若这里不兜，格式串里的 %(request_id)s
        #      会因缺键抛 ValueError，整条日志丢失。
        logger.addFilter(_RequestIdFilter())
        handler.addFilter(_RequestIdFilter())
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        logger.propagate = False
    return logger
