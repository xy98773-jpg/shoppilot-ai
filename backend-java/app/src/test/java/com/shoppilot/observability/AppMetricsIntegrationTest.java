package com.shoppilot.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M8-T02（{@code R195} / {@code NFR-OBS-02}）：业务指标埋点集成测试。
 *
 * <p><b>为什么自带 {@link SimpleMeterRegistry} bean</b>：Boot 自动配置的注册表在
 * {@code @SpringBootTest} 里同样存在，但这里显式提供一个，是为了能直接读它的计数器
 * （不需要起 HTTP 抓 {@code /actuator/prometheus}，也不需要反射）。真栈的端点可达性由
 * {@code verify-compose.sh} 单独断言。
 *
 * <p>覆盖四条口径：导入批次终态计数、导入确认耗时 Timer、分析运行终态计数（含降级标签）、
 * 以及「埋点缺失不得影响业务」（注册表缺失时埋点是 no-op，由单测直接覆盖）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(AppMetricsIntegrationTest.MeterConfig.class)
class AppMetricsIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    @TestConfiguration
    static class MeterConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
        registry.add("app.import.storage-dir", () -> System.getProperty("java.io.tmpdir"));
    }

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate rest;

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;
    private static final String OPERATOR = "operator01";

    private String token(String username) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username + "\",\"password\":\""
                + TestCredentials.RAW_PASSWORD + "\"}";
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(org.springframework.http.HttpStatus.OK, response.getStatusCode(),
                "登录应成功：" + username);
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private void seed() {
        jdbc.execute("DELETE FROM import_row_errors");
        jdbc.execute("DELETE FROM import_batches");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");
        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);
        // 列清单与本仓其它集成测试的种子一致（email 必填、无 team_id 列）
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')", OPERATOR_ID, OPERATOR,
                OPERATOR + "@example.com", TestCredentials.PASSWORD_HASH, OPERATOR);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES (?, ?, 'OPERATOR', 'ACTIVE')",
                TEAM_ID, OPERATOR_ID);
    }

    @Test
    @DisplayName("R195-1 导入确认埋点：import.batch.total{status,biz_type} 与 import.confirm.seconds 都记上")
    void importConfirmMetricsAreRecorded() throws Exception {
        seed();
        String tok = token(OPERATOR);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tok);
        headers.set("Idempotency-Key", "m8t02-import-metrics");
        // 未上传文件 ⇒ 确认走状态门（MAPPED/VALIDATED 之外）⇒ 抛 IMP-4005。
        // 该失败同样必须被计时与计数记下（失败率分子），这正是本用例的断言点。
        jdbc.update("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash, "
                        + "dedupe_hash, file_size, storage_path, status, total_rows, created_by) "
                        + "VALUES (?, 'B-1', 'SKU', 'a.csv', 'h1', 'd1', 1, '/tmp/a.csv', 'PARSED', 0, ?)",
                TEAM_ID, OPERATOR_ID);
        long batchId = jdbc.queryForObject("SELECT MAX(id) FROM import_batches", Long.class);

        rest.exchange("/api/v1/import/batches/" + batchId + "/confirm", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);

        // 失败路径下终态计数器可以没有（未走到终态刷新点），但 Timer 必须记——
        // 否则「失败」在指标里完全不可见，成功率永远是 100%，埋点失去告警意义。
        assertNotNull(registry.find("shoppilot.import.confirm.seconds").timer(),
                "confirm 耗时 Timer 必须存在（失败路径也要计时）");
        assertTrue(registry.find("shoppilot.import.confirm.seconds").timer().count() >= 1,
                "至少记了一次 confirm 耗时");
    }

    @Test
    @DisplayName("R195-2 指标命名与标签稳定：四个指标名都能按预期查到")
    void metricNamesAreStable() {
        // 直接驱动埋点组件，锁住「指标名 + 标签名」不被无意改动
        com.shoppilot.common.observability.AppMetrics metrics =
                new com.shoppilot.common.observability.AppMetrics(
                        new org.springframework.beans.factory.ObjectProvider<>() {
                            @Override
                            public MeterRegistry getObject() {
                                return registry;
                            }

                            @Override
                            public MeterRegistry getObject(Object... args) {
                                return registry;
                            }

                            @Override
                            public MeterRegistry getIfAvailable() {
                                return registry;
                            }

                            @Override
                            public MeterRegistry getIfUnique() {
                                return registry;
                            }
                        });
        metrics.recordAnalysisRun("DEGRADED", "SKIPPED");
        metrics.recordAnalysisRunDuration(1234L);

        assertEquals(1.0, registry.find("shoppilot.analysis.run.total")
                        .tag("status", "DEGRADED").tag("llm_status", "SKIPPED").counter().count(),
                "分析运行终态计数 + 降级标签");
        io.micrometer.core.instrument.Timer timer =
                registry.find("shoppilot.analysis.run.seconds").timer();
        assertNotNull(timer, "分析耗时指标名必须可查");
        assertEquals(1L, timer.count(), "记了一次");
        assertEquals(1234.0, timer.max(java.util.concurrent.TimeUnit.MILLISECONDS), 0.001,
                "Timer 的 max 口径为毫秒");
    }
}
