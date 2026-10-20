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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-B22.1 {@code GET /api/v1/analysis/runs} 运行列表端到端集成测试
 * （真实 MySQL + 真实安全链；{@code API_CONTRACT.md §11.5} / F-ANL-09）。
 *
 * <p>只 Mock {@link AiServiceClient}（列表端点不触发对端调用，仅为隔离）；DB / Mapper /
 * 租户线（{@code team_id} 自动追加）/ 鉴权全真。<b>禁止</b> Mock Mapper（任务书 T1）。
 *
 * <p>覆盖：分页与 {@code total}、默认 {@code created_at,desc}、{@code status} 过滤、
 * {@code date_from/date_to} 自然日过滤（含边界日）、{@code sort} 白名单与方向、
 * 非法 {@code status/sort/page/pageSize/date_from} ⇒ {@code 400 COM-0001}
 * （{@code details[].field} 一律 snake_case，R146）、VIEWER 可查、跨团队隔离、空列表合法、
 * 列表元素与 {@code GET /runs/{id}} <b>字段逐字一致</b>（A4）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalysisRunListIntegrationTest {

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

    private static final long WAREHOUSE = 7701L;
    private static final long WAREHOUSE_OTHER = 7702L;

    /** 与 {@code AnalysisParams.canonicalJson()} 同构，供 {@code params_json} 落库。 */
    private static final String PARAMS_JSON = "{\"window_days\":28,\"min_days\":7,\"coverage_days\":7,"
            + "\"lead_time_default\":7,\"ss_ratio\":0.5,\"cv_high\":1.0,\"budget_limit\":null,"
            + "\"enable_llm\":false}";

    private static final String RUN_A = "RUN-LIST-A";
    private static final String RUN_B = "RUN-LIST-B";
    private static final String RUN_C = "RUN-LIST-C";
    private static final String RUN_D = "RUN-LIST-D";
    private static final String RUN_OTHER = "RUN-LIST-X";

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

    private long runAId;
    private long runBId;
    private long runCId;
    private long runDId;

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
                        + "(?, 'T-8', '八组', 'ACTIVE', 'Asia/Shanghai')",
                TEAM_ID, OTHER_TEAM_ID);
        insertUser(OPERATOR_ID, "operator01");
        insertUser(VIEWER_ID, "viewer01");
        insertUser(OTHER_OWNER_ID, "other01");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);

        // 团队 7 四个 run（created_at 刻意错开：A 最早、C 最晚；C 与 D 同日用于「同日边界」）
        runAId = insertRun(TEAM_ID, RUN_A, "SUCCEEDED", 100, "DONE", 3,
                "2026-10-01 10:00:00.000");
        runBId = insertRun(TEAM_ID, RUN_B, "FAILED", 40, "FAILED", 2,
                "2026-10-02 10:00:00.000");
        runCId = insertRun(TEAM_ID, RUN_C, "RUNNING", 40, "COMPUTING", 3,
                "2026-10-03 10:00:00.000");
        runDId = insertRun(TEAM_ID, RUN_D, "SUCCEEDED", 100, "DONE", 3,
                "2026-10-03 09:00:00.000");
        // 团队 8：用于跨团队隔离（落在 10-02，若租户线失效会污染 date 过滤用例）
        insertRun(OTHER_TEAM_ID, RUN_OTHER, "SUCCEEDED", 100, "DONE", 3,
                "2026-10-02 12:00:00.000");
    }

    // =====================================================================
    // 分页 / 排序 / 过滤
    // =====================================================================

    @Test
    @DisplayName("分页：list/page/pageSize/total 逐项；默认 created_at,desc")
    void defaultPagingAndOrder() throws Exception {
        ResponseEntity<String> resp = listRuns(operatorToken(), "page=1&pageSize=2");

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals(1, data.get("page").asInt());
        assertEquals(2, data.get("pageSize").asInt());
        assertEquals(4, data.get("total").asInt(), "租户内 4 个 run（他团队 1 个不可见）");
        assertEquals(2, data.get("list").size());

        // created_at desc：C(10-03 10:00) → D(10-03 09:00)
        assertEquals(RUN_C, data.get("list").get(0).get("run_no").asText());
        assertEquals(RUN_D, data.get("list").get(1).get("run_no").asText());

        JsonNode second = data(listRuns(operatorToken(), "page=2&pageSize=2"));
        assertEquals(2, second.get("list").size());
        assertEquals(RUN_B, second.get("list").get(0).get("run_no").asText());
        assertEquals(RUN_A, second.get("list").get(1).get("run_no").asText());
    }

    @Test
    @DisplayName("status 过滤：只返回匹配状态，total 为过滤后总数")
    void statusFilter() throws Exception {
        JsonNode data = data(listRuns(operatorToken(), "status=SUCCEEDED&page=1&pageSize=20"));

        assertEquals(2, data.get("total").asInt(), "团队 7 的 SUCCEEDED = A + D");
        List<String> statuses = new ArrayList<>();
        data.get("list").forEach(n -> statuses.add(n.get("status").asText()));
        assertTrue(statuses.stream().allMatch("SUCCEEDED"::equals), "实际：" + statuses);
        // created_at desc：D(10-03 09:00) → A(10-01 10:00)
        assertEquals(RUN_D, data.get("list").get(0).get("run_no").asText());
    }

    @Test
    @DisplayName("date_from/date_to：按 created_at 自然日过滤（含两端边界）")
    void dateRangeFilterInclusive() throws Exception {
        // 单日 10-03：C(10:00) + D(09:00)
        JsonNode sameDay = data(listRuns(operatorToken(),
                "date_from=2026-10-03&date_to=2026-10-03&page=1&pageSize=20"));
        assertEquals(2, sameDay.get("total").asInt());
        assertEquals(RUN_C, sameDay.get("list").get(0).get("run_no").asText());

        // 区间 10-01..10-02：A + B（他团队 X 落在 10-02，若不隔离会变成 3）
        JsonNode range = data(listRuns(operatorToken(),
                "date_from=2026-10-01&date_to=2026-10-02&page=1&pageSize=20"));
        assertEquals(2, range.get("total").asInt());
        assertEquals(RUN_B, range.get("list").get(0).get("run_no").asText());

        // 只给 date_to：<= 10-01 仅 A
        JsonNode untilFirst = data(listRuns(operatorToken(), "date_to=2026-10-01&page=1&pageSize=20"));
        assertEquals(1, untilFirst.get("total").asInt());
        assertEquals(RUN_A, untilFirst.get("list").get(0).get("run_no").asText());
    }

    @Test
    @DisplayName("sort 白名单：created_at,asc 与 status,asc 生效")
    void sortWhitelistApplied() throws Exception {
        JsonNode asc = data(listRuns(operatorToken(), "sort=created_at,asc&page=1&pageSize=20"));
        assertEquals(RUN_A, asc.get("list").get(0).get("run_no").asText(), "最早的在最前");

        JsonNode byStatus = data(listRuns(operatorToken(), "sort=status,asc&page=1&pageSize=20"));
        List<String> statuses = new ArrayList<>();
        byStatus.get("list").forEach(n -> statuses.add(n.get("status").asText()));
        // 字典序：FAILED < RUNNING < SUCCEEDED
        assertEquals(List.of("FAILED", "RUNNING", "SUCCEEDED", "SUCCEEDED"), statuses);
    }

    // =====================================================================
    // 非法参数（400 COM-0001 + snake_case field）
    // =====================================================================

    @Test
    @DisplayName("非法参数 ⇒ 400 COM-0001，details[].field 一律 snake_case")
    void invalidParamsReturn400WithSnakeCaseField() throws Exception {
        assertBadParam("status=BOGUS", "status");
        assertBadParam("sort=bogus,desc", "sort");
        assertBadParam("sort=created_at,sideways", "sort");
        assertBadParam("page=0", "page");
        assertBadParam("pageSize=201", "page_size");
        assertBadParam("pageSize=abc", "page_size");
        assertBadParam("date_from=not-a-date", "date_from");
        // date_from > date_to（手工校验分支）
        assertBadParam("date_from=2026-10-05&date_to=2026-10-01", "date_from");
    }

    @Test
    @DisplayName("空列表合法：200 + total=0 + list=[]")
    void emptyListIsLegal() throws Exception {
        ResponseEntity<String> resp = listRuns(operatorToken(), "status=TIMEOUT&page=1&pageSize=20");

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals(0, data.get("total").asInt());
        assertTrue(data.get("list").isArray());
        assertEquals(0, data.get("list").size());
    }

    @Test
    @DisplayName("翻页越界：200 + total 不变 + list 为空")
    void pageBeyondRangeIsEmpty() throws Exception {
        JsonNode data = data(listRuns(operatorToken(), "page=99&pageSize=20"));

        assertEquals(4, data.get("total").asInt());
        assertEquals(0, data.get("list").size());
    }

    // =====================================================================
    // 权限与租户线
    // =====================================================================

    @Test
    @DisplayName("VIEWER 可查（任何已登录成员，§11.5）")
    void viewerCanList() throws Exception {
        ResponseEntity<String> resp = listRuns(viewerToken(), "page=1&pageSize=20");

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals(4, data(resp).get("total").asInt());
    }

    @Test
    @DisplayName("跨团队隔离：他团队 run 不出现在任何页/任何过滤下")
    void crossTeamRunsAreInvisible() throws Exception {
        JsonNode data = data(listRuns(operatorToken(), "page=1&pageSize=50"));
        List<String> runNos = new ArrayList<>();
        data.get("list").forEach(n -> runNos.add(n.get("run_no").asText()));

        assertEquals(4, data.get("total").asInt());
        assertFalse(runNos.contains(RUN_OTHER), "跨团队 run 泄漏：" + runNos);
    }

    // =====================================================================
    // A4：列表元素与 GET /runs/{id} 字段逐字一致
    // =====================================================================

    @Test
    @DisplayName("A4：列表元素与 GET /runs/{id} 的 data 逐字相同（字段/取值/可空省略一致）")
    void listItemEqualsDetailResponse() throws Exception {
        JsonNode list = data(listRuns(operatorToken(), "status=SUCCEEDED&page=1&pageSize=20"));
        JsonNode fromList = null;
        for (JsonNode node : list.get("list")) {
            if (node.get("run_id").asLong() == runAId) {
                fromList = node;
            }
        }
        assertTrue(fromList != null, "列表中应能找到 RUN-LIST-A");

        JsonNode detail = data(getRun(runAId, operatorToken()));

        assertEquals(detail, fromList, "列表元素必须与详情 data 字段逐字一致（A4：不新建平行 DTO）");
    }

    // =====================================================================
    // 种子与工具
    // =====================================================================

    private long insertRun(long teamId, String runNo, String status, int progress, String currentStep,
                           int processedSkus, String createdAt) {
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, scope_json, "
                        + "warehouse_id, params_json, algorithm_version, prompt_version, model_name, "
                        + "llm_status, data_start_date, data_end_date, total_skus, processed_skus, "
                        + "progress, current_step, risk_sku_count, suggestion_count, idempotency_key, "
                        + "triggered_by, timeout_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ALL_ACTIVE', NULL, ?, ?, 'avg-ma-3sigma-v1', "
                        + "'explain_risk.v1', 'gpt-4o-mini', 'NOT_REQUESTED', '2026-09-16', '2026-10-13', "
                        + "3, ?, ?, ?, 1, 1, ?, ?, NOW(3) + INTERVAL 300 SECOND, ?, ?)",
                teamId, runNo, status,
                teamId == TEAM_ID ? WAREHOUSE : WAREHOUSE_OTHER,
                PARAMS_JSON, processedSkus, progress, currentStep, "idem-" + runNo, OPERATOR_ID,
                createdAt, createdAt);
        return jdbc.queryForObject("SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);
    }

    private void assertBadParam(String query, String expectedField) throws Exception {
        ResponseEntity<String> resp = listRuns(operatorToken(), query);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), "query=" + query + " body=" + resp.getBody());
        JsonNode root = json.readTree(resp.getBody());
        assertEquals("COM-0001", root.get("code").asText(), "query=" + query);
        JsonNode details = root.get("details");
        assertTrue(details != null && details.isArray() && !details.isEmpty(),
                "应带字段级细节，query=" + query + " body=" + resp.getBody());
        assertEquals(expectedField, details.get(0).get("field").asText(),
                "field 必须 snake_case，query=" + query);
    }

    private ResponseEntity<String> listRuns(String bearer, String query) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        String url = "/api/v1/analysis/runs" + (query == null || query.isEmpty() ? "" : "?" + query);
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> getRun(long runId, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange("/api/v1/analysis/runs/" + runId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> resp) throws Exception {
        return json.readTree(resp.getBody()).get("data");
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
