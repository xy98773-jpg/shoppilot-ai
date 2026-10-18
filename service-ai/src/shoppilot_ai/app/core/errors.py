"""统一错误体与错误码（Java ↔ Python 内部契约）。

契约依据（``AI_SERVICE_CONTRACT.md``）：
- §2:74  ``X-Internal-Token`` 必填；缺失或不匹配 → ``401 AI-0001``；
- §5:639-660  错误体为**四字段** ``{code, message, detail, request_id}``，
  ``message`` 为英文技术描述，``request_id`` 回显请求头 ``X-Request-Id``；
- ``ERROR_CODES.md:241-257``  ``AI-0001``(401) / ``AI-1001``(400) / ``AI-9001``(500)。

设计约束：
1. 本模块是**错误码与错误体的唯一出口**；其它模块不得自造错误码字符串。
   早期骨架自造的 4 个非契约码（服务错误 / 令牌非法 / 校验失败 / 未找到 四个英文别名）
   均不在 ``ERROR_CODES.md`` 表内，已按 ``R94`` 全部删除，统一改用 AI-xxxx。
2. ``error_body`` **恒定返回四键**：调用方不必判断 ``detail`` / ``request_id`` 是否存在，
   Java 侧的反序列化也不会因缺键而 NPE。
3. ``request_id`` 缺失时给空串而非 ``None``：保持四键且类型稳定（字符串），
   便于 Java 侧按 ``String`` 绑定。
"""
from __future__ import annotations

from typing import Any

# ---- 错误码常量（ERROR_CODES.md §2.11）-------------------------------------
#: 内部令牌缺失或不匹配（HTTP 401）。
AI_0001 = "AI-0001"
#: 请求体 Schema 校验失败（HTTP 400）。
AI_1001 = "AI-1001"
#: AI 服务未预期错误（HTTP 500）。
AI_9001 = "AI-9001"

#: 各码的默认英文技术描述（契约 §5 要求 message 为英文技术描述）。
DEFAULT_MESSAGES: dict[str, str] = {
    AI_0001: "Invalid or missing X-Internal-Token",
    AI_1001: "Request payload failed schema validation",
    AI_9001: "Unexpected internal error",
}


def error_body(
    code: str,
    message: str,
    detail: Any = None,
    request_id: str | None = None,
) -> dict[str, Any]:
    """构造契约四字段错误体。

    :param code: 契约错误码（``AI-0001`` / ``AI-1001`` / ``AI-9001``）。
    :param message: 英文技术描述。
    :param detail: 结构化明细；``None`` 时给空字典 ``{}``（**不是** ``None``）。
    :param request_id: 回显的 ``X-Request-Id``；``None`` 时给空串。
    :return: 恰好包含 ``code`` / ``message`` / ``detail`` / ``request_id`` 四键的字典。
    """
    return {
        "code": code,
        "message": message,
        "detail": {} if detail is None else detail,
        "request_id": "" if request_id is None else request_id,
    }


class ServiceAIBaseError(Exception):
    """服务内部错误的基类：带契约错误码与结构化 ``detail``。

    继承 ``Exception`` 而非 ``HTTPException``：HTTP 状态码由 ``app/main.py`` 的异常处理器
    统一决定，业务代码只表达「哪一类错误」，不关心传输层。
    """

    code: str = AI_9001
    http_status: int = 500

    def __init__(self, message: str | None = None, detail: Any = None) -> None:
        self.message = message or DEFAULT_MESSAGES[self.code]
        self.detail: Any = {} if detail is None else detail
        super().__init__(self.message)


class InternalTokenError(ServiceAIBaseError):
    """``X-Internal-Token`` 缺失或不匹配 → ``401 AI-0001``。"""

    code = AI_0001
    http_status = 401


class SchemaValidationError(ServiceAIBaseError):
    """请求体不满足 Schema → ``400 AI-1001``（不是 FastAPI 默认的 422）。"""

    code = AI_1001
    http_status = 400


__all__ = [
    "AI_0001",
    "AI_1001",
    "AI_9001",
    "DEFAULT_MESSAGES",
    "ServiceAIBaseError",
    "InternalTokenError",
    "SchemaValidationError",
    "error_body",
]
