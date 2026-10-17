package com.shoppilot.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.sales.application.SalesWriteService;
import com.shoppilot.sales.application.dto.SalesDailyResponse;
import com.shoppilot.sales.application.dto.SalesDailyUpsertRow;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/**
 * M3-T12 {@code SalesWriteService} 端到端集成测试 —— 真栈
 * （Spring 上下文 + Testcontainers {@code mysql:8.0.36} + 真实 SQL + 真实租户线 + 真实审计）。
 *
 * <p><b>断言纪律</b>：所有「是否落库」的断言一律查<b>真实表</b>（{@code sales_daily} / {@code audit_logs}），
 * <b>绝不</b>用 mock Mapper 反推。团队上下文由 {@link TeamContext#set} 注入（等价于 JWT 过滤器），
 * 以便直接调用服务层（无需 HTTP）。
 *
 * <p><b>为什么不加 {@code @Transactional}</b>：服务方法的提交语义正是被测对象（upsert 覆盖、
 * 审计 REQUIRES_NEW），测试类若自带事务会掩盖真实提交行为。故每个用例前用 {@code DELETE} 重建种子。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SalesWriteServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OPERATOR_ID = 1001L;

    private static final long PRODUCT_ID = 9001L;
    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;
    private static final long WAREHOUSE_ID = 9004L;

    private static final long OTHER_PRODUCT_ID = 9101L;
    private static final long OTHER_SKU_ID = 9102L;
    private static final long OTHER_WAREHOUSE_ID = 9103L;

    private static final LocalDate DATE = LocalDate.parse("2026-10-01");

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private SalesWriteService writeService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM sales_daily");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);

        // 操作者（审计 operator_id 快照；显式建号以兼容任何逻辑关联）
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                + "VALUES (?, 'operator01', 'operator01@example.com', 'x', 'operator01', 'ACTIVE')", OPERATOR_ID);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OPERATOR_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) VALUES "
                + "(?, ?, 'P-BAG-001', '箱包', 'ACTIVE'), (?, ?, 'P-OTHER-001', '别家商品', 'ACTIVE')",
                PRODUCT_ID, TEAM_ID, OTHER_PRODUCT_ID, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                + "(?, ?, ?, 'SKU-A', 'SKU-A', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-B', 'SKU-B', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-OTHER', 'SKU-OTHER', 'ACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_ID,
                SKU_B, TEAM_ID, PRODUCT_ID,
                OTHER_SKU_ID, OTHER_TEAM_ID, OTHER_PRODUCT_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) VALUES "
                + "(?, ?, 'WH-MAIN', '主仓', 'ACTIVE'), (?, ?, 'WH-OTHER', '别家仓', 'ACTIVE')",
                WAREHOUSE_ID, TEAM_ID, OTHER_WAREHOUSE_ID, OTHER_TEAM_ID);

        TeamContext.set(new TeamContext.Snapshot(TEAM_ID, List.of("OWNER"), OPERATOR_ID, "operator01"));
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    // =====================================================================
    // ① upsert 幂等（覆盖，非累加）
    // =====================================================================

    @Test
    @DisplayName("① upsert 幂等：同 (wh,sku,date) 两次写入 → 仍 1 行，qty 为新值（U-6 覆盖）")
    void upsertIsIdempotentOverwrite() {
        SalesDailyResponse first = writeService.upsertDaily(
                SKU_A, WAREHOUSE_ID, DATE, new BigDecimal("10"), new BigDecimal("100.00"), "k1");
        assertEquals(1L, countRows());

        SalesDailyResponse second = writeService.upsertDaily(
                SKU_A, WAREHOUSE_ID, DATE, new BigDecimal("7"), new BigDecimal("70.00"), "k2");

        assertEquals(1L, countRows(), "同业务键第二次写入必须覆盖，行数仍为 1");
        assertEquals(first.getId(), second.getId(), "覆盖应复用同一行 id");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT qty, return_qty, sales_amount, source FROM sales_daily WHERE id = ?", second.getId());
        assertEquals(0, new BigDecimal("7").compareTo((BigDecimal) row.get("qty")), "qty 应为新值 7");
        assertEquals(0, new BigDecimal("70").compareTo((BigDecimal) row.get("sales_amount")),
                "sales_amount 应为新值 70");
        assertEquals("MANUAL", row.get("source"), "手工 upsert 的 source 应为 MANUAL");
    }

    // =====================================================================
    // ② qty 为负 → SAL-6002
    // =====================================================================

    @Test
    @DisplayName("② qty < 0 → SAL-6002（400）")
    void negativeQtyRejected() {
        BizException ex = assertThrows(BizException.class, () -> writeService.upsertDaily(
                SKU_A, WAREHOUSE_ID, DATE, new BigDecimal("-1"), null, "k"));
        assertEquals(ErrorCodes.SAL_6002, ex.getErrorCode());
        assertEquals(0L, countRows(), "被拒绝的写入不应落行");
    }

    // =====================================================================
    // ③ sale_date 缺失 / 非法 → SAL-6003
    // =====================================================================

    @Test
    @DisplayName("③ sale_date 缺失 → SAL-6003（400）")
    void nullSaleDateRejected() {
        BizException ex = assertThrows(BizException.class, () -> writeService.upsertDaily(
                SKU_A, WAREHOUSE_ID, null, BigDecimal.ONE, null, "k"));
        assertEquals(ErrorCodes.SAL_6003, ex.getErrorCode());
        assertEquals(0L, countRows());
    }

    // =====================================================================
    // ④ 跨团队 SKU → SAL-6005
    // =====================================================================

    @Test
    @DisplayName("④ 跨团队 SKU → SAL-6005（422）")
    void crossTeamSkuRejected() {
        BizException ex = assertThrows(BizException.class, () -> writeService.upsertDaily(
                OTHER_SKU_ID, WAREHOUSE_ID, DATE, BigDecimal.ONE, null, "k"));
        assertEquals(ErrorCodes.SAL_6005, ex.getErrorCode());
        assertEquals(0L, countRows());
    }

    // =====================================================================
    // ⑤ 跨团队仓库 → SAL-6005
    // =====================================================================

    @Test
    @DisplayName("⑤ 跨团队仓库 → SAL-6005（422）")
    void crossTeamWarehouseRejected() {
        BizException ex = assertThrows(BizException.class, () -> writeService.upsertDaily(
                SKU_A, OTHER_WAREHOUSE_ID, DATE, BigDecimal.ONE, null, "k"));
        assertEquals(ErrorCodes.SAL_6005, ex.getErrorCode());
        assertEquals(0L, countRows());
    }

    // =====================================================================
    // ⑥ batchUpsert：多行写入 + 一条审计（记覆盖行数）
    // =====================================================================

    @Test
    @DisplayName("⑥ batchUpsert：多行写入成功，source=IMPORT，写 1 条 SALES_DAILY_UPSERT 审计")
    void batchUpsertWritesAllAndAudits() {
        List<SalesDailyUpsertRow> rows = List.of(
                new SalesDailyUpsertRow(SKU_A, WAREHOUSE_ID, DATE, new BigDecimal("10"), BigDecimal.ZERO,
                        new BigDecimal("100.00"), null, "IMPORT", 501L, "imp-1"),
                new SalesDailyUpsertRow(SKU_B, WAREHOUSE_ID, DATE, new BigDecimal("5"), BigDecimal.ZERO,
                        new BigDecimal("50.00"), null, "IMPORT", 501L, "imp-2"));

        List<SalesDailyResponse> result = writeService.batchUpsert(rows);

        assertEquals(2, result.size(), "两行都应写入");
        assertEquals(2L, countRows());
        assertEquals("IMPORT", jdbc.queryForObject(
                "SELECT source FROM sales_daily WHERE sku_id = ?", String.class, SKU_A), "batch 的 source 应为 IMPORT");
        assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE action = 'SALES_DAILY_UPSERT'", Long.class),
                "一次 batchUpsert 应写 1 条 SALES_DAILY_UPSERT 审计（记覆盖行数）");
        String summary = jdbc.queryForObject(
                "SELECT change_summary FROM audit_logs WHERE action = 'SALES_DAILY_UPSERT'", String.class);
        assertTrue(summary != null && summary.contains("覆盖 0 行"),
                "首次写入覆盖行数应为 0，实测：" + summary);
    }

    // =====================================================================
    // ⑦ 覆盖可把可空列写成 NULL（LambdaUpdateWrapper 显式 set）
    // =====================================================================

    @Test
    @DisplayName("⑦ 覆盖可把 sales_amount 写成 NULL（避免 NOT_NULL 策略跳过 null）")
    void overwriteCanNullOutAmount() {
        writeService.upsertDaily(SKU_A, WAREHOUSE_ID, DATE, new BigDecimal("10"), new BigDecimal("100.00"), "k1");
        writeService.upsertDaily(SKU_A, WAREHOUSE_ID, DATE, new BigDecimal("8"), null, "k2");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT qty, sales_amount FROM sales_daily WHERE sku_id = ?", SKU_A);
        assertEquals(0, new BigDecimal("8").compareTo((BigDecimal) row.get("qty")));
        assertNull(row.get("sales_amount"), "用 null 覆盖旧金额必须生效（U-6 覆盖语义）");
    }

    // =====================================================================
    // ⑧ product_id 由 skus.product_id 派生（冗余列永远正确）
    // =====================================================================

    @Test
    @DisplayName("⑧ product_id 由 skus.product_id 派生（冗余列永远正确，§5.11）")
    void productIdDerivedFromSku() {
        writeService.upsertDaily(SKU_A, WAREHOUSE_ID, DATE, BigDecimal.ONE, null, "k");
        Long productId = jdbc.queryForObject(
                "SELECT product_id FROM sales_daily WHERE sku_id = ?", Long.class, SKU_A);
        assertEquals(PRODUCT_ID, productId, "product_id 应等于 skus.product_id");
    }

    // =====================================================================
    // ⑨ R68(a) sale_date 晚于今天 → SAL-6003
    // =====================================================================

    @Test
    @DisplayName("⑨ sale_date 晚于今天 → SAL-6003（R68(a)），零落库")
    void futureSaleDateRejected() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        BizException ex = assertThrows(BizException.class, () -> writeService.upsertDaily(
                SKU_A, WAREHOUSE_ID, tomorrow, BigDecimal.ONE, null, "k-future"));
        assertEquals(ErrorCodes.SAL_6003, ex.getErrorCode(), "未来日期必须是 SAL-6003（ERROR_CODES §2.7）");
        assertEquals(0L, countRows(), "未来日期被拒绝后不得落库（否则会污染 D 的 28 天窗口）");
    }

    // =====================================================================
    // ⑩ R68(b) sale_date 早于上架日 → SAL-6003
    // =====================================================================

    @Test
    @DisplayName("⑩ sale_date 早于 skus.listed_date → SAL-6003（R68(b)），零落库；上架日当天不得误杀")
    void saleDateBeforeListedDateRejected() {
        // 种子里的 SKU 未设 listed_date（NULL ⇒ 只校验「晚于今天」）；本用例显式设一个过去的上架日
        LocalDate listedDate = LocalDate.now().minusDays(10);
        jdbc.update("UPDATE skus SET listed_date = ? WHERE id = ?", listedDate, SKU_A);

        BizException ex = assertThrows(BizException.class, () -> writeService.upsertDaily(
                SKU_A, WAREHOUSE_ID, listedDate.minusDays(1), BigDecimal.ONE, null, "k-early"));
        assertEquals(ErrorCodes.SAL_6003, ex.getErrorCode(), "早于上架日必须是 SAL-6003（ERROR_CODES §2.7）");
        assertEquals(0L, countRows(), "早于上架日被拒绝后不得落库");

        // 正例对照：恰在上架日（未早于 listed_date，也未晚于今天）必须放行——证明不是误杀
        SalesDailyResponse ok = writeService.upsertDaily(
                SKU_A, WAREHOUSE_ID, listedDate, BigDecimal.ONE, null, "k-listed");
        assertEquals(listedDate, ok.getSaleDate(), "边界日（= listed_date）应被接受");
        assertEquals(1L, countRows());
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private long countRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales_daily", Long.class);
    }
}
