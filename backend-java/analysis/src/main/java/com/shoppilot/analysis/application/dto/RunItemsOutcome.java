package com.shoppilot.analysis.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.common.web.PageResult;

/**
 * {@code GET /api/v1/analysis/runs/{id}/items} 的查询结果（{@code API_CONTRACT.md §11.6}）。
 *
 * <p>承载两种互斥结果：
 * <ul>
 *   <li><b>就绪</b>（{@code page != null}）：{@code progress == 100}，返回分页明细；</li>
 *   <li><b>未就绪</b>（{@code notReady != null}）：{@code progress < 100}，由控制器转
 *       {@code 409 ANL-7012}，{@code data} 附 {@link NotReady}（{@code §11.6} 失败响应 2）。</li>
 * </ul>
 *
 * <p>★ 为什么不用 {@code BizException} 抛「未就绪」：{@code ANL-7012} 契约要求 {@code data}
 * 回带 {@code {run_id, status, progress}}，而 {@code GlobalExceptionHandler.handleBiz} 的
 * {@code data} 恒为 {@code null}。故沿用 {@code ImportBatchController}（{@code IMP-4004} 带
 * {@code data}）的「service 返回 outcome → controller 分派」既有模式，不改 common 全局出口。
 */
public record RunItemsOutcome(
        PageResult<AnalysisItemView> page,
        NotReady notReady) {

    /** 结果是否已就绪（{@code progress == 100}）。 */
    public boolean ready() {
        return page != null;
    }

    /** 就绪：分页明细。 */
    public static RunItemsOutcome ready(PageResult<AnalysisItemView> page) {
        return new RunItemsOutcome(page, null);
    }

    /** 未就绪：携带 {@code run_id/status/progress}（{@code §11.6} 失败响应 2 的 {@code data}）。 */
    public static RunItemsOutcome notReady(AnalysisRunEntity run) {
        return new RunItemsOutcome(null, new NotReady(
                run.getId(), run.getStatus(), run.getProgress()));
    }

    /** {@code ANL-7012} 的 {@code data}（{@code §11.6}）。 */
    public record NotReady(
            @JsonProperty("run_id") Long runId,
            @JsonProperty("status") String status,
            @JsonProperty("progress") Integer progress) {
    }
}
