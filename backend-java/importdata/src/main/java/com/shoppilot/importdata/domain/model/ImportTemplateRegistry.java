package com.shoppilot.importdata.domain.model;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>六类导入模板的唯一定义处</b>（{@code IMPORT_WORKFLOW.md §7.1–§7.7} 的代码化权威）。
 *
 * <h2>为什么集中在本类</h2>
 * §9.1 原文：「别名表在<b>单一模块维护</b>（{@code importdata} 的模板定义处），禁止各处散落；
 * 模板版本记录在 {@code import_batches.template_version}，用于诊断「为什么昨天能导入今天不行」」。
 * 因此 6 类模板的「字段清单 / 必填 / 类型 / 长度 / 别名 / 引用对象」<b>只在本类出现</b>，
 * 校验器（{@code ImportValidationService}）与自动映射都从这里取，杜绝两处口径漂移。
 *
 * <h2>自动映射（§9.1，按优先级短路）</h2>
 * <ol>
 *   <li><b>列名精确匹配</b>（大小写 / 全半角规范化后）→ 命中即用；</li>
 *   <li><b>别名匹配</b>（本类 {@link ImportFieldSpec#aliases()}）→ 命中即用；</li>
 *   <li>无法自动匹配 → 「待指定」，交用户手工指定。</li>
 * </ol>
 *
 * <p><b>模板版本号</b>：本类 {@code TEMPLATE_VERSION} 单一来源；解析成功且批次
 * {@code template_version} 为空时写入（不可变列，只补不覆盖，{@code DATABASE_DESIGN §5.12}）。
 */
public final class ImportTemplateRegistry {

    /** 模板版本号（写入 {@code import_batches.template_version}，诊断用）。 */
    public static final String TEMPLATE_VERSION = "v1";

    private static final Map<ImportBizType, List<ImportFieldSpec>> TEMPLATES = new EnumMap<>(ImportBizType.class);

    static {
        TEMPLATES.put(ImportBizType.PRODUCT, List.of(
                ImportFieldSpec.str("product_code", "商品编码", true, 64, List.of("商品编码", "商品编号", "编码")),
                ImportFieldSpec.str("name", "商品名称", true, 200, List.of("商品名称", "名称")),
                ImportFieldSpec.str("category", "类目", false, 64, List.of("类目", "分类")),
                ImportFieldSpec.str("brand", "品牌", false, 64, List.of("品牌")),
                ImportFieldSpec.str("unit", "单位", false, 16, List.of("单位")),
                ImportFieldSpec.ref("default_supplier_code", "默认供应商编码", false, 32,
                        List.of("默认供应商编码", "默认供应商"), ImportFieldSpec.ImportReference.SUPPLIER),
                ImportFieldSpec.str("spec_summary", "规格摘要", false, 255, List.of("规格摘要", "规格")),
                ImportFieldSpec.status("status", "状态", false, List.of("状态"))));

        TEMPLATES.put(ImportBizType.SKU, List.of(
                ImportFieldSpec.str("sku_code", "SKU 编码", true, 64,
                        List.of("SKU编码", "SKU", "sku", "商品规格编码", "规格编码")),
                ImportFieldSpec.ref("product_code", "所属商品编码", true, 64,
                        List.of("商品编码", "所属商品编码", "商品编号"), ImportFieldSpec.ImportReference.PRODUCT),
                ImportFieldSpec.str("name", "展示名", false, 200, List.of("展示名", "名称")),
                ImportFieldSpec.str("barcode", "条码", false, 64, List.of("条码", "条形码")),
                ImportFieldSpec.json("spec_json", "规格键值", false, List.of("规格键值", "规格JSON")),
                ImportFieldSpec.ref("supplier_code", "供应商编码", false, 32,
                        List.of("供应商编码", "供应商"), ImportFieldSpec.ImportReference.SUPPLIER),
                ImportFieldSpec.intNum("default_lead_time_days", "SKU 级交期", false, 0,
                        List.of("交期", "交期天数", "默认交期")),
                ImportFieldSpec.decimal3("moq", "最小起订量", false, 0, List.of("最小起订量", "起订量", "MOQ")),
                ImportFieldSpec.decimal3Gt("order_multiple", "采购倍数", false, 0, List.of("采购倍数", "倍数")),
                ImportFieldSpec.decimal3Gt("qty_step", "最小起订精度", false, 0, List.of("起订精度", "最小起订精度")),
                ImportFieldSpec.decimal4("purchase_price", "参考采购单价", false, 0, List.of("采购单价", "进价")),
                ImportFieldSpec.decimal4("sale_price", "参考售价", false, 0, List.of("售价", "参考售价")),
                ImportFieldSpec.date("listed_date", "上架日期", false, List.of("上架日期")),
                ImportFieldSpec.status("status", "状态", false, List.of("状态"))));

        TEMPLATES.put(ImportBizType.SUPPLIER, List.of(
                ImportFieldSpec.str("code", "供应商编码", true, 32, List.of("供应商编码", "供应商编号", "编码")),
                ImportFieldSpec.str("name", "供应商名称", true, 128, List.of("供应商名称", "名称")),
                ImportFieldSpec.str("contact_name", "联系人", false, 64, List.of("联系人")),
                ImportFieldSpec.str("contact_phone", "联系电话", false, 32, List.of("联系电话", "电话")),
                ImportFieldSpec.str("email", "邮箱", false, 128, List.of("邮箱", "电子邮箱", "Email")),
                ImportFieldSpec.str("address", "地址", false, 255, List.of("地址")),
                ImportFieldSpec.intNum("lead_time_days", "供应商级交期", false, 0, List.of("交期", "交期天数")),
                ImportFieldSpec.decimal3("moq", "供应商级 MOQ", false, 0, List.of("最小起订量", "起订量", "MOQ")),
                ImportFieldSpec.decimal3Gt("order_multiple", "供应商级倍数", false, 0, List.of("采购倍数", "倍数")),
                ImportFieldSpec.status("status", "状态", false, List.of("状态"))));

        TEMPLATES.put(ImportBizType.WAREHOUSE, List.of(
                ImportFieldSpec.str("code", "仓库编码", true, 32, List.of("仓库编码", "仓库编号", "编码")),
                ImportFieldSpec.str("name", "仓库名称", true, 128, List.of("仓库名称", "名称")),
                ImportFieldSpec.bool("is_default", "是否默认仓", false, List.of("是否默认仓", "默认仓", "是否默认")),
                ImportFieldSpec.str("address", "地址", false, 255, List.of("地址")),
                ImportFieldSpec.status("status", "状态", false, List.of("状态"))));

        TEMPLATES.put(ImportBizType.INVENTORY_SNAPSHOT, List.of(
                ImportFieldSpec.ref("sku_code", "SKU 编码", true, 64,
                        List.of("SKU编码", "SKU", "sku", "商品规格编码"), ImportFieldSpec.ImportReference.SKU),
                ImportFieldSpec.ref("warehouse_code", "仓库编码", true, 32,
                        List.of("仓库编码", "仓库"), ImportFieldSpec.ImportReference.WAREHOUSE),
                ImportFieldSpec.decimal3("qty", "快照数量", true, 0, List.of("快照数量", "数量", "库存数量")),
                ImportFieldSpec.date("snapshot_date", "快照日期", false, List.of("快照日期", "日期")),
                ImportFieldSpec.str("remark", "备注", false, 255, List.of("备注"))));

        TEMPLATES.put(ImportBizType.SALES_DAILY, List.of(
                ImportFieldSpec.ref("sku_code", "SKU 编码", true, 64,
                        List.of("SKU编码", "SKU", "sku", "商品规格编码"), ImportFieldSpec.ImportReference.SKU),
                ImportFieldSpec.ref("warehouse_code", "仓库编码", true, 32,
                        List.of("仓库编码", "仓库"), ImportFieldSpec.ImportReference.WAREHOUSE),
                ImportFieldSpec.date("sale_date", "销售日期", true, List.of("销售日期", "日期")),
                ImportFieldSpec.decimal3("qty", "净销量", true, 0, List.of("净销量", "销量", "销售数量")),
                ImportFieldSpec.decimal3("return_qty", "退货量", false, 0, List.of("退货量", "退货数量")),
                ImportFieldSpec.amount2("sales_amount", "销售金额", false, 0, List.of("销售金额", "金额")),
                ImportFieldSpec.intNum("order_count", "订单数", false, 0, List.of("订单数", "订单量"))));
    }

    private ImportTemplateRegistry() {
    }

    /** 取指定业务类型的模板字段清单（不可变，顺序即模板列顺序）。 */
    public static List<ImportFieldSpec> fieldsOf(ImportBizType bizType) {
        return TEMPLATES.get(bizType);
    }

    /** 按字段名取规格；字段不在模板内返回 {@code null}（供 {@code IMP-4008} 判定）。 */
    public static ImportFieldSpec specOf(ImportBizType bizType, String fieldName) {
        for (ImportFieldSpec spec : TEMPLATES.get(bizType)) {
            if (spec.name().equals(fieldName)) {
                return spec;
            }
        }
        return null;
    }

    /** 模板全部字段名（mapping 的键白名单）。 */
    public static Set<String> fieldNamesOf(ImportBizType bizType) {
        Set<String> names = new java.util.LinkedHashSet<>();
        TEMPLATES.get(bizType).forEach(spec -> names.add(spec.name()));
        return names;
    }

    /** 必填字段名集合（§9.4 未映射即拒绝）。 */
    public static List<ImportFieldSpec> requiredFieldsOf(ImportBizType bizType) {
        return TEMPLATES.get(bizType).stream().filter(ImportFieldSpec::required).toList();
    }

    /**
     * <b>自动映射建议</b>（§9.1 优先级短路：精确匹配 → 别名匹配 → 待指定）。
     *
     * <p>键 = 目标字段名，值 = 源列名。无法自动匹配的字段<b>不出现在结果里</b>
     * （即「待指定」，由 {@code PUT /mapping} 时按 {@code IMP-4003} 拒绝——必填未被映射）。
     *
     * <p>规范化：{@link #normalize(String)}（去空白 / 大小写 / 全角转半角），与 §9.1 一致。
     *
     * @param bizType 业务类型
     * @param header  解析出的源列头
     * @return 自动映射建议（可能为空 {@code Map}）
     */
    public static Map<String, String> autoMapping(ImportBizType bizType, List<String> header) {
        Map<String, String> result = new LinkedHashMap<>();
        if (header == null || header.isEmpty()) {
            return result;
        }
        // 第 1 优先级：列名精确匹配（规范化后）
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String column : header) {
            normalized.putIfAbsent(normalize(column), column);
        }
        for (ImportFieldSpec spec : TEMPLATES.get(bizType)) {
            String exact = normalized.get(normalize(spec.name()));
            if (exact != null) {
                result.put(spec.name(), exact);
            }
        }
        // 第 2 优先级：别名匹配（已精确命中的字段不再参与，保持"短路"语义）
        Map<String, String> aliasIndex = new LinkedHashMap<>();
        for (ImportFieldSpec spec : TEMPLATES.get(bizType)) {
            if (result.containsKey(spec.name())) {
                continue;
            }
            for (String alias : spec.aliases()) {
                aliasIndex.putIfAbsent(normalize(alias), spec.name());
            }
        }
        for (Map.Entry<String, String> column : normalized.entrySet()) {
            String target = aliasIndex.get(column.getKey());
            if (target != null) {
                result.putIfAbsent(target, column.getValue());
            }
        }
        return result;
    }

    /**
     * 列名规范化（§9.1 第 1 条原文：「大小写 / 全半角规范化后」）。
     *
     * <p>依次：去首尾空白 → 全角转半角 → 内部空白折叠为单个空格 → 转小写。
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            // 全角空格 U+3000 → 半角
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                // 全角 ! 到 ~ → 半角（差值固定 0xFEE0）
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }
}
