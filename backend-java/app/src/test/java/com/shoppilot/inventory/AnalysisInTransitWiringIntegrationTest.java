package com.shoppilot.inventory;

import com.shoppilot.analysis.domain.AnalysisParams;
import com.shoppilot.analysis.domain.AnalysisScope;
import com.shoppilot.analysis.domain.AnalysisScopeType;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunItem;
import com.shoppilot.analysis.infrastructure.sampling.AnalysisItemAssembler;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.inventory.application.InventoryQueryService;
import com.shoppilot.inventory.application.dto.BalanceView;
import com.shoppilot.inventory.application.dto.InventoryPageQuery;
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

/**
 * {@code M6-T10} · 在途换源接线测试（{@code R178} + {@code R124} 的端到端证据）。
 *
 * <p>三个消费方在同一次夹具下对照，证明「口径分道」：
 * <ol>
 *   <li><b>A1 出站</b>：{@code AnalysisItemAssembler.assemble} 的 {@code effective_in_transit_qty}
 *       = <b>窗口内</b>合计（Near 15，Far 30 因 {@code today+100 > today+L+R} 被排除）；</li>
 *   <li><b>A2 快照</b>：{@code loadInTransitQty}（落库 {@code analysis_items.in_transit_qty} 的来源，
 *       {@code AnalysisResultWriteService:259}）= <b>不过滤</b>合计 45；</li>
 *   <li><b>A3 展示</b>：{@code InventoryQueryService.listBalances} 的 {@code in_transit_qty}
 *       同样取不过滤合计 45（不再是退役列的 0）。</li>
 * </ol>
 *
 * <p>★ A3 走 application 层而非 HTTP（{@code TeamContext} 直设）：HTTP 鉴权/契约层已由
 * {@code InventoryApiIntegrationTest} 覆盖，本类只验证「值从哪来」，避免重复搭建 JWT 链路。
 */
@SpringBootTest(classes = ShopPilotApplication.class)
@Testcontainers
class AnalysisInTransitWiringIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final long TEAM = 7L;
    private static final long WH = 9004L;
    private static final long SUPPLIER = 9201L;
    private static final long PRODUCT = 9001L;
    private static final long SKU = 9002L;
    private static final long OPERATOR_ID = 1001L;

    private static final int LEAD_TIME_DAYS = 7;
    private static final int COVERAGE_DAYS = 7;

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
    private AnalysisItemAssembler assembler;

    @Autowired
    private InventoryQueryService inventoryQueryService;

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
                + "(?, 'T-WIRE-7', '接线七团', 'ACTIVE', 'Asia/Shanghai')", TEAM);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name) VALUES "
                + "(?, ?, 'P-WIRE', '接线商品')", PRODUCT, TEAM);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, is_default, status) VALUES "
                + "(?, ?, 'WH-WIRE', '接线仓', 1, 'ACTIVE')", WH, TEAM);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-WIRE', '接线供应商')", SUPPLIER, TEAM);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                + "(?, ?, ?, 'SKU-WIRE', '接线规格', 'ACTIVE')", SKU, TEAM, PRODUCT);
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty) VALUES (?, ?, ?, 10.000, 0.000, 0.000)", TEAM, WH, SKU);

        // 窗口内：到期 today+3，剩余 20 − 5 = 15
        long poNear = insertPo(today.plusDays(3));
        insertLine(poNear, "20.000", "5.000", null);
        // 窗口外：到期 today+100，剩余 30（L+R = 14 天，远在窗外）
        long poFar = insertPo(today.plusDays(100));
        insertLine(poFar, "30.000", "0.000", null);

        TeamContext.set(TeamContext.Snapshot.of(TEAM, List.of("OPERATOR"), OPERATOR_ID, "operator01"));
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    @Test
    @DisplayName("A1 出站 effective_in_transit_qty = 窗口内合计 15（窗口外 30 被排除）")
    void assembledEffectiveInTransitUsesWindow() {
        AnalysisParams params = new AnalysisParams(28, 7, COVERAGE_DAYS, LEAD_TIME_DAYS,
                new BigDecimal("0.5"), new BigDecimal("1.0"), null, Boolean.TRUE);

        List<AiRunItem> items = assembler.assemble(TEAM, WH,
                new AnalysisScope(AnalysisScopeType.SELECTED_SKUS, List.of(SKU), null),
                today.minusDays(28), today, params);

        assertEquals(1, items.size());
        assertEquals("15.000", items.get(0).effectiveInTransitQty(),
                "★ 有效在途必须按 today + L + R = today+14 过滤：15 计入、30 排除");
        assertEquals(LEAD_TIME_DAYS, items.get(0).leadTimeDays(),
                "无策略/无 SKU 交期/无供应商交期 ⇒ 取 params.lead_time_default（不是硬编码 7 以外的值）");
        assertEquals("DEFAULT", items.get(0).leadTimeSource());
    }

    @Test
    @DisplayName("A2 快照 in_transit_qty = 不过滤合计 45（analysis_items 落库来源）")
    void snapshotInTransitIsUnfiltered() {
        Map<Long, BigDecimal> inTransit = assembler.loadInTransitQty(TEAM, WH, List.of(SKU));

        assertEquals(0, new BigDecimal("45.000").compareTo(inTransit.get(SKU)),
                "analysis_items.in_transit_qty 要的是「原始在途」快照，不应用到货窗口");
    }

    @Test
    @DisplayName("A3 余额列表 in_transit_qty = 派生 45（不再是退役列的 0）")
    void balanceViewInTransitComesFromDerivation() {
        PageResult<BalanceView> page = inventoryQueryService.listBalances(
                InventoryPageQuery.of(1, 20, List.of()), WH, SKU);

        assertEquals(1, page.getList().size());
        assertEquals(0, new BigDecimal("45.000").compareTo(page.getList().get(0).getInTransitQty()),
                "GET /inventory/balances 的 in_transit_qty 必须由 batchGetInTransit 填充（列已退役、恒 0）");
    }

    // =====================================================================
    // 夹具
    // =====================================================================
    private long insertPo(LocalDate headerArrival) {
        String poNo = "PO-WIRE-" + String.format("%04d", ++poSeq);
        jdbc.update("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, status, source, "
                        + "expected_arrival_date, total_qty, total_amount, received_qty, version, created_by) "
                        + "VALUES (?, ?, ?, ?, 'ORDERED', 'MANUAL', ?, 0.000, 0.00, 0.000, 0, ?)",
                TEAM, poNo, SUPPLIER, WH, headerArrival, OPERATOR_ID);
        return jdbc.queryForObject("SELECT id FROM purchase_orders WHERE po_no = ?", Long.class, poNo);
    }

    private void insertLine(long poId, String ordered, String received, LocalDate lineArrival) {
        jdbc.update("INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, product_id, "
                        + "ordered_qty, received_qty, unit_price, amount, expected_arrival_date, version, created_by) "
                        + "VALUES (?, ?, 1, ?, ?, ?, ?, 10.0000, 0.00, ?, 0, ?)",
                TEAM, poId, SKU, PRODUCT, new BigDecimal(ordered), new BigDecimal(received),
                lineArrival, OPERATOR_ID);
    }
}
