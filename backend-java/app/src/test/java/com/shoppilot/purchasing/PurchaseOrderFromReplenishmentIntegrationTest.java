package com.shoppilot.purchasing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.error.ErrorCodes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * M6-T02-B 由已审批建议生成采购单 —— 端到端集成测试（真实 MySQL + 真实安全链 + 真实事务）。
 *
 * <p>覆盖 E1–E13：正常转采购（⓪–⑩ 全链）、重复转采购 409、状态守卫 422、冲突未确认 409、
 * 确认后放行、确认集过期、幂等三态、权限矩阵、跨团队/归属、参数校验、
 * <b>不写库存（INV-03）</b>、锚点锁双路径、<b>降级锁 SQL 索引可达性</b>。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PurchaseOrderFromReplenishmentIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM7 = 7L;
    private static final long TEAM8 = 8L;
    private static final long APPROVER_ID = 1002L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;

    private static final long PRODUCT7 = 9001L;
    private static final long PRODUCT8 = 9101L;
    /** 有余额行的 SKU（⇒ ② 走 lockBalanceAnchors）。 */
    private static final long SKU_WITH_BALANCE = 9002L;
    /** 无余额行的 SKU（⇒ ②b 走 lockApprovedAnchors 降级锁）。 */
    private static final long SKU_NO_BALANCE = 9003L;
    private static final long SKU8 = 9102L;
    private static final long WH7 = 9004L;
    private static final long WH8 = 9104L;
    private static final long SUPPLIER7 = 8001L;
    private static final long SUPPLIER8 = 8101L;

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
        // 子表先删（无 CASCADE）
        jdbc.execute("DELETE FROM purchase_order_items");
        jdbc.execute("DELETE FROM purchase_orders");
        jdbc.execute("DELETE FROM goods_receipt_items");
        jdbc.execute("DELETE FROM goods_receipts");
        jdbc.execute("DELETE FROM inventory_balances");
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
                + "(?, 'T-PO-7', '采购七团', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-PO-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'approver01', 'approver01@example.com', ?, '小审', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE')",
                OPERATOR_ID, PASSWORD_HASH, APPROVER_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'APPROVER', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM7, OPERATOR_ID, TEAM7, APPROVER_ID, TEAM7, VIEWER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-P7', '七团商品'), (?, ?, 'P-P8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-P7', '七团仓'), (?, ?, 'WH-P8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-P7', '七团供应商'), (?, ?, 'SUP-P8', '八团供应商')",
                SUPPLIER7, TEAM7, SUPPLIER8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-P7', '七团SKU'), (?, ?, ?, 'SKU-P7B', '七团SKU-B'), "
                + "(?, ?, ?, 'SKU-P8', '八团SKU')",
                SKU_WITH_BALANCE, TEAM7, PRODUCT7, SKU_NO_BALANCE, TEAM7, PRODUCT7,
                SKU8, TEAM8, PRODUCT8);
        // 余额行：仅 SKU_WITH_BALANCE 有 ⇒ SKU_NO_BALANCE 走 ②b 降级锁
        // ★ available_qty 是 STORED 生成列（= on_hand_qty - committed_qty），不可写、不写进列清单；
        //   真实列是 on_hand_qty / in_transit_qty / committed_qty（无 reserved_qty 列）
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty, version) VALUES "
                + "(?, ?, ?, 100.000, 0.000, 0.000, 0)", TEAM7, WH7, SKU_WITH_BALANCE);
    }

    // =====================================================================
    // E1：正常转采购（⓪–⑩ 全链）
    // =====================================================================

    @Test
    @DisplayName("E1 正常转采购：201 + DRAFT + total 正确 + 建议 CONVERTED + 审计 CREATE×1/CONVERT×2")
    void normalConversion() throws Exception {
        long a = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "80.000", SUPPLIER7, "12.5000");
        long b = createApprovedItem(TEAM7, SKU_NO_BALANCE, "20.000", SUPPLIER7, "5.0000");
        String token = login("approver01");

        String body = "{\"replenishment_item_ids\":[" + a + "," + b + "],"
                + "\"warehouse_id\":" + WH7 + "}";
        ResponseEntity<String> res = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment", body, token, "idem-e1");
        System.out.println("[M6T02-EVIDENCE] E1 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CREATED, res.getStatusCode(), res.getBody());

        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("DRAFT", data.get("status").asText());
        assertEquals("REPLENISHMENT", data.get("source").asText());
        assertEquals(SUPPLIER7, data.get("supplier_id").asLong());
        assertEquals(WH7, data.get("warehouse_id").asLong());
        assertEquals("100.000", data.get("total_qty").asText(), "total_qty = Σ ordered_qty");
        // 80×12.5 + 20×5 = 1000 + 100 = 1100
        assertEquals("1100.00", data.get("total_amount").asText(), "total_amount = Σ amount");
        assertTrue(data.get("po_no").asText().matches("PO-\\d{8}-000001"),
                "po_no 格式 PO-yyyyMMdd-000001，实际：" + data.get("po_no").asText());
        assertEquals(2, data.get("items").size());
        long poId = data.get("purchase_order_id").asLong();

        JsonNode items = data.get("items");
        assertEquals(a, items.get(0).get("replenishment_item_id").asLong());
        assertEquals("80.000", items.get(0).get("ordered_qty").asText());
        assertEquals("12.5000", items.get(0).get("unit_price").asText());
        assertEquals("1000.00", items.get(0).get("amount").asText(), "amount = qty × unit_price");
        assertEquals(1, items.get(0).get("line_no").asInt());
        assertEquals(b, items.get(1).get("replenishment_item_id").asLong());
        assertEquals(2, items.get(1).get("line_no").asInt());

        Map<String, Object> po = jdbc.queryForMap(
                "SELECT id, po_no, status, source, supplier_id, warehouse_id, total_qty, total_amount, "
                        + "received_qty, idempotency_key FROM purchase_orders WHERE id = ?", poId);
        System.out.println("[M6T02-EVIDENCE] E1 po row: " + po);
        assertEquals("DRAFT", po.get("status"));
        assertEquals("REPLENISHMENT", po.get("source"));
        assertEquals(0, new BigDecimal("0.000").compareTo((BigDecimal) po.get("received_qty")),
                "received_qty 初始 0");
        assertEquals("idem-e1", po.get("idempotency_key"), "★ 幂等键落库（可空列，本路径必填）");

        System.out.println("[M6T02-EVIDENCE] E1 建议侧: " + itemRow(a) + " | " + itemRow(b));
        for (long itemId : List.of(a, b)) {
            Map<String, Object> item = jdbc.queryForMap(
                    "SELECT id, status, converted_po_item_ids, version FROM replenishment_items WHERE id = ?",
                    itemId);
            assertEquals("CONVERTED", item.get("status"), "建议应变为 CONVERTED");
            String ids = (String) item.get("converted_po_item_ids");
            assertNotNull(ids, "converted_po_item_ids 应回填");
            assertTrue(ids.contains(String.valueOf(poItemIdOf(itemId))),
                    "converted_po_item_ids 应追加本行 poi id，实际：" + ids);
        }
        assertEquals(1L, auditCount(poId, "PURCHASE_ORDER_CREATE"), "⑩ 建单留痕 ×1");
        assertEquals(1L, auditCount(a, "REPLENISHMENT_CONVERT"), "⑧ 每条建议 CONVERT 审计 ×1");
        assertEquals(1L, auditCount(b, "REPLENISHMENT_CONVERT"));
    }

    // =====================================================================
    // E2：重复转采购 ⇒ PO-9001
    // =====================================================================

    @Test
    @DisplayName("E2 重复转采购：已 CONVERTED ⇒ 409 PO-9001（不是 RPL-8001）")
    void alreadyConvertedReturns9001() throws Exception {
        long a = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
        String token = login("approver01");
        ResponseEntity<String> first = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + a + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e2-a");
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());

        ResponseEntity<String> second = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + a + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e2-b");
        System.out.println("[M6T02-EVIDENCE] E2 HTTP: " + second.getStatusCode().value() + " " + second.getBody());
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals(ErrorCodes.PO_9001, codeOf(second));
    }

    // =====================================================================
    // E3：状态守卫 ⇒ RPL-8001
    // =====================================================================

    @Test
    @DisplayName("E3 状态守卫：SUGGESTED/PENDING_REVIEW/REJECTED/CANCELLED ⇒ 422 RPL-8001")
    void statusGuard() throws Exception {
        String token = login("approver01");
        for (String status : List.of("SUGGESTED", "PENDING_REVIEW", "REJECTED", "CANCELLED")) {
            long item = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
            jdbc.update("UPDATE replenishment_items SET status = ? WHERE id = ?", status, item);
            ResponseEntity<String> res = postWithKey(
                    "/api/v1/purchasing/orders/from-replenishment",
                    "{\"replenishment_item_ids\":[" + item + "],\"warehouse_id\":" + WH7 + "}",
                    token, "idem-e3-" + status);
            System.out.println("[M6T02-EVIDENCE] E3 " + status + ": " + res.getStatusCode().value()
                    + " " + res.getBody());
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, res.getStatusCode());
            assertEquals(ErrorCodes.RPL_8001, codeOf(res));
        }
    }

    // =====================================================================
    // E4：冲突未确认 ⇒ RPL-8012
    // =====================================================================

    @Test
    @DisplayName("E4 冲突未确认：同 team×wh×sku 有其它 APPROVED 未转建议 ⇒ 409 RPL-8012，不建单")
    void conflictNotAcknowledged() throws Exception {
        long a = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
        long other = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "50.000", SUPPLIER7, "9.0000");
        String token = login("approver01");

        ResponseEntity<String> res = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + a + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e4");
        System.out.println("[M6T02-EVIDENCE] E4 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals(ErrorCodes.RPL_8012, codeOf(res));
        // 冲突清单落点：details（R153：data 通道本批不实现）
        JsonNode details = json.readTree(res.getBody()).get("details");
        assertNotNull(details, "冲突清单应落在 details");
        boolean found = false;
        for (JsonNode d : details) {
            if ("conflict_items".equals(d.get("field").asText())) {
                // 清单承载在 rejectedValue（每冲突项一条 details），不是 message 文本
                JsonNode payload = d.get("rejectedValue");
                assertNotNull(payload, "conflict_items 应带 rejectedValue 结构");
                assertTrue(payload.has("replenishment_item_id") && payload.has("sku_id")
                                && payload.has("qty") && payload.has("risk_level")
                                && payload.has("created_at"),
                        "冲突项应含 replenishment_item_id/sku_id/qty/risk_level/created_at");
                if (payload.get("replenishment_item_id").asLong() == other) {
                    found = true;
                }
            }
        }
        assertTrue(found, "details.conflict_items 应含 other 建议 id=" + other);
        // 不产生任何采购单、建议状态不变
        assertEquals(0L, countOf("purchase_orders"), "不得产生采购单");
        assertEquals("APPROVED", statusOf(a));
        assertEquals("APPROVED", statusOf(other));
    }

    // =====================================================================
    // E5：确认后放行
    // =====================================================================

    @Test
    @DisplayName("E5 确认后放行：conflict_acknowledged=true + 确认集（乱序）⇒ 201 + CONFLICT_ACK 审计")
    void conflictAcknowledgedPasses() throws Exception {
        long a = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
        long other = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "50.000", SUPPLIER7, "9.0000");
        String token = login("approver01");
        String body = "{\"replenishment_item_ids\":[" + a + "],\"warehouse_id\":" + WH7 + ","
                + "\"conflict_acknowledged\":true,\"acknowledged_item_ids\":[" + other + "]}";

        ResponseEntity<String> res = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment", body, token, "idem-e5");
        System.out.println("[M6T02-EVIDENCE] E5 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CREATED, res.getStatusCode(), res.getBody());
        long poId = json.readTree(res.getBody()).get("data").get("purchase_order_id").asLong();
        assertEquals(1L, auditCount(poId, "PURCHASE_ORDER_CONFLICT_ACK"), "⑨ 冲突确认留痕");
    }

    // =====================================================================
    // E6：确认集过期 ⇒ 再次 RPL-8012
    // =====================================================================

    @Test
    @DisplayName("E6 确认集过期：确认时冲突集变化 ⇒ 再次 409，且不写 CONFLICT_ACK")
    void staleAckSetRejected() throws Exception {
        long a = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
        long otherB = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "50.000", SUPPLIER7, "9.0000");
        long otherC = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "60.000", SUPPLIER7, "8.0000");
        String token = login("approver01");

        // 先把 otherB 单独转走（它已 CONVERTED ⇒ 不再是冲突）；
        // 此刻 otherB 视角的冲突集是 {a, otherC}，确认集必须给全这两个才会被放行
        ResponseEntity<String> pre = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + otherB + "],\"warehouse_id\":" + WH7 + ","
                        + "\"conflict_acknowledged\":true,\"acknowledged_item_ids\":["
                        + a + "," + otherC + "]}",
                token, "idem-e6-pre");
        assertEquals(HttpStatus.CREATED, pre.getStatusCode(), pre.getBody());
        // pre 带确认转单成功 ⇒ 写 1 条 CONFLICT_ACK（这是「确认放行」的合法留痕）
        long ackBefore = countOfAuditByAction("PURCHASE_ORDER_CONFLICT_ACK");
        assertEquals(1L, ackBefore, "pre 应已写 1 条 CONFLICT_ACK");

        // 此时真实冲突集只剩 {otherC}；客户端却确认了 {otherB}（已过期）⇒ 拒绝
        ResponseEntity<String> res = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + a + "],\"warehouse_id\":" + WH7 + ","
                        + "\"conflict_acknowledged\":true,\"acknowledged_item_ids\":[" + otherB + "]}",
                token, "idem-e6");
        System.out.println("[M6T02-EVIDENCE] E6 HTTP: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals(ErrorCodes.RPL_8012, codeOf(res));
        assertEquals(1L, countOf("purchase_orders"), "不应为 a 建单");
        // ★ 裁定 P-6 本意 = 「本次过期请求未写审计」：res 在④即被拒、未走到⑨ ⇒ 计数不变。
        //   （令文「全表 = 0」与 E6 数据流不符：pre 必先写 1 条，故按「前后不变」断言）
        assertEquals(ackBefore, countOfAuditByAction("PURCHASE_ORDER_CONFLICT_ACK"),
                "确认集过期 ⇒ 本次请求不得新增 CONFLICT_ACK 审计");
    }

    // =====================================================================
    // E7：幂等三态
    // =====================================================================

    @Test
    @DisplayName("E7 幂等：同键同体⇒返回首单；同键异体⇒COM-0009；缺 key⇒COM-0010")
    void idempotency() throws Exception {
        long a = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
        String token = login("approver01");
        String body = "{\"replenishment_item_ids\":[" + a + "],\"warehouse_id\":" + WH7 + "}";

        ResponseEntity<String> first = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment", body, token, "idem-e7");
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        long poId = json.readTree(first.getBody()).get("data").get("purchase_order_id").asLong();
        String poNo = json.readTree(first.getBody()).get("data").get("po_no").asText();

        ResponseEntity<String> replay = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment", body, token, "idem-e7");
        System.out.println("[M6T02-EVIDENCE] E7 重放: " + replay.getStatusCode().value() + " " + replay.getBody());
        // ★ 契约 §11.8「成功响应 201」；幂等重放返回与首次相同的响应（含状态码）
        assertEquals(HttpStatus.CREATED, replay.getStatusCode(), "★ 同键同体重放返回首单（幂等）");
        JsonNode replayData = json.readTree(replay.getBody()).get("data");
        assertEquals(poId, replayData.get("purchase_order_id").asLong(), "必须是首次那张采购单");
        assertEquals(poNo, replayData.get("po_no").asText());
        assertEquals(1L, countOf("purchase_orders"), "不得重复建单");

        long other = createApprovedItem(TEAM7, SKU_NO_BALANCE, "40.000", SUPPLIER7, "7.0000");
        ResponseEntity<String> diffBody = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + other + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e7");
        System.out.println("[M6T02-EVIDENCE] E7 异体: " + diffBody.getStatusCode().value() + " " + diffBody.getBody());
        assertEquals(HttpStatus.CONFLICT, diffBody.getStatusCode());
        assertEquals(ErrorCodes.COM_0009, codeOf(diffBody));

        ResponseEntity<String> noKey = post(
                "/api/v1/purchasing/orders/from-replenishment", body, token);
        System.out.println("[M6T02-EVIDENCE] E7 缺 key: " + noKey.getStatusCode().value() + " " + noKey.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, noKey.getStatusCode());
        assertEquals(ErrorCodes.COM_0010, codeOf(noKey));
    }

    // =====================================================================
    // E8：权限矩阵
    // =====================================================================

    @Test
    @DisplayName("E8 权限：OPERATOR⇒403；VIEWER⇒403；APPROVER⇒201")
    void permissionMatrix() throws Exception {
        // OPERATOR/VIEWER 复用同一条（被拒 ⇒ 不转单、不留 APPROVED 新痕迹）；
        // APPROVER 用 SKU_NO_BALANCE 上唯一一条 ⇒ 无冲突，验证“角色本身”而非冲突
        long deniedTarget = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "10.000", SUPPLIER7, "3.0000");
        long forApprover = createApprovedItem(TEAM7, SKU_NO_BALANCE, "10.000", SUPPLIER7, "3.0000");

        ResponseEntity<String> operatorRes = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + deniedTarget + "],\"warehouse_id\":" + WH7 + "}",
                login("operator01"), "idem-e8-op");
        System.out.println("[M6T02-EVIDENCE] E8 OPERATOR: " + operatorRes.getStatusCode().value()
                + " " + operatorRes.getBody());
        assertEquals(HttpStatus.FORBIDDEN, operatorRes.getStatusCode());
        assertEquals(ErrorCodes.TEAM_2003, codeOf(operatorRes));

        ResponseEntity<String> viewerRes = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + deniedTarget + "],\"warehouse_id\":" + WH7 + "}",
                login("viewer01"), "idem-e8-viewer");
        assertEquals(HttpStatus.FORBIDDEN, viewerRes.getStatusCode());
        assertEquals(ErrorCodes.TEAM_2003, codeOf(viewerRes));

        ResponseEntity<String> approverRes = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + forApprover + "],\"warehouse_id\":" + WH7 + "}",
                login("approver01"), "idem-e8-appr");
        System.out.println("[M6T02-EVIDENCE] E8 APPROVER: " + approverRes.getStatusCode().value()
                + " " + approverRes.getBody());
        assertEquals(HttpStatus.CREATED, approverRes.getStatusCode(), approverRes.getBody());
    }

    // =====================================================================
    // E9：跨团队 / 归属（★ 修订：仓库码用 CAT-3003）
    // =====================================================================

    @Test
    @DisplayName("E9 跨团队/归属：他团队建议⇒RPL-8004；不存在仓库⇒CAT-3003；跨团队仓库⇒TEAM-2001；供应商跨团队⇒PO-9007")
    void crossTeamRejected() throws Exception {
        String token = login("approver01");
        long mine = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
        long foreign = createApprovedItem(TEAM8, SKU8, "30.000", SUPPLIER8, "10.0000");
        // 本团队建议但供应商是他团队的（JDBC 直插，绕过租户线）
        long foreignSupplierItem = createApprovedItem(TEAM7, SKU_NO_BALANCE, "30.000", SUPPLIER7, "10.0000");
        jdbc.update("UPDATE replenishment_items SET supplier_id = ? WHERE id = ?",
                SUPPLIER8, foreignSupplierItem);

        ResponseEntity<String> foreignItem = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + foreign + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e9-a");
        System.out.println("[M6T02-EVIDENCE] E9 他团队建议: " + foreignItem.getStatusCode().value()
                + " " + foreignItem.getBody());
        assertEquals(HttpStatus.NOT_FOUND, foreignItem.getStatusCode());
        assertEquals(ErrorCodes.RPL_8004, codeOf(foreignItem));

        // ★ 口令径修正（申报）：WarehouseQueryService#requireInTeam 的既有实现是
        //   CatalogGuards.requireInTeam ——「存在但跨团队 ⇒ TEAM-2001(403)（隔离优先，
        //   与 M2/TeamScopeValidator 系统级口径一致）/ 不存在 ⇒ CAT-3003(404)」，
        //   并非令文 A3 所的「一律 404」。不改变既有实现（改它会破坏 M2 口径），按实测断言。
        ResponseEntity<String> foreignWh = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + mine + "],\"warehouse_id\":" + WH8 + "}",
                token, "idem-e9-b");
        System.out.println("[M6T02-EVIDENCE] E9 他团队仓库: " + foreignWh.getStatusCode().value()
                + " " + foreignWh.getBody());
        assertEquals(HttpStatus.FORBIDDEN, foreignWh.getStatusCode());
        assertEquals(ErrorCodes.TEAM_2001, codeOf(foreignWh));

        ResponseEntity<String> missingWh = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + mine + "],\"warehouse_id\":999999}",
                token, "idem-e9-c");
        System.out.println("[M6T02-EVIDENCE] E9 不存在仓库: " + missingWh.getStatusCode().value()
                + " " + missingWh.getBody());
        assertEquals(HttpStatus.NOT_FOUND, missingWh.getStatusCode());
        assertEquals(ErrorCodes.CAT_3003, codeOf(missingWh));

        ResponseEntity<String> foreignSupplier = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + foreignSupplierItem + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e9-d");
        System.out.println("[M6T02-EVIDENCE] E9 他团队供应商: " + foreignSupplier.getStatusCode().value()
                + " " + foreignSupplier.getBody());
        assertEquals(HttpStatus.FORBIDDEN, foreignSupplier.getStatusCode());
        assertEquals(ErrorCodes.PO_9007, codeOf(foreignSupplier));
    }

    // =====================================================================
    // E10：参数校验
    // =====================================================================

    @Test
    @DisplayName("E10 参数：空 ids⇒PO-9006；remark 超 500⇒COM-0001；建议 qty=0⇒RPL-8006")
    void paramValidation() throws Exception {
        String token = login("approver01");
        long ok = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
        long zero = createApprovedItem(TEAM7, SKU_NO_BALANCE, "0.000", SUPPLIER7, "10.0000");

        ResponseEntity<String> emptyIds = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e10-a");
        System.out.println("[M6T02-EVIDENCE] E10 空 ids: " + emptyIds.getStatusCode().value()
                + " " + emptyIds.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, emptyIds.getStatusCode());
        assertEquals(ErrorCodes.PO_9006, codeOf(emptyIds));

        ResponseEntity<String> longRemark = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + ok + "],\"warehouse_id\":" + WH7 + ","
                        + "\"remark\":\"" + "x".repeat(501) + "\"}",
                token, "idem-e10-b");
        assertEquals(HttpStatus.BAD_REQUEST, longRemark.getStatusCode());
        assertEquals(ErrorCodes.COM_0001, codeOf(longRemark));

        ResponseEntity<String> zeroQty = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + zero + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e10-c");
        System.out.println("[M6T02-EVIDENCE] E10 qty=0: " + zeroQty.getStatusCode().value()
                + " " + zeroQty.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, zeroQty.getStatusCode());
        assertEquals(ErrorCodes.RPL_8006, codeOf(zeroQty));
    }

    // =====================================================================
    // E11：不写库存（INV-03）
    // =====================================================================

    @Test
    @DisplayName("E11 不写库存：转采购前后 inventory_balances.on_hand_qty 与 transactions 计数均不变")
    void doesNotTouchInventory() throws Exception {
        long a = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
        long b = createApprovedItem(TEAM7, SKU_NO_BALANCE, "20.000", SUPPLIER7, "10.0000");
        String token = login("approver01");
        BigDecimal onHandBefore = jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE team_id = ? AND warehouse_id = ? "
                        + "AND sku_id = ?", BigDecimal.class, TEAM7, WH7, SKU_WITH_BALANCE);
        Long txnBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transactions", Long.class);

        ResponseEntity<String> res = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + a + "," + b + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e11");
        assertEquals(HttpStatus.CREATED, res.getStatusCode(), res.getBody());

        BigDecimal onHandAfter = jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE team_id = ? AND warehouse_id = ? "
                        + "AND sku_id = ?", BigDecimal.class, TEAM7, WH7, SKU_WITH_BALANCE);
        Long txnAfter = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions", Long.class);
        System.out.println("[M6T02-EVIDENCE] E11 on_hand: " + onHandBefore + " → " + onHandAfter
                + " | inventory_transactions: " + txnBefore + " → " + txnAfter);
        assertEquals(0, onHandBefore.compareTo(onHandAfter), "on_hand_qty 不得变化");
        assertEquals(txnBefore, txnAfter, "不得写入库存流水");
    }

    // =====================================================================
    // E12：锚点锁双路径
    // =====================================================================

    @Test
    @DisplayName("E12【弱验证】锚点锁双路径：两条锁路径无副作用，本用例无法区分分支走向")
    void bothLockPaths() throws Exception {
        String token = login("approver01");
        // ★ 弱验证（§5.67.4）：两条锁路径均无副作用（不写数据、不写审计），本用例仅断言
        //   201 + CONVERTED，与快乐路径同构——删掉降级分支测试仍绿 ⇒ 无法区分走了哪条分支。
        // 路径 1：SKU_WITH_BALANCE 有余额行 ⇒ ② 即够，不降级
        long withBalance = createApprovedItem(TEAM7, SKU_WITH_BALANCE, "30.000", SUPPLIER7, "10.0000");
        ResponseEntity<String> res1 = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + withBalance + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e12-a");
        System.out.println("[M6T02-EVIDENCE] E12 有余额行: " + res1.getStatusCode().value());
        assertEquals(HttpStatus.CREATED, res1.getStatusCode(), res1.getBody());

        // 路径 2：SKU_NO_BALANCE 无余额行 ⇒ ②b 降级锁必须生效
        long noBalance = createApprovedItem(TEAM7, SKU_NO_BALANCE, "30.000", SUPPLIER7, "10.0000");
        ResponseEntity<String> res2 = postWithKey(
                "/api/v1/purchasing/orders/from-replenishment",
                "{\"replenishment_item_ids\":[" + noBalance + "],\"warehouse_id\":" + WH7 + "}",
                token, "idem-e12-b");
        System.out.println("[M6T02-EVIDENCE] E12 无余额行: " + res2.getStatusCode().value());
        assertEquals(HttpStatus.CREATED, res2.getStatusCode(), res2.getBody());
        assertEquals("CONVERTED", statusOf(noBalance));
    }

    // =====================================================================
    // E13：降级锁 SQL 索引可达性
    // =====================================================================

    @Test
    @DisplayName("E13【未覆盖/N-A】降级锁 EXPLAIN 观测（不证明优化器选中 idx_repl_team_sku_time）")
    void degradeLockUsesIndex() {
        long runId = insertRun(TEAM7, "RUN-EXPLAIN");
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, "
                        + "qty_suggest, safety_stock_qty) VALUES (?, ?, ?, ?, ?, 8.000, 'HIGH', 'SKU', "
                        + "6.250, 104.500, 110.000, 25.000)",
                TEAM7, runId, SKU_WITH_BALANCE, PRODUCT7, WH7);
        long srcId = jdbc.queryForObject(
                "SELECT id FROM analysis_items WHERE run_id = ?", Long.class, runId);
        jdbc.update("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                        + "product_id, warehouse_id, status, suggested_qty, qty, risk_level) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'APPROVED', 10.000, 10.000, 'HIGH')",
                TEAM7, srcId, runId, SKU_WITH_BALANCE, PRODUCT7, WH7);
        jdbc.update("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                        + "product_id, warehouse_id, status, suggested_qty, qty, risk_level) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'APPROVED', 20.000, 20.000, 'LOW')",
                TEAM7, srcId, runId, SKU_NO_BALANCE, PRODUCT7, WH7);

        Map<String, Object> explain = jdbc.queryForMap(
                "EXPLAIN SELECT id FROM replenishment_items "
                        + "WHERE team_id = ? AND warehouse_id = ? AND sku_id IN (?, ?) "
                        + "AND status = 'APPROVED' ORDER BY sku_id ASC",
                TEAM7, WH7, SKU_WITH_BALANCE, SKU_NO_BALANCE);
        System.out.println("[M6T02-EVIDENCE] E13 EXPLAIN: " + explain);
        // ★ 【未覆盖/N-A】（§5.67.4）：实测 key=idx_repl_team_status_time + Extra=Using where;
        //   Using filesort，令文要求的「key=idx_repl_team_sku_time 且无 filesort」未达成。
        //   断言仅为「非全表扫描 + 索引在 possible_keys」——后者是静态元数据校验、近乎恒真、
        //   无法证伪；且 EXPLAIN 省略了 FOR UPDATE，与生产加锁语句不等价 ⇒ 不构成 R2 修复生效的证据。
        //   根因与正解见 R165：需 V1.0.12 新增 idx_repl_team_wh_sku (team_id, warehouse_id, sku_id)，
        //   本批 G3 禁改迁移，不做；不得把 possible_keys 硬改成 key 断言。
        assertTrue(!"ALL".equals(explain.get("type")),
                "不得全表扫描（R2 修复的最低目标），实际 type=" + explain.get("type"));
        assertTrue(String.valueOf(explain.get("possible_keys")).contains("idx_repl_team_sku_time"),
                "idx_repl_team_sku_time 必须可达（在 possible_keys 中），实际："
                        + explain.get("possible_keys"));
    }

    // =====================================================================
    // E14：并发幂等（C1 必修）
    // =====================================================================

    @Test
    @DisplayName("E14 并发幂等：同键同体并发 ⇒ 组合 ∈ {(201,201),(201,409 COM-0009)}，绝不 500/COM-0002，purchase_orders 恰 1 条")
    void concurrentIdempotencyDoesNotReturn500() throws Exception {
        long a = createApprovedItem(TEAM7, SKU_NO_BALANCE, "30.000", SUPPLIER7, "10.0000");
        String token = login("approver01");
        String body = "{\"replenishment_item_ids\":[" + a + "],\"warehouse_id\":" + WH7 + "}";

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return postWithKey("/api/v1/purchasing/orders/from-replenishment",
                        body, token, "idem-e14");
            }));
        }
        gate.countDown();
        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> f : futures) {
            responses.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        List<Integer> codes = new ArrayList<>();
        for (ResponseEntity<String> r : responses) {
            codes.add(r.getStatusCode().value());
        }
        System.out.println("[M6T02-EVIDENCE] E14 响应组合: " + codes
                + " | bodies=" + responses.stream().map(ResponseEntity::getBody).toList());
        // ★ 合法组合（C1 后）：(201,201)=B 在⑤步命中首单；(201,409)=B 撞 uk_po_team_idem 后
        //   RC 重查命中所落 COM-0009。绝不出现 500/COM-0002。
        for (ResponseEntity<String> r : responses) {
            int status = r.getStatusCode().value();
            assertTrue(status == 201 || status == 409,
                    "只允许 201 或 409，实际 " + status + " body=" + r.getBody());
            if (status == 409) {
                assertEquals(ErrorCodes.COM_0009, codeOf(r), "并发撞键必须落 COM-0009(409)，不是 COM-0002");
            }
            assertFalse(r.getBody().contains("COM-0002"), "绝不出现 COM-0002(500)");
        }
        assertEquals(1L, countOf("purchase_orders"), "并发生成恰好 1 条采购单");
        assertEquals(1L, countOf("purchase_order_items"), "明细行数与首次一致（1 条）");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private long insertRun(long teamId, String runNoPrefix) {
        String runNo = runNoPrefix + "-" + (++runSeq);
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, algorithm_version) "
                + "VALUES (?, ?, 'SUCCEEDED', 'ALL_ACTIVE', 'avg-ma-3sigma-v1')", teamId, runNo);
        return jdbc.queryForObject("SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);
    }

    /** 建一条 APPROVED 建议（含分析链：run → item → replenishment_item）。 */
    private long createApprovedItem(long teamId, long skuId, String qty, long supplierId,
                                    String purchasePrice) {
        boolean team7 = teamId == TEAM7;
        long product = team7 ? PRODUCT7 : PRODUCT8;
        long wh = team7 ? WH7 : WH8;
        long runId = insertRun(teamId, "RUN-PO-" + teamId);
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, "
                        + "qty_suggest, safety_stock_qty) VALUES (?, ?, ?, ?, ?, 8.000, 'HIGH', 'SKU', "
                        + "6.250, 104.500, ?, 25.000)",
                teamId, runId, skuId, product, wh, new BigDecimal(qty));
        long srcId = jdbc.queryForObject(
                "SELECT id FROM analysis_items WHERE run_id = ?", Long.class, runId);
        jdbc.update("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                        + "product_id, warehouse_id, status, suggested_qty, qty, risk_level, supplier_id, "
                        + "purchase_price, version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'APPROVED', ?, ?, 'HIGH', ?, ?, 0, ?, ?)",
                teamId, srcId, runId, skuId, product, wh, new BigDecimal(qty), new BigDecimal(qty),
                supplierId, new BigDecimal(purchasePrice), 1001L, 1001L);
        return jdbc.queryForObject(
                "SELECT id FROM replenishment_items WHERE source_analysis_item_id = ?", Long.class, srcId);
    }

    private long poItemIdOf(long itemId) {
        return jdbc.queryForObject(
                "SELECT id FROM purchase_order_items WHERE replenishment_item_id = ?", Long.class, itemId);
    }

    private String statusOf(long itemId) {
        return jdbc.queryForObject(
                "SELECT status FROM replenishment_items WHERE id = ?", String.class, itemId);
    }

    private String itemRow(long itemId) {
        return jdbc.queryForMap(
                "SELECT id, status, converted_po_item_ids, version FROM replenishment_items WHERE id = ?",
                itemId).toString();
    }

    private long countOf(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    /** 按 action 全表计数（E6 断言「确认集过期 ⇒ 不得写某类审计」用）。 */
    private long countOfAuditByAction(String action) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ?", Long.class, action);
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

    private ResponseEntity<String> postWithKey(String path, String body, String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
