package com.shoppilot.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateEnvelope;
import com.shoppilot.app.ShopPilotApplication;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M4-T03（B15）{@code POST /api/v1/analysis/runs} 端到端集成测试（真实 MySQL + 真实安全链 + 真实审计）。
 *
 * <p>覆盖任务书：202 信封（{@code code=OK}、{@code data} 十字段、{@code requestId == 响应头}）、
 * 缺 {@code Idempotency-Key} → {@code COM-0010}、VIEWER → {@code TEAM-2003}、
 * 同键同体 → 202 + {@code Idempotent-Replayed: true}、{@code ANL-7001} 409、
 * 请求体 {@code team_id} 被忽略。
 *
 * <p>★ {@link AiServiceClient} 用 {@code @MockitoBean}：真实 HTTP 语义由 {@code AiServiceClientTest} 覆盖；
 * 本类只验证「HTTP 端点 + 鉴权 + 幂等头 + 落库」。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalysisApiIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;

    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;
    private static final long WAREHOUSE_ID = 9004L;

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @MockitoBean
    private AiServiceClient aiServiceClient;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);
        insertUser(OPERATOR_ID, "operator01");
        insertUser(VIEWER_ID, "viewer01");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID);

        when(aiServiceClient.createAnalysisRun(any())).thenAnswer(invocation -> {
            com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateRequest request =
                    invocation.getArgument(0);
            return new AiRunCreateEnvelope.AiRunAccepted(
                    request.analysisRunId(), "ACCEPTED", "2026-10-19T00:00:00.000Z");
        });
    }

    // =====================================================================
    // 202 信封
    // =====================================================================

    @Test
    @DisplayName("创建成功：202 + code=OK + data 十一个字段齐全 + requestId 与响应头一致")
    void createReturns202Envelope() throws Exception {
        ResponseEntity<String> resp = postRun(operatorToken(), "api-key-1",
                selectedSkusBody(List.of(SKU_A), "from-api"));

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(), resp.getBody());
        JsonNode root = json.readTree(resp.getBody());
        assertEquals("OK", root.get("code").asText());
        assertEquals("success", root.get("message").asText());
        assertEquals(root.get("requestId").asText(),
                resp.getHeaders().getFirst("X-Request-Id"),
                "响应体 requestId 必须等于响应头 X-Request-Id");

        JsonNode data = root.get("data");
        for (String field : List.of("run_id", "run_no", "status", "scope_type", "total_skus",
                "processed_skus", "progress", "current_step", "llm_status", "timeout_at", "created_at")) {
            assertNotNull(data.get(field), "响应 data 缺少字段 " + field);
        }
        assertEquals("RUNNING", data.get("status").asText());
        assertEquals("SELECTED_SKUS", data.get("scope_type").asText());
        assertEquals(1, data.get("total_skus").asInt());
        assertEquals(0, data.get("processed_skus").asInt());
        assertEquals(5, data.get("progress").asInt());
        assertEquals("VALIDATING", data.get("current_step").asText());
        assertEquals("PENDING", data.get("llm_status").asText());
        assertTrue(data.get("run_no").asText().matches("^RUN-\\d{8}-\\d{4}$"),
                "run_no 格式不符：" + data.get("run_no").asText());
        assertTrue(data.get("timeout_at").asText().endsWith("Z"), "时间必须是 ISO-8601 UTC");
    }

    @Test
    @DisplayName("同键同体：第二次 202 + Idempotent-Replayed: true；首次无该头")
    void sameKeyReplaysWithHeader() {
        String body = selectedSkusBody(List.of(SKU_A), "replay-api");
        ResponseEntity<String> first = postRun(operatorToken(), "api-key-2", body);
        ResponseEntity<String> second = postRun(operatorToken(), "api-key-2", body);

        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode(), first.getBody());
        assertNull(first.getHeaders().getFirst("Idempotent-Replayed"), "首次不得标重放");
        assertEquals(HttpStatus.ACCEPTED, second.getStatusCode(), second.getBody());
        assertEquals("true", second.getHeaders().getFirst("Idempotent-Replayed"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_runs", Long.class));
    }

    // =====================================================================
    // 幂等键 / 鉴权 / 角色
    // =====================================================================

    @Test
    @DisplayName("缺 Idempotency-Key → 400 COM-0010")
    void missingIdempotencyKeyRejected() throws Exception {
        ResponseEntity<String> resp = postRun(operatorToken(), null,
                selectedSkusBody(List.of(SKU_A), "no-key"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        assertEquals("COM-0010", json.readTree(resp.getBody()).get("code").asText());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_runs", Long.class));
    }

    @Test
    @DisplayName("Idempotency-Key 超 128 字符 → 400 COM-0010")
    void oversizedIdempotencyKeyRejected() throws Exception {
        ResponseEntity<String> resp = postRun(operatorToken(), "k".repeat(129),
                selectedSkusBody(List.of(SKU_A), "long-key"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        assertEquals("COM-0010", json.readTree(resp.getBody()).get("code").asText());
    }

    @Test
    @DisplayName("VIEWER → 403 TEAM-2003（API_CONTRACT §9 权限矩阵）")
    void viewerForbidden() throws Exception {
        ResponseEntity<String> resp = postRun(viewerToken(), "api-viewer",
                selectedSkusBody(List.of(SKU_A), "viewer"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(), resp.getBody());
        assertEquals("TEAM-2003", json.readTree(resp.getBody()).get("code").asText());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_runs", Long.class));
    }

    @Test
    @DisplayName("未带令牌 → 401")
    void missingTokenRejected() {
        ResponseEntity<String> resp = postRun(null, "api-anon",
                selectedSkusBody(List.of(SKU_A), "anon"));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    // =====================================================================
    // 并发冲突
    // =====================================================================

    @Test
    @DisplayName("同范围不同幂等键 → 409 ANL-7001")
    void overlappingScopeConflicts() throws Exception {
        assertEquals(HttpStatus.ACCEPTED,
                postRun(operatorToken(), "api-conflict-a", selectedSkusBody(List.of(SKU_A), "c-a")).getStatusCode());

        ResponseEntity<String> resp = postRun(operatorToken(), "api-conflict-b",
                selectedSkusBody(List.of(SKU_A, SKU_B), "c-b"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode(), resp.getBody());
        assertEquals("ANL-7001", json.readTree(resp.getBody()).get("code").asText());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_runs", Long.class));
    }

    // =====================================================================
    // team_id 来源
    // =====================================================================

    @Test
    @DisplayName("请求体塞 team_id=999 被忽略：落库团队仍来自 JWT（7）")
    void requestBodyTeamIdIgnored() {
        String body = "{\"scope_type\":\"SELECTED_SKUS\",\"scope_json\":{\"sku_ids\":[" + SKU_A + "]},"
                + "\"warehouse_id\":" + WAREHOUSE_ID + ",\"date_from\":\"2026-09-16\","
                + "\"date_to\":\"2026-10-13\",\"algorithm_version\":\"avg-ma-3sigma-v1\","
                + "\"prompt_version\":\"explain_risk.v1\",\"team_id\":999}";

        assertEquals(HttpStatus.ACCEPTED, postRun(operatorToken(), "api-team", body).getStatusCode());
        assertEquals(TEAM_ID, jdbc.queryForObject(
                "SELECT team_id FROM analysis_runs WHERE idempotency_key='api-team'", Long.class),
                "team_id 只来自服务端上下文");
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private String selectedSkusBody(List<Long> skuIds, String marker) {
        StringBuilder sb = new StringBuilder("{\"scope_type\":\"SELECTED_SKUS\",\"scope_json\":{\"sku_ids\":[");
        for (int i = 0; i < skuIds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(skuIds.get(i));
        }
        sb.append("]},\"warehouse_id\":").append(WAREHOUSE_ID)
                .append(",\"date_from\":\"2026-09-16\",\"date_to\":\"2026-10-13\",")
                .append("\"algorithm_version\":\"avg-ma-3sigma-v1\",")
                .append("\"prompt_version\":\"explain_risk.v1\",")
                .append("\"model_name\":\"gpt-4o-mini-").append(marker).append("\"}");
        return sb.toString();
    }

    private ResponseEntity<String> postRun(String bearer, String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange("/api/v1/analysis/runs", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, username);
    }

    private String operatorToken() {
        return loginAccessToken("operator01");
    }

    private String viewerToken() {
        return loginAccessToken("viewer01");
    }

    private String loginAccessToken(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\""
                        + RAW_PASSWORD + "\"}", headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + response.getBody());
        try {
            return json.readTree(response.getBody()).get("data").get("accessToken").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("登录响应解析失败", ex);
        }
    }
}
