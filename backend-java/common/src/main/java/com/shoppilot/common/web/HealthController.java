package com.shoppilot.common.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统存活探针（liveness）。
 *
 * <p><b>权威定义</b>：{@code API_CONTRACT.md §10.11 system} —— {@code GET /api/v1/health}
 * 「存活探针（liveness），返回服务与版本」；{@code §5.1} 将其列为**免认证**（匿名可访问）接口。
 *
 * <p><b>与 {@code /actuator/health} 的分工</b>：{@code /actuator/health} 由 Spring Boot Actuator
 * 提供（返回 {@code {"status":"UP"}}），暴露面由 {@code application.yml} 的
 * {@code management.endpoints.web.exposure.include} 收敛为 {@code health,info}；
 * 本控制器提供**契约规定的对外端点**，走统一响应体 {@link ApiResponse}。二者在 M1-T06 同时交付，
 * 分别对应 {@code DEVELOPMENT_PLAN.md} 的 T06（{@code /actuator/health} → {@code UP}）
 * 与 {@code API_CONTRACT.md §10.11} 的 {@code GET /api/v1/health}。
 *
 * <p><b>为何 {@code /internal/v1/*} 前缀不在此实现</b>：{@code §1.1} 规定内部 API 前缀
 * {@code /internal/v1} 属 {@code service-ai}（Python）；其 {@code /internal/v1/health} 由
 * **M1-T07** 交付（{@code DEVELOPMENT_PLAN.md} M1 门禁②「{@code /actuator/health} 与
 * {@code /internal/v1/health} 均 UP」中后者指 Python 服务）。Java 侧不越界提供同名前缀。
 *
 * <p><b>边界</b>：本类属 {@code common}（基础设施），不含任何业务名词与业务规则
 * （{@code MODULE_BOUNDARIES.md §3.2} 黑名单与 §3.3 判定口诀）；由 {@code app} 的
 * {@code @SpringBootApplication(scanBasePackages = "com.shoppilot")} 扫描装配。
 */
@RestController
public class HealthController {

    private final String serviceName;
    private final ObjectProvider<BuildProperties> buildProperties;

    public HealthController(@Value("${spring.application.name:shoppilot-ai}") String serviceName,
                            ObjectProvider<BuildProperties> buildProperties) {
        this.serviceName = serviceName;
        this.buildProperties = buildProperties;
    }

    /**
     * 存活探针：返回服务名与构建版本（{@code API_CONTRACT.md §10.11}「返回服务与版本」）。
     *
     * <p>版本取自构建产物 {@code META-INF/build-info.properties}（{@code spring-boot:build-info}）；
     * 在未生成该资源的场景（如 IDE 内直接运行测试）退化为 {@code "unknown"}，不会因缺资源启动失败。
     */
    @GetMapping("/api/v1/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("service", serviceName);
        BuildProperties build = buildProperties.getIfAvailable();
        data.put("version", build == null ? "unknown" : build.getVersion());
        return ApiResponse.success(data);
    }
}
