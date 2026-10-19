"""`AI_SERVICE_CONTRACT.md §3.2` 的 Pydantic v2 请求模型（M4-T06）。

字段口径来源（按 SSOT 优先级）：
- `AI_SERVICE_CONTRACT.md §3.2` 请求体完整 Schema（`analysis_run_id` / `team_id` /
  `warehouse_id` / `scope` / `date_from` / `date_to` / `params` / `algorithm_version` /
  `prompt_version` / `model_name` / `items`）；
- `AI_SERVICE_CONTRACT.md §3.3` 裁剪原则（只传计算必需字段）；
- `ANALYSIS_CONTRACT.md §1.1` run 级参数（默认值/约束）、`§1.2` item 级字段。

★ 三条刻意的口径（与项目负责人裁定一致，勿按契约字面「修正」）：

1. **`warehouse_id` / `prompt_version` 可缺省**（`R112`）：契约 §3.2 标 ✅ 必填，但 Java
   `AiRunCreateRequest` 在缺省时按 `NON_NULL` 省略（仓库为空 = 默认仓；`prompt_version` 只在
   `enable_llm=true` 时由 Java 服务层强制）。Schema 按**实现**放宽，否则合法请求会被 400。
2. **数值一律字符串**（`§4.1/§4.2`）：数量 3 位小数、比率 4 位，禁 IEEE-754 裸传。
   本模块只做**格式**校验（正则），不转 `float`——转换会丢精度并破坏「输出中的数字可在输入中找到」
   这一 LLM 溯源前提。
3. **`extra="forbid"`**：字段漂移立即暴露（否则 Java 侧改了字段名，Python 静默忽略，
   表现为「计算结果与输入不符」这种最难查的缺陷）。响应模型反过来用 `ignore`（容忍新增字段）。

错误码（`ERROR_CODES.md §2.11`）：
- 结构/格式/范围 → `AI-1001`（400）；
- `items` 超过 `MAX_ANALYSIS_SKUS` → `AI-7002`（400）；
- item 缺 `sku_id` → `AI-2001`（422）。
"""
from __future__ import annotations

from datetime import date
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from shoppilot_ai.app.core.errors import (
    AI_1001,
    AI_2001,
    AI_7002,
    ServiceAIBaseError,
)

#: 单次分析的 SKU 上限（`DESIGN_BASIS.md §10.6`：`MAX_ANALYSIS_SKUS`）。
MAX_ANALYSIS_SKUS = 2000

#: 数量/金额：非负十进制，最多 3 位小数（`ANALYSIS_CONTRACT §1.2` decimal3）。
Decimal3Str = Annotated[str, StringConstraints(pattern=r"^(0|[1-9][0-9]*)(\.[0-9]{1,3})?$")]

#: 比率：非负十进制，最多 4 位小数（`ANALYSIS_CONTRACT §1.1` decimal4）。
Decimal4Str = Annotated[str, StringConstraints(pattern=r"^(0|[1-9][0-9]*)(\.[0-9]{1,4})?$")]

#: 带符号的数量：**仅用于 `inventory_position`（`IP`）**。
#:
#: `IP = on_hand + eff_in_transit − committed` 允许为负（超卖：`committed` 大于在手+在途），
#: `analysis_items.inventory_position_qty` 是有符号 `DECIMAL(18,3)`，`ANALYSIS_CONTRACT §2.1`
#: 对该字段只要求「与三项一致」、**未要求 ≥0**（`R118`）。
#: ★ 不得用于 `on_hand_qty` / `committed_qty` / `effective_in_transit_qty` / `q_base` /
#: `q_suggest` 等非负字段——Java 侧对这些字段有非负校验，放宽会与 Java 不一致。
SignedDecimal3Str = Annotated[
    str, StringConstraints(pattern=r"^-?(0|[1-9][0-9]*)(\.[0-9]{1,3})?$")
]

#: 算法版本：字母数字开头，允许 `._-`，总长 1..64（与 Java 侧 `@Pattern` 同一条正则）。
ALGORITHM_VERSION_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"

ScopeType = Literal["ALL_ACTIVE", "SELECTED_SKUS", "CATEGORY"]
LeadTimeSource = Literal["POLICY", "SKU", "SUPPLIER", "DEFAULT"]


def _schema_error(reason: str, **extra: object) -> ServiceAIBaseError:
    """构造 `400 AI-1001`（结构/语义校验失败），`detail` 说明具体原因。"""
    detail: dict[str, object] = {"reason": reason}
    detail.update(extra)
    return ServiceAIBaseError(code=AI_1001, detail=detail)


class SalesPoint(BaseModel):
    """窗口内单日净销量（`ANALYSIS_CONTRACT §1.2` 的 `sales_series[]`）。

    `qty` 为 3 位小数字符串；`date` 必须落在请求的 `[date_from, date_to]` 内
    （由 `AnalyzeRequest` 在 run 级校验——item 自己看不到窗口）。
    """

    model_config = ConfigDict(extra="forbid")

    date: date
    qty: Decimal3Str


