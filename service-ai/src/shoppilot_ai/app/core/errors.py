"""统一错误体与错误码（Java ↔ Python 内部契约）。

契约依据（``AI_SERVICE_CONTRACT.md``）：
- §2:74  ``X-Internal-Token`` 必填；缺失或不匹配 → ``401 AI-0001``；
- §5:639-660  错误体为**四字段** ``{code, message, detail, request_id}``，
  ``message`` 为英文技术描述，``request_id`` 回显请求头 ``X-Request-Id``；
  §5 已明确「适用范围 = service-ai 的**全部** HTTP 响应，无例外」——故 404/405 也必须走本信封；
- ``ERROR_CODES.md §2.11``  内部错误码**全 19 条**（本模块是 Python 侧唯一镜像）。

设计约束：
1. 本模块是**错误码与错误体的唯一出口**；其它模块不得自造错误码字符串。
   早期骨架自造的 4 个非契约码（服务错误 / 令牌非法 / 校验失败 / 未找到 四个英文别名）
   均不在 ``ERROR_CODES.md`` 表内，已按 B13-令 §G10 / §F12 全部删除，统一改用 AI-xxxx。
   （注：先前此处误引为 ``R94``；``R94`` 的真实内容是 V1.0.9 迁移号冲突，与本条无关。）
2. ``error_body`` **恒定返回四键**：调用方不必判断 ``detail`` / ``request_id`` 是否存在，
   Java 侧的反序列化也不会因缺键而 NPE。
3. ``request_id`` 缺失时给空串而非 ``None``：保持四键且类型稳定（字符串），
   便于 Java 侧按 ``String`` 绑定。

★ B16（R101）：``ServiceAIBaseError`` 以往只有类属性 ``code``/``http_status``，构造器无法表达
「同一个基类抛不同契约码」，且 ``app/main.py`` 只给 ``InternalTokenError`` 注册了处理器
⇒ ``AI-6001``/``AI-6002`` 等业务错误会落 ``Exception → 500 AI-9001``（码与状态全丢）。
本版：① 19 码常量与 HTTP 状态表补全；② 构造器支持 ``code=`` / ``http_status=`` 覆盖；
③ ``main.py`` 注册通用处理器（见 ``handle_service_ai_error``）。
"""
from __future__ import annotations

from typing import Any

# ---- 错误码常量（ERROR_CODES.md §2.11，全 19 条）---------------------------
#: 内部令牌缺失或不匹配（HTTP 401）。
AI_0001 = "AI-0001"
#: 请求体 Schema 校验失败（HTTP 400）。
AI_1001 = "AI-1001"
#: ``analysis_run_id`` 缺失或非法（HTTP 400）。
AI_1002 = "AI-1002"
#: 内部路径不存在（HTTP 404）——请求了契约 §3 未定义的内部路径（R95）。
AI_1003 = "AI-1003"
#: 内部方法不允许（HTTP 405）——路径存在但 HTTP 方法不匹配（R95）。
AI_1004 = "AI-1004"
#: SKU 必需数据缺失（HTTP 422）。
AI_2001 = "AI-2001"
#: 窗口内销售数据不足（HTTP 422）。
AI_2002 = "AI-2002"
#: 规则引擎执行失败（HTTP 500）。
AI_3001 = "AI-3001"
#: 补货计算失败（HTTP 500）。
AI_4001 = "AI-4001"
#: LLM 不可用（HTTP 502）。
AI_5001 = "AI-5001"
#: LLM 调用超时（HTTP 504）。
AI_5002 = "AI-5002"
#: LLM 输出非法 JSON（HTTP 422）。
AI_5003 = "AI-5003"
#: LLM 输出未通过 Schema 校验（HTTP 422）。
AI_5004 = "AI-5004"
#: 分析任务不存在（HTTP 404）——业务 404，**不是** ``AI-1003``（R101 裁定）。
AI_6001 = "AI-6001"
#: 任务状态不允许（HTTP 409）。
AI_6002 = "AI-6002"
#: 任务已取消（HTTP 409）。
AI_6003 = "AI-6003"
#: 并发超限（HTTP 429）。
AI_7001 = "AI-7001"
#: SKU 数量超上限（HTTP 400）。
AI_7002 = "AI-7002"
#: AI 服务未预期错误（HTTP 500）。
AI_9001 = "AI-9001"

