package com.shoppilot.analysis.application.dto;

/**
 * {@code createRun} 的应用层结果：响应数据 + 是否「幂等重放」。
 *
 * <p>控制器据 {@link #replayed()} 决定是否加 {@code Idempotent-Replayed: true} 响应头
 * （{@code API_CONTRACT §6}：同键同体重放时返回首次结果并标注）。
 *
 * @param data     响应体 {@code data}
 * @param replayed {@code true} = 本次未新建，返回的是既有 run
 */
public record CreateRunOutcome(CreateRunResponse data, boolean replayed) {
}
