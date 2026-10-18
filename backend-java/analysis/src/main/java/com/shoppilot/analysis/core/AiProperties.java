package com.shoppilot.analysis.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务（Python {@code service-ai}）的内部调用配置。
 *
 * <p><b>★ 刻意不做启动期校验</b>：本类<b>不得</b>在初始化阶段做任何「配置缺失即抛异常」的
 * 校验（生命周期回调抛错、断言失败即崩之类）。原因（{@code M4-B13 F15}）：
 * 本仓已有 34 个 {@code @SpringBootTest} 类，每个都自建属性源；一旦新增启动期必填属性，
 * 这 34 个文件会被连锁改动。令牌是否可用由 {@link #isConfigured()} <b>在调用点</b>判断，
 * 由 B15 的 client 决定「未配置时如何降级」，而不是让整个应用起不来。
 *
 * <p>字段与 {@code application.yml} 的 {@code shoppilot.ai.*} 一一对应；环境变量经
 * {@code AI_INTERNAL_TOKEN} / {@code AI_SERVICE_BASE_URL} 注入（见 {@code application.yml}）。
 */
@ConfigurationProperties(prefix = "shoppilot.ai")
public class AiProperties {

    /** 内部令牌，对应 Python 侧 {@code AI_INTERNAL_TOKEN}；默认空串（= 未配置）。 */
    private String internalToken = "";

    /** Python 服务基址；默认取 Compose 服务名（{@code DESIGN_BASIS.md §13.1}）。 */
    private String baseUrl = "http://service-ai:8000";

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 令牌是否已配置（去空白后非空）。
     *
     * <p>供 B15 的 client 在<b>发请求前</b>判空：未配置时应显式降级（并留日志），
     * 而不是发一个注定 401 的请求、也不是在启动期崩掉。
     */
    public boolean isConfigured() {
        return internalToken != null && !internalToken.isBlank();
    }
}
