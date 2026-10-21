package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDateTime;

/**
 * 采购单状态动作响应（M6-T03 A1/A2/A3：{@code confirm} / {@code order} / {@code cancel}）。
 *
 * <p>三种动作共用本形态；不同动作只填各自字段（{@code NON_NULL} ⇒ 未涉及的字段不出现在 JSON 里）：
 * <ul>
 *   <li>{@code confirm}：{@code confirmed_by} + {@code confirmed_at}（契约 §11.8 请求 B 成功响应）；</li>
 *   <li>{@code order}：{@code ordered_at}；</li>
 *   <li>{@code cancel}：{@code cancelled_at} + {@code cancel_reason}。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurchaseOrderActionResponse(
        @JsonProperty("purchase_order_id") Long purchaseOrderId,
        @JsonProperty("po_no") String poNo,
        @JsonProperty("status") String status,
        @JsonProperty("confirmed_by") Long confirmedBy,
        @JsonProperty("confirmed_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime confirmedAt,
        @JsonProperty("ordered_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime orderedAt,
        @JsonProperty("cancelled_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime cancelledAt,
        @JsonProperty("cancel_reason") String cancelReason,
        @JsonProperty("version") Integer version) {
}
