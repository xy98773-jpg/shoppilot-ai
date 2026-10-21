package com.shoppilot.purchasing.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code PATCH /api/v1/purchasing/receipts/{id}} 请求体（到货草稿编辑；M6-T04）。
 *
 * <h2>★ 可改 / 不可改</h2>
 * <ul>
 *   <li><b>可改</b>：{@code items[]}（整组替换：数量 / 单价 / 批号 / 行备注）、
 *       {@code remark}（单头备注）；</li>
 *   <li><b>不可改</b>（本 DTO <b>没有</b>这些字段 ⇒ 传了也不生效）：
 *       {@code purchase_order_id} / {@code warehouse_id} / {@code receipt_date} /
 *       {@code idempotency_key} / {@code status} / {@code total_qty} / {@code total_amount}
 *       —— 后两者由服务端<b>同事务重算</b>。</li>
 * </ul>
 *
 * <h2>★ 部分更新语义</h2>
 * <ul>
 *   <li>{@code items} <b>未传（null）</b> ⇒ 明细行<b>不修改</b>；</li>
 *   <li>{@code items} 传了 ⇒ <b>增量 upsert</b>本到货单的明细行
 *       （校验同登记：{@code PO-9006} / {@code PO-9012} / {@code PO-9002} / {@code PO-9011} / {@code COM-0001}）：
 *       <ul>
 *         <li>本单<b>已存在</b>的 {@code purchase_order_item_id} ⇒ <b>UPDATE</b> 该行
 *             （DRAFT 时 {@code received_qty}/{@code unit_price}/{@code batch_no}/{@code remark} 可改）；</li>
 *         <li>本单<b>尚不存在</b>的 {@code purchase_order_item_id} ⇒ <b>INSERT</b> 新行；</li>
 *         <li>本单已有但本次<b>未提及</b>的行 ⇒ <b>保留不动</b>。</li>
 *       </ul>
 *       ★ <b>不是「整组替换」</b>：SSOT {@code DATABASE_DESIGN §5.21} 明写
 *       {@code goods_receipt_items}「<b>不物理删除</b>」⇒ 本批不提供「删行」能力
 *       （不能删行 ⇒ 未提及的旧行必须保留）。</li>
 *   <li>{@code remark} 未传（null）⇒ 保留原备注；传空串 {@code ""} ⇒ 清空为空的非 NULL 值。</li>
 * </ul>
 *
 * <p>★ {@code expected_version} 是乐观锁令牌：传了且与服务端当前 {@code version} 不一致 ⇒
 * {@code PO-9008}（409）；不传则由服务端用「读到的版本」做同语句 CAS（并发改动同样会被
 * {@code PO-9008} 拦下）。
 */
public class UpdateGoodsReceiptRequest {

    /** 乐观锁期望版本（可选；不一致 ⇒ {@code PO-9008}）。 */
    @JsonProperty("expected_version")
    private Integer expectedVersion;

    /** 单头备注（可选；未传 ⇒ 不修改；传 {@code ""} ⇒ 清空）。 */
    @JsonProperty("remark")
    private String remark;

    /** 明细行（可选；未传 ⇒ 不修改；传了 ⇒ 整组替换）。 */
    @JsonProperty("items")
    private List<GoodsReceiptItemRequest> items;

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public List<GoodsReceiptItemRequest> getItems() {
        return items;
    }

    public void setItems(List<GoodsReceiptItemRequest> items) {
        this.items = items;
    }
}
