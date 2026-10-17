package com.shoppilot.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.sales.application.SalesQueryService;
import com.shoppilot.sales.application.dto.SalesSeriesPoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

/**
 * M3-T12 {@code SalesQueryService} 端到端集成测试 —— 真栈（Spring + Testcontainers + 真实 SQL）。
 *
 * <p>重点覆盖 {@code MODULE_BOUNDARIES §2.6} 的 {@code getSeries}：该方法是<b>纯服务接口</b>
 * （供 {@code analysis} 组装给 Python，<b>无 HTTP 端点</b>），无法用网关 curl 取证，故在此直接断言
 * 「按 {@code sale_date} <b>升序</b>」与区间过滤语义。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SalesQueryServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;
    private static final long PRODUCT_ID = 9001L;
    private static final long SKU_A = 9002L;
    private static final long WAREHOUSE_ID = 9004L;

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private SalesQueryService queryService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM sales_daily");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                + "VALUES (?, 'operator01', 'o@example.com', 'x', 'operator01', 'ACTIVE')", OPERATOR_ID);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OPERATOR_ID);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) VALUES "
                + "(?, ?, 'P-BAG-001', '箱包', 'ACTIVE')", PRODUCT_ID, TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                + "(?, ?, ?, 'SKU-A', 'SKU-A', 'ACTIVE')", SKU_A, TEAM_ID, PRODUCT_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) VALUES "
                + "(?, ?, 'WH-1', '一号仓', 'ACTIVE')", WAREHOUSE_ID, TEAM_ID);

        // ★ 故意乱序插入：10-03 → 10-01 → 10-02，用于验证 getSeries 的 SQL 升序（而非插入序）
        insertDaily("2026-10-03", "30");
        insertDaily("2026-10-01", "10");
        insertDaily("2026-10-02", "20");

        TeamContext.set(new TeamContext.Snapshot(TEAM_ID, List.of("OWNER"), OPERATOR_ID, "operator01"));
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    @Test
    @DisplayName("getSeries：返回按 sale_date 升序的销量序列（乱序插入不影响）")
    void getSeriesReturnsAscendingByDate() {
        List<SalesSeriesPoint> series = queryService.getSeries(SKU_A, WAREHOUSE_ID, null, null);

        assertEquals(3, series.size());
        assertEquals(LocalDate.parse("2026-10-01"), series.get(0).getSaleDate(), "首点应为 10-01");
        assertEquals(LocalDate.parse("2026-10-02"), series.get(1).getSaleDate(), "次点应为 10-02");
        assertEquals(LocalDate.parse("2026-10-03"), series.get(2).getSaleDate(), "末点应为 10-03");
        assertEquals(0, new BigDecimal("10").compareTo(series.get(0).getQty()));
        assertEquals(0, new BigDecimal("20").compareTo(series.get(1).getQty()));
        assertEquals(0, new BigDecimal("30").compareTo(series.get(2).getQty()));
    }

    @Test
    @DisplayName("getSeries：按 [fromDate, toDate] 区间过滤（含端点），仍升序")
    void getSeriesFiltersByRange() {
        List<SalesSeriesPoint> series = queryService.getSeries(SKU_A, WAREHOUSE_ID,
                LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-03"));

        assertEquals(2, series.size());
        assertEquals(LocalDate.parse("2026-10-02"), series.get(0).getSaleDate());
        assertEquals(LocalDate.parse("2026-10-03"), series.get(1).getSaleDate());
    }

    @Test
    @DisplayName("getSeries：sku_id 缺失 → COM-0001")
    void getSeriesRequiresSku() {
        BizException ex = assertThrows(BizException.class,
                () -> queryService.getSeries(null, WAREHOUSE_ID, null, null));
        assertEquals(ErrorCodes.COM_0001, ex.getErrorCode());
    }

    @Test
    @DisplayName("getSeries：fromDate > toDate → COM-0007")
    void getSeriesRejectsInvertedRange() {
        BizException ex = assertThrows(BizException.class, () -> queryService.getSeries(
                SKU_A, WAREHOUSE_ID, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-01")));
        assertEquals(ErrorCodes.COM_0007, ex.getErrorCode());
    }

    private void insertDaily(String saleDate, String qty) {
        jdbc.update("INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, "
                        + "qty, return_qty, sales_amount, source) VALUES (?, ?, ?, ?, ?, ?, 0, NULL, 'IMPORT')",
                TEAM_ID, WAREHOUSE_ID, SKU_A, PRODUCT_ID, saleDate, new BigDecimal(qty));
    }
}
