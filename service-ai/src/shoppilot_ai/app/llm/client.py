"""LLM 客户端 —— OpenAI 兼容聊天补全（M8-T07 / `R155`；默认接 DeepSeek）。

## 为什么是「OpenAI 兼容」而不是厂商 SDK

DeepSeek 暴露 **OpenAI 兼容**的 `POST /chat/completions`，换厂商只需换
`LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` 三个环境变量，**不改代码**。
栈内已有 `httpx`（`requirements.txt`），不引 `openai` SDK（禁新增依赖）。

## ★ 安全红线

1. **绝不复用 `core.http.get_client()`**——它会注入 `X-Internal-Token`，
   把 Java↔Python 的内部令牌发给第三方是**凭据外泄**。本模块自己建客户端，
   只带 `Authorization: Bearer <LLM_API_KEY>`。
2. **密钥不落日志**：异常信息只带状态码与响应体**前若干字符**，且响应体可能含
   请求回声，故一律截断；**不**打印 `Authorization` 头。
3. 密钥只从环境变量读（`core.config.settings.llm_api_key`），不入仓、不写默认值。

## 同步而非异步

编排 `_execute_run` 是**同步**函数（Starlette 丢线程池跑），故这里用同步
``httpx.Client``。在同步上下文里用 ``AsyncClient`` 要么报错、要么需自建事件循环。

## 错误分类（与 `LlmStatus` 7 值一一对应，勿新造）

| 异常 | `llm_status` | 重试 |
| --- | --- | --- |
| :class:`LlmTimeout` | `TIMEOUT` | 是（超时默认可恢复） |
| :class:`LlmUnavailable` | `FAILED` | 是（网络 / 5xx / 429） |
| :class:`LlmRejected` | `FAILED` | **否**（4xx＝鉴权或参数错，重试无意义） |

★ 全部异常都由 `orchestration/pipeline._run_narrative` 兜底收敛：
**规则结果一律保留**，绝不因为解释失败让整条 run 失败（铁律③）。
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

import httpx

from shoppilot_ai.app.core.config import Settings, settings

#: 聊天补全路径（相对 `base_url`）。
CHAT_COMPLETIONS_PATH = "/chat/completions"

#: 异常信息里回显的响应体最大字符数（防密钥/大段回声进日志）。
_ERROR_BODY_LIMIT = 300


class LlmError(Exception):
    """LLM 调用失败基类。**不**向外冒泡（由编排收敛为 `llm_status`）。"""


class LlmTimeout(LlmError):
    """请求超时（含连接/读取超时）⇒ `TIMEOUT`。"""


class LlmUnavailable(LlmError):
    """服务不可用：网络错误 / 5xx / 429（可重试，重试仍失败）⇒ `FAILED`。"""


class LlmRejected(LlmError):
    """被拒绝：4xx（鉴权失败、参数非法等）⇒ `FAILED`，**不重试**。"""


@dataclass(frozen=True)
class ChatMessage:
    """一条聊天消息（`role` 取 `system` / `user`）。"""

    role: str
    content: str


def _endpoint(base_url: str) -> str:
    """拼完整端点：容忍 `base_url` 带/不带结尾斜杠。

    ★ **不**自动补 `/v1`：DeepSeek 官方 `https://api.deepseek.com` 直接挂
    `/chat/completions`；若运维填了 `.../v1` 也能正常工作（原样拼接）。
    """
    return base_url.rstrip("/") + CHAT_COMPLETIONS_PATH


def _truncate(text: str) -> str:
    """截断响应体文本，避免把大段回声（可能含敏感内容）写进日志/异常。"""
    return text if len(text) <= _ERROR_BODY_LIMIT else text[:_ERROR_BODY_LIMIT] + "..."


def _extract_content(payload: dict[str, Any]) -> str:
    """取 `choices[0].message.content`；结构不符 ⇒ `LlmUnavailable`。

    ★ 不用 `.get()` 链一路吞掉异常：结构变了要**显式失败**并落到 `FAILED`，
    静默返回 `""` 会让「LLM 没解释」伪装成「解释为空」这种难查的缺陷。
    """
    try:
        choices = payload["choices"]
        content = choices[0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise LlmUnavailable(f"LLM 响应结构异常：{exc}") from exc
    if not isinstance(content, str):
        raise LlmUnavailable("LLM 响应 content 不是字符串")
    return content


def complete(
    messages: list[ChatMessage],
    *,
    cfg: Settings | None = None,
    client: httpx.Client | None = None,
) -> str:
    """调用聊天补全，返回 assistant 的 `content` 文本。

    :param messages: 消息序列（至少一条）
    :param cfg: 配置注入点（测试用；缺省用模块级 `settings`）
    :param client: HTTP 客户端注入点（测试用；缺省内部新建并关闭）
    :raises LlmRejected: 未配置密钥，或 4xx（不重试）
    :raises LlmTimeout: 超时（重试耗尽）
    :raises LlmUnavailable: 网络错误 / 5xx / 429（重试耗尽）或响应结构异常
    """
    conf = cfg or settings
    if not conf.llm_configured:
        # ★ fail-safe：未配置密钥不算「服务故障」，但也不该静默当成成功。
        #   调用方（narrative）会先判 llm_configured 走 SKIPPED；这里是防御性兜底。
        raise LlmRejected("LLM 未配置（缺 LLM_API_KEY 或 LLM_BASE_URL）")

    url = _endpoint(conf.llm_base_url)
    headers = {
        "Authorization": f"Bearer {conf.llm_api_key}",
        "Content-Type": "application/json",
    }
    payload: dict[str, Any] = {
        "model": conf.llm_model,
        "messages": [{"role": m.role, "content": m.content} for m in messages],
        "temperature": conf.llm_temperature,
        "max_tokens": conf.llm_max_tokens,
        "stream": False,
        # DeepSeek 支持 JSON 输出模式：让模型严格输出 JSON，减轻下游解析负担。
        # ★ 若换成不支持该参数的厂商，需要去掉本行（届时按 VALIDATION_FAILED 兜底）。
        "response_format": {"type": "json_object"},
    }

    attempts = max(0, conf.llm_max_retries) + 1
    timeout = conf.llm_timeout_seconds
    owns_client = client is None
    http = client or httpx.Client(timeout=timeout)

    try:
        for attempt in range(1, attempts + 1):
            try:
                response = http.post(url, json=payload, headers=headers)
            except httpx.TimeoutException as exc:
                if attempt < attempts:
                    _backoff(attempt)
                    continue
                raise LlmTimeout(f"LLM 请求超时（{timeout}s）") from exc
            except httpx.HTTPError as exc:
                # 网络层错误（连接失败/DNS 等）
                if attempt < attempts:
                    _backoff(attempt)
                    continue
                raise LlmUnavailable(f"LLM 网络错误：{exc}") from exc

            status = response.status_code
            if status == 429 or status >= 500:
                # 可恢复：限流/服务端故障
                if attempt < attempts:
                    _backoff(attempt)
                    continue
                raise LlmUnavailable(
                    f"LLM 服务不可用（HTTP {status}）：{_truncate(response.text)}"
                )
            if status >= 400:
                # 4xx（除 429）：鉴权/参数错误 ⇒ 重试无意义，立即失败
                raise LlmRejected(
                    f"LLM 拒绝请求（HTTP {status}）：{_truncate(response.text)}"
                )

            try:
                body = response.json()
            except ValueError as exc:
                raise LlmUnavailable(
                    f"LLM 响应不是合法 JSON：{_truncate(response.text)}"
                ) from exc
            return _extract_content(body)

        # 理论上不可达（循环内必 return 或 raise）；留作静态检查兜底。
        raise LlmUnavailable("LLM 调用重试耗尽")
    finally:
        if owns_client:
            http.close()


def _backoff(attempt: int) -> None:
    """线性退避（0.5s → 1.0s → ...）。同步 sleep：本函数在线程池中执行。"""
    time.sleep(0.5 * attempt)


__all__ = [
    "CHAT_COMPLETIONS_PATH",
    "ChatMessage",
    "LlmError",
    "LlmRejected",
    "LlmTimeout",
    "LlmUnavailable",
    "complete",
]
