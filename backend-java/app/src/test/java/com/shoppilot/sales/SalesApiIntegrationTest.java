package com.shoppilot.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.math.BigDecimal;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-T12（B8）sales API 层 —— 端到端集成测试（真实 MySQL + 真实 Spring 上下文 + 真实 HTTP）。
 *
 * <p><b>真栈</b>：MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移 + {@code SecurityFilterChain}
 * + {@code JwtAuthenticationFilter} + 租户线 + 分页拦截器 + {@code @RequireRole} 切面 + 真实审计。
 *
 * <p>覆盖任务书门槛：两端点可用；{@code groupBy=sku|warehouse|date} 三聚合正确；日期区间筛选；
 * {@code pageSize=201} / 非法 {@code sort} / 非法 {@code groupBy} / 逆序日期区间 → {@code COM-0007}；
 * 角色（VIEWER POST→{@code TEAM-2003}，任何成员 GET→200）；upsert 覆盖（同键行数仍 1）；
 * 跨团队 SKU/仓库 → {@code SAL-6005}；qty 负 → {@code SAL-6002}；sale_date 非法 → {@code SAL-6003}；
 * upsert 写 {@code SALES_DAILY_UPSERT} 审计。
 *
 * <p>★ 契约 {@code SAL-6006} 未固化，按任务书降级为 {@code COM-0007}（与 B7 {@code listTransactions} 同口径）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SalesApiIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;

    private static final long OWNER_ID = 1004L;
    private static final long ADMIN_ID = 1005L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;
    private static final long APPROVER_ID = 1008L;
    private static final long OTHER_OWNER_ID = 2004L;

    private static final long PRODUCT_ID = 9001L;
    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;
    private static final long SKU_C = 9005L;
    private static final long WAREHOUSE_1 = 9004L;
    private static final long WAREHOUSE_2 = 9007L;

    private static final long OTHER_PRODUCT_ID = 9101L;
    private static final long OTHER_SKU_ID = 9102L;
    private static final long OTHER_WAREHOUSE_ID = 9103L;

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM sales_daily");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);

        insertUser(OWNER_ID, "owner01");
        insertUser(ADMIN_ID, "admin01");
        insertUser(OPERATOR_ID, "operator01");
        insertUser(VIEWER_ID, "viewer01");
        insertUser(APPROVER_ID, "approver01");
        insertUser(OTHER_OWNER_ID, "owner8");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'ADMIN', 'ACTIVE'), "
                        + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), "
                        + "(?, ?, 'APPROVER', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, ADMIN_ID, TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID,
                TEAM_ID, APPROVER_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) VALUES "
                + "(?, ?, 'P-BAG-001', '箱包', 'ACTIVE'), (?, ?, 'P-OTHER-001', '别家商品', 'ACTIVE')",
                PRODUCT_ID, TEAM_ID, OTHER_PRODUCT_ID, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                + "(?, ?, ?, 'SKU-A', 'SKU-A', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-B', 'SKU-B', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-C', 'SKU-C', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-OTHER', 'SKU-OTHER', 'ACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_ID,
                SKU_B, TEAM_ID, PRODUCT_ID,
                SKU_C, TEAM_ID, PRODUCT_ID,
                OTHER_SKU_ID, OTHER_TEAM_ID, OTHER_PRODUCT_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) VALUES "
                + "(?, ?, 'WH-1', '一号仓', 'ACTIVE'), (?, ?, 'WH-2', '二号仓', 'ACTIVE'), "
                + "(?, ?, 'WH-OTHER', '别家仓', 'ACTIVE')",
                WAREHOUSE_1, TEAM_ID, WAREHOUSE_2, TEAM_ID, OTHER_WAREHOUSE_ID, OTHER_TEAM_ID);

        // 种子日销售（团队 7）：
        //   SKU-A / WH1: 10-01=10, 10-02=20；SKU-B / WH1: 10-01=5；SKU-C / WH2: 10-01=30
        //   groupBy=sku: 9002=30, 9003=5, 9005=30
        //   groupBy=warehouse: WH1=35, WH2=30；groupBy=date: 10-01=45, 10-02=20
        jdbc.update("INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, "
                + "qty, return_qty, sales_amount, source) VALUES "
                + "(" + TEAM_ID + "," + WAREHOUSE_1 + "," + SKU_A + "," + PRODUCT_ID + ",'2026-10-01',10.000,0,100.00,'IMPORT'),"
                + "(" + TEAM_ID + "," + WAREHOUSE_1 + "," + SKU_A + "," + PRODUCT_ID + ",'2026-10-02',20.000,0,200.00,'IMPORT'),"
                + "(" + TEAM_ID + "," + WAREHOUSE_1 + "," + SKU_B + "," + PRODUCT_ID + ",'2026-10-01',5.000,0,50.00,'IMPORT'),"
                + "(" + TEAM_ID + "," + WAREHOUSE_2 + "," + SKU_C + "," + PRODUCT_ID + ",'2026-10-01',30.000,0,300.00,'IMPORT')");
    }

    // =====================================================================
    // POST /sales/daily —— 写入 / 覆盖 / 校验 / 角色
    // =====================================================================

    @Test
    @DisplayName("POST 成功：OPERATOR 手工写入 → 201，qty 为字符串、sale_date 与 sku_code 正确")
    void postCreatesDailySales() throws Exception {
        ResponseEntity<String> resp = postDaily(operatorToken(),
                "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_1
                        + ",\"sale_date\":\"2026-10-03\",\"qty\":\"15\",\"sales_amount\":\"150.00\"}");

        assertEquals(HttpStatus.CREATED, resp.getStatusCode(), "应返回 201：" + resp.getBody());
        JsonNode data = objectMapper.readTree(resp.getBody()).get("data");
        assertNotNull(data.get("id"));
        assertTrue(data.get("qty").isTextual(), "数量应以字符串输出");
        assertEquals(0, new BigDecimal("15").compareTo(new BigDecimal(data.get("qty").asText())));
        assertEquals("2026-10-03", data.get("sale_date").asText());
        assertEquals("SKU-A", data.get("sku_code").asText());
        assertEquals("MANUAL", data.get("source").asText());
    }

    @Test
    @DisplayName("POST upsert 覆盖：同 (wh,sku,date) 两次写入 → 行数仍 1，qty 为新值（U-6）")
    void postUpsertOverwritesSameKey() throws Exception {
        String body15 = "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_1
                + ",\"sale_date\":\"2026-10-05\",\"qty\":\"15\"}";
        String body9 = "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_1
                + ",\"sale_date\":\"2026-10-05\",\"qty\":\"9\"}";
        assertEquals(HttpStatus.CREATED, postDaily(operatorToken(), body15).getStatusCode());
        assertEquals(HttpStatus.CREATED, postDaily(operatorToken(), body9).getStatusCode());

        assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sales_daily WHERE team_id=? AND warehouse_id=? AND sku_id=? AND sale_date='2026-10-05'",
                        Long.class, TEAM_ID, WAREHOUSE_1, SKU_A),
                "同业务键应只有 1 行");
        assertEquals(0, new BigDecimal("9").compareTo(jdbc.queryForObject(
                        "SELECT qty FROM sales_daily WHERE team_id=? AND warehouse_id=? AND sku_id=? AND sale_date='2026-10-05'",
                        BigDecimal.class, TEAM_ID, WAREHOUSE_1, SKU_A)),
                "qty 应为最后一次写入的值 9");
    }

    @Test
    @DisplayName("POST qty < 0 → 400 SAL-6002")
    void postNegativeQtyRejected() throws Exception {
        ResponseEntity<String> resp = postDaily(operatorToken(),
                "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_1
                        + ",\"sale_date\":\"2026-10-03\",\"qty\":\"-1\"}");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("SAL-6002", errorCode(resp));
    }

    @Test
    @DisplayName("POST sale_date 非法 → 400 SAL-6003")
    void postInvalidSaleDateRejected() throws Exception {
        ResponseEntity<String> resp = postDaily(operatorToken(),
                "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_1
                        + ",\"sale_date\":\"2026-13-40\",\"qty\":\"1\"}");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("SAL-6003", errorCode(resp));
    }

    @Test
    @DisplayName("POST 跨团队 SKU → 422 SAL-6005")
    void postCrossTeamSkuRejected() throws Exception {
        ResponseEntity<String> resp = postDaily(operatorToken(),
                "{\"sku_id\":" + OTHER_SKU_ID + ",\"warehouse_id\":" + WAREHOUSE_1
                        + ",\"sale_date\":\"2026-10-03\",\"qty\":\"1\"}");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertEquals("SAL-6005", errorCode(resp));
    }

    @Test
    @DisplayName("POST 跨团队仓库 → 422 SAL-6005")
    void postCrossTeamWarehouseRejected() throws Exception {
        ResponseEntity<String> resp = postDaily(operatorToken(),
                "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + OTHER_WAREHOUSE_ID
                        + ",\"sale_date\":\"2026-10-03\",\"qty\":\"1\"}");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertEquals("SAL-6005", errorCode(resp));
    }

    @Test
    @DisplayName("POST 缺 sku_id → 400 COM-0001")
    void postMissingSkuRejected() throws Exception {
        ResponseEntity<String> resp = postDaily(operatorToken(),
                "{\"warehouse_id\":" + WAREHOUSE_1 + ",\"sale_date\":\"2026-10-03\",\"qty\":\"1\"}");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("COM-0001", errorCode(resp));
    }

    @Test
    @DisplayName("POST VIEWER → 403 TEAM-2003（角色不足）")
    void postViewerForbidden() throws Exception {
        ResponseEntity<String> resp = postDaily(viewerToken(),
                "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_1
                        + ",\"sale_date\":\"2026-10-03\",\"qty\":\"1\"}");
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals("TEAM-2003", errorCode(resp));
    }

    @Test
    @DisplayName("POST ADMIN / APPROVER → 201（OPERATOR+ 均可写）")
    void postAdminAndApproverAllowed() throws Exception {
        String body = "{\"sku_id\":" + SKU_B + ",\"warehouse_id\":" + WAREHOUSE_1
                + ",\"sale_date\":\"2026-10-03\",\"qty\":\"3\"}";
        assertEquals(HttpStatus.CREATED, postDaily(adminToken(), body).getStatusCode(), "ADMIN 应可写");
        assertEquals(HttpStatus.CREATED, postDaily(approverToken(), body).getStatusCode(), "APPROVER 应可写");
    }

    // =====================================================================
    // GET /sales/daily —— 原始列表 / 筛选 / 分页
    // =====================================================================

    @Test
    @DisplayName("GET 原始列表：data 为 PageResult（字段 list / total），共 4 行种子")
    void getRawListReturnsListField() throws Exception {
        ResponseEntity<String> resp = get("/api/v1/sales/daily", ownerToken());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = objectMapper.readTree(resp.getBody()).get("data");
        assertTrue(data.has("list"), "分页响应字段必须是 list（不是 items）");
        assertEquals(4, data.get("list").size());
        assertEquals(4, data.get("total").asInt());
    }

    @Test
    @DisplayName("GET ?sku_id 筛选：仅返回该 SKU 的行")
    void getFilterBySku() throws Exception {
        ResponseEntity<String> resp = get("/api/v1/sales/daily?sku_id=" + SKU_A, ownerToken());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode list = objectMapper.readTree(resp.getBody()).get("data").get("list");
        assertEquals(2, list.size(), "SKU-A 有 10-01 / 10-02 两行");
        for (JsonNode n : list) {
            assertEquals(SKU_A, n.get("sku_id").asLong());
        }
    }

    @Test
    @DisplayName("GET ?dateFrom&dateTo 筛选：日期区间过滤正确")
    void getFilterByDateRange() throws Exception {
        ResponseEntity<String> resp = get(
                "/api/v1/sales/daily?dateFrom=2026-10-02&dateTo=2026-10-02", ownerToken());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode list = objectMapper.readTree(resp.getBody()).get("data").get("list");
        assertEquals(1, list.size(), "仅 10-02 一行（SKU-A）");
        assertEquals("2026-10-02", list.get(0).get("sale_date").asText());
    }

    // =====================================================================
    // GET ?groupBy —— 三聚合
    // =====================================================================

    @Test
    @DisplayName("GET ?groupBy=sku：三 SKU 聚合正确（9002=30、9003=5、9005=30）")
    void getGroupBySkuAggregates() throws Exception {
        JsonNode data = objectMapper.readTree(
                get("/api/v1/sales/daily?groupBy=sku", ownerToken()).getBody()).get("data");
        assertGroupQty(data, String.valueOf(SKU_A), "30");
        assertGroupQty(data, String.valueOf(SKU_B), "5");
        assertGroupQty(data, String.valueOf(SKU_C), "30");
    }

    @Test
    @DisplayName("GET ?groupBy=warehouse：两仓聚合正确（WH1=35、WH2=30）")
    void getGroupByWarehouseAggregates() throws Exception {
        JsonNode data = objectMapper.readTree(
                get("/api/v1/sales/daily?groupBy=warehouse", ownerToken()).getBody()).get("data");
        assertGroupQty(data, String.valueOf(WAREHOUSE_1), "35");
        assertGroupQty(data, String.valueOf(WAREHOUSE_2), "30");
    }

    @Test
    @DisplayName("GET ?groupBy=date：两日聚合正确（10-01=45、10-02=20）")
    void getGroupByDateAggregates() throws Exception {
        JsonNode data = objectMapper.readTree(
                get("/api/v1/sales/daily?groupBy=date", ownerToken()).getBody()).get("data");
        assertGroupQty(data, "2026-10-01", "45");
        assertGroupQty(data, "2026-10-02", "20");
    }

    // =====================================================================
    // GET —— 参数校验（COM-0007）
    // =====================================================================

    @Test
    @DisplayName("GET ?pageSize=201 → 400 COM-0007（不静默夹取）")
    void getPageSizeTooLargeRejected() throws Exception {
        ResponseEntity<String> resp = get("/api/v1/sales/daily?pageSize=201", ownerToken());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("COM-0007", errorCode(resp));
    }

    @Test
    @DisplayName("GET ?sort=hacked,asc → 400 COM-0007（排序白名单）")
    void getInvalidSortRejected() throws Exception {
        ResponseEntity<String> resp = get("/api/v1/sales/daily?sort=hacked,asc", ownerToken());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("COM-0007", errorCode(resp));
    }

    @Test
    @DisplayName("GET dateFrom > dateTo → 400 COM-0007")
    void getInvalidDateRangeRejected() throws Exception {
        ResponseEntity<String> resp = get(
                "/api/v1/sales/daily?dateFrom=2026-10-10&dateTo=2026-10-01", ownerToken());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("COM-0007", errorCode(resp));
    }

    @Test
    @DisplayName("GET ?groupBy=product（非法维度）→ 400 COM-0007")
    void getInvalidGroupByRejected() throws Exception {
        ResponseEntity<String> resp = get("/api/v1/sales/daily?groupBy=product", ownerToken());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("COM-0007", errorCode(resp));
    }

    // =====================================================================
    // GET —— 鉴权 / 角色
    // =====================================================================

    @Test
    @DisplayName("GET 未带令牌 → 401")
    void getRequiresAuth() {
        ResponseEntity<String> resp = get("/api/v1/sales/daily", null);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("GET VIEWER → 200（读端点对任何已登录成员开放）")
    void getViewerAllowed() throws Exception {
        ResponseEntity<String> resp = get("/api/v1/sales/daily", viewerToken());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "VIEWER 应可读：" + resp.getBody());
    }

    @Test
    @DisplayName("GET ?sort=sale_date,asc → 200（合法排序白名单）")
    void getValidSortAllowed() throws Exception {
        ResponseEntity<String> resp = get("/api/v1/sales/daily?sort=sale_date,asc", ownerToken());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode list = objectMapper.readTree(resp.getBody()).get("data").get("list");
        assertEquals("2026-10-01", list.get(0).get("sale_date").asText(), "升序首行应为 10-01");
    }

    // =====================================================================
    // 审计
    // =====================================================================

    @Test
    @DisplayName("POST upsert 写 SALES_DAILY_UPSERT 审计（change_summary 含覆盖行数）")
    void postWritesUpsertAudit() throws Exception {
        String body = "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_1
                + ",\"sale_date\":\"2026-10-06\",\"qty\":\"2\"}";
        assertEquals(HttpStatus.CREATED, postDaily(operatorToken(), body).getStatusCode());

        assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE action = 'SALES_DAILY_UPSERT'", Long.class),
                "手工写入应写 1 条 SALES_DAILY_UPSERT 审计");
        String summary = jdbc.queryForObject(
                "SELECT change_summary FROM audit_logs WHERE action = 'SALES_DAILY_UPSERT'", String.class);
        assertTrue(summary != null && summary.contains("覆盖"), "change_summary 应记录覆盖行数：" + summary);
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private void assertGroupQty(JsonNode data, String groupKey, String expectedQty) {
        for (JsonNode n : data.get("list")) {
            if (groupKey.equals(n.get("group_key").asText())) {
                assertEquals(0, new BigDecimal(expectedQty).compareTo(new BigDecimal(n.get("qty").asText())),
                        "group_key=" + groupKey + " 的 qty 应为 " + expectedQty + "，实测 " + n.get("qty").asText());
                return;
            }
        }
        throw new AssertionError("聚合结果中找不到 group_key=" + groupKey + "，实际：" + data.get("list"));
    }

    private String errorCode(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readTree(resp.getBody()).get("code").asText();
    }

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, username);
    }

    private String ownerToken() throws Exception {
        return loginAccessToken("owner01");
    }

    private String adminToken() throws Exception {
        return loginAccessToken("admin01");
    }

    private String operatorToken() throws Exception {
        return loginAccessToken("operator01");
    }

    private String viewerToken() throws Exception {
        return loginAccessToken("viewer01");
    }

    private String approverToken() throws Exception {
        return loginAccessToken("approver01");
    }

    private String loginAccessToken(String username) throws Exception {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + username + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private ResponseEntity<String> postDaily(String bearer, String jsonBody) {
        return post("/api/v1/sales/daily", jsonBody, bearer, null);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, entity(null, bearer, null), String.class);
    }

    private ResponseEntity<String> post(String path, String jsonBody, String bearer, String idemKey) {
        return rest.exchange(path, HttpMethod.POST, entity(jsonBody, bearer, idemKey), String.class);
    }

    private HttpEntity<String> entity(String jsonBody, String bearer, String idemKey) {
        HttpHeaders headers = new HttpHeaders();
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        if (idemKey != null) {
            headers.set("Idempotency-Key", idemKey);
        }
        return new HttpEntity<>(jsonBody, headers);
    }
}
