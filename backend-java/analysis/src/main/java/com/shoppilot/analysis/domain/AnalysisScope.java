package com.shoppilot.analysis.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分析范围值对象（{@code analysis_runs.scope_type + scope_json}）。
 *
 * <p>权威依据：
 * <ul>
 *   <li>取值 —— {@code DESIGN_BASIS.md §9.16}（{@code ALL_ACTIVE} / {@code SELECTED_SKUS} / {@code CATEGORY}）；</li>
 *   <li>明细结构 —— {@code ANALYSIS_CONTRACT.md §1.1}（{@code scope.sku_ids} 长度 1..2000；
 *       {@code scope.category} 字符串）；</li>
 *   <li>SKU 上限 2000 —— {@code DESIGN_BASIS.md §10.6}（{@code MAX_ANALYSIS_SKUS}），
 *       超限 {@code ANL-7007}（{@code ERROR_CODES.md §2.8}）；</li>
 *   <li>并发交集口径 —— 任务书 §三.4：<b>同 scope_type</b> 才判交集（{@code ALL_ACTIVE} 任一进行中即冲突；
 *       {@code SELECTED_SKUS} 集合有交集；{@code CATEGORY} 同类目）。</li>
 * </ul>
 *
 * <p><b>为何域值对象直接抛 {@link BizException}</b>：本仓 {@code common} 为各模块共享叶模块
 * （{@code MODULE_BOUNDARIES §2} F-40 豁免），{@code BizException} 是唯一对外错误出口；
 * 把「从请求到合法值」的构造规则收拢在值对象自身，比散在服务层更好维护（错误码仍只取自
 * {@link ErrorCodes}，权威定义在 {@code ERROR_CODES.md §2}）。
 *
 * @param scopeType 范围类型（不可变）
 * @param skuIds    {@code SELECTED_SKUS} 的 SKU 列表（保序；其余类型为 {@code null}）
 * @param category  {@code CATEGORY} 的类目名（其余类型为 {@code null}）
 */
public record AnalysisScope(AnalysisScopeType scopeType, List<Long> skuIds, String category) {

    /** SKU 数量上限（{@code DESIGN_BASIS.md §10.6}：{@code MAX_ANALYSIS_SKUS}）。 */
    public static final int MAX_SKUS = 2000;

    /** 仅用于构造 {@code scope_json} 的节点树（不参与结构比较，比较走 {@link CanonicalJson}）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 从外部请求构造合法范围。
     *
     * @param type      已解析的范围类型（{@code null} 由调用方先判 {@code COM-0001}）
     * @param scopeJson 客户端传来的 {@code scope_json}（可空；按类型要求补全）
     * @throws BizException {@code COM-0001}（结构缺失/类型错）或 {@code ANL-7007}（SKU 超 2000）
     */
    public static AnalysisScope resolve(AnalysisScopeType type, JsonNode scopeJson) {
        return switch (type) {
            case ALL_ACTIVE -> new AnalysisScope(type, null, null);
            case SELECTED_SKUS -> new AnalysisScope(type, resolveSkuIds(scopeJson), null);
            case CATEGORY -> new AnalysisScope(type, null, resolveCategory(scopeJson));
        };
    }

    /**
     * 与另一范围是否有交集（并发保护 {@code ANL-7001} 的范围判定）。
     *
     * <p>口径（任务书 §三.4）：<b>scope_type 不同一律不冲突</b>（含 {@code ALL_ACTIVE} 与
     * {@code SELECTED_SKUS} 之间——同型才判交集是明文的批次口径）；
     * {@code ALL_ACTIVE} 任一进行中即冲突；{@code SELECTED_SKUS} 集合相交；
     * {@code CATEGORY} 同类目。
     *
     * @param other 待比较范围（进行中 run 解析出的范围）
     */
    public boolean overlaps(AnalysisScope other) {
        if (other == null || other.scopeType != this.scopeType) {
            return false;
        }
        return switch (this.scopeType) {
            case ALL_ACTIVE -> true;
            case SELECTED_SKUS -> !Collections.disjoint(this.skuIds, other.skuIds);
            case CATEGORY -> this.category.equals(other.category);
        };
    }

