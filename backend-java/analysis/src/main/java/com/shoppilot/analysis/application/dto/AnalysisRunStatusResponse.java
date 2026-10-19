package com.shoppilot.analysis.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code GET /api/v1/analysis/runs/{id}} 成功响应 {@code data}（{@code API_CONTRACT.md §11.5}）。
 *
 * <p>字段与契约两个示例<b>逐项对齐</b>（{@code §11.5:1483} 中间态 / {@code :1510} 终态）：
 * <ul>
 *   <li>中间态：{@code run_id / run_no / status / scope_type / warehouse_id / total_skus /
 *       processed_skus / progress / current_step / llm_status / timeout_at / started_at / finished_at}；</li>
 *   <li>终态追加：{@code llm_error_message / risk_sku_count / suggestion_count /
 *       data_start_date / data_end_date}。</li>
 * </ul>
 *
 * <p>★ 中间态与终态的可空字段<b>一律用 {@code null}</b>，不返回空串或 {@code 0} 占位
 * （{@code @JsonInclude(NON_NULL)}）：前端需要靠「字段缺失」区分「还没算完」与「算完是 0」，
 * 用 0 占位会让「零风险」和「尚无数据」混为一谈。
 * 例外：{@code total_skus}/{@code processed_skus}/{@code progress} 是<b>非空计数</b>，
 * DB 里恒有值（建行即写 0），按契约示例输出。
 *
 * <p>★ {@code total_skus} 口径（R135）：建行时写入<b>装配后的实际 SKU 数</b>
 * （而非请求声明数——{@code ALL_ACTIVE}/{@code CATEGORY} 过去恒为 0）。
 *
 * @param runId           运行 ID
 * @param runNo           运行编号
 * @param status          运行状态（{@code AnalysisRunStatus}）
 * @param scopeType       范围类型
 * @param warehouseId     仓库（解析后的默认仓；恒非空）
 * @param totalSkus       分析 SKU 数（装配后实际数）
 * @param processedSkus   已完成 SKU 数
 * @param progress        进度 0–100（Python 档位值，R119；Java 不自算）
 * @param currentStep     当前步骤
 * @param llmStatus       run 级 LLM 状态
 * @param llmErrorMessage LLM 失败原因（仅 {@code DEGRADED} 非空）
 * @param riskSkuCount    风险 SKU 数（终态）
 * @param suggestionCount 建议数（终态；Java 自算，裁定 #19）
 * @param dataStartDate   输入销售数据最早日
 * @param dataEndDate     输入销售数据最晚日
 * @param timeoutAt       超时截止
 * @param startedAt       开始时间
 * @param finishedAt      结束时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisRunStatusResponse(
        @JsonProperty("run_id") Long runId,
        @JsonProperty("run_no") String runNo,
        @JsonProperty("status") String status,
        @JsonProperty("scope_type") String scopeType,
        @JsonProperty("warehouse_id") Long warehouseId,
        @JsonProperty("total_skus") Integer totalSkus,
        @JsonProperty("processed_skus") Integer processedSkus,
        @JsonProperty("progress") Integer progress,
        @JsonProperty("current_step") String currentStep,
        @JsonProperty("llm_status") String llmStatus,
        @JsonProperty("llm_error_message") String llmErrorMessage,
        @JsonProperty("risk_sku_count") Integer riskSkuCount,
        @JsonProperty("suggestion_count") Integer suggestionCount,
        @JsonProperty("data_start_date") LocalDate dataStartDate,
        @JsonProperty("data_end_date") LocalDate dataEndDate,
        @JsonProperty("timeout_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime timeoutAt,
        @JsonProperty("started_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime startedAt,
        @JsonProperty("finished_at")
        @JsonSerialize(using = UtcDateTimeSerializer.class) LocalDateTime finishedAt) {

    /** 从实体快照转换（计数列建行即有值，{@code null} 时兜 0；其余可空列保持 {@code null}）。 */
    public static AnalysisRunStatusResponse from(AnalysisRunEntity entity) {
        return new AnalysisRunStatusResponse(
                entity.getId(),
                entity.getRunNo(),
                entity.getStatus(),
                entity.getScopeType(),
                entity.getWarehouseId(),
                entity.getTotalSkus() == null ? 0 : entity.getTotalSkus(),
                entity.getProcessedSkus() == null ? 0 : entity.getProcessedSkus(),
                entity.getProgress() == null ? 0 : entity.getProgress(),
                entity.getCurrentStep(),
                entity.getLlmStatus(),
                entity.getLlmErrorMessage(),
                entity.getRiskSkuCount(),
                entity.getSuggestionCount(),
                entity.getDataStartDate(),
                entity.getDataEndDate(),
                entity.getTimeoutAt(),
                entity.getStartedAt(),
                entity.getFinishedAt());
    }
}