"""Configuration surface tests (``R96``).

The defect this file locks down: the Python service used to read
``AI_SERVICE_BASE_URL`` and default it to ``http://localhost:8000`` — which is
**its own** port, while the very same env-var name on the Java side means
"where Python lives". Same name, opposite meaning, and a self-referential
default. The fix is a **rename**, not an alias: ``ai_service_base_url`` must
cease to exist entirely.
"""
from __future__ import annotations

import pytest

from shoppilot_ai.app.core.config import (
    DEFAULT_JAVA_BACKEND_BASE_URL,
    Settings,
)


def test_java_backend_base_url_default_and_old_field_removed() -> None:
    settings = Settings()

    # 1) 默认值指向 Java 的 Compose 服务名 + 端口，而不是本服务自己的 8000
    assert settings.java_backend_base_url == "http://backend-java:8080"
    assert settings.java_backend_base_url == DEFAULT_JAVA_BACKEND_BASE_URL
    assert "localhost:8000" not in settings.java_backend_base_url

    # 2) 旧字段必须彻底消失（留别名 = 把同名反义的歧义继续留在代码里）
    with pytest.raises(AttributeError):
        _ = settings.ai_service_base_url  # type: ignore[attr-defined]

    # 3) dataclass 字段名也要干净（防止有人用 getattr/字符串反射绕过去）
    assert "java_backend_base_url" in Settings.__dataclass_fields__
    assert "ai_service_base_url" not in Settings.__dataclass_fields__
