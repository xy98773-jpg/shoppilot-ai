package com.shoppilot.analysis.application.event;

/**
 * 「分析运行已到达终态」的领域事件（{@code MODULE_BOUNDARIES.md §8.2 E-2} 的落地载体）。
 *
 * <h2>为什么用事件而不是直接调用</h2>
 * <p>模块 DAG 是 {@code analysis → replenishment}（{@code DESIGN_BASIS.md §7.1}）：
 * {@code replenishment} 可以依赖 {@code analysis}，<b>反之禁止</b>。因此
 * 「把分析结果派发为补货建议」这一步只能由 {@code replenishment} 侧发起。
 * 本事件由 {@code analysis} 在结果落库事务<b>提交时</b>发布，
 * {@code replenishment} 侧用 {@code @TransactionalEventListener(AFTER_COMMIT)} 订阅，
 * 从而在不产生反向依赖的前提下完成编排（{@code MODULE_BOUNDARIES §5.3}）。
 *
 * <h2>★ 为什么是 AFTER_COMMIT 而不是同步监听</h2>
 * <p>分析结果（{@code analysis_items}）一旦通过二次校验落库，就是不可变快照；
 * 建议生成失败<b>不得</b>回滚已成立的分析结果（与「LLM 失败不能丢规则结果」同一取舍，
 * {@code DESIGN_BASIS §8}）。生成侧本身幂等（{@code ReplenishmentGenerationService}
 * 先查后插），失败后可由运维重放。
 *
 * @param runId  已到终态的分析运行 ID
 * @param teamId 该运行所属团队（仅供日志与断言；<b>不建议</b>用它绕过 {@code TeamContext}）
 */
public record AnalysisRunCompletedEvent(Long runId, Long teamId) {
}
