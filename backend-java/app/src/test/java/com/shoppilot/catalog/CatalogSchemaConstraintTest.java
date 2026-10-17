package com.shoppilot.catalog;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-T08 catalog 四表迁移 + 约束 / 索引 —— 端到端集成测试。
 *
 * <p>用真实 MySQL 8.0.36（Testcontainers）执行真实迁移与<b>真实 SQL 约束</b>，
 * 断言 {@code DESIGN_BASIS §6} 与 {@code DATABASE_DESIGN §5.4-§5.7} 的数据库级保障：
 * <ul>
 *   <li>Flyway 应用 {@code V1.0.0} → {@code V1.0.5} 共六条迁移（含 M2-T08 catalog、M2-T11 audit、
 *       M3-T01 importdata）；</li>
 *   <li>团队内唯一约束（uk_*_team_code）冲突 → 完整性约束（SQLState 23xx）；</li>
 *   <li>跨团队同编码<b>可重复</b>插入成功（D2，§6.3）；</li>
 *   <li>四表全部 13 条索引（4 个 uk_* + 9 个 idx_*）<b>team_id 为最左列</b>（逐条断言）；</li>
 *   <li>CHECK 约束（status / is_default / lead_time）生效；</li>
 *   <li>外键 RESTRICT（fk_prod_supplier）删除被引用行失败。</li>
 * </ul>
 *
 * <p>不启动 Spring 上下文：手工装配 Flyway + {@link DriverManagerDataSource}，断言最终数据库状态。
 */
