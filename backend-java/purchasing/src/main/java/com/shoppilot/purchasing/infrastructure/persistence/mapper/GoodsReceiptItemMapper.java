package com.shoppilot.purchasing.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.purchasing.infrastructure.persistence.entity.GoodsReceiptItemEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code goods_receipt_items} 表 Mapper（{@code DESIGN_BASIS §5.x} / 迁移 {@code V1.0.11__purchasing.sql}）。
 *
 * <p>★ <b>本表无 {@code version}（审计集合 B）</b>：到货单 {@code CONFIRMED} 后一切字段冻结
 * （只能走冲销），本表<b>不物理删除</b>。
 *
 * <p>★ <b>写路径口径（{@code DATABASE_DESIGN §5.21} 更新规则）</b>：
 * {@code received_qty} / {@code unit_price} / {@code amount} / {@code batch_no} / {@code remark}
 * 「<b>仅到货单 DRAFT 时可改</b>」⇒ DRAFT 期（登记 / 编辑）允许
 * {@code insert} 与<b>受限的</b> {@code update}（按 {@code id} + {@code goods_receipt_id}
 * 双条件定位）；<b>不得</b> {@code delete}（SSOT「不物理删除」）。
 * {@code CONFIRMED} 之后本表<b>只读</b>（M6-T05 确认 / M6-T08 冲销均不写本表）。
 *
 * <p>★ <b>不加 {@code @InterceptorIgnore}</b>：走租户线（INSERT 自动补 {@code team_id}，
 * SELECT/UPDATE/DELETE 自动追加团队条件），本批<b>不写自定义 SQL</b>（无查询需求）。
 */
@Mapper
public interface GoodsReceiptItemMapper extends BaseMapper<GoodsReceiptItemEntity> {
}
