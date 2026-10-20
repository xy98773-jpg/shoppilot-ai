package com.shoppilot.purchasing.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.purchasing.infrastructure.persistence.entity.GoodsReceiptItemEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code goods_receipt_items} 表 Mapper（{@code DESIGN_BASIS §5.x} / 迁移 {@code V1.0.11__purchasing.sql}）。
 *
 * <p>★ 本表<b>仅 INSERT</b>（到货单 {@code CONFIRMED} 后不可变）：不得调用 {@code updateById} / {@code update} / {@code delete*}。
 *
 * <p>★ <b>不加 {@code @InterceptorIgnore}</b>：走租户线（INSERT 自动补 {@code team_id}，
 * SELECT/UPDATE/DELETE 自动追加团队条件），本批<b>不写自定义 SQL</b>（无查询需求）。
 */
@Mapper
public interface GoodsReceiptItemMapper extends BaseMapper<GoodsReceiptItemEntity> {
}
