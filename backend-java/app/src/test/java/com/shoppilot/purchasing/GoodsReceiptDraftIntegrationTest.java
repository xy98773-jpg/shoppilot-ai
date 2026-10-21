package com.shoppilot.purchasing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.error.ErrorCodes;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * M6-T04 到货单草稿（登记 / 编辑 / 作废 / 查询）—— 端到端集成测试
 * （真实 MySQL + 真实安全链 + 真实事务，<b>禁止 Mock Mapper</b>）。
 *
 * <p>覆盖 F1–F11：登记（含幂等、超额守卫、数量/单价校验、采购单状态守卫）、
 * 编辑草稿（重算、乐观锁、状态守卫）、作废（含审计）、详情/列表查询
 * （{@code list} 字段名、团队隔离、排序）、<b>不写库存（INV-03）</b>、跨团队采购单。
 *
 * <p>种子采购单由 JDBC 直插（焦点在到货域，不在采购单生成链路——生成端点由
 * {@code PurchaseOrderFromReplenishmentIntegrationTest} 覆盖）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GoodsReceiptDraftIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM7 = 7L;
    private static final long TEAM8 = 8L;
    private static final long OPERATOR_ID = 1001L;
    private static final long APPROVER_ID = 1002L;
    private static final long VIEWER_ID = 1003L;

    private static final long PRODUCT7 = 9001L;
    private static final long PRODUCT8 = 9101L;
    private static final long SKU7 = 9002L;
    private static final long SKU7B = 9003L;
    private static final long SKU8 = 9102L;
    private static final long WH7 = 9004L;
    private static final long WH8 = 9104L;
    private static final long SUPPLIER7 = 8001L;
    private static final long SUPPLIER8 = 8101L;

    private static final String BASE = "/api/v1/purchasing/receipts";

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

    private int poSeq;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM goods_receipt_items");
        jdbc.execute("DELETE FROM goods_receipts");
        jdbc.execute("DELETE FROM purchase_order_items");
        jdbc.execute("DELETE FROM purchase_orders");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM inventory_transactions");
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
        poSeq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-GRD-7', '到货七团', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-GRD-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'approver01', 'approver01@example.com', ?, '小审', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE')",
                OPERATOR_ID, PASSWORD_HASH, APPROVER_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'APPROVER', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM7, OPERATOR_ID, TEAM7, APPROVER_ID, TEAM7, VIEWER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-G7', '七团商品'), (?, ?, 'P-G8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-G7', '七团仓'), (?, ?, 'WH-G8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-G7', '七团供应商'), (?, ?, 'SUP-G8', '八团供应商')",
                SUPPLIER7, TEAM7, SUPPLIER8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-G7', '七团SKU'), (?, ?, ?, 'SKU-G7B', '七团SKU-B'), "
                + "(?, ?, ?, 'SKU-G8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU7B, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);
        // 余额行：供 INV-03 断言（草稿到货绝不触达它）
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty, version) VALUES "
                + "(?, ?, ?, 100.000, 0.000, 0.000, 0)", TEAM7, WH7, SKU7);
    }

    // =====================================================================
    // F1：登记到货正常
    // =====================================================================

    @Test
    @DisplayName("F1 登记到货正常：201，DRAFT，receipt_no 匹配 GR-，total_qty 正确，明细含 sku_id；库存流水 0")
    void createReceiptSucceeds() throws Exception {
        long poId = createPo("ORDERED", "100.000");
        long poItemId = firstLineId(poId);
        String token = login("operator01");

        long txBefore = countOf("inventory_transactions");
        ResponseEntity<String> res = postWithKey(BASE,
                "{\"purchase_order_id\":" + poId + ",\"warehouse_id\":" + WH7 + ","
                        + "\"receipt_date\":\"2026-10-21\",\"remark\":\"首批到货\","
                        + "\"items\":[{\"purchase_order_item_id\":" + poItemId + ","
                        + "\"received_qty\":\"50.000\",\"unit_price\":\"35.5000\"}]}",
                token, "idem-f1");
        System.out.println("[M6T04-EVIDENCE] F1 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CREATED, res.getStatusCode(), res.getBody());

        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("DRAFT", data.get("status").asText());
        String receiptNo = data.get("receipt_no").asText();
        assertTrue(receiptNo.matches("GR-\\d{8}-\\d{6}"), "receipt_no 须匹配 GR-yyyyMMdd-6 位：" + receiptNo);
        assertEquals("50.000", data.get("total_qty").asText(), "total_qty = Σ received_qty");
        assertEquals("1775.00", data.get("total_amount").asText(), "total_amount = Σ amount（50×35.5）");
        assertEquals(1, data.get("items").size());
        assertEquals(SKU7, data.get("items").get(0).get("sku_id").asLong(), "sku_id 冗余取自采购明细");
        assertEquals("50.000", data.get("items").get(0).get("received_qty").asText());
        assertEquals("35.5000", data.get("items").get(0).get("unit_price").asText());
        assertEquals("1775.00", data.get("items").get(0).get("amount").asText());

        long txAfter = countOf("inventory_transactions");
        System.out.println("[M6T04-EVIDENCE] F1 inventory_transactions: " + txBefore + " → " + txAfter);
        assertEquals(txBefore, txAfter, "★ 草稿到货不产生任何库存流水（INV-03）");
    }

    // =====================================================================
    // F2：采购单状态不允许
    // =====================================================================

    @Test
    @DisplayName("F2 采购单状态不允许（DRAFT/PENDING）⇒ 409 PO-9003")
    void purchaseOrderStatusGuard() throws Exception {
        long draftPo = createPo("DRAFT", "100.000");
        long pendingPo = createPo("PENDING", "100.000");
        String token = login("operator01");

        ResponseEntity<String> res = postWithKey(BASE,
                receiptBody(draftPo, firstLineId(draftPo), "50.000"), token, "idem-f2a");
        System.out.println("[M6T04-EVIDENCE] F2 DRAFT: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals(ErrorCodes.PO_9003, codeOf(res));

        ResponseEntity<String> res2 = postWithKey(BASE,
                receiptBody(pendingPo, firstLineId(pendingPo), "50.000"), token, "idem-f2b");
        assertEquals(HttpStatus.CONFLICT, res2.getStatusCode());
        assertEquals(ErrorCodes.PO_9003, codeOf(res2));
        assertEquals(0L, countOf("goods_receipts"), "被拒的请求不得建单");
    }

    // =====================================================================
    // F3：超额守卫
    // =====================================================================

    @Test
    @DisplayName("F3 超额 ⇒ 409 PO-9002，details 含待收数量与本次数量")
    void overReceiptGuard() throws Exception {
        long poId = createPo("ORDERED", "80.000");
        long poItemId = firstLineId(poId);
        String token = login("operator01");

        ResponseEntity<String> res = postWithKey(BASE,
                receiptBody(poId, poItemId, "90.000"), token, "idem-f3");
        System.out.println("[M6T04-EVIDENCE] F3 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals(ErrorCodes.PO_9002, codeOf(res));

        JsonNode details = json.readTree(res.getBody()).get("details");
        assertNotNull(details, "超额必须带 details");
        assertEquals(1, details.size());
        assertEquals("items[0].received_qty", details.get(0).get("field").asText());
        assertTrue(details.get(0).get("message").asText().contains("80.000"),
                "message 须含待收数量，实际：" + details.get(0).get("message").asText());
        assertEquals("90.000", details.get(0).get("rejectedValue").asText(),
                "rejectedValue 为本次登记数量");
        assertEquals(0L, countOf("goods_receipts"), "被拒的请求不得建单");
    }

    // =====================================================================
    // F4：数量/单价/明细校验
    // =====================================================================

    @Test
    @DisplayName("F4 received_qty<=0 ⇒ PO-9012；items 空 ⇒ PO-9006；unit_price 非法 ⇒ PO-9011")
    void fieldValidations() throws Exception {
        long poId = createPo("ORDERED", "100.000");
        long poItemId = firstLineId(poId);
        String token = login("operator01");

        ResponseEntity<String> zero = postWithKey(BASE,
                receiptBody(poId, poItemId, "0.000"), token, "idem-f4a");
        System.out.println("[M6T04-EVIDENCE] F4 qty=0: " + zero.getStatusCode().value() + " " + zero.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, zero.getStatusCode());
        assertEquals(ErrorCodes.PO_9012, codeOf(zero));

        ResponseEntity<String> empty = postWithKey(BASE,
                "{\"purchase_order_id\":" + poId + ",\"warehouse_id\":" + WH7 + ","
                        + "\"receipt_date\":\"2026-10-21\",\"items\":[]}",
                token, "idem-f4b");
        System.out.println("[M6T04-EVIDENCE] F4 items 空: " + empty.getStatusCode().value() + " " + empty.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
        assertEquals(ErrorCodes.PO_9006, codeOf(empty));

        ResponseEntity<String> badPrice = postWithKey(BASE,
                "{\"purchase_order_id\":" + poId + ",\"warehouse_id\":" + WH7 + ","
                        + "\"receipt_date\":\"2026-10-21\",\"items\":[{"
                        + "\"purchase_order_item_id\":" + poItemId + ","
                        + "\"received_qty\":\"10.000\",\"unit_price\":\"-1.0000\"}]}",
                token, "idem-f4c");
        System.out.println("[M6T04-EVIDENCE] F4 单价负: " + badPrice.getStatusCode().value() + " " + badPrice.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, badPrice.getStatusCode());
        assertEquals(ErrorCodes.PO_9011, codeOf(badPrice));
    }

    // =====================================================================
    // F5：幂等
    // =====================================================================

    @Test
    @DisplayName("F5 幂等：同键同体返回首次那张；异体 ⇒ COM-0009；缺 Idempotency-Key ⇒ COM-0010")
    void idempotency() throws Exception {
        long poId = createPo("ORDERED", "100.000");
        long poItemId = firstLineId(poId);
        String token = login("operator01");
        String body = receiptBody(poId, poItemId, "50.000");

        ResponseEntity<String> first = postWithKey(BASE, body, token, "idem-f5");
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        JsonNode firstData = json.readTree(first.getBody()).get("data");

        // 同键同体重发 ⇒ 返回同一张
        ResponseEntity<String> replay = postWithKey(BASE, body, token, "idem-f5");
        System.out.println("[M6T04-EVIDENCE] F5 重放: " + replay.getStatusCode().value() + " " + replay.getBody());
        assertEquals(HttpStatus.CREATED, replay.getStatusCode(), "同键同体 ⇒ 201 + 首次那张");
        JsonNode replayData = json.readTree(replay.getBody()).get("data");
        assertEquals(firstData.get("goods_receipt_id").asLong(), replayData.get("goods_receipt_id").asLong(),
                "id 不变");
        assertEquals(firstData.get("receipt_no").asText(), replayData.get("receipt_no").asText(),
                "receipt_no 不变");
        assertEquals(1L, countOf("goods_receipts"), "同键同体不得重复建单");

        // 同键异体 ⇒ COM-0009
        ResponseEntity<String> other = postWithKey(BASE,
                receiptBody(poId, poItemId, "60.000"), token, "idem-f5");
        System.out.println("[M6T04-EVIDENCE] F5 异体: " + other.getStatusCode().value() + " " + other.getBody());
        assertEquals(HttpStatus.CONFLICT, other.getStatusCode());
        assertEquals(ErrorCodes.COM_0009, codeOf(other));

        // 缺 Idempotency-Key ⇒ COM-0010
        ResponseEntity<String> noKey = postWithoutKey(BASE, body, token);
        System.out.println("[M6T04-EVIDENCE] F5 缺 key: " + noKey.getStatusCode().value() + " " + noKey.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, noKey.getStatusCode());
        assertEquals(ErrorCodes.COM_0010, codeOf(noKey));
    }

    // =====================================================================
    // F6：编辑草稿
    // =====================================================================

    @Test
    @DisplayName("F6 编辑草稿：改明细 ⇒ 200，total 重算，version+1；非 DRAFT ⇒ PO-9008")
    void updateDraft() throws Exception {
        long poId = createPo("ORDERED", "100.000");
        long poItemId = firstLineId(poId);
        String token = login("operator01");
        ResponseEntity<String> created = postWithKey(BASE,
                receiptBody(poId, poItemId, "50.000"), token, "idem-f6");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        JsonNode createdData = json.readTree(created.getBody()).get("data");
        long receiptId = createdData.get("goods_receipt_id").asLong();
        int version = createdData.get("version").asInt();

        ResponseEntity<String> res = patch(BASE + "/" + receiptId,
                "{\"expected_version\":" + version + ",\"remark\":\"已核对\","
                        + "\"items\":[{\"purchase_order_item_id\":" + poItemId + ","
                        + "\"received_qty\":\"30.000\",\"unit_price\":\"40.0000\"}]}",
                token);
        System.out.println("[M6T04-EVIDENCE] F6 PATCH: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("30.000", data.get("total_qty").asText(), "total 重算");
        assertEquals("1200.00", data.get("total_amount").asText(), "total_amount 重算（30×40）");
        assertEquals(version + 1, data.get("version").asInt(), "version+1");
        assertEquals("已核对", data.get("remark").asText());
        assertEquals("30.000", data.get("items").get(0).get("received_qty").asText());

        // 非 DRAFT ⇒ PO-9008
        ResponseEntity<String> cancel = postWithKey(BASE + "/" + receiptId + "/cancel", "{}", token, "idem-f6c");
        assertEquals(HttpStatus.OK, cancel.getStatusCode(), cancel.getBody());
        ResponseEntity<String> afterCancel = patch(BASE + "/" + receiptId,
                "{\"remark\":\"改已作废单\"}", token);
        System.out.println("[M6T04-EVIDENCE] F6 非 DRAFT: " + afterCancel.getStatusCode().value()
                + " " + afterCancel.getBody());
        assertEquals(HttpStatus.CONFLICT, afterCancel.getStatusCode());
        assertEquals(ErrorCodes.PO_9008, codeOf(afterCancel));
    }

    // =====================================================================
    // F7：作废草稿
    // =====================================================================

    @Test
    @DisplayName("F7 作废草稿：DRAFT→CANCELLED，cancelled_at 有值，审计 GOODS_RECEIPT_CANCEL ×1")
    void cancelDraft() throws Exception {
        long poId = createPo("ORDERED", "100.000");
        long poItemId = firstLineId(poId);
        String token = login("operator01");
        ResponseEntity<String> created = postWithKey(BASE,
                receiptBody(poId, poItemId, "50.000"), token, "idem-f7");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        long receiptId = json.readTree(created.getBody()).get("data").get("goods_receipt_id").asLong();

        ResponseEntity<String> res = postWithKey(BASE + "/" + receiptId + "/cancel", "{}", token, "idem-f7c");
        System.out.println("[M6T04-EVIDENCE] F7 cancel: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("CANCELLED", data.get("status").asText());
        assertNotNull(data.get("cancelled_at"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, cancelled_at, version FROM goods_receipts WHERE id = ?", receiptId);
        assertEquals("CANCELLED", row.get("status"));
        assertNotNull(row.get("cancelled_at"));
        assertEquals(1L, auditCount(receiptId, "GOODS_RECEIPT_CANCEL"), "作废审计 ×1");
    }

    // =====================================================================
    // F8：详情
    // =====================================================================

    @Test
    @DisplayName("F8 详情：含 items[]；不存在/跨团队 ⇒ 404 PO-9005")
    void receiptDetail() throws Exception {
        long poId = createPo("ORDERED", "100.000");
        long poItemId = firstLineId(poId);
        String token = login("operator01");
        ResponseEntity<String> created = postWithKey(BASE,
                receiptBody(poId, poItemId, "50.000"), token, "idem-f8");
        long receiptId = json.readTree(created.getBody()).get("data").get("goods_receipt_id").asLong();

        ResponseEntity<String> res = get(BASE + "/" + receiptId, token);
        System.out.println("[M6T04-EVIDENCE] F8 GET: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals(1, data.get("items").size());
        assertEquals(poItemId, data.get("items").get(0).get("purchase_order_item_id").asLong());

        ResponseEntity<String> missing = get(BASE + "/999999", token);
        System.out.println("[M6T04-EVIDENCE] F8 不存在: " + missing.getStatusCode().value() + " " + missing.getBody());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(ErrorCodes.PO_9005, codeOf(missing));

        // 跨团队：team8 的到货单对 team7 不可见 ⇒ PO-9005
        long team8Po = createPoTeam8("ORDERED", "100.000");
        long foreign = createReceiptTeam8(team8Po, firstLineId(team8Po), "10.000", "idem-f8-foreign");
        ResponseEntity<String> cross = get(BASE + "/" + foreign, token);
        assertEquals(HttpStatus.NOT_FOUND, cross.getStatusCode());
        assertEquals(ErrorCodes.PO_9005, codeOf(cross));
    }

    // =====================================================================
    // F9：列表
    // =====================================================================

    @Test
    @DisplayName("F9 列表：字段名 list、总数正确、跨团队不可见、created_at DESC 排序")
    void receiptList() throws Exception {
        String token = login("operator01");
        long poA = createPo("ORDERED", "100.000");
        long poB = createPo("ORDERED", "100.000");
        long poC = createPo("ORDERED", "100.000");
        createReceipt(poA, firstLineId(poA), "10.000", "idem-f9a", "2026-01-01T08:00:00");
        createReceipt(poB, firstLineId(poB), "20.000", "idem-f9b", "2026-01-02T08:00:00");
        createReceipt(poC, firstLineId(poC), "30.000", "idem-f9c", "2026-01-03T08:00:00");

        ResponseEntity<String> res = get(BASE + "?page=1&pageSize=50", token);
        System.out.println("[M6T04-EVIDENCE] F9 GET: " + res.getStatusCode().value()
                + " " + res.getBody().substring(0, Math.min(240, res.getBody().length())) + "…");
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals(3L, data.get("total").asLong());
        assertEquals(3, data.get("list").size(), "★ 分页字段名是 list（不是 items）");
        assertTrue(data.has("list"), "data 必须含 list 键");
        assertFalse(data.has("items"), "★ data 不得含 items 键");
        // created_at DESC ⇒ c, b, a
        assertEquals(poC, data.get("list").get(0).get("purchase_order_id").asLong());
        assertEquals(poB, data.get("list").get(1).get("purchase_order_id").asLong());
        assertEquals(poA, data.get("list").get(2).get("purchase_order_id").asLong());

        // 状态过滤
        ResponseEntity<String> filtered = get(BASE + "?status=DRAFT", token);
        assertEquals(3L, json.readTree(filtered.getBody()).get("data").get("total").asLong());
        ResponseEntity<String> none = get(BASE + "?status=CONFIRMED", token);
        assertEquals(0L, json.readTree(none.getBody()).get("data").get("total").asLong());
    }

    // =====================================================================
    // F10：不写库存
    // =====================================================================

    @Test
    @DisplayName("F10 不写库存：登记/编辑/作废前后 on_hand_qty 与流水计数均不变")
    void neverWritesInventory() throws Exception {
        long poId = createPo("ORDERED", "100.000");
        long poItemId = firstLineId(poId);
        String token = login("operator01");

        String onHandBefore = onHand(WH7, SKU7);
        long txBefore = countOf("inventory_transactions");

        ResponseEntity<String> created = postWithKey(BASE,
                receiptBody(poId, poItemId, "50.000"), token, "idem-f10");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        long receiptId = json.readTree(created.getBody()).get("data").get("goods_receipt_id").asLong();

        patch(BASE + "/" + receiptId,
                "{\"items\":[{\"purchase_order_item_id\":" + poItemId + ","
                        + "\"received_qty\":\"40.000\",\"unit_price\":\"30.0000\"}]}", token);
        postWithKey(BASE + "/" + receiptId + "/cancel", "{}", token, "idem-f10c");

        String onHandAfter = onHand(WH7, SKU7);
        long txAfter = countOf("inventory_transactions");
        System.out.println("[M6T04-EVIDENCE] F10 on_hand: " + onHandBefore + " → " + onHandAfter
                + " | inventory_transactions: " + txBefore + " → " + txAfter);
        assertEquals(onHandBefore, onHandAfter, "★ 草稿到货不得改库存余额（INV-03）");
        assertEquals(txBefore, txAfter, "★ 草稿到货不得产生库存流水（INV-03）");
    }

    // =====================================================================
    // F11：跨团队采购单
    // =====================================================================

    @Test
    @DisplayName("F11 跨团队采购单 ⇒ 404 PO-9004")
    void crossTeamPurchaseOrder() throws Exception {
        long team8Po = createPoTeam8("ORDERED", "100.000");
        long poItemId = firstLineId(team8Po);
        String token = login("operator01");

        ResponseEntity<String> res = postWithKey(BASE,
                receiptBody(team8Po, poItemId, "50.000"), token, "idem-f11");
        System.out.println("[M6T04-EVIDENCE] F11 跨团队: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals(ErrorCodes.PO_9004, codeOf(res));
        assertEquals(0L, countOf("goods_receipts"), "被拒的请求不得建单");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private String receiptBody(long poId, long poItemId, String qty) {
        return "{\"purchase_order_id\":" + poId + ",\"warehouse_id\":" + WH7 + ","
                + "\"receipt_date\":\"2026-10-21\",\"items\":[{"
                + "\"purchase_order_item_id\":" + poItemId + ","
                + "\"received_qty\":\"" + qty + "\",\"unit_price\":\"35.5000\"}]}";
    }

    /** 直插采购单（含 1 条明细），返回 poId。 */
    private long createPo(String status, String qty) {
        return insertPo(status, qty, TEAM7, WH7, SUPPLIER7, PRODUCT7, SKU7, null);
    }

    private long createPoTeam8(String status, String qty) {
        return insertPo(status, qty, TEAM8, WH8, SUPPLIER8, PRODUCT8, SKU8, null);
    }

    private long insertPo(String status, String qty, long teamId, long warehouse, long supplier,
                          long product, long sku, String createdAt) {
        String poNo = "PO-G" + teamId + "-" + String.format("%04d", ++poSeq);
        if (createdAt == null) {
            jdbc.update("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, "
                            + "status, source, total_qty, total_amount, received_qty, version, "
                            + "created_by, updated_by) VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, 0.000, ?, ?, ?)",
                    teamId, poNo, supplier, warehouse, status,
                    new BigDecimal(qty), new BigDecimal("1000.00"), 0, APPROVER_ID, APPROVER_ID);
        } else {
            jdbc.update("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, "
                            + "status, source, total_qty, total_amount, received_qty, version, "
                            + "created_by, updated_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, 0.000, ?, ?, ?, ?)",
                    teamId, poNo, supplier, warehouse, status,
                    new BigDecimal(qty), new BigDecimal("1000.00"), 0,
                    APPROVER_ID, APPROVER_ID, createdAt);
        }
        long poId = jdbc.queryForObject(
                "SELECT id FROM purchase_orders WHERE po_no = ?", Long.class, poNo);
        jdbc.update("INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, "
                        + "product_id, ordered_qty, received_qty, unit_price, amount, version, "
                        + "created_by, updated_by) VALUES (?, ?, 1, ?, ?, ?, 0.000, ?, ?, 0, ?, ?)",
                teamId, poId, sku, product, new BigDecimal(qty),
                new BigDecimal("10.0000"), new BigDecimal("1000.00"), APPROVER_ID, APPROVER_ID);
        return poId;
    }

    /** 直插一张到货单（用于列表排序场景），返回 receiptId。 */
    private long createReceipt(long poId, long poItemId, String qty, String idem, String createdAt) {
        jdbc.update("INSERT INTO goods_receipts (team_id, receipt_no, purchase_order_id, warehouse_id, "
                        + "status, receipt_date, total_qty, total_amount, idempotency_key, remark, "
                        + "operator_id, version, created_by, updated_by, created_at) "
                        + "VALUES (?, ?, ?, ?, 'DRAFT', '2026-10-21', ?, ?, ?, '直接插入', ?, 0, ?, ?, ?)",
                TEAM7, "GR-SEED-" + idem, poId, WH7, new BigDecimal(qty),
                new BigDecimal("100.00"), idem, OPERATOR_ID, OPERATOR_ID, OPERATOR_ID, createdAt);
        long receiptId = jdbc.queryForObject(
                "SELECT id FROM goods_receipts WHERE idempotency_key = ?", Long.class, idem);
        jdbc.update("INSERT INTO goods_receipt_items (team_id, goods_receipt_id, purchase_order_item_id, "
                        + "sku_id, received_qty, unit_price, amount, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                TEAM7, receiptId, poItemId, SKU7, new BigDecimal(qty),
                new BigDecimal("10.0000"), new BigDecimal("100.00"), OPERATOR_ID);
        return receiptId;
    }

    private long firstLineId(long poId) {
        return jdbc.queryForObject(
                "SELECT id FROM purchase_order_items WHERE purchase_order_id = ? ORDER BY line_no",
                Long.class, poId);
    }

    /** 直插一张他团队的到货单（跨团队不可见场景）。 */
    private long createReceiptTeam8(long poId, long poItemId, String qty, String idem) {
        jdbc.update("INSERT INTO goods_receipts (team_id, receipt_no, purchase_order_id, warehouse_id, "
                        + "status, receipt_date, total_qty, total_amount, idempotency_key, remark, "
                        + "operator_id, version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, 'DRAFT', '2026-10-21', ?, ?, ?, '他团队', ?, 0, ?, ?)",
                TEAM8, "GR-SEED-" + idem, poId, WH8, new BigDecimal(qty),
                new BigDecimal("100.00"), idem, OPERATOR_ID, OPERATOR_ID, OPERATOR_ID);
        return jdbc.queryForObject(
                "SELECT id FROM goods_receipts WHERE idempotency_key = ?", Long.class, idem);
    }

    /** 余额行 {@code on_hand_qty}（DECIMAL ⇒ 按字符串取，避免类型转换误差）。 */
    private String onHand(long warehouseId, long skuId) {
        String v = jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE warehouse_id = ? AND sku_id = ?",
                String.class, warehouseId, skuId);
        return v == null ? null : v;
    }

    private long countOf(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long auditCount(long bizId, String action) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE biz_id = ? AND action = ?", Long.class, bizId, action);
    }

    private String codeOf(ResponseEntity<String> res) throws Exception {
        return json.readTree(res.getBody()).get("code").asText();
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

    private ResponseEntity<String> postWithKey(String path, String body, String token, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postWithoutKey(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> patch(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
