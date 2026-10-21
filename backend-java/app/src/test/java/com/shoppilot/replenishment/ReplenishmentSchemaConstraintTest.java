package com.shoppilot.replenishment;

import com.shoppilot.replenishment.domain.ReplenishmentItemStatus;
import com.shoppilot.replenishment.domain.ReviewAction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-T01 {@code replenishment_items} / {@code replenishment_reviews} 迁移与约束 —— 端到端结构测试。
 *
 * <p>用真实 MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移链，断言
 * {@code DATABASE_DESIGN §5.16/§5.17/§7.4} 与
 * {@code DATABASE_CONSTRAINTS #18 / §3.2 #36–#42 / §4.1–§4.3 / §5.2 #18–#23}：
 * <ul>
 *   <li>迁移链到 {@code V1.0.13} 共 14 条；两表存在，关键列类型/可空/默认值正确；</li>
 *   <li>{@code replenishment_items} = 集合 A + {@code version}（{@code int unsigned NOT NULL}）；
 *       {@code replenishment_reviews} = 集合 B，且<b>没有</b> {@code updated_at}/{@code updated_by}/{@code version}；</li>
 *   <li>{@code uk_repl_run_sku_wh} = (run_id, sku_id, warehouse_id) 且<b>不含 team_id</b>（R89）；</li>
 *   <li>业务索引 5 + 3 条（items 增 V1.0.12 的 {@code idx_repl_team_wh_sku}，R165），名称与最左列（{@code team_id}）精确匹配；</li>
 *   <li>6 条外键全 {@code RESTRICT}，引用表正确；</li>
 *   <li>9 条 CHECK 逐条负向验证；★ {@code ck_rr_comment_required} 的空/空白/正常三种 comment 行为；</li>
 *   <li>两表 {@code team_id} 均 {@code NOT NULL} 且<b>无默认值</b>（租户隔离红线）。</li>
 * </ul>
 *
 * <p>不启动 Spring 上下文：手工装配 Flyway + {@link DriverManagerDataSource}，断言最终数据库状态
 * （与 {@code AnalysisSchemaConstraintTest} 同范式）。直连 DB 的 INSERT <b>显式带 team_id</b>，
 * 因为此路径没有租户插件。
 */
@Testcontainers
class ReplenishmentSchemaConstraintTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** items 业务索引（#36–#39 四条 + V1.0.12 的 idx_repl_team_wh_sku，R165）。 */
    private static final List<String> EXPECTED_ITEM_INDEXES = List.of(
            "idx_repl_team_run", "idx_repl_team_sku_time",
            "idx_repl_team_source", "idx_repl_team_status_time", "idx_repl_team_wh_sku");

    /** reviews 三条业务索引（#40–#42）。 */
    private static final List<String> EXPECTED_REVIEW_INDEXES = List.of(
            "idx_rr_team_action_time", "idx_rr_team_item_time", "idx_rr_team_reviewer_time");

    /** 6 条外键（#18–#23）及其引用表。 */
    private static final List<String> EXPECTED_FKS = List.of(
            "fk_repl_run", "fk_repl_sku", "fk_repl_source",
            "fk_repl_supplier", "fk_repl_warehouse", "fk_rr_item");

    private static final String ITEM_STATUS_IN = "'SUGGESTED', 'PENDING_REVIEW', 'APPROVED', "
            + "'CONVERTED', 'REJECTED', 'SUPERSEDED', 'CANCELLED'";

    private static DataSource dataSource;

    private static long team7;
    private static long team8;
    private static long wh7;
    private static long prod7;
    private static long sku7;
    private static long supplier7;
    private static long run7;
    private static long run7b;
    private static long source7;

    @BeforeAll
    static void setUp() {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }

    @BeforeEach
    void seed() throws Exception {
        execute("DELETE FROM replenishment_reviews");
        execute("DELETE FROM replenishment_items");
        execute("DELETE FROM analysis_items");
        execute("DELETE FROM analysis_runs");
        execute("DELETE FROM replenishment_policies");
        execute("DELETE FROM sales_daily");
        execute("DELETE FROM skus");
        execute("DELETE FROM products");
        execute("DELETE FROM suppliers");
        execute("DELETE FROM warehouses");
        execute("DELETE FROM team_members");
        execute("DELETE FROM teams");

        team7 = insertTeam("T-RPL-7", "补货团队七");
        team8 = insertTeam("T-RPL-8", "补货团队八");
        wh7 = insertWarehouse(team7, "WH-RPL-7");
        prod7 = insertProduct(team7, "P-RPL-7");
        sku7 = insertSku(team7, prod7, "SKU-RPL-7");
        supplier7 = insertSupplier(team7, "SUP-RPL-7");
        run7 = insertRun(team7, "RUN-RPL-7A");
        run7b = insertRun(team7, "RUN-RPL-7B");
        source7 = insertAnalysisItem(team7, run7, sku7, prod7, wh7);
    }

    // =====================================================================
    // T1 迁移到位 + 关键列
    // =====================================================================

    @Test
    @DisplayName("T1 迁移链到 V1.0.13（14 条）：两表存在，关键列类型/可空/默认值正确")
    void migrationAppliesThroughV110AndCreatesBothTables() throws Exception {
        List<String> tables = queryStrings("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = DATABASE()");
        assertTrue(tables.contains("replenishment_items"), "replenishment_items 表应存在");
        assertTrue(tables.contains("replenishment_reviews"), "replenishment_reviews 表应存在");

        List<String> versions = queryStrings(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        assertEquals(List.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5",
                        "1.0.6", "1.0.7", "1.0.8", "1.0.9", "1.0.10", "1.0.11", "1.0.12", "1.0.13"),
                versions, "应应用 V1.0.0 → V1.0.13 共 14 条迁移（V1.0.10 = M5-T01 replenishment，"
                        + "V1.0.11 = M6-T01 purchasing，V1.0.12 = M6-T02-D R165 锚点锁索引，V1.0.13 = M8-T04 ADR-008 幂等键占位表）");

        // 关键列类型（§5.16 / §5.17 / §14.2）
        assertEquals("int unsigned", columnType("replenishment_items", "version"),
                "version 应为 INT UNSIGNED（§7.4 表 A）");
        assertEquals("NO", columnNullable("replenishment_items", "version"), "version 必须 NOT NULL");
        assertEquals("0", columnDefault("replenishment_items", "version"), "version 默认 0");
        assertEquals("decimal(18,4)", columnType("replenishment_items", "purchase_price"),
                "purchase_price 应为 DECIMAL(18,4)（单价 4 位，§14.2）");
        assertEquals("decimal(18,3)", columnType("replenishment_items", "suggested_qty"));
        assertEquals("decimal(18,3)", columnType("replenishment_items", "qty"));
        assertEquals("json", columnType("replenishment_items", "converted_po_item_ids"));
        assertEquals("datetime(3)", columnType("replenishment_items", "converted_at"));
        assertEquals("varchar(16)", columnType("replenishment_items", "risk_level"));
        assertEquals("datetime(3)", columnType("replenishment_reviews", "occurred_at"));
        assertEquals("NO", columnNullable("replenishment_reviews", "occurred_at"),
                "occurred_at 必须 NOT NULL");
        assertEquals("decimal(18,3)", columnType("replenishment_reviews", "qty_before"));
        assertEquals("varchar(500)", columnType("replenishment_reviews", "comment"));
        assertEquals("YES", columnNullable("replenishment_reviews", "comment"), "comment 可空");
        assertEquals("varchar(64)", columnType("replenishment_reviews", "request_id"));

        // 默认值（§5.16）
        assertEquals("'SUGGESTED'", "'" + columnDefault("replenishment_items", "status") + "'",
                "status 默认 SUGGESTED");
        assertEquals("'NONE'", "'" + columnDefault("replenishment_items", "risk_level") + "'",
                "risk_level 默认 NONE");

        // 审计字段集合（§4.1 表 A / 表 B）
        Set<String> itemCols = new TreeSet<>(columnNames("replenishment_items"));
        assertTrue(itemCols.containsAll(List.of("created_at", "updated_at", "created_by", "updated_by", "version")),
                "items 应为集合 A + version，实际列：" + itemCols);
        Set<String> reviewCols = new TreeSet<>(columnNames("replenishment_reviews"));
        assertTrue(reviewCols.containsAll(List.of("created_at", "created_by")),
                "reviews 应含集合 B 字段，实际列：" + reviewCols);
        assertFalse(reviewCols.contains("updated_at"), "★ reviews 仅追加：不得有 updated_at");
        assertFalse(reviewCols.contains("updated_by"), "★ reviews 仅追加：不得有 updated_by");
        assertFalse(reviewCols.contains("version"), "★ reviews 仅追加：不得有 version");
    }

    // =====================================================================
    // T2 唯一约束 uk_repl_run_sku_wh
    // =====================================================================

    @Test
    @DisplayName("T2 uk_repl_run_sku_wh = (run_id,sku_id,warehouse_id) 且不含 team_id；同三元组冲突、换 run 允许")
    void uniqueKeyIsRunSkuWarehouseWithoutTeamId() throws Exception {
        List<String> cols = indexColumns("replenishment_items", "uk_repl_run_sku_wh");
        System.out.println("[M5T01-EVIDENCE] uk_repl_run_sku_wh columns=" + cols);
        assertEquals(List.of("run_id", "sku_id", "warehouse_id"), cols,
                "★ R89 豁免：唯一键不带 team_id，列序按 §2.1 #18");
        assertFalse(cols.contains("team_id"), "不得好心加 team_id");

        insertItemRow(team7, source7, run7, sku7, prod7, wh7, "SUGGESTED", "10.000", "10.000");
        // 同 (run, sku, wh) 第二条 → 拒绝
        assertIntegrityViolation(() -> insertItemRow(team7, source7, run7, sku7, prod7, wh7,
                "SUGGESTED", "20.000", "20.000"));
        // 换 run_id → 允许（新 run 不冲突，旧建议可置 SUPERSEDED）
        insertItemRow(team7, source7, run7b, sku7, prod7, wh7, "SUGGESTED", "20.000", "20.000");

        assertEquals(2L, queryLong("SELECT COUNT(*) FROM replenishment_items"),
                "同三元组只 1 行；换 run 后 2 行");
    }

    // =====================================================================
    // T3 索引：5 + 3（V1.0.12 增 idx_repl_team_wh_sku），均 team_id 最左
    // =====================================================================

    @Test
    @DisplayName("T3 业务索引 items 5 条 / reviews 3 条，名称与最左列精确匹配")
    void businessIndexesMatchSpec() throws Exception {
        List<String> itemIdx = indexNames("replenishment_items");
        List<String> reviewIdx = indexNames("replenishment_reviews");
        System.out.println("[M5T01-EVIDENCE] items idx=" + itemIdx);
        System.out.println("[M5T01-EVIDENCE] reviews idx=" + reviewIdx);

        assertEquals(EXPECTED_ITEM_INDEXES, itemIdx,
                "items 业务索引应为 #36–#39 四条 + idx_repl_team_wh_sku（V1.0.12 / R165），共五条（名称精确）");
        assertEquals(EXPECTED_REVIEW_INDEXES, reviewIdx, "reviews 业务索引应为 #40–#42 三条（名称精确）");

        for (String idx : itemIdx) {
            assertEquals("team_id", leftmostColumn("replenishment_items", idx),
                    "★ items 索引 " + idx + " 最左列必须是 team_id（§3.2）");
        }
        for (String idx : reviewIdx) {
            assertEquals("team_id", leftmostColumn("replenishment_reviews", idx),
                    "★ reviews 索引 " + idx + " 最左列必须是 team_id（§3.2）");
        }

        // 逐条列序（防「名字对、列错」）
        assertEquals(List.of("team_id", "status", "created_at"),
                indexColumns("replenishment_items", "idx_repl_team_status_time"));
        assertEquals(List.of("team_id", "sku_id", "created_at"),
                indexColumns("replenishment_items", "idx_repl_team_sku_time"));
        assertEquals(List.of("team_id", "run_id"),
                indexColumns("replenishment_items", "idx_repl_team_run"));
        assertEquals(List.of("team_id", "source_analysis_item_id"),
                indexColumns("replenishment_items", "idx_repl_team_source"));
        // ★ V1.0.12 / R165：降级锚点行锁的专用索引（列序写错 ⇒ 锁序失效，必须逐字断言）
        assertEquals(List.of("team_id", "warehouse_id", "sku_id"),
                indexColumns("replenishment_items", "idx_repl_team_wh_sku"));
        assertEquals(List.of("team_id", "replenishment_item_id", "occurred_at"),
                indexColumns("replenishment_reviews", "idx_rr_team_item_time"));
        assertEquals(List.of("team_id", "reviewer_id", "occurred_at"),
                indexColumns("replenishment_reviews", "idx_rr_team_reviewer_time"));
        assertEquals(List.of("team_id", "action", "occurred_at"),
                indexColumns("replenishment_reviews", "idx_rr_team_action_time"));
    }

    // =====================================================================
    // T4 外键：6 条全 RESTRICT
    // =====================================================================

    @Test
    @DisplayName("T4 六条外键存在、引用表正确、DELETE/UPDATE 均 RESTRICT")
    void foreignKeysAreRestrict() throws Exception {
        List<String> fks = fkNames();
        System.out.println("[M5T01-EVIDENCE] fks=" + fks);
        assertEquals(EXPECTED_FKS, fks, "应有 6 条外键（#18–#23）");

        for (String fk : fks) {
            String rules = fkDeleteRule(fk) + "/" + fkUpdateRule(fk);
            System.out.println("[M5T01-EVIDENCE] " + fk + " -> " + fkReferencedTable(fk) + " rules=" + rules);
            assertEquals("RESTRICT/RESTRICT", rules,
                    "★ 全仓唯一 CASCADE 是 fk_rt_user；" + fk + " 必须 RESTRICT");
        }
        assertEquals("analysis_items", fkReferencedTable("fk_repl_source"));
        assertEquals("analysis_runs", fkReferencedTable("fk_repl_run"));
        assertEquals("skus", fkReferencedTable("fk_repl_sku"));
        assertEquals("warehouses", fkReferencedTable("fk_repl_warehouse"));
        assertEquals("suppliers", fkReferencedTable("fk_repl_supplier"));
        assertEquals("replenishment_items", fkReferencedTable("fk_rr_item"));

        // 行为：删除被引用主数据必须失败（RESTRICT 生效）。
        // ★ 为避免「analysis_items 也引用同一主数据」导致归因不清，这里为每条外键
        //   构造**只被本表引用**的目标行。
        long itemId = insertItemRow(team7, source7, run7, sku7, prod7, wh7, "SUGGESTED", "1.000", "1.000");

        // fk_repl_sku：新建一个未被 analysis_items 引用的 SKU，仅被建议引用
        long skuOnly = insertSku(team7, prod7, "SKU-RPL-ONLY");
        insertItemRow(team7, source7, run7b, skuOnly, prod7, wh7, "SUGGESTED", "1.000", "1.000");
        assertIntegrityViolation(() -> execute("DELETE FROM skus WHERE id = " + skuOnly));

        // fk_repl_run：新建一个无 analysis_items 的 run，仅被建议引用
        long runOnly = insertRun(team7, "RUN-RPL-ONLY");
        insertItemRow(team7, source7, runOnly, sku7, prod7, wh7, "SUGGESTED", "1.000", "1.000");
        assertIntegrityViolation(() -> execute("DELETE FROM analysis_runs WHERE id = " + runOnly));

        // fk_repl_source：analysis_items 被建议引用
        assertIntegrityViolation(() -> execute("DELETE FROM analysis_items WHERE id = " + source7));

        // fk_rr_item：先有审核记录，再删建议必须失败（审核轨迹不可丢）
        insertReview(itemId, "SUBMIT", "SUGGESTED", "PENDING_REVIEW", "OPERATOR", "NULL");
        assertIntegrityViolation(() -> execute("DELETE FROM replenishment_items WHERE id = " + itemId));
    }

    // =====================================================================
    // T5 CHECK：枚举 / 非负
    // =====================================================================

    @Test
    @DisplayName("T5 items 四条 CHECK：非法状态/风险等级/负数量全部拒绝")
    void itemChecksRejectInvalidValues() throws Exception {
        assertIntegrityViolation(() -> insertItemRow(team7, source7, run7, sku7, prod7, wh7,
                "BOGUS", "1.000", "1.000"));
        insertItemRow(team7, source7, run7, sku7, prod7, wh7, "CANCELLED", "1.000", "1.000");

        assertIntegrityViolation(() -> insertItemRow(team7, source7, run7b, sku7, prod7, wh7,
                "SUGGESTED", "-0.001", "1.000"));
        assertIntegrityViolation(() -> insertItemRow(team7, source7, run7b, sku7, prod7, wh7,
                "SUGGESTED", "1.000", "-0.001"));

        // 非法 risk_level（默认 NONE 之外的枚举外取值）
        assertIntegrityViolation(() -> insertItemRowWithRisk(team7, source7, run7b, sku7, prod7, wh7,
                "CRITICAL", "1.000", "1.000"));
    }

    // =====================================================================
    // T6 CHECK：reviews 枚举 + 驳回必填（★ 本批验收硬项）
    // =====================================================================

    @Test
    @DisplayName("T6 reviews 四条枚举 CHECK：非法 action/from/to/role 全部拒绝")
    void reviewEnumChecksRejectInvalidValues() throws Exception {
        long itemId = insertItemRow(team7, source7, run7, sku7, prod7, wh7, "SUGGESTED", "1.000", "1.000");

        assertIntegrityViolation(() -> insertReview(itemId, "DELETE", "SUGGESTED", "PENDING_REVIEW",
                "OPERATOR", "NULL"));
        assertIntegrityViolation(() -> insertReview(itemId, "SUBMIT", "BOGUS", "PENDING_REVIEW",
                "OPERATOR", "NULL"));
        assertIntegrityViolation(() -> insertReview(itemId, "SUBMIT", "SUGGESTED", "BOGUS",
                "OPERATOR", "NULL"));
        assertIntegrityViolation(() -> insertReview(itemId, "SUBMIT", "SUGGESTED", "PENDING_REVIEW",
                "SUPERUSER", "NULL"));

        // 合法值通过（证明不是「一律拒绝」）
        insertReview(itemId, "SUBMIT", "SUGGESTED", "PENDING_REVIEW", "OPERATOR", "NULL");
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM replenishment_reviews"));
    }

    @Test
    @DisplayName("T6b ck_rr_comment_required：REJECT + NULL/''/'  ' 全部拒绝，REJECT + 文字通过，非 REJECT 可空")
    void rejectRequiresNonBlankComment() throws Exception {
        long itemId = insertItemRow(team7, source7, run7, sku7, prod7, wh7, "PENDING_REVIEW", "1.000", "1.000");

        // ① REJECT + comment=NULL → 拒绝
        assertIntegrityViolation(() -> insertReview(itemId, "REJECT", "PENDING_REVIEW", "REJECTED",
                "APPROVER", "NULL"));
        System.out.println("[M5T01-EVIDENCE] REJECT+NULL  -> rejected");

        // ② REJECT + comment='' → 拒绝
        assertIntegrityViolation(() -> insertReview(itemId, "REJECT", "PENDING_REVIEW", "REJECTED",
                "APPROVER", "''"));
        System.out.println("[M5T01-EVIDENCE] REJECT+''    -> rejected");

        // ③ ★ REJECT + comment='  '（纯空白）→ 必须拒绝（项目负责人裁定用 TRIM(comment) <> ''）
        assertIntegrityViolation(() -> insertReview(itemId, "REJECT", "PENDING_REVIEW", "REJECTED",
                "APPROVER", "'  '"));
        System.out.println("[M5T01-EVIDENCE] REJECT+'  '  -> rejected (TRIM 生效)");

        // ④ REJECT + 正常文字 → 通过（用 ASCII 便于证据留档；中文值另有应用层用例）
        insertReview(itemId, "REJECT", "PENDING_REVIEW", "REJECTED", "APPROVER", "'non-empty'");
        System.out.println("[M5T01-EVIDENCE] REJECT+'non-empty' -> accepted");

        // ⑤ 非 REJECT + NULL → 允许（SUBMIT 无需意见）
        insertReview(itemId, "SUBMIT", "SUGGESTED", "PENDING_REVIEW", "OPERATOR", "NULL");
        System.out.println("[M5T01-EVIDENCE] SUBMIT+NULL  -> accepted");

        assertEquals(2L, queryLong("SELECT COUNT(*) FROM replenishment_reviews"));
    }

    // =====================================================================
    // T7 租户红线：team_id NOT NULL 且无默认值
    // =====================================================================

    @Test
    @DisplayName("T7 两表 team_id 均 NOT NULL 且无 DEFAULT；裸 INSERT 被拒")
    void teamIdIsMandatoryWithoutDefault() throws Exception {
        for (String table : List.of("replenishment_items", "replenishment_reviews")) {
            assertEquals("NO", columnNullable(table, "team_id"), table + ".team_id 必须 NOT NULL");
            assertNull(columnDefault(table, "team_id"),
                    "★ " + table + ".team_id 不得有 DEFAULT（TenantTables 黑名单只允许 3 张表）");
        }

        // 裸 INSERT（不写 team_id）必须被 NOT NULL 拒绝
        assertIntegrityViolation(() -> execute("INSERT INTO replenishment_items "
                + "(source_analysis_item_id, run_id, sku_id, product_id, warehouse_id, suggested_qty, qty) "
                + "VALUES (" + source7 + ", " + run7 + ", " + sku7 + ", " + prod7 + ", " + wh7
                + ", 1.000, 1.000)"));
        assertIntegrityViolation(() -> execute("INSERT INTO replenishment_reviews "
                + "(replenishment_item_id, action, from_status, to_status, reviewer_id, reviewer_role, occurred_at) "
                + "VALUES (" + insertItemRow(team7, source7, run7, sku7, prod7, wh7, "SUGGESTED", "1.000", "1.000")
                + ", 'SUBMIT', 'SUGGESTED', 'PENDING_REVIEW', 1, 'OPERATOR', NOW(3))"));
    }

    // =====================================================================
    // T8 枚举与 CHECK 取值集合一致（防两边各自演进）
    // =====================================================================

    @Test
    @DisplayName("T8 Java 枚举取值 == DB CHECK 取值集合（7 状态 / 5 动作 / 5 角色）")
    void javaEnumsMatchCheckConstraints() throws Exception {
        String statusCheck = normalizeCheck(checkClause("replenishment_items", "ck_repl_status"));
        System.out.println("[M5T01-EVIDENCE] ck_repl_status=" + statusCheck);
        for (ReplenishmentItemStatus value : ReplenishmentItemStatus.values()) {
            assertTrue(statusCheck.contains(value.name()),
                    "ck_repl_status 缺少枚举值 " + value.name() + "，实际：" + statusCheck);
        }
        String actionCheck = normalizeCheck(checkClause("replenishment_reviews", "ck_rr_action"));
        for (ReviewAction value : ReviewAction.values()) {
            assertTrue(actionCheck.contains(value.name()),
                    "ck_rr_action 缺少枚举值 " + value.name() + "，实际：" + actionCheck);
        }
        String roleCheck = normalizeCheck(checkClause("replenishment_reviews", "ck_rr_role"));
        for (String role : List.of("OWNER", "ADMIN", "OPERATOR", "APPROVER", "VIEWER")) {
            assertTrue(roleCheck.contains(role), "ck_rr_role 缺少 " + role + "，实际：" + roleCheck);
        }
        assertEquals(Set.of("CONVERTED", "REJECTED", "SUPERSEDED", "CANCELLED"),
                Set.of(Arrays.stream(ReplenishmentItemStatus.values())
                        .filter(ReplenishmentItemStatus::isTerminal)
                        .map(Enum::name).toArray(String[]::new)),
                "§9.11 终态集合：CONVERTED/REJECTED/SUPERSEDED/CANCELLED");
    }

    // =====================================================================
    // 种子与工具
    // =====================================================================

    private static long insertItemRow(long teamId, long sourceId, long runId, long skuId,
                                      long productId, long whId, String status,
                                      String suggestedQty, String qty) throws Exception {
        execute("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                + "product_id, warehouse_id, status, suggested_qty, qty) VALUES ("
                + teamId + ", " + sourceId + ", " + runId + ", " + skuId + ", " + productId + ", "
                + whId + ", '" + status + "', " + suggestedQty + ", " + qty + ")");
        return queryLong("SELECT id FROM replenishment_items WHERE run_id = " + runId
                + " AND sku_id = " + skuId + " AND warehouse_id = " + whId);
    }

    private static long insertItemRowWithRisk(long teamId, long sourceId, long runId, long skuId,
                                              long productId, long whId, String riskLevel,
                                              String suggestedQty, String qty) throws Exception {
        execute("INSERT INTO replenishment_items (team_id, source_analysis_item_id, run_id, sku_id, "
                + "product_id, warehouse_id, status, risk_level, suggested_qty, qty) VALUES ("
                + teamId + ", " + sourceId + ", " + runId + ", " + skuId + ", " + productId + ", "
                + whId + ", 'SUGGESTED', '" + riskLevel + "', " + suggestedQty + ", " + qty + ")");
        return queryLong("SELECT id FROM replenishment_items WHERE run_id = " + runId
                + " AND sku_id = " + skuId + " AND warehouse_id = " + whId);
    }

    private static void insertReview(long itemId, String action, String fromStatus, String toStatus,
                                     String role, String commentLiteral) throws Exception {
        execute("INSERT INTO replenishment_reviews (team_id, replenishment_item_id, action, from_status, "
                + "to_status, comment, reviewer_id, reviewer_role, occurred_at) VALUES ("
                + team7 + ", " + itemId + ", '" + action + "', '" + fromStatus + "', '" + toStatus + "', "
                + commentLiteral + ", 1001, '" + role + "', NOW(3))");
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

    private static long insertSupplier(long teamId, String code) throws Exception {
        execute("INSERT INTO suppliers (team_id, code, name) VALUES ("
                + teamId + ", '" + code + "', '" + code + "')");
        return queryLong("SELECT id FROM suppliers WHERE team_id = " + teamId + " AND code = '" + code + "'");
    }

    private static long insertRun(long teamId, String runNo) throws Exception {
        execute("INSERT INTO analysis_runs (team_id, run_no, scope_type, algorithm_version) VALUES ("
                + teamId + ", '" + runNo + "', 'ALL_ACTIVE', 'avg-ma-3sigma-v1')");
        return queryLong("SELECT id FROM analysis_runs WHERE team_id = " + teamId
                + " AND run_no = '" + runNo + "'");
    }

    private static long insertAnalysisItem(long teamId, long runId, long skuId, long productId,
                                           long whId) throws Exception {
        execute("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, qty_suggest, "
                + "safety_stock_qty) VALUES ("
                + teamId + ", " + runId + ", " + skuId + ", " + productId + ", " + whId + ", "
                + "8.000, 'HIGH', 'SKU', 6.250, 104.500, 110.000, 25.000)");
        return queryLong("SELECT id FROM analysis_items WHERE run_id = " + runId + " AND sku_id = " + skuId);
    }

    /** 业务索引名（{@code idx\_%}；排除外键自动索引与主键）。 */
    private static List<String> indexNames(String table) throws Exception {
        return queryStrings("SELECT DISTINCT index_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name LIKE 'idx\\_%' ORDER BY index_name");
    }

    private static List<String> indexColumns(String table, String index) throws Exception {
        return queryStrings("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name = '" + index + "' ORDER BY seq_in_index");
    }

    private static String leftmostColumn(String table, String index) throws Exception {
        return queryString("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name = '" + index + "' AND seq_in_index = 1");
    }

    private static List<String> fkNames() throws Exception {
        return queryStrings("SELECT constraint_name FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() "
                + "AND table_name IN ('replenishment_items', 'replenishment_reviews') "
                + "ORDER BY constraint_name");
    }

    private static String fkReferencedTable(String fk) throws Exception {
        return queryString("SELECT referenced_table_name FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND constraint_name = '" + fk + "'");
    }

    private static String fkDeleteRule(String fk) throws Exception {
        return queryString("SELECT delete_rule FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND constraint_name = '" + fk + "'");
    }

    private static String fkUpdateRule(String fk) throws Exception {
        return queryString("SELECT update_rule FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND constraint_name = '" + fk + "'");
    }

    /** information_schema 的 CHECK 子句可能带 {@code _utf8mb4\\'X\\'} 转义，统一去掉反斜杠便于断言。 */
    private static String normalizeCheck(String clause) {
        return clause == null ? "" : clause.replace("\\'", "'");
    }

    private static String checkClause(String table, String checkName) throws Exception {
        return queryString("SELECT check_clause FROM information_schema.check_constraints "
                + "WHERE constraint_schema = DATABASE() AND constraint_name = '" + checkName + "'");
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

    private static List<String> columnNames(String table) throws Exception {
        return queryStrings("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' ORDER BY ordinal_position");
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }

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
                || (msg != null && (msg.contains("Check constraint")
                        || msg.contains("doesn't have a default value")
                        || msg.contains("cannot be null")));
        assertTrue(isIntegrity,
                "应触发完整性约束（唯一/外键 SQLState 23xx 或 CHECK 约束），实际：" + state + " / " + msg);
    }

    private static void execute(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        }
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

    private static String queryString(String sql) throws Exception {
        List<String> out = queryStrings(sql);
        return out.isEmpty() ? null : out.get(0);
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
