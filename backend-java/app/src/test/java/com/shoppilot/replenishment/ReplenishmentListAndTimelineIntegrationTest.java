package com.shoppilot.replenishment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-T07 建议列表 / 审核时间线 —— 端到端集成测试（真实 MySQL + 真实安全链）。
 *
 * <p>覆盖 D1–D11：分页计数、筛选（单条件/组合）、排序（含 id desc tie-breaker）、
 * 非法参数一律 COM-0007、空结果与越界翻页、<b>列表项与详情逐字一致</b>（回归闸）、
 * 响应字段是 {@code list} 不是 {@code items}、VIEWER 可读且他团队不可见、
 * 时间线升序与字段、空时间线/不存在/非法分页。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReplenishmentListAndTimelineIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM7 = 7L;
    private static final long TEAM8 = 8L;
    private static final long OP_ID = 1001L;
    private static final long VIEWER_ID = 1003L;

    private static final long PRODUCT7 = 9001L;
    private static final long PRODUCT8 = 9101L;
    private static final long SKU7 = 9002L;
    /** team7 的第二个 SKU：使同一 run 下能放两条建议（uk_repl_run_sku_wh 要求 run×sku×wh 唯一）。 */
    private static final long SKU7B = 9003L;
    private static final long SKU8 = 9102L;
    private static final long WH7 = 9004L;
    private static final long WH8 = 9104L;

    private static final String LIST_PATH = "/api/v1/replenishment/items";

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    private long runA;
    private long runB;
    private long runE;
    private long runC;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM replenishment_reviews");
        jdbc.execute("DELETE FROM replenishment_items");
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-LST-7', '列表组', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-LST-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE')",
                OP_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM7, OP_ID, TEAM7, VIEWER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-L7', '七团商品'), (?, ?, 'P-L8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-L7', '七团仓'), (?, ?, 'WH-L8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-L7', '七团SKU'), (?, ?, ?, 'SKU-L7B', '七团SKU-B'), "
                + "(?, ?, ?, 'SKU-L8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU7B, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);

        runA = insertRun(TEAM7, "RUN-L-A");
        runB = insertRun(TEAM7, "RUN-L-B");
        runE = insertRun(TEAM7, "RUN-L-E");
        runC = insertRun(TEAM8, "RUN-L-C");

        // 四条本团队建议（C 与 D 的 created_at/qty 完全相同 ⇒ 检验 id desc tie-breaker）
        insertItem(TEAM7, runA, SKU7, "SUGGESTED", "HIGH", "30.000", LocalDateTime.parse("2026-01-01T08:00:00"));
        insertItem(TEAM7, runB, SKU7, "PENDING_REVIEW", "LOW", "10.000", LocalDateTime.parse("2026-01-02T08:00:00"));
        insertItem(TEAM7, runE, SKU7, "APPROVED", "MEDIUM", "20.000", LocalDateTime.parse("2026-01-03T08:00:00"));
        insertItem(TEAM7, runE, SKU7B, "SUGGESTED", "NONE", "20.000", LocalDateTime.parse("2026-01-03T08:00:00"));
        // 他团队（对列表不可见）
        insertItem(TEAM8, runC, SKU8, "SUGGESTED", "HIGH", "99.000", LocalDateTime.parse("2026-01-04T08:00:00"));
    }

    // =====================================================================
    // D1：分页
    // =====================================================================

    @Test
    @DisplayName("D1 分页：total=4（筛选后总数，非当前页条数），list/page/pageSize 回显正确")
    void paginationCountsTotalNotPageSize() throws Exception {
        String token = login("operator01");

        JsonNode page1 = dataOf(get(LIST_PATH + "?page=1&pageSize=2", token));
        assertEquals(4, page1.get("total").asLong(), "total 必须是筛选后总数");
        assertEquals(2, page1.get("list").size());
        assertEquals(1, page1.get("page").asInt());
        assertEquals(2, page1.get("pageSize").asInt());

        JsonNode page2 = dataOf(get(LIST_PATH + "?page=2&pageSize=2", token));
        assertEquals(2, page2.get("list").size());
        assertEquals(4, page2.get("total").asLong());

        JsonNode page3 = dataOf(get(LIST_PATH + "?page=3&pageSize=2", token));
        assertEquals(0, page3.get("list").size(), "越界翻页 ⇒ 空列表");
        assertEquals(4, page3.get("total").asLong(), "越界翻页 total 仍为总数");
    }

    // =====================================================================
    // D2：筛选
    // =====================================================================

    @Test
    @DisplayName("D2 筛选：status / risk_level / run_id 单条件与组合，命中数与 total 正确")
    void filtersApplyToTotal() throws Exception {
        String token = login("operator01");

        assertEquals(2, totalOf(LIST_PATH + "?status=SUGGESTED", token));
        assertEquals(1, totalOf(LIST_PATH + "?risk_level=LOW", token));
        assertEquals(1, totalOf(LIST_PATH + "?run_id=" + runA, token));
        assertEquals(1, totalOf(LIST_PATH + "?run_id=" + runB, token));
        assertEquals(2, totalOf(LIST_PATH + "?run_id=" + runE, token));
        // 组合：runE + SUGGESTED ⇒ 1（D）；runE + SUGGESTED + NONE ⇒ 1
        assertEquals(1, totalOf(LIST_PATH + "?run_id=" + runE + "&status=SUGGESTED", token));
        assertEquals(1, totalOf(LIST_PATH + "?run_id=" + runE + "&status=SUGGESTED&risk_level=NONE", token));
        // 驼峰别名同样可用（契约 §2 查询参数小驼峰）
        assertEquals(1, totalOf(LIST_PATH + "?riskLevel=LOW", token));
        assertEquals(1, totalOf(LIST_PATH + "?runId=" + runA, token));
    }

    // =====================================================================
    // D3：排序
    // =====================================================================

    @Test
    @DisplayName("D3 排序：created_at,desc / qty,asc 顺序正确；id desc tie-breaker 不改主排序")
    void sortingIsWhitelistedAndStable() throws Exception {
        String token = login("operator01");

        JsonNode descData = dataOf(get(LIST_PATH + "?sort=created_at,desc&pageSize=50", token));
        assertEquals(4, descData.get("list").size());
        // created_at desc ⇒ D(20), C(20), B(10), A(30)（C/D 同 created_at ⇒ id desc）
        assertEquals(List.of("20.000", "20.000", "10.000", "30.000"), qtysOf(descData));
        List<Long> byCreatedDesc = idsOf(descData);
        assertTrue(byCreatedDesc.get(0) > byCreatedDesc.get(1), "同 created_at 按 id desc（D 在 C 前）");

        JsonNode ascData = dataOf(get(LIST_PATH + "?sort=qty,asc&pageSize=50", token));
        assertEquals(4, ascData.get("list").size());
        // qty asc ⇒ B(10), D(20), C(20), A(30)
        assertEquals(List.of("10.000", "20.000", "20.000", "30.000"), qtysOf(ascData));
        List<Long> byQtyAsc = idsOf(ascData);
        assertTrue(byQtyAsc.get(1) > byQtyAsc.get(2), "qty 相同按 id desc（D 在 C 前）");

        System.out.println("[M5T07-EVIDENCE] sort created_at,desc qtys=" + qtysOf(descData)
                + " ids=" + byCreatedDesc + " | sort qty,asc qtys=" + qtysOf(ascData)
                + " ids=" + byQtyAsc);
    }

    // =====================================================================
    // D4：非法参数
    // =====================================================================

    @Test
    @DisplayName("D4 非法参数：page/pageSize/status/risk_level/sort 全部 COM-0007(400)")
    void invalidParametersReturn0007() throws Exception {
        String token = login("operator01");
        for (String query : List.of(
                "page=0",
                "pageSize=0",
                "pageSize=201",
                "status=BAD",
                "risk_level=BAD",
                "sort=unknown_field,asc",
                "sort=created_at,xxx")) {
            ResponseEntity<String> res = get(LIST_PATH + "?" + query, token);
            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode(), query);
            assertEquals("COM-0007", json.readTree(res.getBody()).get("code").asText(), query);
        }
    }

    // =====================================================================
    // D5：空结果
    // =====================================================================

    @Test
    @DisplayName("D5 空结果：无命中 ⇒ 空 list + total=0，不报错")
    void emptyResultIsNotAnError() throws Exception {
        String token = login("operator01");
        JsonNode data = dataOf(get(LIST_PATH + "?status=SUPERSEDED", token));
        assertEquals(0, data.get("total").asLong());
        assertEquals(0, data.get("list").size());
    }

    // =====================================================================
    // D6/D7：与详情同构 + 响应形状
    // =====================================================================

    @Test
    @DisplayName("D6/D7 列表项与详情逐字一致；data 内是 list 不是 items")
    void listItemsMatchDetailExactly() throws Exception {
        String token = login("operator01");
        JsonNode data = dataOf(get(LIST_PATH + "?pageSize=50", token));

        assertTrue(data.has("list"), "data 必须有 list");
        assertFalse(data.has("items"), "★ data 不得出现 items（契约 §2.2 用 list）");
        assertEquals(4, data.get("list").size());

        for (JsonNode item : data.get("list")) {
            long id = item.get("id").asLong();
            JsonNode detail = dataOf(get(LIST_PATH + "/" + id, token));
            assertEquals(detail, item, "★ 列表项与详情必须逐字同构（id=" + id + "）");
        }

        JsonNode first = data.get("list").get(0);
        JsonNode firstDetail = dataOf(get(LIST_PATH + "/" + first.get("id").asLong(), token));
        System.out.println("[M5T07-EVIDENCE] list[0] == detail: " + first + " | detail: " + firstDetail);
    }

    // =====================================================================
    // D8：权限与隔离
    // =====================================================================

    @Test
    @DisplayName("D8 VIEWER 可读（200）；他团队数据不可见（total 不含他团队）")
    void viewerCanReadAndOtherTeamIsInvisible() throws Exception {
        String viewerToken = login("viewer01");
        JsonNode data = dataOf(get(LIST_PATH + "?pageSize=50", viewerToken));
        assertEquals(4, data.get("total").asLong(), "他团队的建议不可见");
        for (JsonNode item : data.get("list")) {
            assertEquals(TEAM7, teamIdOf(item.get("id").asLong()), "列表只含本团队数据");
        }
    }

    // =====================================================================
    // D9：时间线
    // =====================================================================

    @Test
    @DisplayName("D9 时间线：3 条轨迹按 occurred_at 升序，字段逐项正确")
    void timelineIsAscendingWithCorrectFields() throws Exception {
        String token = login("operator01");
        long id = insertItemWithNewRun(TEAM7, SKU7, "SUGGESTED", "NONE", "40.000",
                LocalDateTime.parse("2026-02-01T08:00:00"));

        assertEquals(HttpStatus.OK, post(LIST_PATH + "/" + id + "/submit", null, token).getStatusCode());
        assertEquals(HttpStatus.OK, post(LIST_PATH + "/" + id + "/withdraw", null, token).getStatusCode());
        assertEquals(HttpStatus.OK, post(LIST_PATH + "/" + id + "/submit", null, token).getStatusCode());

        JsonNode data = dataOf(get(LIST_PATH + "/" + id + "/reviews", token));
        assertEquals(3, data.get("total").asLong());
        assertEquals(3, data.get("list").size());

        JsonNode first = data.get("list").get(0);
        JsonNode second = data.get("list").get(1);
        JsonNode third = data.get("list").get(2);
        assertEquals("SUBMIT", first.get("action").asText());
        assertEquals("SUGGESTED", first.get("from_status").asText());
        assertEquals("PENDING_REVIEW", first.get("to_status").asText());
        assertEquals("WITHDRAW", second.get("action").asText());
        assertEquals("PENDING_REVIEW", second.get("from_status").asText());
        assertEquals("SUGGESTED", second.get("to_status").asText());
        assertEquals("SUBMIT", third.get("action").asText());

        assertTrue(first.get("occurred_at").asText().compareTo(second.get("occurred_at").asText()) <= 0);
        assertTrue(second.get("occurred_at").asText().compareTo(third.get("occurred_at").asText()) <= 0);

        assertEquals("40.000", first.get("qty_before").asText());
        assertEquals("40.000", first.get("qty_after").asText());
        assertEquals(OP_ID, first.get("reviewer_id").asLong());
        assertEquals("OPERATOR", first.get("reviewer_role").asText());
        assertNotNull(first.get("request_id"));
        assertNotNull(first.get("created_at"));
        assertEquals(id, first.get("replenishment_item_id").asLong());

        System.out.println("[M5T07-EVIDENCE] timeline: "
                + first.get("action").asText() + " " + first.get("from_status").asText() + "->"
                + first.get("to_status").asText() + " at=" + first.get("occurred_at").asText()
                + " | " + second.get("action").asText() + " " + second.get("from_status").asText() + "->"
                + second.get("to_status").asText() + " at=" + second.get("occurred_at").asText()
                + " | " + third.get("action").asText() + " " + third.get("from_status").asText() + "->"
                + third.get("to_status").asText() + " at=" + third.get("occurred_at").asText());
    }

    // =====================================================================
    // D10/D11：时间线边界
    // =====================================================================

    @Test
    @DisplayName("D10 时间线：无记录 ⇒ 空 list+total=0；不存在/跨团队 ⇒ RPL-8004；VIEWER 可读")
    void timelineEdgeCases() throws Exception {
        String token = login("operator01");
        long id = insertItemWithNewRun(TEAM7, SKU7, "SUGGESTED", "NONE", "40.000",
                LocalDateTime.parse("2026-02-01T08:00:00"));

        JsonNode empty = dataOf(get(LIST_PATH + "/" + id + "/reviews", token));
        assertEquals(0, empty.get("total").asLong());
        assertEquals(0, empty.get("list").size());

        ResponseEntity<String> missing = get(LIST_PATH + "/99999999/reviews", token);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("RPL-8004", json.readTree(missing.getBody()).get("code").asText());

        long otherTeam = insertItemWithNewRun(TEAM8, SKU8, "SUGGESTED", "NONE", "40.000",
                LocalDateTime.parse("2026-02-01T08:00:00"));
        ResponseEntity<String> cross = get(LIST_PATH + "/" + otherTeam + "/reviews", token);
        assertEquals(HttpStatus.NOT_FOUND, cross.getStatusCode());
        assertEquals("RPL-8004", json.readTree(cross.getBody()).get("code").asText());

        assertEquals(HttpStatus.OK,
                get(LIST_PATH + "/" + id + "/reviews", login("viewer01")).getStatusCode());
    }

    @Test
    @DisplayName("D11 时间线非法分页参数 ⇒ COM-0007")
    void timelineInvalidPagination() throws Exception {
        String token = login("operator01");
        long id = insertItemWithNewRun(TEAM7, SKU7, "SUGGESTED", "NONE", "40.000",
                LocalDateTime.parse("2026-02-01T08:00:00"));
        for (String query : List.of("page=0", "pageSize=0", "pageSize=201", "sort=bogus,asc")) {
            ResponseEntity<String> res = get(LIST_PATH + "/" + id + "/reviews?" + query, token);
            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode(), query);
            assertEquals("COM-0007", json.readTree(res.getBody()).get("code").asText(), query);
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private long insertRun(long teamId, String runNo) {
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, algorithm_version) "
                + "VALUES (?, ?, 'SUCCEEDED', 'ALL_ACTIVE', 'avg-ma-3sigma-v1')", teamId, runNo);
        return jdbc.queryForObject("SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);
    }

    private long insertItem(long teamId, long runId, long sku, String status, String riskLevel,
                            String qty, LocalDateTime createdAt) {
        long product = teamId == TEAM7 ? PRODUCT7 : PRODUCT8;
        long wh = teamId == TEAM7 ? WH7 : WH8;
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, qty_suggest, "
                        + "safety_stock_qty) VALUES (?, ?, ?, ?, ?, 8.000, 'HIGH', 'SKU', 6.250, 104.500, "
                        + "110.000, 25.000)",
                teamId, runId, sku, product, wh);
        long sourceId = jdbc.queryForObject(
                "SELECT id FROM analysis_items WHERE run_id = ? AND team_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, runId, teamId);
        jdbc.update("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                        + "product_id, warehouse_id, status, suggested_qty, qty, risk_level, version, "
                        + "created_at, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
                teamId, sourceId, runId, sku, product, wh, status,
                new java.math.BigDecimal(qty), new java.math.BigDecimal(qty), riskLevel,
                createdAt, OP_ID, OP_ID);
        return jdbc.queryForObject("SELECT id FROM replenishment_items WHERE team_id = ? AND run_id = ? "
                + "ORDER BY id DESC LIMIT 1", Long.class, teamId, runId);
    }

    /** 每次新建独立 run（保证 uk_repl_run_sku_wh 唯一）。 */
    private long insertItemWithNewRun(long teamId, long sku, String status, String riskLevel, String qty,
                                      LocalDateTime createdAt) {
        long runId = insertRun(teamId, "RUN-L-X" + System.nanoTime());
        return insertItem(teamId, runId, sku, status, riskLevel, qty, createdAt);
    }

    private long teamIdOf(long itemId) {
        return jdbc.queryForObject("SELECT team_id FROM replenishment_items WHERE id = ?", Long.class, itemId);
    }

    private JsonNode dataOf(ResponseEntity<String> res) throws Exception {
        return json.readTree(res.getBody()).get("data");
    }

    private long totalOf(String path, String token) throws Exception {
        return dataOf(get(path, token)).get("total").asLong();
    }

    private List<String> qtysOf(JsonNode data) {
        List<String> qtys = new ArrayList<>();
        for (JsonNode item : data.get("list")) {
            qtys.add(item.get("qty").asText());
        }
        return qtys;
    }

    private List<Long> idsOf(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : data.get("list")) {
            ids.add(item.get("id").asLong());
        }
        return ids;
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String login(String username) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}",
                        headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + response.getBody());
        return json.readTree(response.getBody()).get("data").get("accessToken").asText();
    }
}
