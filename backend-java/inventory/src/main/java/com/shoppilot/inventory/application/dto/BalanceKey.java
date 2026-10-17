package com.shoppilot.inventory.application.dto;

/**
 * 库存余额行的业务键 {@code (sku_id, warehouse_id)}（M3-B10.5 / R78）。
 *
 * <h2>用途</h2>
 * {@code importdata} 在 {@code VALIDATED} 阶段要对 {@code INVENTORY_SNAPSHOT} 的每一行判定
 * 「目标 {@code (wh, sku)} 是否已有余额行」以产生 {@code IMP-4006}（{@code WARNING}，
 * {@code IMPORT_WORKFLOW §7.5} / §8.5 汇总表）。逐行 {@code hasBalance} 是 N+1，
 * 故以本记录作为<b>批量</b>存在性查询的入参/出参元素。
 *
 * <p>{@code MODULE_BOUNDARIES §2.4}：{@code importdata} 只能经本模块的 application 服务
 * 与本包的 DTO 交互，<b>不得</b>注入 {@code InventoryBalanceMapper}。
 *
 * @param skuId       SKU ID
 * @param warehouseId 仓库 ID
 */
public record BalanceKey(long skuId, long warehouseId) {

    /** 语义化工厂（与 {@code new} 等价，便于调用处可读）。 */
    public static BalanceKey of(long skuId, long warehouseId) {
        return new BalanceKey(skuId, warehouseId);
    }
}
