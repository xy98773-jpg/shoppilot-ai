package com.shoppilot.replenishment.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDateTime;

/**
 * 状态命令的成功响应 {@code data}（{@code POST /items/{id}/submit|withdraw}；
 * {@code API_CONTRACT §11.7} 逐字段对齐）。
 *
 * <p>字段：{@code replenishment_item_id / status / qty / submitted_at / review_id /
 * action / from_status / to_status}。其中：
 * <ul>
 *   <li>{@code qty} 为<b>字符串</b>（scale=3，{@code §9.4}）；</li>
 *   <li>{@code review_id} 是本次新增 {@code replenishment_reviews} 的主键（审计留痕的可追溯入口）；</li>
 *   <li>{@code submitted_at} 在 {@code withdraw} 时返回<b>既有值</b>（撤回不清空，{@code §5.55} 裁定 4）。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplenishmentReviewActionResponse(
        @JsonProperty("replenishment_item_id") Long replenishmentItemId,
        @JsonProperty("status") String status,
        @JsonProperty("qty") String qty,
        @JsonProperty("submitted_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime submittedAt,
        @JsonProperty("review_id") Long reviewId,
        @JsonProperty("action") String action,
        @JsonProperty("from_status") String fromStatus,
        @JsonProperty("to_status") String toStatus) {
}
