package com.shoppilot.replenishment.application.dto;

import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentItemEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补货建议的<b>跨模块只读</b>摘要（M6-T02 转采购用；{@code DATABASE_DESIGN §7.2 #8} 步骤 ①/③）。
 *
 * <p>{@code purchasing} 只需「校验状态」与「取建单所需的数量/单价/供应商」两组信息，
 * 本 DTO 只暴露这些，<b>不外泄</b>实体本身（{@code MODULE_BOUNDARIES §4.1} 规则 1：
 * 跨模块只经服务接口，不接触他模块实体/Mapper）。
 *
 * <p>★ 建单三要素的口径（{@code API_CONTRACT §11.8} 请求体<b>没有</b>这三项，
 * 说明它们<b>只来自建议</b>、不接受客户端覆盖）：
 * {@code orderedQty ← 建议 qty}、{@code unitPrice ← 建议 purchasePrice}、
 * {@code supplierId ← 建议 supplier_id}。
 *
 * @param id            建议 ID
 * @param skuId         SKU
 * @param productId     冗余商品 ID
 * @param warehouseId   建议所属仓库
 * @param status        建议状态（{@code ReplenishmentItemStatus}）
 * @param qty           当前生效数量（建单取它，不是 {@code suggested_qty}）
 * @param suggestedQty  系统原始建议量（仅供追溯/校验，不用于建单）
 * @param purchasePrice 参考采购单价（可空 ⇒ 建单行金额为 null）
 * @param supplierId    供应商（可空）
 * @param riskLevel     风险等级
 * @param version       乐观锁版本（供调用方判断是否被并发改动）
 * @param createdAt     创建时间
 */
public record ReplenishmentItemBrief(
        Long id,
        Long skuId,
        Long productId,
        Long warehouseId,
        String status,
        BigDecimal qty,
        BigDecimal suggestedQty,
        BigDecimal purchasePrice,
        Long supplierId,
        String riskLevel,
        Integer version,
        LocalDateTime createdAt) {

    public static ReplenishmentItemBrief of(ReplenishmentItemEntity entity) {
        return new ReplenishmentItemBrief(
                entity.getId(),
                entity.getSkuId(),
                entity.getProductId(),
                entity.getWarehouseId(),
                entity.getStatus(),
                entity.getQty(),
                entity.getSuggestedQty(),
                entity.getPurchasePrice(),
                entity.getSupplierId(),
                entity.getRiskLevel(),
                entity.getVersion(),
                entity.getCreatedAt());
    }
}
