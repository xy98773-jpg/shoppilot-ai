package com.shoppilot.analysis.core;

import com.shoppilot.common.web.RequestIdContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java → Python 出站请求头的构造器。
 *
 * <p>{@code RequestIdFilter}（{@code common/web}）只处理<b>入站</b>：读/生成 ID、写上下文、回写响应头。
 * 出站调用需要把同一个 ID 带给 Python（{@code AI_SERVICE_CONTRACT.md §2:75}），本类补的就是这一环。
 *
 * <p><b>★ 不在 Java 侧生成 request-id</b>：{@code req_<32hex>} 的唯一生成点是
 * {@link RequestIdContext}（{@code R91} 裁定②）。本类只做「取现有值 → 放头」，
 * 取不到就<b>不放</b>该头，由调用方（B15 的 client）决定是拒绝调用还是走降级 —— 若在这里
 * 顺手生成一个，就会出现「响应体/日志/出站头三处 ID 各不相同」的串号问题。
 */
public final class AiOutboundHeaders {

    private AiOutboundHeaders() {
    }

    /**
     * 构造一次内部调用的请求头。
     *
     * @param internalToken 内部令牌；{@code null} 或空白时<b>不放</b> {@code X-Internal-Token} 键
     *                      （不放 ≠ 放空串：空串会被 Python 侧当成「提供了错误令牌」而非「未提供」）
     * @return 只读头映射（保持插入顺序：先令牌、后 request-id）
     */
    public static Map<String, String> forInternalCall(String internalToken) {
        Map<String, String> headers = new LinkedHashMap<>(4);
        if (internalToken != null && !internalToken.isBlank()) {
            headers.put(AiContract.HEADER_INTERNAL_TOKEN, internalToken);
        }
        String requestId = RequestIdContext.get();
        if (requestId != null && !requestId.isBlank()) {
            headers.put(AiContract.HEADER_REQUEST_ID, requestId);
        }
        return Collections.unmodifiableMap(headers);
    }
}
