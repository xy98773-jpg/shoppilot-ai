package com.shoppilot.importdata.domain.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * 导入业务类型（{@code import_batches.biz_type}，权威见 {@code DESIGN_BASIS.md §9.15}）。
 *
 * <p><b>6 值</b>，与 {@code V1.0.5__importdata.sql} 的 {@code ck_imp_biz_type} 一一对应；
 * 各类型的字段清单与校验规则见 {@code IMPORT_WORKFLOW.md §7.1–§7.7}。
 *
 * <p>本批（M3-T02）只用其做<b>取值合法性判定</b>与落库；各类型的解析/映射/校验属 M3-T03 起。
 */
public enum ImportBizType {

    /** 商品（落库目标 {@code products}）。 */
    PRODUCT,
    /** SKU（落库目标 {@code skus}）。 */
    SKU,
    /** 供应商（落库目标 {@code suppliers}）。 */
    SUPPLIER,
    /** 仓库（落库目标 {@code warehouses}）。 */
    WAREHOUSE,
    /** 库存快照（落库目标 {@code inventory_balances}，M3-T08）。 */
    INVENTORY_SNAPSHOT,
    /** 日销售（落库目标 {@code sales_daily}，M3-T12）。 */
    SALES_DAILY;

    /**
     * 解析文本；不在 6 值内返回 {@code null}（由调用方决定错误出口）。
     *
     * <p>大小写不敏感（契约示例为大写，前端可能传小写）；空白与 {@code null} 均视为非法。
     *
     * @param text 原始取值
     * @return 对应枚举；非法返回 {@code null}
     */
    public static ImportBizType parseOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
