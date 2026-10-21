package com.shoppilot.purchasing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.error.ErrorCodes;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6-T05+T06 确认到货入库 + 部分到货/超额守卫 —— 端到端集成测试
 * （真实 MySQL + 真实安全链 + 真实事务，<b>禁止 Mock Mapper</b>）。
 *
 * <p>覆盖任务书 E1–E12：
 * <ul>
 *   <li>E1 正常确认（余额增、流水新增、采购汇总累加）；</li>
 *   <li>E2 全部到货 ⇒ {@code RECEIVED}；E3 部分到货 ⇒ {@code PARTIALLY_RECEIVED}；</li>
 *   <li>E4 跨单据累计超额 ⇒ {@code PO-9002} 且<b>无任何库存变更</b>；</li>
 *   <li>E5 重复确认 ⇒ {@code PO-9003} 且不产生第二条流水；</li>
 *   <li>E6 状态守卫（不存在/跨团队 {@code PO-9005}；采购单 {@code DRAFT} ⇒ {@code PO-9003}）；</li>
 *   <li>E7 角色（{@code VIEWER} ⇒ {@code TEAM-2003}；{@code OPERATOR} ⇒ 成功，验证 {@code OPERATOR+}）；</li>
 *   <li>E8 审计 {@code GOODS_RECEIPT_CONFIRM} ×1（按 {@code biz_id}+{@code action} 差分）；</li>
 *   <li>E9 流水字段与幂等键口径；</li>
 *   <li>E10 第二条明细失败 ⇒ 整事务回滚（无流水、余额不变、到货单仍 {@code DRAFT}、采购单状态不变）；</li>
 *   <li>E11 两张到货单分批累计；</li>
 *   <li>E12 库存余额乐观锁冲突 ⇒ {@code INV-5002} 且无部分写入。</li>
 * </ul>
 *
 * <p>★ <b>种子成对</b>（{@code TEST_STRATEGY §7} 警示框）：余额行一律配套 {@code INIT_IMPORT} 流水，
 * 使「余额 = Σ 流水」在对账口径下恒成立（不收 {@code tools/evi/seed-b95.sql} 的历史缺口影响）。
 *
 * <p>★ <b>{@code in_transit_qty}</b>：SSOT（{@code DATABASE_DESIGN §6-I.3} ③）要求在入库时扣减，
 * 但实测 {@code InventoryWriteService.applyInbound} 只推进 {@code on_hand_qty}
 * （{@code InventoryBalanceUpdater} 全文仅 set {@code on_hand_qty/last_txn_*}），
 * 且 {@code purchasing} 被 {@code MODULE_BOUNDARIES §2.9} 禁止直写余额表 ⇒
 * 本批<b>不对 {@code in_transit_qty} 作断言</b>，仅打印前后值作为证据（见交付汇报偏差 D-1）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GoodsReceiptConfirmIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            // ★ E10/E12 用「触发器注入确定性故障」验证回滚与乐观锁冲突；
            //   MySQL 8 默认开启 binlog，非 SUPER 用户建触发器报 1419 ⇒ 显式放开（仅测试容器）。
            .withCommand("--default-time-zone=+00:00", "--log-bin-trust-function-creators=1")
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
    private static final String TRG_FAIL = "trg_b11_inject_fail";
    private static final String TRG_VERSION = "trg_b11_bump_version";

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
    private int idemSeq;

    @BeforeEach
    void resetAndSeed() {
        dropTriggers();
        jdbc.execute("DELETE FROM goods_receipt_items");
        jdbc.execute("DELETE FROM goods_receipts");
        jdbc.execute("DELETE FROM purchase_order_items");
        jdbc.execute("DELETE FROM purchase_orders");
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
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
        idemSeq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-GRC-7', '确认七团', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-GRC-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'approver01', 'approver01@example.com', ?, '小审', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE')",
                OPERATOR_ID, PASSWORD_HASH, APPROVER_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'APPROVER', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM7, OPERATOR_ID, TEAM7, APPROVER_ID, TEAM7, VIEWER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-C7', '确认七团商品'), (?, ?, 'P-C8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-C7', '七团仓'), (?, ?, 'WH-C8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-C7', '七团供应商'), (?, ?, 'SUP-C8', '八团供应商')",
                SUPPLIER7, TEAM7, SUPPLIER8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-C7', '七团SKU'), (?, ?, ?, 'SKU-C7B', '七团SKU-B'), "
                + "(?, ?, ?, 'SKU-C8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU7B, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);

        // ★ 成对种子：余额行 + INIT_IMPORT 流水（on_hand 100，在途 30 —— 在途仅作证据，不作断言）
        seedBalanceWithTxn(SKU7, "100.000", "30.000");
        seedBalanceWithTxn(SKU7B, "50.000", "0.000");
    }

    @AfterEach
    void cleanupTriggers() {
        dropTriggers();
    }

    // =====================================================================
    // E1：确认到货正常
    // =====================================================================

    @Test
    @DisplayName("E1 确认到货：200 CONFIRMED；on_hand 增；流水新增；采购汇总累加；审计 1 条")
    void confirmSucceeds() throws Exception {
        long poId = createPo("ORDERED", "100.000", "0.000");
        long poItemId = firstLineId(poId);
        long receiptId = createDraftReceipt(poId, poItemId, "40.000", "idem-e1");
        String token = login("operator01");

        String onHandBefore = onHand(WH7, SKU7);
        String inTransitBefore = inTransit(WH7, SKU7);
        long txnBefore = txnCount();
        long auditBefore = auditCount(receiptId, "GOODS_RECEIPT_CONFIRM");

        ResponseEntity<String> res = post(BASE + "/" + receiptId + "/confirm", token);
        System.out.println("[M6T05-EVIDENCE] E1 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());

        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("CONFIRMED", data.get("status").asText());
        assertNotNull(data.get("confirmed_at"), "confirmed_at 必须写入");
        assertEquals(OPERATOR_ID, data.get("confirmed_by").asLong(), "confirmed_by = 当前操作人");

        String onHandAfter = onHand(WH7, SKU7);
        String inTransitAfter = inTransit(WH7, SKU7);
        long txnAfter = txnCount();
        System.out.println("[M6T05-EVIDENCE] E1 库存侧：on_hand " + onHandBefore + " → " + onHandAfter
                + " | in_transit " + inTransitBefore + " → " + inTransitAfter
                + " | inventory_transactions " + txnBefore + " → " + txnAfter);
        assertEquals("140.000", onHandAfter, "on_hand 增加本次到货量（100 + 40）");
        assertEquals(txnBefore + 1, txnAfter, "每条明细 1 条流水");

        Map<String, Object> po = poRow(poId);
        assertEquals("40.000", qtyText(po.get("received_qty")), "采购单 received_qty 累加");
        assertEquals("PARTIALLY_RECEIVED", po.get("status"), "未收齐 ⇒ PARTIALLY_RECEIVED");

        assertEquals(auditBefore + 1, auditCount(receiptId, "GOODS_RECEIPT_CONFIRM"),
                "审计 GOODS_RECEIPT_CONFIRM 恰好 +1");
        assertReconciled();
    }

    // =====================================================================
    // E2：全部到货
    // =====================================================================

    @Test
    @DisplayName("E2 全部到货：Σ received == Σ ordered ⇒ RECEIVED，closed_at 写入")
    void confirmAllReceived() throws Exception {
        long poId = createPo("ORDERED", "100.000", "0.000");
        long receiptId = createDraftReceipt(poId, firstLineId(poId), "100.000", "idem-e2");

        ResponseEntity<String> res = post(BASE + "/" + receiptId + "/confirm", login("operator01"));
        System.out.println("[M6T05-EVIDENCE] E2 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());

        Map<String, Object> po = poRow(poId);
        assertEquals("100.000", qtyText(po.get("received_qty")));
        assertEquals("RECEIVED", po.get("status"), "全部到货 ⇒ RECEIVED（由明细推导）");
        assertNotNull(po.get("closed_at"), "进入终态须写 closed_at");
        assertEquals("200.000", onHand(WH7, SKU7), "100 + 100");
        assertReconciled();
    }

    // =====================================================================
    // E3：部分到货
    // =====================================================================

    @Test
    @DisplayName("E3 部分到货：Σ received < Σ ordered ⇒ PARTIALLY_RECEIVED")
    void confirmPartiallyReceived() throws Exception {
        long poId = createPo("ORDERED", "100.000", "0.000");
        long receiptId = createDraftReceipt(poId, firstLineId(poId), "30.000", "idem-e3");

        ResponseEntity<String> res = post(BASE + "/" + receiptId + "/confirm", login("operator01"));
        System.out.println("[M6T05-EVIDENCE] E3 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());

        Map<String, Object> po = poRow(poId);
        assertEquals("30.000", qtyText(po.get("received_qty")));
        assertEquals("PARTIALLY_RECEIVED", po.get("status"));
        assertNull(po.get("closed_at"), "部分到货不是终态，closed_at 应保持 NULL");
        assertReconciled();
    }

    // =====================================================================
    // E4：超额（跨单据累计）
    // =====================================================================

    @Test
    @DisplayName("E4 跨单据累计超额 ⇒ 409 PO-9002 + details 三字段；无任何库存变更；到货单仍 DRAFT")
    void oversizeRejected() throws Exception {
        // ordered=100，前序到货单已确认 20（体现在 received_qty）⇒ 待收 80，本次 90
        long poId = createPo("PARTIALLY_RECEIVED", "100.000", "20.000");
        long poItemId = firstLineId(poId);
        long receiptId = createDraftReceipt(poId, poItemId, "90.000", "idem-e4");

        String onHandBefore = onHand(WH7, SKU7);
        long txnBefore = txnCount();

        ResponseEntity<String> res = post(BASE + "/" + receiptId + "/confirm", login("operator01"));
        System.out.println("[M6T05-EVIDENCE] E4 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals(ErrorCodes.PO_9002, codeOf(res));

        JsonNode details = json.readTree(res.getBody()).get("details");
        assertNotNull(details, "超额必须带 details");
        assertEquals(1, details.size());
        assertEquals("items[0].received_qty", details.get(0).get("field").asText());
        assertTrue(details.get(0).get("message").asText().contains("80.000"),
                "message 须含待收数量，实际：" + details.get(0).get("message").asText());
        assertEquals("90.000", details.get(0).get("rejectedValue").asText());

        String onHandAfter = onHand(WH7, SKU7);
        long txnAfter = txnCount();
        System.out.println("[M6T05-EVIDENCE] E4 无库存变更：on_hand " + onHandBefore + " → " + onHandAfter
                + " | txn " + txnBefore + " → " + txnAfter);
        assertEquals(onHandBefore, onHandAfter, "★ 超额校验在写库存之前 ⇒ 余额不得变");
        assertEquals(txnBefore, txnAfter, "★ 超额不得产生任何流水");
        assertEquals("DRAFT", receiptStatus(receiptId), "★ 到货单须仍为 DRAFT");
        assertEquals("20.000", qtyText(poRow(poId).get("received_qty")), "采购明细累加量不得变");
        assertReconciled();
    }

    // =====================================================================
    // E5：重复确认
    // =====================================================================

    @Test
    @DisplayName("E5 重复确认 ⇒ 409 PO-9003；不产生第二条流水")
    void duplicateConfirmRejected() throws Exception {
        long poId = createPo("ORDERED", "100.000", "0.000");
        long receiptId = createDraftReceipt(poId, firstLineId(poId), "40.000", "idem-e5");
        String token = login("operator01");

        assertEquals(HttpStatus.OK, post(BASE + "/" + receiptId + "/confirm", token).getStatusCode());
        long txnAfterFirst = txnCount();
        String onHandAfterFirst = onHand(WH7, SKU7);

        ResponseEntity<String> res = post(BASE + "/" + receiptId + "/confirm", token);
        System.out.println("[M6T05-EVIDENCE] E5 二次确认: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals(ErrorCodes.PO_9003, codeOf(res));
        assertEquals(txnAfterFirst, txnCount(), "★ 重复确认不得产生第二条流水");
        assertEquals(onHandAfterFirst, onHand(WH7, SKU7), "★ 余额不得重复增加");
        assertEquals("40.000", qtyText(poRow(poId).get("received_qty")), "★ 不得重复累加");
        assertReconciled();
    }

    // =====================================================================
    // E6：状态守卫
    // =====================================================================

    @Test
    @DisplayName("E6 状态守卫：不存在/跨团队 ⇒ PO-9005；采购单 DRAFT ⇒ PO-9003")
    void stateGuards() throws Exception {
        String token = login("operator01");

        ResponseEntity<String> missing = post(BASE + "/999999/confirm", token);
        System.out.println("[M6T05-EVIDENCE] E6 不存在: " + missing.getStatusCode().value() + " " + missing.getBody());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(ErrorCodes.PO_9005, codeOf(missing));

        long team8Po = createPoTeam8("ORDERED", "100.000");
        long team8Receipt = createDraftReceiptTeam8(team8Po, firstLineId(team8Po), "40.000", "idem-e6x");
        ResponseEntity<String> cross = post(BASE + "/" + team8Receipt + "/confirm", token);
        System.out.println("[M6T05-EVIDENCE] E6 跨团队: " + cross.getStatusCode().value() + " " + cross.getBody());
        assertEquals(HttpStatus.NOT_FOUND, cross.getStatusCode(), "跨团队应与不存在同表现（租户线）");
        assertEquals(ErrorCodes.PO_9005, codeOf(cross));

        long draftPo = createPo("DRAFT", "100.000", "0.000");
        long draftReceipt = createDraftReceipt(draftPo, firstLineId(draftPo), "40.000", "idem-e6d");
        ResponseEntity<String> draft = post(BASE + "/" + draftReceipt + "/confirm", token);
        System.out.println("[M6T05-EVIDENCE] E6 采购单DRAFT: " + draft.getStatusCode().value() + " " + draft.getBody());
        assertEquals(HttpStatus.CONFLICT, draft.getStatusCode());
        assertEquals(ErrorCodes.PO_9003, codeOf(draft));
        assertEquals("DRAFT", receiptStatus(draftReceipt), "被拒后到货单仍 DRAFT");
    }

    // =====================================================================
    // E7：角色（OPERATOR+）
    // =====================================================================

    @Test
    @DisplayName("E7 角色：VIEWER ⇒ 403 TEAM-2003；OPERATOR ⇒ 成功（验证 OPERATOR+ 而非 APPROVER+）")
    void roleGuard() throws Exception {
        long poId = createPo("ORDERED", "100.000", "0.000");
        long receiptId = createDraftReceipt(poId, firstLineId(poId), "40.000", "idem-e7");

        ResponseEntity<String> viewer = post(BASE + "/" + receiptId + "/confirm", login("viewer01"));
        System.out.println("[M6T05-EVIDENCE] E7 VIEWER: " + viewer.getStatusCode().value() + " " + viewer.getBody());
        assertEquals(HttpStatus.FORBIDDEN, viewer.getStatusCode());
        assertEquals(ErrorCodes.TEAM_2003, codeOf(viewer));
        assertEquals("DRAFT", receiptStatus(receiptId), "越权被拒后状态不变");

        ResponseEntity<String> operator = post(BASE + "/" + receiptId + "/confirm", login("operator01"));
        System.out.println("[M6T05-EVIDENCE] E7 OPERATOR: " + operator.getStatusCode().value()
                + " " + operator.getBody());
        assertEquals(HttpStatus.OK, operator.getStatusCode(), "★ OPERATOR 必须可确认（B0-15 = OPERATOR+）");
        assertEquals("CONFIRMED", receiptStatus(receiptId));
    }

    // =====================================================================
    // E8：审计
    // =====================================================================

    @Test
    @DisplayName("E8 审计：GOODS_RECEIPT_CONFIRM 恰好 1 条（biz_id + action 差分，不用全表计数）")
    void auditWritten() throws Exception {
        long poId = createPo("ORDERED", "100.000", "0.000");
        long receiptId = createDraftReceipt(poId, firstLineId(poId), "40.000", "idem-e8");
        String token = login("operator01");

        long before = auditCount(receiptId, "GOODS_RECEIPT_CONFIRM");
        assertEquals(HttpStatus.OK, post(BASE + "/" + receiptId + "/confirm", token).getStatusCode());
        long after = auditCount(receiptId, "GOODS_RECEIPT_CONFIRM");
        System.out.println("[M6T05-EVIDENCE] E8 审计差分: " + before + " → " + after);
        assertEquals(before + 1, after, "恰好 1 条");

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT biz_type, result, change_summary FROM audit_logs "
                        + "WHERE biz_id = ? AND action = 'GOODS_RECEIPT_CONFIRM' ORDER BY id DESC LIMIT 1",
                receiptId);
        assertEquals("GOODS_RECEIPT", audit.get("biz_type"), "biz_type 口径");
        assertEquals("SUCCESS", audit.get("result"));
        assertNotNull(audit.get("change_summary"));
    }

    // =====================================================================
    // E9：流水字段口径
    // =====================================================================

    @Test
    @DisplayName("E9 流水：txn_type=PURCHASE_INBOUND / biz_type=GOODS_RECEIPT / biz_ref_id=receiptId / 幂等键格式")
    void inboundTxnFields() throws Exception {
        long poId = createPo("ORDERED", "100.000", "0.000");
        long receiptId = createDraftReceipt(poId, firstLineId(poId), "40.000", "idem-e9");
        assertEquals(HttpStatus.OK, post(BASE + "/" + receiptId + "/confirm", login("operator01")).getStatusCode());

        Map<String, Object> txn = jdbc.queryForMap(
                "SELECT txn_type, biz_type, biz_ref_id, idempotency_key, qty_delta, qty_before, qty_after "
                        + "FROM inventory_transactions WHERE txn_type = 'PURCHASE_INBOUND' AND biz_ref_id = ?",
                receiptId);
        System.out.println("[M6T05-EVIDENCE] E9 流水: " + txn);
        assertEquals("PURCHASE_INBOUND", txn.get("txn_type"));
        assertEquals("GOODS_RECEIPT", txn.get("biz_type"));
        assertEquals(receiptId, ((Number) txn.get("biz_ref_id")).longValue());
        assertEquals("PURCHASE_INBOUND:GOODS_RECEIPT:" + receiptId + ":" + SKU7,
                txn.get("idempotency_key"), "幂等键 = PURCHASE_INBOUND:GOODS_RECEIPT:<rid>:<skuId>（B0-4）");
        assertEquals("40.000", qtyText(txn.get("qty_delta")));
        assertEquals("100.000", qtyText(txn.get("qty_before")));
        assertEquals("140.000", qtyText(txn.get("qty_after")));
    }

    // =====================================================================
    // E10：事务回滚（第一条成功、第二条失败）
    // =====================================================================

    @Test
    @DisplayName("E10 第二条明细失败 ⇒ 整事务回滚：无流水、余额不变、到货单仍 DRAFT、采购单不变")
    void rollbackWhenSecondItemFails() throws Exception {
        long poId = createPoTwoLines("ORDERED", "100.000", "50.000");
        long[] itemIds = poItemIds(poId);
        long receiptId = createDraftReceiptTwoLines(poId, itemIds[0], "40.000", itemIds[1], "20.000", "idem-e10");

        // 注入：向 SKU7B（第二条明细）插流水时抛错 ⇒ 同事务后半段失败
        installFailTrigger(SKU7B);
        try {
            String onHand7Before = onHand(WH7, SKU7);
            String onHand7bBefore = onHand(WH7, SKU7B);
            long txnBefore = txnCount();

            ResponseEntity<String> res = post(BASE + "/" + receiptId + "/confirm", login("operator01"));
            System.out.println("[M6T05-EVIDENCE] E10 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
            assertFalse(res.getStatusCode().is2xxSuccessful(), "注入失败后不得返回成功");

            System.out.println("[M6T05-EVIDENCE] E10 回滚证据：txn " + txnBefore + " → " + txnCount()
                    + " | SKU7 on_hand " + onHand7Before + " → " + onHand(WH7, SKU7)
                    + " | SKU7B on_hand " + onHand7bBefore + " → " + onHand(WH7, SKU7B));
            assertEquals(txnBefore, txnCount(), "★ 整体回滚：第一条的流水也必须消失");
            assertEquals(onHand7Before, onHand(WH7, SKU7), "★ 第一条的余额变更必须回滚");
            assertEquals(onHand7bBefore, onHand(WH7, SKU7B));
            assertEquals("DRAFT", receiptStatus(receiptId), "★ 到货单仍 DRAFT");
            Map<String, Object> po = poRow(poId);
            assertEquals("0.000", qtyText(po.get("received_qty")), "★ 采购单 received_qty 不变");
            assertEquals("ORDERED", po.get("status"), "★ 采购单状态不变");
            assertReconciled();
        } finally {
            dropTriggers();
        }
    }

    // =====================================================================
    // E11：多单累计
    // =====================================================================

    @Test
    @DisplayName("E11 两张到货单分批登记同一采购单 ⇒ received_qty 累加、状态推导正确")
    void multipleReceiptsAccumulate() throws Exception {
        long poId = createPo("ORDERED", "100.000", "0.000");
        long poItemId = firstLineId(poId);
        long first = createDraftReceipt(poId, poItemId, "40.000", "idem-e11a");
        long second = createDraftReceipt(poId, poItemId, "60.000", "idem-e11b");
        String token = login("operator01");

        assertEquals(HttpStatus.OK, post(BASE + "/" + first + "/confirm", token).getStatusCode());
        Map<String, Object> afterFirst = poRow(poId);
        assertEquals("40.000", qtyText(afterFirst.get("received_qty")));
        assertEquals("PARTIALLY_RECEIVED", afterFirst.get("status"));

        assertEquals(HttpStatus.OK, post(BASE + "/" + second + "/confirm", token).getStatusCode());
        Map<String, Object> afterSecond = poRow(poId);
        System.out.println("[M6T05-EVIDENCE] E11 累计: received_qty=" + qtyText(afterSecond.get("received_qty"))
                + " status=" + afterSecond.get("status")
                + " 明细行 received_qty=" + qtyText(itemRow(poItemId).get("received_qty")));
        assertEquals("100.000", qtyText(afterSecond.get("received_qty")), "两张单累计 40 + 60");
        assertEquals("RECEIVED", afterSecond.get("status"));
        assertEquals("100.000", qtyText(itemRow(poItemId).get("received_qty")), "累加落在明细行上");
        assertEquals("200.000", onHand(WH7, SKU7));
        assertReconciled();
    }

    // =====================================================================
    // E12：库存乐观锁冲突
    // =====================================================================

    @Test
    @DisplayName("E12 余额 version 变化 ⇒ INV-5002 409，且无部分写入")
    void inventoryOptimisticConflict() throws Exception {
        long poId = createPo("ORDERED", "100.000", "0.000");
        long receiptId = createDraftReceipt(poId, firstLineId(poId), "40.000", "idem-e12");

        // 注入：每次插流水都推进余额 version ⇒ 随后的 CAS 必然 0 行 ⇒ 重试耗尽 ⇒ INV-5002
        installVersionBumpTrigger();
        try {
            String onHandBefore = onHand(WH7, SKU7);
            long txnBefore = txnCount();
            ResponseEntity<String> res = post(BASE + "/" + receiptId + "/confirm", login("operator01"));
            System.out.println("[M6T05-EVIDENCE] E12 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
            assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
            assertEquals(ErrorCodes.INV_5002, codeOf(res));

            System.out.println("[M6T05-EVIDENCE] E12 无部分写入：txn " + txnBefore + " → " + txnCount()
                    + " | on_hand " + onHandBefore + " → " + onHand(WH7, SKU7));
            assertEquals(txnBefore, txnCount(), "★ 重试 SAVEPOINT 必须抹掉孤儿流水");
            assertEquals(onHandBefore, onHand(WH7, SKU7), "★ 余额不得变");
            assertEquals("DRAFT", receiptStatus(receiptId), "★ 到货单仍 DRAFT");
            assertEquals("0.000", qtyText(poRow(poId).get("received_qty")), "★ 采购单不变");
            assertReconciled();
        } finally {
            dropTriggers();
        }
    }

    // =====================================================================
    // 种子与辅助
    // =====================================================================

    /** 成对种子：余额行 + {@code INIT_IMPORT} 流水（{@code TEST_STRATEGY §7}：余额须可由流水推导）。 */
    private void seedBalanceWithTxn(long skuId, String onHand, String inTransit) {
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                        + "in_transit_qty, committed_qty, version, created_by) VALUES (?, ?, ?, ?, ?, 0.000, 0, ?)",
                TEAM7, WH7, skuId, new BigDecimal(onHand), new BigDecimal(inTransit), OPERATOR_ID);
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                        + "qty_before, qty_after, biz_type, idempotency_key, operator_id, occurred_at) "
                        + "VALUES (?, ?, ?, 'INIT_IMPORT', ?, 0.000, ?, 'IMPORT', ?, ?, UTC_TIMESTAMP(3))",
                TEAM7, WH7, skuId, new BigDecimal(onHand), new BigDecimal(onHand),
                "seed-init-" + skuId, OPERATOR_ID);
    }

    /** 建采购单（1 条明细），返回 poId。 */
    private long createPo(String status, String orderedQty, String receivedQty) {
        long poId = insertPoHead(status, TEAM7, WH7, SUPPLIER7, orderedQty, receivedQty);
        insertPoLine(poId, 1, TEAM7, SKU7, PRODUCT7, orderedQty, receivedQty);
        return poId;
    }

    /** 建采购单（2 条明细：SKU7 / SKU7B）。 */
    private long createPoTwoLines(String status, String qtyA, String qtyB) {
        long poId = insertPoHead(status, TEAM7, WH7, SUPPLIER7,
                new BigDecimal(qtyA).add(new BigDecimal(qtyB)).toPlainString(), "0.000");
        insertPoLine(poId, 1, TEAM7, SKU7, PRODUCT7, qtyA, "0.000");
        insertPoLine(poId, 2, TEAM7, SKU7B, PRODUCT7, qtyB, "0.000");
        return poId;
    }

    private long createPoTeam8(String status, String orderedQty) {
        long poId = insertPoHead(status, TEAM8, WH8, SUPPLIER8, orderedQty, "0.000");
        insertPoLine(poId, 1, TEAM8, SKU8, PRODUCT8, orderedQty, "0.000");
        return poId;
    }

    private long insertPoHead(String status, long teamId, long warehouseId, long supplierId,
                              String totalQty, String receivedQty) {
        String poNo = "PO-C" + teamId + "-" + String.format("%04d", ++poSeq);
        jdbc.update("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, status, source, "
                        + "total_qty, total_amount, received_qty, version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, 1000.00, ?, 0, ?, ?)",
                teamId, poNo, supplierId, warehouseId, status,
                new BigDecimal(totalQty), new BigDecimal(receivedQty), APPROVER_ID, APPROVER_ID);
        return jdbc.queryForObject("SELECT id FROM purchase_orders WHERE po_no = ?", Long.class, poNo);
    }

    private void insertPoLine(long poId, int lineNo, long teamId, long skuId, long productId,
                              String orderedQty, String receivedQty) {
        jdbc.update("INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, product_id, "
                        + "ordered_qty, received_qty, unit_price, amount, version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 10.0000, 1000.00, 0, ?, ?)",
                teamId, poId, lineNo, skuId, productId,
                new BigDecimal(orderedQty), new BigDecimal(receivedQty), APPROVER_ID, APPROVER_ID);
    }

    /** 直插一张 DRAFT 到货单（1 条明细）。 */
    private long createDraftReceipt(long poId, long poItemId, String qty, String idem) {
        String receiptNo = "GR-C-" + (++idemSeq) + "-" + idem;
        jdbc.update("INSERT INTO goods_receipts (team_id, receipt_no, purchase_order_id, warehouse_id, status, "
                        + "receipt_date, total_qty, total_amount, idempotency_key, operator_id, version, "
                        + "created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, 'DRAFT', '2026-10-21', ?, 0.00, ?, ?, 0, ?, ?)",
                TEAM7, receiptNo, poId, WH7, new BigDecimal(qty), idem, OPERATOR_ID, OPERATOR_ID, OPERATOR_ID);
        long receiptId = jdbc.queryForObject(
                "SELECT id FROM goods_receipts WHERE idempotency_key = ?", Long.class, idem);
        insertReceiptLine(TEAM7, receiptId, poItemId, SKU7, qty);
        return receiptId;
    }

    /** 直插一张 DRAFT 到货单（2 条明细：poItemA→SKU7，poItemB→SKU7B）。 */
    private long createDraftReceiptTwoLines(long poId, long poItemA, String qtyA,
                                           long poItemB, String qtyB, String idem) {
        String receiptNo = "GR-C-" + (++idemSeq) + "-" + idem;
        BigDecimal total = new BigDecimal(qtyA).add(new BigDecimal(qtyB));
        jdbc.update("INSERT INTO goods_receipts (team_id, receipt_no, purchase_order_id, warehouse_id, status, "
                        + "receipt_date, total_qty, total_amount, idempotency_key, operator_id, version, "
                        + "created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, 'DRAFT', '2026-10-21', ?, 0.00, ?, ?, 0, ?, ?)",
                TEAM7, receiptNo, poId, WH7, total, idem, OPERATOR_ID, OPERATOR_ID, OPERATOR_ID);
        long receiptId = jdbc.queryForObject(
                "SELECT id FROM goods_receipts WHERE idempotency_key = ?", Long.class, idem);
        insertReceiptLine(TEAM7, receiptId, poItemA, SKU7, qtyA);
        insertReceiptLine(TEAM7, receiptId, poItemB, SKU7B, qtyB);
        return receiptId;
    }

    private long createDraftReceiptTeam8(long poId, long poItemId, String qty, String idem) {
        String receiptNo = "GR-C8-" + (++idemSeq);
        jdbc.update("INSERT INTO goods_receipts (team_id, receipt_no, purchase_order_id, warehouse_id, status, "
                        + "receipt_date, total_qty, total_amount, idempotency_key, operator_id, version, "
                        + "created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, 'DRAFT', '2026-10-21', ?, 0.00, ?, ?, 0, ?, ?)",
                TEAM8, receiptNo, poId, WH8, new BigDecimal(qty), idem, OPERATOR_ID, OPERATOR_ID, OPERATOR_ID);
        long receiptId = jdbc.queryForObject(
                "SELECT id FROM goods_receipts WHERE idempotency_key = ?", Long.class, idem);
        insertReceiptLine(TEAM8, receiptId, poItemId, SKU8, qty);
        return receiptId;
    }

    private void insertReceiptLine(long teamId, long receiptId, long poItemId, long skuId, String qty) {
        BigDecimal v = new BigDecimal(qty);
        jdbc.update("INSERT INTO goods_receipt_items (team_id, goods_receipt_id, purchase_order_item_id, sku_id, "
                        + "received_qty, unit_price, amount, created_by) VALUES (?, ?, ?, ?, ?, 10.0000, ?, ?)",
                teamId, receiptId, poItemId, skuId, v, v.multiply(new BigDecimal("10.0000"))
                        .setScale(2, java.math.RoundingMode.HALF_UP), OPERATOR_ID);
    }

    private long firstLineId(long poId) {
        return jdbc.queryForObject(
                "SELECT id FROM purchase_order_items WHERE purchase_order_id = ? ORDER BY line_no",
                Long.class, poId);
    }

    private long[] poItemIds(long poId) {
        return jdbc.queryForList(
                        "SELECT id FROM purchase_order_items WHERE purchase_order_id = ? ORDER BY line_no",
                        Long.class, poId)
                .stream().mapToLong(Long::longValue).toArray();
    }

    // ---- 触发器注入（确定性故障，测试内建、finally 必删）----

    private void installFailTrigger(long skuId) {
        jdbc.execute("DROP TRIGGER IF EXISTS " + TRG_FAIL);
        jdbc.execute("CREATE TRIGGER " + TRG_FAIL + " BEFORE INSERT ON inventory_transactions "
                + "FOR EACH ROW BEGIN IF NEW.sku_id = " + skuId + " THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected inbound failure (E10)'; "
                + "END IF; END");
    }

    private void installVersionBumpTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + TRG_VERSION);
        jdbc.execute("CREATE TRIGGER " + TRG_VERSION + " BEFORE INSERT ON inventory_transactions "
                + "FOR EACH ROW BEGIN UPDATE inventory_balances SET version = version + 1 "
                + "WHERE team_id = NEW.team_id AND warehouse_id = NEW.warehouse_id "
                + "AND sku_id = NEW.sku_id; END");
    }

    private void dropTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + TRG_FAIL);
        jdbc.execute("DROP TRIGGER IF EXISTS " + TRG_VERSION);
    }

    // ---- 查询辅助 ----

    private Map<String, Object> poRow(long poId) {
        return jdbc.queryForMap("SELECT status, received_qty, closed_at FROM purchase_orders WHERE id = ?", poId);
    }

    private Map<String, Object> itemRow(long poItemId) {
        return jdbc.queryForMap("SELECT received_qty FROM purchase_order_items WHERE id = ?", poItemId);
    }

    private String receiptStatus(long receiptId) {
        return jdbc.queryForObject("SELECT status FROM goods_receipts WHERE id = ?", String.class, receiptId);
    }

    private String onHand(long warehouseId, long skuId) {
        return jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE warehouse_id = ? AND sku_id = ?",
                String.class, warehouseId, skuId);
    }

    private String inTransit(long warehouseId, long skuId) {
        return jdbc.queryForObject(
                "SELECT in_transit_qty FROM inventory_balances WHERE warehouse_id = ? AND sku_id = ?",
                String.class, warehouseId, skuId);
    }

    private long txnCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions", Long.class);
    }

    private long auditCount(long bizId, String action) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE biz_id = ? AND action = ?", Long.class, bizId, action);
    }

    /** 对账断言（{@code TEST_STRATEGY §7}）：余额 = 全部流水 {@code qty_delta} 之和（不收 REVERSAL 单列）。 */
    private void assertReconciled() {
        Long diff = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_balances b "
                        + "LEFT JOIN (SELECT team_id, warehouse_id, sku_id, SUM(qty_delta) AS d "
                        + "           FROM inventory_transactions GROUP BY team_id, warehouse_id, sku_id) t "
                        + "  ON t.team_id = b.team_id AND t.warehouse_id = b.warehouse_id "
                        + " AND t.sku_id = b.sku_id "
                        + "WHERE b.on_hand_qty <> COALESCE(t.d, 0)", Long.class);
        System.out.println("[M6T05-EVIDENCE] 对账差异行数=" + diff);
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

    private ResponseEntity<String> post(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
    }
}
