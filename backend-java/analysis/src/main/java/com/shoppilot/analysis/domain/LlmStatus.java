package com.shoppilot.analysis.domain;

/**
 * LLM 阶段状态（{@code analysis_runs.llm_status}；权威定义 {@code DESIGN_BASIS.md §9.8}）。
 *
 * <p>{@code SKIPPED} = 分析配置关闭了 LLM 解释，或前置步骤判定无需解释
 * （如全部 SKU 数据不足）。LLM 状态<b>不</b>影响 run 的业务成败：
 * {@code LLM} 失败至多使 run 降级为 {@link AnalysisRunStatus#DEGRADED}，规则结果不丢
 * （{@code AI_SERVICE_CONTRACT.md §8}）。
 *
 * <p>与 {@code ck_run_llm_status} CHECK 的取值集合一一对应（V1.0.9 迁移）。
 */
public enum LlmStatus {

    /** 未请求 LLM（初始值；{@code enable_llm=false} 时保持该值）。 */
    NOT_REQUESTED,

    /** 已请求、待返回。 */
    PENDING,

    /** LLM 解释成功。 */
    SUCCEEDED,

    /** LLM 调用失败（不影响规则结果）。 */
    FAILED,

    /** LLM 调用超时。 */
    TIMEOUT,

    /** LLM 输出未通过 Schema 校验。 */
    VALIDATION_FAILED,

    /** 跳过 LLM（配置关闭或无需解释）。 */
    SKIPPED
}