    /**
     * 入库用 {@code scope_json} 规范化文本（{@code ALL_ACTIVE} 返回 {@code null}——无明细可存）。
     *
     * <p>键序固定（单键无排序问题）；数组保序（客户端原序，重复项<b>不去重</b>——输入即事实，
     * 去重会改变幂等体比对语义）。
     *
     * <p><b>★ R109（B15.1）：必须经 JSON 序列化，禁止字符串拼接。</b>
     * {@code CATEGORY} 的类目名是用户输入（契约允许任意 1..128 字符串），含 {@code "} 或 {@code \} 时
     * 手工拼接会产出非法 JSON，落 MySQL {@code JSON} 列直接 3140 → 500 {@code COM-0002}。
     * 这里先构造 {@code JsonNode} 再交给 {@link CanonicalJson#canonicalize}，
     * 与幂等比对基准<b>同源</b>（同一套转义与数值归一），不会出现「落库文本 ≠ 比对基准」。
     */
    public String canonicalJson() {
        return switch (scopeType) {
            case ALL_ACTIVE -> null;
            case SELECTED_SKUS -> {
                ArrayNode ids = JSON.createArrayNode();
                skuIds.forEach(ids::add);
                ObjectNode node = JSON.createObjectNode();
                node.set("sku_ids", ids);
                yield CanonicalJson.canonicalize(node);
            }
            case CATEGORY -> {
                ObjectNode node = JSON.createObjectNode();
                node.put("category", category);
                yield CanonicalJson.canonicalize(node);
            }
        };
    }

    /**
     * 解析进行中 run 入库的 {@code scope_json}（并发判定用）。
     *
     * @return 合法范围；入参为 {@code null}/非法/结构不符时返回 {@code null}（调用方按「无交集」跳过，
     *         不因历史脏数据阻断新任务创建）
     */
    public static AnalysisScope parse(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        try {
            if (node.has("sku_ids")) {
                List<Long> ids = new ArrayList<>();
                node.get("sku_ids").forEach(e -> ids.add(e.longValue()));
                return new AnalysisScope(AnalysisScopeType.SELECTED_SKUS, ids, null);
            }
            if (node.has("category")) {
                return new AnalysisScope(AnalysisScopeType.CATEGORY, null,
                        node.get("category").asText());
            }
            return new AnalysisScope(AnalysisScopeType.ALL_ACTIVE, null, null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** SKU 计划数（响应 {@code total_skus}；非 {@code SELECTED_SKUS} 为 {@code 0}——数量由执行端回填）。 */
    public int plannedSkuCount() {
        return scopeType == AnalysisScopeType.SELECTED_SKUS ? skuIds.size() : 0;
    }

    private static List<Long> resolveSkuIds(JsonNode scopeJson) {
        JsonNode ids = scopeJson == null ? null : scopeJson.get("sku_ids");
        if (ids == null || !ids.isArray() || ids.isEmpty()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("scope_json.sku_ids",
                            "SELECTED_SKUS 范围必须提供非空 sku_ids 数组")));
        }
        List<Long> out = new ArrayList<>(ids.size());
        for (JsonNode e : ids) {
            if (!e.isIntegralNumber() || e.longValue() <= 0) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("scope_json.sku_ids", "sku_ids 必须为正整数")));
            }
            out.add(e.longValue());
        }
        // 规模按「数组原长」判定（ANALYSIS_CONTRACT §1.1：长度 1..2000），不去重
        if (out.size() > MAX_SKUS) {
            throw new BizException(ErrorCodes.ANL_7007, "单次分析的 SKU 数量过多，请缩小范围");
        }
        return Collections.unmodifiableList(out);
    }

    private static String resolveCategory(JsonNode scopeJson) {
        JsonNode cat = scopeJson == null ? null : scopeJson.get("category");
        if (cat == null || !cat.isTextual() || cat.asText().isBlank()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("scope_json.category",
                            "CATEGORY 范围必须提供非空 category 字符串")));
        }
        String value = cat.asText().trim();
        if (value.length() > 128) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("scope_json.category", "category 长度不能超过 128")));
        }
        return value;
    }
}
