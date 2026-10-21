package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDateTime;

/**
 * 到货单作废响应（{@code POST /api/v1/purchasing/receipts/{id}/cancel}；M6-T04）。
 *
 * <p>★ 契约未给作废响应示例，本 DTO 取「标识 + 终态 + 时间戳」四要素：
 * {@code goods_receipt_id} / {@code receipt_no} / {@code status}（恒 {@code CANCELLED}）/
 * {@code cancelled_at} / {@code version}（作废后的新版本号）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoodsReceiptCancelResponse(
        @JsonProperty("goods_receipt_id") Long goodsReceiptId,
        @JsonProperty("receipt_no") String receiptNo,
        @JsonProperty("status") String status,
        @JsonProperty("cancelled_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime cancelledAt,
        @JsonProperty("version") Integer version) {
}
