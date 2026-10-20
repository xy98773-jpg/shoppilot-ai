package com.shoppilot.replenishment.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code PATCH /api/v1/replenishment/items/{id}} 请求体（{@code API_CONTRACT §10.8}，F-RPL-02）。
 *
 * <h2>部分更新语义（M5-T03 B1–B3）</h2>
 * <ul>
 *   <li><b>未传 / 显式 {@code null} ⇒ 不修改</b>（两者不可区分，均视为「不改」）；</li>
 *   <li>{@code remark} 传<b>空串 {@code ""} ⇒ 落库 {@code ""}</b>（不是 {@code NULL}）；</li>
 *   <li>数值/日期<b>不支持清空</b>（传 {@code null} 即「不改」）；</li>
 *   <li>四个可改字段<b>全为 null ⇒ 400 {@code COM-0001}</b>（否则会白白把 {@code version} +1）。</li>
 * </ul>
 *
 * <p>★ {@code version} <b>必填</b>：它是乐观锁令牌，缺失 ⇒ 400 {@code COM-0001}。
 * 客户端应原样回传 {@code GET /items/{id}} 返回的 {@code version}。
 */
public class UpdateReplenishmentItemRequest {

    /** 乐观锁版本（必填）。 */
    @JsonProperty("version")
    private Integer version;

    /** 修改后数量（{@code > 0}，小数位 ≤ 3；★ 无上限）。 */
    @JsonProperty("qty")
    private BigDecimal qty;

    /** 指定供应商（须属于本团队）。 */
    @JsonProperty("supplier_id")
    private Long supplierId;

    /** 期望到货日（不得早于今天 UTC）。 */
    @JsonProperty("expected_arrival_date")
    private LocalDate expectedArrivalDate;

    /** 备注（≤ 500 字符；传 {@code ""} 表示清空为空的非 NULL 值）。 */
    @JsonProperty("remark")
    private String remark;

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public Long getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(Long supplierId) {
        this.supplierId = supplierId;
    }

    public LocalDate getExpectedArrivalDate() {
        return expectedArrivalDate;
    }

    public void setExpectedArrivalDate(LocalDate expectedArrivalDate) {
        this.expectedArrivalDate = expectedArrivalDate;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
