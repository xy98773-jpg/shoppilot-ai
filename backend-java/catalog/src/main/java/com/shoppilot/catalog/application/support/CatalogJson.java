package com.shoppilot.catalog.application.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * catalog 写请求的 JSON 字段读取工具（{@code JsonNode} → 强类型）。
 *
 * <h2>为何用 {@code JsonNode} 而非强类型 DTO</h2>
 * PATCH 要求<b>真·部分更新</b>（{@code 任务书 §6}：只把「传来的字段」写进 SET，绝不整行覆盖 / null 掉），
 * 这需要区分「字段缺席」与「字段显式传 null」。强类型 DTO 无法区分二者，故统一以 {@link JsonNode}
 * 承载请求体：{@link #has} 判「传来且非 null」，{@link #hasKey} 判「传来」（含显式 null）。
 *
 * <p>所有「格式非法」的读取（如 {@code moq} 传了非数字）统一归 {@code 400 COM-0001}（携带 {@code details[]}）。
 * 数值可接受 JSON number 或字符串（契约 §11.2 示例中 {@code moq} 即字符串 {@code "50.000"}）。
 */
public final class CatalogJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CatalogJson() {
    }

    /** 字段「已传入且非 JSON null」（用于 PATCH：命中才写 SET）。 */
    public static boolean has(JsonNode body, String field) {
        return body != null && body.has(field) && !body.get(field).isNull();
    }

    /** 字段「已传入」（含显式 JSON null；用于 nullable 字段的「显式清空」语义）。 */
    public static boolean hasKey(JsonNode body, String field) {
        return body != null && body.has(field);
    }

    /** 取字符串；缺席或 null → {@code null}；数字/布尔按文本形式返回。 */
    public static String text(JsonNode body, String field) {
        if (!has(body, field)) {
            return null;
        }
        return body.get(field).asText();
    }

    /** 取字符串（含显式 null → {@code null}）。 */
    public static String textOrNull(JsonNode body, String field) {
        return hasKey(body, field) ? (body.get(field).isNull() ? null : body.get(field).asText()) : null;
    }

    public static Long longVal(JsonNode body, String field) {
        if (!has(body, field)) {
            return null;
        }
        return parseLong(body, field, body.get(field));
    }

    public static Integer intVal(JsonNode body, String field) {
        if (!has(body, field)) {
            return null;
        }
        return parseInt(body, field, body.get(field));
    }

    public static BigDecimal decimal(JsonNode body, String field) {
        if (!has(body, field)) {
            return null;
        }
        JsonNode node = body.get(field);
        try {
            return new BigDecimal(node.asText().trim());
        } catch (NumberFormatException e) {
            throw invalid(field, "必须为数字", node.asText());
        }
    }

    public static Boolean boolVal(JsonNode body, String field) {
        if (!has(body, field)) {
            return null;
        }
        JsonNode node = body.get(field);
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String s = node.asText().trim();
        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        throw invalid(field, "必须为布尔值", s);
    }

    public static LocalDate dateVal(JsonNode body, String field) {
        if (!has(body, field)) {
            return null;
        }
        JsonNode node = body.get(field);
        try {
            return LocalDate.parse(node.asText().trim());
        } catch (RuntimeException e) {
            throw invalid(field, "必须为日期（yyyy-MM-dd）", node.asText());
        }
    }

    /** 取嵌套 JSON 的文本形式（如 {@code spec_json}）；缺席/null → {@code null}。写入 JSON 列。 */
    public static String jsonText(JsonNode body, String field) {
        if (!has(body, field)) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(body.get(field));
        } catch (Exception e) {
            throw invalid(field, "不是合法的 JSON", body.get(field).toString());
        }
    }

    public static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 构造单字段 {@code COM-0001} 异常。 */
    public static BizException invalid(String field, String message, Object rejected) {
        return BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                List.of(new ApiErrorDetail(field, message, rejected)));
    }

    private static Long parseLong(JsonNode body, String field, JsonNode node) {
        try {
            return node.isNumber() ? node.longValue() : Long.valueOf(node.asText().trim());
        } catch (NumberFormatException e) {
            throw invalid(field, "必须为整数", node.asText());
        }
    }

    private static Integer parseInt(JsonNode body, String field, JsonNode node) {
        try {
            return node.isNumber() ? node.intValue() : Integer.valueOf(node.asText().trim());
        } catch (NumberFormatException e) {
            throw invalid(field, "必须为整数", node.asText());
        }
    }
}
