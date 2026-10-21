package com.shoppilot.replenishment.application;

import com.shoppilot.analysis.application.event.AnalysisRunCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 「分析完成 → 生成补货建议」的编排入口（{@code MODULE_BOUNDARIES.md §8.2 E-2} 的落地实现）。
 *
 * <h2>为什么在 {@code replenishment} 侧监听</h2>
 * <p>模块 DAG 是 {@code analysis → replenishment}（{@code DESIGN_BASIS.md §7.1}）。
 * 由 {@code analysis} 反调 {@code ReplenishmentGenerationService} 会形成反向依赖，
 * 架构测试与 {@code MODULE_BOUNDARIES §5.3} 均禁止。因此 {@code analysis} 只发布
 * {@link AnalysisRunCompletedEvent}，由本监听器在自己的模块内调用
 * {@link ReplenishmentGenerationService#generateFromRun(Long)}。
 *
 * <h2>★ 三个刻意选择</h2>
 * <ol>
 *   <li><b>{@code AFTER_COMMIT}</b>：分析快照（{@code analysis_items}）一旦通过二次校验落库
 *       就是不可变事实，建议生成失败<b>不得</b>回滚它。这与「LLM 失败也要保留规则结果」
 *       是同一取舍（{@code DESIGN_BASIS §8}）。</li>
 *   <li><b>异常只记 ERROR、不向上抛</b>：{@code AFTER_COMMIT} 阶段抛异常会污染调用方
 *       （轮询线程/HTTP 请求），让一次已成功的运行表现为失败。生成侧本身<b>幂等</b>
 *       （{@code generateFromRun} 先按 {@code run_id} 查后插），失败可安全重放。</li>
 *   <li><b>不切线程（不用 {@code @Async}）</b>：生成需要 {@code TeamContext}
 *       （租户线限定团队），异步线程会丢上下文，造成跨团队写或空结果。
 *       本方法在发布事件的原线程内执行，上下文仍在。</li>
 * </ol>
 *
 * <p>★ 无状态；不写审计（{@code ReplenishmentGenerationService} 内部自行记录）。
 */
@Component
public class AnalysisRunCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunCompletedListener.class);

    private final ReplenishmentGenerationService generationService;

    public AnalysisRunCompletedListener(ReplenishmentGenerationService generationService) {
        this.generationService = generationService;
    }

    /**
     * 分析运行终态提交后派发建议。
     *
     * @param event 终态事件（{@code runId} 非空）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnalysisRunCompleted(AnalysisRunCompletedEvent event) {
        if (event == null || event.runId() == null) {
            return;
        }
        try {
            ReplenishmentGenerationResult result = generationService.generateFromRun(event.runId());
            log.info("分析完成派发补货建议：runId={} teamId={} created={} existing={} idempotent={}",
                    event.runId(), event.teamId(), result.createdCount(),
                    result.existingCount(), result.idempotent());
        } catch (RuntimeException ex) {
            // ★ 只记不抛：分析结果已提交，派发失败不应让调用方看到失败
            log.error("分析完成派发补货建议失败（分析结果已落库，不回滚；可安全重放）："
                            + "runId={} teamId={} reason={}",
                    event.runId(), event.teamId(), ex.toString(), ex);
        }
    }
}
