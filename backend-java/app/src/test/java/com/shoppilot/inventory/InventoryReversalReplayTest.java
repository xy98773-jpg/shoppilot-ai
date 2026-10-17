package com.shoppilot.inventory;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-B9.5（{@code R73}）—— <b>冲销的幂等重放必须排在「已被冲销」三态校验之前</b>
 * （{@code API_CONTRACT §6.3}：同幂等键 + 请求体一致 ⇒ 返回首次结果，不重复执行）。
 *
 * <p>旧顺序下（{@code INV-5009} 在 {@code replayIfIdempotent} 之前）首次冲销成功后，
 * 客户端因超时重试同一请求会拿到 <b>409</b>，把一次成功的操作报成失败，且响应里没有
 * {@code transaction_id}；本次回修把重放判定前移，并用三类回归点固定语义：
 * <ol>
 *   <li><b>同键同体</b> → 200 + 首次的 {@code transaction_id} + {@code Idempotent-Replayed: true}，
 *       不新增 REVERSAL 流水、不新增 {@code #13} 审计；</li>
 *   <li><b>同键不同体</b>（换一笔原流水）→ {@code INV-5003}（幂等键内容冲突）；</li>
 *   <li><b>不同键二次冲销</b> → 仍 {@code INV-5009}（重复冲销的拦截<b>未被</b>回修削弱）。</li>
 * </ol>
 *
 * <p>★ 第 4 例覆盖调整端点的重放响应头；第 5 例是 {@code R74}（{@code M3-B9.6}）的<b>反向</b>防线：
 * 响应头判据是 {@code StockChangeResult.replayed()}（真重放才带），{@code idempotentHit()} 不能直接用
 * —— 它把「盘点差额为 0 的无变更成功」也算命中，会给一次正常的无差异盘点误标「已重放」。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InventoryReversalReplayTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OWNER_ID = 1004L;
    private static final long OPERATOR_ID = 1001L;

    private static final long PRODUCT_ID = 9001L;
    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;
    private static final long WAREHOUSE_ID = 9004L;

    private static final String ACTION_REVERSE = "INVENTORY_REVERSE";
    private static final String ACTION_COUNT = "INVENTORY_COUNT_ADJUST";

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
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
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
        insertUser(OPERATOR_ID, "operator01");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID);

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

        // 两笔可冲销的原流水：同键异体用例需要「第二笔」来制造内容差异
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                        + "qty_before, qty_after, biz_type, idempotency_key, reason, operator_id, occurred_at, created_by) "
                        + "VALUES (?, ?, ?, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9002', "
                        + "'种子导入', ?, NOW(3), ?), "
                        + "(?, ?, ?, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9003', "
                        + "'种子导入', ?, NOW(3), ?)",
                TEAM_ID, WAREHOUSE_ID, SKU_A, OPERATOR_ID, OPERATOR_ID,
                TEAM_ID, WAREHOUSE_ID, SKU_B, OPERATOR_ID, OPERATOR_ID);
    }

    // =====================================================================
    // R73-1 同键同体 → 重放
    // =====================================================================

    @Test
    @DisplayName("R73 同键同体重放 → 200 + 首次 transaction_id + Idempotent-Replayed: true，流水与审计均不增")
    void sameKeySameBodyReplaysFirstResult() throws Exception {
        String token = ownerToken();
        long original = txnIdByIdempotencyKey("seed-init-9002");
        String body = "{\"reason\":\"导入错误\"}";

        ResponseEntity<String> first = post("/api/v1/inventory/transactions/" + original + "/reverse",
                body, token, "idem-r73-replay");
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        long firstTxnId = objectMapper.readTree(first.getBody()).get("data").get("transaction_id").asLong();
        assertNull(first.getHeaders().getFirst("Idempotent-Replayed"), "首次【有变更】执行不带重放头");

        ResponseEntity<String> replay = post("/api/v1/inventory/transactions/" + original + "/reverse",
                body, token, "idem-r73-replay");
        assertEquals(HttpStatus.OK, replay.getStatusCode(),
                "★ 同键同体必须重放 200，而不是 409 INV-5009；实测：" + replay.getBody());
        assertEquals(firstTxnId,
                objectMapper.readTree(replay.getBody()).get("data").get("transaction_id").asLong(),
                "重放必须返回首次的 transaction_id");
        assertEquals("true", replay.getHeaders().getFirst("Idempotent-Replayed"),
                "重放须补 Idempotent-Replayed: true（§6.3）");

        assertEquals(1, reversalCount(), "重放不得重复落库");
        assertEquals(1, countAudit(ACTION_REVERSE), "重放不产生新变更 ⇒ #13 审计仍 1 条");
        assertEquals("0.000", onHand(SKU_A), "余额只回退一次");
    }

    // =====================================================================
    // R73-2 同键异体 → INV-5003
    // =====================================================================

    @Test
    @DisplayName("R73 同键不同体（换一笔原流水）→ 409 INV-5003，第二笔原流水未被冲销")
    void sameKeyDifferentBodyConflicts() throws Exception {
        String token = ownerToken();
        long originalA = txnIdByIdempotencyKey("seed-init-9002");
        long originalB = txnIdByIdempotencyKey("seed-init-9003");

        assertEquals(HttpStatus.OK, post("/api/v1/inventory/transactions/" + originalA + "/reverse",
                "{\"reason\":\"导入错误\"}", token, "idem-r73-conflict").getStatusCode());

        ResponseEntity<String> conflict = post("/api/v1/inventory/transactions/" + originalB + "/reverse",
                "{\"reason\":\"导入错误\"}", token, "idem-r73-conflict");
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), conflict.getBody());
        assertEquals("INV-5003", code(conflict), "同键不同内容 → INV-5003（由 replayIfIdempotent 判定）");

        assertEquals(1, reversalCount(), "冲突不得写入第二笔冲销");
        assertEquals("10.000", onHand(SKU_B), "第二笔原流水的余额不得被冲销");
    }

    // =====================================================================
    // R73-3 不同键二次冲销 → INV-5009（回归点）
    // =====================================================================

    @Test
    @DisplayName("R73 回归：不同键二次冲销 → 仍 409 INV-5009（重放前移不得削弱重复冲销拦截）")
    void secondReversalWithDifferentKeyStillConflicts() throws Exception {
        String token = ownerToken();
        long original = txnIdByIdempotencyKey("seed-init-9002");

        assertEquals(HttpStatus.OK, post("/api/v1/inventory/transactions/" + original + "/reverse",
                "{\"reason\":\"导入错误\"}", token, "idem-r73-first").getStatusCode());

        ResponseEntity<String> second = post("/api/v1/inventory/transactions/" + original + "/reverse",
                "{\"reason\":\"再冲一次\"}", token, "idem-r73-second");
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode(), second.getBody());
        assertEquals("INV-5009", code(second), "不同键的重复冲销必须仍被拦下");

        assertEquals(1, reversalCount());
        assertEquals(1, countAudit(ACTION_REVERSE), "被拒的请求不写审计");
    }

    // =====================================================================
    // 调整端点的重放响应头（#11/#12 共用同一判定）
    // =====================================================================

    @Test
    @DisplayName("调整端点重放：同键同体 → 201 + 同一 transaction_id + Idempotent-Replayed: true")
    void adjustmentReplaySetsHeader() throws Exception {
        String token = operatorToken();
        String body = countAdjustBody(SKU_A, "7.000", "月度盘点");

        ResponseEntity<String> first = post("/api/v1/inventory/adjustments", body, token, "idem-r73-adj");
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        long firstTxnId = objectMapper.readTree(first.getBody()).get("data").get("transaction_id").asLong();
        assertNull(first.getHeaders().getFirst("Idempotent-Replayed"), "首次【有变更】执行不带重放头");

        ResponseEntity<String> replay = post("/api/v1/inventory/adjustments", body, token, "idem-r73-adj");
        assertEquals(HttpStatus.CREATED, replay.getStatusCode(), replay.getBody());
        assertEquals(firstTxnId,
                objectMapper.readTree(replay.getBody()).get("data").get("transaction_id").asLong());
        assertEquals("true", replay.getHeaders().getFirst("Idempotent-Replayed"));
        assertEquals("7.000", onHand(SKU_A), "重放不得再次调整余额");
        assertEquals(1, countAudit(ACTION_COUNT), "重放不写第二条审计");
    }

    // =====================================================================
    // R74 无变更成功不得标成重放（M3-B9.6）
    // =====================================================================

    @Test
    @DisplayName("R74 首次差额 0（目标 == 当前余额）→ 201 + transaction_id 为 null + 无 Idempotent-Replayed 头 + #12 审计不增")
    void zeroDeltaCountAdjustIsNotReplay() throws Exception {
        String token = operatorToken();
        int txnBefore = txnCount();
        int auditBefore = countAudit(ACTION_COUNT);

        // 种子余额 10.000，盘点目标也是 10.000 ⇒ 差额 0，按 §5.5 步骤 G 不写流水直接成功
        ResponseEntity<String> response = post("/api/v1/inventory/adjustments",
                countAdjustBody(SKU_A, "10.000", "盘点无差异"), token, "idem-r74-zero");
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());

        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertTrue(data.get("transaction_id").isNull(),
                "差额 0 不写流水 ⇒ transaction_id 必须为 null，实测：" + response.getBody());
        assertNull(response.getHeaders().getFirst("Idempotent-Replayed"),
                "★ R74：无变更成功既不是重放也不是新执行，不得带 Idempotent-Replayed 头");
        assertEquals(txnBefore, txnCount(), "不得新增流水");
        assertEquals(auditBefore, countAudit(ACTION_COUNT), "无变更不写 #12 审计（B9 裁定）");
        assertEquals("10.000", onHand(SKU_A), "余额不得变动");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, username);
    }

    private String onHand(long skuId) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE team_id = ? AND warehouse_id = ? AND sku_id = ?",
                BigDecimal.class, TEAM_ID, WAREHOUSE_ID, skuId);
        return value == null ? null : value.toPlainString();
    }

    private int txnCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE team_id = ?",
                Integer.class, TEAM_ID);
    }

    private int reversalCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions "
                + "WHERE team_id = ? AND txn_type = 'REVERSAL'", Integer.class, TEAM_ID);
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

    private String ownerToken() throws Exception {
        return loginAccessToken("owner01");
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
