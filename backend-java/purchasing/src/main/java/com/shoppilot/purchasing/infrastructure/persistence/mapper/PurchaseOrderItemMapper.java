package com.shoppilot.purchasing.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.purchasing.infrastructure.persistence.entity.PurchaseOrderItemEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code purchase_order_items} 表 Mapper（{@code DESIGN_BASIS §5.x} / 迁移 {@code V1.0.11__purchasing.sql}）。
 *
 * <p>★ {@code received_qty} 累加是典型丢失更新场景：更新必须用 {@code updateById(entity)}（CAS），禁止 {@code update(null, wrapper)}（绕过 {@code @Version}）。
 *
 * <p>★ <b>不加 {@code @InterceptorIgnore}</b>：走租户线（INSERT 自动补 {@code team_id}，
 * SELECT/UPDATE/DELETE 自动追加团队条件），本批<b>不写自定义 SQL</b>（无查询需求）。
 */
@Mapper
public interface PurchaseOrderItemMapper extends BaseMapper<PurchaseOrderItemEntity> {
}
