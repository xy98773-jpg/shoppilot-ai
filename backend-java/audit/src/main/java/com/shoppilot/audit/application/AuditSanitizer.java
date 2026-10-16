package com.shoppilot.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计脱敏器（{@code DATABASE_DESIGN.md §6-J.3} 的<b>统一脱敏实现</b>，硬要求）。
 *
 * <h2>双重过滤</h2>
 * <ol>
 *   <li><b>字段名黑名单</b>（大小写不敏感、归一化后含子串匹配）：{@code password}、{@code password_hash}、
 *       {@code token}、{@code secret}、{@code api_key}、{@code authorization}
 *       ——命中字段值替换为 {@value #MASK}，<b>绝不写入口令 / 令牌 / 密钥</b>；</li>
 *   <li><b>正则掩码</b>：手机号 {@code 138****8000}（留前 3 后 4）、邮箱 {@code a***@b.com}（留首字母与域名）。</li>
 * </ol>
 *
 * <p><b>黑名单在 audit 模块集中维护</b>（§6-J.3）：业务模块只传业务对象，
 * 由本类统一序列化 + 脱敏，<b>不得</b>自行拼装 {@code before_json}/{@code after_json}。
 *
 * <p>另处理（§5.22 更新规则）：
 * <ul>
 *   <li>{@code request_uri} <b>必须剥离查询串</b>（防 {@code ?token=...} 泄漏）；</li>
 *   <li>{@code user_agent} 截断至 255、{@code change_summary} 截断至 1000。</li>
 * </ul>
 */
public final class AuditSanitizer {

    private static final Logger log = LoggerFactory.getLogger(AuditSanitizer.class);

    /** 黑名单命中字段的替换值。 */
    public static final String MASK = "***";

    /** 邮箱本地位掩码后的固定形态（{@code a***@b.com}）。 */
    private static final String EMAIL_MASK = "$1***$2";

    /** 手机号掩码后的固定形态（{@code 138****8000}）。 */
    private static final String PHONE_MASK = "$1****$2";

    /**
     * 字段名黑名单（归一化后匹配：转小写并去掉非字母数字字符，
     * 故 {@code api_key} → {@code apikey}、{@code X-Auth-Token} → {@code xauthtoken} 均可命中）。
     */
    private static final List<String> BLACKLIST = List.of(
            "password", "token", "secret", "apikey", "authorization");

    /** 中国大陆手机号（11 位，1[3-9] 开头）；前后不得再邻接数字，避免误切长数字串。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    /** 邮箱（本地位留首字符，域名完整保留）。 */
    private static final Pattern EMAIL =
            Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditSanitizer() {
    }

    /**
     * 脱敏 {@code before} / {@code after} 两个业务对象，返回可入库的 JSON 文本对。
     *
     * @param before 变更前业务对象（可为 {@code null}）
     * @param after  变更后业务对象（可为 {@code null}）
     * @return 脱敏后的 JSON 字符串对（对象为 {@code null} 或序列化失败时对应分量为 {@code null}）
     */
    public static Sanitized sanitize(Object before, Object after) {
        return new Sanitized(sanitizeToJson(before), sanitizeToJson(after));
    }

    /**
     * 序列化 + 脱敏单个业务对象为 JSON 文本。
     *
     * <p>流程：Jackson 序列化为树 → 递归走查（黑名单字段置掩码 / 文本值做手机号与邮箱掩码）
     * → 再序列化为紧凑 JSON 文本。
     */
    public static String sanitizeToJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = value instanceof JsonNode node
                    ? node
                    : MAPPER.valueToTree(value);
            if (tree.isNull()) {
                return null;
            }
            return MAPPER.writeValueAsString(sanitizeTree(tree));
        } catch (JsonProcessingException ex) {
            // 脱敏是安全边界：序列化失败宁可不写该字段，也绝不能写入未脱敏原文
            log.warn("审计脱敏：对象序列化失败，before/after 对应字段置空 valueType={}", value.getClass().getName());
            return null;
        }
    }

    /**
     * 脱敏自由文本（{@code change_summary} 等）：手机号 / 邮箱掩码。
     *
     * <p>业务编号（{@code biz_no}）等<b>不</b>经过本方法——编号是业务键快照，
     * 掩码会破坏其可追溯性；黑名单与正则已覆盖其余文本入口。
     */
    public static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = PHONE.matcher(text).replaceAll(PHONE_MASK);
        return EMAIL.matcher(masked).replaceAll(EMAIL_MASK);
    }

    /**
     * 剥离请求 URI 的查询串与片段（防 {@code ?token=...} 泄漏；{@code §5.22} 更新规则）。
     *
     * @return 仅路径部分；{@code uri} 为 {@code null} 时原样返回
     */
    public static String stripQuery(String uri) {
        if (uri == null) {
            return null;
        }
        int cut = uri.indexOf('?');
        if (cut < 0) {
            cut = uri.indexOf('#');
        }
        return cut >= 0 ? uri.substring(0, cut) : uri;
    }

    /** 截断至 {@code max} 个字符（{@code null} 原样返回；不足则原样返回）。 */
    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** 字段名是否命中黑名单（大小写不敏感、归一化后含子串匹配）。 */
    public static boolean isBlacklisted(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return BLACKLIST.stream().anyMatch(normalized::contains);
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    private static JsonNode sanitizeTree(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            // 先收集再改：遍历中修改字段会打乱字段迭代器
            List<String> names = new java.util.ArrayList<>(object.size());
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                if (isBlacklisted(name)) {
                    object.put(name, MASK);
                } else {
                    object.set(name, sanitizeTree(value));
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, sanitizeTree(array.get(i)));
            }
            return array;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeText(node.asText()));
        }
        return node;
    }

    /** 脱敏后的 JSON 文本对（供 {@code before_json} / {@code after_json} 落库）。 */
    public record Sanitized(String beforeJson, String afterJson) {
    }
}
