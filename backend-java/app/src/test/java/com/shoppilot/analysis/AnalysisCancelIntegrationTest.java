package com.shoppilot.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.app.ShopPilotApplication;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * M4-T14（B20.4）{@code POST /api/v1/analysis/runs/{id}/cancel} 端到端集成测试
 * （真实 MySQL + 真实安全链 + 真实租户线；{@code API_CONTRACT.md §10.7}）。
 *
 * <p><b>只 Mock {@link AiServiceClient}</b>（远端取消是「尽力而为」的外部副作用，
 * 单测里不应真打 Python）；<b>DB / Mapper / 租户线 / 鉴权全真</b>——任务书 §C2 明确
 * 「禁止 Mock Mapper」。
 *
 * <p>覆盖（§C2 七条 + §A8 一条）：
 * <ul>
 *   <li>{@code PENDING → CANCELLED}（200，{@code data{run_id,status,cancelled_at}}，{@code finished_at} 非空）；</li>
 *   <li>{@code RUNNING → CANCELLED}；</li>
 *   <li>终态 {@code SUCCEEDED} ⇒ {@code 422 ANL-7011}，状态不变；</li>
 *   <li>重复取消 ⇒ 200，审计仍 1 条、{@code cancelled_at} 不变；</li>
 *   <li>不存在 / 跨团队 ⇒ {@code 404 ANL-7005}（两者同表现）；</li>
 *   <li>VIEWER ⇒ {@code 403 TEAM-2003}；</li>
 *   <li>Python 取消失败（Mock 抛错）⇒ 仍 200、本地 {@code CANCELLED}；</li>
 *   <li>取消后 {@code GET /items} 仍 {@code 409 ANL-7012}（{@code progress<100}，不特判取消态）。</li>
 * </ul>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalysisCancelIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;
    private static final long OTHER_OWNER_ID = 1002L;

    private static final String RUN_PENDING_NO = "RUN-CANCEL-PENDING";
    private static final String RUN_RUNNING_NO = "RUN-CANCEL-RUNNING";
    private static final String RUN_SUCCEEDED_NO = "RUN-CANCEL-SUCCEEDED";
    private static final String RUN_OTHER_NO = "RUN-CANCEL-OTHER";

    /** ISO-8601 UTC 毫秒（{@code DESIGN_BASIS §14.1}，如 {@code 2026-10-20T08:00:00.000Z}）。 */
    private static final String ISO_UTC_MILLIS = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$";

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

    private long pendingRunId;
    private long runningRunId;
    private long succeededRunId;
    private long otherTeamRunId;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-7', '七组', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-8', '八组', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);
        insertUser(OPERATOR_ID, "operator01");
        insertUser(VIEWER_ID, "viewer01");
        insertUser(OTHER_OWNER_ID, "other01");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);

        pendingRunId = insertRun(TEAM_ID, RUN_PENDING_NO, "PENDING", 0, null);
        runningRunId = insertRun(TEAM_ID, RUN_RUNNING_NO, "RUNNING", 40, null);
        succeededRunId = insertRun(TEAM_ID, RUN_SUCCEEDED_NO, "SUCCEEDED", 100,
                java.time.LocalDateTime.of(2026, 10, 20, 7, 0, 0));
        otherTeamRunId = insertRun(OTHER_TEAM_ID, RUN_OTHER_NO, "RUNNING", 40, null);
    }

    // =====================================================================
    // 成功路径：PENDING / RUNNING → CANCELLED
    // =====================================================================

    @Test
    @DisplayName("PENDING → CANCELLED：200 + data{run_id,status,cancelled_at}，finished_at/current_step 落库")
    void pendingRunIsCancelled() throws Exception {
        ResponseEntity<String> resp = cancel(operatorToken(), pendingRunId);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = json.readTree(resp.getBody()).get("data");
        assertEquals(pendingRunId, data.get("run_id").asLong());
        assertEquals("CANCELLED", data.get("status").asText());
        assertTrue(data.get("cancelled_at").asText().matches(ISO_UTC_MILLIS),
                "cancelled_at 必须是 ISO-8601 UTC 毫秒：" + data.get("cancelled_at").asText());

        assertEquals("CANCELLED", runStatus(pendingRunId));
        assertEquals("CANCELLED", runCurrentStep(pendingRunId));
        assertNotNull(runFinishedAt(pendingRunId), "finished_at 必须由 NOW(3) 写入");
        assertEquals(1L, countAudits("ANALYSIS_RUN_CANCEL"), "取消审计恰 1 条");
        assertEquals(1L, countAuditsByResult("ANALYSIS_RUN_CANCEL", "SUCCESS"));
        assertEquals(0L, countAudits("ANALYSIS_RUN_FINISH"), "取消不额外记 FINISH（§八.7）");
    }

    @Test
    @DisplayName("RUNNING → CANCELLED：200，且取消后 GET /items 仍 409 ANL-7012（不特判）")
    void runningRunIsCancelledAndItemsStillNotReady() throws Exception {
        ResponseEntity<String> resp = cancel(operatorToken(), runningRunId);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("CANCELLED", json.readTree(resp.getBody()).get("data").get("status").asText());
        assertEquals("CANCELLED", runStatus(runningRunId));

        // §A8：progress<100 即未就绪，取消态不特判
        ResponseEntity<String> items = rest.exchange("/api/v1/analysis/runs/" + runningRunId + "/items",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(operatorToken())), String.class);
        assertEquals(HttpStatus.CONFLICT, items.getStatusCode(), items.getBody());
        assertEquals("ANL-7012", json.readTree(items.getBody()).get("code").asText());
    }

    // =====================================================================
    // 终态 / 幂等 / 存在性 / 权限
    // =====================================================================

    @Test
    @DisplayName("终态 SUCCEEDED ⇒ 422 ANL-7011，状态不变、无取消审计")
    void succeededRunCannotBeCancelled() throws Exception {
        ResponseEntity<String> resp = cancel(operatorToken(), succeededRunId);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        assertEquals("ANL-7011", json.readTree(resp.getBody()).get("code").asText());
        assertEquals("SUCCEEDED", runStatus(succeededRunId), "终态不得被改写");
        assertEquals(0L, countAudits("ANALYSIS_RUN_CANCEL"));
    }

    @Test
    @DisplayName("重复取消 ⇒ 两次都 200，审计仍 1 条，cancelled_at 为首次时刻")
    void duplicateCancelIsIdempotent() throws Exception {
        ResponseEntity<String> first = cancel(operatorToken(), pendingRunId);
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        String firstCancelledAt = json.readTree(first.getBody()).get("data").get("cancelled_at").asText();

        ResponseEntity<String> second = cancel(operatorToken(), pendingRunId);

        assertEquals(HttpStatus.OK, second.getStatusCode(), "已 CANCELLED 再取消 ⇒ 200 幂等");
        assertEquals("CANCELLED", json.readTree(second.getBody()).get("data").get("status").asText());
        assertEquals(firstCancelledAt,
                json.readTree(second.getBody()).get("data").get("cancelled_at").asText(),
                "幂等重放返回首次取消时刻");
        assertEquals(1L, countAudits("ANALYSIS_RUN_CANCEL"), "重放不得重复审计");
    }

    @Test
    @DisplayName("不存在 ⇒ 404 ANL-7005")
    void unknownRunReturns404() throws Exception {
        ResponseEntity<String> resp = cancel(operatorToken(), 999_999L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(), resp.getBody());
        assertEquals("ANL-7005", json.readTree(resp.getBody()).get("code").asText());
    }

    @Test
    @DisplayName("跨团队 ⇒ 404 ANL-7005（与不存在同表现，不泄漏他团队）")
    void crossTeamRunReturns404() throws Exception {
        ResponseEntity<String> resp = cancel(operatorToken(), otherTeamRunId);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(), resp.getBody());
        assertEquals("ANL-7005", json.readTree(resp.getBody()).get("code").asText());
        assertEquals("RUNNING", runStatus(otherTeamRunId), "他团队 run 不得被取消");
    }

    @Test
    @DisplayName("VIEWER ⇒ 403 TEAM-2003，状态不变、无审计")
    void viewerCannotCancel() throws Exception {
        ResponseEntity<String> resp = cancel(viewerToken(), pendingRunId);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(), resp.getBody());
        assertEquals("TEAM-2003", json.readTree(resp.getBody()).get("code").asText());
        assertEquals("PENDING", runStatus(pendingRunId));
        assertEquals(0L, countAudits("ANALYSIS_RUN_CANCEL"));
    }

    @Test
    @DisplayName("Python 取消失败（Mock 抛错）⇒ 仍 200、本地 CANCELLED、只 WARN")
    void pythonCancelFailureDoesNotAffectLocalState() throws Exception {
        when(aiServiceClient.cancelBestEffort(anyLong()))
                .thenThrow(new RuntimeException("python down"));

        ResponseEntity<String> resp = cancel(operatorToken(), pendingRunId);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("CANCELLED", json.readTree(resp.getBody()).get("data").get("status").asText());
        assertEquals("CANCELLED", runStatus(pendingRunId), "远端失败不得回滚本地终态");
        assertEquals(1L, countAudits("ANALYSIS_RUN_CANCEL"));
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private ResponseEntity<String> cancel(String bearer, long runId) {
        return rest.exchange("/api/v1/analysis/runs/" + runId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(bearer)), String.class);
    }

    private static HttpHeaders bearerHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
    }

    private long insertRun(long teamId, String runNo, String status, int progress,
                           java.time.LocalDateTime finishedAt) {
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, "
                        + "algorithm_version, llm_status, progress, finished_at) "
                        + "VALUES (?, ?, ?, 'ALL_ACTIVE', 'avg-ma-3sigma-v1', 'NOT_REQUESTED', ?, ?)",
                teamId, runNo, status, progress, finishedAt);
        return jdbc.queryForObject("SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);
    }

    private String runStatus(long runId) {
        return jdbc.queryForObject("SELECT status FROM analysis_runs WHERE id=?", String.class, runId);
    }

    private String runCurrentStep(long runId) {
        return jdbc.queryForObject("SELECT current_step FROM analysis_runs WHERE id=?", String.class, runId);
    }

    private java.time.LocalDateTime runFinishedAt(long runId) {
        return jdbc.queryForObject("SELECT finished_at FROM analysis_runs WHERE id=?",
                java.time.LocalDateTime.class, runId);
    }

    private long countAudits(String action) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action=?", Long.class, action);
        return value == null ? 0L : value;
    }

    private long countAuditsByResult(String action, String result) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action=? AND result=?", Long.class, action, result);
        return value == null ? 0L : value;
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
