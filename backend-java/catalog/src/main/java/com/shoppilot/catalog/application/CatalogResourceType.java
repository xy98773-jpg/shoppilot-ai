package com.shoppilot.catalog.application;

/**
 * catalog 四类主数据资源标识——用于收敛启停操作的 {@code resourceType} 入参。
 *
 * <p>{@code MODULE_BOUNDARIES §2.3} 规定 {@code CatalogWriteService.changeStatus(resourceType, id, status)}，
 * 由本枚举区分 {@code warehouses} / {@code suppliers} / {@code products} / {@code skus} 四张表，
 * 使 4 个 DELETE（软删）与 2 个 {@code PATCH .../status} 共用<b>同一条</b>状态流转路径。
 */
public enum CatalogResourceType {

    /** {@code warehouses}。 */
    WAREHOUSE,
    /** {@code suppliers}。 */
    SUPPLIER,
    /** {@code products}。 */
    PRODUCT,
    /** {@code skus}。 */
    SKU
}
