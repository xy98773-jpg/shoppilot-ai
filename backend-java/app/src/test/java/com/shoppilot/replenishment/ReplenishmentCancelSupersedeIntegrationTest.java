package com.shoppilot.replenishment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.replenishment.application.ReplenishmentGenerationService;
import com.shoppilot.replenishment.application.ReplenishmentItemService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-T06 作废 / 被取代 —— 端到端集成测试（真实 MySQL + 真实安全链 + 真实事务）。
 *
 * <h2>cancel（{@code §5.59} 裁定 1–2、6–8）</h2>
 * <p>合法来源状态<b>只有</b> {@code SUGGESTED}/{@code APPROVED}；{@code PENDING_REVIEW} 必须
 * 返回 422 且提示「请先撤回再作废」。不要求 {@code expected_version}，但仍走 {@code @Version} CAS，
 * 0 行时锁定读判因（已 CANCELLED ⇒ 422；其余 ⇒ 409）。
 *
 * <h2>SUPERSEDED（{@code §5.59} 裁定 3–5）</h2>
 * <p>新 run 生成后，同 SKU×仓库的旧 {@code SUGGESTED}/{@code PENDING_REVIEW} 置取代；
 * {@code APPROVED}/{@code CONVERTED}/{@code REJECTED} 不动；<b>不写 reviews、不写审计</b>。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReplenishmentCancelSupersedeIntegrationTest {

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
    private ReplenishmentGenerationService generationService;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    private int seq;

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
        seq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-CXL-7', '作废组', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-CXL-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE')",
                OP_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM7, OP_ID, TEAM7, VIEWER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-C7', '七团商品'), (?, ?, 'P-C8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-C7', '七团仓'), (?, ?, 'WH-C8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-C7', '七团SKU'), (?, ?, ?, 'SKU-C8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    // =====================================================================
    // cancel：合法来源
    // =====================================================================

    @Test
    @DisplayName("C1 cancel 成功（SUGGESTED）：CANCELLED + closed_at + reviews 1（CANCEL）+ 审计 1")
    void cancelFromSuggested() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "80.000", 2);

        ResponseEntity<String> res = postCancel(id, "{\"comment\":\"不再需要\"}", login("operator01"), "req_m5t06_c1");
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("CANCELLED", data.get("status").asText());
        assertEquals("CANCEL", data.get("action").asText());
        assertEquals("SUGGESTED", data.get("from_status").asText());
        assertEquals("CANCELLED", data.get("to_status").asText());
        assertEquals("80.000", data.get("qty").asText());
        assertNotNull(data.get("closed_at"));
        long reviewId = data.get("review_id").asLong();

        Map<String, Object> item = jdbc.queryForMap(
                "SELECT status, closed_at, version FROM replenishment_items WHERE id = ?", id);
        assertEquals("CANCELLED", item.get("status"));
        assertNotNull(item.get("closed_at"));
        assertEquals(3, ((Number) item.get("version")).intValue());

        Map<String, Object> review = jdbc.queryForMap(
                "SELECT id, action, from_status, to_status, comment, reviewer_role, qty_before, qty_after, "
                        + "request_id FROM replenishment_reviews WHERE replenishment_item_id = ?", id);
        assertEquals(reviewId, ((Number) review.get("id")).longValue());
        assertEquals("CANCEL", review.get("action"));
        assertEquals("SUGGESTED", review.get("from_status"));
        assertEquals("CANCELLED", review.get("to_status"));
        assertEquals("不再需要", review.get("comment"));
        assertEquals("OPERATOR", review.get("reviewer_role"));
        assertEquals(0, new java.math.BigDecimal("80.000")
                .compareTo((java.math.BigDecimal) review.get("qty_before")));
        assertEquals("req_m5t06_c1", review.get("request_id"));
        assertEquals(1L, auditCount(id, "REPLENISHMENT_CANCEL"));
        System.out.println("[M5T06-EVIDENCE] cancel SUGGESTED: " + itemRow(id));
    }

    @Test
    @DisplayName("C2 cancel 成功（APPROVED）：from_status=APPROVED，同样写 closed_at/reviews/审计")
    void cancelFromApproved() throws Exception {
        long id = createItem(TEAM7, "APPROVED", "20.000", 4);

        ResponseEntity<String> res = postCancel(id, null, login("operator01"), null);
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("CANCELLED", data.get("status").asText());
        assertEquals("APPROVED", data.get("from_status").asText());
        assertEquals(1L, reviewCount(id));
        assertEquals(1L, auditCount(id, "REPLENISHMENT_CANCEL"));
    }

    // =====================================================================
    // cancel：状态机口径（本批最易写错）
    // =====================================================================

    @Test
    @DisplayName("C3 PENDING_REVIEW 不可直接作废 ⇒ 422 RPL-8001，提示「请先撤回再作废」")
    void cancelFromPendingReviewIsRejectedWithHint() throws Exception {
        long id = createItem(TEAM7, "PENDING_REVIEW", "10.000", 1);

        ResponseEntity<String> res = postCancel(id, null, login("operator01"), null);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, res.getStatusCode(), res.getBody());
        JsonNode body = json.readTree(res.getBody());
        assertEquals("RPL-8001", body.get("code").asText());
        assertTrue(body.get("message").asText().contains("请先撤回再作废"),
                "提示语必须给出可执行动作");
        assertEquals(0L, reviewCount(id));
        assertEquals("PENDING_REVIEW", statusOf(id));
        System.out.println("[M5T06-EVIDENCE] cancel PENDING_REVIEW: " + res.getBody());
    }

    @Test
    @DisplayName("C4 其它不可作废状态：CONVERTED/REJECTED/SUPERSEDED/CANCELLED ⇒ 422 RPL-8001")
    void cancelFromTerminalStatesIsRejected() throws Exception {
        String token = login("operator01");
        for (String status : List.of("CONVERTED", "REJECTED", "SUPERSEDED", "CANCELLED")) {
            long id = createItem(TEAM7, status, "10.000", 0);
            ResponseEntity<String> res = postCancel(id, null, token, null);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, res.getStatusCode(), "status=" + status);
            assertEquals("RPL-8001", json.readTree(res.getBody()).get("code").asText());
            assertEquals(0L, reviewCount(id));
        }
    }

    @Test
    @DisplayName("C5 重复作废（已 CANCELLED 再 cancel）⇒ 422 RPL-8001，不是 409")
    void repeatCancelReturns8001() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "10.000", 0);
        String token = login("operator01");
        assertEquals(HttpStatus.OK, postCancel(id, null, token, null).getStatusCode());

        ResponseEntity<String> second = postCancel(id, null, token, null);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, second.getStatusCode(), second.getBody());
        assertEquals("RPL-8001", json.readTree(second.getBody()).get("code").asText());
        assertEquals(1L, reviewCount(id), "重复作废不得新增轨迹");
    }

    // =====================================================================
    // cancel：权限 / 校验 / 跨团队 / 并发判因
    // =====================================================================

    @Test
    @DisplayName("C6 权限：VIEWER ⇒ 403；comment 501 ⇒ COM-0001；缺省 comment ⇒ reviews.comment IS NULL")
    void permissionsAndComment() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "10.000", 0);
        ResponseEntity<String> denied = postCancel(id, null, login("viewer01"), null);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals(0L, reviewCount(id));

        long longId = createItem(TEAM7, "SUGGESTED", "10.000", 0);
        ResponseEntity<String> bad = postCancel(longId, "{\"comment\":\"" + "x".repeat(501) + "\"}",
                login("operator01"), null);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        JsonNode body = json.readTree(bad.getBody());
        assertEquals("COM-0001", body.get("code").asText());
        assertEquals("comment", body.get("details").get(0).get("field").asText());

        long nullId = createItem(TEAM7, "SUGGESTED", "10.000", 0);
        assertEquals(HttpStatus.OK, postCancel(nullId, null, login("operator01"), null).getStatusCode());
        assertNull(jdbc.queryForObject(
                "SELECT comment FROM replenishment_reviews WHERE replenishment_item_id = ?", String.class, nullId),
                "未传 comment 应存 NULL");
    }

    @Test
    @DisplayName("C7 跨团队 ⇒ 404 RPL-8004")
    void crossTeamCancel() throws Exception {
        long otherId = createItem(TEAM8, "SUGGESTED", "10.000", 0);
        ResponseEntity<String> res = postCancel(otherId, null, login("operator01"), null);
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals("RPL-8004", json.readTree(res.getBody()).get("code").asText());
    }

    @Test
    @DisplayName("C8 并发判因：陈旧读 → 他已作废 ⇒ RPL-8001（不是 8003）")
    void staleCancelIsReportedAsStatusConflict() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "10.000", 0);
        TeamContext.set(new TeamContext.Snapshot(TEAM7, List.of("OPERATOR"), OP_ID, "operator01"));
        TransactionTemplate tx = new TransactionTemplate(txManager);

        BizException ex = tx.execute(status -> {
            assertEquals("SUGGESTED", statusOf(id));      // 固定 RR 快照
            commitConcurrentCancel(id);                    // 另一连接已作废（version+1）
            BizException thrown = assertThrows(BizException.class, () -> itemService.cancel(id, null));
            status.setRollbackOnly();
            return thrown;
        });
        TeamContext.clear();

        assertNotNull(ex);
        assertEquals(ErrorCodes.RPL_8001, ex.getErrorCode(), "★ 已作废应判为状态冲突（422），不是 8003");
        assertEquals("CANCELLED", statusOf(id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM replenishment_items WHERE id = ?", Integer.class, id));
        assertEquals(1L, reviewCount(id), "失败方不得新增轨迹");
    }

    // =====================================================================
    // SUPERSEDED：派生逻辑
    // =====================================================================

    @Test
    @DisplayName("S1 新 run 取代：旧 SUGGESTED/PENDING_REVIEW → SUPERSEDED；APPROVED/CONVERTED/REJECTED 不动")
    void newRunSupersedesOnlyUnapproved() throws Exception {
        long oldSuggested = createItemWithRun(TEAM7, "SUGGESTED", "10.000");
        long oldPending = createItemWithRun(TEAM7, "PENDING_REVIEW", "11.000");
        long oldApproved = createItemWithRun(TEAM7, "APPROVED", "12.000");
        long oldConverted = createItemWithRun(TEAM7, "CONVERTED", "13.000");
        long oldRejected = createItemWithRun(TEAM7, "REJECTED", "14.000");

        long newRun = createRun(TEAM7, "RUN-NEW-S1");
        insertAnalysisItem(newRun, "120.000");
        TeamContext.set(new TeamContext.Snapshot(TEAM7, List.of("OPERATOR"), OP_ID, "operator01"));
        var result = generationService.generateFromRun(newRun);
        TeamContext.clear();
        assertEquals(1, result.createdCount());

        long newItemId = jdbc.queryForObject(
                "SELECT id FROM replenishment_items WHERE run_id = ?", Long.class, newRun);

        assertEquals("SUPERSEDED", statusOf(oldSuggested));
        assertEquals("SUPERSEDED", statusOf(oldPending));
        assertEquals(newItemId, jdbc.queryForObject(
                "SELECT superseded_by_item_id FROM replenishment_items WHERE id = ?", Long.class, oldSuggested));
        assertNotNull(jdbc.queryForObject(
                "SELECT closed_at FROM replenishment_items WHERE id = ?", Object.class, oldSuggested));

        assertEquals("APPROVED", statusOf(oldApproved), "★ 已审批不得被覆盖（§9.11 规则 2/3）");
        assertEquals("CONVERTED", statusOf(oldConverted));
        assertEquals("REJECTED", statusOf(oldRejected));

        // ★ 不写 reviews、不写审计
        assertEquals(0L, reviewCount(oldSuggested));
        assertEquals(0L, reviewCount(oldPending));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE biz_id IN (?, ?)", Long.class, oldSuggested, oldPending),
                "SUPERSEDED 是系统派生动作，不写审计");

        System.out.println("[M5T06-EVIDENCE] supersede: oldSuggested=" + statusOf(oldSuggested)
                + " oldPending=" + statusOf(oldPending) + " newItem=" + newItemId
                + " oldApproved=" + statusOf(oldApproved) + " oldConverted=" + statusOf(oldConverted)
                + " oldRejected=" + statusOf(oldRejected) + " reviews=" + reviewCount(oldSuggested));
    }

    @Test
    @DisplayName("S2 跨团队/其它 SKU 不受影响；同 run 幂等重调不重复取代")
    void supersedeScopedToTeamAndSku() throws Exception {
        long sameTeamOtherSku = createItemWithRun(TEAM7, "SUGGESTED", "10.000", SKU_OTHER);
        long otherTeam = createItemWithRun(TEAM8, "SUGGESTED", "10.000");
        long target = createItemWithRun(TEAM7, "SUGGESTED", "10.000");

        long newRun = createRun(TEAM7, "RUN-NEW-S2");
        insertAnalysisItem(newRun, "50.000");
        TeamContext.set(new TeamContext.Snapshot(TEAM7, List.of("OPERATOR"), OP_ID, "operator01"));
        generationService.generateFromRun(newRun);
        // 幂等重调：不得报错、不得改变已取代结果
        generationService.generateFromRun(newRun);
        TeamContext.clear();

        assertEquals("SUPERSEDED", statusOf(target));
        assertEquals("SUGGESTED", statusOf(sameTeamOtherSku), "其它 SKU 不受影响");
        assertEquals("SUGGESTED", statusOf(otherTeam), "其它团队不受影响");
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 另一个 SKU（同团队）用于「按 SKU 隔离」断言：id 9003 在 seed 中不存在，按需创建。 */
    private static final long SKU_OTHER = 9003L;

    private long createRun(long teamId, String runNo) {
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, algorithm_version) "
                + "VALUES (?, ?, 'SUCCEEDED', 'ALL_ACTIVE', 'avg-ma-3sigma-v1')", teamId, runNo);
        return jdbc.queryForObject("SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);
    }

    private void insertAnalysisItem(long runId, String qtySuggest) {
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, qty_suggest, "
                        + "safety_stock_qty) VALUES (?, ?, ?, ?, ?, 8.000, 'HIGH', 'SKU', 6.250, 104.500, ?, 25.000)",
                TEAM7, runId, SKU7, PRODUCT7, WH7, new java.math.BigDecimal(qtySuggest));
    }

    private long createItem(long teamId, String status, String qty, int version) {
        return insertItem(teamId, status, qty, version, teamId == TEAM7 ? SKU7 : SKU8);
    }

    private long createItemWithRun(long teamId, String status, String qty) {
        return createItemWithRun(teamId, status, qty, teamId == TEAM7 ? SKU7 : SKU8);
    }

    private long createItemWithRun(long teamId, String status, String qty, long skuId) {
        ensureOtherSku();
        long runId = createRun(teamId, "RUN-CXL-" + teamId + "-" + (++seq));
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, qty_suggest, "
                        + "safety_stock_qty) VALUES (?, ?, ?, ?, ?, 8.000, 'HIGH', 'SKU', 6.250, 104.500, "
                        + "110.000, 25.000)",
                teamId, runId, skuId, teamId == TEAM7 ? PRODUCT7 : PRODUCT8, teamId == TEAM7 ? WH7 : WH8);
        long sourceId = jdbc.queryForObject("SELECT id FROM analysis_items WHERE run_id = ?", Long.class, runId);
        return insertItemRow(teamId, runId, sourceId, skuId, status, qty, 0);
    }

    /** 首次调用时补一条同团队的另一 SKU（用于「按 SKU 隔离」断言）。 */
    private void ensureOtherSku() {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM skus WHERE id = ?", Integer.class, SKU_OTHER);
        if (exists == null || exists == 0) {
            jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES (?, ?, ?, 'SKU-C7B', '七团SKU-B')",
                    SKU_OTHER, TEAM7, PRODUCT7);
        }
    }

    private long insertItem(long teamId, String status, String qty, int version, long skuId) {
        long runId = createRun(teamId, "RUN-CXL-" + teamId + "-" + (++seq));
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, qty_suggest, "
                        + "safety_stock_qty) VALUES (?, ?, ?, ?, ?, 8.000, 'HIGH', 'SKU', 6.250, 104.500, "
                        + "110.000, 25.000)",
                teamId, runId, skuId, teamId == TEAM7 ? PRODUCT7 : PRODUCT8, teamId == TEAM7 ? WH7 : WH8);
        long sourceId = jdbc.queryForObject("SELECT id FROM analysis_items WHERE run_id = ?", Long.class, runId);
        return insertItemRow(teamId, runId, sourceId, skuId, status, qty, version);
    }

    private long insertItemRow(long teamId, long runId, long sourceId, long skuId, String status,
                               String qty, int version) {
        jdbc.update("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                        + "product_id, warehouse_id, status, suggested_qty, qty, risk_level, version, "
                        + "created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'HIGH', ?, ?, ?)",
                teamId, sourceId, runId, skuId, teamId == TEAM7 ? PRODUCT7 : PRODUCT8,
                teamId == TEAM7 ? WH7 : WH8, status,
                new java.math.BigDecimal(qty), new java.math.BigDecimal(qty), version, OP_ID, OP_ID);
        return jdbc.queryForObject("SELECT id FROM replenishment_items WHERE run_id = ?", Long.class, runId);
    }

    private void commitConcurrentCancel(long id) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE replenishment_items SET status = 'CANCELLED', closed_at = NOW(3), "
                            + "version = version + 1 WHERE id = ?")) {
                ps.setLong(1, id);
                assertEquals(1, ps.executeUpdate());
            }
            // 并发者同样留下一条 CANCEL 轨迹（真实 cancel 会写 reviews）
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO replenishment_reviews (team_id, replenishment_item_id, action, from_status, "
                            + "to_status, qty_before, qty_after, reviewer_id, reviewer_role, request_id, "
                            + "occurred_at, created_at) VALUES (?, ?, 'CANCEL', 'SUGGESTED', 'CANCELLED', "
                            + "10.000, 10.000, ?, 'OPERATOR', 'req_concurrent_cancel', NOW(3), NOW(3))")) {
                ps.setLong(1, TEAM7);
                ps.setLong(2, id);
                ps.setLong(3, OP_ID);
                assertEquals(1, ps.executeUpdate());
            }
        } catch (Exception e) {
            throw new IllegalStateException("并发作废构造失败", e);
        }
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM replenishment_items WHERE id = ?", String.class, id);
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
                "SELECT status, closed_at, version FROM replenishment_items WHERE id = ?", id);
        return "id=" + id + " status=" + row.get("status") + " closed_at=" + row.get("closed_at")
                + " version=" + row.get("version");
    }

    private ResponseEntity<String> postCancel(long id, String body, String token, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (requestId != null) {
            headers.set("X-Request-Id", requestId);
        }
        return rest.exchange("/api/v1/replenishment/items/" + id + "/cancel", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
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
