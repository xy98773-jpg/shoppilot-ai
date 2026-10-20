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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-T13（B20.3）{@code GET /api/v1/analysis/runs/{id}/items} 快照查询端到端集成测试
 * （真实 MySQL + 真实安全链；{@code API_CONTRACT.md §11.6}）。
 *
 * <p>只 Mock {@link AiServiceClient}（本端点不触发对端同步，仅为隔离）；DB / Mapper / 租户线 / 鉴权全真。
 *
 * <p>覆盖：终态分页（{@code list/page/pageSize/total}）、{@code risk_level} 过滤、
 * {@code sort=avg_daily_sales,desc} 排序、非法 {@code sort} ⇒ {@code COM-0001}、
 * {@code progress<100} ⇒ {@code 409 ANL-7012}（带 data）、不存在/跨团队 ⇒ {@code 404 ANL-7005}、
 * VIEWER 可查、三个 JSON 列解析后结构与 {@code §11.6} 一致 + {@code sku_code} 回填。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalysisItemQueryIntegrationTest {

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

    private static final long PRODUCT_A = 7601L;
    private static final long PRODUCT_OTHER = 7602L;
    private static final long WAREHOUSE = 7701L;
    private static final long WAREHOUSE_OTHER = 7702L;
    private static final long SKU_A = 7501L;
    private static final long SKU_B = 7502L;
    private static final long SKU_C = 7503L;
    private static final long SKU_OTHER = 7504L;

    private static final String RUN_OK_NO = "RUN-ITEMS-OK";
    private static final String RUN_RUNNING_NO = "RUN-ITEMS-RUNNING";
    private static final String RUN_OTHER_NO = "RUN-ITEMS-OTHER";

    // ---- 三个 JSON 列落库结构（R139，与 writeService 规范化后同构） ----
    private static final String RISK_DETAIL_HIGH =
            "{\"findings\":[{\"rule_code\":\"R-LOWSTOCK\",\"inputs\":{\"IP\":\"8.000\"},"
                    + "\"threshold\":\"IP < D * (L + R) = 87.500\",\"result\":\"MEDIUM\"}]}";
    private static final String DATA_QUALITY_GOOD =
            "{\"level\":\"GOOD\",\"valid_days\":26,\"missing_days\":2,\"outlier_days\":null}";
    private static final String LLM_DETAIL_SUCCEEDED =
            "{\"status\":\"SUCCEEDED\",\"evidence\":[\"on_hand_qty\",\"avg_daily_sales\"],"
                    + "\"warnings\":[],\"prompt_version\":\"explain_risk.v1\",\"model_name\":\"gpt-4o-mini\"}";

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

    private long okRunId;
    private long runningRunId;
    private long otherRunId;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM products");
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
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, category, status) VALUES "
                        + "(?, ?, 'P-A', '商品A', 'CAT-01', 'ACTIVE'), "
                        + "(?, ?, 'P-X', '他团队商品', 'CAT-01', 'ACTIVE')",
                PRODUCT_A, TEAM_ID, PRODUCT_OTHER, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status, is_default) VALUES "
                        + "(?, ?, 'WH-A', '主仓', 'ACTIVE', 1), "
                        + "(?, ?, 'WH-X', '他团队仓', 'ACTIVE', 1)",
                WAREHOUSE, TEAM_ID, WAREHOUSE_OTHER, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                        + "(?, ?, ?, 'SKU-A', '规格A', 'ACTIVE'), "
                        + "(?, ?, ?, 'SKU-B', '规格B', 'ACTIVE'), "
                        + "(?, ?, ?, 'SKU-C', '规格C', 'ACTIVE'), "
                        + "(?, ?, ?, 'SKU-X', '他团队规格', 'ACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_A, SKU_B, TEAM_ID, PRODUCT_A,
                SKU_C, TEAM_ID, PRODUCT_A, SKU_OTHER, OTHER_TEAM_ID, PRODUCT_OTHER);

        // 三个 run：终态（可查）、运行中（未就绪）、跨团队（404）
        okRunId = insertRun(TEAM_ID, RUN_OK_NO, "SUCCEEDED", 100, WAREHOUSE);
        runningRunId = insertRun(TEAM_ID, RUN_RUNNING_NO, "RUNNING", 40, WAREHOUSE);
        otherRunId = insertRun(OTHER_TEAM_ID, RUN_OTHER_NO, "SUCCEEDED", 100, WAREHOUSE_OTHER);

        // 终态 run 下 3 个 items（HIGH / LOW / UNKNOWN），avg_daily_sales 用于排序、risk_level 用于过滤
        insertItem(okRunId, SKU_A, "HIGH", "6.250", 26,
                "[\"STOCKOUT\",\"LOW_STOCK\"]", RISK_DETAIL_HIGH, DATA_QUALITY_GOOD, LLM_DETAIL_SUCCEEDED);
        insertItem(okRunId, SKU_B, "LOW", "2.000", 26,
                "[\"LOW_STOCK\"]",
                "{\"findings\":[{\"rule_code\":\"R-LOWSTOCK\",\"inputs\":{\"IP\":\"20.000\"},"
                        + "\"threshold\":\"IP < D * (L + R)\",\"result\":\"LOW\"}]}",
                "{\"level\":\"FAIR\",\"valid_days\":26,\"missing_days\":2,\"outlier_days\":null}",
                "{\"status\":\"SUCCEEDED\",\"evidence\":[],\"warnings\":[],"
                        + "\"prompt_version\":\"explain_risk.v1\",\"model_name\":\"gpt-4o-mini\"}");
        insertItem(okRunId, SKU_C, "UNKNOWN", null, 3,
                "[\"DATA_INSUFFICIENT\"]",
                "{\"findings\":[{\"rule_code\":\"R-DATAINSUFFICIENT\",\"inputs\":{\"valid_days\":3,"
                        + "\"min_days\":7},\"threshold\":\"n < MIN_DAYS(7)\",\"result\":\"UNKNOWN\"}]}",
                "{\"level\":\"INSUFFICIENT\",\"valid_days\":3,\"missing_days\":25,\"outlier_days\":null}",
                "{\"status\":\"SKIPPED\",\"evidence\":[],\"warnings\":[\"有效销售天数不足，未产出补货建议\"],"
                        + "\"prompt_version\":null,\"model_name\":null}");
    }

    // =====================================================================
    // 终态分页 + 过滤 + 排序
    // =====================================================================

    @Test
    @DisplayName("终态分页：list/page/pageSize/total 逐项 + sku_code 回填")
    void succeededRunReturnsPagedItems() throws Exception {
        ResponseEntity<String> resp = getItems(operatorToken(), okRunId, null, 1, 20, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = json.readTree(resp.getBody()).get("data");
        assertEquals(3, data.get("total").asInt(), "total = 过滤后总数");
        assertEquals(1, data.get("page").asInt());
        assertEquals(20, data.get("pageSize").asInt());
        JsonNode list = data.get("list");
        assertEquals(3, list.size());

        // 默认 id 升序 ⇒ SKU_A 第一个
        JsonNode first = list.get(0);
        assertEquals(SKU_A, first.get("sku_id").asLong());
        assertEquals("SKU-A", first.get("sku_code").asText(), "sku_code 回填自 skus 主数据");
        assertEquals(WAREHOUSE, first.get("warehouse_id").asLong());
        assertEquals("8.000", first.get("on_hand_qty").asText(), "数量列字符串、3 位小数");
        assertEquals("0.4200", first.get("cv").asText(), "cv 4 位小数");
        assertEquals("HIGH", first.get("risk_level").asText());
        assertEquals(28, first.get("sales_window_days").asInt());
        assertEquals("6.250", first.get("avg_daily_sales").asText());
    }

    @Test
    @DisplayName("risk_level 过滤：HIGH 只返回 1 条；total 为过滤后总数")
    void riskLevelFilterWorks() throws Exception {
        ResponseEntity<String> resp = getItems(operatorToken(), okRunId, "HIGH", 1, 20, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = json.readTree(resp.getBody()).get("data");
        assertEquals(1, data.get("total").asInt());
        assertEquals(1, data.get("list").size());
        assertEquals("HIGH", data.get("list").get(0).get("risk_level").asText());
        assertEquals(SKU_A, data.get("list").get(0).get("sku_id").asLong());
    }

    @Test
    @DisplayName("sort=avg_daily_sales,desc：6.250 → 2.000 → null（DESC 时 null 最后）")
    void sortByAvgDailySalesDescWorks() throws Exception {
        ResponseEntity<String> resp = getItems(operatorToken(), okRunId, null, 1, 20, "avg_daily_sales,desc");

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode list = json.readTree(resp.getBody()).get("data").get("list");
        assertEquals(3, list.size());
        assertEquals(SKU_A, list.get(0).get("sku_id").asLong(), "6.250 排最前");
        assertEquals(SKU_B, list.get(1).get("sku_id").asLong(), "2.000 次之");
        assertEquals(SKU_C, list.get(2).get("sku_id").asLong(), "null 最后");
    }

    @Test
    @DisplayName("非法 sort（白名单外字段）⇒ 400 COM-0001")
    void illegalSortRejected() throws Exception {
        ResponseEntity<String> resp = getItems(operatorToken(), okRunId, null, 1, 20, "illegal_field,desc");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        assertEquals("COM-0001", json.readTree(resp.getBody()).get("code").asText());
    }

    @Test
    @DisplayName("pageSize 超上限 200 ⇒ 400 COM-0001")
    void oversizedPageSizeRejected() throws Exception {
        ResponseEntity<String> resp = getItems(operatorToken(), okRunId, null, 1, 201, null);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        assertEquals("COM-0001", json.readTree(resp.getBody()).get("code").asText());
    }

    // =====================================================================
    // R142（B20.4）：@RequestParam 基础类型转换失败 ⇒ 400 COM-0001（全局出口）
    // =====================================================================

    @Test
    @DisplayName("R142-page=abc ⇒ 400 COM-0001，details[0].field=page（此前落到 500 COM-0002）")
    void nonNumericPageReturns400() throws Exception {
        ResponseEntity<String> resp = rawItems("/api/v1/analysis/runs/" + okRunId + "/items?page=abc");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        JsonNode root = json.readTree(resp.getBody());
        assertEquals("COM-0001", root.get("code").asText());
        JsonNode detail = root.get("details").get(0);
        assertEquals("page", detail.get("field").asText(), "field 取参数名（snake_case）");
        assertEquals("abc", detail.get("rejectedValue").asText(), "回显被拒绝的原始值");
    }

    @Test
    @DisplayName("R142-pageSize=abc ⇒ 400 COM-0001，details[0].field=page_size（证明是全局 handler）")
    void nonNumericPageSizeReturns400() throws Exception {
        ResponseEntity<String> resp = rawItems("/api/v1/analysis/runs/" + okRunId + "/items?pageSize=abc");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        JsonNode root = json.readTree(resp.getBody());
        assertEquals("COM-0001", root.get("code").asText());
        assertEquals("page_size", root.get("details").get(0).get("field").asText(),
                "toSnakeCase(pageSize) = page_size（与 COM-0001 的字段路径口径一致）");
    }

    @Test
    @DisplayName("R146-pageSize=0 ⇒ 400 COM-0001，details[0].field=page_size（服务端口径 snake_case）")
    void zeroPageSizeRejected() throws Exception {
        ResponseEntity<String> resp = rawItems("/api/v1/analysis/runs/" + okRunId + "/items?pageSize=0");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        JsonNode root = json.readTree(resp.getBody());
        assertEquals("COM-0001", root.get("code").asText());
        JsonNode detail = root.get("details").get(0);
        assertEquals("page_size", detail.get("field").asText(),
                "R146：服务端 badParam 首参必须是 snake_case（消息文案可保留 pageSize）");
        assertEquals(0, detail.get("rejectedValue").asInt(), "回显被拒绝的原始值");
    }

    // =====================================================================
    // 就绪判定 / 存在性 / 权限
    // =====================================================================

    @Test
    @DisplayName("progress<100 ⇒ 409 ANL-7012，data 附 {run_id,status,progress}")
    void runningRunReturnsNotReady() throws Exception {
        ResponseEntity<String> resp = getItems(operatorToken(), runningRunId, null, 1, 20, null);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode(), resp.getBody());
        JsonNode root = json.readTree(resp.getBody());
        assertEquals("ANL-7012", root.get("code").asText());
        JsonNode data = root.get("data");
        assertEquals(runningRunId, data.get("run_id").asLong());
        assertEquals("RUNNING", data.get("status").asText());
        assertEquals(40, data.get("progress").asInt());
    }

    @Test
    @DisplayName("不存在 / 跨团队 run ⇒ 404 ANL-7005（不区分两者）")
    void missingOrCrossTeamRunReturns404() throws Exception {
        ResponseEntity<String> missing = getItems(operatorToken(), 999999L, null, 1, 20, null);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode(), missing.getBody());
        assertEquals("ANL-7005", json.readTree(missing.getBody()).get("code").asText());

        ResponseEntity<String> crossTeam = getItems(operatorToken(), otherRunId, null, 1, 20, null);
        assertEquals(HttpStatus.NOT_FOUND, crossTeam.getStatusCode(), crossTeam.getBody());
        assertEquals("ANL-7005", json.readTree(crossTeam.getBody()).get("code").asText());
        assertTrue(json.readTree(crossTeam.getBody()).get("data").isNull(), "404 不泄漏跨团队信息");
    }

    @Test
    @DisplayName("VIEWER 可查（任何已登录成员，§11.6）")
    void viewerCanQuery() throws Exception {
        ResponseEntity<String> resp = getItems(viewerToken(), okRunId, null, 1, 20, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals(3, json.readTree(resp.getBody()).get("data").get("total").asInt());
    }

    // =====================================================================
    // 三个 JSON 列解析后结构（§11.6）
    // =====================================================================

    @Test
    @DisplayName("三个 JSON 列解析后结构与 §11.6 一致（level/findings/status/evidence/warnings）")
    void jsonColumnsParsedToContractShape() throws Exception {
        ResponseEntity<String> resp = getItems(operatorToken(), okRunId, "HIGH", 1, 20, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode item = json.readTree(resp.getBody()).get("data").get("list").get(0);

        // data_quality_json：对象（level/valid_days/missing_days/outlier_days）
        JsonNode dq = item.get("data_quality_json");
        assertEquals("GOOD", dq.get("level").asText());
        assertEquals(26, dq.get("valid_days").asInt());
        assertEquals(2, dq.get("missing_days").asInt());
        assertTrue(dq.get("outlier_days").isNull(), "outlier_days 落 null（R140）");

        // risk_types / risk_findings：数组
        assertTrue(item.get("risk_types").isArray());
        assertEquals("STOCKOUT", item.get("risk_types").get(0).asText());
        assertTrue(item.get("risk_findings").isArray(), "risk_findings 是数组");
        assertEquals("R-LOWSTOCK", item.get("risk_findings").get(0).get("rule_code").asText());

        // llm_detail_json 拆出 llm_status / llm_evidence / warnings
        assertEquals("SUCCEEDED", item.get("llm_status").asText(), "llm_status 从 llm_detail_json.status 拆出");
        assertTrue(item.get("llm_evidence").isArray());
        assertEquals("on_hand_qty", item.get("llm_evidence").get(0).asText());
        assertTrue(item.get("warnings").isArray(), "warnings 从 llm_detail_json 拆出");
    }

    // =====================================================================
    // 种子与工具
    // =====================================================================

    private long insertRun(long teamId, String runNo, String status, int progress, long warehouseId) {
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, scope_json, "
                        + "warehouse_id, params_json, algorithm_version, prompt_version, model_name, "
                        + "llm_status, data_start_date, data_end_date, total_skus, processed_skus, "
                        + "progress, idempotency_key, triggered_by, timeout_at) "
                        + "VALUES (?, ?, ?, 'ALL_ACTIVE', NULL, ?, ?, 'avg-ma-3sigma-v1', "
                        + "'explain_risk.v1', 'gpt-4o-mini', 'NOT_REQUESTED', '2026-09-16', '2026-10-13', "
                        + "3, ?, ?, ?, ?, NOW(3) + INTERVAL 300 SECOND)",
                teamId, runNo, status, warehouseId,
                "{\"window_days\":28,\"min_days\":7,\"coverage_days\":7,\"lead_time_default\":7,"
                        + "\"ss_ratio\":0.5,\"cv_high\":1.0,\"budget_limit\":null,\"enable_llm\":false}",
                3, progress, "idem-" + runNo, OPERATOR_ID);
        return jdbc.queryForObject("SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);
    }

    private void insertItem(long runId, long skuId, String riskLevel, String avgDailySales, int validDays,
                            String riskTypes, String riskDetail, String dataQuality, String llmDetail) {
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, in_transit_qty, effective_in_transit_qty, committed_qty, "
                        + "inventory_position_qty, available_qty, avg_daily_sales, sales_window_days, "
                        + "valid_days, total_sales_qty, cv, lead_time_days, lead_time_source, coverage_days, "
                        + "safety_stock_qty, volatility_adjusted, qty_base, qty_suggest, moq_applied, "
                        + "order_multiple_applied, truncated_by_budget, risk_level, risk_types, "
                        + "risk_detail_json, data_quality_json, llm_detail_json) "
                        + "VALUES (?, ?, ?, ?, ?, 8.000, 0.000, 0.000, 1.000, 7.000, 7.000, ?, 28, ?, "
                        + "175.000, 0.4200, 7, 'SKU', 7, 25.000, 0, 104.500, 110.000, 50.000, 10.000, 0, "
                        + "?, ?, ?, ?, ?)",
                TEAM_ID, runId, skuId, PRODUCT_A, WAREHOUSE, avgDailySales, validDays,
                riskLevel, riskTypes, riskDetail, dataQuality, llmDetail);
    }

    private ResponseEntity<String> getItems(String bearer, Long runId, String riskLevel,
                                            int page, int pageSize, String sort) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        String url = "/api/v1/analysis/runs/" + runId + "/items?page=" + page + "&pageSize=" + pageSize;
        if (riskLevel != null) {
            url += "&risk_level=" + riskLevel;
        }
        if (sort != null) {
            url += "&sort=" + sort;
        }
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** 原样 URL（R142 用：需要传非数字的 page/pageSize，不能走 int 形参的 {@link #getItems}）。 */
    private ResponseEntity<String> rawItems(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(operatorToken());
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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
