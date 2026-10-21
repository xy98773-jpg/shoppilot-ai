package com.shoppilot.purchasing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code R180} —— 确认到货幂等键<b>补 {@code :<purchase_order_item_id>} 后缀</b>的回归测试。
 *
 * <h2>为什么需要这个后缀（SSOT）</h2>
 * <p>{@code DATABASE_DESIGN.md §5.10}「幂等键的构造」后缀用途 ③（{@code R180} 订正）：
 * {@code purchase_order_items} <b>无</b> {@code (po_id, sku_id)} 唯一键（{@code V1.0.11:139}），
 * {@code goods_receipt_items} 只约束 {@code (receipt_id, purchase_order_item_id)} ⇒
 * 同一张到货单<b>可以合法</b>出现「同 SKU、不同采购明细行」两行。
 * 幂等键基序是「4 段 + 可选后缀」（{@code DATABASE_CONSTRAINTS.md:104}），
 * 凡「单据粒度不足以唯一确定一行流水」的场景后缀<b>必填</b>：
 * <pre>{@code PURCHASE_INBOUND:GOODS_RECEIPT:<receiptId>:<skuId>:<purchaseOrderItemId>}</pre>
 *
 * <h2>修前/修后的行为差（本类 D1/D2 即锁定点）</h2>
 * <ul>
 *   <li><b>修前</b>（4 段键）：两行同 SKU ⇒ 第二行命中同一唯一键
 *       {@code uk_invtxn_team_idem} ⇒ 指纹比对：数量不同 ⇒ {@code INV-5003}（409），
 *       整事务回滚 ⇒ 两行<b>都</b>不入库；若两行数量恰好相同则指纹一致 ⇒
 *       第二行被静默判为「重复提交」而<b>少入库</b>，但采购单 {@code received_qty} 仍按两行累加 ⇒ 账实不符；</li>
 *   <li><b>修后</b>（5 段键）：两行各自成键 ⇒ 两笔流水、两行都累加，且
 *       {@code received_qty} = 两行之和、余额 = 种子 + 两行之和 ⇒ 对账恒为 0。</li>
 * </ul>
 *
 * <h2>★ 种子纪律</h2>
 * <p>库存种子<b>一律成对</b>（余额行 + 等额 {@code INIT_IMPORT} 流水），否则对账在种子阶段即假 FAIL。
 * 采购单两行明细<b>直插</b>（B0-6：无 {@code (po_id, sku_id)} 唯一键，故同 SKU 两行可达）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GoodsReceiptDuplicateSkuLinesIntegrationTest {

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

    private static final long PRODUCT7 = 9001L;
    private static final long PRODUCT8 = 9101L;
    private static final long SKU7 = 9002L;
    private static final long SKU8 = 9102L;
    private static final long WH7 = 9004L;
    private static final long WH8 = 9104L;
    private static final long SUPPLIER7 = 8001L;
    private static final long SUPPLIER8 = 8101L;

    /** 成对种子的初始在手量（余额行与 {@code INIT_IMPORT} 流水同为 100.000）。 */
    private static final BigDecimal SEED_ON_HAND = new BigDecimal("100.000");

    private static final String RECEIPTS = "/api/v1/purchasing/receipts";

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
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM goods_receipt_items");
        jdbc.execute("DELETE FROM goods_receipts");
        jdbc.execute("DELETE FROM purchase_order_items");
        jdbc.execute("DELETE FROM purchase_orders");
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
        poSeq = 0;
        idemSeq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-R180-7', 'R180 七团', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-R180-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'approver01', 'approver01@example.com', ?, '小审', 'ACTIVE')",
                OPERATOR_ID, PASSWORD_HASH, APPROVER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'APPROVER', 'ACTIVE')",
                TEAM7, OPERATOR_ID, TEAM7, APPROVER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-R180-7', '七团商品'), (?, ?, 'P-R180-8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-R180-7', '七团仓'), (?, ?, 'WH-R180-8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-R180-7', '七团供应商'), (?, ?, 'SUP-R180-8', '八团供应商')",
                SUPPLIER7, TEAM7, SUPPLIER8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-R180-7', '七团SKU'), (?, ?, ?, 'SKU-R180-8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);

        seedInventoryPair(TEAM7, WH7, SKU7, SEED_ON_HAND);
    }

    // =====================================================================
    // D1：同 SKU 两行、数量相同（10 + 10）
    // =====================================================================

    @Test
    @DisplayName("R180-D1 同 SKU 两行 10+10：确认成功；流水 2 笔；PO received_qty=20.000；余额 +20.000；对账 0")
    void duplicateSkuTwoLinesSameQty() throws Exception {
        long[] items = createPo(TEAM7, WH7, new String[] {"100.000", "100.000"});
        long receiptId = createReceiptViaApi(items, new String[] {"10.000", "10.000"});
        String token = login("approver01");

        String onHandBefore = onHand(WH7, SKU7);
        assertEquals("100.000", onHandBefore, "前置：成对种子");

        ResponseEntity<String> res = confirm(receiptId, token);
        System.out.println("[R180-EVIDENCE] D1 confirm: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), "同 SKU 两行必须可确认成功：" + res.getBody());

        List<Map<String, Object>> txns = inboundTxns(receiptId);
        System.out.println("[R180-EVIDENCE] D1 流水: " + txns);
        assertEquals(2, txns.size(), "★ 两行各有 1 笔 PURCHASE_INBOUND 流水");
        assertEquals("PURCHASE_INBOUND:GOODS_RECEIPT:" + receiptId + ":" + SKU7 + ":" + items[0],
                txns.get(0).get("idempotency_key"), "第 1 行的键含各自 purchase_order_item_id");
        assertEquals("PURCHASE_INBOUND:GOODS_RECEIPT:" + receiptId + ":" + SKU7 + ":" + items[1],
                txns.get(1).get("idempotency_key"), "第 2 行的键含各自 purchase_order_item_id");
        assertEquals("10.000", qtyText(txns.get(0).get("qty_delta")));
        assertEquals("10.000", qtyText(txns.get(1).get("qty_delta")));

        assertEquals("20.000", qtyText(poReceivedQty(poIdOf(items))),
                "采购单 received_qty = 两行之和");
        assertEquals("10.000", qtyText(itemReceivedQty(items[0])), "明细行 1 累加 10");
        assertEquals("10.000", qtyText(itemReceivedQty(items[1])), "明细行 2 累加 10");

        assertEquals("120.000", onHand(WH7, SKU7), "余额 = 100 + 20");
        assertEquals("CONFIRMED", receiptStatus(receiptId));
        assertReconciled(WH7, SKU7);
        assertWholeTableReconciled();
    }

    // =====================================================================
    // D2：同 SKU 两行、数量不等（10 + 30）—— 修前必 INV-5003
    // =====================================================================

    @Test
    @DisplayName("R180-D2 同 SKU 两行 10+30（数量不等）：确认成功（修前 INV-5003）；PO received_qty=40.000")
    void duplicateSkuTwoLinesDifferentQty() throws Exception {
        long[] items = createPo(TEAM7, WH7, new String[] {"100.000", "100.000"});
        long receiptId = createReceiptViaApi(items, new String[] {"10.000", "30.000"});

        ResponseEntity<String> res = confirm(receiptId, login("approver01"));
        System.out.println("[R180-EVIDENCE] D2 confirm: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "★ 数量不同也必须成功：修前第二行命中同键且指纹不同 ⇒ INV-5003(409)。实际：" + res.getBody());

        List<Map<String, Object>> txns = inboundTxns(receiptId);
        System.out.println("[R180-EVIDENCE] D2 流水: " + txns);
        assertEquals(2, txns.size(), "两行各 1 笔流水（修前为 0 笔且整单回滚）");
        assertEquals("10.000", qtyText(txns.get(0).get("qty_delta")));
        assertEquals("30.000", qtyText(txns.get(1).get("qty_delta")));

        assertEquals("40.000", qtyText(poReceivedQty(poIdOf(items))));
        assertEquals("140.000", onHand(WH7, SKU7), "余额 = 100 + 40");
        assertEquals("PARTIALLY_RECEIVED", poStatus(poIdOf(items)), "40 < Σ ordered(200)");
        assertReconciled(WH7, SKU7);
        assertWholeTableReconciled();
    }

    // =====================================================================
    // D3：幂等重放
    // =====================================================================

    @Test
    @DisplayName("R180-D3 同 receipt 再 confirm 一次：200 幂等成功（返回首次结果），不新增流水、不重复累加")
    void replayConfirmIsIdempotent() throws Exception {
        long[] items = createPo(TEAM7, WH7, new String[] {"100.000", "100.000"});
        long receiptId = createReceiptViaApi(items, new String[] {"10.000", "30.000"});
        String token = login("approver01");

        ResponseEntity<String> first = confirm(receiptId, token);
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        JsonNode firstData = json.readTree(first.getBody()).get("data");
        long txnAfterFirst = inboundTxns(receiptId).size();
        String receivedAfterFirst = qtyText(poReceivedQty(poIdOf(items)));
        String onHandAfterFirst = onHand(WH7, SKU7);
        int versionAfterFirst = jdbc.queryForObject(
                "SELECT version FROM goods_receipts WHERE id = ?", Integer.class, receiptId);

        ResponseEntity<String> replay = confirm(receiptId, token);
        System.out.println("[R180-EVIDENCE] D3 二次 confirm（原始响应）: "
                + replay.getStatusCode().value() + " " + replay.getBody());

        // ★ 依 SSOT：DATABASE_CONSTRAINTS.md:85（幂等 #5「重复确认且已 CONFIRMED → 返回成功（幂等）」）
        //   + TEST_STRATEGY.md:343（§8.2 R-4「幂等：只入库一次，返回已确认结果」）。
        //   ⚠ 原 M6-T05+T06 实现在此抛 409 PO-9003，是令文缺陷，现已按 SSOT 订正为幂等成功。
        assertEquals(HttpStatus.OK, replay.getStatusCode(), "★ 重复确认必须幂等成功：" + replay.getBody());
        JsonNode replayData = json.readTree(replay.getBody()).get("data");
        assertEquals("CONFIRMED", replayData.get("status").asText());
        assertEquals(firstData.get("goods_receipt_id").asLong(),
                replayData.get("goods_receipt_id").asLong(), "同一张到货单");
        assertEquals(firstData.get("confirmed_at").asText(), replayData.get("confirmed_at").asText(),
                "★ 返回首次确认结果（confirmed_at 不变）");
        assertEquals(versionAfterFirst, replayData.get("version").asInt(), "★ 幂等路径不得推进 version");
        assertEquals(versionAfterFirst, jdbc.queryForObject(
                "SELECT version FROM goods_receipts WHERE id = ?", Integer.class, receiptId),
                "★ 库内 version 不得变");

        assertEquals(txnAfterFirst, inboundTxns(receiptId).size(), "★ 不得新增流水");
        assertEquals(receivedAfterFirst, qtyText(poReceivedQty(poIdOf(items))), "★ 不得重复累加");
        assertEquals(onHandAfterFirst, onHand(WH7, SKU7), "★ 余额不得重复增加");
        assertReconciled(WH7, SKU7);
    }

    // =====================================================================
    // D4：单行路径回归（键格式）
    // =====================================================================

    @Test
    @DisplayName("R180-D4 单行回归：流水键 = PURCHASE_INBOUND:GOODS_RECEIPT:<r>:<sku>:<item>，且只 1 笔")
    void singleLineKeyStillFourSegmentsPlusSuffix() throws Exception {
        long[] items = createPo(TEAM7, WH7, new String[] {"100.000"});
        long receiptId = createReceiptViaApi(items, new String[] {"40.000"});

        assertEquals(HttpStatus.OK, confirm(receiptId, login("approver01")).getStatusCode());

        List<Map<String, Object>> txns = inboundTxns(receiptId);
        System.out.println("[R180-EVIDENCE] D4 流水: " + txns);
        assertEquals(1, txns.size(), "单行路径只 1 笔流水");
        String key = (String) txns.get(0).get("idempotency_key");
        assertEquals("PURCHASE_INBOUND:GOODS_RECEIPT:" + receiptId + ":" + SKU7 + ":" + items[0], key,
                "★ 键 = 4 段基序 + :<purchase_order_item_id> 后缀");
        assertEquals(5, key.split(":").length, "键恰为 5 段（4 段基序 + 1 段后缀）");
        assertTrue(key.endsWith(":" + items[0]), "后缀必须是 purchase_order_item_id（不是随机值）");
        assertReconciled(WH7, SKU7);
    }

    // =====================================================================
    // D5：全表对账
    // =====================================================================

    @Test
    @DisplayName("R180-D5 全表对账：Σ qty_delta == on_hand（差异行数 0）")
    void wholeTableReconcilesAfterDuplicateSkuConfirm() throws Exception {
        long[] twoLines = createPo(TEAM7, WH7, new String[] {"100.000", "100.000"});
        long receiptA = createReceiptViaApi(twoLines, new String[] {"10.000", "30.000"});
        String token = login("approver01");
        assertEquals(HttpStatus.OK, confirm(receiptA, token).getStatusCode());

        // 再加一张单行单（同一 PO 的第三行不存在 ⇒ 复用第一行，剩余待收 90）
        long receiptB = createReceiptViaApi(new long[] {twoLines[0]}, new String[] {"5.000"});
        assertEquals(HttpStatus.OK, confirm(receiptB, token).getStatusCode());

        System.out.println("[R180-EVIDENCE] D5 on_hand=" + onHand(WH7, SKU7)
                + " | 流水笔数=" + jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_transactions", Long.class));
        assertEquals("145.000", onHand(WH7, SKU7), "100 + 40 + 5");
        assertReconciled(WH7, SKU7);
        assertWholeTableReconciled();
    }

    // =====================================================================
    // 辅助：种子
    // =====================================================================

    /** 成对写入库存种子：余额行 + 等额 {@code INIT_IMPORT} 流水。 */
    private void seedInventoryPair(long teamId, long warehouseId, long skuId, BigDecimal qty) {
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                        + "in_transit_qty, committed_qty, version) VALUES (?, ?, ?, ?, 0.000, 0.000, 0)",
                teamId, warehouseId, skuId, qty);
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, "
                        + "qty_delta, qty_before, qty_after, biz_type, idempotency_key, occurred_at) "
                        + "VALUES (?, ?, ?, 'INIT_IMPORT', ?, 0.000, ?, 'IMPORT', ?, NOW(3))",
                teamId, warehouseId, skuId, qty, qty, "SEED-INIT-" + teamId + "-" + skuId);
    }

    /**
     * 直插 {@code ORDERED} 采购单 + N 条明细（<b>全部同一个 SKU</b>，B0-6：
     * {@code purchase_order_items} 无 {@code (po_id, sku_id)} 唯一键 ⇒ 合法可达）。
     *
     * @return 明细 ID 数组（按 {@code line_no} 升序）
     */
    private long[] createPo(long teamId, long warehouseId, String[] orderedQtys) {
        BigDecimal total = BigDecimal.ZERO;
        for (String q : orderedQtys) {
            total = total.add(new BigDecimal(q));
        }
        String poNo = "PO-R180-" + String.format("%04d", ++poSeq);
        jdbc.update("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, status, source, "
                        + "total_qty, total_amount, received_qty, version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, 'ORDERED', 'MANUAL', ?, 1000.00, 0.000, 0, ?, ?)",
                teamId, poNo, teamId == TEAM7 ? SUPPLIER7 : SUPPLIER8, warehouseId,
                total.setScale(3, RoundingMode.HALF_UP), APPROVER_ID, APPROVER_ID);
        long poId = jdbc.queryForObject(
                "SELECT id FROM purchase_orders WHERE po_no = ?", Long.class, poNo);

        long skuId = teamId == TEAM7 ? SKU7 : SKU8;
        long productId = teamId == TEAM7 ? PRODUCT7 : PRODUCT8;
        for (int i = 0; i < orderedQtys.length; i++) {
            jdbc.update("INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, "
                            + "product_id, ordered_qty, received_qty, unit_price, amount, version, "
                            + "created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, 0.000, 10.0000, 1000.00, 0, ?, ?)",
                    teamId, poId, i + 1, skuId, productId,
                    new BigDecimal(orderedQtys[i]).setScale(3, RoundingMode.HALF_UP), APPROVER_ID, APPROVER_ID);
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM purchase_order_items WHERE purchase_order_id = ? ORDER BY line_no",
                Long.class, poId);
        return ids.stream().mapToLong(Long::longValue).toArray();
    }

    // =====================================================================
    // 辅助：HTTP
    // =====================================================================

    /** 走<b>真实登记端点</b>建 DRAFT 到货单（同 SKU 多行是合法入参：{@code prepareItems} 只去重 poItemId）。 */
    private long createReceiptViaApi(long[] poItemIds, String[] qtys) throws Exception {
        long poId = poIdOf(poItemIds[0]);
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < poItemIds.length; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"purchase_order_item_id\":").append(poItemIds[i])
                    .append(",\"received_qty\":\"").append(qtys[i])
                    .append("\",\"unit_price\":\"10.0000\"}");
        }
        String body = "{\"purchase_order_id\":" + poId
                + ",\"warehouse_id\":" + WH7
                + ",\"receipt_date\":\"2026-10-21\""
                + ",\"items\":[" + items + "]}";
        ResponseEntity<String> res = post(RECEIPTS, body, login("approver01"),
                "IDEM-R180-" + TEAM7 + "-" + (++idemSeq));
        System.out.println("[R180-EVIDENCE] 登记 " + poItemIds.length + " 行: "
                + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CREATED, res.getStatusCode(), "登记到货应 201：" + res.getBody());
        long receiptId = json.readTree(res.getBody()).get("data").get("goods_receipt_id").asLong();
        assertTrue(receiptId > 0, "登记响应必须带 data.goods_receipt_id");
        return receiptId;
    }

    private ResponseEntity<String> confirm(long receiptId, String token) {
        return post(RECEIPTS + "/" + receiptId + "/confirm", "{}", token, null);
    }

    private ResponseEntity<String> post(String path, String body, String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
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

    // =====================================================================
    // 辅助：查询与对账
    // =====================================================================

    private long poIdOf(long[] poItemIds) {
        return poIdOf(poItemIds[0]);
    }

    private long poIdOf(long poItemId) {
        return jdbc.queryForObject(
                "SELECT purchase_order_id FROM purchase_order_items WHERE id = ?", Long.class, poItemId);
    }

    private Object poReceivedQty(long poId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT received_qty FROM purchase_orders WHERE id = ?", poId);
        return row.get("received_qty");
    }

    private String poStatus(long poId) {
        return jdbc.queryForObject(
                "SELECT status FROM purchase_orders WHERE id = ?", String.class, poId);
    }

    private Object itemReceivedQty(long poItemId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT received_qty FROM purchase_order_items WHERE id = ?", poItemId);
        return row.get("received_qty");
    }

    private String receiptStatus(long receiptId) {
        return jdbc.queryForObject(
                "SELECT status FROM goods_receipts WHERE id = ?", String.class, receiptId);
    }

    private String onHand(long warehouseId, long skuId) {
        return jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE warehouse_id = ? AND sku_id = ?",
                String.class, warehouseId, skuId);
    }

    /** 该到货单产生的 {@code PURCHASE_INBOUND} 流水（按 {@code id} 升序）。 */
    private List<Map<String, Object>> inboundTxns(long receiptId) {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT idempotency_key, qty_delta, qty_before, qty_after, sku_id "
                        + "FROM inventory_transactions WHERE txn_type = 'PURCHASE_INBOUND' "
                        + "AND biz_ref_id = ? ORDER BY id", receiptId));
    }

    /** 单 {@code (warehouse, sku)} 维度对账：{@code on_hand == Σ qty_delta}（排除 {@code REVERSAL}）。 */
    private void assertReconciled(long warehouseId, long skuId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.on_hand_qty AS balance_qty, "
                        + "COALESCE((SELECT SUM(t.qty_delta) FROM inventory_transactions t "
                        + "  WHERE t.team_id = b.team_id AND t.warehouse_id = b.warehouse_id "
                        + "    AND t.sku_id = b.sku_id AND t.txn_type <> 'REVERSAL'), 0) AS ledger_qty "
                        + "FROM inventory_balances b "
                        + "WHERE b.warehouse_id = ? AND b.sku_id = ?", warehouseId, skuId);
        assertEquals(1, rows.size(), "余额行必须唯一存在");
        BigDecimal balance = (BigDecimal) rows.get(0).get("balance_qty");
        BigDecimal ledger = (BigDecimal) rows.get(0).get("ledger_qty");
        System.out.println("[R180-EVIDENCE] 对账 wh=" + warehouseId + " sku=" + skuId
                + " | on_hand=" + balance.toPlainString()
                + " | Σqty_delta=" + ledger.toPlainString()
                + " | 判定=" + (balance.compareTo(ledger) == 0 ? "一致" : "不一致"));
        assertNotNull(balance);
        assertNotNull(ledger);
        assertEquals(0, balance.compareTo(ledger), "★ 对账失败：余额 != 流水累计");
    }

    /** 全表对账：差异行数必须为 0（{@code on_hand <> Σ qty_delta} 的余额行数）。 */
    private void assertWholeTableReconciled() {
        Long diff = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_balances b "
                        + "LEFT JOIN (SELECT team_id, warehouse_id, sku_id, SUM(qty_delta) AS d "
                        + "           FROM inventory_transactions WHERE txn_type <> 'REVERSAL' "
                        + "           GROUP BY team_id, warehouse_id, sku_id) t "
                        + "  ON t.team_id = b.team_id AND t.warehouse_id = b.warehouse_id "
                        + " AND t.sku_id = b.sku_id "
                        + "WHERE b.on_hand_qty <> COALESCE(t.d, 0)", Long.class);
        System.out.println("[R180-EVIDENCE] 全表对账差异行数=" + diff);
        assertEquals(0L, diff, "★ 全表对账差异必须为 0");
    }

    private static String qtyText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd.setScale(3, RoundingMode.HALF_UP).toPlainString();
        }
        return value.toString();
    }
}
