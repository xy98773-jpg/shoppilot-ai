package com.shoppilot.analysis.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDateTime;

/**
 * {@code POST /api/v1/analysis/runs/{id}/cancel} 成功响应 {@code data}（{@code M4-T14}，{@code B20.4}）。
 *
 * <p>字段按任务书 §A7：{@code run_id} / {@code status="CANCELLED"} / {@code cancelled_at}。
 * {@code cancelled_at} 取 {@code analysis_runs.finished_at}（{@code NOW(3)} 写入的 UTC 时刻），
 * 按 {@code DESIGN_BASIS §14.1} 输出 ISO-8601 UTC 毫秒（{@link UtcDateTimeSerializer}，
 * 形如 {@code 2026-10-20T08:00:00.000Z}）。
 *
 * <p>★ 幂等重放（已 {@code CANCELLED} 再取消）返回同一结构、同一 {@code cancelled_at}——
 * 它是首次取消的真实时刻，不是本次请求时间。
 *
 * @param runId       运行 ID（{@code analysis_runs.id}）
 * @param status      取消后的状态（恒 {@code CANCELLED}）
 * @param cancelledAt 取消时刻（{@code finished_at}）
 */
public record CancelRunResponse(
        @JsonProperty("run_id") Long runId,
        @JsonProperty("status") String status,
        @JsonProperty("cancelled_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime cancelledAt) {

    /** 从取消后的实体快照转换。 */
    public static CancelRunResponse from(AnalysisRunEntity entity) {
        return new CancelRunResponse(entity.getId(), entity.getStatus(), entity.getFinishedAt());
    }
}
