package com.shoppilot.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.TestCredentials;
import java.util.HashSet;
import java.util.Set;
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
 * M3-T10/T11/T14/T15/T16（B7）库存 API 层 —— 端到端集成测试（真实 MySQL + 真实 Spring 上下文 + 真实 HTTP）。
 *
 * <p><b>真栈</b>：MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移 + {@code SecurityFilterChain}
 * + {@code JwtAuthenticationFilter} + 租户线 + 分页拦截器 + {@code @RequireRole} 切面。
 *
 * <p>覆盖任务书硬门槛：10 个 HTTP 端点可用；读端点契约（{@code list} 字段、分页元信息、sku_code 展示、
 * 小数当字符串）；预警三态（STOCKOUT/LOWSTOCK/SLOWMOVING）；调整二合一与 R59 角色矩阵
 * （VIEWER→TEAM-2003、MANUAL_ADJUST 的 OPERATOR→INV-5007、ADMIN→201）；冲销链路；补货策略 upsert 幂等 /
 * 停用 / 校验；跨团队隔离（异团队流水→INV-5008、异团队策略→CAT-3001）。
 *
 * <p>★ 与本批冻结项一致：缺 {@code Idempotency-Key}、缺参、非法 {@code txn_type} 一律 {@code COM-0001}
 * （契约标注的 COM-0010 未固化，降级使用）；策略参数负值同理 {@code COM-0001}（CAT-3010 未固化）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InventoryApiIntegrationTest {

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

    /** 团队 7：产品 / 四个 SKU / 仓库（与 B6 同款 ID，便于跨批对照）。 */
    private static final long PRODUCT_ID = 9001L;
    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;
    private static final long SKU_C = 9005L;
    private static final long SKU_D = 9006L;
    private static final long WAREHOUSE_ID = 9004L;
    /** 团队 8：跨团队负向用例。 */
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
        // 库存三表
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM replenishment_policies");
        // 主数据
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM warehouses");
        // 身份
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
                + "(?, ?, ?, 'SKU-D', 'SKU-D', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-OTHER', 'SKU-OTHER', 'ACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_ID,
                SKU_B, TEAM_ID, PRODUCT_ID,
                SKU_C, TEAM_ID, PRODUCT_ID,
                SKU_D, TEAM_ID, PRODUCT_ID,
                OTHER_SKU_ID, OTHER_TEAM_ID, OTHER_PRODUCT_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) VALUES "
                + "(?, ?, 'WH-MAIN', '主仓', 'ACTIVE'), (?, ?, 'WH-OTHER', '别家仓', 'ACTIVE')",
                WAREHOUSE_ID, TEAM_ID, OTHER_WAREHOUSE_ID, OTHER_TEAM_ID);

        // 库存余额（available_qty 为 STORED 生成列，不显式写入）
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty, created_by, updated_by, version) VALUES "
                + "(?, ?, ?, 10.000, 0.000, 0.000, ?, ?, 0), "      // SKU-A：正常（受调整/冲销/流水）
                + "(?, ?, ?, 0.000, 0.000, 0.000, ?, ?, 0), "       // SKU-B：缺货（on_hand=0 → STOCKOUT）
                + "(?, ?, ?, 20.000, 0.000, 0.000, ?, ?, 0), "      // SKU-C：滞销（无近 30 天销售 → SLOWMOVING）
                + "(?, ?, ?, 5.000, 0.000, 0.000, ?, ?, 0)",        // SKU-D：低库存（策略 safety=10 → LOWSTOCK）
                TEAM_ID, WAREHOUSE_ID, SKU_A, OPERATOR_ID, OPERATOR_ID,
                TEAM_ID, WAREHOUSE_ID, SKU_B, OPERATOR_ID, OPERATOR_ID,
                TEAM_ID, WAREHOUSE_ID, SKU_C, OPERATOR_ID, OPERATOR_ID,
                TEAM_ID, WAREHOUSE_ID, SKU_D, OPERATOR_ID, OPERATOR_ID);

        // 库存流水（团队 7）
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                + "qty_before, qty_after, biz_type, idempotency_key, reason, operator_id, occurred_at, created_by) VALUES "
                + "(?, ?, ?, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9002', '种子导入', ?, NOW(3), ?), "
                + "(?, ?, ?, 'SALE_OUTBOUND', -2.000, 12.000, 10.000, 'SALE', 'seed-sale-9002', '销售', ?, NOW(3), ?), "
                + "(?, ?, ?, 'SALE_OUTBOUND', -1.000, 6.000, 5.000, 'SALE', 'seed-sale-9006', '销售', ?, NOW(3), ?)",
                TEAM_ID, WAREHOUSE_ID, SKU_A, OPERATOR_ID, OPERATOR_ID,
                TEAM_ID, WAREHOUSE_ID, SKU_A, OPERATOR_ID, OPERATOR_ID,
                TEAM_ID, WAREHOUSE_ID, SKU_D, OPERATOR_ID, OPERATOR_ID);

        // 补货策略（团队 7）：SKU-D 安全库存 10 → 触发 LOWSTOCK（available=5 ≤ 10）
        jdbc.update("INSERT INTO replenishment_policies (team_id, sku_id, warehouse_id, safety_stock_qty, "
                + "is_enabled, created_by, updated_by, version) VALUES (?, ?, ?, 10.000, 1, ?, ?, 0)",
                TEAM_ID, SKU_D, WAREHOUSE_ID, OPERATOR_ID, OPERATOR_ID);

        // 跨团队负向用例数据（团队 8）
        jdbc.update("INSERT INTO replenishment_policies (team_id, sku_id, warehouse_id, is_enabled, "
                + "created_by, updated_by, version) VALUES (?, ?, ?, 1, ?, ?, 0)",
                OTHER_TEAM_ID, OTHER_SKU_ID, OTHER_WAREHOUSE_ID, OTHER_OWNER_ID, OTHER_OWNER_ID);
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                + "qty_before, qty_after, biz_type, idempotency_key, operator_id, occurred_at, created_by) VALUES "
                + "(?, ?, ?, 'MANUAL_ADJUST', 1.000, 0.000, 1.000, 'MANUAL', 'other-txn', ?, NOW(3), ?)",
                OTHER_TEAM_ID, OTHER_WAREHOUSE_ID, OTHER_SKU_ID, OTHER_OWNER_ID, OTHER_OWNER_ID);
    }

    // =====================================================================
    // §10.4-1 GET /balances（F-INV-01）
    // =====================================================================

    @Test
    @DisplayName("T1 GET /balances 200：list 字段、分页元信息、sku_code 展示、小数当字符串")
    void balancesList() throws Exception {
        ResponseEntity<String> r = get("/api/v1/inventory/balances", operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        JsonNode data = objectMapper.readTree(r.getBody()).get("data");
        assertTrue(data.get("list").isArray());
        assertEquals(1, data.get("page").asInt());
        assertEquals(20, data.get("pageSize").asInt());
        assertEquals(4, data.get("total").asInt(), "应返回 4 行余额");

        JsonNode first = data.get("list").get(0);
        assertTrue(first.has("sku_code"), "余额响应须含 sku_code 展示字段");
        assertTrue(first.get("on_hand_qty").isTextual(), "数量必须以字符串传输");
        assertNotNull(first.get("available_qty"), "available_qty 须展示（仅展示，不参与补货）");
    }

    @Test
    @DisplayName("T2 GET /balances?warehouse_id=&sku_id= 筛选生效")
    void balancesFilter() throws Exception {
        String op = operatorToken();
        JsonNode bySku = objectMapper.readTree(
                get("/api/v1/inventory/balances?sku_id=" + SKU_A, op).getBody()).get("data");
        assertEquals(1, bySku.get("total").asInt(), "按 sku_id 筛选应只返回 1 条");
        assertEquals(SKU_A, bySku.get("list").get(0).get("sku_id").asLong());

        JsonNode byWh = objectMapper.readTree(
                get("/api/v1/inventory/balances?warehouse_id=" + WAREHOUSE_ID, op).getBody()).get("data");
        assertEquals(4, byWh.get("total").asInt());
    }

    @Test
    @DisplayName("T3 GET /balances?sort=bogus 非法排序 → 400 COM-0007")
    void balancesBadSort() throws Exception {
        ResponseEntity<String> r = get("/api/v1/inventory/balances?sort=bogus_field,desc", operatorToken());
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0007", code(r));
    }

    // =====================================================================
    // §10.4-2 GET /transactions（F-INV-02）
    // =====================================================================

    @Test
    @DisplayName("T4 GET /transactions 200：返回种子流水且含 sku_code")
    void transactionsList() throws Exception {
        ResponseEntity<String> r = get("/api/v1/inventory/transactions", operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        JsonNode data = objectMapper.readTree(r.getBody()).get("data");
        assertEquals(3, data.get("total").asInt());
        JsonNode first = data.get("list").get(0);
        assertTrue(first.has("sku_code"));
        assertTrue(first.get("qty_delta").isTextual(), "流水数量必须以字符串传输");
    }

    @Test
    @DisplayName("T5 GET /transactions?txn_type=SALE_OUTBOUND 筛选生效")
    void transactionsFilterByType() throws Exception {
        JsonNode data = objectMapper.readTree(
                get("/api/v1/inventory/transactions?txn_type=SALE_OUTBOUND", operatorToken()).getBody()).get("data");
        assertEquals(2, data.get("total").asInt(), "应只返回 2 笔销售出库");
    }

    @Test
    @DisplayName("T6 GET /transactions?dateFrom>dateTo → 400 COM-0007")
    void transactionsDateOrder() throws Exception {
        ResponseEntity<String> r = get(
                "/api/v1/inventory/transactions?dateFrom=2026-01-10&dateTo=2026-01-01", operatorToken());
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0007", code(r));
    }

    @Test
    @DisplayName("T7 GET /transactions?dateFrom= 非法格式 → 400 COM-0007")
    void transactionsDateParse() throws Exception {
        ResponseEntity<String> r = get("/api/v1/inventory/transactions?dateFrom=not-a-date", operatorToken());
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0007", code(r));
    }

    // =====================================================================
    // §10.4-3 GET /transactions/{id}（不存在 / 异团队 → INV-5008）
    // =====================================================================

    @Test
    @DisplayName("T8 GET /transactions/{id} 200：含 sku_code")
    void transactionDetailOk() throws Exception {
        long id = txnIdByIdempotencyKey("seed-init-9002");
        ResponseEntity<String> r = get("/api/v1/inventory/transactions/" + id, operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        assertEquals("INIT_IMPORT", objectMapper.readTree(r.getBody()).get("data").get("txn_type").asText());
    }

    @Test
    @DisplayName("T9 GET /transactions/{id} 不存在 → 404 INV-5008")
    void transactionDetailMissing() throws Exception {
        ResponseEntity<String> r = get("/api/v1/inventory/transactions/999999", operatorToken());
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertEquals("INV-5008", code(r));
    }

    @Test
    @DisplayName("T10 GET /transactions/{id} 异团队 → 404 INV-5008（租户线让异团队流水「查不到」）")
    void transactionDetailCrossTeam() throws Exception {
        long otherId = txnIdByIdempotencyKey("other-txn");
        ResponseEntity<String> r = get("/api/v1/inventory/transactions/" + otherId, operatorToken());
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertEquals("INV-5008", code(r));
    }

    // =====================================================================
    // §10.4-5 GET /alerts（F-INV-05）：无状态、三态判定
    // =====================================================================

    @Test
    @DisplayName("T11 GET /alerts 200：至少含 STOCKOUT（SKU-B on_hand=0）、LOWSTOCK（SKU-D）、SLOWMOVING（SKU-C）")
    void alertsThreeTypes() throws Exception {
        JsonNode data = objectMapper.readTree(get("/api/v1/inventory/alerts", operatorToken()).getBody()).get("data");
        Set<String> types = new HashSet<>();
        for (JsonNode a : data.get("list")) {
            types.add(a.get("alert_type").asText());
        }
        assertTrue(types.contains("STOCKOUT"), "应含 STOCKOUT，实测：" + types);
        assertTrue(types.contains("LOWSTOCK"), "应含 LOWSTOCK，实测：" + types);
        assertTrue(types.contains("SLOWMOVING"), "应含 SLOWMOVING，实测：" + types);
    }

    // =====================================================================
    // §10.4-4 POST /adjustments：二合一 + R59 角色矩阵
    // =====================================================================

    @Test
    @DisplayName("T12 POST /adjustments COUNT_ADJUST：OPERATOR 201，返回 transaction_id / qty_after / balance_version")
    void countAdjustByOperator() throws Exception {
        String body = "{\"warehouse_id\":" + WAREHOUSE_ID + ",\"sku_id\":" + SKU_A
                + ",\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"月度盘点\"}";
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments", body, operatorToken(), "idem-count-1");
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), r.getBody());
        JsonNode data = objectMapper.readTree(r.getBody()).get("data");
        assertNotNull(data.get("transaction_id"), "差额非 0 应落流水");
        assertEquals("7.000", data.get("qty_after").asText());
        assertEquals("COUNT_ADJUST", data.get("txn_type").asText());
        assertNotNull(data.get("balance_version"));
    }

    @Test
    @DisplayName("T13 POST /adjustments 缺 Idempotency-Key → 400 COM-0001")
    void adjustMissingIdempotencyKey() throws Exception {
        String body = "{\"warehouse_id\":" + WAREHOUSE_ID + ",\"sku_id\":" + SKU_A
                + ",\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"x\"}";
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments", body, operatorToken(), null);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0001", code(r));
    }

    @Test
    @DisplayName("T14 POST /adjustments 缺 warehouse_id/sku_id → 400 COM-0001")
    void adjustMissingRequired() throws Exception {
        String body = "{\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"x\"}";
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments", body, operatorToken(), "idem-14");
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0001", code(r));
    }

    @Test
    @DisplayName("T15 POST /adjustments 非法 txn_type → 400 COM-0001")
    void adjustBadTxnType() throws Exception {
        String body = "{\"warehouse_id\":" + WAREHOUSE_ID + ",\"sku_id\":" + SKU_A
                + ",\"txn_type\":\"WEIRD\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"x\"}";
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments", body, operatorToken(), "idem-15");
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0001", code(r));
    }

    @Test
    @DisplayName("T16 ★ R59：MANUAL_ADJUST 由 OPERATOR（非 ADMIN+）调用 → 403 INV-5007")
    void manualAdjustByOperatorForbidden() throws Exception {
        String body = "{\"warehouse_id\":" + WAREHOUSE_ID + ",\"sku_id\":" + SKU_A
                + ",\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"-2.000\",\"reason\":\"破损\"}";
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments", body, operatorToken(), "idem-man-op");
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertEquals("INV-5007", code(r), "★ MANUAL_ADJUST 需 ADMIN+，OPERATOR 必须 INV-5007");
    }

    @Test
    @DisplayName("T17 ★ R59：MANUAL_ADJUST 由 ADMIN 调用 → 201")
    void manualAdjustByAdminOk() throws Exception {
        String body = "{\"warehouse_id\":" + WAREHOUSE_ID + ",\"sku_id\":" + SKU_A
                + ",\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"-2.000\",\"reason\":\"破损\"}";
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments", body, adminToken(), "idem-man-admin");
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), r.getBody());
        assertEquals("MANUAL_ADJUST", objectMapper.readTree(r.getBody()).get("data").get("txn_type").asText());
    }

    @Test
    @DisplayName("T18 ★ R59：VIEWER 调用 adjustments（静态 @RequireRole 四写角色）→ 403 TEAM-2003")
    void adjustByViewerForbidden() throws Exception {
        String body = "{\"warehouse_id\":" + WAREHOUSE_ID + ",\"sku_id\":" + SKU_A
                + ",\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"x\"}";
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments", body, viewerToken(), "idem-view");
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertEquals("TEAM-2003", code(r), "VIEWER 不满足静态四写角色，必须 TEAM-2003（非 INV-5007）");
    }

    // =====================================================================
    // §10.4-6 POST /transactions/{id}/reverse：ADMIN+ 静态层（TEAM-2003），非 INV-5007
    // =====================================================================

    @Test
    @DisplayName("T19 POST /transactions/{id}/reverse：OWNER 201，余额回退、流水反向")
    void reverseByOwner() throws Exception {
        long id = txnIdByIdempotencyKey("seed-init-9002");
        ResponseEntity<String> r = post("/api/v1/inventory/transactions/" + id + "/reverse",
                "{\"reason\":\"导入错误\"}", ownerToken(), "idem-rev-owner");
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        JsonNode data = objectMapper.readTree(r.getBody()).get("data");
        assertEquals("REVERSAL", data.get("txn_type").asText());
        // 余额 10 → 0
        assertEquals(0, new java.math.BigDecimal("0.000").compareTo(
                jdbc.queryForObject("SELECT on_hand_qty FROM inventory_balances WHERE team_id=? "
                        + "AND warehouse_id=? AND sku_id=?", java.math.BigDecimal.class,
                        TEAM_ID, WAREHOUSE_ID, SKU_A)));
    }

    @Test
    @DisplayName("T20 POST /transactions/{id}/reverse：OPERATOR（非 OWNER/ADMIN）→ 403 TEAM-2003")
    void reverseByOperatorForbidden() throws Exception {
        long id = txnIdByIdempotencyKey("seed-sale-9002");
        ResponseEntity<String> r = post("/api/v1/inventory/transactions/" + id + "/reverse",
                "{\"reason\":\"x\"}", operatorToken(), "idem-rev-op");
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertEquals("TEAM-2003", code(r));
    }

    @Test
    @DisplayName("T21 POST /transactions/{id}/reverse 缺 Idempotency-Key → 400 COM-0001")
    void reverseMissingIdempotencyKey() throws Exception {
        long id = txnIdByIdempotencyKey("seed-sale-9002");
        ResponseEntity<String> r = post("/api/v1/inventory/transactions/" + id + "/reverse",
                "{\"reason\":\"x\"}", ownerToken(), null);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0001", code(r));
    }

    @Test
    @DisplayName("T22 POST /transactions/{id}/reverse 不存在 → 404 INV-5008")
    void reverseMissing() throws Exception {
        ResponseEntity<String> r = post("/api/v1/inventory/transactions/999999/reverse",
                "{\"reason\":\"x\"}", ownerToken(), "idem-rev-miss");
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertEquals("INV-5008", code(r));
    }

    // =====================================================================
    // §10.4 补货策略：列表 / 详情 / upsert / 停用
    // =====================================================================

    @Test
    @DisplayName("T23 GET /replenishment-policies 200：返回种子策略且含 sku_code、is_enabled、小数当字符串")
    void policyList() throws Exception {
        ResponseEntity<String> r = get("/api/v1/replenishment-policies", operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        JsonNode data = objectMapper.readTree(r.getBody()).get("data");
        assertEquals(1, data.get("total").asInt());
        JsonNode first = data.get("list").get(0);
        assertTrue(first.has("sku_code"));
        assertEquals(true, first.get("is_enabled").asBoolean());
        assertTrue(first.get("safety_stock_qty").isTextual());
    }

    @Test
    @DisplayName("T24 GET /replenishment-policies/{id} 200")
    void policyDetail() throws Exception {
        long id = policyIdBySkuAndWarehouse(SKU_D, WAREHOUSE_ID);
        ResponseEntity<String> r = get("/api/v1/replenishment-policies/" + id, operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        assertEquals(SKU_D, objectMapper.readTree(r.getBody()).get("data").get("sku_id").asLong());
    }

    @Test
    @DisplayName("T25 GET /replenishment-policies/{id} 异团队 → 404 CAT-3001")
    void policyDetailCrossTeam() throws Exception {
        long otherId = policyIdBySkuAndWarehouse(OTHER_SKU_ID, OTHER_WAREHOUSE_ID);
        ResponseEntity<String> r = get("/api/v1/replenishment-policies/" + otherId, operatorToken());
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertEquals("CAT-3001", code(r));
    }

    @Test
    @DisplayName("T26 PUT /replenishment-policies upsert：OPERATOR 200，返回 PolicyView")
    void policyUpsert() throws Exception {
        String body = "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_ID
                + ",\"lead_time_days\":5,\"coverage_days\":7,\"safety_stock_qty\":\"8.000\",\"is_enabled\":true}";
        ResponseEntity<String> r = put("/api/v1/replenishment-policies", body, operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        JsonNode data = objectMapper.readTree(r.getBody()).get("data");
        assertEquals(SKU_A, data.get("sku_id").asLong());
        assertEquals(true, data.get("is_enabled").asBoolean());
    }

    @Test
    @DisplayName("T27 PUT /replenishment-policies upsert 缺 sku_id → 400 COM-0001")
    void policyUpsertMissingSku() throws Exception {
        String body = "{\"warehouse_id\":" + WAREHOUSE_ID + ",\"lead_time_days\":5}";
        ResponseEntity<String> r = put("/api/v1/replenishment-policies", body, operatorToken());
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0001", code(r));
    }

    @Test
    @DisplayName("T28 PUT /replenishment-policies upsert 参数负值 → 400 COM-0001（CAT-3010 未固化，降级）")
    void policyUpsertNegative() throws Exception {
        String body = "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_ID + ",\"lead_time_days\":-1}";
        ResponseEntity<String> r = put("/api/v1/replenishment-policies", body, operatorToken());
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0001", code(r));
    }

    @Test
    @DisplayName("T29 PUT /replenishment-policies upsert 幂等：同一 (sku,warehouse) 二次 upsert 不新增行（uk 兜底）")
    void policyUpsertIdempotent() throws Exception {
        String body = "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_ID
                + ",\"safety_stock_qty\":\"8.000\",\"remark\":\"首次\"}";
        assertEquals(HttpStatus.OK, put("/api/v1/replenishment-policies", body, operatorToken()).getStatusCode());
        String body2 = "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_ID
                + ",\"safety_stock_qty\":\"9.000\",\"remark\":\"二次\"}";
        assertEquals(HttpStatus.OK, put("/api/v1/replenishment-policies", body2, operatorToken()).getStatusCode());

        int total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM replenishment_policies WHERE team_id=? AND sku_id=? AND warehouse_id=?",
                Integer.class, TEAM_ID, SKU_A, WAREHOUSE_ID);
        assertEquals(1, total, "二次 upsert 不得新增行（uk_pol_team_sku_wh 吸收）");
        String remark = jdbc.queryForObject(
                "SELECT remark FROM replenishment_policies WHERE team_id=? AND sku_id=? AND warehouse_id=?",
                String.class, TEAM_ID, SKU_A, WAREHOUSE_ID);
        assertEquals("二次", remark, "二次 upsert 必须覆盖字段");
    }

    @Test
    @DisplayName("T30 DELETE /replenishment-policies/{id}：OPERATOR 停用 → is_enabled=false（不物理删除）")
    void policyDisable() throws Exception {
        long id = policyIdBySkuAndWarehouse(SKU_D, WAREHOUSE_ID);
        ResponseEntity<String> r = delete("/api/v1/replenishment-policies/" + id, operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        assertEquals(false, objectMapper.readTree(r.getBody()).get("data").get("is_enabled").asBoolean());

        int stillThere = jdbc.queryForObject(
                "SELECT COUNT(*) FROM replenishment_policies WHERE id=?", Integer.class, id);
        assertEquals(1, stillThere, "停用不得物理删除，行仍在");
        int enabled = jdbc.queryForObject(
                "SELECT is_enabled FROM replenishment_policies WHERE id=?", Integer.class, id);
        assertEquals(0, enabled, "is_enabled 必须置 0");
    }

    @Test
    @DisplayName("T31 契约包络：GET /balances 返回 code=OK、timestamp 带 Z")
    void contractEnvelope() throws Exception {
        ResponseEntity<String> r = get("/api/v1/inventory/balances", operatorToken());
        JsonNode body = objectMapper.readTree(r.getBody());
        assertEquals("OK", body.get("code").asText());
        assertEquals("success", body.get("message").asText());
        assertTrue(body.get("timestamp").asText().endsWith("Z"), "timestamp 应带 Z");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, username);
    }

    private long txnIdByIdempotencyKey(String key) {
        return jdbc.queryForObject("SELECT id FROM inventory_transactions WHERE idempotency_key = ?",
                Long.class, key);
    }

    private long policyIdBySkuAndWarehouse(long skuId, long warehouseId) {
        return jdbc.queryForObject(
                "SELECT id FROM replenishment_policies WHERE sku_id=? AND warehouse_id=?",
                Long.class, skuId, warehouseId);
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

    private String loginAccessToken(String username) throws Exception {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + username + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, entity(null, bearer, null), String.class);
    }

    private ResponseEntity<String> post(String path, String jsonBody, String bearer, String idemKey) {
        return rest.exchange(path, HttpMethod.POST, entity(jsonBody, bearer, idemKey), String.class);
    }

    private ResponseEntity<String> put(String path, String jsonBody, String bearer) {
        return rest.exchange(path, HttpMethod.PUT, entity(jsonBody, bearer, null), String.class);
    }

    private ResponseEntity<String> delete(String path, String bearer) {
        return rest.exchange(path, HttpMethod.DELETE, entity(null, bearer, null), String.class);
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

    private String code(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("code").asText();
    }
}
