package com.shoppilot.analysis;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import com.shoppilot.analysis.domain.DataQualityGrade;
import com.shoppilot.analysis.domain.LeadTimeSource;
import com.shoppilot.analysis.domain.RiskLevel;
import com.shoppilot.analysis.domain.RiskType;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-T02 analysis_runs / analysis_items 迁移 + 约束 / 索引 / 外键 —— 端到端集成测试。
 *
 * <p>用真实 MySQL 8.0.36（Testcontainers）执行真实迁移与<b>真实 SQL 约束</b>，断言
 * {@code DATABASE_DESIGN §5.14/§5.15/§7.4} 与
 * {@code DATABASE_CONSTRAINTS #15–#17 / §3.2 #31–#35 / §3.3 / §4.1 / §4.2 / §4.3 / §5.2}：
 * <ul>
 *   <li>Flyway 应用 {@code V1.0.0} → {@code V1.0.9} 共十条迁移，两张表存在且关键列类型正确；</li>
 *   <li>唯一键 {@code uk_run_team_no} / {@code uk_run_team_idem} 在<b>团队内</b>生效；
 *       {@code idempotency_key} 可空 ⇒ 多行 {@code NULL} 合法；</li>
 *   <li>{@code uk_ai_run_sku_wh} 在<b>一次运行内</b>生效，且<b>不带 team_id</b>（R89）；</li>
 *   <li>二级索引<b>恰好</b> 3 + 2 条，名字集合精确匹配（防多建 {@code idx_ai_team_run_risk}，R102），
 *       {@code idx_run_timeout} 最左列是 {@code status}（R103 + §3.3 例外），其余四条最左是 {@code team_id}；</li>
 *   <li>三条外键全部 {@code RESTRICT}（删除被引用的 run / sku / warehouse 均失败）；</li>
 *   <li>{@code analysis_runs} 五条 CHECK、{@code analysis_items} 六条 CHECK 逐条负向验证，
 *       ★ 可空列额外断言 {@code NULL} 合法（防「先比大小后判 NULL」把 NULL 误拒）；</li>
 *   <li>审计字段：{@code analysis_runs} 集合 A 且<b>无 version</b>；
 *       {@code analysis_items} 集合 B 且<b>无 updated_at / updated_by / version</b>。</li>
 * </ul>
 *
 * <p>不启动 Spring 上下文：手工装配 Flyway + {@link DriverManagerDataSource}，断言最终数据库状态。
 * 写法照抄 {@code SalesSchemaConstraintTest}（M3-T12 同款）。
 */
@Testcontainers
class AnalysisSchemaConstraintTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** analysis_runs 三条二级索引（DATABASE_CONSTRAINTS §3.2 #31–#33；R103 定名 idx_run_timeout）。 */
    private static final List<String> EXPECTED_RUN_INDEXES = List.of(
            "idx_run_team_created", "idx_run_team_status", "idx_run_timeout");

    /** analysis_items 两条二级索引（#34–#35；★ 不含 idx_ai_team_run_risk，R102）。 */
    private static final List<String> EXPECTED_ITEM_INDEXES = List.of(
            "idx_ai_team_run", "idx_ai_team_sku_time");

    private static DataSource dataSource;

    private long team7;
    private long team8;
    private long wh7;
    private long wh7b;
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
        // 先删子表（analysis_items 三条外键指向 runs/skus/warehouses）
        execute("DELETE FROM analysis_items");
        execute("DELETE FROM analysis_runs");
        execute("DELETE FROM skus");
        execute("DELETE FROM products");
        execute("DELETE FROM warehouses");
        execute("DELETE FROM team_members");
        execute("DELETE FROM teams");

        team7 = insertTeam("T-ANL-7", "分析团队七");
        team8 = insertTeam("T-ANL-8", "分析团队八");
        wh7 = insertWarehouse(team7, "WH-ANL-7");
        wh7b = insertWarehouse(team7, "WH-ANL-7B");
        wh8 = insertWarehouse(team8, "WH-ANL-8");
        prod7 = insertProduct(team7, "P-ANL-7");
        sku7a = insertSku(team7, prod7, "SKU-ANL-7A");
        sku7b = insertSku(team7, prod7, "SKU-ANL-7B");
        sku8 = insertSku(team8, insertProduct(team8, "P-ANL-8"), "SKU-ANL-8");
    }

    // =====================================================================
    // T1 迁移到位 + 关键列类型
    // =====================================================================

    @Test
    @DisplayName("T1 Flyway 全链到 V1.0.9：两表存在，6 个关键列类型/可空性正确")
    void migrationAppliesThroughV109AndCreatesBothTables() throws Exception {
        List<String> tables = queryStrings("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = DATABASE()");
        assertTrue(tables.contains("analysis_runs"), "analysis_runs 表应存在");
        assertTrue(tables.contains("analysis_items"), "analysis_items 表应存在");

        List<String> versions = queryStrings(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        assertEquals(List.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4",
                        "1.0.5", "1.0.6", "1.0.7", "1.0.8", "1.0.9"),
                versions,
                "应应用 V1.0.0 → V1.0.9 十条迁移（V1.0.9 为 M4-T02 analysis）");

        // ★ 6 个关键列：覆盖「无符号整数 / 精度 4 的 DECIMAL / TEXT / JSON / DATETIME(3) / 长 VARCHAR」
        assertEquals("tinyint unsigned", columnType("analysis_runs", "progress"),
                "progress 应为 TINYINT UNSIGNED（0–100）");
        assertEquals("decimal(18,4)", columnType("analysis_items", "cv"),
                "cv 应为 DECIMAL(18,4)（★ 精度 4，其余数量列为 3）");
        assertEquals("text", columnType("analysis_items", "llm_explanation"),
                "llm_explanation 应为 TEXT");
        assertEquals("json", columnType("analysis_runs", "params_json"),
                "params_json 应为 JSON");
        assertEquals("datetime(3)", columnType("analysis_runs", "timeout_at"),
                "timeout_at 应为 DATETIME(3)");
        assertEquals("varchar(128)", columnType("analysis_runs", "idempotency_key"),
                "idempotency_key 应为 VARCHAR(128)");

        // 可空性抽查（§5.14 / §5.15）
        assertEquals("NO", columnNullable("analysis_runs", "team_id"), "team_id 必须 NOT NULL");
        assertEquals("NO", columnNullable("analysis_items", "on_hand_qty"), "on_hand_qty 必须 NOT NULL");
        assertEquals("YES", columnNullable("analysis_items", "avg_daily_sales"), "avg_daily_sales 应可空");
    }

    // =====================================================================
    // T2 uk_run_team_no
    // =====================================================================

    @Test
    @DisplayName("T2 uk_run_team_no：同团队同 run_no 冲突；换团队 / 换 run_no 允许")
    void uniqueRunNoScopedToTeam() throws Exception {
        insertRun(team7, "RUN-T2-A");
        // 同团队同 run_no → 冲突
        assertIntegrityViolation(() -> insertRun(team7, "RUN-T2-A"));
        // 换 run_no → 允许
        insertRun(team7, "RUN-T2-B");
        // 跨团队同 run_no → 允许（编号是团队内语义）
        insertRun(team8, "RUN-T2-A");

        assertEquals(3L, queryLong("SELECT COUNT(*) FROM analysis_runs"),
                "同团队同 run_no 只 1 行；换编号 + 跨团队各 1 行，共 3 行");
    }

    // =====================================================================
    // T3 uk_run_team_idem（可空 ⇒ 多行 NULL 合法）
    // =====================================================================

    @Test
    @DisplayName("T3 uk_run_team_idem：同团队同幂等键冲突；两行 NULL 合法；跨团队同键允许")
    void idempotencyKeyScopedToTeamAndNullsAllowed() throws Exception {
        insertRunWithIdem(team7, "RUN-T3-A", "'idem-key-1'");
        // 同团队同幂等键 → 冲突
        assertIntegrityViolation(() -> insertRunWithIdem(team7, "RUN-T3-B", "'idem-key-1'"));
        // 跨团队同幂等键 → 允许
        insertRunWithIdem(team8, "RUN-T3-A", "'idem-key-1'");
        // ★ 两行 NULL 必须同时存在（MySQL 唯一键对 NULL 不去重；未指定幂等键的请求不受幂等约束）
        insertRunWithIdem(team7, "RUN-T3-C", "NULL");
        insertRunWithIdem(team7, "RUN-T3-D", "NULL");

        assertEquals(2L, queryLong("SELECT COUNT(*) FROM analysis_runs WHERE idempotency_key IS NULL"),
                "两行 NULL 幂等键应同时存在（唯一键对 NULL 不去重）");
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM analysis_runs "
                        + "WHERE team_id = " + team7 + " AND idempotency_key = 'idem-key-1'"),
                "同团队同幂等键只应 1 行");
    }

    // =====================================================================
    // T4 uk_ai_run_sku_wh（不带 team_id，R89）
    // =====================================================================

    @Test
    @DisplayName("T4 uk_ai_run_sku_wh：同 run 同 (sku,wh) 冲突；换 run / 换 wh 允许")
    void uniqueRunSkuWarehousePerRun() throws Exception {
        long runA = insertRun(team7, "RUN-T4-A");
        long runB = insertRun(team7, "RUN-T4-B");

        insertItem(team7, runA, sku7a, prod7, wh7, "10", "NONE", "NULL", "NULL", "NULL", "NULL", "NULL");
        // 同 run 同 (sku,wh) → 冲突（★ 唯一键不含 team_id，仍能拦住重复快照）
        assertIntegrityViolation(() -> insertItem(team7, runA, sku7a, prod7, wh7,
                "20", "NONE", "NULL", "NULL", "NULL", "NULL", "NULL"));
        // 换 run → 允许（每次重新分析都是新 run 的新快照）
        insertItem(team7, runB, sku7a, prod7, wh7, "20", "NONE", "NULL", "NULL", "NULL", "NULL", "NULL");
        // 同 run 换 wh → 允许（粒度是 SKU×仓库）
        insertItem(team7, runA, sku7a, prod7, wh7b, "30", "NONE", "NULL", "NULL", "NULL", "NULL", "NULL");

        assertEquals(3L, queryLong("SELECT COUNT(*) FROM analysis_items"),
                "同 run 同 (sku,wh) 只 1 行；换 run + 换 wh 各 1 行，共 3 行");
    }

    // =====================================================================
    // T5 索引精确断言（防多建 / 少建）
    // =====================================================================

    @Test
    @DisplayName("T5 索引恰 3+2 条且名字精确；idx_run_timeout 最左是 status，其余最左是 team_id")
    void indexSetIsExactAndLeftmostColumnsCorrect() throws Exception {
        // ★ 统计索引条数必须 COUNT(DISTINCT index_name)：statistics 按列展开，COUNT(*) 会多算复合索引
        assertEquals(3L, queryLong("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'analysis_runs' "
                        + "AND index_name LIKE 'idx\\_%'"),
                "analysis_runs 二级索引应恰 3 条（#31–#33）");
        assertEquals(EXPECTED_RUN_INDEXES, queryStrings(
                        "SELECT DISTINCT index_name FROM information_schema.statistics "
                                + "WHERE table_schema = DATABASE() AND table_name = 'analysis_runs' "
                                + "AND index_name LIKE 'idx\\_%' ORDER BY index_name"),
                "analysis_runs 索引名集合应精确等于 {idx_run_team_status, idx_run_team_created, idx_run_timeout}");

        assertEquals(2L, queryLong("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'analysis_items' "
                        + "AND index_name LIKE 'idx\\_%'"),
                "analysis_items 二级索引应恰 2 条（#34–#35）");
        assertEquals(EXPECTED_ITEM_INDEXES, queryStrings(
                        "SELECT DISTINCT index_name FROM information_schema.statistics "
                                + "WHERE table_schema = DATABASE() AND table_name = 'analysis_items' "
                                + "AND index_name LIKE 'idx\\_%' ORDER BY index_name"),
                "analysis_items 索引名集合应精确等于 {idx_ai_team_run, idx_ai_team_sku_time}");

        // ★ R102：idx_ai_team_run_risk 与 idx_ai_team_run 列完全相同，是 §5.15 笔误，必须不存在
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'analysis_items' "
                        + "AND index_name = 'idx_ai_team_run_risk'"),
                "不得创建 idx_ai_team_run_risk（R102：与 idx_ai_team_run 列完全相同）");

        // ★ R103 + §3.3：超时索引最左是 status，不是 team_id（全仓唯一业务索引例外）
        assertEquals("status", leftmostColumn("analysis_runs", "idx_run_timeout"),
                "idx_run_timeout 最左列必须是 status（§3.3 系统级调度例外，R103）");

        for (String index : List.of("idx_run_team_status", "idx_run_team_created")) {
            assertEquals("team_id", leftmostColumn("analysis_runs", index),
                    "analysis_runs." + index + " 的最左列必须是 team_id");
        }
        for (String index : EXPECTED_ITEM_INDEXES) {
            assertEquals("team_id", leftmostColumn("analysis_items", index),
                    "analysis_items." + index + " 的最左列必须是 team_id");
        }
    }

    // =====================================================================
    // T6 三条外键全部 RESTRICT
    // =====================================================================

    @Test
    @DisplayName("T6 三条外键全 RESTRICT：删除被引用的 run / sku / warehouse 均失败")
    void foreignKeysAreRestrict() throws Exception {
        long run = insertRun(team7, "RUN-T6-A");
        insertItem(team7, run, sku7a, prod7, wh7, "10", "NONE", "NULL", "NULL", "NULL", "NULL", "NULL");

        assertIntegrityViolation(() -> execute("DELETE FROM analysis_runs WHERE id = " + run));
        assertIntegrityViolation(() -> execute("DELETE FROM skus WHERE id = " + sku7a));
        assertIntegrityViolation(() -> execute("DELETE FROM warehouses WHERE id = " + wh7));

        // 未被引用的 SKU 可删（证明失败来自外键，而非语法/权限）
        execute("DELETE FROM skus WHERE id = " + sku7b);

        List<String> refs = queryStrings(
                "SELECT referenced_table_name FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() AND table_name = 'analysis_items' "
                        + "ORDER BY referenced_table_name");
        assertEquals(List.of("analysis_runs", "skus", "warehouses"), refs,
                "analysis_items 的外键应恰指向 analysis_runs / skus / warehouses");

        assertEquals(3L, queryLong("SELECT COUNT(*) FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() AND table_name = 'analysis_items' "
                        + "AND delete_rule = 'RESTRICT'"),
                "三条外键均应为 ON DELETE RESTRICT（全仓唯一 CASCADE 只有 fk_rt_user）");

        // analysis_runs 无任何外键（§5.14 明文：warehouse_id / triggered_by 均逻辑关联）
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() AND table_name = 'analysis_runs'"),
                "analysis_runs 不得有任何外键");
    }

    // =====================================================================
    // T7 analysis_runs 五条 CHECK
    // =====================================================================

    @Test
    @DisplayName("T7 analysis_runs 五条 CHECK：status/llm_status/scope_type/progress/date_range 负向拒绝 + 正向接受")
    void runCheckConstraintsRejectInvalidValues() throws Exception {
        // ck_run_status
        assertIntegrityViolation(() -> insertRun(team7, "RUN-T7-01", "DONE", "NOT_REQUESTED",
                "ALL_ACTIVE", "0", "NULL", "NULL"));
        insertRun(team7, "RUN-T7-01", "RUNNING", "NOT_REQUESTED", "ALL_ACTIVE", "0", "NULL", "NULL");

        // ck_run_llm_status
        assertIntegrityViolation(() -> insertRun(team7, "RUN-T7-02", "PENDING", "BOGUS",
                "ALL_ACTIVE", "0", "NULL", "NULL"));
        insertRun(team7, "RUN-T7-02", "PENDING", "SUCCEEDED", "ALL_ACTIVE", "0", "NULL", "NULL");

        // ck_run_scope_type
        assertIntegrityViolation(() -> insertRun(team7, "RUN-T7-03", "PENDING", "NOT_REQUESTED",
                "FOO", "0", "NULL", "NULL"));
        insertRun(team7, "RUN-T7-03", "PENDING", "NOT_REQUESTED", "CATEGORY", "0", "NULL", "NULL");

        // ck_run_progress（0–100；100 是合法边界）
        assertIntegrityViolation(() -> insertRun(team7, "RUN-T7-04", "PENDING", "NOT_REQUESTED",
                "ALL_ACTIVE", "101", "NULL", "NULL"));
        insertRun(team7, "RUN-T7-04", "PENDING", "NOT_REQUESTED", "ALL_ACTIVE", "100", "NULL", "NULL");

        // ck_run_date_range（start <= end；相等合法）
        assertIntegrityViolation(() -> insertRun(team7, "RUN-T7-05", "PENDING", "NOT_REQUESTED",
                "ALL_ACTIVE", "0", "'2026-10-10'", "'2026-10-01'"));
        insertRun(team7, "RUN-T7-05", "PENDING", "NOT_REQUESTED", "ALL_ACTIVE", "0",
                "'2026-10-01'", "'2026-10-01'");
        // ★ 两端 NULL 也必须合法（先判 NULL 的意义：NULL 比较结果为 NULL，不会被拒）
        insertRun(team7, "RUN-T7-06", "PENDING", "NOT_REQUESTED", "ALL_ACTIVE", "0", "NULL", "NULL");

        assertEquals(6L, queryLong("SELECT COUNT(*) FROM analysis_runs"),
                "五条正向 + 一条双 NULL 共 6 行；五条负向均不应落行");
    }

    // =====================================================================
    // T8 analysis_items 六条 CHECK（★ 可空列必须断言 NULL 合法）
    // =====================================================================

    @Test
    @DisplayName("T8 analysis_items 六条 CHECK：枚举/非负负向拒绝；★ 四条可空列 NULL 合法")
    void itemCheckConstraintsRejectInvalidValues() throws Exception {
        long run = insertRun(team7, "RUN-T8-A");

        // ck_ai_risk_level
        assertIntegrityViolation(() -> insertItem(team7, run, sku7a, prod7, wh7,
                "10", "CRITICAL", "NULL", "NULL", "NULL", "NULL", "NULL"));
        insertItem(team7, run, sku7a, prod7, wh7, "10", "HIGH", "NULL", "1", "1", "1", "1");

        // ck_ai_lead_time_source（NULL 合法 + 非法值拒绝 + 合法值接受）
        assertIntegrityViolation(() -> insertItem(team7, run, sku7b, prod7, wh7,
                "10", "NONE", "'MAGIC'", "NULL", "NULL", "NULL", "NULL"));
        insertItem(team7, run, sku7b, prod7, wh7, "10", "NONE", "'SUPPLIER'", "1", "1", "1", "1");

        // 下面四条非负 CHECK 各用一个独立 run（同 run 内 (sku,wh) 已被占用）
        long runD = insertRun(team7, "RUN-T8-D");
        assertIntegrityViolation(() -> insertItem(team7, runD, sku7a, prod7, wh7,
                "10", "NONE", "NULL", "-0.001", "NULL", "NULL", "NULL"));
        // ★ NULL 合法：若 CHECK 写成 avg_daily_sales >= 0（未先判 NULL），NULL 会被判违反
        insertItem(team7, runD, sku7a, prod7, wh7, "10", "NONE", "NULL", "NULL", "NULL", "NULL", "NULL");

        long runQb = insertRun(team7, "RUN-T8-QB");
        assertIntegrityViolation(() -> insertItem(team7, runQb, sku7a, prod7, wh7,
                "10", "NONE", "NULL", "NULL", "-1", "NULL", "NULL"));
        insertItem(team7, runQb, sku7a, prod7, wh7, "10", "NONE", "NULL", "NULL", "NULL", "NULL", "NULL");

        long runQs = insertRun(team7, "RUN-T8-QS");
        assertIntegrityViolation(() -> insertItem(team7, runQs, sku7a, prod7, wh7,
                "10", "NONE", "NULL", "NULL", "NULL", "-1", "NULL"));
        insertItem(team7, runQs, sku7a, prod7, wh7, "10", "NONE", "NULL", "NULL", "NULL", "NULL", "NULL");

        long runSs = insertRun(team7, "RUN-T8-SS");
        assertIntegrityViolation(() -> insertItem(team7, runSs, sku7a, prod7, wh7,
                "10", "NONE", "NULL", "NULL", "NULL", "NULL", "-1"));
        insertItem(team7, runSs, sku7a, prod7, wh7, "10", "NONE", "NULL", "NULL", "NULL", "NULL", "NULL");

        // 0 是合法边界（四条非负 CHECK 共用 0）
        long runZero = insertRun(team7, "RUN-T8-ZERO");
        insertItem(team7, runZero, sku7a, prod7, wh7, "0", "NONE", "NULL", "0", "0", "0", "0");

        assertEquals(7L, queryLong("SELECT COUNT(*) FROM analysis_items"),
                "六条负向均不应落行；合法行应为 HIGH + SUPPLIER + 4 条 NULL + 1 条全 0 = 7 行");
        assertEquals(4L, queryLong("SELECT COUNT(*) FROM analysis_items "
                        + "WHERE avg_daily_sales IS NULL AND qty_base IS NULL "
                        + "AND qty_suggest IS NULL AND safety_stock_qty IS NULL"),
                "四条可空列同时为 NULL 的行应被接受（先判 NULL 的直接证据）");
    }

    // =====================================================================
    // T9 审计字段口径
    // =====================================================================

    @Test
    @DisplayName("T9 审计字段：runs 集合 A 无 version；items 集合 B 无 updated_*/version；team_id 无默认值")
    void auditFieldSetsMatchDesign() throws Exception {
        List<String> runCols = columnNames("analysis_runs");
        assertTrue(runCols.containsAll(List.of("created_at", "updated_at", "created_by", "updated_by")),
                "analysis_runs 应含集合 A 审计字段（DATA_DICTIONARY §4.1）");
        assertFalse(runCols.contains("version"),
                "analysis_runs 不得有 version（§7.4 表 A：用状态守卫，无乐观锁）");

        List<String> itemCols = columnNames("analysis_items");
        assertTrue(itemCols.containsAll(List.of("created_at", "created_by")),
                "analysis_items 应含集合 B 审计字段（仅 created_at + created_by）");
        assertFalse(itemCols.contains("updated_at"),
                "analysis_items 不得有 updated_at（§5.15：写入后不可变，仅 INSERT）");
        assertFalse(itemCols.contains("updated_by"), "analysis_items 不得有 updated_by");
        assertFalse(itemCols.contains("version"), "analysis_items 不得有 version");

        // ★ F10：team_id 必须 NOT NULL 且【无 DEFAULT】（M2 租户隔离测试会遍历全表断言）
        assertNull(columnDefault("analysis_runs", "team_id"),
                "analysis_runs.team_id 不得有 DEFAULT（TenantTables 断言口径）");
        assertNull(columnDefault("analysis_items", "team_id"),
                "analysis_items.team_id 不得有 DEFAULT（TenantTables 断言口径）");

        // 业务列完整性抽查（§5.14 / §5.15 关键列）
        assertTrue(runCols.containsAll(List.of("run_no", "status", "scope_type", "scope_json",
                        "params_json", "algorithm_version", "llm_status", "summary_json", "progress",
                        "request_id", "idempotency_key", "triggered_by", "started_at", "finished_at", "timeout_at")),
                "analysis_runs 应含 §5.14 全部关键业务列");
        assertTrue(itemCols.containsAll(List.of("run_id", "sku_id", "product_id", "warehouse_id",
                        "on_hand_qty", "inventory_position_qty", "available_qty", "cv",
                        "lead_time_source", "risk_level", "risk_types", "llm_detail_json")),
                "analysis_items 应含 §5.15 全部关键业务列");
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private static long insertRun(long teamId, String runNo) throws Exception {
        return insertRun(teamId, runNo, "PENDING", "NOT_REQUESTED", "ALL_ACTIVE", "0", "NULL", "NULL");
    }

    private static long insertRun(long teamId, String runNo, String status, String llmStatus,
                                  String scopeType, String progress, String startDate, String endDate) throws Exception {
        execute("INSERT INTO analysis_runs (team_id, run_no, status, llm_status, scope_type, "
                + "algorithm_version, progress, data_start_date, data_end_date) VALUES ("
                + teamId + ", '" + runNo + "', '" + status + "', '" + llmStatus + "', '" + scopeType + "', "
                + "'avg-ma-3sigma-v1', " + progress + ", " + startDate + ", " + endDate + ")");
        return queryLong("SELECT id FROM analysis_runs WHERE team_id = " + teamId
                + " AND run_no = '" + runNo + "'");
    }

    private static long insertRunWithIdem(long teamId, String runNo, String idemLiteral) throws Exception {
        execute("INSERT INTO analysis_runs (team_id, run_no, scope_type, algorithm_version, idempotency_key) "
                + "VALUES (" + teamId + ", '" + runNo + "', 'ALL_ACTIVE', 'avg-ma-3sigma-v1', "
                + idemLiteral + ")");
        return queryLong("SELECT id FROM analysis_runs WHERE team_id = " + teamId
                + " AND run_no = '" + runNo + "'");
    }

    private static void insertItem(long teamId, long runId, long skuId, long productId, long whId,
                                   String onHand, String riskLevel, String leadTimeSource,
                                   String avgDaily, String qtyBase, String qtySuggest, String ss) throws Exception {
        execute("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, qty_suggest, "
                + "safety_stock_qty) VALUES ("
                + teamId + ", " + runId + ", " + skuId + ", " + productId + ", " + whId + ", "
                + onHand + ", '" + riskLevel + "', " + leadTimeSource + ", " + avgDaily + ", "
                + qtyBase + ", " + qtySuggest + ", " + ss + ")");
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

    private static String columnType(String table, String column) throws Exception {
        return queryString("SELECT column_type FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'");
    }

    private static String columnNullable(String table, String column) throws Exception {
        return queryString("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'");
    }

    private static String columnDefault(String table, String column) throws Exception {
        return queryString("SELECT column_default FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'");
    }

    private static String leftmostColumn(String table, String index) throws Exception {
        return queryString("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name = '" + index + "' AND seq_in_index = 1");
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

    // =====================================================================
    // T10（M4-B20.2）：CHECK 取值集合 == Java 枚举（防两边各自演进）
    // =====================================================================

    @Test
    @DisplayName("T10 ck_ai_risk_level / ck_ai_lead_time_source 的取值集合与 Java 枚举完全一致")
    void enumValuesMatchJavaEnums() throws Exception {
        Set<String> riskLevelCheck = quotedLiterals(checkClause("analysis_items", "ck_ai_risk_level"));
        assertEquals(new TreeSet<>(RiskLevel.codes()), riskLevelCheck,
                "ck_ai_risk_level 的取值集合必须与 RiskLevel.codes() 完全一致（DESIGN_BASIS §9.9）");

        Set<String> leadTimeCheck = quotedLiterals(checkClause("analysis_items", "ck_ai_lead_time_source"));
        assertEquals(new TreeSet<>(LeadTimeSource.codes()), leadTimeCheck,
                "ck_ai_lead_time_source 的取值集合必须与 LeadTimeSource.codes() 完全一致（§10.2）");

        // 无 CHECK 的两枚举：断言它们与 Python 侧 Literal 同名同值（§7 第 5/6 项的判定依据）
        assertEquals(Set.of("STOCKOUT", "LOW_STOCK", "OVERSTOCK", "SLOW_MOVING",
                "SALES_ANOMALY", "DATA_INSUFFICIENT"), new TreeSet<>(RiskType.codes()),
                "RiskType 必须是 6 值（LOWSTOCK 是 RuleCode，不是 RiskType —— R115）");
        assertEquals(Set.of("GOOD", "FAIR", "POOR", "INSUFFICIENT"),
                new TreeSet<>(DataQualityGrade.codes()),
                "DataQualityGrade 必须是 4 值（ANALYSIS_CONTRACT §4.2）");

        // fail-closed：未知取值必须解析为 null，而不是「就近映射」成一个合法枚举
        assertNull(RiskLevel.fromCode("CRITICAL"));
        assertNull(RiskLevel.fromCode("LOWSTOCK"));
        assertNull(LeadTimeSource.fromCode("MAGIC"));
        assertNull(RiskType.fromCode("LOWSTOCK"));
        assertNull(DataQualityGrade.fromCode("EXCELLENT"));
    }

    /** 读 {@code information_schema.check_constraints} 里该 CHECK 的表达式。 */
    private static String checkClause(String table, String constraint) throws Exception {
        // table 仅用于失败信息定位；CHECK 表达式本身不含表名
        List<String> clauses = queryStrings(
                "SELECT check_clause FROM information_schema.check_constraints "
                        + "WHERE constraint_schema = DATABASE() AND constraint_name = '"
                        + constraint + "'");
        assertEquals(1, clauses.size(), "CHECK " + constraint + " 应恰好存在一条，实测 " + clauses.size());
        return clauses.get(0);
    }

    /**
     * 从 CHECK 表达式里抽出全部单引号字面量。
     *
     * <p>★ MySQL 8 在 {@code information_schema.check_constraints} 里返回的是
     * <b>反序列化后的源码文本</b>，字符集 introducer 与引号都被转义，实际形如
     * {@code (`risk_level` in (_utf8mb4\'NONE\',_utf8mb4\'LOW\',...))}。
     * 因此解析必须做两件事：容忍 {@code _utf8mb4} 前缀、剥掉字面量<b>首尾</b>的反斜杠
     * （Java 字符串里写作 {@code \\'} 的那个转义序列）。
     *
     * <p>另：CHECK 表达式只含<b>列名</b>、不含表名，故不做「提及表名」的断言
     * （表名归属已由 {@code T8} 的负向插入断言覆盖）。
     */
    private static Set<String> quotedLiterals(String clause) {
        Set<String> out = new TreeSet<>();
        Matcher matcher = Pattern.compile("(?:_[a-zA-Z0-9]+)?\\\\'([^']*)\\\\'").matcher(clause);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
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
