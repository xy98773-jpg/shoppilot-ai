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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6-T12a —— 采购单 / 补货建议<b>状态机表驱动</b> + <b>并发</b> + <b>对账收尾</b>。
 *
 * <h2>覆盖范围（T12a 段 + T12b 补齐）</h2>
 * <ul>
 *   <li><b>A1</b> {@code TEST_STRATEGY §8.1} 第一张表（13 行）→ 扩为 <b>24 个参数</b>
 *       （合法 10 + 非法 14；终态「→任意」按目标态拆开，B0-1 要求）。
 *       ★ 4 个「到货驱动」合法行自 {@code M6-T12b} 起走<b>真实链路</b>
 *       （{@code POST /receipts} 201 → {@code POST /receipts/{id}/confirm} 200）并断言<b>目标态</b>
 *       ⇒ <b>真断通过 = 24 行</b>（10 合法 + 14 非法），台账见 {@code a1CoverageLedger}；</li>
 *   <li><b>A2</b> {@code §8.1} 第二段（{@code ReplenishmentItemStatus}）→
 *       5 个命令 × 7 个状态 = <b>35 个参数</b>全表；另加「驳回未填意见 ⇒ {@code RPL-8005}」；</li>
 *   <li><b>A3</b> 并发 confirm / cancel / order 各一例（两线程，恰好 1 胜）；</li>
 *   <li><b>A4</b> 可复用的<b>对账断言</b>（余额行 vs 流水累计，{@code txn_type <> 'REVERSAL'} 净额口径）
 *       + <b>成对种子</b>（余额行 + {@code INIT_IMPORT} 流水）。</li>
 * </ul>
 *
 * <h2>★ 三处口径取舍（均有出处，非自由发挥；详见 {@code M6-T12a} 汇报）</h2>
 * <ol>
 *   <li><b>合法 10 / 非法 3</b>（不是 B0-1 写的 9/4）：出处
 *       {@code TEST_STRATEGY.md:318-332} 的表逐行数即 10 合法 + 3 非法 = 13 行。
 *       ★ 项目负责人已裁定（F1）：以 SSOT 为准按 10/3 做，令文「9/4」为笔误。</li>
 *   <li><b>{@code RECEIVED → CANCELLED} 判 {@code PO-9009}（不是 {@code PO-9003}）</b>：
 *       {@code PurchaseOrderAppService#cancel} 先判「已全部到货」再判状态集合
 *       （{@code PurchaseOrderAppService.java:236-238}）；{@code ERROR_CODES.md:231}、
 *       {@code API_CONTRACT.md:601}、{@code PURCHASING_WORKFLOW.md:213/375} 同口径；
 *       既有生产测试 {@code PurchaseOrderFlowIntegrationTest#cancelGuards}（E7）已锁死该行为。
 *       ★ 项目负责人已裁定（F2）：该行期望 {@code PO-9009}，其余非法行仍 {@code PO-9003}。</li>
 *   <li><b>4 个「到货驱动」合法行断言<b>目标态</b>（{@code M6-T12b} 已落地）</b>：
 *       {@code ORDERED→PARTIALLY_RECEIVED} / {@code ORDERED→RECEIVED} /
 *       {@code PARTIALLY_RECEIVED→PARTIALLY_RECEIVED} / {@code PARTIALLY_RECEIVED→RECEIVED}
 *       的唯一生产者是到货<b>确认</b>（{@code POST /receipts/{id}/confirm}，{@code GoodsReceiptController:127}）。
 *       T12a 期间该端点未落地，故当时只做「到货登记入口接受该源态」的替代断言 + {@code Assumptions.abort}；
 *       T12b 起改为<b>登记 201 → 确认 200 → 断言目标态</b>，四行计入 A1 通过行数。
 *       对称的非法行（{@code RECEIVED/CANCELLED → PARTIALLY_RECEIVED/RECEIVED} ⇒ 409
 *       {@code PO-9003}）反证「只有这两个源态合法」。</li>
 * </ol>
 *
 * <h2>★ 种子纪律（B0-5）</h2>
 * <p>库存种子<b>一律成对</b>（余额行 + {@code INIT_IMPORT} 流水），绝不复用
 * {@code tools/evi/seed-b95.sql}（B0-6：SKU-B/9003 有余额无流水，会让对账在<b>种子阶段</b>假 FAIL）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PurchaseOrderStateMachineTableTest {

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
    private static final long SKU8 = 9102L;
    private static final long WH7 = 9004L;
    private static final long WH8 = 9104L;
    private static final long SUPPLIER7 = 8001L;
    private static final long SUPPLIER8 = 8101L;

    /** 成对种子的初始在手量：余额行与 {@code INIT_IMPORT} 流水必须同为 100.000。 */
    private static final BigDecimal SEED_ON_HAND = new BigDecimal("100.000");

    private static final String ORDERS = "/api/v1/purchasing/orders";
    private static final String RECEIPTS = "/api/v1/purchasing/receipts";
    private static final String RPL = "/api/v1/replenishment/items";

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
    private int runSeq;
    private int idemSeq;

    // =====================================================================
    // 种子
    // =====================================================================

    @BeforeEach
    void resetAndSeed() {
        // ★ 删除序必须逆着外键依赖走：
        //   inventory_transactions → warehouses/skus；
        //   goods_receipt_items → purchase_order_items（fk_gri_poi RESTRICT）与 goods_receipts；
        //   故 goods_receipt_items 必须早于 purchase_order_items。
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
        runSeq = 0;
        idemSeq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-SM-7', '状态机七团', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-SM-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE'), "
                + "(?, 'approver01', 'approver01@example.com', ?, '小审', 'ACTIVE'), "
                + "(?, 'viewer01', 'viewer01@example.com', ?, '小观', 'ACTIVE')",
                OPERATOR_ID, PASSWORD_HASH, APPROVER_ID, PASSWORD_HASH, VIEWER_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'APPROVER', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM7, OPERATOR_ID, TEAM7, APPROVER_ID, TEAM7, VIEWER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-SM7', '七团商品'), (?, ?, 'P-SM8', '八团商品')",
                PRODUCT7, TEAM7, PRODUCT8, TEAM8);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES "
                + "(?, ?, 'WH-SM7', '七团仓'), (?, ?, 'WH-SM8', '八团仓')", WH7, TEAM7, WH8, TEAM8);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-SM7', '七团供应商'), (?, ?, 'SUP-SM8', '八团供应商')",
                SUPPLIER7, TEAM7, SUPPLIER8, TEAM8);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name) VALUES "
                + "(?, ?, ?, 'SKU-SM7', '七团SKU'), (?, ?, ?, 'SKU-SM8', '八团SKU')",
                SKU7, TEAM7, PRODUCT7, SKU8, TEAM8, PRODUCT8);

        // ★ B0-5：成对种子（余额行 + INIT_IMPORT 流水），对账从种子阶段即自洽
        seedInventoryPair(TEAM7, WH7, SKU7, SEED_ON_HAND);
    }

    /**
     * 写入<b>成对</b>库存种子：{@code inventory_balances} 余额行 + 等额 {@code INIT_IMPORT} 流水。
     *
     * <p>★ 这是 B0-5 的硬要求：只插余额不插流水会让 A4 对账在<b>种子阶段</b>命中非零差，
     * 产出与生产代码无关的「假 FAIL」（{@code TEST_STRATEGY.md:305} 警示框）。
     */
    private void seedInventoryPair(long teamId, long warehouseId, long skuId, BigDecimal qty) {
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                        + "in_transit_qty, committed_qty, version) VALUES (?, ?, ?, ?, 0.000, 0.000, 0)",
                teamId, warehouseId, skuId, qty);
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, "
                        + "qty_delta, qty_before, qty_after, biz_type, idempotency_key, occurred_at) "
                        + "VALUES (?, ?, ?, 'INIT_IMPORT', ?, 0.000, ?, 'IMPORT', ?, NOW(3))",
                teamId, warehouseId, skuId, qty, qty, "SEED-INIT-" + teamId + "-" + skuId);
    }

    // =====================================================================
    // A1 —— 采购单状态转换表驱动（§8.1 第一张表：10 合法 + 3 非法，拆成 24 参数）
    // =====================================================================

    /** 驱动一次状态转换的<b>生产入口</b>（不是测试自造的跳转）。 */
    private enum Driver {
        /** {@code POST /orders/{id}/confirm}：DRAFT → PENDING。 */
        CONFIRM,
        /** {@code POST /orders/{id}/order}：PENDING → ORDERED。 */
        ORDER,
        /** {@code POST /orders/{id}/cancel}：{DRAFT,PENDING,ORDERED,PARTIALLY_RECEIVED} → CANCELLED。 */
        CANCEL,
        /**
         * 到货<b>登记</b>（{@code POST /receipts}）：源态守卫 ORDERED / PARTIALLY_RECEIVED 才放行。
         *
         * <p>★ 合法行在 T12b 起继续调<b>确认</b>（{@code POST /receipts/{id}/confirm}）以产生目标态；
         * 非法行只走到登记（源态守卫即拒绝，{@code PO-9003}）。
         */
        RECEIPT
    }

    /**
     * A1 行构造器（非到货驱动）：{@code receiptQty} 缺省 {@code null}。
     */
    private static Arguments poRow(String from, String to, boolean legal, Driver driver,
                                   int expectStatus, String expectCode, String note) {
        return poRow(from, to, legal, driver, expectStatus, expectCode, null, note);
    }

    /**
     * A1 行构造器（含到货数量）。
     *
     * <p>★ {@code receiptQty} 必须<b>参数化</b>：种子采购单固定 {@code ordered_qty = 100.000}
     * （{@code PARTIALLY_RECEIVED} 时 {@code received_qty = 40.000}），若到货量写死则
     * 「→ RECEIVED」两行永远到不了终态。四行依次取 10 / 100 / 10 / 60。
     */
    private static Arguments poRow(String from, String to, boolean legal, Driver driver,
                                   int expectStatus, String expectCode, String receiptQty, String note) {
        return Arguments.of(from, to, legal, driver, expectStatus, expectCode, receiptQty, note);
    }

    /**
     * A1 参数清单（V4 的「表驱动参数清单原文」即本方法体）。
     *
     * <p>合法 10 行逐行对齐 {@code TEST_STRATEGY.md:320-329}；非法 3 行（第 330–332 行）
     * 中「{@code RECEIVED}/{@code CANCELLED} → 任意」按目标态拆成 12 个参数（B0-1 要求
     * 「终态两行要拆成多个参数」）。
     */
    static Stream<Arguments> purchaseOrderTransitions() {
        return Stream.of(
                // ── 合法 10 行（TEST_STRATEGY.md:320-329）──
                poRow("DRAFT", "PENDING", true, Driver.CONFIRM, 200, null, "§8.1:320 仅 APPROVER+"),
                poRow("DRAFT", "CANCELLED", true, Driver.CANCEL, 200, null, "§8.1:321"),
                poRow("PENDING", "ORDERED", true, Driver.ORDER, 200, null, "§8.1:322"),
                poRow("PENDING", "CANCELLED", true, Driver.CANCEL, 200, null, "§8.1:323"),
                poRow("ORDERED", "PARTIALLY_RECEIVED", true, Driver.RECEIPT, 201, null, "10.000",
                        "§8.1:324 部分到货（目标态由 confirm 产生）"),
                poRow("ORDERED", "RECEIVED", true, Driver.RECEIPT, 201, null, "100.000",
                        "§8.1:325 全部到货（目标态由 confirm 产生）"),
                poRow("ORDERED", "CANCELLED", true, Driver.CANCEL, 200, null, "§8.1:326"),
                poRow("PARTIALLY_RECEIVED", "PARTIALLY_RECEIVED", true, Driver.RECEIPT, 201, null, "10.000",
                        "§8.1:327 继续部分到货（40+10=50 < 100）"),
                poRow("PARTIALLY_RECEIVED", "RECEIVED", true, Driver.RECEIPT, 201, null, "60.000",
                        "§8.1:328 补齐剩余（40+60=100 ⇒ RECEIVED）"),
                poRow("PARTIALLY_RECEIVED", "CANCELLED", true, Driver.CANCEL, 200, null, "§8.1:329"),

                // ── 非法：终态 RECEIVED → 任意（§8.1:330）──
                poRow("RECEIVED", "DRAFT", false, Driver.CONFIRM, 409, ErrorCodes.PO_9003, "终态不可变"),
                poRow("RECEIVED", "PENDING", false, Driver.CONFIRM, 409, ErrorCodes.PO_9003, "终态不可变"),
                poRow("RECEIVED", "ORDERED", false, Driver.ORDER, 409, ErrorCodes.PO_9003, "终态不可变"),
                poRow("RECEIVED", "PARTIALLY_RECEIVED", false, Driver.RECEIPT, 409, ErrorCodes.PO_9003,
                        "终态不可变（到货登记源态守卫）"),
                poRow("RECEIVED", "RECEIVED", false, Driver.RECEIPT, 409, ErrorCodes.PO_9003,
                        "终态不可变（到货登记源态守卫）"),
                // ★ 特例：不是 PO-9003，而是 PO-9009（PurchaseOrderAppService.java:236-238 先判「已全部到货」）
                poRow("RECEIVED", "CANCELLED", false, Driver.CANCEL, 409, ErrorCodes.PO_9009,
                        "★特例 ERROR_CODES.md:231 / WORKFLOW:375"),

                // ── 非法：终态 CANCELLED → 任意（§8.1:330）──
                poRow("CANCELLED", "DRAFT", false, Driver.CONFIRM, 409, ErrorCodes.PO_9003, "终态不可变"),
                poRow("CANCELLED", "PENDING", false, Driver.CONFIRM, 409, ErrorCodes.PO_9003, "终态不可变"),
                poRow("CANCELLED", "ORDERED", false, Driver.ORDER, 409, ErrorCodes.PO_9003, "终态不可变"),
                poRow("CANCELLED", "PARTIALLY_RECEIVED", false, Driver.RECEIPT, 409, ErrorCodes.PO_9003,
                        "终态不可变（到货登记源态守卫）"),
                poRow("CANCELLED", "RECEIVED", false, Driver.RECEIPT, 409, ErrorCodes.PO_9003,
                        "终态不可变（到货登记源态守卫）"),
                poRow("CANCELLED", "CANCELLED", false, Driver.CANCEL, 409, ErrorCodes.PO_9003, "终态不可变"),

                // ── 非法：跳级（§8.1:331-332）──
                poRow("DRAFT", "ORDERED", false, Driver.ORDER, 409, ErrorCodes.PO_9003, "跳级：DRAFT 不能直接下单"),
                poRow("PENDING", "RECEIVED", false, Driver.RECEIPT, 409, ErrorCodes.PO_9003,
                        "跳级：未 ORDERED 不能登记到货")
        );
    }

    @ParameterizedTest(name = "A1[{index}] {0} -> {1} legal={2} driver={3} expect={4}/{5}")
    @MethodSource("purchaseOrderTransitions")
    @DisplayName("A1 采购单状态转换表驱动（13 行 → 24 参数，全部真断）")
    void a1PurchaseOrderTransition(String from, String to, boolean legal, Driver driver,
                                   int expectStatus, String expectCode, String receiptQty,
                                   String note) throws Exception {
        PoSeed po = createPurchaseOrder(from);
        String token = login("approver01");

        ResponseEntity<String> res = drive(driver, po, token, receiptQty);
        int actualStatus = res.getStatusCode().value();
        String actualCode = actualStatus >= 400 ? codeOf(res) : null;
        String statusAfter = poStatus(po.id());

        System.out.println("[M6T12-A1] " + from + " -> " + to
                + " | legal=" + legal + " driver=" + driver
                + " | expect=" + expectStatus + "/" + (expectCode == null ? "-" : expectCode)
                + " | actual=" + actualStatus + "/" + (actualCode == null ? "-" : actualCode)
                + " | po.status after=" + statusAfter
                + " | " + note);

        if (legal) {
            if (driver == Driver.RECEIPT) {
                // ★ T12b：真实链路 = 登记(201) → 确认(200) ⇒ 断言<b>目标态</b>与连带事实
                assertEquals(expectStatus, actualStatus, "到货登记应被接受：" + res.getBody());
                long receiptId = json.readTree(res.getBody()).get("data").get("goods_receipt_id").asLong();
                assertEquals("DRAFT", receiptStatus(receiptId), "登记后到货单必须为 DRAFT（未确认）");

                ResponseEntity<String> confirmed = post(
                        RECEIPTS + "/" + receiptId + "/confirm", "{}", token, null);
                System.out.println("[M6T12-A1] confirm " + receiptId + " -> "
                        + confirmed.getStatusCode().value() + " " + confirmed.getBody());
                assertEquals(200, confirmed.getStatusCode().value(),
                        "到货确认应成功：" + confirmed.getBody());

                // 目标态：PO 由明细推导（PurchaseOrderAppService:364-365）
                //   ★ 必须<b>重新读库</b>：statusAfter 是登记后（confirm 前）的快照。
                assertEquals(to, poStatus(po.id()), "confirm 后采购单目标态不符");
                // 到货单目标态（用本次 receiptId 精确查，不用 ORDER BY id DESC）
                assertEquals("CONFIRMED",
                        json.readTree(confirmed.getBody()).get("data").get("status").asText(),
                        "确认响应体的到货单状态必须为 CONFIRMED");
                assertEquals("CONFIRMED", receiptStatus(receiptId), "到货单必须推进到 CONFIRMED");
                // 流水：种子 1 条 + 本单 1 条（限该 warehouse + sku，避免全表计数被其它数据污染）
                assertEquals(2L, countInboundTxns(WH7, SKU7),
                        "★ 确认后应有 2 笔 PURCHASE_INBOUND+INIT_IMPORT 流水（种子 1 + 本单 1）");
                assertEquals(1L, countPurchaseInbound(receiptId),
                        "★ 本单恰好产生 1 笔 PURCHASE_INBOUND 流水");
                assertInventoryReconciled(TEAM7, WH7, SKU7);
            } else {
                assertEquals(expectStatus, actualStatus, "合法转换应成功：" + res.getBody());
                assertEquals(to, statusAfter, "数据库中的目标状态不符");
                assertEquals(to, json.readTree(res.getBody()).get("data").get("status").asText(),
                        "响应体中的目标状态不符");
            }
        } else {
            assertEquals(expectStatus, actualStatus, "非法转换必须被拒：" + res.getBody());
            assertEquals(expectCode, actualCode, "错误码不符");
            assertEquals(from, statusAfter, "被拒后源状态不得变化（状态守卫必须原子）");
        }

        // ★ B0-4：库存相关用例一律以对账断言收尾
        assertInventoryReconciled(TEAM7, WH7, SKU7);
    }

    /**
     * A1 覆盖度台账（把「计入口径」变成可执行断言，杜绝汇报与实际执行偷偷漂移）。
     *
     * <p>★ {@code M6-T12b} 起 4 个「到货驱动」合法行改为目标态真断言并计入通过行数
     * ⇒ 真断通过 = <b>24</b> 行（10 合法 + 14 非法），{@code 待补 = 0}。
     *
     * <p>「待补」的可执行口径 = 合法 + 到货驱动 + <b>{@code receiptQty} 为空</b>
     * （空数量既无法构造到货单，也意味着该行还没接上真链路）。T12b 后必须为 0；
     * 反向保证「4 行到货驱动都带了数量」，而不只是把常量改成 0。
     */
    @Test
    @DisplayName("A1 覆盖度台账：10 合法 + 3 非法 = 13 行 → 24 参数，全部真断")
    void a1CoverageLedger() {
        List<Arguments> rows = purchaseOrderTransitions().toList();
        long legal = rows.stream().filter(row -> Boolean.TRUE.equals(row.get()[2])).count();
        long illegal = rows.size() - legal;
        long receiptDriven = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.get()[2]) && row.get()[3] == Driver.RECEIPT)
                .count();
        long pending = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.get()[2])
                        && row.get()[3] == Driver.RECEIPT
                        && row.get()[6] == null)
                .count();
        long asserted = rows.size() - pending;

        System.out.println("[M6T12-A1] 台账: 参数总数=" + rows.size() + " 合法=" + legal
                + " 非法=" + illegal + "（3 行展开）| 到货驱动=" + receiptDriven
                + " | 真断通过=" + asserted + " | 待补（不计入通过）=" + pending);

        assertEquals(24, rows.size(), "§8.1 的 13 行按目标态展开后应为 24 参数");
        assertEquals(10, legal, "SSOT TEST_STRATEGY.md:320-329 合法 10 行（B0-1 的「9」是令文笔误）");
        assertEquals(14, illegal, "SSOT 非法 3 行（终态→任意 / DRAFT→ORDERED / PENDING→RECEIVED）展开为 14 参数");
        assertEquals(4, receiptDriven, "到货驱动的合法行恰为 4 行");
        assertEquals(0, pending, "★ T12b 后到货驱动 4 行均已携带 receiptQty、断言目标态，不再有待补行");
        assertEquals(24, asserted, "★ A1 真断通过行数 = 24（10 合法 + 14 非法，无 SKIPPED）");
    }

    // =====================================================================
    // A2 —— 补货建议状态转换表驱动（§8.1 第二段：5 命令 × 7 状态 = 35 参数）
    // =====================================================================

    private static final String OK = "OK";
    private static Arguments rplRow(String action, String fromStatus, String expect) {
        return Arguments.of(action, fromStatus, expect);
    }

    /**
     * A2 参数清单：{@code submit} / {@code withdraw} / {@code approve} / {@code reject} / {@code cancel}
     * 五个命令 × {@code ReplenishmentItemStatus} 七个状态的全表（{@code DESIGN_BASIS §9.11}）。
     *
     * <p>期望值编码：{@code OK:<目标态>} 或 {@code <错误码>:<HTTP>}。
     */
    static Stream<Arguments> replenishmentTransitions() {
        return Stream.of(
                // ── 合法 6 行 ──
                rplRow("submit", "SUGGESTED", "OK:PENDING_REVIEW"),
                rplRow("withdraw", "PENDING_REVIEW", "OK:SUGGESTED"),
                rplRow("approve", "PENDING_REVIEW", "OK:APPROVED"),
                rplRow("reject", "PENDING_REVIEW", "OK:REJECTED"),
                rplRow("cancel", "SUGGESTED", "OK:CANCELLED"),
                rplRow("cancel", "APPROVED", "OK:CANCELLED"),

                // ── 非法：submit（重复提交是 8002，其它是 8001）──
                rplRow("submit", "PENDING_REVIEW", "RPL-8002:409"),
                rplRow("submit", "APPROVED", "RPL-8001:422"),
                rplRow("submit", "CONVERTED", "RPL-8001:422"),
                rplRow("submit", "REJECTED", "RPL-8001:422"),
                rplRow("submit", "SUPERSEDED", "RPL-8001:422"),
                rplRow("submit", "CANCELLED", "RPL-8001:422"),

                // ── 非法：withdraw ──
                rplRow("withdraw", "SUGGESTED", "RPL-8001:422"),
                rplRow("withdraw", "APPROVED", "RPL-8001:422"),
                rplRow("withdraw", "CONVERTED", "RPL-8001:422"),
                rplRow("withdraw", "REJECTED", "RPL-8001:422"),
                rplRow("withdraw", "SUPERSEDED", "RPL-8001:422"),
                rplRow("withdraw", "CANCELLED", "RPL-8001:422"),

                // ── 非法：approve（重复审批 8002）──
                rplRow("approve", "APPROVED", "RPL-8002:409"),
                rplRow("approve", "SUGGESTED", "RPL-8001:422"),
                rplRow("approve", "CONVERTED", "RPL-8001:422"),
                rplRow("approve", "REJECTED", "RPL-8001:422"),
                rplRow("approve", "SUPERSEDED", "RPL-8001:422"),
                rplRow("approve", "CANCELLED", "RPL-8001:422"),

                // ── 非法：reject（重复驳回 8002）──
                rplRow("reject", "REJECTED", "RPL-8002:409"),
                rplRow("reject", "SUGGESTED", "RPL-8001:422"),
                rplRow("reject", "APPROVED", "RPL-8001:422"),
                rplRow("reject", "CONVERTED", "RPL-8001:422"),
                rplRow("reject", "SUPERSEDED", "RPL-8001:422"),
                rplRow("reject", "CANCELLED", "RPL-8001:422"),

                // ── 非法：cancel（★ PENDING_REVIEW → CANCELLED 不存在，§9.11）──
                rplRow("cancel", "PENDING_REVIEW", "RPL-8001:422"),
                rplRow("cancel", "CONVERTED", "RPL-8001:422"),
                rplRow("cancel", "REJECTED", "RPL-8001:422"),
                rplRow("cancel", "SUPERSEDED", "RPL-8001:422"),
                rplRow("cancel", "CANCELLED", "RPL-8001:422")
        );
    }

    @ParameterizedTest(name = "A2[{index}] {0} on {1} => {2}")
    @MethodSource("replenishmentTransitions")
    @DisplayName("A2 补货建议状态转换表驱动（5 命令 × 7 状态 = 35 参数）")
    void a2ReplenishmentTransition(String action, String fromStatus, String expect) throws Exception {
        long id = createReplenishmentItem(fromStatus);
        String token = login("approver01");

        ResponseEntity<String> res = driveReplenishment(action, id, token);
        int actualStatus = res.getStatusCode().value();
        String actualCode = actualStatus >= 400 ? codeOf(res) : null;
        String statusAfter = rplStatus(id);

        System.out.println("[M6T12-A2] " + action + " on " + fromStatus
                + " | expect=" + expect
                + " | actual=" + actualStatus + "/" + (actualCode == null ? "-" : actualCode)
                + " | item.status after=" + statusAfter);

        if (expect.startsWith(OK + ":")) {
            String target = expect.substring(OK.length() + 1);
            assertEquals(200, actualStatus, "合法转换应成功：" + res.getBody());
            assertEquals(target, statusAfter, "数据库中的目标状态不符");
            assertEquals(target, json.readTree(res.getBody()).get("data").get("status").asText(),
                    "响应体中的目标状态不符");
            assertEquals(1L, reviewCount(id), "每次合法流转必须在 replenishment_reviews 留痕 1 条");
        } else {
            int idx = expect.lastIndexOf(':');
            String expectCode = expect.substring(0, idx);
            int expectHttp = Integer.parseInt(expect.substring(idx + 1));
            assertEquals(expectHttp, actualStatus, "HTTP 状态不符：" + res.getBody());
            assertEquals(expectCode, actualCode, "错误码不符");
            assertEquals(fromStatus, statusAfter, "被拒后源状态不得变化");
            assertEquals(0L, reviewCount(id), "被拒时不得留痕");
        }
    }

    @Test
    @DisplayName("A2 驳回未填意见：应用层 400 RPL-8005；DB CHECK ck_rr_comment_required 亦兜底")
    void a2RejectWithoutCommentIsRejected() throws Exception {
        long id = createReplenishmentItem("PENDING_REVIEW");
        String token = login("approver01");

        ResponseEntity<String> blank = post(RPL + "/" + id + "/reject",
                "{\"expected_version\":0,\"comment\":\"   \"}", token, null);
        System.out.println("[M6T12-A2] reject 空白 comment: " + blank.getStatusCode().value()
                + " " + blank.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode());
        assertEquals(ErrorCodes.RPL_8005, codeOf(blank));
        assertEquals("PENDING_REVIEW", rplStatus(id), "被拒后状态不得变化");

        ResponseEntity<String> missing = post(RPL + "/" + id + "/reject",
                "{\"expected_version\":0}", token, null);
        System.out.println("[M6T12-A2] reject 缺 comment: " + missing.getStatusCode().value()
                + " " + missing.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals(ErrorCodes.RPL_8005, codeOf(missing));

        // ★ DB 层兜底：绕过应用层直接插一条 action=REJECT 且 comment 为空 ⇒ CHECK 拒绝
        //   注：MySQL 的 CHECK 违规（SQLSTATE 3819）Spring 未归类为 DataIntegrityViolationException，
        //   实际抛 UncategorizedSQLException（DataAccessException 子类）⇒ 按父类断言。
        org.springframework.dao.DataAccessException dbReject = assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("INSERT INTO replenishment_reviews (team_id, replenishment_item_id, action, "
                        + "from_status, to_status, qty_before, qty_after, reviewer_id, reviewer_role, "
                        + "occurred_at, created_at) VALUES (?, ?, 'REJECT', 'PENDING_REVIEW', 'REJECTED', "
                        + "10.000, 10.000, ?, 'APPROVER', NOW(3), NOW(3))",
                        TEAM7, id, APPROVER_ID),
                "ck_rr_comment_required 必须拒绝「驳回无意见」");
        assertTrue(dbReject.getMessage() != null && dbReject.getMessage().contains("ck_rr_comment_required"),
                "违规必须来自 ck_rr_comment_required，实际：" + dbReject.getMessage());
        System.out.println("[M6T12-A2] DB CHECK 兜底命中: "
                + dbReject.getMessage().replace('\n', ' '));
        assertEquals(0L, reviewCount(id), "DB 拒绝后不得留下轨迹");
    }

    // =====================================================================
    // A3 —— 并发用例（B0-15：CountDownLatch 起跑门 + 无 sleep）
    // =====================================================================

    @Test
    @DisplayName("A3-1 并发 confirm：两线程同时确认 ⇒ 恰好 1 个 200、1 个 409 PO-9003")
    void a3ConcurrentConfirm() throws Exception {
        PoSeed po = createPurchaseOrder("DRAFT");
        String token = login("approver01");
        String body = "{\"expected_version\":" + po.version() + "}";

        List<ResponseEntity<String>> responses = runConcurrently(2,
                () -> post(ORDERS + "/" + po.id() + "/confirm", body, token, null));

        int[] tally = tallyConcurrency("A3-1 confirm", responses);
        System.out.println("[M6T12-A3] confirm: ok=" + tally[0] + " conflict=" + tally[1]
                + " | po.status=" + poStatus(po.id())
                + " version=" + poVersion(po.id())
                + " audit=" + auditCount(po.id(), "PURCHASE_ORDER_CONFIRM"));
        assertEquals(1, tally[0], "恰好 1 个成功");
        assertEquals(1, tally[1], "另一个必须 PO-9003");
        assertEquals("PENDING", poStatus(po.id()));
        assertEquals(po.version() + 1, poVersion(po.id()), "version 只允许 +1 一次");
        assertEquals(1L, auditCount(po.id(), "PURCHASE_ORDER_CONFIRM"), "审计只写 1 条");
        assertInventoryReconciled(TEAM7, WH7, SKU7);
    }

    @Test
    @DisplayName("A3-2 并发 cancel：两线程同时取消 ⇒ 恰好 1 个 200、1 个 409 PO-9003")
    void a3ConcurrentCancel() throws Exception {
        PoSeed po = createPurchaseOrder("ORDERED");
        String token = login("approver01");
        String body = "{\"reason\":\"并发取消\"}";

        List<ResponseEntity<String>> responses = runConcurrently(2,
                () -> post(ORDERS + "/" + po.id() + "/cancel", body, token, null));

        int[] tally = tallyConcurrency("A3-2 cancel", responses);
        System.out.println("[M6T12-A3] cancel: ok=" + tally[0] + " conflict=" + tally[1]
                + " | po.status=" + poStatus(po.id())
                + " version=" + poVersion(po.id())
                + " audit=" + auditCount(po.id(), "PURCHASE_ORDER_CANCEL"));
        assertEquals(1, tally[0], "恰好 1 个成功");
        assertEquals(1, tally[1], "另一个必须 PO-9003");
        assertEquals("CANCELLED", poStatus(po.id()));
        assertEquals(po.version() + 1, poVersion(po.id()), "version 只允许 +1 一次");
        assertEquals(1L, auditCount(po.id(), "PURCHASE_ORDER_CANCEL"), "审计只写 1 条");
        assertInventoryReconciled(TEAM7, WH7, SKU7);
    }

    @Test
    @DisplayName("A3-3 并发 order：两线程同时下单 ⇒ 恰好 1 个 200、1 个 409 PO-9003")
    void a3ConcurrentOrder() throws Exception {
        PoSeed po = createPurchaseOrder("PENDING");
        String token = login("approver01");
        String body = "{\"expected_version\":" + po.version() + "}";

        List<ResponseEntity<String>> responses = runConcurrently(2,
                () -> post(ORDERS + "/" + po.id() + "/order", body, token, null));

        int[] tally = tallyConcurrency("A3-3 order", responses);
        System.out.println("[M6T12-A3] order: ok=" + tally[0] + " conflict=" + tally[1]
                + " | po.status=" + poStatus(po.id())
                + " version=" + poVersion(po.id())
                + " audit=" + auditCount(po.id(), "PURCHASE_ORDER_ORDERED"));
        assertEquals(1, tally[0], "恰好 1 个成功");
        assertEquals(1, tally[1], "另一个必须 PO-9003");
        assertEquals("ORDERED", poStatus(po.id()));
        assertEquals(po.version() + 1, poVersion(po.id()), "version 只允许 +1 一次");
        assertEquals(1L, auditCount(po.id(), "PURCHASE_ORDER_ORDERED"), "审计只写 1 条");
        assertInventoryReconciled(TEAM7, WH7, SKU7);
    }

    /**
     * 并发执行器（B0-15 范式：{@code CountDownLatch} 起跑门 + {@code try/finally} +
     * {@code shutdownNow} + {@code awaitTermination}；<b>禁止</b> {@code Thread.sleep} 时序 hack）。
     */
    private List<ResponseEntity<String>> runConcurrently(
            int threads, java.util.concurrent.Callable<ResponseEntity<String>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 统计并发结果：{@code [0] = 200 成功数，[1] = 409 PO-9003 数；其它组合直接失败。 */
    private int[] tallyConcurrency(String label, List<ResponseEntity<String>> responses) throws Exception {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (ResponseEntity<String> res : responses) {
            int status = res.getStatusCode().value();
            String code = status >= 400 ? codeOf(res) : null;
            System.out.println("[M6T12-A3] " + label + " 并发响应: " + status + "/" + code
                    + " " + res.getBody());
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 409 && ErrorCodes.PO_9003.equals(code)) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError(label + " 出现非法并发结果：" + status + " " + res.getBody());
            }
        }
        return new int[] {ok.get(), conflict.get()};
    }

    // =====================================================================
    // A4 —— 对账断言基建（B0-4 / B0-5）
    // =====================================================================

    /**
     * ★ 可复用的<b>对账断言</b>（B0-4）：给定 {@code (team_id, warehouse_id, sku_id)}，
     * 断言 {@code inventory_balances.on_hand_qty} == Σ{@code inventory_transactions.qty_delta}
     * （{@code txn_type <> 'REVERSAL'} 的净额口径，{@code TEST_STRATEGY.md:306}）。
     *
     * <p>★ 之所以要求<b>成对种子</b>（B0-5）：若只插余额行不插 {@code INIT_IMPORT} 流水，
     * 本断言会在<b>种子阶段</b>就命中非零差，产出与生产代码无关的「假 FAIL」。
     *
     * @return 实测的 {@code [余额, 流水累计]}，供调用方/Surefire 日志取证
     */
    private BigDecimal[] assertInventoryReconciled(long teamId, long warehouseId, long skuId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.on_hand_qty AS balance_qty, "
                        + "COALESCE((SELECT SUM(t.qty_delta) FROM inventory_transactions t "
                        + "  WHERE t.team_id = b.team_id AND t.warehouse_id = b.warehouse_id "
                        + "    AND t.sku_id = b.sku_id AND t.txn_type <> 'REVERSAL'), 0) AS ledger_qty "
                        + "FROM inventory_balances b "
                        + "WHERE b.team_id = ? AND b.warehouse_id = ? AND b.sku_id = ?",
                teamId, warehouseId, skuId);
        assertEquals(1, rows.size(), "对账前置：余额行必须唯一存在（team=" + teamId
                + " wh=" + warehouseId + " sku=" + skuId + "）");
        BigDecimal balance = (BigDecimal) rows.get(0).get("balance_qty");
        BigDecimal ledger = (BigDecimal) rows.get(0).get("ledger_qty");
        assertNotNull(balance, "余额值不得为空");
        assertNotNull(ledger, "流水累计值不得为空");
        System.out.println("[M6T12-A4] 对账 team=" + teamId + " wh=" + warehouseId + " sku=" + skuId
                + " | 余额 on_hand_qty=" + balance.toPlainString()
                + " | 流水累计 Σ(qty_delta, txn_type<>REVERSAL)=" + ledger.toPlainString()
                + " | 判定=" + (balance.compareTo(ledger) == 0 ? "一致" : "不一致"));
        assertEquals(0, balance.compareTo(ledger),
                "★ 对账失败：余额 " + balance.toPlainString()
                        + " != 流水累计 " + ledger.toPlainString() + "（ledger_summary）");
        return new BigDecimal[] {balance, ledger};
    }

    @Test
    @DisplayName("A4 对账断言非空转：人为制造账实差 ⇒ 断言必须失败；恢复后必须通过")
    void a4ReconciliationDetectsDrift() {
        BigDecimal[] before = assertInventoryReconciled(TEAM7, WH7, SKU7);
        System.out.println("[M6T12-A4] 基线: 余额=" + before[0].toPlainString()
                + " 流水累计=" + before[1].toPlainString());

        // 人为把余额改高 1（不写流水）——模拟「账实不符」
        jdbc.update("UPDATE inventory_balances SET on_hand_qty = on_hand_qty + 1 "
                + "WHERE team_id = ? AND warehouse_id = ? AND sku_id = ?", TEAM7, WH7, SKU7);
        AssertionError drift = assertThrows(AssertionError.class,
                () -> assertInventoryReconciled(TEAM7, WH7, SKU7),
                "★ 对账断言必须能捕获账实差，否则该断言是空转的假绿");
        System.out.println("[M6T12-A4] 漂移被捕获: " + drift.getMessage());

        // 恢复：再写一条等额 INIT_IMPORT 流水与余额对齐（证明「余额可由流水推导」是双向可修的）
        jdbc.update("UPDATE inventory_balances SET on_hand_qty = ? "
                + "WHERE team_id = ? AND warehouse_id = ? AND sku_id = ?",
                SEED_ON_HAND, TEAM7, WH7, SKU7);
        BigDecimal[] after = assertInventoryReconciled(TEAM7, WH7, SKU7);
        System.out.println("[M6T12-A4] 恢复后: 余额=" + after[0].toPlainString()
                + " 流水累计=" + after[1].toPlainString());
        assertTrue(after[0].compareTo(after[1]) == 0, "恢复后必须一致");
    }

    @Test
    @DisplayName("A4 采购单三动作全程不写库存（INV-03）：流水条数与余额均不变")
    void a4PurchaseOrderActionsDoNotTouchInventory() throws Exception {
        String token = login("approver01");
        PoSeed po = createPurchaseOrder("DRAFT");
        BigDecimal[] before = assertInventoryReconciled(TEAM7, WH7, SKU7);
        long txnBefore = countOf("inventory_transactions");

        assertEquals(200, post(ORDERS + "/" + po.id() + "/confirm",
                "{\"expected_version\":" + po.version() + "}", token, null).getStatusCode().value());
        assertEquals(200, post(ORDERS + "/" + po.id() + "/order",
                "{\"expected_version\":" + (po.version() + 1) + "}", token, null).getStatusCode().value());
        assertEquals(200, post(ORDERS + "/" + po.id() + "/cancel",
                "{\"reason\":\"计划调整\"}", token, null).getStatusCode().value());

        BigDecimal[] after = assertInventoryReconciled(TEAM7, WH7, SKU7);
        long txnAfter = countOf("inventory_transactions");
        System.out.println("[M6T12-A4] INV-03: on_hand " + before[0].toPlainString() + " -> "
                + after[0].toPlainString() + " | inventory_transactions " + txnBefore + " -> " + txnAfter);
        assertEquals(0, before[0].compareTo(after[0]), "on_hand_qty 不得变化");
        assertEquals(txnBefore, txnAfter, "confirm/order/cancel 不得写库存流水");
    }

    // =====================================================================
    // 辅助：驱动
    // =====================================================================

    private ResponseEntity<String> drive(Driver driver, PoSeed po, String token, String receiptQty) {
        return switch (driver) {
            case CONFIRM -> post(ORDERS + "/" + po.id() + "/confirm",
                    "{\"expected_version\":" + po.version() + "}", token, null);
            case ORDER -> post(ORDERS + "/" + po.id() + "/order",
                    "{\"expected_version\":" + po.version() + "}", token, null);
            case CANCEL -> post(ORDERS + "/" + po.id() + "/cancel",
                    "{\"reason\":\"表驱动取消\"}", token, null);
            case RECEIPT -> postReceipt(po, token, receiptQty);
        };
    }

    /**
     * {@code POST /receipts}：登记一张<b>数量由参数给定</b>的到货草稿（确认由 T12b 的调用方续做）。
     *
     * <p>★ 数量必须参数化（B0-8）：写死 {@code 10.000} 会让「→ RECEIVED」两行永远收不满。
     */
    private ResponseEntity<String> postReceipt(PoSeed po, String token, String receiptQty) {
        String body = "{\"purchase_order_id\":" + po.id()
                + ",\"warehouse_id\":" + WH7
                + ",\"receipt_date\":\"2026-10-20\""
                + ",\"items\":[{\"purchase_order_item_id\":" + po.lineId()
                + ",\"received_qty\":\"" + receiptQty + "\",\"unit_price\":\"10.0000\"}]}";
        return post(RECEIPTS, body, token, "IDEM-RCPT-" + TEAM7 + "-" + (++idemSeq));
    }

    private ResponseEntity<String> driveReplenishment(String action, long id, String token) {
        return switch (action) {
            case "submit" -> post(RPL + "/" + id + "/submit", "{\"comment\":\"表驱动提交\"}", token, null);
            case "withdraw" -> post(RPL + "/" + id + "/withdraw", "{\"comment\":\"表驱动撤回\"}", token, null);
            case "approve" -> post(RPL + "/" + id + "/approve",
                    "{\"expected_version\":0,\"comment\":\"同意\"}", token, null);
            case "reject" -> post(RPL + "/" + id + "/reject",
                    "{\"expected_version\":0,\"comment\":\"不符合补货条件\"}", token, null);
            case "cancel" -> post(RPL + "/" + id + "/cancel", "{\"comment\":\"表驱动作废\"}", token, null);
            default -> throw new IllegalArgumentException("未知动作：" + action);
        };
    }

    // =====================================================================
    // 辅助：种子数据
    // =====================================================================

    /** 采购单种子（含 1 条明细）；明细 {@code received_qty} 与单头状态保持一致（B0-11）。 */
    private PoSeed createPurchaseOrder(String status) {
        BigDecimal ordered = new BigDecimal("100.000");
        BigDecimal received = switch (status) {
            case "PARTIALLY_RECEIVED" -> new BigDecimal("40.000");
            case "RECEIVED" -> new BigDecimal("100.000");
            default -> new BigDecimal("0.000");
        };
        int version = 0;
        String poNo = "PO-SM-" + String.format("%04d", ++poSeq);

        jdbc.update("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, status, "
                        + "source, total_qty, total_amount, received_qty, version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, 1000.00, ?, ?, ?, ?)",
                TEAM7, poNo, SUPPLIER7, WH7, status, ordered, received, version, APPROVER_ID, APPROVER_ID);
        long poId = jdbc.queryForObject(
                "SELECT id FROM purchase_orders WHERE po_no = ?", Long.class, poNo);

        jdbc.update("INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, "
                        + "product_id, ordered_qty, received_qty, unit_price, amount, version, "
                        + "created_by, updated_by) VALUES (?, ?, 1, ?, ?, ?, ?, 10.0000, 1000.00, 0, ?, ?)",
                TEAM7, poId, SKU7, PRODUCT7, ordered, received, APPROVER_ID, APPROVER_ID);
        long lineId = jdbc.queryForObject(
                "SELECT id FROM purchase_order_items WHERE purchase_order_id = ? ORDER BY line_no",
                Long.class, poId);
        return new PoSeed(poId, lineId, version);
    }

    /** 补货建议种子（含分析链）；每个建议独立 run ⇒ 满足 {@code uk_repl_run_sku_wh}。 */
    private long createReplenishmentItem(String status) {
        String runNo = "RUN-SM-" + (++runSeq);
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, algorithm_version) "
                + "VALUES (?, ?, 'SUCCEEDED', 'ALL_ACTIVE', 'avg-ma-3sigma-v1')", TEAM7, runNo);
        long runId = jdbc.queryForObject(
                "SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);

        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, "
                        + "qty_suggest, safety_stock_qty) VALUES (?, ?, ?, ?, ?, 8.000, 'HIGH', 'SKU', "
                        + "6.250, 104.500, 110.000, 25.000)",
                TEAM7, runId, SKU7, PRODUCT7, WH7);
        long sourceId = jdbc.queryForObject(
                "SELECT id FROM analysis_items WHERE run_id = ?", Long.class, runId);

        jdbc.update("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                        + "product_id, warehouse_id, status, suggested_qty, qty, risk_level, version, "
                        + "created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, 40.000, 40.000, 'HIGH', 0, ?, ?)",
                TEAM7, sourceId, runId, SKU7, PRODUCT7, WH7, status, APPROVER_ID, APPROVER_ID);
        return jdbc.queryForObject(
                "SELECT id FROM replenishment_items WHERE run_id = ?", Long.class, runId);
    }

    /** 采购单单头 + 明细的种子句柄（{@code version} 为插入时的乐观锁版本）。 */
    private record PoSeed(long id, long lineId, int version) {
    }

    // =====================================================================
    // 辅助：读取与 HTTP
    // =====================================================================

    private String poStatus(long poId) {
        return jdbc.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, poId);
    }

    private int poVersion(long poId) {
        return jdbc.queryForObject("SELECT version FROM purchase_orders WHERE id = ?", Integer.class, poId);
    }

    private String rplStatus(long itemId) {
        return jdbc.queryForObject("SELECT status FROM replenishment_items WHERE id = ?", String.class, itemId);
    }

    private long reviewCount(long itemId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM replenishment_reviews WHERE replenishment_item_id = ?",
                Long.class, itemId);
    }

    private long countOf(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    /** 到货单状态（按<b>精确 ID</b> 查；禁用 {@code ORDER BY id DESC LIMIT 1} 这类隐式定位）。 */
    private String receiptStatus(long receiptId) {
        return jdbc.queryForObject(
                "SELECT status FROM goods_receipts WHERE id = ?", String.class, receiptId);
    }

    /** 指定 {@code (warehouse, sku)} 维度下的入库类流水条数（种子 {@code INIT_IMPORT} + {@code PURCHASE_INBOUND}）。 */
    private long countInboundTxns(long warehouseId, long skuId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transactions WHERE warehouse_id = ? AND sku_id = ? "
                        + "AND txn_type IN ('INIT_IMPORT', 'PURCHASE_INBOUND')",
                Long.class, warehouseId, skuId);
    }

    /** 指定到货单产生的 {@code PURCHASE_INBOUND} 流水条数（{@code biz_ref_id} = receiptId）。 */
    private long countPurchaseInbound(long receiptId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transactions "
                        + "WHERE txn_type = 'PURCHASE_INBOUND' AND biz_ref_id = ?",
                Long.class, receiptId);
    }

    /** ★ B0-12：审计计数一律 {@code biz_id} + {@code action} 差分，绝不全表计数（{@code /auth/login} 也写审计）。 */
    private long auditCount(long bizId, String action) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE biz_id = ? AND action = ?", Long.class, bizId, action);
    }

    private String codeOf(ResponseEntity<String> res) throws Exception {
        JsonNode body = json.readTree(res.getBody());
        JsonNode code = body.get("code");
        return code == null ? null : code.asText();
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

    private ResponseEntity<String> post(String path, String body, String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
