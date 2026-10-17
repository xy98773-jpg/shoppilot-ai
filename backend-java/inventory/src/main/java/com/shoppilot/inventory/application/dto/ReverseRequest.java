package com.shoppilot.inventory.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 冲销请求体（{@code POST /api/v1/inventory/transactions/{id}/reverse}，契约 {@code API_CONTRACT §10.4}）。
 *
 * <p>被冲销流水 ID 来自路径 {@code {id}}；本请求体仅含必填 {@code reason}。幂等键来自请求头
 * {@code Idempotency-Key}（控制器透传 {@code InventoryWriteService}，本 DTO 不含）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ReverseRequest {

    /** 冲销原因（必填）。 */
    private String reason;
}
