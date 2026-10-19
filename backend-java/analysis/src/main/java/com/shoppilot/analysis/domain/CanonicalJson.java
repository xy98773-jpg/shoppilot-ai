package com.shoppilot.analysis.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * JSON 规范化（幂等体一致性比对的底座）。
 *
 * <p>契约依据 {@code API_CONTRACT.md §6.3}：「对请求体做规范化（字段排序、去除空白、统一数值精度）
 * 后计算哈希……与首次存储的哈希比较」。本类即该规范化的实现：
 * <ul>
 *   <li><b>字段排序</b>：对象键按字典序重排（与 MySQL JSON 列无关，两侧同函数保证一致）；</li>
 *   <li><b>去除空白</b>：重新序列化，不保留任何源码空白；</li>
 *   <li><b>统一数值精度</b>：数值一律 {@code BigDecimal.stripTrailingZeros().toPlainString()}
 *       —— {@code 28} / {@code 28.0} / {@code 28.00} 归一为 {@code "28"}，{@code 0.5000} 归一为 {@code "0.5"}；</li>
 *   <li><b>数组保序</b>：JSON 数组顺序是语义的一部分（如 {@code sku_ids}），<b>不</b>重排。</li>
 * </ul>
 *
 * <p>用途：createRun 幂等「同键异体 → {@code COM-0009}」判定（任务书 §三.3 比对范围：
 * {@code scope_type / scope_json / warehouse_id / params_json / algorithm_version}）。
 *
 * <p>线程安全：无状态静态方法；内部 {@link ObjectMapper} 只做字符串转义（不参与结构比较）。
 */
public final class CanonicalJson {

    private static final ObjectMapper ESCAPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private CanonicalJson() {
    }

    /**
     * 规范化序列化。{@code null} 入参返回 {@code null}（调用方据此判「两侧均无值」）。
     *
     * @param node 任意 JSON 节点（对象/数组/标量）
     * @return 规范化字符串；入参为 {@code null} 时返回 {@code null}
     */
    public static String canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return write(node);
    }

    /** 解析已入库的 JSON 文本；非法 JSON 返回 {@code null}（比对时按「无值」处理，不抛异常）。 */
    public static JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return ESCAPER.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 两侧规范化后比较（含 {@code null} 语义：双 {@code null} 相等，单侧 {@code null} 不等）。 */
    public static boolean jsonEquals(JsonNode a, JsonNode b) {
        String ca = canonicalize(a);
        String cb = canonicalize(b);
        if (ca == null || cb == null) {
            return ca == null && cb == null;
        }
        return ca.equals(cb);
    }

    // =====================================================================
    // 内部：递归规范化写出（自实现，避免依赖 ObjectMapper 的序列化配置差异）
    // =====================================================================

    private static String write(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            List<String> parts = new ArrayList<>(sorted.size());
            sorted.forEach((k, v) -> parts.add(quote(k) + ":" + write(v)));
            return "{" + String.join(",", parts) + "}";
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>(node.size());
            node.forEach(e -> parts.add(write(e)));
            return "[" + String.join(",", parts) + "]";
        }
        if (node.isTextual()) {
            return quote(node.asText());
        }
        if (node.isNumber()) {
            return normalizeNumber(node);
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? "true" : "false";
        }
        return "null";
    }

    /** 数值归一：去尾零 + 普通十进制（禁科学计数法），{@code -0} 归 {@code 0}。 */
    private static String normalizeNumber(JsonNode node) {
        BigDecimal bd = node.decimalValue().stripTrailingZeros();
        if (bd.signum() == 0) {
            bd = BigDecimal.ZERO;
        }
        return bd.toPlainString();
    }

    /** 字符串转义（借 Jackson，保证与 {@code "}、{@code \}、控制字符的转义一致）。 */
    private static String quote(String raw) {
        try {
            return ESCAPER.writeValueAsString(new TextNode(raw));
        } catch (Exception ex) {
            // TextNode 序列化不存在失败路径；真失败即编程错误，直接抛出
            throw new IllegalStateException("JSON 字符串转义失败", ex);
        }
    }
}
