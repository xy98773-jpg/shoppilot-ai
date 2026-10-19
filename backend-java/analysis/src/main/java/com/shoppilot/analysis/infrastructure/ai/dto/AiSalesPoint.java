package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 内部契约 {@code sales_series[]} 单点（{@code AI_SERVICE_CONTRACT.md §3.2}，
 * 结构对齐 Python 侧 {@code schemas/analysis_request.py} 的 {@code SalesPoint}）。
 *
 * <p><b>★ 只含有记录的日子</b>（{@code ANALYSIS_CONTRACT §1.2}）：缺失日<b>不补 0</b>——
 * Python 侧的 {@code valid_days} 就是按记录条数算的，Java 补 0 会直接算错有效天数
 * 与异常值比例。
 *
 * <p><b>★ 口径（§5.36 裁定 #10 / Q9）</b>：{@code qty} 取 {@code sales_daily.qty}，
 * 该列<b>已是净销量</b>（退货单独记在 {@code return_qty}，此处不二次抵扣）；
 * 若将来口径变化，必须同步改本类与 {@code SalesQueryService.getSeries} 的用法。
 *
 * <p>数量按 {@code AI_SERVICE_CONTRACT §4.1} 用字符串传输（固定 3 位小数，禁 IEEE-754 裸传）。
 *
 * @param date 销售日期（ISO-8601）
 * @param qty  当日净销量（3 位小数字符串）
 */
public record AiSalesPoint(
        @JsonProperty("date") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate date,
        @JsonProperty("qty") String qty) {

    /** 净销量定点字符串（3 位小数，{@code §4.1}）。 */
    public static String decimal3(BigDecimal value) {
        return value == null ? null : value.setScale(3, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}