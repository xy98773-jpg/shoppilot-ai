"""Structured logging factory for the AI service.

Skeleton: emits field-style log lines via the standard :mod:`logging` module
(no external JSON dependency required). Each record carries a ``request_id``
field (defaulting to ``"-"``) so logs can be correlated with X-Request-Id.
"""
from __future__ import annotations

import logging
import sys

_LOG_FORMAT = (
    "%(asctime)s %(levelname)s %(name)s "
    "req_id=%(request_id)s %(message)s"
)


class _RequestIdFilter(logging.Filter):
    """Ensure every record has a ``request_id`` attribute for the formatter."""

    def filter(self, record: logging.LogRecord) -> bool:  # noqa: A003
        if not hasattr(record, "request_id"):
            record.request_id = "-"
        return True


def get_logger(name: str) -> logging.Logger:
    """Return a configured logger for ``name``.

    The logger writes to stdout with a field-style format and a request-id
    filter. Handlers are attached only once per name.
    """
    logger = logging.getLogger(name)
    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(logging.Formatter(_LOG_FORMAT))
        handler.addFilter(_RequestIdFilter())
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        logger.propagate = False
    return logger
