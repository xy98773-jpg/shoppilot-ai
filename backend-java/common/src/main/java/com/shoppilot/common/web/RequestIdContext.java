package com.shoppilot.common.web;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 链路追踪 ID 的请求级上下文（基础设施，ThreadLocal）。
 *
 * <p>契约依据：{@code API_CONTRACT.md §7}——{@code X-Request-Id} 请求可传、响应必回；
 * 未传时由服务端生成（{@code req_} 前缀随机串）；<b>响应体 {@code requestId} 必须等于响应头 {@code X-Request-Id}</b>。
 * 本类为唯一真源：{@link RequestIdFilter} 在链首写入，{@link ApiResponse} 与日志（MDC）读取同一值。
 *
 * <p>生命周期：由 {@code RequestIdFilter} 在 {@code finally} 中 {@link #clear()}，避免线程池复用串号。
 */
public final class RequestIdContext {

    /** HTTP 头名（契约 §7）。 */
    public static final String HEADER = "X-Request-Id";

    /** SLF4J MDC 键名（契约 §7：服务端日志必须包含 request_id）。 */
    public static final String MDC_KEY = "request_id";

    /** 允许透传的客户端 ID 形态：1..128 个字母/数字/下划线/点/连字符（防注入与超长）。 */
    private static final Pattern ACCEPTABLE = Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RequestIdContext() {
    }

    public static void set(String requestId) {
        HOLDER.set(requestId);
    }

    /** 当前请求的 ID；未初始化时返回 {@code null}。 */
    public static String get() {
        return HOLDER.get();
    }

    /** 当前请求的 ID；未初始化时生成一个（不写回 ThreadLocal，供极端兜底路径使用）。 */
    public static String getOrGenerate() {
        String current = HOLDER.get();
        return current != null ? current : generate();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 生成服务端 ID：{@code req_} + 32 位十六进制（契约 §7「req_ 前缀随机串」）。 */
    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "req_" + HexFormat.of().formatHex(bytes);
    }

    /**
     * 采纳客户端传入的 ID：仅当形态合法时透传，否则生成新的（契约 §7：未传时服务端生成）。
     */
    public static String adoptOrGenerate(String incoming) {
        if (incoming != null && ACCEPTABLE.matcher(incoming).matches()) {
            return incoming;
        }
        return generate();
    }
}
