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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6-T03 采购单状态机 + 草稿编辑 + 查询 —— 端到端集成测试（真实 MySQL + 真实安全链 + 真实事务）。
 *
 * <p>覆盖 E1–E13：confirm/order/cancel 三动作（含判因、审计、角色）、草稿编辑（含重算、
 * 归属校验、乐观锁）、详情/列表查询（{@code list} 字段名、团队隔离、排序）、
 * <b>不写库存（INV-03）</b>、并发 confirm（CAS 守卫）。
 *
 * <p>种子采购单由 JDBC 直插（焦点在状态机，不在生成链路——生成端点由
 * {@code PurchaseOrderFromReplenishmentIntegrationTest} 覆盖）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PurchaseOrderFlowIntegrationTest {

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

    private static final String BASE = "/api/v1/purchasing/orders";

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
    private int itemSeq;

    @BeforeEach
    void resetAndSeed() {
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
        poSeq = 0;
        itemSeq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-POF-7', '采购流七团', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-POF-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'approver01', 'approver01@example.com', ?, '小审', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE')",
                OPERATOR_ID, PASSWORD_HASH, APPROVER_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'APPROVER', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM7, OPERATOR_ID, TEAM7, APPROVER_ID, TEAM7, VIEWER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-F7', '七团商品'), (?, ?, 'P-F8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-F7', '七团仓'), (?, ?, 'WH-F8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-F7', '七团供应商'), (?, ?, 'SUP-F8', '八团供应商')",
                SUPPLIER7, TEAM7, SUPPLIER8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-F7', '七团SKU'), (?, ?, ?, 'SKU-F7B', '七团SKU-B'), "
                + "(?, ?, ?, 'SKU-F8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU7B, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);
        // 余额行：同 M6-T02 口径（左边有、右边无 ⇒ 供 INV-03 断言与潜在降级路径）
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty, version) VALUES "
                + "(?, ?, ?, 100.000, 0.000, 0.000, 0)", TEAM7, WH7, SKU7);
    }

    // =====================================================================
    // E1：confirm 正常
    // =====================================================================

    @Test
    @DisplayName("E1 confirm 正常：DRAFT→PENDING，confirmed_by/at 有值，version+1，审计 ×1")
    void confirmSucceeds() throws Exception {
        long poId = createDraftPo("DRAFT", "10.000", "100.00", 0);
        String token = login("approver01");

        ResponseEntity<String> res = postWithVersion(BASE + "/" + poId + "/confirm",
                "{\"expected_version\":0}", token);
        System.out.println("[M6T03-EVIDENCE] E1 confirm: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals(poId, data.get("purchase_order_id").asLong());
        assertEquals("PENDING", data.get("status").asText());
        assertEquals(APPROVER_ID, data.get("confirmed_by").asLong());
        assertNotNull(data.get("confirmed_at"), "confirmed_at 必须写入");
        assertEquals(1, data.get("version").asInt(), "version 应 +1");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, confirmed_by, confirmed_at, version FROM purchase_orders WHERE id = ?", poId);
        System.out.println("[M6T03-EVIDENCE] E1 DB: " + row);
        assertEquals("PENDING", row.get("status"));
        assertEquals(APPROVER_ID, ((Number) row.get("confirmed_by")).longValue());
        assertEquals(1L, auditCount(poId, "PURCHASE_ORDER_CONFIRM"), "① 审计 ×1");
    }

    // =====================================================================
    // E2：confirm 重复（已 PENDING）
    // =====================================================================

    @Test
    @DisplayName("E2 confirm 重复：已 PENDING ⇒ 409 PO-9003")
    void confirmDuplicateReturns9003() throws Exception {
        long poId = createDraftPo("PENDING", "10.000", "100.00", 1);
        String token = login("approver01");

        ResponseEntity<String> res = postWithVersion(BASE + "/" + poId + "/confirm",
                "{\"expected_version\":1}", token);
        System.out.println("[M6T03-EVIDENCE] E2 confirm: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals(ErrorCodes.PO_9003, codeOf(res));
    }

    // =====================================================================
    // E3：confirm 角色不足
    // =====================================================================

    @Test
    @DisplayName("E3 confirm 角色不足（OPERATOR）⇒ 403 TEAM-2003")
    void confirmForbiddenForOperator() throws Exception {
        long poId = createDraftPo("DRAFT", "10.000", "100.00", 0);

        ResponseEntity<String> res = postWithVersion(BASE + "/" + poId + "/confirm",
                "{\"expected_version\":0}", login("operator01"));
        System.out.println("[M6T03-EVIDENCE] E3 confirm: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertEquals(ErrorCodes.TEAM_2003, codeOf(res));
    }

    // =====================================================================
    // E4：order 正常
    // =====================================================================

    @Test
    @DisplayName("E4 order 正常：PENDING→ORDERED，ordered_at 有值，审计 ×1")
    void orderSucceeds() throws Exception {
        long poId = createDraftPo("PENDING", "10.000", "100.00", 1);
        String token = login("approver01");

        ResponseEntity<String> res = postWithVersion(BASE + "/" + poId + "/order",
                "{\"expected_version\":1}", token);
        System.out.println("[M6T03-EVIDENCE] E4 order: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("ORDERED", data.get("status").asText());
        assertNotNull(data.get("ordered_at"), "ordered_at 必须写入");
        assertEquals(2, data.get("version").asInt());
        assertEquals(1L, auditCount(poId, "PURCHASE_ORDER_ORDERED"), "① 审计 ×1");
        // ★ INV-03：order 不写库存
        assertEquals(0L, countOf("inventory_transactions"), "order 不得产生库存流水");
    }

    // =====================================================================
    // E5：order 状态非法（DRAFT 直接 order）
    // =====================================================================

    @Test
    @DisplayName("E5 order 状态非法（DRAFT）⇒ 409 PO-9003")
    void orderFromDraftRejected() throws Exception {
        long poId = createDraftPo("DRAFT", "10.000", "100.00", 0);
        String token = login("approver01");

        ResponseEntity<String> res = postWithVersion(BASE + "/" + poId + "/order",
                "{\"expected_version\":0}", token);
        System.out.println("[M6T03-EVIDENCE] E5 order: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals(ErrorCodes.PO_9003, codeOf(res));
    }

    // =====================================================================
    // E6：cancel 正常（源态 ORDERED）+ 建议不变回 APPROVED
    // =====================================================================

    @Test
    @DisplayName("E6 cancel 正常：ORDERED→CANCELLED，时间/原因落库，审计 ×1；建议仍 CONVERTED")
    void cancelSucceedsAndKeepsSuggestionConverted() throws Exception {
        long poId = createDraftPo("ORDERED", "10.000", "100.00", 1);
        long suggestionId = createConvertedSuggestion(poId);
        String token = login("approver01");

        ResponseEntity<String> res = postWithBody(BASE + "/" + poId + "/cancel",
                "{\"reason\":\"供应商断供\"}", token);
        System.out.println("[M6T03-EVIDENCE] E6 cancel: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("CANCELLED", data.get("status").asText());
        assertNotNull(data.get("cancelled_at"));
        assertEquals("供应商断供", data.get("cancel_reason").asText());
        assertEquals(2, data.get("version").asInt());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, cancelled_at, cancel_reason, closed_at, version "
                        + "FROM purchase_orders WHERE id = ?", poId);
        System.out.println("[M6T03-EVIDENCE] E6 DB: " + row);
        assertEquals("CANCELLED", row.get("status"));
        assertNotNull(row.get("closed_at"), "closed_at = 进入终态时间（§5.18），cancel 应写入");
        assertEquals(1L, auditCount(poId, "PURCHASE_ORDER_CANCEL"), "① 审计 ×1");
        // ★ 取消不得把补货建议改回 APPROVED（DATABASE_DESIGN:1442 规则 4）
        assertEquals("CONVERTED", statusOf(suggestionId),
                "取消采购单不得把建议改回 APPROVED");
    }

    // =====================================================================
    // E7：cancel 已 RECEIVED / 角色不足
    // =====================================================================

    @Test
    @DisplayName("E7 cancel 已 RECEIVED ⇒ 409 PO-9009；cancel 角色不足 ⇒ 403 TEAM-2003")
    void cancelGuards() throws Exception {
        long received = createDraftPo("RECEIVED", "10.000", "100.00", 2);
        long forbidden = createDraftPo("ORDERED", "10.000", "100.00", 1);
        String approverToken = login("approver01");

        ResponseEntity<String> res1 = postWithBody(BASE + "/" + received + "/cancel",
                "{\"reason\":\"太晚\"}", approverToken);
        System.out.println("[M6T03-EVIDENCE] E7 RECEIVED: " + res1.getStatusCode().value() + " " + res1.getBody());
        assertEquals(HttpStatus.CONFLICT, res1.getStatusCode());
        assertEquals(ErrorCodes.PO_9009, codeOf(res1));

        ResponseEntity<String> res2 = postWithBody(BASE + "/" + forbidden + "/cancel",
                "{\"reason\":\"角色不足\"}", login("operator01"));
        System.out.println("[M6T03-EVIDENCE] E7 OPERATOR: " + res2.getStatusCode().value() + " " + res2.getBody());
        assertEquals(HttpStatus.FORBIDDEN, res2.getStatusCode());
        assertEquals(ErrorCodes.TEAM_2003, codeOf(res2));
    }

    // =====================================================================
    // E8：PATCH 草稿（改 remark + 明细 ⇒ total 重算）
    // =====================================================================

    @Test
    @DisplayName("E8 PATCH 草稿：改 remark/明细 ⇒ 200，total 重算正确，version+1")
    void patchDraftRecalculatesTotals() throws Exception {
        long poId = createDraftPo("DRAFT", "10.000", "100.00", 0);
        long lineId = firstLineId(poId);
        String token = login("operator01");
        // ★ R172：草稿编辑不写审计。按 biz_id + action 差分断言（裁定二：限定 action，
        //   避免同一 biz_id 上其它动作干扰；登录等系统动作有自己的审计，全表计数会误撞）。
        //   基数钉死为 0，杜绝 0==0 恒真假绿。
        long auditBefore = auditCount(poId, "PURCHASE_ORDER_CONFIRM");
        assertEquals(0L, auditBefore, "基线应为 0 条（本 PO 尚无任何采购域审计）");

        String body = "{\"expected_version\":0,\"remark\":\"已对齐价格\","
                + "\"items\":[{\"purchase_order_item_id\":" + lineId + ","
                + "\"ordered_qty\":\"20.000\",\"unit_price\":\"15.0000\"}]}";
        ResponseEntity<String> res = patch(BASE + "/" + poId, body, token);
        System.out.println("[M6T03-EVIDENCE] E8 PATCH: " + res.getStatusCode().value() + " " + res.getBody());
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals("20.000", data.get("total_qty").asText(), "total_qty = Σ ordered_qty");
        assertEquals("300.00", data.get("total_amount").asText(), "total_amount = Σ amount");
        assertEquals(1, data.get("version").asInt());
        assertEquals("已对齐价格", data.get("remark").asText());
        assertEquals("20.000", data.get("items").get(0).get("ordered_qty").asText());
        assertEquals("15.0000", data.get("items").get(0).get("unit_price").asText());
        // ★ R172：草稿编辑不得写审计（前后计数不变）
        assertEquals(auditBefore, auditCount(poId, "PURCHASE_ORDER_CONFIRM"),
                "草稿编辑不得写审计（R172）");
    }

    // =====================================================================
    // E9：PATCH 各类守卫
    // =====================================================================

    @Test
    @DisplayName("E9 PATCH 守卫：非 DRAFT⇒PO-9003；供应商跨团队⇒PO-9007；仓库跨团队⇒TEAM-2001；单价非法⇒PO-9011")
    void patchGuards() throws Exception {
        String operatorToken = login("operator01");
        long pending = createDraftPo("PENDING", "10.000", "100.00", 1);
        long draft = createDraftPo("DRAFT", "10.000", "100.00", 0);
        long lineId = firstLineId(draft);

        ResponseEntity<String> r1 = patch(BASE + "/" + pending,
                "{\"expected_version\":1,\"remark\":\"x\"}", operatorToken);
        System.out.println("[M6T03-EVIDENCE] E9 非DRAFT: " + r1.getStatusCode().value() + " " + r1.getBody());
        assertEquals(HttpStatus.CONFLICT, r1.getStatusCode());
        assertEquals(ErrorCodes.PO_9003, codeOf(r1));

        ResponseEntity<String> r2 = patch(BASE + "/" + draft,
                "{\"expected_version\":0,\"supplier_id\":" + SUPPLIER8 + "}", operatorToken);
        System.out.println("[M6T03-EVIDENCE] E9 供应商跨团队: " + r2.getStatusCode().value() + " " + r2.getBody());
        assertEquals(HttpStatus.FORBIDDEN, r2.getStatusCode());
        assertEquals(ErrorCodes.PO_9007, codeOf(r2));

        ResponseEntity<String> r3 = patch(BASE + "/" + draft,
                "{\"expected_version\":0,\"warehouse_id\":" + WH8 + "}", operatorToken);
        System.out.println("[M6T03-EVIDENCE] E9 仓库跨团队: " + r3.getStatusCode().value() + " " + r3.getBody());
        assertEquals(HttpStatus.FORBIDDEN, r3.getStatusCode());
        assertEquals(ErrorCodes.TEAM_2001, codeOf(r3));

        ResponseEntity<String> r4 = patch(BASE + "/" + draft,
                "{\"expected_version\":0,\"items\":[{\"purchase_order_item_id\":" + lineId
                        + ",\"ordered_qty\":\"10.000\",\"unit_price\":\"-1.0000\"}]}", operatorToken);
        System.out.println("[M6T03-EVIDENCE] E9 单价非法: " + r4.getStatusCode().value() + " " + r4.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, r4.getStatusCode());
        assertEquals(ErrorCodes.PO_9011, codeOf(r4));
    }

    // =====================================================================
    // E10：GET 详情
    // =====================================================================

    @Test
    @DisplayName("E10 GET 详情：含 items[] 与 received_qty；不存在/跨团队 ⇒ 404 PO-9004")
    void getDetail() throws Exception {
        long poId = createDraftPo("DRAFT", "10.000", "100.00", 0);
        String token = login("viewer01");

        ResponseEntity<String> res = get(BASE + "/" + poId, token);
        System.out.println("[M6T03-EVIDENCE] E10 GET: " + res.getStatusCode().value()
                + " " + res.getBody().substring(0, Math.min(300, res.getBody().length())) + "…");
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals(poId, data.get("purchase_order_id").asLong());
        assertEquals("DRAFT", data.get("status").asText());
        assertEquals("10.000", data.get("total_qty").asText(), "数量 scale=3 字符串");
        assertEquals("100.00", data.get("total_amount").asText(), "金额 scale=2 字符串");
        assertEquals("0.000", data.get("received_qty").asText(), "到货进度（received_qty）必须返回");
        assertEquals(1, data.get("items").size());
        assertEquals(SKU7, data.get("items").get(0).get("sku_id").asLong());
        assertEquals("10.0000", data.get("items").get(0).get("unit_price").asText(),
                "单价 scale=4 字符串");
        // ★ VIEWER 可读（未加 @RequireRole）

        ResponseEntity<String> missing = get(BASE + "/999999", token);
        System.out.println("[M6T03-EVIDENCE] E10 不存在: " + missing.getStatusCode().value()
                + " " + missing.getBody());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(ErrorCodes.PO_9004, codeOf(missing));
    }

    // =====================================================================
    // E11：GET 列表
    // =====================================================================

    @Test
    @DisplayName("E11 GET 列表：字段名 list、total 正确、跨团队不可见、created_at DESC 排序")
    void listOrders() throws Exception {
        String token = login("viewer01");
        // 三条本团队（created_at 逆序播种 ⇒ DESC 排序应还原为 c,b,a）
        long a = createDraftPoAt("DRAFT", "10.000", "100.00", "2026-01-01T08:00:00");
        long b = createDraftPoAt("PENDING", "20.000", "200.00", "2026-01-02T08:00:00");
        long c = createDraftPoAt("ORDERED", "30.000", "300.00", "2026-01-03T08:00:00");
        createDraftPoAtTeam8(); // 他团队 ⇒ 不可见

        ResponseEntity<String> res = get(BASE + "?page=1&pageSize=50", token);
        System.out.println("[M6T03-EVIDENCE] E11 GET: " + res.getStatusCode().value()
                + " " + res.getBody().substring(0, Math.min(260, res.getBody().length())) + "…");
        assertEquals(HttpStatus.OK, res.getStatusCode(), res.getBody());
        JsonNode data = json.readTree(res.getBody()).get("data");
        assertEquals(3L, data.get("total").asLong(), "他团队不可见 ⇒ total=3");
        assertEquals(3, data.get("list").size(), "★ 分页字段名是 list（不是 items）");
        assertTrue(data.has("list"), "data 必须含 list 键");
        assertFalse(data.has("items"), "★ data 不得含 items 键（契约字段名是 list）");
        // created_at DESC ⇒ c, b, a
        assertEquals(c, data.get("list").get(0).get("purchase_order_id").asLong());
        assertEquals(b, data.get("list").get(1).get("purchase_order_id").asLong());
        assertEquals(a, data.get("list").get(2).get("purchase_order_id").asLong());

        // 状态过滤
        ResponseEntity<String> filtered = get(BASE + "?status=ORDERED", token);
        JsonNode fdata = json.readTree(filtered.getBody()).get("data");
        System.out.println("[M6T03-EVIDENCE] E11 status=ORDERED: total=" + fdata.get("total").asLong());
        assertEquals(1L, fdata.get("total").asLong());
        assertEquals(c, fdata.get("list").get(0).get("purchase_order_id").asLong());
    }

    // =====================================================================
    // E12：不写库存（INV-03）
    // =====================================================================

    @Test
    @DisplayName("E12 不写库存：confirm/order/cancel/PATCH 前后 on_hand_qty 与流水计数均不变")
    void doesNotTouchInventory() throws Exception {
        String approverToken = login("approver01");
        long poId = createDraftPo("DRAFT", "10.000", "100.00", 0);
        BigDecimal onHandBefore = jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE team_id = ? AND warehouse_id = ? "
                        + "AND sku_id = ?", BigDecimal.class, TEAM7, WH7, SKU7);
        Long txnBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions", Long.class);

        assertEquals(HttpStatus.OK, postWithVersion(BASE + "/" + poId + "/confirm",
                "{\"expected_version\":0}", approverToken).getStatusCode());
        assertEquals(HttpStatus.OK, postWithVersion(BASE + "/" + poId + "/order",
                "{\"expected_version\":1}", approverToken).getStatusCode());
        assertEquals(HttpStatus.OK, postWithBody(BASE + "/" + poId + "/cancel",
                "{\"reason\":\"计划调整\"}", approverToken).getStatusCode());
        long other = createDraftPo("DRAFT", "10.000", "100.00", 0);
        long lineId = firstLineId(other);
        assertEquals(HttpStatus.OK, patch(BASE + "/" + other,
                "{\"expected_version\":0,\"items\":[{\"purchase_order_item_id\":" + lineId
                        + ",\"ordered_qty\":\"12.000\"}]}", login("operator01")).getStatusCode());

        BigDecimal onHandAfter = jdbc.queryForObject(
                "SELECT on_hand_qty FROM inventory_balances WHERE team_id = ? AND warehouse_id = ? "
                        + "AND sku_id = ?", BigDecimal.class, TEAM7, WH7, SKU7);
        Long txnAfter = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions", Long.class);
        System.out.println("[M6T03-EVIDENCE] E12 on_hand: " + onHandBefore + " → " + onHandAfter
                + " | inventory_transactions: " + txnBefore + " → " + txnAfter);
        assertEquals(0, onHandBefore.compareTo(onHandAfter), "on_hand_qty 不得变化");
        assertEquals(txnBefore, txnAfter, "不得写入库存流水");
    }

    // =====================================================================
    // E13：并发 confirm（CAS 守卫）
    // =====================================================================

    @Test
    @DisplayName("E13 并发 confirm：两线程同时确认 ⇒ 恰好 1 个 200，另一个 PO-9003")
    void concurrentConfirmOnlyOneSucceeds() throws Exception {
        long poId = createDraftPo("DRAFT", "10.000", "100.00", 0);
        String token = login("approver01");
        String body = "{\"expected_version\":0}";

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return postWithVersion(BASE + "/" + poId + "/confirm", body, token);
                }));
            }
            gate.countDown();
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : futures) {
                responses.add(f.get(60, TimeUnit.SECONDS));
            }
            int ok = 0;
            int conflict = 0;
            for (ResponseEntity<String> r : responses) {
                int status = r.getStatusCode().value();
                System.out.println("[M6T03-EVIDENCE] E13 并发响应: " + status + " " + r.getBody());
                if (status == 200) {
                    ok++;
                } else if (status == 409 && ErrorCodes.PO_9003.equals(codeOf(r))) {
                    conflict++;
                } else {
                    throw new AssertionError("并发结果非法：" + status + " " + r.getBody());
                }
            }
            System.out.println("[M6T03-EVIDENCE] E13 组合: ok=" + ok + " conflict=" + conflict);
            assertEquals(1, ok, "恰好 1 个 200");
            assertEquals(1, conflict, "另一个必须 PO-9003（CAS 守卫）");
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, version FROM purchase_orders WHERE id = ?", poId);
            System.out.println("[M6T03-EVIDENCE] E13 DB: " + row);
            assertEquals("PENDING", row.get("status"));
            assertEquals(1, ((Number) row.get("version")).intValue(), "只允许 +1 次");
            assertEquals(1L, auditCount(poId, "PURCHASE_ORDER_CONFIRM"), "审计只写 1 条");
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** JDBC 直插采购单（含 1 条明细）；返回 poId。 */
    private long createDraftPo(String status, String qty, String amount, int version) {
        long runTag = ++itemSeq;
        long sku = runTag % 2 == 0 ? SKU7B : SKU7;
        return insertPo(status, qty, amount, version, sku, null, TEAM7);
    }

    private long createDraftPoAt(String status, String qty, String amount, String createdAt) {
        long sku = (++itemSeq) % 2 == 0 ? SKU7B : SKU7;
        return insertPo(status, qty, amount, 0, sku,
                java.time.LocalDateTime.parse(createdAt), TEAM7);
    }

    private long createDraftPoAtTeam8() {
        return insertPo("DRAFT", "10.000", "100.00", 0, SKU8, null, TEAM8);
    }

    private long insertPo(String status, String qty, String amount, int version, long sku,
                          java.time.LocalDateTime createdAt, long teamId) {
        long supplier = teamId == TEAM7 ? SUPPLIER7 : SUPPLIER8;
        long warehouse = teamId == TEAM7 ? WH7 : WH8;
        long product = teamId == TEAM7 ? PRODUCT7 : PRODUCT8;
        String poNo = "PO-T" + teamId + "-" + String.format("%04d", ++poSeq);
        if (createdAt == null) {
            jdbc.update("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, "
                            + "status, source, total_qty, total_amount, received_qty, version, "
                            + "created_by, updated_by) VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, 0.000, ?, ?, ?)",
                    teamId, poNo, supplier, warehouse, status,
                    new BigDecimal(qty), new BigDecimal(amount), version, APPROVER_ID, APPROVER_ID);
        } else {
            jdbc.update("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, "
                            + "status, source, total_qty, total_amount, received_qty, version, "
                            + "created_by, updated_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, 0.000, ?, ?, ?, ?)",
                    teamId, poNo, supplier, warehouse, status,
                    new BigDecimal(qty), new BigDecimal(amount), version,
                    APPROVER_ID, APPROVER_ID, createdAt);
        }
        long poId = jdbc.queryForObject(
                "SELECT id FROM purchase_orders WHERE po_no = ?", Long.class, poNo);
        jdbc.update("INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, "
                        + "product_id, ordered_qty, received_qty, unit_price, amount, version, "
                        + "created_by, updated_by) VALUES (?, ?, 1, ?, ?, ?, 0.000, ?, ?, 0, ?, ?)",
                teamId, poId, sku, product, new BigDecimal(qty),
                new BigDecimal("10.0000"), new BigDecimal(amount), APPROVER_ID, APPROVER_ID);
        return poId;
    }

    /** 建一条 CONVERTED 补货建议（含分析链），用于 E6「取消不改回 APPROVED」。 */
    private long createConvertedSuggestion(long poId) {
        long poItemId = jdbc.queryForObject(
                "SELECT id FROM purchase_order_items WHERE purchase_order_id = ?", Long.class, poId);
        String runNo = "RUN-T-CONV-" + (++itemSeq);
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, algorithm_version) "
                + "VALUES (?, ?, 'SUCCEEDED', 'ALL_ACTIVE', 'avg-ma-3sigma-v1')", TEAM7, runNo);
        long runId = jdbc.queryForObject(
                "SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, "
                        + "qty_suggest, safety_stock_qty) VALUES (?, ?, ?, ?, ?, 8.000, 'HIGH', 'SKU', "
                        + "6.250, 104.500, 10.000, 25.000)",
                TEAM7, runId, SKU7, PRODUCT7, WH7);
        long srcId = jdbc.queryForObject(
                "SELECT id FROM analysis_items WHERE run_id = ?", Long.class, runId);
        jdbc.update("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                        + "product_id, warehouse_id, status, suggested_qty, qty, risk_level, version, "
                        + "created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, 'CONVERTED', 10.000, "
                        + "10.000, 'HIGH', 0, ?, ?)",
                TEAM7, srcId, runId, SKU7, PRODUCT7, WH7, APPROVER_ID, APPROVER_ID);
        return jdbc.queryForObject(
                "SELECT id FROM replenishment_items WHERE source_analysis_item_id = ?", Long.class, srcId);
    }

    private long firstLineId(long poId) {
        return jdbc.queryForObject(
                "SELECT id FROM purchase_order_items WHERE purchase_order_id = ? ORDER BY line_no",
                Long.class, poId);
    }

    private String statusOf(long itemId) {
        return jdbc.queryForObject(
                "SELECT status FROM replenishment_items WHERE id = ?", String.class, itemId);
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

    private ResponseEntity<String> postWithVersion(String path, String body, String token) {
        return exchange(path, body, token, HttpMethod.POST);
    }

    private ResponseEntity<String> postWithBody(String path, String body, String token) {
        return exchange(path, body, token, HttpMethod.POST);
    }

    private ResponseEntity<String> patch(String path, String body, String token) {
        return exchange(path, body, token, HttpMethod.PATCH);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> exchange(String path, String body, String token, HttpMethod method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