class Item(BaseModel):
    """单个 SKU 的计算输入（`ANALYSIS_CONTRACT §1.2`）。

    ★ `sku_id` 声明为可空 + 在 `AnalyzeRequest` 中校验「非空且 >0」：
    这样「缺 `sku_id`」映射为业务码 `AI-2001`（422），而不是 Pydantic 的结构错误
    `AI-1001`（400）——契约对两者有明确区分。
    """

    model_config = ConfigDict(extra="forbid")

    sku_id: int | None = None
    on_hand_qty: Decimal3Str
    committed_qty: Decimal3Str
    effective_in_transit_qty: Decimal3Str
    lead_time_days: int | None = Field(default=None, ge=0)
    lead_time_source: LeadTimeSource | None = None
    safety_stock_override: Decimal3Str | None = None
    moq: Decimal3Str | None = None
    order_multiple: Decimal3Str | None = None
    qty_step: Decimal3Str | None = None
    listed_date: date | None = None
    sales_series: list[SalesPoint]


class Scope(BaseModel):
    """分析范围（`ANALYSIS_CONTRACT §1.1` 的 `scope` 对象）。"""

    model_config = ConfigDict(extra="forbid")

    scope_type: ScopeType
    sku_ids: list[int] | None = None
    category: str | None = None

    @model_validator(mode="after")
    def _validate_by_type(self) -> "Scope":
        if self.scope_type == "SELECTED_SKUS":
            if not self.sku_ids:
                raise _schema_error("scope.sku_ids is required when scope_type=SELECTED_SKUS")
            if len(self.sku_ids) > MAX_ANALYSIS_SKUS:
                raise ServiceAIBaseError(
                    code=AI_7002,
                    detail={"scope_sku_count": len(self.sku_ids), "max": MAX_ANALYSIS_SKUS},
                )
            for sku_id in self.sku_ids:
                if sku_id <= 0:
                    raise _schema_error(
                        "scope.sku_ids entries must be positive integers",
                        rejected_value=sku_id,
                    )
        if self.scope_type == "CATEGORY" and not (self.category and self.category.strip()):
            raise _schema_error("scope.category is required when scope_type=CATEGORY")
        return self


class Params(BaseModel):
    """运行参数（`ANALYSIS_CONTRACT §1.1`，默认值取自 `DESIGN_BASIS §10.6`）。

    `min_days` 的上界依赖 `window_days`，故用 `model_validator` 而不是 `Field(le=...)`。
    """

    model_config = ConfigDict(extra="forbid")

    window_days: int = Field(default=28, ge=1, le=365)
    min_days: int = Field(default=7, ge=1)
    coverage_days: int = Field(default=7, ge=0, le=365)
    lead_time_default: int = Field(default=7, ge=0, le=365)
    ss_ratio: Decimal4Str = "0.5000"
    cv_high: Decimal4Str = "1.0000"
    budget_limit: Decimal3Str | None = None
    enable_llm: bool = True

    @model_validator(mode="after")
    def _min_days_within_window(self) -> "Params":
        if self.min_days > self.window_days:
            raise _schema_error(
                "params.min_days must be <= params.window_days",
                window_days=self.window_days,
                min_days=self.min_days,
            )
        return self


