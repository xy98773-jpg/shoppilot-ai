package com.shoppilot.purchasing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.math.BigDecimal;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * M8-T05（{@code R185}）—— 冲销 {@code PURCHASE_INBOUND} 必须<b>同事务</b>回退
 * {@code purchase_order_items.received_qty} 并重算 {@code purchase_orders.status}，
 * 否则派生在途（{@code ordered_qty − received_qty}）会少计。
 *
 * <p>覆盖（真栈：Spring 上下文 + Testcontainers {@code mysql:8.0.36} + 真实 SQL，
 * 禁止 Mock Mapper）：
 * <ul>
 *   <li>R185-1 部分到货（PARTIALLY_RECEIVED，已收 40/100）冲销 ⇒ received_qty 回退到 0、
 *       PO 状态退回 {@code ORDERED}、{@code closed_at} 清空、on_hand 回退到种子值、对账差异 0；</li>
 *   <li>R185-2 全部到货（RECEIVED，已收 100/100）单笔冲销 ⇒ 同上退回 {@code ORDERED}、
 *       {@code closed_at} 清空；</li>
 *   <li>R185-3 全部到货（两笔：40 + 60 = RECEIVED）冲销第二笔（60）⇒ received_qty 100→40、
 *       PO 状态 {@code RECEIVED → PARTIALLY_RECEIVED}、{@code closed_at} 清空；</li>
 *   <li>R185-4 幂等重放（同键同体）⇒ 200 + {@code Idempotent-Replayed: true}，
 *       <b>不重复回退</b> received_qty / on_hand。</li>
 * </ul>
 *
 * <p>★ 种子：余额行配套 INIT_IMPORT 流水（{@code TEST_STRATEGY §7} 对账口径），
 * 使「余额 = Σ 流水」恒成立；PURCHASE_INBOUND 流水按 {@code R180} 幂等键
 * {@code PURCHASE_INBOUND:GOODS_RECEIPT:<rid>:<sku>:<poItemId>} 种子，便于直接经 REST 端点冲销。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PurchaseInboundReversalIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM7 = 7L;
    private static final long OWNER_ID = 1004L;
    private static final long PRODUCT7 = 9001L;
    private static final long SKU7 = 9002L;
    private static final long WH7 = 9004L;
    private static final long SUPPLIER7 = 8001L;

    private static final long PO_ID = 1L;
    private static final long PO_ITEM_ID = 10L;
    private static final long RECEIPT_ID = 100L;
    private static final long INBOUND_TXN_A = 1000L;
    private static final long INBOUND_TXN_B = 1001L;

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

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM purchase_order_items");
        jdbc.execute("DELETE FROM purchase_orders");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-GRC-7', '确认七团', 'ACTIVE', 'Asia/Shanghai')", TEAM7);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'owner01', 'owner01@example.com', ?, '小主', 'ACTIVE')", OWNER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OWNER', 'ACTIVE')", TEAM7, OWNER_ID);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) VALUES "
                + "(?, ?, 'P-C7', '七团商品', 'ACTIVE')", PRODUCT7, TEAM7);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) VALUES "
                + "(?, ?, 'WH-C7', '七团仓', 'ACTIVE')", WH7, TEAM7);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status) VALUES "
                + "(?, ?, 'SUP-C7', '七团供应商', 'ACTIVE')", SUPPLIER7, TEAM7);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                + "(?, ?, ?, 'SKU-C7', '七团SKU', 'ACTIVE')", SKU7, TEAM7, PRODUCT7);
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM purchase_order_items");
        jdbc.execute("DELETE FROM purchase_orders");
        jdbc.execute("DELETE FROM audit_logs");
    }

    // =====================================================================
    // R185-1：部分到货冲销 → ORDERED
    // =====================================================================

    @Test
    @DisplayName("R185-1 部分到货冲销：received_qty 回退 0、PO→ORDERED、closed_at 清空、on_hand 回退、对账 0")
    void partialReceiptReversalRollsBackToOrdered() throws Exception {
        // ordered=100，已收 40（INIT_IMPORT 100 + PURCHASE_INBOUND 40 ⇒ on_hand 140）
        seedInbound("PARTIALLY_RECEIVED", "40.000", "40.000", "40.000",
                "100.000", "140.000", "140.000", INBOUND_TXN_A);

        String onHandBefore = onHand(SKU7);
        String token = ownerToken();

        ResponseEntity<String> res = post("/api/v1/inventory/transactions/" + INBOUND_TXN_A + "/reverse",
                "{\"reason\":\"冲销部分到货\"}", token, "idem-r185-1");
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        assertNull(res.getHeaders().getFirst("Idempotent-Replayed"), "首次执行不带重放头");

        Map<String, Object> po = poRow(PO_ID);
        assertEquals("0.000", qtyText(po.get("received_qty")), "采购单 received_qty 回退到 0");
        assertEquals("ORDERED", po.get("status"), "★ PARTIALLY_RECEIVED → ORDERED（派生在途须重新计入全额）");
        assertNull(po.get("closed_at"), "★ 非终态 closed_at 必须清空");
        assertEquals("0.000", qtyText(itemRow(PO_ITEM_ID).get("received_qty")), "明细行 received_qty 回退到 0");

        assertEquals("100.000", onHand(SKU7), "★ on_hand 回退到 INIT_IMPORT 种子值（140 − 40）");
        assertEquals(onHandBefore, "140.000", "冲销前 on_hand 应等于种子 140");
        assertEquals(1, reversalCount(), "恰好 1 条 REVERSAL 流水");
        assertReconciled();
    }

    // =====================================================================
    // R185-2：全部到货（单笔）冲销 → ORDERED
    // =====================================================================

    @Test
    @DisplayName("R185-2 全部到货（单笔）冲销：RECEIVED→ORDERED、closed_at 清空、on_hand 回退到种子")
    void fullReceiptReversalRollsBackToOrdered() throws Exception {
        // ordered=100，已收 100（INIT_IMPORT 100 + PURCHASE_INBOUND 100 ⇒ on_hand 200，RECEIVED 且 closed_at 非空）
        seedInbound("RECEIVED", "100.000", "100.000", "100.000",
                "100.000", "200.000", "200.000", INBOUND_TXN_A);
        jdbc.update("UPDATE purchase_orders SET closed_at = UTC_TIMESTAMP(3) WHERE id = ?", PO_ID);

        ResponseEntity<String> res = post("/api/v1/inventory/transactions/" + INBOUND_TXN_A + "/reverse",
                "{\"reason\":\"冲销全部到货\"}", ownerToken(), "idem-r185-2");
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());

        Map<String, Object> po = poRow(PO_ID);
        assertEquals("0.000", qtyText(po.get("received_qty")), "received_qty 回退到 0");
        assertEquals("ORDERED", po.get("status"), "★ RECEIVED → ORDERED");
        assertNull(po.get("closed_at"), "★ 清出终态必须清空 closed_at");
        assertEquals("100.000", onHand(SKU7), "on_hand 回退到 INIT_IMPORT 种子值（200 − 100）");
        assertEquals(1, reversalCount());
        assertReconciled();
    }

    // =====================================================================
    // R185-3：全部到货（两笔：40 + 60）冲销第二笔 → PARTIALLY_RECEIVED
    // =====================================================================

    @Test
    @DisplayName("R185-3 两笔到货（RECEIVED）冲销第二笔（60）：received 100→40、RECEIVED→PARTIALLY_RECEIVED")
    void secondReceiptReversalRollsBackToPartial() throws Exception {
        // 两笔入库：40 + 60 = 100（RECEIVED）。冲销第二笔 ⇒ 剩余 40 ⇒ PARTIALLY_RECEIVED
        seedInbound("RECEIVED", "100.000", "100.000", "40.000",
                "100.000", "140.000", "140.000", INBOUND_TXN_A);
        // 追加第二笔 PURCHASE_INBOUND（60，幂等键末段同为 PO_ITEM_ID=10 但 receipt 段用 101 区分），
        // 并把余额/PO 状态推到「全收」
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                        + "qty_before, qty_after, biz_type, biz_ref_id, idempotency_key, operator_id, occurred_at, created_by) "
                        + "VALUES (?, ?, ?, 'PURCHASE_INBOUND', ?, ?, ?, 'GOODS_RECEIPT', ?, "
                        + "'PURCHASE_INBOUND:GOODS_RECEIPT:101:" + SKU7 + ":" + PO_ITEM_ID + "', "
                        + "?, UTC_TIMESTAMP(3), ?)",
                TEAM7, WH7, SKU7, new BigDecimal("60.000"), new BigDecimal("140.000"),
                new BigDecimal("200.000"), 101L, OWNER_ID, OWNER_ID);
        jdbc.update("UPDATE inventory_balances SET on_hand_qty = 200.000, version = version + 1 "
                + "WHERE team_id = ? AND warehouse_id = ? AND sku_id = ?", TEAM7, WH7, SKU7);
        jdbc.update("UPDATE purchase_orders SET received_qty = 100.000, status = 'RECEIVED', "
                + "closed_at = UTC_TIMESTAMP(3), version = version + 1 WHERE id = ?", PO_ID);
        jdbc.update("UPDATE purchase_order_items SET received_qty = 100.000, version = version + 1 "
                + "WHERE id = ?", PO_ITEM_ID);

        ResponseEntity<String> res = post("/api/v1/inventory/transactions/" + INBOUND_TXN_B + "/reverse",
                "{\"reason\":\"冲销第二笔到货\"}", ownerToken(), "idem-r185-3");
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());

        Map<String, Object> po = poRow(PO_ID);
        assertEquals("40.000", qtyText(po.get("received_qty")), "★ 第二笔回退后剩余 40");
        assertEquals("PARTIALLY_RECEIVED", po.get("status"), "★ RECEIVED → PARTIALLY_RECEIVED");
        assertNull(po.get("closed_at"), "★ 离开终态必须清空 closed_at");
        assertEquals("40.000", qtyText(itemRow(PO_ITEM_ID).get("received_qty")));
        assertEquals("140.000", onHand(SKU7), "on_hand 200 − 60 = 140");
        assertEquals(1, reversalCount(), "只产生 1 条 REVERSAL（冲销第二笔）");
        assertReconciled();
    }

    // =====================================================================
    // R185-4：幂等重放 → 不重复回退
    // =====================================================================

    @Test
    @DisplayName("R185-4 同键同体重放：200 + Idempotent-Replayed:true，received_qty / on_hand 不二次回退")
    void idempotentReplayDoesNotDoubleRollback() throws Exception {
        seedInbound("PARTIALLY_RECEIVED", "40.000", "40.000", "40.000",
                "100.000", "140.000", "140.000", INBOUND_TXN_A);
        String token = ownerToken();

        ResponseEntity<String> first = post("/api/v1/inventory/transactions/" + INBOUND_TXN_A + "/reverse",
                "{\"reason\":\"冲销到货\"}", token, "idem-r185-4");
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        long firstTxnId = json.readTree(first.getBody()).get("data").get("transaction_id").asLong();

        // 同键同体重放
        ResponseEntity<String> replay = post("/api/v1/inventory/transactions/" + INBOUND_TXN_A + "/reverse",
                "{\"reason\":\"冲销到货\"}", token, "idem-r185-4");
        assertEquals(HttpStatus.OK, replay.getStatusCode(), replay.getBody());
        assertEquals(firstTxnId, json.readTree(replay.getBody()).get("data").get("transaction_id").asLong(),
                "重放返回首次的 transaction_id");
        assertEquals("true", replay.getHeaders().getFirst("Idempotent-Replayed"), "重放须补头");

        Map<String, Object> po = poRow(PO_ID);
        assertEquals("0.000", qtyText(po.get("received_qty")), "★ 重放不得二次回退（仍为 0）");
        assertEquals("ORDERED", po.get("status"));
        assertEquals("100.000", onHand(SKU7), "★ 余额不得二次回退（仍为 100）");
        assertEquals(1, reversalCount(), "★ 重放不得新增 REVERSAL 流水");
        assertReconciled();
    }

    // =====================================================================
    // 种子与辅助
    // =====================================================================

    /**
     * 种子一笔入库上下文：PO（单明细，ordered=100）+ 余额（INIT_IMPORT + 单笔 PURCHASE_INBOUND）
     * + 对应采购明细 received_qty。幂等键末段为 {@code PO_ITEM_ID}（{@code R180}）。
     *
     * @param poStatus     采购单初始状态
     * @param poReceived   采购单初始 received_qty（字符串，避免精度歧义）
     * @param itemReceived 明细行初始 received_qty
     * @param inboundDelta PURCHASE_INBOUND 的 qty_delta（正数）
     * @param inboundBefore PURCHASE_INBOUND 的 qty_before
     * @param inboundAfter  PURCHASE_INBOUND 的 qty_after（= before + delta）
     * @param balanceOnHand 余额 on_hand（应 = inboundBefore + inboundDelta）
     * @param inboundTxnId  PURCHASE_INBOUND 流水 ID（显式指定以便冲销）
     */
    private void seedInbound(String poStatus, String poReceived, String itemReceived,
                             String inboundDelta, String inboundBefore, String inboundAfter,
                             String balanceOnHand, long inboundTxnId) {
        jdbc.update("INSERT INTO purchase_orders (id, team_id, po_no, supplier_id, warehouse_id, status, source, "
                        + "total_qty, total_amount, received_qty, version, created_by, updated_by) "
                        + "VALUES (?, ?, 'PO-R185', ?, ?, ?, 'MANUAL', 100.000, 1000.00, ?, 0, ?, ?)",
                PO_ID, TEAM7, SUPPLIER7, WH7, poStatus, new BigDecimal(poReceived), OWNER_ID, OWNER_ID);
        jdbc.update("INSERT INTO purchase_order_items (id, team_id, purchase_order_id, line_no, sku_id, product_id, "
                        + "ordered_qty, received_qty, unit_price, amount, version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, 1, ?, ?, 100.000, ?, 10.0000, 1000.00, 0, ?, ?)",
                PO_ITEM_ID, TEAM7, PO_ID, SKU7, PRODUCT7, new BigDecimal(itemReceived), OWNER_ID, OWNER_ID);
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                        + "in_transit_qty, committed_qty, version, created_by) VALUES (?, ?, ?, ?, 0.000, 0.000, 0, ?)",
                TEAM7, WH7, SKU7, new BigDecimal(balanceOnHand), OWNER_ID);
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                        + "qty_before, qty_after, biz_type, idempotency_key, operator_id, occurred_at, created_by) "
                        + "VALUES (?, ?, ?, 'INIT_IMPORT', ?, 0.000, ?, 'IMPORT', 'seed-init-" + SKU7 + "', "
                        + "?, UTC_TIMESTAMP(3), ?)",
                TEAM7, WH7, SKU7, new BigDecimal(inboundBefore), new BigDecimal(inboundBefore), OWNER_ID, OWNER_ID);
        jdbc.update("INSERT INTO inventory_transactions (id, team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                        + "qty_before, qty_after, biz_type, biz_ref_id, idempotency_key, operator_id, occurred_at, created_by) "
                        + "VALUES (?, ?, ?, ?, 'PURCHASE_INBOUND', ?, ?, ?, 'GOODS_RECEIPT', ?, "
                        + "'PURCHASE_INBOUND:GOODS_RECEIPT:" + RECEIPT_ID + ":" + SKU7 + ":" + PO_ITEM_ID + "', "
                        + "?, UTC_TIMESTAMP(3), ?)",
                inboundTxnId, TEAM7, WH7, SKU7, new BigDecimal(inboundDelta), new BigDecimal(inboundBefore),
                new BigDecimal(inboundAfter), RECEIPT_ID, OWNER_ID, OWNER_ID);
    }

    private Map<String, Object> poRow(long poId) {
        return jdbc.queryForMap("SELECT status, received_qty, closed_at FROM purchase_orders WHERE id = ?", poId);
    }

    private Map<String, Object> itemRow(long poItemId) {
        return jdbc.queryForMap("SELECT received_qty FROM purchase_order_items WHERE id = ?", poItemId);
    }

    private String onHand(long skuId) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE team_id = ? AND warehouse_id = ? AND sku_id = ?",
                BigDecimal.class, TEAM7, WH7, skuId);
        return value == null ? null : value.toPlainString();
    }

    private int reversalCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions "
                + "WHERE team_id = ? AND txn_type = 'REVERSAL'", Integer.class, TEAM7);
    }

    private void assertReconciled() {
        Long diff = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_balances b "
                        + "LEFT JOIN (SELECT team_id, warehouse_id, sku_id, SUM(qty_delta) AS d "
                        + "           FROM inventory_transactions GROUP BY team_id, warehouse_id, sku_id) t "
                        + "  ON t.team_id = b.team_id AND t.warehouse_id = b.warehouse_id "
                        + " AND t.sku_id = b.sku_id "
                        + "WHERE b.on_hand_qty <> COALESCE(t.d, 0)", Long.class);
        assertEquals(0L, diff, "★ 余额必须可由流水推导（对账差异必须为 0）");
    }

    private static String qtyText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd.setScale(3, java.math.RoundingMode.HALF_UP).toPlainString();
        }
        return value.toString();
    }

    private String ownerToken() throws Exception {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                "{\"username\":\"owner01\",\"password\":\"" + RAW_PASSWORD + "\"}", null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + response.getBody());
        return json.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private ResponseEntity<String> post(String path, String body, String token, String idemKey) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idemKey != null) {
            headers.set("Idempotency-Key", idemKey);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
