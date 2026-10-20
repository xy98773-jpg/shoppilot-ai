package com.shoppilot.replenishment.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 状态命令的请求体（{@code POST /items/{id}/submit}、{@code /withdraw}；{@code API_CONTRACT §11.7}）。
 *
 * <p>★ 请求体<b>可空</b>（契约示例即为「无请求体」）：控制器用
 * {@code @RequestBody(required = false)} 接收，{@code null} 等价于「不传意见」。
 *
 * <p>{@code comment} 只允许 ≤ 500 字符；超长 ⇒ 400 {@code COM-0001}（{@code details.field=comment}）。
 */
public class ReviewCommandRequest {

    /** 审核意见（可选；{@code SUBMIT}/{@code WITHDRAW} 均非必填）。 */
    @JsonProperty("comment")
    private String comment;

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
