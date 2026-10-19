"""`AI_SERVICE_CONTRACT.md §3.1/§3.4/§3.5` 的 Pydantic v2 响应模型（M4-T06）。

三套模型（各自对应一个端点，端点本身归 B19/B20，本批只落 Schema）：

1. `ValidateData` —— `§3.1` 数据质量预检结果（同步 200）；
2. `RunStatusData` —— `§3.4` 状态查询（轮询）；
3. `RunResultData` —— `§3.5` 结果（全量返回，不分页）。

★ 与请求模型相反，响应模型用 `extra="ignore"`：容忍对端/后续批次新增字段
（Java 侧按需取字段，Python 不应因为多返回一个字段就整体 500）。

★ 枚举只允许 SSOT 已登记的值（`Literal`），**不新造**：
`data_quality`（4 值，`ANALYSIS_CONTRACT §4.1`）、`risk_level`（5 值，`DESIGN_BASIS §11.1`）、
`llm_status`（7 值，`DESIGN_BASIS §9.8`）、`lead_time_source`（4 值，`DESIGN_BASIS §10.2`）、
`execution_status`（5 值，`§3.4`）。

★ `risk_types` 用 `RiskType` Literal（6 值，`DESIGN_BASIS §11:436`）：`STOCKOUT` / `LOW_STOCK` /
`OVERSTOCK` / `SLOW_MOVING` / `SALES_ANOMALY` / `DATA_INSUFFICIENT`。
★ R115 订正：`LOWSTOCK` 是 **`RuleCode`**（规则编码，如 `R-LOWSTOCK`），**不是** `RiskType`；
B16 曾据此把字段放宽为 `list[str]`，理由不成立，B16.1 已收紧。
"""
from __future__ import annotations

from datetime import date
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict

from shoppilot_ai.app.schemas.analysis_request import (
    Decimal3Str,
    Decimal4Str,
    SignedDecimal3Str,
)
from shoppilot_ai.app.schemas.common import Envelope

