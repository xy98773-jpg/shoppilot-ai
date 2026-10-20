package com.shoppilot.replenishment.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 审批类命令的请求体（{@code POST /items/{id}/approve|reject}；{@code API_CONTRACT §11.7}）。
 *
 * <p>★ 与 submit/withdraw 不同，审批类命令<b>必须</b>带 {@code expected_version}：
 * 它是客户端在详情页读到的版本，服务端先比对（给字段级 details），再用
 * {@code @Version} CAS 兜底（双保险，见 {@code M5-T05 A4}）。
 *
 * <p>{@code comment}：{@code approve} 可选；{@code reject} <b>必填且非空白</b>
 * （空 ⇒ 400 {@code RPL-8005}）。
 */
public class ApprovalCommandRequest {

    /** 期望版本（必填，来自详情响应的 {@code version}）。 */
    @JsonProperty("expected_version")
    private Integer expectedVersion;

    /** 审核意见（{@code reject} 必填；{@code approve} 可选；≤500 字符）。 */
    @JsonProperty("comment")
    private String comment;

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
