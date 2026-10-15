package com.shoppilot.identity.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 用户启用/禁用请求体（{@code PATCH /api/v1/users/{userId}/status}）。
 *
 * @param status 目标 {@code UserStatus}，取值 {@code ACTIVE} / {@code DISABLED}
 *               （{@code DESIGN_BASIS §9.1}）。
 */
public record UpdateUserStatusRequest(
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "ACTIVE|DISABLED", message = "取值必须为 ACTIVE 或 DISABLED")
        String status) {
}
