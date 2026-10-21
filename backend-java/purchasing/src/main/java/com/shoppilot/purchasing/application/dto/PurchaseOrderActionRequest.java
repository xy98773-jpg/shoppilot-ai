package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 采购单状态动作请求（{@code confirm} / {@code order}，M6-T03 A1/A2）。
 *
 * <p>契约 §11.8 请求 B：{@code {"expected_version": 1}}。角色不足由
 * {@code @RequireRole} 在方法入口拦（{@code TEAM-2003}），不到 Service。
 */
public record PurchaseOrderActionRequest(
        @JsonProperty("expected_version") Integer expectedVersion) {
}
