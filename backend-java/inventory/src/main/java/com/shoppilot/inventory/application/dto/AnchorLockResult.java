package com.shoppilot.inventory.application.dto;

import java.util.List;

/**
 * 同口径锚点行锁的结果（{@code F-03}，供 {@code purchasing} 在「建议转采购单」时串行化同 SKU×仓库的并发）。
 *
 * <p>语义：{@code lockedSkuIds} 是<b>真实存在余额行并被本次锁住</b>的 SKU（升序）；
 * {@code missingSkuIds} 是<b>尚无余额行、因而无行可锁</b>的 SKU。
 *
 * <p>★ {@code missingSkuIds} 非空时，调用方（{@code purchasing}）应按
 * {@code DATABASE_DESIGN §7.2} #8 ②b 降级为
 * {@code replenishment.ReplenishmentAppService.lockApprovedAnchors} 取锁；本服务<b>不</b>代其决定。
 *
 * @param lockedSkuIds  已锁定的 SKU（升序）
 * @param missingSkuIds 无余额行的 SKU（升序）
 */
public record AnchorLockResult(List<Long> lockedSkuIds, List<Long> missingSkuIds) {
}
