package com.shoppilot.inventory;

import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.inventory.application.InventoryInTransitQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code M6-T10} · 在途派生集成测试（真实 MySQL + 真实租户线 + 真实 SQL）。
 *
 * <p>覆盖 {@code DESIGN_BASIS §10.4} 的五条口径与 {@code R178} 的两条硬要求：
 * <ol>
 *   <li><b>状态过滤</b>：{@code {PENDING, ORDERED, PARTIALLY_RECEIVED}} 计入，{@code DRAFT/RECEIVED/CANCELLED} 不计；</li>
 *   <li><b>剩余量</b>：{@code ordered_qty − received_qty}；</li>
 *   <li><b>到期日</b>：{@code COALESCE(行级, 单头)}，双空 ⇒ 计入；</li>
 *   <li><b>窗口</b>：{@code arrival <= windowEnd}（含等号），逐 SKU 不同；</li>
 *   <li><b>物化列退役</b>：查询既不读也不写 {@code inventory_balances.in_transit_qty}。</li>
 * </ol>
 *
 * <p>★ 日期一律相对 {@code today}（{@code ZoneOffset.UTC}），不写死绝对日期——否则过几天必红。
 */
@SpringBootTest(classes = ShopPilotApplication.class)
@Testcontainers
class InTransitDerivationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final long TEAM = 7L;
    private static final long OTHER_TEAM = 8L;
    private static final long WH = 9004L;
    private static final long OTHER_WH = 9103L;
    private static final long SUPPLIER = 9201L;
    private static final long PRODUCT = 9001L;

    private static final long OPERATOR_ID = 1001L;

    /** I1 状态过滤。 */
    private static final long SKU_STATUS = 9002L;
    /** I2 窗口边界（in time / late）。 */
    private static final long SKU_IN_TIME = 9003L;
    private static final long SKU_LATE = 9005L;
    /** I2 到期日 NULL。 */
    private static final long SKU_NO_DATE = 9006L;
    /** I3 部分收货。 */
    private static final long SKU_PARTIAL = 9007L;
    /** I4 行级覆盖单头。 */
    private static final long SKU_OVERRIDE = 9008L;
    /** I5 未过滤合计。 */
    private static final long SKU_RAW = 9009L;
    /** I6 零读写探针。 */
    private static final long SKU_ZERO_WRITE = 9010L;

    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private int poSeq;

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
    private InventoryInTransitQueryService inTransitQueryService;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM goods_receipt_items");
        jdbc.execute("DELETE FROM goods_receipts");
        jdbc.execute("DELETE FROM purchase_order_items");
        jdbc.execute("DELETE FROM purchase_orders");
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM replenishment_policies");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM teams");
        poSeq = 0;

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-T10-7', '在途七团', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-T10-8', '在途八团', 'ACTIVE', 'Asia/Shanghai')", TEAM, OTHER_TEAM);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-T10', '在途商品')", PRODUCT, TEAM);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, is_default) VALUES "
                + "(?, ?, 'WH-T10', '在途仓', 1), (?, ?, 'WH-T10-X', '他团仓', 1)",
                WH, TEAM, OTHER_WH, OTHER_TEAM);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-T10', '在途供应商')", SUPPLIER, TEAM);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                + "(?, ?, ?, 'SKU-ST', '状态', 'ACTIVE'), (?, ?, ?, 'SKU-IN', '窗口内', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-LT', '窗口外', 'ACTIVE'), (?, ?, ?, 'SKU-ND', '无日期', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-PT', '部分收货', 'ACTIVE'), (?, ?, ?, 'SKU-OV', '行级覆盖', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-RW', '原始', 'ACTIVE'), (?, ?, ?, 'SKU-ZW', '零写', 'ACTIVE')",
                SKU_STATUS, TEAM, PRODUCT, SKU_IN_TIME, TEAM, PRODUCT,
                SKU_LATE, TEAM, PRODUCT, SKU_NO_DATE, TEAM, PRODUCT,
                SKU_PARTIAL, TEAM, PRODUCT, SKU_OVERRIDE, TEAM, PRODUCT,
                SKU_RAW, TEAM, PRODUCT, SKU_ZERO_WRITE, TEAM, PRODUCT);

        TeamContext.set(TeamContext.Snapshot.of(TEAM, List.of("OPERATOR"), OPERATOR_ID, "operator01"));
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    // =====================================================================
    // I1 状态过滤
    // =====================================================================
    @Test
    @DisplayName("I1 状态过滤：仅 PENDING / ORDERED / PARTIALLY_RECEIVED 计入（六状态各一行）")
    void onlyInTransitStatusesCounted() {
        for (String status : List.of("DRAFT", "PENDING", "ORDERED", "PARTIALLY_RECEIVED", "RECEIVED", "CANCELLED")) {
            long poId = insertPo(status, null);
            // received_qty 必须 <= ordered_qty（ck_poi_received_range）；这里 received 恒 0，排除数量因素
            insertLine(poId, 1, SKU_STATUS, "10.000", "0.000", null);
        }

        Map<Long, BigDecimal> effective =
                inTransitQueryService.batchGetEffectiveInTransit(TEAM, WH, window(SKU_STATUS, today.plusDays(14)));

        assertEquals(0, new BigDecimal("30.000").compareTo(effective.get(SKU_STATUS)),
                "PENDING + ORDERED + PARTIALLY_RECEIVED 各 10 ⇒ 30；DRAFT/RECEIVED/CANCELLED 不计，实际="
                        + effective.get(SKU_STATUS));
    }

    // =====================================================================
    // I2 窗口边界（含等号 / 到期日 NULL）
    // =====================================================================
    @Test
    @DisplayName("I2 窗口边界：today+14 计入、today+15 不计、NULL 计入（窗口末日 today+14）")
    void windowBoundaryAndNullArrival() {
        long poIn = insertPo("ORDERED", null);
        insertLine(poIn, 1, SKU_IN_TIME, "10.000", "0.000", today.plusDays(14));
        long poLate = insertPo("ORDERED", null);
        insertLine(poLate, 1, SKU_LATE, "10.000", "0.000", today.plusDays(15));
        long poNoDate = insertPo("ORDERED", null);
        insertLine(poNoDate, 1, SKU_NO_DATE, "10.000", "0.000", null);

        Map<Long, BigDecimal> effective = inTransitQueryService.batchGetEffectiveInTransit(TEAM, WH,
                Map.of(SKU_IN_TIME, today.plusDays(14),
                        SKU_LATE, today.plusDays(14),
                        SKU_NO_DATE, today.plusDays(14)));

        assertEquals(0, new BigDecimal("10.000").compareTo(effective.get(SKU_IN_TIME)),
                "arrival == windowEnd 必须计入（含等号）");
        assertFalse(effective.containsKey(SKU_LATE), "arrival = windowEnd + 1 不得计入");
        assertEquals(0, new BigDecimal("10.000").compareTo(effective.get(SKU_NO_DATE)),
                "行级与单头到期日皆空 ⇒ 保守计入");
    }

    // =====================================================================
    // I3 部分收货剩量
    // =====================================================================
    @Test
    @DisplayName("I3 部分收货：ordered 100 / received 40 ⇒ 在途 60（不是 100）")
    void partialReceiptUsesRemaining() {
        long poId = insertPo("PARTIALLY_RECEIVED", null);
        insertLine(poId, 1, SKU_PARTIAL, "100.000", "40.000", null);

        Map<Long, BigDecimal> effective =
                inTransitQueryService.batchGetEffectiveInTransit(TEAM, WH, window(SKU_PARTIAL, today.plusDays(30)));

        assertEquals(0, new BigDecimal("60.000").compareTo(effective.get(SKU_PARTIAL)),
                "剩余在途 = ordered − received，实际=" + effective.get(SKU_PARTIAL));
    }

    // =====================================================================
    // I4 行级到期日覆盖单头
    // =====================================================================
    @Test
    @DisplayName("I4 行级到期日覆盖单头：行级在窗口内/外决定去留，与单头无关")
    void itemArrivalOverridesHeader() {
        // 单头远（today+90），行级近（today+1）⇒ 计入
        long poItemNear = insertPo("ORDERED", today.plusDays(90));
        insertLine(poItemNear, 1, SKU_OVERRIDE, "10.000", "0.000", today.plusDays(1));
        // 单头近（today+1），行级远（today+90）⇒ 不计
        long poItemFar = insertPo("ORDERED", today.plusDays(1));
        insertLine(poItemFar, 1, SKU_LATE, "10.000", "0.000", today.plusDays(90));

        Map<Long, BigDecimal> effective = inTransitQueryService.batchGetEffectiveInTransit(TEAM, WH,
                Map.of(SKU_OVERRIDE, today.plusDays(7), SKU_LATE, today.plusDays(7)));

        assertEquals(0, new BigDecimal("10.000").compareTo(effective.get(SKU_OVERRIDE)),
                "行级到期日优先于单头：单头远但行级近 ⇒ 计入");
        assertFalse(effective.containsKey(SKU_LATE), "单头近但行级远 ⇒ 不计");
    }

    // =====================================================================
    // I5 未过滤合计 ≥ 有效在途
    // =====================================================================
    @Test
    @DisplayName("I5 batchGetInTransit 不过滤窗口：含窗口外 40，且 ≥ 有效在途 10")
    void unfilteredIncludesOutOfWindow() {
        long poNear = insertPo("ORDERED", null);
        insertLine(poNear, 1, SKU_RAW, "10.000", "0.000", today.plusDays(1));
        long poFar = insertPo("ORDERED", null);
        insertLine(poFar, 1, SKU_RAW, "40.000", "0.000", today.plusDays(200));

        Map<Long, BigDecimal> raw = inTransitQueryService.batchGetInTransit(TEAM, WH, List.of(SKU_RAW));
        Map<Long, BigDecimal> effective =
                inTransitQueryService.batchGetEffectiveInTransit(TEAM, WH, window(SKU_RAW, today.plusDays(14)));

        assertEquals(0, new BigDecimal("50.000").compareTo(raw.get(SKU_RAW)), "原始在途 = 10 + 40");
        assertEquals(0, new BigDecimal("10.000").compareTo(effective.get(SKU_RAW)), "有效在途只含窗口内 10");
        assertEquals(1, raw.get(SKU_RAW).compareTo(effective.get(SKU_RAW)) > 0 ? 1 : 0);
    }

    // =====================================================================
    // I6 物化列零读零写
    // =====================================================================
    @Test
    @DisplayName("I6 物化列退役：余额列 99 不被读取（派生 10），且两次查询后该列仍为 99、非零行仍 1")
    void materializedColumnIsNeitherReadNorWritten() {
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty) VALUES (?, ?, ?, 10.000, 99.000, 0.000)",
                TEAM, WH, SKU_ZERO_WRITE);
        long poId = insertPo("ORDERED", null);
        insertLine(poId, 1, SKU_ZERO_WRITE, "10.000", "0.000", null);

        Map<Long, BigDecimal> effective = inTransitQueryService.batchGetEffectiveInTransit(
                TEAM, WH, window(SKU_ZERO_WRITE, today.plusDays(14)));
        Map<Long, BigDecimal> raw = inTransitQueryService.batchGetInTransit(TEAM, WH, List.of(SKU_ZERO_WRITE));

        assertEquals(0, new BigDecimal("10.000").compareTo(effective.get(SKU_ZERO_WRITE)),
                "有效在途来自采购派生，不得读余额列 99");
        assertEquals(0, new BigDecimal("10.000").compareTo(raw.get(SKU_ZERO_WRITE)));
        assertEquals(0, new BigDecimal("99.000").compareTo(jdbc.queryForObject(
                        "SELECT in_transit_qty FROM inventory_balances WHERE sku_id = ?",
                        BigDecimal.class, SKU_ZERO_WRITE)),
                "查询过程不得写该列（零写入）");
        assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_balances WHERE in_transit_qty <> 0", Integer.class),
                "全表非零行数仍为种下的 1 行 ⇒ 无新增写入");
    }

    // =====================================================================
    // 夹具
    // =====================================================================
    private Map<Long, LocalDate> window(long skuId, LocalDate windowEnd) {
        return Map.of(skuId, windowEnd);
    }

    private long insertPo(String status, LocalDate headerArrival) {
        String poNo = "PO-T10-" + String.format("%04d", ++poSeq);
        jdbc.update("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, status, source, "
                        + "expected_arrival_date, total_qty, total_amount, received_qty, version, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, 0.000, 0.00, 0.000, 0, ?)",
                TEAM, poNo, SUPPLIER, WH, status, headerArrival, OPERATOR_ID);
        return jdbc.queryForObject("SELECT id FROM purchase_orders WHERE po_no = ?", Long.class, poNo);
    }

    private void insertLine(long poId, int lineNo, long skuId, String ordered, String received,
                            LocalDate lineArrival) {
        jdbc.update("INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, product_id, "
                        + "ordered_qty, received_qty, unit_price, amount, expected_arrival_date, version, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 10.0000, 0.00, ?, 0, ?)",
                TEAM, poId, lineNo, skuId, PRODUCT, new BigDecimal(ordered), new BigDecimal(received),
                lineArrival, OPERATOR_ID);
    }
}
