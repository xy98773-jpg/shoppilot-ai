package com.shoppilot.sales;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-T12 sales_daily 迁移 + 约束 / 索引 / 外键 —— 端到端集成测试。
 *
 * <p>用真实 MySQL 8.0.36（Testcontainers）执行真实迁移与<b>真实 SQL 约束</b>，断言
 * {@code DATABASE_DESIGN §5.11/§7.4} 与 {@code DATABASE_CONSTRAINTS #13/§3.2 #21–#24/§4.1/§4.2/§5.2}：
 * <ul>
 *   <li>Flyway 应用 {@code V1.0.0} → {@code V1.0.13} 共十四条迁移，{@code sales_daily} 存在；</li>
 *   <li>唯一键 {@code uk_sales_team_wh_sku_date} 在<b>团队内</b>冲突；跨团队 / 换日期允许；</li>
 *   <li>四条二级索引 {@code team_id} 最左；唯一键同样 {@code team_id} 最左；</li>
 *   <li>两条外键均 {@code RESTRICT}（删除被销售引用的 SKU 失败）；</li>
 *   <li>四条 CHECK 逐条负向验证（{@code ck_sales_source} / {@code ck_sales_qty_non_negative} /
 *       {@code ck_sales_return_non_negative} / {@code ck_sales_amount_non_negative}）；
 *       ★ 可空列 {@code sales_amount} 先判 {@code NULL}，{@code NULL} 合法、负数拒绝；</li>
 *   <li>{@code sales_daily} <b>无 version</b>（{@code §7.4} 表 B），集合 A 审计字段齐备。</li>
 * </ul>
 *
 * <p>不启动 Spring 上下文：手工装配 Flyway + {@link DriverManagerDataSource}，断言最终数据库状态。
 */
@Testcontainers
class SalesSchemaConstraintTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 四条二级索引（DATABASE_CONSTRAINTS §3.2 #21–#24）。 */
    private static final List<String> EXPECTED_INDEXES = List.of(
            "idx_sales_team_sku_date", "idx_sales_team_date",
            "idx_sales_team_product_date", "idx_sales_team_batch");

    private static DataSource dataSource;

    private long team7;
    private long team8;
    private long wh7;
    private long wh8;
    private long prod7;
    private long sku7a;
    private long sku7b;
    private long sku8;

    @BeforeAll
    static void setUp() {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        // 真实执行 Flyway 迁移链（V1.0.0 baseline → V1.0.9 analysis）
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }

    @BeforeEach
    void seed() throws Exception {
        execute("DELETE FROM sales_daily");
        execute("DELETE FROM skus");
        execute("DELETE FROM products");
        execute("DELETE FROM warehouses");
        execute("DELETE FROM team_members");
        execute("DELETE FROM teams");

        team7 = insertTeam("T-SAL-7", "销售团队七");
        team8 = insertTeam("T-SAL-8", "销售团队八");
        wh7 = insertWarehouse(team7, "WH-SAL-7");
        wh8 = insertWarehouse(team8, "WH-SAL-8");
        prod7 = insertProduct(team7, "P-SAL-7");
        sku7a = insertSku(team7, prod7, "SKU-SAL-7A");
        sku7b = insertSku(team7, prod7, "SKU-SAL-7B");
        sku8 = insertSku(team8, insertProduct(team8, "P-SAL-8"), "SKU-SAL-8");
    }

    // =====================================================================
    // ① 表存在 + Flyway 已应用到 1.0.9
    // =====================================================================

    @Test
    @DisplayName("① Flyway 全链到 V1.0.12：sales_daily 存在，十三条迁移均 success=1")
    void migrationAppliesThroughV109AndCreatesSalesTable() throws Exception {
        assertTrue(queryStrings("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE()").contains("sales_daily"),
                "sales_daily 表应存在");

        List<String> versions = queryStrings(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        assertEquals(List.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5", "1.0.6", "1.0.7", "1.0.8", "1.0.9", "1.0.10", "1.0.11", "1.0.12", "1.0.13"),
                versions,
                "应应用 V1.0.0 → V1.0.13 十四条迁移（V1.0.8 为 M3-T12 sales，"
                        + "V1.0.9 为 M4-T02 analysis，V1.0.10 为 M5-T01 replenishment，"
                        + "V1.0.11 为 M6-T01 purchasing，V1.0.12 为 M6-T02-D R165 锚点锁索引，V1.0.13 为 M8-T04 ADR-008 幂等键占位表）");
    }

    // =====================================================================
    // ② 唯一键 uk_sales_team_wh_sku_date（团队内冲突）
    // =====================================================================

    @Test
    @DisplayName("② uk_sales_team_wh_sku_date：同团队同 (wh,sku,date) 二次插入 → 冲突；换日期/跨团队允许")
    void uniqueKeyScopedToTeamAndDate() throws Exception {
        execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-01", "10", "0", "100.00", "IMPORT"));
        // 同键二次插入 → 冲突
        assertIntegrityViolation(() ->
                execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-01", "20", "0", "200.00", "IMPORT")));
        // 换日期 → 允许（粒度是「自然日」）
        execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-02", "20", "0", "200.00", "IMPORT"));
        // 跨团队同 (wh,sku,date) → 允许（唯一性只在团队内）
        execute(salesInsert(team8, wh8, sku8, insertProduct(team8, "P-SAL-8B"), "2026-10-01", "1", "0", "1.00", "MANUAL"));

        assertEquals(3L, queryLong("SELECT COUNT(*) FROM sales_daily"),
                "同团队同 (wh,sku,date) 只 1 行；换日期 + 跨团队各 1 行，共 3 行");
    }

    // =====================================================================
    // ③ 约束条数：唯一 1 / 外键 2 / CHECK 4 / 二级索引 4
    // =====================================================================

    @Test
    @DisplayName("③ 约束条数：唯一 1（uk_sales_team_wh_sku_date）/ 外键 2 / CHECK 4 / 二级索引 4")
    void constraintCountsAreExact() throws Exception {
        assertEquals(1L, queryLong("SELECT COUNT(DISTINCT constraint_name) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'sales_daily' "
                        + "AND constraint_type = 'UNIQUE'"),
                "唯一约束应恰 1 条（uk_sales_team_wh_sku_date）");
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'sales_daily' "
                        + "AND constraint_type = 'FOREIGN KEY'"),
                "外键应恰 2 条（fk_sales_warehouse / fk_sales_sku）");
        assertEquals(4L, queryLong("SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'sales_daily' "
                        + "AND constraint_type = 'CHECK'"),
                "CHECK 应恰 4 条（ck_sales_source / qty / return / amount）");
        // ★ 统计索引条数必须 COUNT(DISTINCT index_name)：statistics 按列展开，COUNT(*) 会多算复合索引
        assertEquals(4L, queryLong("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'sales_daily' "
                        + "AND index_name LIKE 'idx\\_%'"),
                "二级索引应恰 4 条（#21–#24）");
    }

    // =====================================================================
    // ④ 四条二级索引 + 唯一键 team_id 最左
    // =====================================================================

    @Test
    @DisplayName("④ 四条二级索引 + 唯一键：team_id 均为最左列（DESIGN_BASIS §6 规则 6）")
    void allIndexesAreTeamIdLeftmost() throws Exception {
        for (String index : EXPECTED_INDEXES) {
            assertEquals("team_id", queryString(
                            "SELECT column_name FROM information_schema.statistics "
                                    + "WHERE table_schema = DATABASE() AND table_name = 'sales_daily' "
                                    + "AND index_name = '" + index + "' AND seq_in_index = 1"),
                    "sales_daily." + index + " 的最左列必须是 team_id");
        }
        assertEquals("team_id", queryString(
                        "SELECT column_name FROM information_schema.statistics "
                                + "WHERE table_schema = DATABASE() AND table_name = 'sales_daily' "
                                + "AND index_name = 'uk_sales_team_wh_sku_date' AND seq_in_index = 1"),
                "uk_sales_team_wh_sku_date 的最左列必须是 team_id");
    }

    // =====================================================================
    // ⑤ 外键 RESTRICT
    // =====================================================================

    @Test
    @DisplayName("⑤ fk_sales_sku RESTRICT：删除被销售引用的 SKU → 失败；外键目标为 warehouses / skus")
    void foreignKeysRestrictAndTargetCorrectTables() throws Exception {
        execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-01", "1", "0", "1.00", "IMPORT"));
        assertIntegrityViolation(() -> execute("DELETE FROM skus WHERE id = " + sku7a));

        // 未被引用的 SKU 可删（证明失败来自外键，而非语法/权限）
        execute("DELETE FROM skus WHERE id = " + sku7b);

        List<String> refs = queryStrings(
                "SELECT referenced_table_name FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() AND table_name = 'sales_daily' "
                        + "ORDER BY referenced_table_name");
        assertEquals(List.of("skus", "warehouses"), refs,
                "sales_daily 的外键应恰指向 skus / warehouses（fk_sales_sku / fk_sales_warehouse）");

        // 三条外键的删除规则均 RESTRICT（唯一 CASCADE 只有 fk_rt_user）
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() AND table_name = 'sales_daily' "
                        + "AND delete_rule = 'RESTRICT'"),
                "两条外键均应为 ON DELETE RESTRICT");
    }

    // =====================================================================
    // ⑥ ck_sales_source
    // =====================================================================

    @Test
    @DisplayName("⑥ ck_sales_source：非法来源拒绝；IMPORT/MANUAL/PLATFORM 三值接受")
    void sourceCheckRejectsInvalidAndAcceptsThree() throws Exception {
        assertIntegrityViolation(() ->
                execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-01", "1", "0", "1.00", "HACKED")));

        String[] legal = {"IMPORT", "MANUAL", "PLATFORM"};
        for (int i = 0; i < legal.length; i++) {
            execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-0" + (i + 1), "1", "0", "1.00", legal[i]));
        }
        assertEquals(3L, queryLong("SELECT COUNT(*) FROM sales_daily"),
                "三个合法 SalesSource 应全部被接受");
    }

    // =====================================================================
    // ⑦ ck_sales_qty_non_negative / ck_sales_return_non_negative
    // =====================================================================

    @Test
    @DisplayName("⑦ ck_sales_qty/return_non_negative：qty 或 return_qty 为负 → 拒绝")
    void quantityChecksRejectNegatives() throws Exception {
        assertIntegrityViolation(() ->
                execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-01", "-1", "0", "1.00", "MANUAL")));
        assertIntegrityViolation(() ->
                execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-01", "1", "-1", "1.00", "MANUAL")));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM sales_daily"), "负数插入不应落行");

        // 0 是合法边界
        execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-01", "0", "0", "0.00", "MANUAL"));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM sales_daily"));
    }

    // =====================================================================
    // ⑧ ck_sales_amount_non_negative（可空列先判 NULL）
    // =====================================================================

    @Test
    @DisplayName("⑧ ck_sales_amount_non_negative：负数拒绝；NULL 合法（先判 NULL，避免 NULL>=0 静默陷阱）")
    void amountCheckRejectsNegativeButAllowsNull() throws Exception {
        assertIntegrityViolation(() ->
                execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-01", "1", "0", "-0.01", "MANUAL")));

        // NULL 必须合法（若写成 amount >= 0 而不先判 NULL，NULL>=0 为 NULL 语义上不拦但也不报错）
        execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-01", "1", "0", "NULL", "MANUAL"));
        // 0 与正数合法
        execute(salesInsert(team7, wh7, sku7a, prod7, "2026-10-02", "1", "0", "0.00", "MANUAL"));
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM sales_daily"), "NULL 与 0 金额均应接受");

        assertEquals("1", queryString("SELECT COUNT(*) FROM sales_daily WHERE sales_amount IS NULL"),
                "NULL 金额应原样保留为 NULL");
    }

    // =====================================================================
    // ⑨ 无 version + 集合 A
    // =====================================================================

    @Test
    @DisplayName("⑨ 无 version（§7.4 表 B）；集合 A 审计字段齐备")
    void noVersionColumnAndAuditSetAPresent() throws Exception {
        List<String> cols = columnNames("sales_daily");
        assertTrue(cols.containsAll(List.of("created_at", "updated_at", "created_by", "updated_by")),
                "sales_daily 应含集合 A 审计字段");
        assertFalse(cols.contains("version"),
                "sales_daily 不得有 version（§7.4 表 B：upsert 覆盖语义，无乐观锁）");

        // 必含业务键与可覆盖列
        assertTrue(cols.containsAll(List.of("team_id", "warehouse_id", "sku_id", "product_id", "sale_date",
                        "qty", "return_qty", "sales_amount", "order_count", "source", "batch_id")),
                "sales_daily 应含 §5.11 全部业务列");
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private static String salesInsert(long teamId, long whId, long skuId, long productId, String saleDate,
                                      String qty, String returnQty, String amount, String source) {
        return "INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, "
                + "qty, return_qty, sales_amount, source) VALUES ("
                + teamId + ", " + whId + ", " + skuId + ", " + productId + ", '" + saleDate + "', "
                + qty + ", " + returnQty + ", " + amount + ", '" + source + "')";
    }

    private static long insertTeam(String code, String name) throws Exception {
        execute("INSERT INTO teams (code, name) VALUES ('" + code + "', '" + name + "')");
        return queryLong("SELECT id FROM teams WHERE code = '" + code + "'");
    }

    private static long insertWarehouse(long teamId, String code) throws Exception {
        execute("INSERT INTO warehouses (team_id, code, name) VALUES ("
                + teamId + ", '" + code + "', '" + code + "')");
        return queryLong("SELECT id FROM warehouses WHERE team_id = " + teamId + " AND code = '" + code + "'");
    }

    private static long insertProduct(long teamId, String code) throws Exception {
        execute("INSERT INTO products (team_id, product_code, name) VALUES ("
                + teamId + ", '" + code + "', '" + code + "')");
        return queryLong("SELECT id FROM products WHERE team_id = " + teamId
                + " AND product_code = '" + code + "'");
    }

    private static long insertSku(long teamId, long productId, String code) throws Exception {
        execute("INSERT INTO skus (team_id, product_id, sku_code) VALUES ("
                + teamId + ", " + productId + ", '" + code + "')");
        return queryLong("SELECT id FROM skus WHERE team_id = " + teamId + " AND sku_code = '" + code + "'");
    }

    private static List<String> columnNames(String table) throws Exception {
        return queryStrings("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' ORDER BY ordinal_position");
    }

    /** 断言一段 SQL 触发完整性约束（唯一/外键 SQLState 23xx，或 CHECK 约束消息）。 */
    private static void assertIntegrityViolation(SqlAction sql) {
        SQLException ex;
        try {
            sql.run();
            throw new AssertionError("期望触发完整性约束（唯一/外键/CHECK），但语句成功执行");
        } catch (SQLException e) {
            ex = e;
        } catch (Exception e) {
            throw new AssertionError("期望触发完整性约束（SQLState 23xx），实际抛出的不是 SQLException：" + e, e);
        }
        String state = ex.getSQLState();
        String msg = ex.getMessage();
        boolean isIntegrity = (state != null && state.startsWith("23"))
                || (msg != null && msg.contains("Check constraint"));
        assertTrue(isIntegrity,
                "应触发完整性约束（唯一/外键 SQLState 23xx 或 CHECK 约束），实际：" + state + " / " + msg);
    }

    private static void execute(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private static String queryString(String sql) throws Exception {
        List<String> out = queryStrings(sql);
        return out.isEmpty() ? null : out.get(0);
    }

    private static List<String> queryStrings(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static long queryLong(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }
}