DataQuality = Literal["GOOD", "FAIR", "POOR", "INSUFFICIENT"]
RiskLevel = Literal["HIGH", "MEDIUM", "LOW", "NONE", "UNKNOWN"]
#: 风险类型（`DESIGN_BASIS.md §11:436` 的 `RiskType`，6 值）。`RiskType` 与 `RuleCode`
#: 是两套值空间：规则编码是 `R-STOCKOUT` / `R-LOWSTOCK`，风险类型是 `STOCKOUT` / `LOW_STOCK`。
RiskType = Literal[
    "STOCKOUT",
    "LOW_STOCK",
    "OVERSTOCK",
    "SLOW_MOVING",
    "SALES_ANOMALY",
    "DATA_INSUFFICIENT",
]
LlmStatus = Literal[
    "NOT_REQUESTED",
    "PENDING",
    "SUCCEEDED",
    "FAILED",
    "TIMEOUT",
    "VALIDATION_FAILED",
    "SKIPPED",
]
LeadTimeSourceValue = Literal["POLICY", "SKU", "SUPPLIER", "DEFAULT"]
ExecutionStatus = Literal["ACCEPTED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
CurrentStep = Literal[
    "VALIDATING", "STATS", "RISK_RULES", "REPLENISHMENT", "LLM_EXPLAIN", "DONE"
]


# --------------------------------------------------------------------------- #
# §3.1 数据质量预检（validate）
# --------------------------------------------------------------------------- #
class PerSkuQuality(BaseModel):
    """单 SKU 的预检结果（`§3.1` 响应 `per_sku[]`）。"""

    model_config = ConfigDict(extra="ignore")

    sku_id: int
    valid_days: int
    missing_days: int
    outlier_count: int
    quality: DataQuality


class ValidateData(BaseModel):
    """`§3.1` 响应 `data`（`validate` 端点用，端点归 B19）。"""

    model_config = ConfigDict(extra="ignore")

    analyzable: bool
    window_days: int
    total_skus: int
    analyzable_skus: int
    insufficient_skus: int
    valid_days_avg: float
    missing_days_total: int
    outlier_count_total: int
    per_sku: list[PerSkuQuality]
    warnings: list[str]


# --------------------------------------------------------------------------- #
# §3.4 状态查询
# --------------------------------------------------------------------------- #
class RunError(BaseModel):
    """`§3.4` 的 `error` 结构（失败时非空）。"""

    model_config = ConfigDict(extra="ignore")

    code: str
    message: str
    detail: dict[str, Any] = {}


class RunStatusData(BaseModel):
    """`§3.4` 响应 `data`。

    时间字段用 `str`（ISO-8601 UTC）而非 `datetime`：本服务只**透传**执行时间，
    不做时区换算；用 `str` 可保证 `"...Z"` 字面量原样回给 Java（与 `§14.1` 一致）。
    """

    model_config = ConfigDict(extra="ignore")

    analysis_run_id: int
    execution_status: ExecutionStatus
    progress: int
    current_step: CurrentStep | None = None
    total_skus: int
    processed_skus: int
    llm_status: LlmStatus
    started_at: str | None = None
    finished_at: str | None = None
    error: RunError | None = None


# --------------------------------------------------------------------------- #
# §3.5 结果
# --------------------------------------------------------------------------- #
class RiskFinding(BaseModel):
    """单条规则命中明细（`ANALYSIS_CONTRACT §5`）。"""

    model_config = ConfigDict(extra="ignore")

    rule_code: str
    inputs: dict[str, Any] = {}
    threshold: str
    result: str


class ResultItem(BaseModel):
    """`§3.5` 响应 `items[]`（字段逐项对齐 `ANALYSIS_CONTRACT §2.1`）。"""

    model_config = ConfigDict(extra="ignore")

    sku_id: int
    warehouse_id: int
    avg_daily_sales: Decimal3Str | None = None
    sales_qty_total: Decimal3Str
    valid_days: int
    data_quality: DataQuality
    risk_level: RiskLevel
    risk_types: list[RiskType] = []
    risk_findings: list[RiskFinding] = []
    on_hand_qty: Decimal3Str
    effective_in_transit_qty: Decimal3Str
    committed_qty: Decimal3Str
    #: ★ `R118`：`IP` 允许为负（超卖），故用带符号类型；其余数量字段保持非负。
    inventory_position: SignedDecimal3Str
    safety_stock: Decimal3Str | None = None
    lead_time_days: int | None = None
    lead_time_source: LeadTimeSourceValue
    coverage_days: int | None = None
    cv: Decimal4Str | None = None
    volatility_adjusted: bool = False
    q_base: Decimal3Str | None = None
    moq_applied: Decimal3Str | None = None
    order_multiple_applied: Decimal3Str | None = None
    q_suggest: Decimal3Str | None = None
    truncated_by_budget: bool = False
    llm_explanation: str | None = None
    llm_evidence: list[str] = []
    llm_status: LlmStatus = "NOT_REQUESTED"
    warnings: list[str] = []


class RunSummary(BaseModel):
    """`§3.5` 的 `summary`（`ANALYSIS_CONTRACT §2.2`）。"""

    model_config = ConfigDict(extra="ignore")

    total_skus: int
    risk_sku_count: int
    suggestion_count: int
    truncated_count: int
    by_risk_level: dict[str, int] = {}


class RunResultData(BaseModel):
    """`§3.5` 响应 `data`（全量返回，不分页）。"""

    model_config = ConfigDict(extra="ignore")

    analysis_run_id: int
    execution_status: ExecutionStatus
    algorithm_version: str
    prompt_version: str | None = None
    model_name: str | None = None
    llm_status: LlmStatus
    data_start_date: date | None = None
    data_end_date: date | None = None
    summary: RunSummary
    items: list[ResultItem]


# --------------------------------------------------------------------------- #
# 具体化的响应信封（与 Java 侧 `AiValidateEnvelope` / `AiRunCreateEnvelope` 同构）
# --------------------------------------------------------------------------- #
ValidateResponse = Envelope[ValidateData]
RunStatusResponse = Envelope[RunStatusData]
RunResultResponse = Envelope[RunResultData]


__all__ = [
    "CurrentStep",
    "DataQuality",
    "ExecutionStatus",
    "LeadTimeSourceValue",
    "LlmStatus",
    "PerSkuQuality",
    "ResultItem",
    "RiskFinding",
    "RiskLevel",
    "RiskType",
    "RunError",
    "RunResultData",
    "RunResultResponse",
    "RunStatusData",
    "RunStatusResponse",
    "RunSummary",
    "ValidateData",
    "ValidateResponse",
]
