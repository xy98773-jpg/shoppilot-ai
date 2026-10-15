package com.shoppilot.common.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 日期工具（基础设施）。
 *
 * <p>统一以 UTC 存储（DESIGN_BASIS：时间戳 DATETIME(3) 存 UTC；业务日期为 DATE）。<b>不含任何业务规则</b>。
 */
public final class DateUtils {

    public static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private DateUtils() {
    }

    public static Instant utcNow() {
        return Instant.now();
    }

    public static OffsetDateTime utcNowOffset() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public static String formatUtc(OffsetDateTime time) {
        return time.format(ISO_UTC);
    }
}
