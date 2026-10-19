package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * 内部契约 {@code items[]} 单个 SKU 的计算输入（{@code AI_SERVICE_CONTRACT.md §3.2}，
 * 结构<b>逐字段</b>对齐 Python 侧 {@code schemas/analysis_request.py} 的 {@code Item}）。
 *
 * <p><b>★ 可空性必须与 Python 侧一致</b>（该模型 {@code extra="forbid"}，多一个字段就 400）：
 * <ul>
 *   <li><b>必填</b>（Python 无默认值）：{@code sku_id} / {@code on_hand_qty} /
 *       {@code committed_qty} / {@code effective_in_transit_qty} / {@code sales_series}；</li>
 *   <li><b>可空</b>（{@code @JsonInclude(NON_NULL)} 直接省略）：{@code lead_time_days} /
 *       {@code safety_stock_override} / {@code moq} / {@code order_multiple} /
 *       {@code qty_step} / {@code listed_date}；</li>
 *   <li>{@code lead_time_source} 恒有值（DEFAULT 兜底，Python 侧是必填且有枚举校验）。</li>
 * </ul>
 *
 * <p><b>★ 数量一律字符串</b>（{@code §4.1}）：3 位小数 + {@code toPlainString()}，
 * 禁止 {@code double}；Python 侧正则 {@code ^(0|[1-9][0-9]*)(\.[0-9]{1,3})?$} 会拒绝负号与 4 位小数。
 *
 * @param skuId                 SKU 主键
 * @param onHandQty             现有库存（3 位小数字符串；缺余额行 ⇒ {@code "0.000"}）
 * @param committedQty          已承诺库存（同上）
 * @param effectiveInTransitQty 有效在途（<b>R124 临时口径</b>，见 {@code AnalysisItemAssembler}）
 * @param leadTimeDays          采用交期 L（可空）
 * @param leadTimeSource        交期来源（POLICY / SKU / SUPPLIER / DEFAULT）
 * @param safetyStockOverride   安全库存绝对量覆盖（可空）
 * @param moq                   最小起订量（可空 ⇒ Python 取默认 1）
 * @param orderMultiple         采购倍数（可空）
 * @param qtyStep               最小起订精度（可空 ⇒ Python 取默认 1）
 * @param listedDate            上架日（可空）
 * @param salesSeries           销量序列（<b>只含有记录的日子</b>，空数组合法）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiRunItem(
        @JsonProperty("sku_id") Long skuId,
        @JsonProperty("on_hand_qty") String onHandQty,
        @JsonProperty("committed_qty") String committedQty,
        @JsonProperty("effective_in_transit_qty") String effectiveInTransitQty,
        @JsonProperty("lead_time_days") Integer leadTimeDays,
        @JsonProperty("lead_time_source") String leadTimeSource,
        @JsonProperty("safety_stock_override") String safetyStockOverride,
        @JsonProperty("moq") String moq,
        @JsonProperty("order_multiple") String orderMultiple,
        @JsonProperty("qty_step") String qtyStep,
        @JsonProperty("listed_date") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate listedDate,
        @JsonProperty("sales_series") List<AiSalesPoint> salesSeries) {
}