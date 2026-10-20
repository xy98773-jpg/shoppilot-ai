package com.shoppilot.replenishment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.replenishment.application.ReplenishmentItemService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-T04 提交 / 撤回 —— 端到端集成测试（真实 MySQL + 真实安全链 + 真实事务）。
 *
 * <p>覆盖 E1–E9：提交成功（三步同事务：items + reviews + audit）、重复提交 409、
 * 五态状态守卫、撤回（{@code submitted_at} 不清空）、反复流转三条轨迹、
 * <b>并发判因</b>（陈旧读 ⇒ 8002 而非 8003）、跨团队 404、VIEWER 403、comment 边界。
 *
 * <p>★ E7 用 REPEATABLE READ 快照 + 独立连接提交构造确定性陈旧读，不依赖线程调度。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReplenishmentReviewFlowIntegrationTest {

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
    private static final long SKU8 = 9102L;
    private static final long WH7 = 9004L;
    private static final long WH8 = 9104L;

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
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private ReplenishmentItemService itemService;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    private int runSeq;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM replenishment_reviews");
        jdbc.execute("DELETE FROM replenishment_items");
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");
        runSeq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-FLOW-7', '流转组', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-FLOW-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE')",
                OP_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM7, OP_ID, TEAM7, VIEWER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-F7', '七团商品'), (?, ?, 'P-F8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-F7', '七团仓'), (?, ?, 'WH-F8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-F7', '七团SKU'), (?, ?, ?, 'SKU-F8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);
    }

    // =====================================================================
    // E1：提交成功（三步同事务）
    // =====================================================================

    @Test
    @DisplayName("E1 submit 成功：状态流转 + submitted_at + version+1 + reviews 1 条 + 审计 1 条 + review_id 回填")
    void submitSucceeds() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "80.000", 0, null);
        String token = login("operator01");

        ResponseEntity<String> res = post("/api/v1/replenishment/items/" + id + "/submit",
                "{\"comment\":\"已核对，请审批\"}", token, "req_m5t04_e1");
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals(id, data.get("replenishment_item_id").asLong());
        assertEquals("PENDING_REVIEW", data.get("status").asText());
        assertEquals("80.000", data.get("qty").asText(), "qty 必须是字符串 scale=3");
        assertEquals("SUBMIT", data.get("action").asText());
        assertEquals("SUGGESTED", data.get("from_status").asText());
        assertEquals("PENDING_REVIEW", data.get("to_status").asText());
        assertNotNull(data.get("submitted_at"));
        long reviewId = data.get("review_id").asLong();

        Map<String, Object> item = jdbc.queryForMap(
                "SELECT status, submitted_at, version FROM replenishment_items WHERE id = ?", id);
        assertEquals("PENDING_REVIEW", item.get("status"));
        assertNotNull(item.get("submitted_at"), "submitted_at 必须写入");
        assertEquals(1, ((Number) item.get("version")).intValue(), "version 应 +1");

        Map<String, Object> review = jdbc.queryForMap(
                "SELECT id, action, from_status, to_status, comment, qty_before, qty_after, reviewer_id, "
                        + "reviewer_role, request_id FROM replenishment_reviews WHERE replenishment_item_id = ?", id);
        assertEquals(reviewId, ((Number) review.get("id")).longValue(), "响应 review_id 必须是 reviews 主键");
        assertEquals("SUBMIT", review.get("action"));
        assertEquals("SUGGESTED", review.get("from_status"));
        assertEquals("PENDING_REVIEW", review.get("to_status"));
        assertEquals("已核对，请审批", review.get("comment"));
        assertEquals(0, new java.math.BigDecimal("80.000")
                .compareTo((java.math.BigDecimal) review.get("qty_before")), "qty_before = 当前 qty");
        assertEquals(0, new java.math.BigDecimal("80.000")
                .compareTo((java.math.BigDecimal) review.get("qty_after")), "qty_after = 当前 qty");
        assertEquals(OP_ID, ((Number) review.get("reviewer_id")).longValue());
        assertEquals("OPERATOR", review.get("reviewer_role"), "★ 角色快照，不是 JOIN");
        assertEquals("req_m5t04_e1", review.get("request_id"), "request_id 取 X-Request-Id");

        assertEquals(1L, auditCount(id, "REPLENISHMENT_SUBMIT"));

        System.out.println("[M5T04-EVIDENCE] after submit: " + itemRow(id));
        System.out.println("[M5T04-EVIDENCE] reviews     : " + reviewsOf(id));
    }

    // =====================================================================
    // E2：重复提交
    // =====================================================================

    @Test
    @DisplayName("E2 重复 submit：409 RPL-8002（不是 8001），reviews 不新增")
    void duplicateSubmitReturns8002() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "80.000", 0, null);
        String token = login("operator01");

        assertEquals(HttpStatus.OK, post("/api/v1/replenishment/items/" + id + "/submit", null, token, null)
                .getStatusCode());
        System.out.println("[M5T04-EVIDENCE] after 1st submit: " + itemRow(id));

        ResponseEntity<String> second = post("/api/v1/replenishment/items/" + id + "/submit", null, token, null);
        System.out.println("[M5T04-EVIDENCE] 2nd submit HTTP : " + second.getStatusCode().value()
                + " " + second.getBody());
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals("RPL-8002", json.readTree(second.getBody()).get("code").asText());
        assertEquals(1L, reviewCount(id), "重复提交不得新增 review");

        System.out.println("[M5T04-EVIDENCE] after 2nd submit: " + itemRow(id));
    }

    // =====================================================================
    // E3：submit 状态守卫
    // =====================================================================

    @Test
    @DisplayName("E3 submit 状态守卫：APPROVED/CONVERTED/REJECTED/CANCELLED/SUPERSEDED ⇒ 422 RPL-8001")
    void submitRejectsNonSuggestedStatus() throws Exception {
        String token = login("operator01");
        for (String status : List.of("APPROVED", "CONVERTED", "REJECTED", "CANCELLED", "SUPERSEDED")) {
            long id = createItem(TEAM7, status, "10.000", 0, null);
            ResponseEntity<String> res = post("/api/v1/replenishment/items/" + id + "/submit", null, token, null);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, res.getStatusCode(), "status=" + status);
            assertEquals("RPL-8001", json.readTree(res.getBody()).get("code").asText());
            assertEquals(0L, reviewCount(id), "被拒时不得写 review：status=" + status);
        }
    }

    // =====================================================================
    // E4/E5：撤回
    // =====================================================================

    @Test
    @DisplayName("E4 withdraw 成功：PENDING_REVIEW→SUGGESTED，submitted_at 保持原值，reviews/审计各 1")
    void withdrawSucceeds() throws Exception {
        LocalDateTime original = LocalDateTime.of(2026, 10, 19, 3, 30, 0, 123000000);
        long id = createItem(TEAM7, "PENDING_REVIEW", "50.000", 3, original);
        String token = login("operator01");

        ResponseEntity<String> res = post("/api/v1/replenishment/items/" + id + "/withdraw",
                "{\"comment\":\"先撤回改量\"}", token, null);
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("SUGGESTED", data.get("status").asText());
        assertEquals("WITHDRAW", data.get("action").asText());
        assertEquals("PENDING_REVIEW", data.get("from_status").asText());
        assertEquals("SUGGESTED", data.get("to_status").asText());
        assertNotNull(data.get("submitted_at"));

        LocalDateTime stored = jdbc.queryForObject(
                "SELECT submitted_at FROM replenishment_items WHERE id = ?", LocalDateTime.class, id);
        assertEquals(original, stored, "★ 撤回不得清空 submitted_at");
        assertEquals(4, jdbc.queryForObject(
                "SELECT version FROM replenishment_items WHERE id = ?", Integer.class, id),
                "种子 version=3 ⇒ 撤回后 4");

        Map<String, Object> review = jdbc.queryForMap(
                "SELECT action, from_status, to_status, comment FROM replenishment_reviews "
                        + "WHERE replenishment_item_id = ?", id);
        assertEquals("WITHDRAW", review.get("action"));
        assertEquals("PENDING_REVIEW", review.get("from_status"));
        assertEquals("SUGGESTED", review.get("to_status"));
        assertEquals("先撤回改量", review.get("comment"));
        assertEquals(1L, auditCount(id, "REPLENISHMENT_WITHDRAW"));

        System.out.println("[M5T04-EVIDENCE] after withdraw: " + itemRow(id));
        System.out.println("[M5T04-EVIDENCE] reviews      : " + reviewsOf(id));
    }

    @Test
    @DisplayName("E5 withdraw 非法：SUGGESTED/APPROVED/CANCELLED ⇒ 422 RPL-8001")
    void withdrawRejectsNonPendingStatus() throws Exception {
        String token = login("operator01");
        for (String status : List.of("SUGGESTED", "APPROVED", "CANCELLED")) {
            long id = createItem(TEAM7, status, "10.000", 0, null);
            ResponseEntity<String> res = post("/api/v1/replenishment/items/" + id + "/withdraw", null, token, null);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, res.getStatusCode(), "status=" + status);
            assertEquals("RPL-8001", json.readTree(res.getBody()).get("code").asText());
            assertEquals(0L, reviewCount(id));
        }
    }

    // =====================================================================
    // E6：反复流转
    // =====================================================================

    @Test
    @DisplayName("E6 submit→withdraw→submit：reviews 共 3 条，终态 PENDING_REVIEW")
    void repeatedFlowLeavesThreeReviews() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "30.000", 0, null);
        String token = login("operator01");
        String base = "/api/v1/replenishment/items/" + id;

        assertEquals(HttpStatus.OK, post(base + "/submit", null, token, null).getStatusCode());
        assertEquals(HttpStatus.OK, post(base + "/withdraw", null, token, null).getStatusCode());
        assertEquals(HttpStatus.OK, post(base + "/submit", null, token, null).getStatusCode());

        assertEquals(3L, reviewCount(id), "每一步都必须留痕");
        assertEquals("PENDING_REVIEW", jdbc.queryForObject(
                "SELECT status FROM replenishment_items WHERE id = ?", String.class, id));
        assertEquals(3, jdbc.queryForObject(
                "SELECT version FROM replenishment_items WHERE id = ?", Integer.class, id));
        System.out.println("[M5T04-EVIDENCE] flow reviews: " + reviewsOf(id));
    }

    // =====================================================================
    // E7：并发判因（确定性陈旧读）
    // =====================================================================

    @Test
    @DisplayName("E7 并发判因：陈旧实体提交 ⇒ 重查判因为 RPL-8002（非 8003），reviews 不新增")
    void staleSubmitIsReportedAsDuplicate() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "10.000", 0, null);
        // ★ 直接调用 Service（不经 HTTP）时必须手工设团队上下文：租户线缺失即拒绝
        com.shoppilot.common.context.TeamContext.set(
                new com.shoppilot.common.context.TeamContext.Snapshot(
                        TEAM7, List.of("OPERATOR"), OP_ID, "operator01"));
        TransactionTemplate tx = new TransactionTemplate(txManager);

        BizException ex = tx.execute(status -> {
            // ① 外层事务先读，固定 REPEATABLE READ 快照（version=0 / SUGGESTED）
            assertEquals("SUGGESTED", jdbc.queryForObject(
                    "SELECT status FROM replenishment_items WHERE id = ?", String.class, id));

            // ② 另一连接提交「并发提交」：状态 + 版本 + 一条 review（模拟另一请求已成功）
            commitConcurrentSubmit(id, "req_concurrent_winner");

            // ③ 本事务内服务层仍看到旧快照 ⇒ update 0 行 ⇒ 锁定读判因 ⇒ RPL-8002
            BizException thrown = assertThrows(BizException.class, () -> itemService.submit(id, null));
            status.setRollbackOnly();
            return thrown;
        });
        com.shoppilot.common.context.TeamContext.clear();

        assertNotNull(ex);
        assertEquals(ErrorCodes.RPL_8002, ex.getErrorCode(), "★ 必须判因为重复提交，而不是 8003");
        assertEquals(1L, reviewCount(id), "失败方不得新增 review");
        assertEquals("PENDING_REVIEW", jdbc.queryForObject(
                "SELECT status FROM replenishment_items WHERE id = ?", String.class, id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM replenishment_items WHERE id = ?", Integer.class, id));
    }

    // =====================================================================
    // E8/E9：权限、跨团队、comment 边界
    // =====================================================================

    @Test
    @DisplayName("E8 跨团队 ⇒ 404 RPL-8004；VIEWER submit ⇒ 403")
    void crossTeamAndViewerAreRejected() throws Exception {
        long otherId = createItem(TEAM8, "SUGGESTED", "10.000", 0, null);
        String operatorToken = login("operator01");
        ResponseEntity<String> cross = post("/api/v1/replenishment/items/" + otherId + "/submit",
                null, operatorToken, null);
        assertEquals(HttpStatus.NOT_FOUND, cross.getStatusCode());
        assertEquals("RPL-8004", json.readTree(cross.getBody()).get("code").asText());

        long id = createItem(TEAM7, "SUGGESTED", "10.000", 0, null);
        String viewerToken = login("viewer01");
        ResponseEntity<String> denied = post("/api/v1/replenishment/items/" + id + "/submit",
                null, viewerToken, null);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), "VIEWER 不得提交");
        assertEquals(0L, reviewCount(id));
    }

    @Test
    @DisplayName("E9 comment 500 通过、501 ⇒ COM-0001(field=comment)、不传 ⇒ reviews.comment IS NULL")
    void commentBoundary() throws Exception {
        String token = login("operator01");

        long okId = createItem(TEAM7, "SUGGESTED", "10.000", 0, null);
        String exactly500 = "x".repeat(500);
        ResponseEntity<String> ok = post("/api/v1/replenishment/items/" + okId + "/submit",
                "{\"comment\":\"" + exactly500 + "\"}", token, null);
        assertEquals(HttpStatus.OK, ok.getStatusCode(), "500 字符应通过");

        long tooLongId = createItem(TEAM7, "SUGGESTED", "10.000", 0, null);
        String tooLong501 = "x".repeat(501);
        ResponseEntity<String> bad = post("/api/v1/replenishment/items/" + tooLongId + "/submit",
                "{\"comment\":\"" + tooLong501 + "\"}", token, null);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        JsonNode badBody = json.readTree(bad.getBody());
        assertEquals("COM-0001", badBody.get("code").asText());
        assertEquals("comment", badBody.get("details").get(0).get("field").asText());
        assertEquals(0L, reviewCount(tooLongId));

        long nullId = createItem(TEAM7, "SUGGESTED", "10.000", 0, null);
        assertEquals(HttpStatus.OK, post("/api/v1/replenishment/items/" + nullId + "/submit",
                null, token, null).getStatusCode());
        Map<String, Object> review = jdbc.queryForMap(
                "SELECT comment FROM replenishment_reviews WHERE replenishment_item_id = ?", nullId);
        assertNull(review.get("comment"), "不传 comment ⇒ NULL");
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private long createItem(long teamId, String status, String qty, int version, LocalDateTime submittedAt) {
        boolean team7 = teamId == TEAM7;
        long sku = team7 ? SKU7 : SKU8;
        long product = team7 ? PRODUCT7 : PRODUCT8;
        long wh = team7 ? WH7 : WH8;
        String runNo = "RUN-FLOW-" + teamId + "-" + (++runSeq);

        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, algorithm_version) "
                + "VALUES (?, ?, 'SUCCEEDED', 'ALL_ACTIVE', 'avg-ma-3sigma-v1')", teamId, runNo);
        long runId = jdbc.queryForObject("SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, qty_suggest, "
                        + "safety_stock_qty) VALUES (?, ?, ?, ?, ?, 8.000, 'HIGH', 'SKU', 6.250, 104.500, "
                        + "110.000, 25.000)",
                teamId, runId, sku, product, wh);
        long sourceId = jdbc.queryForObject(
                "SELECT id FROM analysis_items WHERE run_id = ?", Long.class, runId);

        jdbc.update("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                        + "product_id, warehouse_id, status, suggested_qty, qty, risk_level, version, "
                        + "submitted_at, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'HIGH', ?, ?, ?, ?)",
                teamId, sourceId, runId, sku, product, wh, status,
                new java.math.BigDecimal(qty), new java.math.BigDecimal(qty), version,
                submittedAt, OP_ID, OP_ID);
        return jdbc.queryForObject("SELECT id FROM replenishment_items WHERE run_id = ?", Long.class, runId);
    }

    /** 独立连接提交（模拟并发者已成功提交并留下轨迹）。 */
    private void commitConcurrentSubmit(long id, String requestId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE replenishment_items SET status = 'PENDING_REVIEW', submitted_at = NOW(3), "
                            + "version = version + 1 WHERE id = ?")) {
                ps.setLong(1, id);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO replenishment_reviews (team_id, replenishment_item_id, action, from_status, "
                            + "to_status, qty_before, qty_after, reviewer_id, reviewer_role, request_id, "
                            + "occurred_at, created_at) VALUES (?, ?, 'SUBMIT', 'SUGGESTED', 'PENDING_REVIEW', "
                            + "10.000, 10.000, ?, 'OPERATOR', ?, NOW(3), NOW(3))")) {
                ps.setLong(1, TEAM7);
                ps.setLong(2, id);
                ps.setLong(3, OP_ID);
                ps.setString(4, requestId);
                assertEquals(1, ps.executeUpdate());
            }
        } catch (Exception e) {
            throw new IllegalStateException("并发提交构造失败", e);
        }
    }

    private long reviewCount(long itemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_reviews WHERE replenishment_item_id = ?",
                Long.class, itemId);
    }

    private long auditCount(long bizId, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE biz_id = ? "
                + "AND biz_type = 'REPLENISHMENT_ITEM' AND action = ?", Long.class, bizId, action);
    }

    private String itemRow(long id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, submitted_at, version FROM replenishment_items WHERE id = ?", id);
        return "id=" + id + " status=" + row.get("status")
                + " submitted_at=" + row.get("submitted_at") + " version=" + row.get("version");
    }

    /** V5 取证：审核轨迹逐行（ASCII 化，避免控制台编码问题）。 */
    private String reviewsOf(long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, action, from_status, to_status, comment, qty_before, qty_after, reviewer_role, "
                        + "request_id, occurred_at FROM replenishment_reviews WHERE replenishment_item_id = ? "
                        + "ORDER BY id", itemId);
        StringBuilder sb = new StringBuilder("count=" + rows.size());
        for (Map<String, Object> row : rows) {
            sb.append(" | id=").append(row.get("id"))
                    .append(" ").append(row.get("action"))
                    .append(" ").append(row.get("from_status")).append("->").append(row.get("to_status"))
                    .append(" qty=").append(row.get("qty_before")).append("/").append(row.get("qty_after"))
                    .append(" role=").append(row.get("reviewer_role"))
                    .append(" req=").append(row.get("request_id"))
                    .append(" at=").append(row.get("occurred_at"));
        }
        return sb.toString();
    }

    private ResponseEntity<String> post(String path, String body, String token, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (requestId != null) {
            headers.set("X-Request-Id", requestId);
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
