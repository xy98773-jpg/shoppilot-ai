"""`ANALYSIS_CONTRACT.md §7.2` 的 18 条 golden case 夹具（GC-01~GC-18，M4-T15）。

## 口径来源

- 输入/期望输出：`ANALYSIS_CONTRACT.md §7.2` 的 GC-01~GC-18 表格；
- 公式与阈值：`DESIGN_BASIS.md §10.1`（`D`/`CV`/3σ 截尾）、`§10.2`（`Q_base`）、
  `§10.3`（`SS`）、`§10.5`（`MOQ`/倍数/预算截断）、`§10.6`（默认值）、`§11`（六规则）；
- 数据质量分级：`ANALYSIS_CONTRACT.md §4.1`；
- 期望值的**算术推导**（以默认 `N=28`、`MIN_DAYS=7`、`R=7`、`SS_RATIO=0.5`、`CV_HIGH=1.0` 为准）：

| 用例 | 关键推导 |
| --- | --- |
| GC-01 | `D=(21×6+7×7)/28=6.250`；`SS_FACTOR=ceil(7×0.5)=4` ⇒ `SS=25.000`；`Q_base=max(0, 6.25×14+25−200)=0` |
| GC-02 | 同上 `D`/`SS`；`Q_base=87.5+25−8=104.500`；`max(104.5, MOQ 50)` 向上取整到 10 的倍数 ⇒ `110.000` |
| GC-03 | `D=5`、`SS=20`、`Q_base=70+20−10=80.000`；`on_hand/D=2.000` 命中 `<= 2` ⇒ `STOCKOUT=HIGH` |
| GC-04 | 取 `L=0`（`R=7`）：`on_hand/D=5 > 2` 且 `> L(0)` ⇒ `STOCKOUT` 未命中；`IP=10 < 2×7=14` ⇒ `LOW_STOCK=MEDIUM` |
| GC-05 | `on_hand/D=95 > 90` ⇒ `OVERSTOCK=MEDIUM`；`Q_base=14+4−95<0` ⇒ `0.000`/`q_suggest=null` |
| GC-06 | `on_hand/D=181 > 180` ⇒ `OVERSTOCK=HIGH` |
| GC-07 | 28 天全 0 ⇒ `D=0.000`、`CV=null`；库存类三条因 `D>0` 前置不满足而跳过；滞销 `MEDIUM` |
| GC-08 | 前 21 天均值 2.0、`std≈0.976`；近 7 天日均 10 ⇒ `|Δ|=8 > 2×0.976` 且变化率 400% ⇒ `SALES_ANOMALY=LOW` |
| GC-09 | `n=6 < MIN_DAYS(7)` ⇒ `D=null`、`UNKNOWN`、`DATA_INSUFFICIENT`、不出建议 |
| GC-10 | `sales_series=[]` ⇒ `n=0`，同 GC-09 |
| GC-11 | `D=5`、`SS=20`、`Q_base=70+20−60=30.000`；`MOQ=50` ⇒ `q_suggest=50.000` |
| GC-12 | `Q_base=70+20−17=73.000`；倍数 10 向上取整 ⇒ `80.000` |
| GC-13 | `L=R=30` ⇒ `SS=5×ceil(15)=75`；`Q_base=5×60+75−175=200.000`；预算 150 ⇒ `150.000` + 截断 |
| GC-14 | `D=3.250`、`CV=1.0742 > CV_HIGH(1.0)` ⇒ `SS_FACTOR=4×1.5=6` ⇒ `SS=19.500`、`volatility_adjusted=true` |
| GC-15 | `safety_stock_override=100` 绝对量覆盖 ⇒ `SS=100.000`，`Q_base=70+100−50=120.000` |
| GC-16 | `IP=10+8−0=18.000`；`Q_base=70+20−18=72.000` |
| GC-17 | 28 天均值 8.393、`std≈17.63`，`100 > mean+3σ` 被截尾 ⇒ `D=5.000`；`sales_qty_total` 仍为 235.000（事实值） |
| GC-18 | `L=R=0`、`SS=0`、`on_hand=50` ⇒ `Q_base=0`、`q_suggest=null`；`on_hand/D=50 <= 90` 不触发高库存 |

## 两处刻意口径（与 `DESIGN_REVIEW.md §5.30` 一致）

1. **GC-04 取 `L=0`**：原稿 `L=7` 会让 `on_hand/D=5 <= L` 命中 `STOCKOUT`，与「未命中」矛盾；
2. **GC-18 取 `on_hand=50`**：原稿 `on_hand=1000` 必触发 `OVERSTOCK`，与「全部规则未命中」矛盾。

## GC-09 / GC-10 的伴随条目

`orchestration/pipeline.py` 在**全部** SKU 不可分析时按 `§5.32 #4` 整体失败（`AI-2002`），
不会产出任何 `ResultItem`。为了让「数据不足」也走**真实管线**并断言 `ResultItem` 字段，
GC-09/GC-10 在请求里附一条可分析的伴随 SKU（`sku_id=9999`），只断言目标 SKU 的条目；
这与 `§5.32 #4`「部分不足照常出结果」的语义一致。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Any, Mapping, Sequence

#: 分析窗口：28 个自然日（`window_days=28`，`DESIGN_BASIS §10.6`）。
START = date(2026, 1, 1)
DATE_TO = START + timedelta(days=27)

#: 请求固定参数（除用例显式覆盖的部分）。
BASE_PARAMS: dict[str, Any] = {
    "window_days": 28,
    "min_days": 7,
    "coverage_days": 7,
    "lead_time_default": 7,
    "enable_llm": False,
}

#: GC-09/GC-10 的伴随 SKU：数据充足、库存充足 ⇒ 自身 `NONE`，只为让 run 不整体失败。
COMPANION_SKU_ID = 9999


@dataclass(frozen=True)
class GoldenCase:
    """一条 golden case：输入 + 逐字段期望（`ANALYSIS_CONTRACT §7.2`）。

    :param case_id: `GC-01`~`GC-18`
    :param title: 用例名（照抄契约表格）
    :param item: 目标 SKU 的 `items[]` 载荷（未含 `sales_series` 的日期展开由 `make_item` 完成）
    :param expected: `ResultItem` 字段名 → 期望值（逐字段断言）
    :param expect_findings: `rule_code` → 期望 `result`（只断言列出的规则）
    :param params: 覆盖 `BASE_PARAMS` 的运行参数
    :param companions: 附带的其它 `items[]`（GC-09/GC-10 用）
    :param warnings_contain: 期望 `warnings` 中出现的子串（逐条断言）
    """

    case_id: str
    title: str
    item: dict[str, Any]
    expected: dict[str, Any]
    expect_findings: dict[str, str] = field(default_factory=dict)
    params: dict[str, Any] = field(default_factory=dict)
    companions: tuple[dict[str, Any], ...] = ()
    warnings_contain: tuple[str, ...] = ()


def series(quantities: Sequence[str], *, start: date = START) -> list[dict[str, str]]:
    """把 `qty` 列表展开成 `sales_series`（连续自然日，从 `start` 起）。"""
    return [
        {"date": (start + timedelta(days=index)).isoformat(), "qty": qty}
        for index, qty in enumerate(quantities)
    ]


def make_item(
    sku_id: int,
    quantities: Sequence[str],
    *,
    on_hand: str,
    committed: str = "0.000",
    transit: str = "0.000",
    lead_time_days: int = 7,
    lead_time_source: str | None = "SKU",
    safety_stock_override: str | None = None,
    moq: str | None = None,
    order_multiple: str | None = None,
    qty_step: str | None = None,
    listed_date: date | None = None,
    start: date = START,
) -> dict[str, Any]:
    """构造 `ANALYSIS_CONTRACT §1.2` 的 item 载荷（只填本批用例需要的字段）。"""
    return {
        "sku_id": sku_id,
        "on_hand_qty": on_hand,
        "committed_qty": committed,
        "effective_in_transit_qty": transit,
        "lead_time_days": lead_time_days,
        "lead_time_source": lead_time_source,
        "safety_stock_override": safety_stock_override,
        "moq": moq,
        "order_multiple": order_multiple,
        "qty_step": qty_step,
        "listed_date": listed_date.isoformat() if listed_date is not None else None,
        "sales_series": series(quantities, start=start),
    }


def _companion() -> dict[str, Any]:
    """GC-09/GC-10 的伴随 SKU（`D=5`、`on_hand=200` ⇒ `NONE`、无建议）。"""
    return make_item(COMPANION_SKU_ID, ["5.000"] * 28, on_hand="200.000")


#: 未命中态的完整 finding 集合（前置条件满足 ⇒ 五条规则都输出 `NOT_TRIGGERED`）。
_ALL_NOT_TRIGGERED: dict[str, str] = {
    "R-STOCKOUT": "NOT_TRIGGERED",
    "R-LOWSTOCK": "NOT_TRIGGERED",
    "R-OVERSTOCK": "NOT_TRIGGERED",
    "R-SLOWMOVING": "NOT_TRIGGERED",
    "R-SALESANOMALY": "NOT_TRIGGERED",
}


CASES: tuple[GoldenCase, ...] = (
    GoldenCase(
        case_id="GC-01",
        title="正常热销、库存充足",
        item=make_item(5001, ["6.000"] * 21 + ["7.000"] * 7, on_hand="200.000"),
        expected={
            "avg_daily_sales": "6.250",
            "sales_qty_total": "175.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "NONE",
            "risk_types": [],
            "on_hand_qty": "200.000",
            "inventory_position": "200.000",
            "safety_stock": "25.000",
            "lead_time_days": 7,
            "lead_time_source": "SKU",
            "coverage_days": 7,
            "cv": "0.0693",
            "volatility_adjusted": False,
            "q_base": "0.000",
            "moq_applied": None,
            "order_multiple_applied": None,
            "q_suggest": None,
            "truncated_by_budget": False,
        },
        expect_findings=dict(_ALL_NOT_TRIGGERED),
    ),
    GoldenCase(
        case_id="GC-02",
        title="缺货高风险",
        item=make_item(
            5002,
            ["6.000"] * 21 + ["7.000"] * 7,
            on_hand="8.000",
            moq="50.000",
            order_multiple="10.000",
        ),
        expected={
            "avg_daily_sales": "6.250",
            "sales_qty_total": "175.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "HIGH",
            "risk_types": ["STOCKOUT", "LOW_STOCK"],
            "inventory_position": "8.000",
            "safety_stock": "25.000",
            "cv": "0.0693",
            "q_base": "104.500",
            "moq_applied": "50.000",
            "order_multiple_applied": "10.000",
            "q_suggest": "110.000",
            "truncated_by_budget": False,
        },
        expect_findings={
            "R-STOCKOUT": "HIGH",
            "R-LOWSTOCK": "MEDIUM",
            "R-OVERSTOCK": "NOT_TRIGGERED",
        },
    ),
    GoldenCase(
        case_id="GC-03",
        title="缺货边界（on_hand/D == 2.000）",
        item=make_item(5003, ["5.000"] * 28, on_hand="10.000"),
        expected={
            "avg_daily_sales": "5.000",
            "sales_qty_total": "140.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "HIGH",
            "risk_types": ["STOCKOUT", "LOW_STOCK"],
            "inventory_position": "10.000",
            "safety_stock": "20.000",
            "cv": "0.0000",
            "q_base": "80.000",
            "moq_applied": "1.000",
            "order_multiple_applied": None,
            "q_suggest": "80.000",
            "truncated_by_budget": False,
        },
        expect_findings={"R-STOCKOUT": "HIGH", "R-LOWSTOCK": "MEDIUM"},
    ),
    GoldenCase(
        case_id="GC-04",
        title="低库存（未触发缺货）",
        item=make_item(5004, ["2.000"] * 28, on_hand="10.000", lead_time_days=0),
        expected={
            "avg_daily_sales": "2.000",
            "sales_qty_total": "56.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "MEDIUM",
            "risk_types": ["LOW_STOCK"],
            "inventory_position": "10.000",
            "safety_stock": "0.000",
            "lead_time_days": 0,
            "coverage_days": 7,
            "cv": "0.0000",
            "q_base": "4.000",
            "moq_applied": "1.000",
            "order_multiple_applied": None,
            "q_suggest": "4.000",
            "truncated_by_budget": False,
        },
        expect_findings={"R-STOCKOUT": "NOT_TRIGGERED", "R-LOWSTOCK": "MEDIUM"},
    ),
    GoldenCase(
        case_id="GC-05",
        title="库存过高",
        item=make_item(5005, ["1.000"] * 28, on_hand="95.000"),
        expected={
            "avg_daily_sales": "1.000",
            "sales_qty_total": "28.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "MEDIUM",
            "risk_types": ["OVERSTOCK"],
            "inventory_position": "95.000",
            "safety_stock": "4.000",
            "cv": "0.0000",
            "q_base": "0.000",
            "moq_applied": None,
            "order_multiple_applied": None,
            "q_suggest": None,
            "truncated_by_budget": False,
        },
        expect_findings={"R-OVERSTOCK": "MEDIUM", "R-STOCKOUT": "NOT_TRIGGERED"},
    ),
    GoldenCase(
        case_id="GC-06",
        title="库存过高 HIGH（> 2*OVERSTOCK_DAYS）",
        item=make_item(5006, ["1.000"] * 28, on_hand="181.000"),
        expected={
            "avg_daily_sales": "1.000",
            "sales_qty_total": "28.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "HIGH",
            "risk_types": ["OVERSTOCK"],
            "inventory_position": "181.000",
            "safety_stock": "4.000",
            "cv": "0.0000",
            "q_base": "0.000",
            "moq_applied": None,
            "order_multiple_applied": None,
            "q_suggest": None,
            "truncated_by_budget": False,
        },
        expect_findings={"R-OVERSTOCK": "HIGH"},
    ),
    GoldenCase(
        case_id="GC-07",
        title="滞销",
        item=make_item(5007, ["0.000"] * 28, on_hand="50.000"),
        expected={
            "avg_daily_sales": "0.000",
            "sales_qty_total": "0.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "MEDIUM",
            "risk_types": ["SLOW_MOVING"],
            "inventory_position": "50.000",
            "safety_stock": "0.000",
            "cv": None,
            "q_base": "0.000",
            "moq_applied": None,
            "order_multiple_applied": None,
            "q_suggest": None,
            "truncated_by_budget": False,
        },
        # `D=0` ⇒ 库存类三条因前置不满足而跳过（`DESIGN_REVIEW §5.30 #4`），只剩滞销与异常两条
        expect_findings={"R-SLOWMOVING": "MEDIUM", "R-SALESANOMALY": "NOT_TRIGGERED"},
    ),
    GoldenCase(
        case_id="GC-08",
        title="销量异常",
        item=make_item(
            5008,
            ["1.000"] * 10 + ["3.000"] * 10 + ["2.000"] + ["10.000"] * 7,
            on_hand="100.000",
        ),
        expected={
            "avg_daily_sales": "4.000",
            "sales_qty_total": "112.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "LOW",
            "risk_types": ["SALES_ANOMALY"],
            "inventory_position": "100.000",
            "safety_stock": "16.000",
            "cv": "0.8914",
            "volatility_adjusted": False,
            "q_base": "0.000",
            "moq_applied": None,
            "order_multiple_applied": None,
            "q_suggest": None,
            "truncated_by_budget": False,
        },
        expect_findings={"R-SALESANOMALY": "LOW", "R-STOCKOUT": "NOT_TRIGGERED"},
    ),
    GoldenCase(
        case_id="GC-09",
        title="数据不足（n=6 < 7）",
        item=make_item(5009, ["5.000"] * 6, on_hand="10.000"),
        expected={
            "avg_daily_sales": None,
            "sales_qty_total": "30.000",
            "valid_days": 6,
            "data_quality": "INSUFFICIENT",
            "risk_level": "UNKNOWN",
            "risk_types": ["DATA_INSUFFICIENT"],
            "inventory_position": "10.000",
            "safety_stock": None,
            "cv": None,
            "volatility_adjusted": False,
            "q_base": None,
            "moq_applied": None,
            "order_multiple_applied": None,
            "q_suggest": None,
            "truncated_by_budget": False,
        },
        expect_findings={"R-DATAINSUFFICIENT": "UNKNOWN"},
        companions=(_companion(),),
        warnings_contain=("数据不足",),
    ),
    GoldenCase(
        case_id="GC-10",
        title="无销售数据（n=0）",
        item=make_item(5010, [], on_hand="10.000"),
        expected={
            "avg_daily_sales": None,
            "sales_qty_total": "0.000",
            "valid_days": 0,
            "data_quality": "INSUFFICIENT",
            "risk_level": "UNKNOWN",
            "risk_types": ["DATA_INSUFFICIENT"],
            "inventory_position": "10.000",
            "safety_stock": None,
            "cv": None,
            "q_base": None,
            "moq_applied": None,
            "order_multiple_applied": None,
            "q_suggest": None,
            "truncated_by_budget": False,
        },
        expect_findings={"R-DATAINSUFFICIENT": "UNKNOWN"},
        companions=(_companion(),),
        warnings_contain=("数据不足",),
    ),
    GoldenCase(
        case_id="GC-11",
        title="MOQ 生效",
        item=make_item(5011, ["5.000"] * 28, on_hand="60.000", moq="50.000"),
        expected={
            "avg_daily_sales": "5.000",
            "sales_qty_total": "140.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "MEDIUM",
            "risk_types": ["LOW_STOCK"],
            "inventory_position": "60.000",
            "safety_stock": "20.000",
            "cv": "0.0000",
            "q_base": "30.000",
            "moq_applied": "50.000",
            "order_multiple_applied": None,
            "q_suggest": "50.000",
            "truncated_by_budget": False,
        },
        expect_findings={"R-LOWSTOCK": "MEDIUM"},
    ),
    GoldenCase(
        case_id="GC-12",
        title="采购倍数生效",
        item=make_item(5012, ["5.000"] * 28, on_hand="17.000", order_multiple="10.000"),
        expected={
            "avg_daily_sales": "5.000",
            "sales_qty_total": "140.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "MEDIUM",
            "risk_types": ["STOCKOUT", "LOW_STOCK"],
            "inventory_position": "17.000",
            "safety_stock": "20.000",
            "cv": "0.0000",
            "q_base": "73.000",
            "moq_applied": "1.000",
            "order_multiple_applied": "10.000",
            "q_suggest": "80.000",
            "truncated_by_budget": False,
        },
        expect_findings={"R-STOCKOUT": "MEDIUM", "R-LOWSTOCK": "MEDIUM"},
    ),
    GoldenCase(
        case_id="GC-13",
        title="预算截断",
        item=make_item(5013, ["5.000"] * 28, on_hand="175.000", lead_time_days=30),
        expected={
            "avg_daily_sales": "5.000",
            "sales_qty_total": "140.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "MEDIUM",
            "risk_types": ["LOW_STOCK"],
            "inventory_position": "175.000",
            "safety_stock": "75.000",
            "lead_time_days": 30,
            "coverage_days": 30,
            "cv": "0.0000",
            "q_base": "200.000",
            "moq_applied": "1.000",
            "order_multiple_applied": None,
            "q_suggest": "150.000",
            "truncated_by_budget": True,
        },
        expect_findings={"R-LOWSTOCK": "MEDIUM"},
        params={"coverage_days": 30, "budget_limit": "150.000"},
    ),
    GoldenCase(
        case_id="GC-14",
        title="高波动上浮",
        item=make_item(5014, ["0.000"] * 15 + ["7.000"] * 13, on_hand="50.000"),
        expected={
            "avg_daily_sales": "3.250",
            "sales_qty_total": "91.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "NONE",
            "risk_types": [],
            "inventory_position": "50.000",
            "safety_stock": "19.500",
            "cv": "1.0742",
            "volatility_adjusted": True,
            "q_base": "15.000",
            "moq_applied": "1.000",
            "order_multiple_applied": None,
            "q_suggest": "15.000",
            "truncated_by_budget": False,
        },
        expect_findings=dict(_ALL_NOT_TRIGGERED),
    ),
    GoldenCase(
        case_id="GC-15",
        title="安全库存覆盖",
        item=make_item(
            5015,
            ["5.000"] * 28,
            on_hand="50.000",
            safety_stock_override="100.000",
        ),
        expected={
            "avg_daily_sales": "5.000",
            "sales_qty_total": "140.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "MEDIUM",
            "risk_types": ["LOW_STOCK"],
            "inventory_position": "50.000",
            "safety_stock": "100.000",
            "cv": "0.0000",
            "volatility_adjusted": False,
            "q_base": "120.000",
            "moq_applied": "1.000",
            "order_multiple_applied": None,
            "q_suggest": "120.000",
            "truncated_by_budget": False,
        },
        expect_findings={"R-LOWSTOCK": "MEDIUM"},
    ),
    GoldenCase(
        case_id="GC-16",
        title="有效在途计入 IP",
        item=make_item(5016, ["5.000"] * 28, on_hand="10.000", transit="8.000"),
        expected={
            "avg_daily_sales": "5.000",
            "sales_qty_total": "140.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "HIGH",
            "risk_types": ["STOCKOUT", "LOW_STOCK"],
            "on_hand_qty": "10.000",
            "effective_in_transit_qty": "8.000",
            "inventory_position": "18.000",
            "safety_stock": "20.000",
            "cv": "0.0000",
            "q_base": "72.000",
            "moq_applied": "1.000",
            "order_multiple_applied": None,
            "q_suggest": "72.000",
            "truncated_by_budget": False,
        },
        expect_findings={"R-STOCKOUT": "HIGH", "R-LOWSTOCK": "MEDIUM"},
    ),
    GoldenCase(
        case_id="GC-17",
        title="3σ 截尾",
        item=make_item(5017, ["5.000"] * 27 + ["100.000"], on_hand="8.000"),
        expected={
            "avg_daily_sales": "5.000",
            # 截尾只影响 `D`，窗口总销量仍是事实值 27×5+100
            "sales_qty_total": "235.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "HIGH",
            "risk_types": ["STOCKOUT", "LOW_STOCK", "SALES_ANOMALY"],
            "inventory_position": "8.000",
            "safety_stock": "20.000",
            "cv": "0.0000",
            "q_base": "82.000",
            "moq_applied": "1.000",
            "order_multiple_applied": None,
            "q_suggest": "82.000",
            "truncated_by_budget": False,
        },
        # 极端日同时让近 7 天日均抬高 ⇒ `R-SALESANOMALY=LOW`（`§11` 未禁止与其它规则共存）
        expect_findings={
            "R-STOCKOUT": "HIGH",
            "R-LOWSTOCK": "MEDIUM",
            "R-SALESANOMALY": "LOW",
        },
    ),
    GoldenCase(
        case_id="GC-18",
        title="全部规则未命中且 Q_base<=0",
        item=make_item(5018, ["1.000"] * 28, on_hand="50.000", lead_time_days=0),
        expected={
            "avg_daily_sales": "1.000",
            "sales_qty_total": "28.000",
            "valid_days": 28,
            "data_quality": "GOOD",
            "risk_level": "NONE",
            "risk_types": [],
            "inventory_position": "50.000",
            "safety_stock": "0.000",
            "lead_time_days": 0,
            "coverage_days": 0,
            "cv": "0.0000",
            "volatility_adjusted": False,
            "q_base": "0.000",
            "moq_applied": None,
            "order_multiple_applied": None,
            "q_suggest": None,
            "truncated_by_budget": False,
        },
        expect_findings=dict(_ALL_NOT_TRIGGERED),
        params={"coverage_days": 0},
    ),
)


def params_of(case: GoldenCase) -> dict[str, Any]:
    """合并默认参数与用例覆盖项。"""
    merged: Mapping[str, Any] = {**BASE_PARAMS, **case.params}
    return dict(merged)


__all__ = [
    "BASE_PARAMS",
    "CASES",
    "COMPANION_SKU_ID",
    "DATE_TO",
    "START",
    "GoldenCase",
    "make_item",
    "params_of",
    "series",
]
