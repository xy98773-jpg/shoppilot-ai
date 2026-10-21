package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 取消采购单请求（M6-T03 A3）。
 *
 * <p>{@code reason} <b>必填</b>且 ≤255 字符（对齐 {@code cancel_reason VARCHAR(255)}）；
 * 缺失/超长 ⇒ {@code COM-0001}（Service 层判，空 body 亦按缺失处理）。
 */
public record PurchaseOrderCancelRequest(
        @JsonProperty("reason") String reason) {
}
