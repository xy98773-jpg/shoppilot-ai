package com.shoppilot.common.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 跨域配置占位（<b>仅 dev profile 生效</b>，便于 M2 前后端联调）。
 *
 * <p>非 dev 环境不注册任何 {@code CorsConfigurationSource}：同源部署（nginx 反代，见
 * {@code PROJECT_STRUCTURE.md §7}）下无需 CORS，且默认拒绝跨域更安全。
 *
 * <p>放行来源可用 {@code shoppilot.cors.allowed-origins} 覆盖（逗号分隔）。
 *
 * <h2>★ 默认白名单为何包含 nginx 的 80 / 8081（M8-T08 / R198 真机修复）</h2>
 * <p>初版默认只放行 Vite 5173 ⇒ dev 栈浏览器登录<b>必 403</b>：
 * 同源 POST（经 nginx 反代）浏览器<b>也</b>会带 {@code Origin: http://localhost:8081}，
 * Spring 的 CorsFilter 只要见到 {@code Origin} 头就会校验白名单——
 * 「同源所以不走 CORS」是误解。<b>curl 不带 Origin</b>，所以后端 132 条 curl 断言
 * 全绿也暴露不了（本缺陷只在真实浏览器出现，响应体 {@code Invalid CORS request}）。
 *
 * <p>本类只做配置装配，不含任何业务逻辑。
 */
@Configuration
@Profile("dev")
public class CorsConfig {

    /** 默认白名单：Vite 5173 + nginx 同源（80 无端口后缀 / dev 常用 8081）。 */
    static final String DEFAULT_ALLOWED_ORIGINS =
            "http://localhost:5173,http://127.0.0.1:5173,"
                    + "http://localhost,http://127.0.0.1,"
                    + "http://localhost:8081,http://127.0.0.1:8081";

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${shoppilot.cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}")
            String[] allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        // 注意：allowCredentials=true 时不得使用 "*"，必须给出确定的来源清单
        config.setAllowedOrigins(List.of(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
