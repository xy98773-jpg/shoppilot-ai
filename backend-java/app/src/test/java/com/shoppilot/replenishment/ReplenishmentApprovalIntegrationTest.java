package com.shoppilot.replenishment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-T05 审批通过 / 驳回 —— 端到端集成测试（真实 MySQL + 真实安全链）。
 *
 * <p>覆盖 E1–E11：approve 成功（approved_at/by、version+1、review、审计）、重复审批 409、
 * 五态守卫、版本冲突 409（带 details）、reject 成功（closed_at 有值 / approved_at 为 NULL）、
 * 空白意见三种形态 400 RPL-8005、重复驳回 409、权限矩阵、<b>四眼原则回归闸</b>、
 * 全链路 submit→approve/reject、跨团队 404、expected_version 缺失。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReplenishmentApprovalIntegrationTest {

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
    private static final long APPROVER_ID = 1005L;

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
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");
        runSeq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-APR-7', '审批组', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-APR-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE'), "
                + "(?, 'approver01', 'approver01@example.com', ?, '小审', 'ACTIVE')",
                OP_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH, APPROVER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), (?, ?, 'APPROVER', 'ACTIVE')",
                TEAM7, OP_ID, TEAM7, VIEWER_ID, TEAM7, APPROVER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-A7', '七团商品'), (?, ?, 'P-A8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-A7', '七团仓'), (?, ?, 'WH-A8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-A7', '七团SKU'), (?, ?, ?, 'SKU-A8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);
    }

    // =====================================================================
    // E1/E2：approve
    // =====================================================================

    @Test
    @DisplayName("E1 approve 成功：APPROVED + approved_at/by + version+1 + reviews 1 + 审计 1 + review_id 回填")
    void approveSucceeds() throws Exception {
        long id = createItem(TEAM7, "PENDING_REVIEW", "80.000", 2, LocalDateTime.now());
        String token = login("approver01");

        ResponseEntity<String> res = post(base(id) + "/approve",
                body(2, "同意补货，按现有供应商执行"), token, "req_m5t05_e1");
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals(id, data.get("replenishment_item_id").asLong());
        assertEquals("APPROVED", data.get("status").asText());
        assertEquals("80.000", data.get("qty").asText());
        assertEquals(APPROVER_ID, data.get("approved_by").asLong());
        assertNotNull(data.get("approved_at"));
        assertEquals(3, data.get("version").asInt());
        assertEquals("APPROVE", data.get("action").asText());
        assertEquals("PENDING_REVIEW", data.get("from_status").asText());
        assertEquals("APPROVED", data.get("to_status").asText());
        long reviewId = data.get("review_id").asLong();

        Map<String, Object> item = jdbc.queryForMap(
                "SELECT status, approved_at, approved_by, closed_at, version FROM replenishment_items WHERE id = ?", id);
        assertEquals("APPROVED", item.get("status"));
        assertNotNull(item.get("approved_at"));
        assertEquals(APPROVER_ID, ((Number) item.get("approved_by")).longValue());
        assertNull(item.get("closed_at"), "★ approve 不写 closed_at（非终态）");
        assertEquals(3, ((Number) item.get("version")).intValue());

        Map<String, Object> review = jdbc.queryForMap(
                "SELECT id, action, from_status, to_status, comment, reviewer_role, qty_before, qty_after "
                        + "FROM replenishment_reviews WHERE replenishment_item_id = ?", id);
        assertEquals(reviewId, ((Number) review.get("id")).longValue());
        assertEquals("APPROVE", review.get("action"));
        assertEquals("APPROVER", review.get("reviewer_role"), "角色快照");
        assertEquals("同意补货，按现有供应商执行", review.get("comment"));
        assertEquals(0, new java.math.BigDecimal("80.000")
                .compareTo((java.math.BigDecimal) review.get("qty_before")));
        assertEquals(0, new java.math.BigDecimal("80.000")
                .compareTo((java.math.BigDecimal) review.get("qty_after")));

        assertEquals(1L, auditCount(id, "REPLENISHMENT_APPROVE"));
        System.out.println("[M5T05-EVIDENCE] after approve: " + itemRow(id));
    }

    @Test
    @DisplayName("E2 approve 重复：已 APPROVED 再 approve ⇒ 409 RPL-8002，reviews 不新增")
    void duplicateApproveReturns8002() throws Exception {
        long id = createItem(TEAM7, "PENDING_REVIEW", "10.000", 0, null);
        String token = login("approver01");

        assertEquals(HttpStatus.OK, post(base(id) + "/approve", body(0, null), token, null).getStatusCode());
        ResponseEntity<String> second = post(base(id) + "/approve", body(1, null), token, null);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode(), second.getBody());
        assertEquals("RPL-8002", json.readTree(second.getBody()).get("code").asText());
        assertEquals(1L, reviewCount(id));
    }

    // =====================================================================
    // E3/E4：approve 守卫与版本冲突
    // =====================================================================

    @Test
    @DisplayName("E3 approve 状态守卫：SUGGESTED/CONVERTED/CANCELLED/SUPERSEDED/REJECTED ⇒ 422 RPL-8001")
    void approveRejectsWrongStatus() throws Exception {
        String token = login("approver01");
        for (String status : List.of("SUGGESTED", "CONVERTED", "CANCELLED", "SUPERSEDED", "REJECTED")) {
            long id = createItem(TEAM7, status, "10.000", 0, null);
            ResponseEntity<String> res = post(base(id) + "/approve", body(0, null), token, null);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, res.getStatusCode(), "status=" + status);
            assertEquals("RPL-8001", json.readTree(res.getBody()).get("code").asText());
            assertEquals(0L, reviewCount(id));
        }
    }

    @Test
    @DisplayName("E4 版本冲突：expected_version 旧值 ⇒ 409 RPL-8003，details[0].field=expected_version")
    void staleExpectedVersionReturns8003() throws Exception {
        long id = createItem(TEAM7, "PENDING_REVIEW", "10.000", 5, null);
        String token = login("approver01");

        ResponseEntity<String> res = post(base(id) + "/approve", body(4, null), token, null);
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(), res.getBody());
        JsonNode body = json.readTree(res.getBody());
        assertEquals("RPL-8003", body.get("code").asText());
        JsonNode detail = body.get("details").get(0);
        assertEquals("expected_version", detail.get("field").asText());
        assertEquals("期望版本 4，实际版本 5", detail.get("message").asText());
        assertEquals(4, detail.get("rejectedValue").asInt());
        assertEquals(0L, reviewCount(id), "冲突时不得留痕");
        System.out.println("[M5T05-EVIDENCE] version conflict: " + res.getBody());
    }

    // =====================================================================
    // E5/E6/E7：reject
    // =====================================================================

    @Test
    @DisplayName("E5 reject 成功：REJECTED + closed_at 有值 + approved_at 为 NULL + reviews/审计各 1")
    void rejectSucceeds() throws Exception {
        long id = createItem(TEAM7, "PENDING_REVIEW", "50.000", 1, LocalDateTime.now());
        String token = login("approver01");

        ResponseEntity<String> res = post(base(id) + "/reject", body(1, "库存已充足，暂不补货"), token, null);
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("REJECTED", data.get("status").asText());
        assertNotNull(data.get("closed_at"));
        assertEquals("REJECT", data.get("action").asText());
        assertTrue(data.get("approved_at") == null || data.get("approved_at").isNull(),
                "驳回不得返回 approved_at");

        Map<String, Object> item = jdbc.queryForMap(
                "SELECT status, approved_at, approved_by, closed_at, version FROM replenishment_items WHERE id = ?", id);
        assertEquals("REJECTED", item.get("status"));
        assertNull(item.get("approved_at"), "★ 驳回不得写 approved_at");
        assertNull(item.get("approved_by"), "★ 驳回不得写 approved_by");
        assertNotNull(item.get("closed_at"), "★ 驳回写 closed_at");
        assertEquals(2, ((Number) item.get("version")).intValue());

        Map<String, Object> review = jdbc.queryForMap(
                "SELECT action, comment, from_status, to_status FROM replenishment_reviews "
                        + "WHERE replenishment_item_id = ?", id);
        assertEquals("REJECT", review.get("action"));
        assertEquals("库存已充足，暂不补货", review.get("comment"));
        assertEquals("PENDING_REVIEW", review.get("from_status"));
        assertEquals("REJECTED", review.get("to_status"));

        assertEquals(1L, auditCount(id, "REPLENISHMENT_REJECT"));
        System.out.println("[M5T05-EVIDENCE] after reject : " + itemRow(id));
    }

    @Test
    @DisplayName("E6 驳回未填意见：null/\"\"/\"  \" ⇒ 全部 400 RPL-8005（details.field=comment）")
    void rejectRequiresNonBlankComment() throws Exception {
        long id = createItem(TEAM7, "PENDING_REVIEW", "10.000", 0, null);
        String token = login("approver01");

        for (String payload : List.of("{\"expected_version\":0}",
                "{\"expected_version\":0,\"comment\":\"\"}",
                "{\"expected_version\":0,\"comment\":\"  \"}")) {
            ResponseEntity<String> res = post(base(id) + "/reject", payload, token, null);
            System.out.println("[M5T05-EVIDENCE] reject blank -> " + res.getStatusCode().value()
                    + " " + res.getBody());
            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode(), payload);
            JsonNode body = json.readTree(res.getBody());
            assertEquals("RPL-8005", body.get("code").asText());
            assertEquals("comment", body.get("details").get(0).get("field").asText());
        }
        assertEquals(0L, reviewCount(id), "被拒时不得落库");
        assertEquals(0, jdbc.queryForObject(
                "SELECT version FROM replenishment_items WHERE id = ?", Integer.class, id));
    }

    @Test
    @DisplayName("E7 reject 重复：已 REJECTED 再 reject ⇒ 409 RPL-8002")
    void duplicateRejectReturns8002() throws Exception {
        long id = createItem(TEAM7, "PENDING_REVIEW", "10.000", 0, null);
        String token = login("approver01");

        assertEquals(HttpStatus.OK, post(base(id) + "/reject", body(0, "不要"), token, null).getStatusCode());
        ResponseEntity<String> second = post(base(id) + "/reject", body(1, "再驳一次"), token, null);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode(), second.getBody());
        assertEquals("RPL-8002", json.readTree(second.getBody()).get("code").asText());
        assertEquals(1L, reviewCount(id));
    }

    // =====================================================================
    // E8/E9：权限与四眼原则
    // =====================================================================

    @Test
    @DisplayName("E8 权限：OPERATOR approve ⇒ 403；VIEWER reject ⇒ 403；APPROVER ⇒ 200")
    void permissionMatrix() throws Exception {
        long id1 = createItem(TEAM7, "PENDING_REVIEW", "10.000", 0, null);
        long id2 = createItem(TEAM7, "PENDING_REVIEW", "10.000", 0, null);

        ResponseEntity<String> byOperator = post(base(id1) + "/approve", body(0, null), login("operator01"), null);
        assertEquals(HttpStatus.FORBIDDEN, byOperator.getStatusCode(), "OPERATOR 不得审批");

        ResponseEntity<String> byViewer = post(base(id2) + "/reject", body(0, "no"), login("viewer01"), null);
        assertEquals(HttpStatus.FORBIDDEN, byViewer.getStatusCode(), "VIEWER 不得驳回");

        ResponseEntity<String> byApprover = post(base(id1) + "/approve", body(0, null), login("approver01"), null);
        assertEquals(HttpStatus.OK, byApprover.getStatusCode(), byApprover.getBody());
    }

    @Test
    @DisplayName("E9 四眼原则回归闸：APPROVER 审批自己提交的建议 ⇒ 200（不得返回 RPL-8007）")
    void fourEyesIsNotEnforced() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "10.000", 0, null);
        String approverToken = login("approver01");

        // APPROVER 自己提交（@RequireRole 允许 APPROVER 提交）
        assertEquals(HttpStatus.OK, post(base(id) + "/submit", null, approverToken, null).getStatusCode());

        ResponseEntity<String> res = post(base(id) + "/approve", body(1, "自己批自己的"), approverToken, null);
        System.out.println("[M5T05-EVIDENCE] four-eyes : " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), "D-09 首期不强制：不得实现四眼原则");
        assertEquals("APPROVED", json.readTree(res.getBody()).get("data").get("status").asText());
        assertEquals(APPROVER_ID, json.readTree(res.getBody()).get("data").get("approved_by").asLong());
    }

    // =====================================================================
    // E10/E11：全链路与杂项
    // =====================================================================

    @Test
    @DisplayName("E10 全链路：submit→approve 2 条轨迹 APPROVED；submit→reject 2 条轨迹 REJECTED")
    void fullFlowsLeaveTwoReviews() throws Exception {
        long approveId = createItem(TEAM7, "SUGGESTED", "10.000", 0, null);
        assertEquals(HttpStatus.OK, post(base(approveId) + "/submit", null, login("operator01"), null).getStatusCode());
        assertEquals(HttpStatus.OK, post(base(approveId) + "/approve", body(1, null), login("approver01"), null)
                .getStatusCode());
        assertEquals(2L, reviewCount(approveId));
        assertEquals("APPROVED", statusOf(approveId));

        long rejectId = createItem(TEAM7, "SUGGESTED", "10.000", 0, null);
        assertEquals(HttpStatus.OK, post(base(rejectId) + "/submit", null, login("operator01"), null).getStatusCode());
        assertEquals(HttpStatus.OK, post(base(rejectId) + "/reject", body(1, "不补"), login("approver01"), null)
                .getStatusCode());
        assertEquals(2L, reviewCount(rejectId));
        assertEquals("REJECTED", statusOf(rejectId));
        System.out.println("[M5T05-EVIDENCE] flows      : approveReviews=" + reviewCount(approveId)
                + " rejectReviews=" + reviewCount(rejectId));
    }

    @Test
    @DisplayName("E11 跨团队 ⇒ 404 RPL-8004；expected_version 缺失 ⇒ 400 COM-0001(field=expected_version)")
    void crossTeamAndMissingVersion() throws Exception {
        long otherId = createItem(TEAM8, "PENDING_REVIEW", "10.000", 0, null);
        String token = login("approver01");

        ResponseEntity<String> cross = post(base(otherId) + "/approve", body(0, null), token, null);
        assertEquals(HttpStatus.NOT_FOUND, cross.getStatusCode());
        assertEquals("RPL-8004", json.readTree(cross.getBody()).get("code").asText());

        long id = createItem(TEAM7, "PENDING_REVIEW", "10.000", 0, null);
        ResponseEntity<String> missing = post(base(id) + "/approve", "{\"comment\":\"ok\"}", token, null);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        JsonNode body = json.readTree(missing.getBody());
        assertEquals("COM-0001", body.get("code").asText());
        assertEquals("expected_version", body.get("details").get(0).get("field").asText());
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private String base(long id) {
        return "/api/v1/replenishment/items/" + id;
    }

    private long createItem(long teamId, String status, String qty, int version, LocalDateTime submittedAt) {
        boolean team7 = teamId == TEAM7;
        long sku = team7 ? SKU7 : SKU8;
        long product = team7 ? PRODUCT7 : PRODUCT8;
        long wh = team7 ? WH7 : WH8;
        String runNo = "RUN-APR-" + teamId + "-" + (++runSeq);

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

    private String body(Integer expectedVersion, String comment) {
        StringBuilder sb = new StringBuilder("{");
        if (expectedVersion != null) {
            sb.append("\"expected_version\":").append(expectedVersion);
        }
        if (comment != null) {
            if (expectedVersion != null) {
                sb.append(",");
            }
            sb.append("\"comment\":\"").append(comment).append("\"");
        }
        return sb.append("}").toString();
    }

    private long reviewCount(long itemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_reviews WHERE replenishment_item_id = ?",
                Long.class, itemId);
    }

    private long auditCount(long bizId, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE biz_id = ? "
                + "AND biz_type = 'REPLENISHMENT_ITEM' AND action = ?", Long.class, bizId, action);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM replenishment_items WHERE id = ?", String.class, id);
    }

    private String itemRow(long id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, approved_at, approved_by, closed_at, version FROM replenishment_items WHERE id = ?", id);
        return "id=" + id + " status=" + row.get("status") + " approved_at=" + row.get("approved_at")
                + " approved_by=" + row.get("approved_by") + " closed_at=" + row.get("closed_at")
                + " version=" + row.get("version");
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
