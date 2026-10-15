package com.shoppilot.identity.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 团队配置详情（{@code GET /api/v1/teams/{teamId}}，{@code API_CONTRACT §10.2}「名称/时区/状态/默认仓」）。
 *
 * <p>字段名对齐 {@code API_CONTRACT §12} 核对表：{@code team_id}（{@code teams.id}）、
 * {@code team_name}（{@code teams.name}）、{@code default_timezone}、{@code default_warehouse_id}。
 * 不含 {@code teams.code}（对外标识），避免暴露内部编码（契约核对表未列该字段）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TeamDetail(
        Long teamId,
        String teamName,
        String status,
        String defaultTimezone,
        Long defaultWarehouseId) {
}
