package com.shoppilot.importdata.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 导入模板的<b>单个目标字段</b>规格（{@code IMPORT_WORKFLOW.md §7.1–§7.6} 表格一行的代码化）。
 *
 * <p><b>字段名严格等于 {@code DATA_DICTIONARY.md} 的字段名</b>（§9.2 原文：mapping JSON 的
 * 键 = 目标字段名，与数据字典严格一致），供 {@code field_mapping_json} 的键合法性校验使用。
 *
 * <p><b>别名表集中维护</b>：{@link #aliases()} 只在 {@link ImportTemplateRegistry} 的模板定义处填写，
 * 禁止散落到其它模块（§9.1 原文：「别名表在单一模块维护（importdata 的模板定义处），禁止各处散落」）。
 *
 * @param name          目标字段名（snake_case，mapping JSON 的键）
 * @param label         中文名（用于 {@code IMP-4003} 的人类可读提示）
 * @param required      是否必填（§9.4：必填列未映射 → {@code PUT /mapping} 直接拒绝）
 * @param type          值类型（决定 §10.1「类型 / 范围 / 精度」三档校验）
 * @param maxLength     字符串最大长度（§10.1 长度行）；非字符串忽略
 * @param min           数值下界；{@code null} 表示不校验下界
 * @param max           数值上界；{@code null} 表示不校验上界
 * @param exclusiveMin  下界是否<b>严格</b>（{@code > min} 而非 {@code >= min}）；
 *                      用于 §7.2/§7.3 的 {@code order_multiple > 0}、{@code qty_step > 0}
 * @param allowedValues 枚举白名单（{@link ImportFieldType#ENUM} 时必填）
 * @param aliases       自动映射别名（§9.1 第 2 优先级）
 * @param reference     引用对象类型：用于 §10.2「必须已存在且同团队」；{@code null} 表示无引用
 */
public record ImportFieldSpec(
        String name,
        String label,
        boolean required,
        ImportFieldType type,
        Integer maxLength,
        BigDecimal min,
        BigDecimal max,
        boolean exclusiveMin,
        Set<String> allowedValues,
        List<String> aliases,
        ImportReference reference) {

    /** 引用对象类型（§10.2 各业务类型特有校验的「必须已存在 + 同团队」）。 */
    public enum ImportReference {
        /** 商品编码 → {@code products.product_code}（不存在 {@code CAT-3001} / 跨团队 {@code CAT-3005}）。 */
        PRODUCT,
        /** SKU 编码 → {@code skus.sku_code}（不存在 {@code CAT-3001} / 跨团队 {@code CAT-3005}）。 */
        SKU,
        /** 供应商编码 → {@code suppliers.code}（不存在 {@code CAT-3001} / 跨团队 {@code CAT-3005}）。 */
        SUPPLIER,
        /** 仓库编码 → {@code warehouses.code}（不存在 {@code CAT-3003} / 跨团队 {@code CAT-3005}）。 */
        WAREHOUSE
    }

    // ================= 构造辅助（保持模板定义处可读） =================

    static ImportFieldSpec str(String name, String label, boolean required, int maxLength,
                               List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.STRING, maxLength,
                null, null, false, null, aliases, null);
    }

    static ImportFieldSpec ref(String name, String label, boolean required, int maxLength,
                               List<String> aliases, ImportReference reference) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.STRING, maxLength,
                null, null, false, null, aliases, reference);
    }

    static ImportFieldSpec intNum(String name, String label, boolean required, int min,
                                  List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.INT, null,
                bd(min), null, false, null, aliases, null);
    }

    static ImportFieldSpec decimal3(String name, String label, boolean required, int min,
                                    List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.DECIMAL_3, null,
                bd(min), null, false, null, aliases, null);
    }

    /** 严格大于下界（§7.2 的 {@code order_multiple > 0} / {@code qty_step > 0}）。 */
    static ImportFieldSpec decimal3Gt(String name, String label, boolean required, int min,
                                      List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.DECIMAL_3, null,
                bd(min), null, true, null, aliases, null);
    }

    static ImportFieldSpec decimal4(String name, String label, boolean required, int min,
                                    List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.DECIMAL_4, null,
                bd(min), null, false, null, aliases, null);
    }

    static ImportFieldSpec amount2(String name, String label, boolean required, int min,
                                   List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.AMOUNT_2, null,
                bd(min), null, false, null, aliases, null);
    }

    static ImportFieldSpec date(String name, String label, boolean required, List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.DATE, null,
                null, null, false, null, aliases, null);
    }

    static ImportFieldSpec bool(String name, String label, boolean required, List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.BOOL, null,
                null, null, false, null, aliases, null);
    }

    static ImportFieldSpec json(String name, String label, boolean required, List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.JSON, null,
                null, null, false, null, aliases, null);
    }

    /** {@code MasterDataStatus}（{@code ACTIVE} / {@code INACTIVE}，缺省 {@code ACTIVE}）。 */
    static ImportFieldSpec status(String name, String label, boolean required, List<String> aliases) {
        return new ImportFieldSpec(name, label, required, ImportFieldType.ENUM, null,
                null, null, false, Set.of("ACTIVE", "INACTIVE"), aliases, null);
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }
}
