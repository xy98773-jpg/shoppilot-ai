package com.shoppilot.replenishment.application;

/**
 * 补货建议生成结果（M5-T02）。
 *
 * @param runId        来源分析运行 ID
 * @param createdCount 本次<b>新插入</b>的建议条数（幂等命中时恒为 0）
 * @param existingCount 幂等命中时该 run 的既有建议条数（未命中时为 0）
 * @param idempotent   {@code true} = 该 run 已有建议，本次未插入任何行（{@code A2} 幂等）
 */
public record ReplenishmentGenerationResult(Long runId, int createdCount, int existingCount,
                                            boolean idempotent) {
}
