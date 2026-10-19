package com.shoppilot.importdata;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
 * M3-T01 importdata 两表迁移 + 约束 / 索引 —— 端到端集成测试。
 *
 * <p>用真实 MySQL 8.0.36（Testcontainers）执行真实迁移与<b>真实 SQL 约束</b>，
 * 断言 {@code DESIGN_BASIS §6} 与 {@code DATABASE_DESIGN §5.12 / §5.13} 的数据库级保障：
 * <ul>
 *   <li>Flyway 应用 {@code V1.0.0} → {@code V1.0.9} 共十条迁移；</li>
 *   <li>幂等槽位唯一键 {@code uk_imp_team_hash_biz} 在<b>团队内</b>冲突 → 完整性约束；</li>
 *   <li>{@code dedupe_hash = NULL} 多行共存（MySQL 唯一索引允许多 NULL）；</li>
 *   <li>跨团队同 {@code file_hash} / 同 {@code dedupe_hash} <b>可重复</b>插入成功（幂等只在团队内）；</li>
 *   <li>枚举 CHECK（{@code ck_imp_status} / {@code ck_imp_biz_type} / {@code ck_ire_level}）生效；</li>
 *   <li>范围 CHECK（{@code ck_imp_attempt_no} / {@code ck_ire_row_no}）生效；</li>
 *   <li>外键 {@code fk_ire_batch} RESTRICT：删除被引用批次失败；</li>
 *   <li>两表全部 6 条二级索引 <b>team_id 为最左列</b>（逐条断言）。</li>
 * </ul>
 *
 * <p>不启动 Spring 上下文：手工装配 Flyway + {@link DriverManagerDataSource}，断言最终数据库状态。
 */
