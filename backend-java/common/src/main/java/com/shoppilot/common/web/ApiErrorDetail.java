package com.shoppilot.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 失败响应中的字段级细节（契约 {@code API_CONTRACT.md §2.3}「{@code details[]} 元素结构」）。
 *
 * <p>仅用于<b>字段级校验错误</b>（如 {@code COM-0001}）；无细节时 {@code details} 整体为 {@code null}（省略）。
 *
 * @param field         出错的字段路径，<b>snake_case</b>，支持嵌套（如 {@code params.window_days}）
 * @param message       该字段的中文错误说明
 * @param rejectedValue 被拒绝的原始值；<b>不得回显敏感值</b>（口令类恒为 {@code null} 或 {@code "***"}）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorDetail(String field, String message, Object rejectedValue) {

    public static ApiErrorDetail of(String field, String message) {
        return new ApiErrorDetail(field, message, null);
    }

    /**
     * 脱敏构造：敏感字段（口令等）的 {@code rejectedValue} 恒为掩码，不落原文。
     */
    public static ApiErrorDetail masked(String field, String message) {
        return new ApiErrorDetail(field, message, "***");
    }
}
