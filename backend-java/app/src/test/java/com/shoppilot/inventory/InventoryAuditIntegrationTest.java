package com.shoppilot.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.TestCredentials;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-T17（B9）审计埋点 —— 端到端集成测试（真实 MySQL + 真实 Spring 上下文 + 真实 HTTP）。
 *
 * <p><b>真栈</b>：MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移 + 安全过滤链 + 租户线 +
 * {@code @RequireRole} 切面 + {@code AuditWriteService}（{@code REQUIRES_NEW} 独立事务）。
 *
 * <p>覆盖 {@code DESIGN_BASIS §9.17} / {@code DATABASE_DESIGN §6-J.1} 的 4 条库存侧动作：
 * {@code #7 REPLENISHMENT_POLICY_UPSERT}、{@code #11 INVENTORY_MANUAL_ADJUST}、
 * {@code #12 INVENTORY_COUNT_ADJUST}、{@code #13 INVENTORY_REVERSE}。
 *
 * <p>★ <b>计数口径</b>：一律 {@code WHERE action = ? AND team_id = ?}，<b>绝不</b>用全表
 * {@code COUNT(*)}——登录本身也会写 {@code AUTH_LOGIN_SUCCESS}，同库的其他用例也在写审计表。
 *
 * <p>★ <b>无审计读端点</b>：工程内没有 {@code GET /api/v1/audit-logs}（审计仅写、由 DBA 侧查询），
 * 故「团队隔离」只能以 SQL 断言 {@code team_id} 归属来证明，不做「团队 8 的成员读不到」的越界断言
 * （没有可越界的入口，造一个假入口去证明等于自证）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InventoryAuditIntegrationTest {

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
    private static final long WAREHOUSE_ID = 9004L;
    private static final long OTHER_PRODUCT_ID = 9101L;
    private static final long OTHER_SKU_ID = 9102L;
    private static final long OTHER_WAREHOUSE_ID = 9103L;

    /** 审计动作常量（与 {@code AuditAction} 名称一致，用字面量便于 grep 与断言语义对齐）。 */
    private static final String ACTION_POLICY = "REPLENISHMENT_POLICY_UPSERT";
    private static final String ACTION_MANUAL = "INVENTORY_MANUAL_ADJUST";
    private static final String ACTION_COUNT = "INVENTORY_COUNT_ADJUST";
    private static final String ACTION_REVERSE = "INVENTORY_REVERSE";

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
        // 审计表先清（登录也会写它，必须让每个用例从 0 开始）
        jdbc.execute("DELETE FROM audit_logs");
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
                + "(?, ?, ?, 'SKU-OTHER', 'SKU-OTHER', 'ACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_ID,
                SKU_B, TEAM_ID, PRODUCT_ID,
                OTHER_SKU_ID, OTHER_TEAM_ID, OTHER_PRODUCT_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) VALUES "
                + "(?, ?, 'WH-MAIN', '主仓', 'ACTIVE'), (?, ?, 'WH-OTHER', '别家仓', 'ACTIVE')",
                WAREHOUSE_ID, TEAM_ID, OTHER_WAREHOUSE_ID, OTHER_TEAM_ID);

        // 余额：SKU-A / SKU-B 均为 10.000
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty, created_by, updated_by, version) VALUES "
                + "(?, ?, ?, 10.000, 0.000, 0.000, ?, ?, 0), "
                + "(?, ?, ?, 10.000, 0.000, 0.000, ?, ?, 0)",
                TEAM_ID, WAREHOUSE_ID, SKU_A, OPERATOR_ID, OPERATOR_ID,
                TEAM_ID, WAREHOUSE_ID, SKU_B, OPERATOR_ID, OPERATOR_ID);

        // 可冲销的原流水（INIT_IMPORT +10，qty_after=10 与余额一致）
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                + "qty_before, qty_after, biz_type, idempotency_key, reason, operator_id, occurred_at, created_by) "
                + "VALUES (?, ?, ?, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', 'seed-init-9002', "
                + "'种子导入', ?, NOW(3), ?)",
                TEAM_ID, WAREHOUSE_ID, SKU_A, OPERATOR_ID, OPERATOR_ID);
    }

    // =====================================================================
    // #12 COUNT_ADJUST
    // =====================================================================

    @Test
    @DisplayName("#12 盘点调整 → 恰 1 条审计：action/biz_type/biz_id(=新流水 id)/result/operator 正确，摘要含前后值")
    void countAdjustWritesExactlyOneAudit() throws Exception {
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments",
                countAdjustBody(SKU_A, "7.000", "月度盘点"), operatorToken(), "idem-audit-count");
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), r.getBody());
        long txnId = objectMapper.readTree(r.getBody()).get("data").get("transaction_id").asLong();

        assertEquals(1, countAudit(ACTION_COUNT), "一次盘点调整应恰写 1 条 #12 审计");
        Map<String, Object> row = onlyAudit(ACTION_COUNT);
        assertEquals("INVENTORY_BALANCE", row.get("biz_type"));
        assertEquals(txnId, ((Number) row.get("biz_id")).longValue(), "biz_id 应等于本次新增流水 id");
        assertEquals("SUCCESS", row.get("result"), "审计只写成功（result=SUCCESS）");
        assertEquals(TEAM_ID, ((Number) row.get("team_id")).longValue());
        assertEquals(OPERATOR_ID, ((Number) row.get("operator_id")).longValue());
        assertEquals("OPERATOR", row.get("operator_role"), "operator_role 取 TeamContext 角色首元素");

        String summary = String.valueOf(row.get("change_summary"));
        assertTrue(summary.contains("10.000 → 7.000"), "摘要必须含前后值，实测：" + summary);
        assertTrue(summary.contains("COUNT_ADJUST"), "摘要需带调整类型，实测：" + summary);
        assertTrue(summary.contains("原因：月度盘点"), "摘要需带原因，实测：" + summary);
        assertTrue(summary.contains("流水 " + txnId), "摘要需带流水 id，实测：" + summary);
        assertEquals("/api/v1/inventory/adjustments", row.get("request_uri"), "HTTP 上下文应自动采集");

        assertEquals("10.000", beforeField(row, "on_hand_qty"));
        assertEquals("7.000", afterField(row, "on_hand_qty"));
    }

    // =====================================================================
    // #11 MANUAL_ADJUST
    // =====================================================================

    @Test
    @DisplayName("#11 手工调整（ADMIN）→ 恰 1 条审计：biz_id=新流水 id，摘要 10.000 → 8.000")
    void manualAdjustWritesExactlyOneAudit() throws Exception {
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments",
                manualAdjustBody(SKU_B, "-2.000", "破损"), adminToken(), "idem-audit-manual");
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), r.getBody());
        long txnId = objectMapper.readTree(r.getBody()).get("data").get("transaction_id").asLong();

        assertEquals(1, countAudit(ACTION_MANUAL), "一次手工调整应恰写 1 条 #11 审计");
        Map<String, Object> row = onlyAudit(ACTION_MANUAL);
        assertEquals("INVENTORY_BALANCE", row.get("biz_type"));
        assertEquals(txnId, ((Number) row.get("biz_id")).longValue());
        assertEquals(ADMIN_ID, ((Number) row.get("operator_id")).longValue());
        assertEquals("ADMIN", row.get("operator_role"));
        String summary = String.valueOf(row.get("change_summary"));
        assertTrue(summary.contains("10.000 → 8.000"), "摘要必须含前后值，实测：" + summary);
        assertTrue(summary.contains("MANUAL_ADJUST"), "实测：" + summary);
        assertTrue(summary.contains("原因：破损"), "实测：" + summary);
    }

    // =====================================================================
    // #13 INVENTORY_REVERSE
    // =====================================================================

    @Test
    @DisplayName("#13 冲销 → 恰 1 条审计：biz_id=被冲销的原流水 id，摘要含新 REVERSAL 流水 id 与回退后余额")
    void reversalAuditUsesOriginalTxnId() throws Exception {
        long original = txnIdByIdempotencyKey("seed-init-9002");
        ResponseEntity<String> r = post("/api/v1/inventory/transactions/" + original + "/reverse",
                "{\"reason\":\"导入错误\"}", ownerToken(), "idem-audit-rev");
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        long newTxnId = objectMapper.readTree(r.getBody()).get("data").get("transaction_id").asLong();

        assertEquals(1, countAudit(ACTION_REVERSE), "一次冲销应恰写 1 条 #13 审计");
        Map<String, Object> row = onlyAudit(ACTION_REVERSE);
        assertEquals("INVENTORY_TRANSACTION", row.get("biz_type"));
        assertEquals(original, ((Number) row.get("biz_id")).longValue(),
                "★ biz_id 必须是被冲销的原流水 id（不是新流水）");
        String summary = String.valueOf(row.get("change_summary"));
        assertTrue(summary.contains("冲销流水 " + original), "实测：" + summary);
        assertTrue(summary.contains("新流水 " + newTxnId), "新流水 id 必须进摘要，实测：" + summary);
        assertTrue(summary.contains("10.000 → 0.000"), "摘要需含回退后余额，实测：" + summary);
        assertTrue(summary.contains("sku=" + SKU_A) && summary.contains("仓库=" + WAREHOUSE_ID),
                "摘要需含 sku/仓库，实测：" + summary);
    }

    // =====================================================================
    // #7 REPLENISHMENT_POLICY_UPSERT（创建 / 更新 / 停用 同用一条 action）
    // =====================================================================

    @Test
    @DisplayName("#7 策略首次 upsert → 恰 1 条审计：biz_id=策略行 id，摘要为「无 → 值」")
    void policyCreateWritesExactlyOneAudit() throws Exception {
        ResponseEntity<String> r = put("/api/v1/replenishment-policies",
                policyBody(SKU_A, "8.000", 5), operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        long policyId = objectMapper.readTree(r.getBody()).get("data").get("id").asLong();

        assertEquals(1, countAudit(ACTION_POLICY), "首次 upsert 应恰写 1 条 #7 审计");
        Map<String, Object> row = onlyAudit(ACTION_POLICY);
        assertEquals("REPLENISHMENT_POLICY", row.get("biz_type"));
        assertEquals(policyId, ((Number) row.get("biz_id")).longValue(), "biz_id 应为策略行 id");
        String summary = String.valueOf(row.get("change_summary"));
        assertTrue(summary.startsWith("策略 " + policyId + " 变更：sku=" + SKU_A + " 仓库=" + WAREHOUSE_ID),
                "摘要格式须与 §6-J.3 口径一致，实测：" + summary);
        assertTrue(summary.contains("安全库存 无 → 8.000"), "创建时 before 为「无」，实测：" + summary);
        assertTrue(summary.contains("提前期天数 无 → 5"), "实测：" + summary);
    }

    @Test
    @DisplayName("#7 策略二次 upsert（只改安全库存）→ +1 条且只记实际变化字段，biz_id 不变")
    void policyUpdateWritesOnlyChangedFields() throws Exception {
        long policyId = objectMapper.readTree(put("/api/v1/replenishment-policies",
                policyBody(SKU_A, "8.000", 5), operatorToken()).getBody()).get("data").get("id").asLong();
        ResponseEntity<String> r = put("/api/v1/replenishment-policies",
                policyBody(SKU_A, "12.000", 5), operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());

        assertEquals(2, countAudit(ACTION_POLICY), "两次 upsert 应各写 1 条（一次请求 = 一条审计）");
        Map<String, Object> latest = latestAudit(ACTION_POLICY);
        assertEquals(policyId, ((Number) latest.get("biz_id")).longValue(), "同一策略的 biz_id 必须一致");
        String summary = String.valueOf(latest.get("change_summary"));
        assertTrue(summary.contains("安全库存 8.000 → 12.000"), "实测：" + summary);
        assertFalse(summary.contains("提前期天数"),
                "未变化的字段不得进摘要（只记实际变化），实测：" + summary);
    }

    @Test
    @DisplayName("#7 策略停用 → +1 条审计，摘要恰为「停用策略 {id}」，与 upsert 同一 biz_id")
    void policyDisableWritesExactlyOneAudit() throws Exception {
        long policyId = objectMapper.readTree(put("/api/v1/replenishment-policies",
                policyBody(SKU_A, "8.000", 5), operatorToken()).getBody()).get("data").get("id").asLong();
        ResponseEntity<String> r = delete("/api/v1/replenishment-policies/" + policyId, operatorToken());
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());

        assertEquals(2, countAudit(ACTION_POLICY), "upsert 1 条 + disable 1 条");
        Map<String, Object> latest = latestAudit(ACTION_POLICY);
        assertEquals(policyId, ((Number) latest.get("biz_id")).longValue(), "disable 的 biz_id 应为策略行 id");
        assertEquals("停用策略 " + policyId, latest.get("change_summary"));
        assertEquals("1", beforeField(latest, "is_enabled"), "停用前 is_enabled=1");
        assertEquals("0", afterField(latest, "is_enabled"), "停用后 is_enabled=0");
    }

    @Test
    @DisplayName("#7 策略参数非法（负提前期）→ 400 COM-0001，不写审计")
    void policyValidationFailureWritesNoAudit() throws Exception {
        ResponseEntity<String> r = put("/api/v1/replenishment-policies",
                "{\"sku_id\":" + SKU_A + ",\"warehouse_id\":" + WAREHOUSE_ID + ",\"lead_time_days\":-1}",
                operatorToken());
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("COM-0001", code(r));
        assertEquals(0, countAudit(ACTION_POLICY), "校验失败不得写审计");
    }

    // =====================================================================
    // 失败路径不写审计
    // =====================================================================

    @Test
    @DisplayName("INV-5006（盘点目标为负 → 调整后为负）→ 409 且审计条数不变")
    void negativeBalanceWritesNoAudit() throws Exception {
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments",
                countAdjustBody(SKU_A, "-1.000", "错误目标"), operatorToken(), "idem-audit-neg");
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode(), r.getBody());
        assertEquals("INV-5006", code(r));
        assertEquals(0, countAudit(ACTION_COUNT), "失败不得写审计");
    }

    @Test
    @DisplayName("INV-5005（手工调整量为 0）→ 400 且审计条数不变")
    void zeroDeltaWritesNoAudit() throws Exception {
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments",
                manualAdjustBody(SKU_A, "0.000", "零调整"), adminToken(), "idem-audit-zero");
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(), r.getBody());
        assertEquals("INV-5005", code(r));
        assertEquals(0, countAudit(ACTION_MANUAL), "失败不得写审计");
    }

    @Test
    @DisplayName("INV-5009（二次冲销）→ 409 且审计不增（仍为 1 条）")
    void doubleReversalWritesOnlyOneAudit() throws Exception {
        long original = txnIdByIdempotencyKey("seed-init-9002");
        assertEquals(HttpStatus.OK, post("/api/v1/inventory/transactions/" + original + "/reverse",
                "{\"reason\":\"导入错误\"}", ownerToken(), "idem-audit-rev-1").getStatusCode());
        ResponseEntity<String> second = post("/api/v1/inventory/transactions/" + original + "/reverse",
                "{\"reason\":\"再冲一次\"}", ownerToken(), "idem-audit-rev-2");
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode(), second.getBody());
        assertEquals("INV-5009", code(second));
        assertEquals(1, countAudit(ACTION_REVERSE), "二次冲销被拒，不得新增审计");
    }

    // =====================================================================
    // 越权不写审计
    // =====================================================================

    @Test
    @DisplayName("越权：VIEWER 调 adjustments → 403 TEAM-2003，审计条数不变")
    void viewerForbiddenWritesNoAudit() throws Exception {
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments",
                countAdjustBody(SKU_A, "7.000", "越权"), viewerToken(), "idem-audit-viewer");
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertEquals("TEAM-2003", code(r));
        assertEquals(0, countAudit(ACTION_COUNT), "越权请求不得写审计");
    }

    @Test
    @DisplayName("越权：OPERATOR 做 MANUAL_ADJUST → 403 INV-5007，审计条数不变")
    void operatorManualAdjustForbiddenWritesNoAudit() throws Exception {
        ResponseEntity<String> r = post("/api/v1/inventory/adjustments",
                manualAdjustBody(SKU_A, "-2.000", "越权"), operatorToken(), "idem-audit-op");
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertEquals("INV-5007", code(r));
        assertEquals(0, countAudit(ACTION_MANUAL), "角色不足不得写审计");
    }

    // =====================================================================
    // 幂等重放 + 团队归属
    // =====================================================================

    @Test
    @DisplayName("幂等重放（同 Idempotency-Key 同内容）→ 仍 1 条审计，不重复计数")
    void idempotentReplayWritesNoDuplicateAudit() throws Exception {
        String body = countAdjustBody(SKU_A, "7.000", "月度盘点");
        ResponseEntity<String> first = post("/api/v1/inventory/adjustments", body, operatorToken(), "idem-replay-1");
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        long firstTxn = objectMapper.readTree(first.getBody()).get("data").get("transaction_id").asLong();

        ResponseEntity<String> replay = post("/api/v1/inventory/adjustments", body, operatorToken(), "idem-replay-1");
        assertEquals(HttpStatus.CREATED, replay.getStatusCode(), replay.getBody());
        assertEquals(firstTxn, objectMapper.readTree(replay.getBody()).get("data").get("transaction_id").asLong(),
                "重放应命中同一条流水");

        assertEquals(1, countAudit(ACTION_COUNT),
                "★ 重放不产生新变更 ⇒ 不写第二条审计（否则同一次变更被计两次，且 occurred_at/前后值都不是本次事实）");
    }

    @Test
    @DisplayName("团队归属：审计行 team_id = 当前团队 7，团队 8 名下 0 条")
    void auditRowBelongsToCurrentTeam() throws Exception {
        assertEquals(HttpStatus.CREATED, post("/api/v1/inventory/adjustments",
                countAdjustBody(SKU_A, "7.000", "团队归属"), operatorToken(), "idem-team").getStatusCode());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND team_id = ?",
                Integer.class, ACTION_COUNT, TEAM_ID));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND team_id = ?",
                Integer.class, ACTION_COUNT, OTHER_TEAM_ID),
                "审计不得落到别的团队名下");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE team_id = ?", Integer.class, OTHER_TEAM_ID),
                "本次请求全程不应产生任何团队 8 的审计行");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, username);
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

    private String policyBody(long skuId, String safetyStock, int leadTimeDays) {
        return "{\"sku_id\":" + skuId + ",\"warehouse_id\":" + WAREHOUSE_ID
                + ",\"lead_time_days\":" + leadTimeDays + ",\"coverage_days\":7"
                + ",\"safety_stock_qty\":\"" + safetyStock + "\",\"is_enabled\":true}";
    }

    /** 按 action + team_id 精确计数（★ 不用全表 COUNT，登录审计与其他用例都在写这张表）。 */
    private int countAudit(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = ? AND team_id = ?",
                Integer.class, action, TEAM_ID);
    }

    private Map<String, Object> onlyAudit(String action) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM audit_logs WHERE action = ? AND team_id = ? ORDER BY id", action, TEAM_ID);
        assertEquals(1, rows.size(), "应恰有 1 条 " + action + " 审计，实测 " + rows.size());
        return rows.get(0);
    }

    private Map<String, Object> latestAudit(String action) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM audit_logs WHERE action = ? AND team_id = ? ORDER BY id DESC LIMIT 1",
                action, TEAM_ID);
        assertEquals(1, rows.size(), "应至少有一条 " + action + " 审计");
        return rows.get(0);
    }

    private String beforeField(Map<String, Object> row, String field) throws Exception {
        return jsonField(row.get("before_json"), field);
    }

    private String afterField(Map<String, Object> row, String field) throws Exception {
        return jsonField(row.get("after_json"), field);
    }

    private String jsonField(Object json, String field) throws Exception {
        assertNotNull(json, field + " 所在 JSON 不能为空");
        JsonNode node = objectMapper.readTree(String.valueOf(json)).get(field);
        assertNotNull(node, "JSON 里缺字段 " + field + "，实际：" + json);
        return node.asText();
    }

    private long txnIdByIdempotencyKey(String key) {
        return jdbc.queryForObject("SELECT id FROM inventory_transactions WHERE idempotency_key = ?",
                Long.class, key);
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
