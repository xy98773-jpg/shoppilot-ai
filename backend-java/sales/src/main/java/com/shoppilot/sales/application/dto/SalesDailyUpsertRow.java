package com.shoppilot.sales.application.dto;

/**
 * 日销售批量 upsert 的单行入参（{@code SalesWriteService.batchUpsert}）。
 *
 * <p>{@code source} 由调用方显式给出：手工端点 = {@code MANUAL}；导入落库 = {@code IMPORT}。
 * {@code teamId} / {@code productId} <b>不在此处</b>——团队来自 {@code TeamContext}（服务端事实），
 * {@code product_id} 由服务端从 {@code skus.product_id} 派生（保证「冗余列永远正确」，{@code §5.11}）。
 *
 * @param idempotencyKey 幂等键（写审计 / 调用方追溯用；sales_daily 本身无此列，覆盖语义由业务键决定）
 */
public record SalesDailyUpsertRow(Long skuId,
                                  Long warehouseId,
                                  java.time.LocalDate saleDate,
                                  java.math.BigDecimal qty,
                                  java.math.BigDecimal returnQty,
                                  java.math.BigDecimal salesAmount,
                                  Integer orderCount,
                                  String source,
                                  Long batchId,
                                  String idempotencyKey) {
}
