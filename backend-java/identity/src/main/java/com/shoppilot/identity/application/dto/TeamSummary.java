package com.shoppilot.identity.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 团队摘要（登录 / me 响应中的 {@code data.team}）。
 *
 * <p>字段名严格对齐 {@code API_CONTRACT §11.1}：{@code team_id} / {@code team_name} /
 * {@code default_timezone}（snake_case）。注意实体列名为 {@code teams.name}，
 * 对外字段名是 {@code team_name}，转换在服务层完成。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TeamSummary(
        Long teamId,
        String teamName,
        String defaultTimezone) {
}
