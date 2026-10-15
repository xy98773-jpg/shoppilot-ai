package com.shoppilot.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路追踪过滤器：落实 {@code API_CONTRACT.md §7}。
 *
 * <p>职责：
 * <ol>
 *   <li>读取请求头 {@code X-Request-Id}；缺失/形态非法时生成 {@code req_<32hex>}；</li>
 *   <li>写入 {@link RequestIdContext}（供 {@link ApiResponse} 填写 {@code requestId}）；</li>
 *   <li>回写<b>同名响应头</b>（保证响应体 {@code requestId} 与响应头一致）；</li>
 *   <li>写入 SLF4J MDC（键 {@code request_id}）。</li>
 * </ol>
 *
 * <p><b>顺序</b>：必须早于 Spring Security 过滤器链执行，否则未认证请求在
 * {@code JsonAuthenticationEntryPoint} 出口处拿不到本请求 ID，响应体与响应头会各生成一个 ID。
 * 故显式标注 {@link Ordered#HIGHEST_PRECEDENCE}（Spring Security 的 FilterChainProxy 默认 order 为 -100）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = RequestIdContext.adoptOrGenerate(request.getHeader(RequestIdContext.HEADER));
        RequestIdContext.set(requestId);
        response.setHeader(RequestIdContext.HEADER, requestId);
        MDC.put(RequestIdContext.MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIdContext.MDC_KEY);
            RequestIdContext.clear();
        }
    }
}