class AnalyzeRequest(BaseModel):
    """`POST /internal/v1/analysis/runs` 请求体（`AI_SERVICE_CONTRACT §3.2`）。"""

    model_config = ConfigDict(extra="forbid")

    analysis_run_id: int = Field(gt=0)
    team_id: int = Field(gt=0)
    warehouse_id: int | None = Field(default=None, gt=0)
    scope: Scope
    date_from: date
    date_to: date
    params: Params = Field(default_factory=Params)
    algorithm_version: str = Field(
        min_length=1, max_length=64, pattern=ALGORITHM_VERSION_PATTERN
    )
    prompt_version: str | None = Field(default=None, max_length=64)
    model_name: str | None = Field(default=None, max_length=128)
    items: list[Item]
    #: 契约 §3.2 的预留回调位（首期恒 `null`，不实现）。保留字段是为了让**契约完整示例**
    #: 能被原样解析；Java 侧 `AiRunCreateRequest` 按 `NON_NULL` 省略该字段，两者兼容。
    callback: dict[str, Any] | None = None

    @model_validator(mode="after")
    def _validate_run(self) -> "AnalyzeRequest":
        if self.date_from > self.date_to:
            raise _schema_error(
                "date_from must be <= date_to",
                date_from=self.date_from.isoformat(),
                date_to=self.date_to.isoformat(),
            )

        # ★ R113（B16.1）：**空 items 合法**。Java `B15` 的 `AnalysisAppService.toAiRequest`
        #   在逐 SKU 装配（B17+）落地前恒传 `List.of()`，契约 §3.2 也只规定「≤2000」。
        #   这里若拒绝，`B19` 端点上线后 `createRun` 会 100% `AI-1001` → Java `ANL-7004`。
        #   「空 items 的业务语义」（`analyzable=false` / run 状态）由端点与 Java 决定，
        #   Schema 只负责结构与规模；其余字段的校验（如 prompt_version）照常执行。
        if len(self.items) > MAX_ANALYSIS_SKUS:
            raise ServiceAIBaseError(
                code=AI_7002,
                detail={"item_count": len(self.items), "max": MAX_ANALYSIS_SKUS},
            )

        for index, item in enumerate(self.items):
            if item.sku_id is None:
                raise ServiceAIBaseError(
                    code=AI_2001,
                    detail={"index": index, "reason": "items[].sku_id is required"},
                )
            if item.sku_id <= 0:
                raise _schema_error(
                    "items[].sku_id must be positive",
                    index=index,
                    rejected_value=item.sku_id,
                )
            for point in item.sales_series:
                if not (self.date_from <= point.date <= self.date_to):
                    raise _schema_error(
                        "sales_series[].date must be within [date_from, date_to]",
                        sku_id=item.sku_id,
                        rejected_value=point.date.isoformat(),
                        date_from=self.date_from.isoformat(),
                        date_to=self.date_to.isoformat(),
                    )

        if self.params.enable_llm and not (self.prompt_version and self.prompt_version.strip()):
            raise _schema_error(
                "prompt_version is required when params.enable_llm=true",
                field="prompt_version",
            )
        return self


class ValidateRequest(BaseModel):
    """`POST /internal/v1/analysis:validate` 请求体（`AI_SERVICE_CONTRACT §3.1`）。

    ★ 与 `AnalyzeRequest` 的唯一结构差异：**没有 `analysis_run_id`**（预检不创建 run）。
    其余字段（`team_id`/`warehouse_id`/`scope`/`date_from`/`date_to`/`params`/`items`）同构，
    以便端点直接复用同一套校验与 `assess()`。

    ⚠️ `items` 复用 `Item`（`§1.2`：含三项库存快照），而 `§3.1` 的示例只给了
    `sku_id` + `sales_series`。两者不一致已随 B19.2 交付汇报申报，待项目负责人裁定
    （Java 侧 `AiValidateRequest.items` 目前是 `List<Object>` 恒空）。
    """

    model_config = ConfigDict(extra="forbid")

    team_id: int = Field(gt=0)
    warehouse_id: int | None = Field(default=None, gt=0)
    scope: Scope
    date_from: date
    date_to: date
    params: Params = Field(default_factory=Params)
    items: list[Item]

    @model_validator(mode="after")
    def _validate_run(self) -> "ValidateRequest":
        if self.date_from > self.date_to:
            raise _schema_error(
                "date_from must be <= date_to",
                date_from=self.date_from.isoformat(),
                date_to=self.date_to.isoformat(),
            )
        if len(self.items) > MAX_ANALYSIS_SKUS:
            raise ServiceAIBaseError(
                code=AI_7002,
                detail={"item_count": len(self.items), "max": MAX_ANALYSIS_SKUS},
            )
        for index, item in enumerate(self.items):
            if item.sku_id is None:
                raise ServiceAIBaseError(
                    code=AI_2001,
                    detail={"index": index, "reason": "items[].sku_id is required"},
                )
            if item.sku_id <= 0:
                raise _schema_error(
                    "items[].sku_id must be positive",
                    index=index,
                    rejected_value=item.sku_id,
                )
            for point in item.sales_series:
                if not (self.date_from <= point.date <= self.date_to):
                    raise _schema_error(
                        "sales_series[].date must be within [date_from, date_to]",
                        sku_id=item.sku_id,
                        rejected_value=point.date.isoformat(),
                        date_from=self.date_from.isoformat(),
                        date_to=self.date_to.isoformat(),
                    )
        return self


class CancelRequest(BaseModel):
    """`POST /internal/v1/analysis/runs/{id}:cancel` 请求体（`§3.6`）。

    `reason` 可缺省；取消是「尽力而为」（`§3.6`），`reason` 只作留痕，Python 不据此改行为。
    """

    model_config = ConfigDict(extra="forbid")

    reason: str | None = Field(default=None, max_length=200)


__all__ = [
    "ALGORITHM_VERSION_PATTERN",
    "MAX_ANALYSIS_SKUS",
    "AnalyzeRequest",
    "CancelRequest",
    "Decimal3Str",
    "Decimal4Str",
    "Item",
    "LeadTimeSource",
    "Params",
    "SalesPoint",
    "Scope",
    "ScopeType",
    "SignedDecimal3Str",
    "ValidateRequest",
]
