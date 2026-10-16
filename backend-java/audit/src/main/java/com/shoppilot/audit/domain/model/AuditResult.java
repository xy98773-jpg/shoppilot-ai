package com.shoppilot.audit.domain.model;

/**
 * 审计结果（{@code audit_logs.result}，CHECK 约束 {@code ck_al_result}）。
 *
 * <p>权威来源：{@code DESIGN_BASIS.md §9.17} 的 {@code AuditResult} 行
 * （{@code SUCCESS}（操作成功）/ {@code FAILURE}（操作失败））。
 * 落库值即枚举名（DB CHECK 白名单与此二值严格一致，见迁移 {@code V1.0.4__audit.sql}）。
 */
public enum AuditResult {

    /** 操作成功。 */
    SUCCESS,

    /** 操作失败（{@code error_code} 记失败原因）。 */
    FAILURE
}
