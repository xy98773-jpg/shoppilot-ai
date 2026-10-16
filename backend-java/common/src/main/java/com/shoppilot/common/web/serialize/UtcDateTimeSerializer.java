package com.shoppilot.common.web.serialize;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间字段的 JSON 序列化器：{@code LocalDateTime}（库内 UTC）→ ISO-8601 UTC 字面量
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}（契约 {@code API_CONTRACT §2.1} / {@code DESIGN_BASIS §14.1}）。
 *
 * <p>库内时间一律 {@code DATETIME(3)} 存 <b>UTC</b>（{@code DATABASE_DESIGN §3.2}），JDBC 连接
 * {@code connectionTimeZone=UTC}（见 {@code application.yml}）。{@code LocalDateTime} 无时区，
 * 故直接按 UTC 解释并追加字面量 {@code Z}，不依赖 JVM 默认时区——
 * 这样在任意宿主时区下输出都稳定，避免「同一毫秒在不同机器序列化出不同字符串」。
 */
public class UtcDateTimeSerializer extends JsonSerializer<LocalDateTime> {

    /** 毫秒精度 + 字面量 Z（UTC），与 {@code ApiResponse.timestamp} 同格式。 */
    private static final DateTimeFormatter ISO_UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(ISO_UTC_MILLIS.format(value));
    }
}
