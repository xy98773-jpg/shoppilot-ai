package com.shoppilot.analysis.domain;

/**
 * 分析运行状态（{@code analysis_runs.status}；权威定义 {@code DESIGN_BASIS.md §9.7}）。
 *
 * <p>合法转换：{@code PENDING → RUNNING → {SUCCEEDED | DEGRADED | FAILED | TIMEOUT | CANCELLED}}；
 * <b>终态不可再变</b>（{@code §9.7} 原文）。
 *
 * <p>状态机归属（{@code DESIGN_BASIS §13.2}）：<b>Java 是 {@code AnalysisRunStatus} 权威</b>——
 * Python 侧只保存执行细节，业务状态一律由 Java 依据 Python 回报更新。
 * {@code DEGRADED} 表示「规则计算成功但 LLM 解释失败」，业务可用（ADR-001 关键设计）。
 *
 * <p>本枚举只承载取值与合法性判定，<b>不</b>包含任何持久化或转换逻辑（那在应用层）；
 * 与 {@code ck_run_status} CHECK 的取值集合一一对应（V1.0.9 迁移，
 * {@code AnalysisSchemaConstraintTest} 已断言）。
 */
public enum AnalysisRunStatus {

    /** 已创建，未开始。 */
    PENDING,

    /** 执行中（附带 {@code progress} 0–100 与 {@code current_step}）。 */
    RUNNING,

    /** 全部成功（含 LLM 解释）。终态。 */
    SUCCEEDED,

    /** 规则计算成功但 LLM 解释失败。终态，业务可用。 */
    DEGRADED,

    /** 规则计算阶段失败。终态，附 {@code error_code} / {@code error_message}。 */
    FAILED,

    /** 超时（{@code timeout_at} 已过）。终态，可重试（生成新 run）。 */
    TIMEOUT,

    /** 用户主动取消。终态。 */
    CANCELLED;

    /** 是否终态（{@code §9.7}：终态不可再变；由 B20 超时扫描与取消端点消费）。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEGRADED || this == FAILED
                || this == TIMEOUT || this == CANCELLED;
    }

    /** 是否「进行中」（并发保护 {@code ANL-7001} 的判定集，{@code ERROR_CODES.md §2.8}）。 */
    public boolean isInFlight() {
        return this == PENDING || this == RUNNING;
    }
}
