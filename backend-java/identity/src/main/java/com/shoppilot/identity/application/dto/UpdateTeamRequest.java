package com.shoppilot.identity.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Size;

/**
 * 团队配置修改请求（{@code PATCH /api/v1/teams/{teamId}}，契约 §10.2
 * 「名称、{@code default_timezone}、{@code default_warehouse_id}」）。
 *
 * <p><b>PATCH 语义</b>：三者均可空，<b>为 {@code null} 表示不修改该字段</b>。
 * 字段名用 snake_case（{@code default_timezone} / {@code default_warehouse_id}）与契约一致。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UpdateTeamRequest(
        @Size(max = 128, message = "长度不能超过 128")
        String name,
        @Size(max = 64, message = "长度不能超过 64")
        String defaultTimezone,
        Long defaultWarehouseId) {
}