@Testcontainers
class ImportSchemaConstraintTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 两表全部二级索引（idx_*）及其所属表，用于「team_id 最左」逐条断言。 */
    private static final List<String[]> EXPECTED_INDEXES = List.of(
            new String[]{"import_batches", "idx_imp_team_status_time"},
            new String[]{"import_batches", "idx_imp_team_biz_time"},
            new String[]{"import_batches", "idx_imp_team_file_hash"},
            new String[]{"import_row_errors", "idx_ire_team_batch_row"},
            new String[]{"import_row_errors", "idx_ire_team_code"},
            new String[]{"import_row_errors", "idx_ire_team_level"});

    private static DataSource dataSource;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        // 真实执行 Flyway 迁移链（V1.0.0 baseline → V1.0.5 importdata）
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }

    @BeforeEach
    void cleanImportData() throws Exception {
        // 同一容器跨测试共享：先清表保证基数确定（删除顺序：先子表，后父表，避免外键 RESTRICT）
        execute("DELETE FROM import_row_errors");
        execute("DELETE FROM import_batches");
    }

    // ---------------------------------------------------------------------
    // §1 Flyway 全链
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§1 Flyway 全链跑通：V1.0.0 → V1.0.9 十条迁移均 success=1")
    void flywayChainAppliedAllVersions() throws Exception {
        List<String> tables = queryStrings(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name");
        for (String t : new String[]{"import_batches", "import_row_errors"}) {
            assertTrue(tables.contains(t), "importdata 表应存在：" + t);
        }

        List<String> versions = queryStrings(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        assertEquals(List.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5", "1.0.6", "1.0.7", "1.0.8", "1.0.9"),
                versions,
                "应应用 V1.0.0 → V1.0.9 十条迁移"
                        + "（V1.0.5 为 M3-T01 importdata，V1.0.6 为 M3-T08 inventory，"
                        + "V1.0.7 为 M3-T09 inventory R53 修正，V1.0.8 为 M3-T12 sales，"
                        + "V1.0.9 为 M4-T02 analysis）");
    }

    // ---------------------------------------------------------------------
    // §2 幂等槽位唯一键（团队内）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§2 uk_imp_team_hash_biz：同团队同 dedupe_hash 同 biz_type 插第二条 → 唯一冲突")
    void dedupeSlotUniqueWithinTeam() throws Exception {
        execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash, dedupe_hash) "
                + "VALUES (7, 'B-1', 'PRODUCT', 'a.csv', 'H1', 'H1')");
        assertIntegrityViolation(() ->
                execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash, dedupe_hash) "
                        + "VALUES (7, 'B-2', 'PRODUCT', 'a.csv', 'H1', 'H1')"));
    }

    // ---------------------------------------------------------------------
    // §3 槽位释放（NULL）可共存——FORCE_REIMPORT 的物理前提
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§3 dedupe_hash = NULL 多行共存：MySQL 唯一索引允许多 NULL")
    void nullDedupeHashCoexists() throws Exception {
        execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash, dedupe_hash) "
                + "VALUES (7, 'B-N1', 'PRODUCT', 'a.csv', 'H1', NULL)");
        execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash, dedupe_hash) "
                + "VALUES (7, 'B-N2', 'PRODUCT', 'a.csv', 'H1', NULL)");

        assertEquals(2L, queryLong(
                        "SELECT COUNT(*) FROM import_batches WHERE team_id = 7 AND dedupe_hash IS NULL"),
                "两条 dedupe_hash 为 NULL 的批次应共存（槽位已释放）");
    }

    // ---------------------------------------------------------------------
    // §4 跨团队同哈希可重复（期望行为）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§4 跨团队同 file_hash / 同 dedupe_hash 可重复：幂等只在团队内（§6）")
    void crossTeamSameHashAllowed() throws Exception {
        execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash, dedupe_hash) "
                + "VALUES (7, 'B-T7', 'PRODUCT', 'a.csv', 'H1', 'H1')");
        execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash, dedupe_hash) "
                + "VALUES (8, 'B-T8', 'PRODUCT', 'a.csv', 'H1', 'H1')");

        assertEquals(2L, queryLong("SELECT COUNT(*) FROM import_batches WHERE file_hash = 'H1'"),
                "两个团队各一行同一文件哈希（idx_imp_team_file_hash 非唯一）");
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM import_batches WHERE dedupe_hash = 'H1'"),
                "两个团队各占用一个幂等槽位");
    }

    // ---------------------------------------------------------------------
    // §5 枚举 CHECK
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§5 ck_imp_status / ck_imp_biz_type：非法枚举值被拒")
    void checkEnumsRejectInvalid() throws Exception {
        // status 非法（10 值之外）
        assertIntegrityViolation(() ->
                execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash, status) "
                        + "VALUES (7, 'B-S', 'PRODUCT', 'a.csv', 'H1', 'BOGUS')"));
        // biz_type 非法（6 值之外）
        assertIntegrityViolation(() ->
                execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash) "
                        + "VALUES (7, 'B-B', 'NOPE', 'a.csv', 'H1')"));
    }

    // ---------------------------------------------------------------------
    // §6 范围 CHECK
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§6 ck_imp_attempt_no 拒绝 0；ck_ire_row_no 拒绝 0")
    void checkRangesRejectZero() throws Exception {
        assertIntegrityViolation(() ->
                execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash, attempt_no) "
                        + "VALUES (7, 'B-A', 'PRODUCT', 'a.csv', 'H1', 0)"));

        long batchId = insertBatch(7, "B-OK");
        assertIntegrityViolation(() ->
                execute("INSERT INTO import_row_errors (team_id, batch_id, row_no, error_code, error_message) "
                        + "VALUES (7, " + batchId + ", 0, 'IMP-4003', '行号非法')"));
    }

    // ---------------------------------------------------------------------
    // §7 import_row_errors 级别 CHECK
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§7 ck_ire_level：非法 error_level（BOGUS）被拒")
    void checkErrorLevelRejectsInvalid() throws Exception {
        long batchId = insertBatch(7, "B-L");
        assertIntegrityViolation(() ->
                execute("INSERT INTO import_row_errors "
                        + "(team_id, batch_id, row_no, error_level, error_code, error_message) "
                        + "VALUES (7, " + batchId + ", 1, 'BOGUS', 'IMP-4003', '级别非法')"));
    }

    // ---------------------------------------------------------------------
    // §8 外键 RESTRICT
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§8 fk_ire_batch RESTRICT：删除仍被 import_row_errors 引用的批次 → 失败")
    void fkBatchRestrictBlocksDelete() throws Exception {
        long batchId = insertBatch(7, "B-FK");
        execute("INSERT INTO import_row_errors (team_id, batch_id, row_no, error_level, error_code, error_message) "
                + "VALUES (7, " + batchId + ", 1, 'ERROR', 'IMP-4003', '缺失必填')");

        // 引用存在 → 删除批次被 RESTRICT 拒绝
        assertIntegrityViolation(() -> execute("DELETE FROM import_batches WHERE id = " + batchId));

        // 反向验证：解除引用后可删
        execute("DELETE FROM import_row_errors WHERE batch_id = " + batchId);
        execute("DELETE FROM import_batches WHERE id = " + batchId);
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM import_batches WHERE id = " + batchId),
                "解除引用后应可删除批次");
    }

    // ---------------------------------------------------------------------
    // §9 team_id 最左生效（逐条断言）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§9 team_id 最左：两表全部 6 条二级索引 SEQ_IN_INDEX=1 一列为 team_id")
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
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    /** 测试专用函数式接口：包裹一段可能抛 {@link SQLException} 的 SQL。 */
    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }

    /** 插入一个最小合法批次（仅填 NOT NULL 列），返回其主键 id。 */
    private static long insertBatch(long teamId, String batchNo) throws Exception {
        execute("INSERT INTO import_batches (team_id, batch_no, biz_type, file_name, file_hash) "
                + "VALUES (" + teamId + ", '" + batchNo + "', 'PRODUCT', 'a.csv', 'H-" + batchNo + "')");
        return queryLong("SELECT id FROM import_batches WHERE team_id = " + teamId
                + " AND batch_no = '" + batchNo + "'");
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