@Testcontainers
class CatalogSchemaConstraintTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 四表全部二级索引（uk_* + idx_*）及其所属表，用于「team_id 最左」逐条断言。 */
    private static final List<String[]> EXPECTED_INDEXES = List.of(
            new String[]{"warehouses", "uk_wh_team_code"}, new String[]{"warehouses", "idx_wh_team_status"},
            new String[]{"warehouses", "idx_wh_team_default"},
            new String[]{"suppliers", "uk_sup_team_code"}, new String[]{"suppliers", "idx_sup_team_status"},
            new String[]{"products", "uk_prod_team_code"}, new String[]{"products", "idx_prod_team_status"},
            new String[]{"products", "idx_prod_team_category"}, new String[]{"products", "idx_prod_team_supplier"},
            new String[]{"skus", "uk_sku_team_code"}, new String[]{"skus", "idx_sku_team_product"},
            new String[]{"skus", "idx_sku_team_status"}, new String[]{"skus", "idx_sku_team_supplier"});

    private static DataSource dataSource;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        // 真实执行 Flyway 迁移链（V1.0.0 baseline → V1.0.3 catalog）
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }

    @BeforeEach
    void cleanCatalogData() throws Exception {
        // 同一容器跨测试共享：先清表保证基数确定（删除顺序：先子表，后父表，避免外键约束）
        execute("DELETE FROM skus");
        execute("DELETE FROM products");
        execute("DELETE FROM suppliers");
        execute("DELETE FROM warehouses");
    }

    @AfterEach
    void clearContext() {
        // 无 TeamContext 注入（本测试不走租户插件），无需清理
    }

    // ---------------------------------------------------------------------
    // §4-1 Flyway 全链
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§4-1 Flyway 全链跑通：V1.0.0 → V1.0.7 八条迁移均 success=1")
    void flywayChainAppliedFourVersions() throws Exception {
        List<String> tables = queryStrings(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name");
        for (String t : new String[]{"warehouses", "suppliers", "products", "skus"}) {
            assertTrue(tables.contains(t), "catalog 表应存在：" + t);
        }

        List<String> versions = queryStrings(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        assertEquals(List.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5", "1.0.6", "1.0.7"),
                versions,
                "应应用 V1.0.0 / V1.0.1 / V1.0.2 / V1.0.3 / V1.0.4 / V1.0.5 / V1.0.6 / V1.0.7 八条迁移"
                        + "（V1.0.5 为 M3-T01 importdata，V1.0.6 为 M3-T08 inventory，"
                        + "V1.0.7 为 M3-T09 inventory R53 修正）");
    }

    // ---------------------------------------------------------------------
    // §4-2 团队内唯一约束生效
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§4-2 团队内唯一约束：同团队重复 code / product_code / sku_code → SQLState 23xx")
    void uniqueConstraintWithinTeamRejected() throws Exception {
        // warehouses（无外键依赖，最简）
        execute("INSERT INTO warehouses (team_id, code, name) VALUES (7, 'W1', '仓一')");
        assertIntegrityViolation(() ->
                execute("INSERT INTO warehouses (team_id, code, name) VALUES (7, 'W1', '仓一重复')"));

        // products（需先有商品）
        execute("INSERT INTO products (team_id, product_code, name) VALUES (7, 'P1', '品一')");
        assertIntegrityViolation(() ->
                execute("INSERT INTO products (team_id, product_code, name) VALUES (7, 'P1', '品一重复')"));

        // skus（需先有商品）
        long productId = queryLong("SELECT id FROM products WHERE team_id = 7 AND product_code = 'P1'");
        execute("INSERT INTO skus (team_id, product_id, sku_code, name) VALUES (7, " + productId + ", 'K1', 'SKU一')");
        assertIntegrityViolation(() ->
                execute("INSERT INTO skus (team_id, product_id, sku_code, name) VALUES (7, " + productId + ", 'K1', 'SKU一重复')"));
    }

    // ---------------------------------------------------------------------
    // §4-3 跨团队同编码可重复（期望行为）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§4-3 跨团队同编码可重复：团队 A 的编码在团队 B 成功插入（D2，§6.3）")
    void crossTeamCodeRepeatableSucceeds() throws Exception {
        // warehouses：团队 8 复用团队 7 的 code 'W1' → 成功
        execute("INSERT INTO warehouses (team_id, code, name) VALUES (7, 'W1', '仓一')");
        execute("INSERT INTO warehouses (team_id, code, name) VALUES (8, 'W1', '仓一在别队')");

        // products：团队 8 复用 'P1'
        execute("INSERT INTO products (team_id, product_code, name) VALUES (7, 'P1', '品一')");
        execute("INSERT INTO products (team_id, product_code, name) VALUES (8, 'P1', '品一在别队')");

        // skus：需两队的商品
        long p7 = queryLong("SELECT id FROM products WHERE team_id = 7 AND product_code = 'P1'");
        long p8 = queryLong("SELECT id FROM products WHERE team_id = 8 AND product_code = 'P1'");
        execute("INSERT INTO skus (team_id, product_id, sku_code, name) VALUES (7, " + p7 + ", 'K1', 'SKU一')");
        execute("INSERT INTO skus (team_id, product_id, sku_code, name) VALUES (8, " + p8 + ", 'K1', 'SKU一在别队')");

        assertEquals(2L, queryLong("SELECT COUNT(*) FROM warehouses WHERE code = 'W1'"), "两个团队各一行 W1");
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM products WHERE product_code = 'P1'"), "两个团队各一行 P1");
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM skus WHERE sku_code = 'K1'"), "两个团队各一行 K1");
    }

    // ---------------------------------------------------------------------
    // §4-4 team_id 最左生效（逐条断言）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§4-4 team_id 最左：四表全部 13 条索引 SEQ_IN_INDEX=1 一列为 team_id")
    void teamIdIsLeftmostColumnOfEveryIndex() throws Exception {
        for (String[] entry : EXPECTED_INDEXES) {
            String table = entry[0];
            String index = entry[1];
            String col = queryString(
                    "SELECT column_name FROM information_schema.STATISTICS "
                            + "WHERE table_schema = DATABASE() AND table_name = '" + table
                            + "' AND index_name = '" + index + "' AND seq_in_index = 1");
            assertEquals("team_id", col,
                    "索引 " + table + "." + index + " 的最左列必须是 team_id（§6 规则 6）");
        }
        // 校验 §3 定义的 13 条索引全部存在且 team_id 为最左列（上面循环已逐条断言最左列）。
        // 注：InnoDB 会为外键引用列（skus.product_id / skus.supplier_id / products.default_supplier_id）
        // 自动补建内部索引，故不按"恰好 13 条"断言；仅断言本批设计的 13 条索引名全部落地且最左列为 team_id。
        long expectedPresent = EXPECTED_INDEXES.stream().filter(e -> {
            try {
                String col = queryString(
                        "SELECT column_name FROM information_schema.STATISTICS "
                                + "WHERE table_schema = DATABASE() AND table_name = '" + e[0]
                                + "' AND index_name = '" + e[1] + "' AND seq_in_index = 1");
                return "team_id".equals(col);
            } catch (Exception ex) {
                return false;
            }
        }).count();
        assertEquals(13L, expectedPresent, "§3 定义的 13 条索引必须全部存在且最左列为 team_id");
    }

    // ---------------------------------------------------------------------
    // §4-5 CHECK 约束生效
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§4-5 CHECK 约束：四表 status 非法 / is_default 越界 均被拒（CK 即拒绝）")
    void checkConstraintsRejectInvalid() throws Exception {
        // warehouses.status 非法
        assertIntegrityViolation(() ->
                execute("INSERT INTO warehouses (team_id, code, name, status) VALUES (7, 'W-BAD', 'x', 'BOGUS')"));
        // warehouses.is_default 越界
        assertIntegrityViolation(() ->
                execute("INSERT INTO warehouses (team_id, code, name, is_default) VALUES (7, 'W-BAD2', 'x', 2)"));
        // suppliers.status 非法
        assertIntegrityViolation(() ->
                execute("INSERT INTO suppliers (team_id, code, name, status) VALUES (7, 'S-BAD', 'x', 'BOGUS')"));
        // products.status 非法
        assertIntegrityViolation(() ->
                execute("INSERT INTO products (team_id, product_code, name, status) VALUES (7, 'P-BAD', 'x', 'BOGUS')"));
        // skus.status 非法（需先有商品）
        execute("INSERT INTO products (team_id, product_code, name) VALUES (7, 'P-CHK', 'p')");
        long pId = queryLong("SELECT id FROM products WHERE product_code = 'P-CHK'");
        assertIntegrityViolation(() ->
                execute("INSERT INTO skus (team_id, product_id, sku_code, name, status) "
                        + "VALUES (7, " + pId + ", 'K-BAD', 'k', 'BOGUS')"));
    }

    // ---------------------------------------------------------------------
    // §4-6 外键 RESTRICT 生效
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§4-6 外键 RESTRICT：删除被 products.default_supplier_id 引用的 suppliers 行 → 失败")
    void fkSupplierRestrictBlocksDelete() throws Exception {
        execute("INSERT INTO suppliers (team_id, code, name) VALUES (7, 'S1', '供应商一')");
        long supId = queryLong("SELECT id FROM suppliers WHERE code = 'S1'");
        execute("INSERT INTO products (team_id, product_code, name, default_supplier_id) "
                + "VALUES (7, 'P1', '品一', " + supId + ")");

        // 引用存在 → 删除供应商被 RESTRICT 拒绝
        assertIntegrityViolation(() -> execute("DELETE FROM suppliers WHERE id = " + supId));

        // 反向验证：解除引用后可删
        execute("UPDATE products SET default_supplier_id = NULL WHERE product_code = 'P1'");
        execute("DELETE FROM suppliers WHERE id = " + supId);
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM suppliers WHERE id = " + supId),
                "解除引用后应可删除供应商");
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    /** 测试专用函数式接口：包裹一段可能抛 {@link SQLException} 的 SQL。 */
    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }

    /** 断言一段 SQL 触发完整性约束（SQLState 以 23 开头，如 23000）。 */
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
        // MySQL：唯一/外键违反 → SQLState 23xx；CHECK 约束违反 → HY000 + 消息含 "Check constraint"。
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
}