#: 各码的默认英文技术描述（契约 §5 要求 message 为英文技术描述）。
DEFAULT_MESSAGES: dict[str, str] = {
    AI_0001: "Invalid or missing X-Internal-Token",
    AI_1001: "Request payload failed schema validation",
    AI_1002: "Missing or invalid analysis_run_id",
    AI_1003: "Internal path not found",
    AI_1004: "Internal method not allowed",
    AI_2001: "Required SKU data is missing",
    AI_2002: "Insufficient sales data within the window",
    AI_3001: "Risk rule engine failed",
    AI_4001: "Replenishment calculation failed",
    AI_5001: "LLM service unavailable",
    AI_5002: "LLM call timed out",
    AI_5003: "LLM output is not valid JSON",
    AI_5004: "LLM output failed schema validation",
    AI_6001: "Analysis run not found",
    AI_6002: "Analysis run status does not allow this operation",
    AI_6003: "Analysis run has been cancelled",
    AI_7001: "Concurrency limit exceeded",
    AI_7002: "Too many SKUs in the request",
    AI_9001: "Unexpected internal error",
}

#: 错误码 → HTTP 状态（``ERROR_CODES.md §2.11`` 的「HTTP」列，逐条对齐）。
#: Java 侧 ``ErrorCodeHttpStatus`` 是同一张表的镜像；两侧不一致即为缺陷。
HTTP_STATUS_BY_CODE: dict[str, int] = {
    AI_0001: 401,
    AI_1001: 400,
    AI_1002: 400,
    AI_1003: 404,
    AI_1004: 405,
    AI_2001: 422,
    AI_2002: 422,
    AI_3001: 500,
    AI_4001: 500,
    AI_5001: 502,
    AI_5002: 504,
    AI_5003: 422,
    AI_5004: 422,
    AI_6001: 404,
    AI_6002: 409,
    AI_6003: 409,
    AI_7001: 429,
    AI_7002: 400,
    AI_9001: 500,
}


def error_body(
    code: str,
    message: str,
    detail: Any = None,
    request_id: str | None = None,
) -> dict[str, Any]:
    """构造契约四字段错误体。

    :param code: 契约错误码（``ERROR_CODES.md §2.11`` 的 ``AI-xxxx``）。
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
    """服务内部错误的基类：带契约错误码、HTTP 状态与结构化 ``detail``。

    继承 ``Exception`` 而非 ``HTTPException``：HTTP 状态码由 ``app/main.py`` 的异常处理器
    统一输出，业务代码只表达「哪一类错误」，不关心传输层。

    ★ 三种用法（``R101``）：

    .. code-block:: python

        raise InternalTokenError()                          # 子类固定码：401 AI-0001
        raise SchemaValidationError(detail={"errors": []})  # 子类固定码：400 AI-1001
        raise ServiceAIBaseError(code=AI_6001, detail={"analysis_run_id": 3})  # 任意契约码：404

    ``code`` 必须是 ``ERROR_CODES.md §2.11`` 已登记的 19 条之一；未登记的码一律
    ``ValueError``（**禁止自造错误码**，见模块 docstring）。显式传 ``code`` 时，HTTP 状态
    默认取 :data:`HTTP_STATUS_BY_CODE`，也可用 ``http_status=`` 再显式覆盖。
    """

    code: str = AI_9001
    http_status: int = 500

    def __init__(
        self,
        message: str | None = None,
        detail: Any = None,
        *,
        code: str | None = None,
        http_status: int | None = None,
    ) -> None:
        resolved_code = code if code is not None else type(self).code
        if resolved_code not in DEFAULT_MESSAGES:
            raise ValueError(
                f"Unknown contract error code {resolved_code!r}; "
                f"use one of ERROR_CODES.md §2.11 (see core.errors.DEFAULT_MESSAGES)."
            )
        self.code = resolved_code
        if http_status is not None:
            self.http_status = http_status
        elif code is not None:
            # 通用基类 + 显式码：状态跟随契约表
            self.http_status = HTTP_STATUS_BY_CODE[resolved_code]
        else:
            # 子类固定码：沿用子类声明（与 HTTP_STATUS_BY_CODE 的一致性由测试守护）
            self.http_status = type(self).http_status
        self.message = message or DEFAULT_MESSAGES[resolved_code]
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
    "AI_1002",
    "AI_1003",
    "AI_1004",
    "AI_2001",
    "AI_2002",
    "AI_3001",
    "AI_4001",
    "AI_5001",
    "AI_5002",
    "AI_5003",
    "AI_5004",
    "AI_6001",
    "AI_6002",
    "AI_6003",
    "AI_7001",
    "AI_7002",
    "AI_9001",
    "DEFAULT_MESSAGES",
    "HTTP_STATUS_BY_CODE",
    "ServiceAIBaseError",
    "InternalTokenError",
    "SchemaValidationError",
    "error_body",
]
