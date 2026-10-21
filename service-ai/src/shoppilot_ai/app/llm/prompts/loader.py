"""Prompt 模板加载与渲染（`PROMPT_DESIGN.md §1.2` / `§1.3` / `§5.1`）。

## 位置说明

`§1.1` 规定模板目录为 `app/llm/prompts/`（`<name>.<version>.md`）。本加载器放在
该目录**内部**叫 `loader.py`——★ 不能叫 `app/llm/prompts.py`：那会与
`prompts/` 包**同名冲突**，Python 导入时包优先，模块会被静默遮蔽。

## 组织方式（`§1.1`）

模板文件内部分区用 `## SYSTEM` / `## USER` / `## OUTPUT_SCHEMA` 三个标题分隔。
本模块在**导入时**扫描并注册，按 `(name, version)` 取；缺失即抛（fail-fast，`§1.2`）。

## ★ 渲染引擎：受限字符串替换，**不用 Jinja2**（`§1.3` 结论 / D-30）

本项目 Prompt 结构固定、无循环/条件；少一个可执行模板引擎 = 少一个注入/越权面。
渲染器只做三件事：

1. **变量白名单**：只替换模板里声明的 `{{ var }}`；上下文多出的键**直接忽略**
   （`§1.3` 理由 4）。
2. **值清洗**（`§5.1`）：去控制字符（保留换行）、折叠连续空白、截断长度。
3. **分隔符转义**：数据值里的 `{{` / `}}` 替换为全角 `［［` / `］］`——
   否则会被**二次渲染**，是典型的模板注入面。

★ 渲染结果**绝不**被 `eval` / `exec` / 二次格式化（`§1.3` 禁止条款）。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from shoppilot_ai.app.llm.client import ChatMessage

#: 模板目录（`§1.1`）：即本模块所在的 `prompts/`。
PROMPT_DIR = Path(__file__).resolve().parent

#: 文件名约定：`<name>.<version>.md`（`§1.1`）。
_FILENAME_PATTERN = re.compile(r"^(?P<name>[a-z0-9_]+)\.(?P<version>v\d+)\.md$")

#: 分区标题（`§1.1`）。
_SECTION_HEADERS = ("SYSTEM", "USER", "OUTPUT_SCHEMA")

#: 变量占位 `{{ var_name }}`（`§1.1`）。★ 只允许标识符，杜绝表达式。
_VAR_PATTERN = re.compile(r"\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}")

#: 单个变量值的最大字符数（`§5.1` 长度截断兜底；字段级上限在 `context_builder`）。
MAX_VALUE_CHARS = 2000

#: 上下文缺该键时替换成的文本（`§2.1` SYSTEM 规则 3 要求"明确声明缺失"）。
MISSING_TEXT = "未提供"


class PromptError(Exception):
    """模板缺失或结构非法（fail-fast，`§1.2`）。"""


@dataclass(frozen=True)
class PromptTemplate:
    """一个已解析的模板（`name.version`）。"""

    name: str
    version: str
    system: str
    user: str
    output_schema: str

    @property
    def key(self) -> str:
        """`name.version`（如 `explain_risk.v1`，与 `analysis_runs.prompt_version` 同形）。"""
        return f"{self.name}.{self.version}"

    def messages(self, context: Mapping[str, object]) -> list[ChatMessage]:
        """渲染出 `[system, user]` 两条消息。

        ★ ★ 必须把 `OUTPUT_SCHEMA` 带给模型（真机教训，M8-T07）：
        初版只发 SYSTEM + USER，**漏发了模板的 OUTPUT_SCHEMA 分区** ⇒
        DeepSeek 自由发挥成 `{type, conclusion, main_basis, ...}`，
        与期望的 `{explanation, evidence, confidence, ...}` 完全不同，
        校验直接 `explanation Field required` 失败。
        注意：`response_format=json_object` **只保证输出是合法 JSON**，
        **不**保证遵循业务 Schema——Schema 必须由 Prompt 显式给出。

        ★ Mock 测试掩盖了这个缺陷（喂的是手写合法 payload），
        故补了 `test_messages_include_output_schema` 钉死它。
        """
        system = self.system
        if self.output_schema.strip():
            system = (
                f"{self.system}\n\n"
                "【输出 JSON Schema】（必须**严格**符合，字段名与类型均不得改动）\n"
                f"{self.output_schema}"
            )
        return [
            ChatMessage(role="system", content=system),
            ChatMessage(role="user", content=render(self.user, context)),
        ]


def render(template: str, context: Mapping[str, object]) -> str:
    """受限替换：只替换模板声明的变量，值经清洗与转义。"""

    def _replace(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in context or context[key] is None:
            return MISSING_TEXT
        return _sanitize(context[key])

    return _VAR_PATTERN.sub(_replace, template)


def _sanitize(value: object) -> str:
    """把任意值清洗成可安全嵌入 Prompt 的文本（`§5.1`）。"""
    text = value if isinstance(value, str) else str(value)
    # 1) 去控制字符（保留换行/制表符）
    text = "".join(ch for ch in text if ch == "\n" or ch == "\t" or ord(ch) >= 32)
    # 2) 折叠连续空白
    text = re.sub(r"[ ]{3,}", " ", text)
    # 3) ★ 转义模板分隔符：防二次渲染（注入防护核心）
    text = text.replace("{{", "［［").replace("}}", "］］")
    # 4) 长度截断
    if len(text) > MAX_VALUE_CHARS:
        text = text[:MAX_VALUE_CHARS] + "…"
    return text


def _parse_sections(text: str) -> dict[str, str]:
    """按 `## <HEADER>` 切分正文；缺任一分区 ⇒ `PromptError`。"""
    sections: dict[str, str] = {}
    current: str | None = None
    buffer: list[str] = []
    for line in text.splitlines():
        header = line.strip()
        if header.startswith("## "):
            if current:
                sections[current] = "\n".join(buffer).strip()
            current = header[3:].strip()
            buffer = []
            continue
        if current:
            buffer.append(line)
    if current:
        sections[current] = "\n".join(buffer).strip()

    missing = [h for h in _SECTION_HEADERS if h not in sections]
    if missing:
        raise PromptError(f"模板缺少分区：{missing}")
    return sections


def load_templates(directory: Path | None = None) -> dict[str, PromptTemplate]:
    """扫描目录，返回 `{name.version: PromptTemplate}`。"""
    root = directory or PROMPT_DIR
    registry: dict[str, PromptTemplate] = {}
    if not root.is_dir():
        return registry
    for path in sorted(root.glob("*.md")):
        match = _FILENAME_PATTERN.match(path.name)
        if not match:
            # 非模板文件（如 README.md）静默跳过
            continue
        sections = _parse_sections(path.read_text(encoding="utf-8"))
        template = PromptTemplate(
            name=match.group("name"),
            version=match.group("version"),
            system=sections["SYSTEM"],
            user=sections["USER"],
            output_schema=sections["OUTPUT_SCHEMA"],
        )
        registry[template.key] = template
    return registry


#: 进程级注册表（`§1.2`：启动时加载）。测试可用 `load_templates(dir)` 覆盖。
REGISTRY: dict[str, PromptTemplate] = load_templates()

#: 首期默认 Prompt（`§2` 首期结论：`explain_risk.v1`）。
DEFAULT_PROMPT_KEY = "explain_risk.v1"


def get_template(
    prompt_version: str | None,
    *,
    registry: dict[str, PromptTemplate] | None = None,
) -> PromptTemplate:
    """按 `prompt_version`（如 `explain_risk.v1`）取模板。

    - `None`/空 ⇒ 用 `DEFAULT_PROMPT_KEY`（Java 未传时兜底，**不擅自升版**）；
    - 版本不存在 ⇒ `PromptError`（fail-fast，`§1.2`）。
    """
    table = registry if registry is not None else REGISTRY
    key = (prompt_version or "").strip() or DEFAULT_PROMPT_KEY
    template = table.get(key)
    if template is None:
        raise PromptError(f"未注册的 prompt_version={key!r}（已注册：{sorted(table)}）")
    return template


__all__ = [
    "DEFAULT_PROMPT_KEY",
    "MISSING_TEXT",
    "MAX_VALUE_CHARS",
    "PROMPT_DIR",
    "PromptError",
    "PromptTemplate",
    "REGISTRY",
    "get_template",
    "load_templates",
    "render",
]
