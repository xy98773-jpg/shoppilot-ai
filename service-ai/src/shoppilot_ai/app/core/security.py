"""``X-Internal-Token`` 校验（恒定时间比较 + fail-closed）。

契约依据：
- ``AI_SERVICE_CONTRACT.md §2:74``  头名 ``X-Internal-Token``，缺失或不匹配 → ``401 AI-0001``；
- ``AI_SERVICE_CONTRACT.md §2:80``  令牌比较必须使用**恒定时间**比较（防时序侧信道）。

两条硬口径（``R92`` 裁定）：
1. **fail-closed**：``AI_INTERNAL_TOKEN`` 未配置或为空白时，一律判为**不通过**。
   早期骨架写法是 ``if expected and presented != expected``——未配置时整段鉴权被跳过
   （fail-open），等于「忘记配令牌」反而把内部接口全公开。本模块彻底去掉这个分支。
   ★ 不通过 ≠ 启动失败：进程照常起来（health 探针要活），只是内部端点全部拒绝。
2. **恒定时间**：两侧都 ``.encode("utf-8")`` 后交给 ``secrets.compare_digest``。
   长度不等时 ``compare_digest`` 自身返回 ``False``（内部按较长的那个走完全程），
   **不得**在长度不等时提前 ``return False`` 短路——那样长度就成了可测量的侧信道。
"""
from __future__ import annotations

import secrets

from shoppilot_ai.app.core import config

#: 内部令牌请求头名（契约 §2:74）。
INTERNAL_TOKEN_HEADER = "X-Internal-Token"


def expected_token() -> str:
    """返回当前配置的期望令牌；未配置/空白时返回空串。

    每次调用都读 ``config.settings``（模块属性查找，而非 import 时绑定），
    这样测试与被 ``Settings.load()`` 重建的单例都能生效。
    """
    token = config.settings.ai_internal_token
    return token if token and token.strip() else ""


def is_token_valid(presented: str | None) -> bool:
    """判断请求携带的令牌是否可用。

    :param presented: 请求头 ``X-Internal-Token`` 的值（可能为 ``None``）。
    :return: ``True`` 仅当「已配置令牌」且「恒定时间比较相等」。
    """
    expected = expected_token()
    if not expected:
        # fail-closed：未配置即拒绝（R92 ①），绝不因为「没配」而放行。
        return False
    # 两侧同类型（bytes）交给 compare_digest；长度不等也走完全程，不提前短路。
    return secrets.compare_digest(
        (presented or "").encode("utf-8"),
        expected.encode("utf-8"),
    )


__all__ = ["INTERNAL_TOKEN_HEADER", "expected_token", "is_token_valid"]
