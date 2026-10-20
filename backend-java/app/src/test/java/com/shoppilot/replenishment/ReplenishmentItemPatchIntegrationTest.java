package com.shoppilot.replenishment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-T03 补货建议详情与人工改量 —— 端到端集成测试（真实 MySQL + 真实安全链 + 真实租户线/乐观锁）。
 *
 * <p>覆盖 F1–F9：详情全列与类型、改量成功（含 {@code suggested_qty} 不变与审计）、
 * 单字段 PATCH、{@code remark=""}、乐观锁两次 PATCH（{@code RPL-8003}）、五态状态守卫、
 * 全部校验分支、VIEWER/OPERATOR 权限矩阵、PATCH 后 GET 立即读到新值。
 *
 * <p>★ 全部经 HTTP：权限（{@code @RequireRole}）、全局异常映射、租户线都在真实链路上验证，
 * 不走「直接调 Service」的捷径（Service 层的分支由这些用例等价覆盖）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReplenishmentItemPatchIntegrationTest {

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

    private static final long SUPPLIER7 = 8001L;
    private static final long SUPPLIER8 = 8101L;
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
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");
        runSeq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-PATCH-7', '改量组', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-PATCH-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE')",
                OP_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM7, OP_ID, TEAM7, VIEWER_ID);

        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-P7', '七团供应商'), (?, ?, 'SUP-P8', '八团供应商')",
                SUPPLIER7, TEAM7, SUPPLIER8, TEAM8);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-P7', '七团商品'), (?, ?, 'P-P8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-P7', '七团仓'), (?, ?, 'WH-P8', '八团仓')",
                WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-P7', '七团SKU'), (?, ?, ?, 'SKU-P8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);
    }

    // =====================================================================
    // F1：详情
    // =====================================================================

    @Test
    @DisplayName("F1 详情：全列/version 为 number/数量为字符串；VIEWER 可读；跨团队 404 RPL-8004")
    void detailReturnsFullColumns() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "110.000", 0);
        String viewerToken = login("viewer01");

        ResponseEntity<String> res = get("/api/v1/replenishment/items/" + id, viewerToken);
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");

        assertEquals(id, data.get("id").asLong());
        assertEquals("SUGGESTED", data.get("status").asText());
        assertEquals("110.000", data.get("suggested_qty").asText(), "数量必须是字符串且 scale=3");
        assertEquals("110.000", data.get("qty").asText());
        assertTrue(data.get("version").isNumber(), "version 必须是 number（并发令牌）");
        assertEquals(0, data.get("version").asInt());
        assertEquals(SKU7, data.get("sku_id").asLong());
        assertEquals(PRODUCT7, data.get("product_id").asLong());
        assertEquals(WH7, data.get("warehouse_id").asLong());
        assertNotNull(data.get("source_analysis_item_id"));
        assertNotNull(data.get("run_id"));
        assertNotNull(data.get("created_at"));
        // 本批不扩边界：不得出现 sku_code/warehouse_name
        assertNull(data.get("sku_code"));
        assertNull(data.get("warehouse_name"));

        long otherId = createItem(TEAM8, "SUGGESTED", "10.000", 0);
        ResponseEntity<String> cross = get("/api/v1/replenishment/items/" + otherId, viewerToken);
        assertEquals(HttpStatus.NOT_FOUND, cross.getStatusCode());
        assertEquals("RPL-8004", json.readTree(cross.getBody()).get("code").asText());
    }

    // =====================================================================
    // F2/F3/F4：改量 / 单字段 / 空串备注
    // =====================================================================

    @Test
    @DisplayName("F2 改量 110→80：200，suggested_qty 仍 110，version+1，审计 1 条")
    void patchQty() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "110.000", 0);
        String token = login("operator01");

        ResponseEntity<String> res = patch(id, body(b -> {
            b.put("version", 0);
            b.put("qty", new java.math.BigDecimal("80"));
        }), token);

        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("80.000", data.get("qty").asText());
        assertEquals("110.000", data.get("suggested_qty").asText(), "★ suggested_qty 不可变");
        assertEquals(1, data.get("version").asInt(), "version 应 +1");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT qty, suggested_qty, version FROM replenishment_items WHERE id = ?", id);
        assertEquals(0, new java.math.BigDecimal("80.000").compareTo((java.math.BigDecimal) row.get("qty")));
        assertEquals(0, new java.math.BigDecimal("110.000")
                .compareTo((java.math.BigDecimal) row.get("suggested_qty")));
        assertEquals(1, ((Number) row.get("version")).intValue());

        assertEquals(1L, auditCount(id), "应写 1 条 REPLENISHMENT_QTY_UPDATE 审计");
        System.out.println("[M5T03-EVIDENCE] audit row: " + jdbc.queryForMap(
                "SELECT action, change_summary, before_json, after_json FROM audit_logs WHERE biz_id = ?", id));
    }

    @Test
    @DisplayName("F3 只传 remark：qty 不变、version+1、审计只含 remark")
    void patchRemarkOnly() throws Exception {
        long id = createItem(TEAM7, "PENDING_REVIEW", "50.000", 2);
        String token = login("operator01");

        ResponseEntity<String> res = patch(id, body(b -> {
            b.put("version", 2);
            b.put("remark", "急件");
        }), token);

        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("50.000", data.get("qty").asText(), "qty 不得被改动");
        assertEquals("急件", data.get("remark").asText());
        assertEquals(3, data.get("version").asInt());

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT before_json, after_json, change_summary FROM audit_logs WHERE biz_id = ?", id);
        assertFalse(audit.get("before_json").toString().contains("qty"), "before 只应含变更字段");
        assertTrue(audit.get("before_json").toString().contains("remark"));
        assertTrue(((String) audit.get("change_summary")).contains("remark: null → \"急件\""));
    }

    @Test
    @DisplayName("F4 remark 传 \"\" ⇒ 落库空串（非 NULL）")
    void patchRemarkToEmptyString() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "10.000", 0);
        String token = login("operator01");

        ResponseEntity<String> res = patch(id, body(b -> {
            b.put("version", 0);
            b.put("remark", "");
        }), token);

        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        String stored = jdbc.queryForObject("SELECT remark FROM replenishment_items WHERE id = ?",
                String.class, id);
        assertEquals("", stored, "空串必须原样落库，不得置 NULL");
    }

    // =====================================================================
    // F5：乐观锁核心（V4/V5 取证）
    // =====================================================================

    @Test
    @DisplayName("F5 乐观锁：v0 改成功（→v1），再用 v0 改 ⇒ 409 RPL-8003，DB 仍为第一次结果")
    void staleVersionIsRejected() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "110.000", 0);
        String token = login("operator01");

        ResponseEntity<String> first = patch(id, body(b -> {
            b.put("version", 0);
            b.put("qty", new java.math.BigDecimal("80"));
        }), token);
        System.out.println("[M5T03-EVIDENCE] first  PATCH: " + first.getStatusCode().value()
                + " " + json.readTree(first.getBody()).get("data").get("qty").asText()
                + " version=" + json.readTree(first.getBody()).get("data").get("version").asInt());
        System.out.println("[M5T03-EVIDENCE] after first : " + itemRow(id));
        assertEquals(HttpStatus.OK, first.getStatusCode());

        ResponseEntity<String> second = patch(id, body(b -> {
            b.put("version", 0);
            b.put("qty", new java.math.BigDecimal("60"));
        }), token);
        System.out.println("[M5T03-EVIDENCE] second PATCH: " + second.getStatusCode().value()
                + " " + second.getBody());
        System.out.println("[M5T03-EVIDENCE] after second: " + itemRow(id));

        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals("RPL-8003", json.readTree(second.getBody()).get("code").asText());
        assertEquals("80.000", jdbc.queryForObject(
                "SELECT qty FROM replenishment_items WHERE id = ?", java.math.BigDecimal.class, id).toPlainString(),
                "★ 失败方不得覆盖第一次结果");
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM replenishment_items WHERE id = ?", Integer.class, id));
    }

    // =====================================================================
    // F6：状态守卫
    // =====================================================================

    @Test
    @DisplayName("F6 状态守卫：APPROVED/CONVERTED/REJECTED/CANCELLED/SUPERSEDED ⇒ 422 RPL-8001")
    void nonEditableStatusIsRejected() throws Exception {
        String token = login("operator01");
        for (String status : List.of("APPROVED", "CONVERTED", "REJECTED", "CANCELLED", "SUPERSEDED")) {
            long id = createItem(TEAM7, status, "10.000", 0);
            ResponseEntity<String> res = patch(id, body(b -> {
                b.put("version", 0);
                b.put("qty", new java.math.BigDecimal("20"));
            }), token);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, res.getStatusCode(), "status=" + status);
            assertEquals("RPL-8001", json.readTree(res.getBody()).get("code").asText());
            assertEquals(0, jdbc.queryForObject(
                    "SELECT version FROM replenishment_items WHERE id = ?", Integer.class, id),
                    "被拒时 version 不得变化：status=" + status);
        }
    }

    // =====================================================================
    // F7：校验分支
    // =====================================================================

    @Test
    @DisplayName("F7 校验：qty 0/-1/1.2345、昨天日期、他团队供应商、501 字符 remark、全 null、无 version")
    void validationBranches() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "10.000", 0);
        String token = login("operator01");

        assertPatchError(id, token, b -> {
            b.put("version", 0);
            b.put("qty", java.math.BigDecimal.ZERO);
        }, HttpStatus.BAD_REQUEST, "RPL-8006");

        assertPatchError(id, token, b -> {
            b.put("version", 0);
            b.put("qty", new java.math.BigDecimal("-1"));
        }, HttpStatus.BAD_REQUEST, "RPL-8006");

        assertPatchError(id, token, b -> {
            b.put("version", 0);
            b.put("qty", new java.math.BigDecimal("1.2345"));
        }, HttpStatus.BAD_REQUEST, "RPL-8006");

        assertPatchError(id, token, b -> {
            b.put("version", 0);
            b.put("expected_arrival_date", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
        }, HttpStatus.BAD_REQUEST, "RPL-8010");

        assertPatchError(id, token, b -> {
            b.put("version", 0);
            b.put("supplier_id", SUPPLIER8);
        }, HttpStatus.FORBIDDEN, "RPL-8009");

        assertPatchError(id, token, b -> {
            b.put("version", 0);
            b.put("remark", "x".repeat(501));
        }, HttpStatus.BAD_REQUEST, "COM-0001");

        assertPatchError(id, token, b -> b.put("version", 0),
                HttpStatus.BAD_REQUEST, "COM-0001");

        assertPatchError(id, token, b -> b.put("qty", new java.math.BigDecimal("20")),
                HttpStatus.BAD_REQUEST, "COM-0001");
    }

    // =====================================================================
    // F8/F9：权限与读写一致性
    // =====================================================================

    @Test
    @DisplayName("F8/F9 VIEWER PATCH ⇒ 403；OPERATOR ⇒ 200；PATCH 后 GET 立即读到新 qty/version")
    void permissionAndReadAfterWrite() throws Exception {
        long id = createItem(TEAM7, "SUGGESTED", "10.000", 0);
        String operatorToken = login("operator01");
        String viewerToken = login("viewer01");

        ResponseEntity<String> denied = patch(id, body(b -> {
            b.put("version", 0);
            b.put("qty", new java.math.BigDecimal("20"));
        }), viewerToken);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), "VIEWER 不得改量");

        ResponseEntity<String> ok = patch(id, body(b -> {
            b.put("version", 0);
            b.put("qty", new java.math.BigDecimal("20"));
        }), operatorToken);
        assertEquals(HttpStatus.OK, ok.getStatusCode(), ok.getBody());

        ResponseEntity<String> read = get("/api/v1/replenishment/items/" + id, viewerToken);
        JsonNode data = json.readTree(read.getBody()).get("data");
        assertEquals("20.000", data.get("qty").asText());
        assertEquals(1, data.get("version").asInt());
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 为指定团队创建一条建议（每次新 run，天然满足 {@code uk_repl_run_sku_wh}）。 */
    private long createItem(long teamId, String status, String qty, int version) {
        boolean team7 = teamId == TEAM7;
        long sku = team7 ? SKU7 : SKU8;
        long product = team7 ? PRODUCT7 : PRODUCT8;
        long wh = team7 ? WH7 : WH8;
        String runNo = "RUN-PATCH-" + teamId + "-" + (++runSeq);

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
                        + "created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'HIGH', ?, ?, ?)",
                teamId, sourceId, runId, sku, product, wh, status,
                new java.math.BigDecimal(qty), new java.math.BigDecimal(qty), version, OP_ID, OP_ID);
        return jdbc.queryForObject("SELECT id FROM replenishment_items WHERE run_id = ?", Long.class, runId);
    }

    private long auditCount(long bizId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE biz_id = ? "
                + "AND biz_type = 'REPLENISHMENT_ITEM' AND action = 'REPLENISHMENT_QTY_UPDATE'",
                Long.class, bizId);
    }

    private String itemRow(long id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, version, qty FROM replenishment_items WHERE id = ?", id);
        return "id=" + row.get("id") + " version=" + row.get("version") + " qty=" + row.get("qty");
    }

    private interface BodyCustomizer {
        void accept(ObjectNode body);
    }

    private String body(BodyCustomizer customizer) {
        ObjectNode node = json.createObjectNode();
        customizer.accept(node);
        return node.toString();
    }

    private void assertPatchError(long id, String token, BodyCustomizer customizer,
                                  HttpStatus expectedStatus, String expectedCode) throws Exception {
        ResponseEntity<String> res = patch(id, body(customizer), token);
        assertEquals(expectedStatus, res.getStatusCode(), res.getBody());
        assertEquals(expectedCode, json.readTree(res.getBody()).get("code").asText(), res.getBody());
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> patch(long id, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/replenishment/items/" + id, HttpMethod.PATCH,
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
