package com.shoppilot.replenishment.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentReviewEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 审核记录（时间线）元素（{@code GET /api/v1/replenishment/items/{id}/reviews}）。
 *
 * <p>字段为 {@code replenishment_reviews} 的业务列全集（不含 {@code team_id}——隔离维度不外露）：
 * {@code id / replenishment_item_id / action / from_status / to_status / comment /
 * qty_before / qty_after / reviewer_id / reviewer_role / request_id / occurred_at /
 * created_at / created_by}。
 *
 * <p>★ 数量列 {@code qty_before}/{@code qty_after} 为<b>字符串</b>（scale=3，{@code §9.4}）；
 * 时间列 ISO-8601 UTC。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplenishmentReviewResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("replenishment_item_id") Long replenishmentItemId,
        @JsonProperty("action") String action,
        @JsonProperty("from_status") String fromStatus,
        @JsonProperty("to_status") String toStatus,
        @JsonProperty("comment") String comment,
        @JsonProperty("qty_before") String qtyBefore,
        @JsonProperty("qty_after") String qtyAfter,
        @JsonProperty("reviewer_id") Long reviewerId,
        @JsonProperty("reviewer_role") String reviewerRole,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("occurred_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime occurredAt,
        @JsonProperty("created_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime createdAt,
        @JsonProperty("created_by") Long createdBy) {

    private static final int QTY_SCALE = 3;

    public static ReplenishmentReviewResponse of(ReplenishmentReviewEntity entity) {
        return new ReplenishmentReviewResponse(
                entity.getId(),
                entity.getReplenishmentItemId(),
                entity.getAction(),
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getComment(),
                qtyText(entity.getQtyBefore()),
                qtyText(entity.getQtyAfter()),
                entity.getReviewerId(),
                entity.getReviewerRole(),
                entity.getRequestId(),
                entity.getOccurredAt(),
                entity.getCreatedAt(),
                entity.getCreatedBy());
    }

    private static String qtyText(BigDecimal value) {
        return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
