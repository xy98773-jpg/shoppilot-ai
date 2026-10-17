package com.shoppilot.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.math.BigDecimal;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-B9.5（{@code R72}）—— <b>高风险操作审计必须与业务同事务</b>（真栈 HTTP + DB 断言）。
 *
 * <p>验证手法：在测试库给 {@code audit_logs} 装一个 {@code BEFORE INSERT} 触发器，
 * 让<b>任何</b>审计写入都以 {@code SQLSTATE 45000} 失败，然后发一次写请求，观察：
 * <ul>
 *   <li>库存调整（{@code #11}/{@code #12}）/ 冲销（{@code #13}）走
 *       {@code AuditWriteService.recordInBusinessTx}（{@code MANDATORY}）⇒
 *       <b>审计写不进 ⇒ 业务整体回滚</b>：非 2xx、余额未变、流水未增、审计 0 行；</li>
 *   <li>补货策略（{@code #7}）走 {@code record}（{@code REQUIRES_NEW}，失败只记 ERROR）⇒
 *       <b>业务照常成功</b>，只是没有审计行 —— 这是 {@code §2.10} 高风险清单<b>不含</b>
 *       策略变更的<b>有意</b>差异（两者不该被"统一"）。</li>
 * </ul>
 *
 * <p>★ 反证：{@code DROP TRIGGER} 后重发同一动作必须成功且「余额 / 流水 / 审计」三者各 +1，
 * 证明前面的失败<b>确实</b>由触发器导致，而不是用例本身写错了。
 *
 * <p>★ 计数口径：一律 {@code WHERE action = ? AND team_id = ?}，并同时断言<b>全表总数不变</b>
 * （登录本身也写审计，故"全表 0 行"在真栈里不成立）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InventoryAuditTxCouplingTest {

    /**
     * ★ {@code --log_bin_trust_function_creators=1}：MySQL 8 默认开启 binlog，普通账号建触发器会报
     * {@code ERROR 1419 (HY000): You do not have the SUPER privilege and binary logging is enabled}。
     * 本用例必须建 {@code BEFORE INSERT} 触发器才能制造「审计写不进」的场景，故在容器启动参数上放开
     * 该限制（<b>仅测试容器</b>，不影响任何生产配置）。
     */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00", "--log_bin_trust_function_creators=1")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OWNER_ID = 1004L;
    private static final long ADMIN_ID = 1005L;
    private static final long OPERATOR_ID = 1001L;

    private static final long PRODUCT_ID = 9001L;
    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;
    private static final long WAREHOUSE_ID = 9004L;

    private static final String ACTION_POLICY = "REPLENISHMENT_POLICY_UPSERT";
    private static final String ACTION_MANUAL = "INVENTORY_MANUAL_ADJUST";
    private static final String ACTION_COUNT = "INVENTORY_COUNT_ADJUST";
    private static final String ACTION_REVERSE = "INVENTORY_REVERSE";

    /** 触发器名：全库唯一，{@code @AfterEach} 兜底清理。 */
    private static final String BLOCK_TRIGGER = "trg_b95_block_audit";

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
        dropBlockingTrigger();
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM replenishment_policies");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);

        insertUser(OWNER_ID, "owner01");
        insertUser(ADMIN_ID, "admin01");
        insertUser(OPERATOR_ID, "operator01");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'ADMIN', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, ADMIN_ID, TEAM_ID, OPERATOR_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) VALUES "
                + "(?, ?, 'P-BAG-001', '箱包', 'ACTIVE')", PRODUCT_ID, TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                        + "(?, ?, ?, 'SKU-A', 'SKU-A', 'ACTIVE'), (?, ?, ?, 'SKU-B', 'SKU-B', 'ACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_ID, SKU_B, TEAM_ID, PRODUCT_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) VALUES "
                + "(?, ?, 'WH-MAIN', '主仓', 'ACTIVE')", WAREHOUSE_ID, TEAM_ID);

        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                        + "in_transit_qty, committed_qty, created_by, updated_by, version) VALUES "
                        + "(?, ?, ?, 10.000, 0.000, 0.000, ?, ?, 0), "
                        + "(?, ?, ?, 10.000, 0.000, 0.000, ?, ?, 0)",
                TEAM_ID, WAREHOUSE_ID, SKU_A, OPERATOR_ID, OPERATOR_ID,
                TEAM_ID, WAREHOUSE_ID, SKU_B, OPERATOR_ID, OPERATOR_ID);

        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                        + "qty_before, qty_after, biz_type, idempotency_key, reason, operator_id, occurred_at, created_by) "
                        + "VALUES (?, ?, ?, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9002', "
                        + "'种子导入', ?, NOW(3), ?)",
                TEAM_ID, WAREHOUSE_ID, SKU_A, OPERATOR_ID, OPERATOR_ID);
    }

    @AfterEach
    void cleanTrigger() {
        dropBlockingTrigger();
    }

    // =====================================================================
    // R72：审计写不进 ⇒ 业务整体回滚（#11 / #12 / #13）
    // =====================================================================

    @Test
    @DisplayName("R72 #12 盘点调整：审计被阻断 → 非 2xx，余额/流水/审计三不动；解除阻断后 201 且三者各 +1")
    void countAdjustRollsBackWhenAuditFails() throws Exception {
        String token = operatorToken();
        // ★ 先登录再装触发器：登录本身也写审计（AUTH_LOGIN_SUCCESS），不能把它算进本用例
        int auditBefore = totalAudit();
        int txnBefore = totalTxn();

        createBlockingTrigger();
        ResponseEntity<String> blocked = post("/api/v1/inventory/adjustments",
                countAdjustBody(SKU_A, "7.000", "月度盘点"), token, "idem-r72-count");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, blocked.getStatusCode(),
                "审计写不进必须让业务失败（同事务），实测：" + blocked.getBody());
        assertEquals("COM-0002", code(blocked), "未捕获异常兜底 → 500 COM-0002");

        assertEquals("10.000", onHand(SKU_A), "★ 业务必须回滚：余额不得变成 7.000");
        assertEquals(txnBefore, totalTxn(), "★ 业务必须回滚：不得留下本次流水");
        assertEquals(0, countAudit(ACTION_COUNT), "审计被阻断 ⇒ 0 行");
        assertEquals(auditBefore, totalAudit(), "全表审计行数也不得变化");

        // 反证：解除阻断后同样的动作必须成功，且「余额 / 流水 / 审计」各 +1
        dropBlockingTrigger();
        ResponseEntity<String> ok = post("/api/v1/inventory/adjustments",
                countAdjustBody(SKU_A, "7.000", "月度盘点"), token, "idem-r72-count-ok");
        assertEquals(HttpStatus.CREATED, ok.getStatusCode(), "解除阻断后应成功：" + ok.getBody());
        assertEquals("7.000", onHand(SKU_A));
        assertEquals(txnBefore + 1, totalTxn());
        assertEquals(1, countAudit(ACTION_COUNT), "成功后才应写入 1 条 #12 审计");
    }

    @Test
    @DisplayName("R72 #11 手工调整（ADMIN）：审计被阻断 → 非 2xx 且余额/流水/审计三不动")
    void manualAdjustRollsBackWhenAuditFails() throws Exception {
        String token = adminToken();
        int auditBefore = totalAudit();
        int txnBefore = totalTxn();

        createBlockingTrigger();
        ResponseEntity<String> blocked = post("/api/v1/inventory/adjustments",
                manualAdjustBody(SKU_B, "-2.000", "破损"), token, "idem-r72-manual");
        assertTrue(blocked.getStatusCode().is5xxServerError(),
                "审计写不进必须让业务失败，实测 " + blocked.getStatusCode() + " / " + blocked.getBody());

        assertEquals("10.000", onHand(SKU_B));
        assertEquals(txnBefore, totalTxn());
        assertEquals(0, countAudit(ACTION_MANUAL));
        assertEquals(auditBefore, totalAudit());
    }

    @Test
    @DisplayName("R72 #13 冲销：审计被阻断 → 非 2xx，不产生 REVERSAL 流水，余额与审计均不变")
    void reversalRollsBackWhenAuditFails() throws Exception {
        String token = ownerToken();
        long original = txnIdByIdempotencyKey("seed-init-9002");
        int auditBefore = totalAudit();
        int txnBefore = totalTxn();

        createBlockingTrigger();
        ResponseEntity<String> blocked = post("/api/v1/inventory/transactions/" + original + "/reverse",
                "{\"reason\":\"导入错误\"}", token, "idem-r72-rev");
        assertTrue(blocked.getStatusCode().is5xxServerError(),
                "审计写不进必须让业务失败，实测 " + blocked.getStatusCode() + " / " + blocked.getBody());

        assertEquals("10.000", onHand(SKU_A), "冲销必须一并回滚");
        assertEquals(txnBefore, totalTxn(), "不得留下 REVERSAL 流水");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transactions WHERE txn_type = 'REVERSAL' "
                        + "AND team_id = ?", Integer.class, TEAM_ID));
        assertEquals(0, countAudit(ACTION_REVERSE));
        assertEquals(auditBefore, totalAudit());
    }

    // =====================================================================
    // 有意差异：#7 策略变更仍走解耦通道（审计失败不阻塞业务）
    // =====================================================================

    @Test
    @DisplayName("对照 #7 策略：审计被阻断时仍 200 且策略已落库（走 record 解耦通道，§2.10 高风险清单不含策略变更）")
    void policyUpsertSurvivesAuditFailureByDesign() throws Exception {
        String token = operatorToken();
        createBlockingTrigger();

        ResponseEntity<String> r = put("/api/v1/replenishment-policies",
                policyBody(SKU_A, "8.000"), token);
        assertEquals(HttpStatus.OK, r.getStatusCode(),
                "策略变更不在 §2.10 高风险清单内，审计失败不得阻塞业务，实测：" + r.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replenishment_policies WHERE team_id = ? AND sku_id = ? "
                        + "AND warehouse_id = ?", Integer.class, TEAM_ID, SKU_A, WAREHOUSE_ID),
                "策略必须已落库");
        assertEquals(0, countAudit(ACTION_POLICY), "触发器阻断 ⇒ 审计行 0（业务照常成功）");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private void createBlockingTrigger() {
        jdbc.execute("CREATE TRIGGER " + BLOCK_TRIGGER + " BEFORE INSERT ON audit_logs "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit blocked by B9.5 test'");
    }

    private void dropBlockingTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + BLOCK_TRIGGER);
    }

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, username);
    }

    private String onHand(long skuId) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE team_id = ? AND warehouse_id = ? AND sku_id = ?",
                BigDecimal.class, TEAM_ID, WAREHOUSE_ID, skuId);
        assertNotNull(value, "余额行必须存在");
        return value.toPlainString();
    }

    private int totalAudit() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class);
    }

    private int totalTxn() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE team_id = ?",
                Integer.class, TEAM_ID);
    }

    private int countAudit(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = ? AND team_id = ?",
                Integer.class, action, TEAM_ID);
    }

    private long txnIdByIdempotencyKey(String key) {
        return jdbc.queryForObject("SELECT id FROM inventory_transactions WHERE idempotency_key = ?",
                Long.class, key);
    }

    private String countAdjustBody(long skuId, String target, String reason) {
        return "{\"warehouse_id\":" + WAREHOUSE_ID + ",\"sku_id\":" + skuId
                + ",\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"" + target
                + "\",\"reason\":\"" + reason + "\"}";
    }

    private String manualAdjustBody(long skuId, String delta, String reason) {
        return "{\"warehouse_id\":" + WAREHOUSE_ID + ",\"sku_id\":" + skuId
                + ",\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"" + delta
                + "\",\"reason\":\"" + reason + "\"}";
    }

    private String policyBody(long skuId, String safetyStock) {
        return "{\"sku_id\":" + skuId + ",\"warehouse_id\":" + WAREHOUSE_ID
                + ",\"lead_time_days\":5,\"coverage_days\":7"
                + ",\"safety_stock_qty\":\"" + safetyStock + "\",\"is_enabled\":true}";
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

    private String loginAccessToken(String username) throws Exception {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + username + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private ResponseEntity<String> post(String path, String jsonBody, String bearer, String idemKey) {
        return rest.exchange(path, HttpMethod.POST, entity(jsonBody, bearer, idemKey), String.class);
    }

    private ResponseEntity<String> put(String path, String jsonBody, String bearer) {
        return rest.exchange(path, HttpMethod.PUT, entity(jsonBody, bearer, null), String.class);
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
