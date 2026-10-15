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
 * <p>放行来源可用 {@code shoppilot.cors.allowed-origins} 覆盖（逗号分隔），默认 Vite 开发端口。
 * 本类只做配置装配，不含任何业务逻辑。
 */
@Configuration
@Profile("dev")
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${shoppilot.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
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
