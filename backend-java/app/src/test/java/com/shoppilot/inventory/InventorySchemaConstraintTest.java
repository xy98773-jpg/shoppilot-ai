package com.shoppilot.inventory;

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
 * M3-T08 inventory 三表迁移 + 约束 / 索引 / 外键 —— 端到端集成测试。
 *
 * <p>用真实 MySQL 8.0.36（Testcontainers）执行真实迁移与<b>真实 SQL 约束</b>，断言
 * {@code DATABASE_DESIGN §5.8/§5.9/§5.10/§7.4} 与 {@code DATABASE_CONSTRAINTS #9–#12/§4.1/§4.2/§5.2}
 * 的数据库级保障：
 * <ul>
 *   <li>Flyway 应用 {@code V1.0.0} → {@code V1.0.13} 共十四条迁移，三表存在；</li>
 *   <li>四条唯一键在<b>团队内</b>冲突：{@code uk_pol_team_sku_wh} / {@code uk_invbal_team_wh_sku} /
 *       {@code uk_invtxn_team_idem} / {@code uk_invtxn_team_reversed}；</li>
 *   <li>跨团队同业务键<b>允许</b>共存（唯一性只在团队内，{@code DESIGN_BASIS §6} 规则 2）；</li>
 *   <li>可空列上的唯一键允许多行 {@code NULL}（{@code reversed_txn_id}）；</li>
 *   <li>9 条 CHECK 逐条负向验证（{@code ck_invtxn_consistency} / {@code ck_invtxn_delta_non_zero} /
 *       {@code ck_invtxn_*_non_negative} / {@code ck_invtxn_type} / {@code ck_invtxn_biz_type} /
 *       {@code ck_invbal_*_non_negative}）；</li>
 *   <li>{@code available_qty} 是 {@code STORED} 生成列：不可显式写入、自动计算、可索引；</li>
 *   <li>{@code version} 归属：策略表与余额表有、流水表<b>无</b>（且无 {@code updated_at}/{@code updated_by}）；</li>
 *   <li>外键 {@code RESTRICT}：删除被余额引用的 SKU 失败；余额<b>不</b>指向流水（账本↔快照不分强绑）；</li>
 *   <li>七条二级索引 <b>team_id 为最左列</b>，且<b>不建</b>冗余的 {@code idx_invbal_team_wh}。</li>
 * </ul>
 *
 * <p>不启动 Spring 上下文：手工装配 Flyway + {@link DriverManagerDataSource}，断言最终数据库状态。
 */
@Testcontainers
class InventorySchemaConstraintTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 三表全部二级索引（idx_*），用于「team_id 最左」逐条断言（DATABASE_CONSTRAINTS §3.2 #14–#20）。 */
    private static final List<String[]> EXPECTED_INDEXES = List.of(
            new String[]{"replenishment_policies", "idx_pol_team_sku"},
            new String[]{"replenishment_policies", "idx_pol_team_wh"},
            new String[]{"inventory_balances", "idx_invbal_team_sku"},
            new String[]{"inventory_balances", "idx_invbal_team_available"},
            new String[]{"inventory_transactions", "idx_invtxn_team_wh_sku_time"},
            new String[]{"inventory_transactions", "idx_invtxn_team_biz"},
            new String[]{"inventory_transactions", "idx_invtxn_team_occurred"});

    private static DataSource dataSource;

    // 种子主键（每个用例前重建，保证基数确定）
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
        // 同一容器跨测试共享：先清表保证基数确定（删除顺序：先子表，后父表，避免外键 RESTRICT）
        execute("DELETE FROM inventory_transactions");
        execute("DELETE FROM inventory_balances");
        execute("DELETE FROM replenishment_policies");
        execute("DELETE FROM skus");
        execute("DELETE FROM products");
        execute("DELETE FROM warehouses");
        execute("DELETE FROM team_members");
        execute("DELETE FROM teams");

        team7 = insertTeam("T-INV-7", "库存团队七");
        team8 = insertTeam("T-INV-8", "库存团队八");
        wh7 = insertWarehouse(team7, "WH-INV-7");
        wh8 = insertWarehouse(team8, "WH-INV-8");
        prod7 = insertProduct(team7, "P-INV-7");
        sku7a = insertSku(team7, prod7, "SKU-INV-7A");
        sku7b = insertSku(team7, prod7, "SKU-INV-7B");
        sku8 = insertSku(team8, insertProduct(team8, "P-INV-8"), "SKU-INV-8");
    }

    // =====================================================================
    // ① 三表存在 + Flyway 已应用到 1.0.9
    // =====================================================================

    @Test
    @DisplayName("① Flyway 全链到 V1.0.13：库存表存在，十四条迁移均 success=1")
    void migrationAppliesThroughV109AndCreatesThreeTables() throws Exception {
        List<String> tables = queryStrings(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name");
        for (String t : new String[]{"replenishment_policies", "inventory_balances", "inventory_transactions"}) {
            assertTrue(tables.contains(t), "inventory 表应存在：" + t);
        }

        List<String> versions = queryStrings(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        assertEquals(List.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5", "1.0.6", "1.0.7", "1.0.8", "1.0.9", "1.0.10", "1.0.11", "1.0.12", "1.0.13"),
                versions,
                "应应用 V1.0.0 → V1.0.13 十四条迁移（V1.0.6 为 M3-T08 inventory，"
                        + "V1.0.7 为 M3-T09 inventory R53 修正，V1.0.8 为 M3-T12 sales，"
                        + "V1.0.9 为 M4-T02 analysis，V1.0.10 为 M5-T01 replenishment，"
                        + "V1.0.11 为 M6-T01 purchasing，V1.0.12 为 M6-T02-D R165 锚点锁索引，V1.0.13 为 M8-T04 ADR-008 幂等键占位表）");
    }

    // =====================================================================
    // ② uk_pol_team_sku_wh
    // =====================================================================

    @Test
    @DisplayName("② uk_pol_team_sku_wh：同团队同 SKU×仓库 二次插入 → 唯一冲突；跨团队同组合允许")
    void policyUniqueKeyIsScopedToTeam() throws Exception {
        execute(policyInsert(team7, sku7a, wh7));
        assertIntegrityViolation(() -> execute(policyInsert(team7, sku7a, wh7)));

        // 唯一键带 team_id：同 (sku, wh) 换个团队 → 不冲突（团队隔离语义，DESIGN_BASIS §6 规则 2）
        execute(policyInsert(team8, sku7a, wh7));
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM replenishment_policies"),
                "跨团队同 (sku_id, warehouse_id) 应允许共存");
    }

    // =====================================================================
    // ③ uk_invbal_team_wh_sku
    // =====================================================================

    @Test
    @DisplayName("③ uk_invbal_team_wh_sku：同团队同仓库×SKU 二次插入 → 唯一冲突")
    void balanceUniqueKeyRejectsDuplicateRow() throws Exception {
        insertBalance(team7, wh7, sku7a, "0", "0", "0");
        assertIntegrityViolation(() -> insertBalance(team7, wh7, sku7a, "5", "0", "0"));

        // 换 SKU → 允许（一 SKU 一仓库一行，不是一仓库一行）
        insertBalance(team7, wh7, sku7b, "0", "0", "0");
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM inventory_balances WHERE team_id = " + team7));
    }

    // =====================================================================
    // ④ uk_invtxn_team_idem
    // =====================================================================

    @Test
    @DisplayName("④ uk_invtxn_team_idem：同团队同幂等键 → 冲突；不同团队同幂等键 → 允许")
    void transactionIdempotencyKeyIsScopedToTeam() throws Exception {
        execute(txnInsert(team7, wh7, sku7a, "INIT_IMPORT", "IMPORT", "10", "0", "10", "IDEM-SAME", null));
        assertIntegrityViolation(() ->
                execute(txnInsert(team7, wh7, sku7b, "INIT_IMPORT", "IMPORT", "5", "0", "5", "IDEM-SAME", null)));

        // 幂等键只在团队内唯一：team8 用同一个键 → 允许
        execute(txnInsert(team8, wh8, sku8, "INIT_IMPORT", "IMPORT", "3", "0", "3", "IDEM-SAME", null));
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM inventory_transactions WHERE idempotency_key = 'IDEM-SAME'"),
                "同一幂等键应可在不同团队各存一行");
    }

    // =====================================================================
    // ⑤ uk_invtxn_team_reversed（可空列上的唯一键）
    // =====================================================================

    @Test
    @DisplayName("⑤ uk_invtxn_team_reversed：同团队同 reversed_txn_id 二次插入 → 冲突；多行 NULL 允许")
    void reversalUniqueKeyAllowsManyNulls() throws Exception {
        // 多行 NULL 共存：MySQL 唯一索引不把 NULL 视为相同值 → 「未被冲销」的流水可无限多
        execute(txnInsert(team7, wh7, sku7a, "INIT_IMPORT", "IMPORT", "10", "0", "10", "IDEM-N1", null));
        execute(txnInsert(team7, wh7, sku7a, "PURCHASE_INBOUND", "GOODS_RECEIPT", "5", "10", "15", "IDEM-N2", null));
        execute(txnInsert(team7, wh7, sku7b, "INIT_IMPORT", "IMPORT", "1", "0", "1", "IDEM-N3", null));
        assertEquals(3L, queryLong("SELECT COUNT(*) FROM inventory_transactions WHERE reversed_txn_id IS NULL"),
                "reversed_txn_id 为 NULL 的行应可多行共存");

        // 同一笔流水只能被冲销一次
        execute(txnInsert(team7, wh7, sku7a, "REVERSAL", "REVERSAL", "-10", "15", "5", "IDEM-R1", 1L));
        assertIntegrityViolation(() ->
                execute(txnInsert(team7, wh7, sku7a, "REVERSAL", "REVERSAL", "-10", "15", "5", "IDEM-R2", 1L)));

        // 换团队 → 允许（团队隔离）
        long txnTeam8 = insertTxn(team8, wh8, sku8, "INIT_IMPORT", "IMPORT", "1", "0", "1", "IDEM-8", null);
        execute(txnInsert(team8, wh8, sku8, "REVERSAL", "REVERSAL", "-1", "1", "0", "IDEM-R8", txnTeam8));
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM inventory_transactions WHERE reversed_txn_id IS NOT NULL"));
    }

    // =====================================================================
    // ⑥ ck_invtxn_consistency
    // =====================================================================

    @Test
    @DisplayName("⑥ ck_invtxn_consistency：qty_after ≠ qty_before + qty_delta → 拒绝")
    void consistencyCheckRejectsMismatchedAfter() throws Exception {
        // 10 + 5 = 15，但写 16 → 拒绝
        assertIntegrityViolation(() ->
                execute(txnInsert(team7, wh7, sku7a, "PURCHASE_INBOUND", "GOODS_RECEIPT", "5", "10", "16", "IDEM-C1", null)));
        // 对照：15 正确 → 接受
        execute(txnInsert(team7, wh7, sku7a, "PURCHASE_INBOUND", "GOODS_RECEIPT", "5", "10", "15", "IDEM-C2", null));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM inventory_transactions"));
    }

    // =====================================================================
    // ⑦ ck_invtxn_delta_non_zero
    // =====================================================================

    @Test
    @DisplayName("⑦ ck_invtxn_delta_non_zero：qty_delta = 0 → 拒绝（一致性成立也不行）")
    void deltaNonZeroCheckRejectsZeroDelta() throws Exception {
        // 5 + 0 = 5 → 一致性满足，但 0 变更不应产生流水
        assertIntegrityViolation(() ->
                execute(txnInsert(team7, wh7, sku7a, "COUNT_ADJUST", "COUNT", "0", "5", "5", "IDEM-Z1", null)));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM inventory_transactions"));
    }

    // =====================================================================
    // ⑧ ck_invtxn_before/after_non_negative
    // =====================================================================

    @Test
    @DisplayName("⑧ ck_invtxn_before/after_non_negative：qty_before 或 qty_after 为负 → 拒绝（D-08 不允许负库存）")
    void negativeBeforeOrAfterRejected() throws Exception {
        // 一致性满足（-5 + 5 = 0），但变更前为负 → 拒绝
        assertIntegrityViolation(() ->
                execute(txnInsert(team7, wh7, sku7a, "MANUAL_ADJUST", "MANUAL", "5", "-5", "0", "IDEM-B1", null)));
        // 一致性满足（5 - 10 = -5），但变更后为负 → 拒绝
        assertIntegrityViolation(() ->
                execute(txnInsert(team7, wh7, sku7a, "SALE_OUTBOUND", "SALE", "-10", "5", "-5", "IDEM-A1", null)));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM inventory_transactions"));
    }

    // =====================================================================
    // ⑨ ck_invtxn_type / ck_invtxn_biz_type
    // =====================================================================

    @Test
    @DisplayName("⑨ ck_invtxn_type / ck_invtxn_biz_type：非法枚举值 → 拒绝")
    void invalidEnumsRejected() throws Exception {
        assertIntegrityViolation(() ->
                execute(txnInsert(team7, wh7, sku7a, "HACKED_TYPE", "IMPORT", "1", "0", "1", "IDEM-E1", null)));
        assertIntegrityViolation(() ->
                execute(txnInsert(team7, wh7, sku7a, "INIT_IMPORT", "HACKED_BIZ", "1", "0", "1", "IDEM-E2", null)));

        // 六个合法 txn_type 逐条接受（DESIGN_BASIS §9.6 六值）
        String[][] legal = {
                {"INIT_IMPORT", "IMPORT"},
                {"PURCHASE_INBOUND", "GOODS_RECEIPT"},
                {"SALE_OUTBOUND", "SALE"},
                {"COUNT_ADJUST", "COUNT"},
                {"MANUAL_ADJUST", "MANUAL"},
                {"REVERSAL", "REVERSAL"}};
        for (int i = 0; i < legal.length; i++) {
            execute(txnInsert(team7, wh7, sku7a, legal[i][0], legal[i][1], "1", "0", "1", "IDEM-OK-" + i, null));
        }
        assertEquals(6L, queryLong("SELECT COUNT(*) FROM inventory_transactions"),
                "六个合法 InventoryTxnType 应全部被接受");
    }

    // =====================================================================
    // ⑩ ck_invbal_*_non_negative
    // =====================================================================

    @Test
    @DisplayName("⑩ ck_invbal_on_hand/in_transit/committed_non_negative：三个数量列各拒绝负数")
    void balanceNonNegativeChecksRejectNegatives() throws Exception {
        assertIntegrityViolation(() -> insertBalance(team7, wh7, sku7a, "-1", "0", "0"));
        assertIntegrityViolation(() -> insertBalance(team7, wh7, sku7a, "0", "-1", "0"));
        assertIntegrityViolation(() -> insertBalance(team7, wh7, sku7a, "0", "0", "-1"));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM inventory_balances"), "三次负数插入都不应落行");

        // 0 是合法边界
        insertBalance(team7, wh7, sku7a, "0", "0", "0");
        assertEquals("0.000", queryString(
                "SELECT available_qty FROM inventory_balances WHERE team_id = " + team7 + " AND sku_id = " + sku7a));
    }

    // =====================================================================
    // ⑪ available_qty：STORED 生成列
    // =====================================================================

    @Test
    @DisplayName("⑪ available_qty 是 STORED 生成列：不可显式写入，on_hand=10 / committed=3 → 自动得 7")
    void availableQtyIsStoredGeneratedColumn() throws Exception {
        // (a) information_schema：必须 STORED GENERATED（不是 VIRTUAL，也不是普通列）
        assertEquals("STORED GENERATED", queryString(
                        "SELECT extra FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = 'inventory_balances' "
                                + "AND column_name = 'available_qty'"),
                "available_qty 必须是 STORED 生成列（DATABASE_DESIGN §5.9 设计要点 4）");
        // MySQL 回显的 generation_expression 带反引号/括号，归一化后比对语义
        String expr = String.valueOf(queryString(
                "SELECT generation_expression FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'inventory_balances' "
                        + "AND column_name = 'available_qty'"))
                .replace("`", "").replace("(", "").replace(")", "").replace(" ", "");
        assertEquals("on_hand_qty-committed_qty", expr,
                "生成表达式应为 on_hand_qty - committed_qty（不含在途，§3）");

        // (b) 显式写入生成列 → MySQL 3105（SQLState HY000，消息含 "generated column"）
        SQLException ex = assertThrows(SQLException.class, () -> execute(
                        "INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, "
                                + "on_hand_qty, in_transit_qty, committed_qty, available_qty) "
                                + "VALUES (" + team7 + ", " + wh7 + ", " + sku7a + ", 10, 0, 3, 7)"),
                "显式写入生成列必须被拒绝");
        String msg = String.valueOf(ex.getMessage()).toLowerCase();
        assertTrue(msg.contains("generated column"),
                "失败原因应指向生成列，实测：" + ex.getMessage());

        // (c) 正常插入 → 自动计算 10 - 3 = 7
        insertBalance(team7, wh7, sku7a, "10", "0", "3");
        assertEquals("7.000", queryString(
                "SELECT available_qty FROM inventory_balances WHERE team_id = " + team7 + " AND sku_id = " + sku7a));

        // (d) 更新源列 → 生成列自动重算 20 - 3 = 17
        execute("UPDATE inventory_balances SET on_hand_qty = 20 WHERE team_id = " + team7 + " AND sku_id = " + sku7a);
        assertEquals("17.000", queryString(
                "SELECT available_qty FROM inventory_balances WHERE team_id = " + team7 + " AND sku_id = " + sku7a));

        // (e) 生成列可建索引（idx_invbal_team_available 依赖它）
        assertEquals(1L, queryLong(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = 'inventory_balances' AND index_name = 'idx_invbal_team_available' "
                        + "AND column_name = 'available_qty'"),
                "idx_invbal_team_available 应覆盖 available_qty 列");
    }

    // =====================================================================
    // ⑫ version 归属
    // =====================================================================

    @Test
    @DisplayName("⑫ version 归属：策略表/余额表有 version；流水表无 version、无 updated_at/updated_by")
    void versionBelongsToPoliciesAndBalancesOnly() throws Exception {
        // 集合 A + version（DATABASE_DESIGN §7.4 表 A）
        List<String> setAWithVersion = List.of("created_at", "updated_at", "created_by", "updated_by", "version");
        List<String> policies = columnNames("replenishment_policies");
        List<String> balances = columnNames("inventory_balances");
        assertTrue(policies.containsAll(setAWithVersion), "replenishment_policies 应为集合 A + version");
        assertTrue(balances.containsAll(setAWithVersion), "inventory_balances 应为集合 A + version");

        // 集合 B（仅追加）且无 version（DATABASE_DESIGN §7.4 表 B：仅 INSERT → V1 不成立）
        List<String> txns = columnNames("inventory_transactions");
        assertTrue(txns.containsAll(List.of("created_at", "created_by")),
                "inventory_transactions 应含集合 B 字段");
        assertFalse(txns.contains("version"), "inventory_transactions 不得有 version（仅 INSERT 的账本）");
        assertFalse(txns.contains("updated_at"), "inventory_transactions 不得有 updated_at");
        assertFalse(txns.contains("updated_by"), "inventory_transactions 不得有 updated_by");

        // version 默认值 0 且 NOT NULL（与既有 catalog 迁移口径一致）
        execute(policyInsert(team7, sku7a, wh7));
        assertEquals(0L, queryLong("SELECT version FROM replenishment_policies WHERE team_id = " + team7),
                "version 默认值应为 0");
    }

    // =====================================================================
    // ⑬ 外键 RESTRICT
    // =====================================================================

    @Test
    @DisplayName("⑬ fk_invbal_sku RESTRICT：删除被余额引用的 SKU → 失败")
    void foreignKeyRestrictPreventsDeletingReferencedSku() throws Exception {
        insertBalance(team7, wh7, sku7a, "1", "0", "0");
        assertIntegrityViolation(() -> execute("DELETE FROM skus WHERE id = " + sku7a));

        // 未被引用的 SKU 可删（证明失败原因确实来自外键，而非语法/权限）
        execute("DELETE FROM skus WHERE id = " + sku7b);
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM skus"),
                "sku7a（被引用）+ sku8（他团队）应仍在");
    }

    // =====================================================================
    // ⑭ 外键方向：余额不指向流水
    // =====================================================================

    @Test
    @DisplayName("⑭ 外键方向：information_schema 中不存在 inventory_balances → inventory_transactions 的外键")
    void balancesHasNoForeignKeyToTransactions() throws Exception {
        assertEquals(0L, queryLong(
                        "SELECT COUNT(*) FROM information_schema.referential_constraints "
                                + "WHERE constraint_schema = DATABASE() "
                                + "AND table_name = 'inventory_balances' "
                                + "AND referenced_table_name = 'inventory_transactions'"),
                "账本↔快照不分强绑：last_txn_id 仅逻辑关联（ER_DIAGRAM §6.2）");

        // last_txn_id 列本身存在（只是不建外键）
        assertTrue(columnNames("inventory_balances").contains("last_txn_id"),
                "last_txn_id 列应存在（对账定位用）");

        // 余额表的外键恰 2 条，且目标为 warehouses / skus（DATABASE_CONSTRAINTS §5.2 #8–#9）
        List<String> refs = queryStrings(
                "SELECT referenced_table_name FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = DATABASE() AND table_name = 'inventory_balances' "
                        + "ORDER BY referenced_table_name");
        assertEquals(List.of("skus", "warehouses"), refs,
                "inventory_balances 的外键应恰为 fk_invbal_sku / fk_invbal_warehouse");
    }

    // =====================================================================
    // ⑮ 索引：team_id 最左 + 不建冗余索引
    // =====================================================================

    @Test
    @DisplayName("⑮ 七条二级索引 team_id 最左；且不建冗余的 idx_invbal_team_wh")
    void indexesAreTeamIdLeftmostWithoutRedundantOne() throws Exception {
        for (String[] pair : EXPECTED_INDEXES) {
            String table = pair[0];
            String index = pair[1];
            assertEquals("team_id", queryString(
                            "SELECT column_name FROM information_schema.statistics "
                                    + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                                    + "AND index_name = '" + index + "' AND seq_in_index = 1"),
                    table + "." + index + " 的最左列必须是 team_id（DESIGN_BASIS §6 规则 6）");
        }

        // 四条唯一键同样 team_id 最左（DATABASE_CONSTRAINTS §2.1 #9–#12）
        for (String[] pair : new String[][]{
                {"replenishment_policies", "uk_pol_team_sku_wh"},
                {"inventory_balances", "uk_invbal_team_wh_sku"},
                {"inventory_transactions", "uk_invtxn_team_idem"},
                {"inventory_transactions", "uk_invtxn_team_reversed"}}) {
            assertEquals("team_id", queryString(
                            "SELECT column_name FROM information_schema.statistics "
                                    + "WHERE table_schema = DATABASE() AND table_name = '" + pair[0] + "' "
                                    + "AND index_name = '" + pair[1] + "' AND seq_in_index = 1"),
                    pair[0] + "." + pair[1] + " 的最左列必须是 team_id");
        }

        // ★ 不建 idx_invbal_team_wh：与 uk_invbal_team_wh_sku 前缀重叠，约束清单 #16/#17 只有两条
        assertEquals(0L, queryLong(
                        "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                                + "AND table_name = 'inventory_balances' AND index_name = 'idx_invbal_team_wh'"),
                "不得创建冗余索引 idx_invbal_team_wh（与唯一键前缀重叠）");

        // 二级索引恰为 7 条（唯一键不计）：逐表核对
        assertEquals(2L, queryLong(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'replenishment_policies' "
                        + "AND index_name LIKE 'idx\\_%'"));
        assertEquals(2L, queryLong(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'inventory_balances' "
                        + "AND index_name LIKE 'idx\\_%'"));
        assertEquals(3L, queryLong(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'inventory_transactions' "
                        + "AND index_name LIKE 'idx\\_%'"));
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private static String policyInsert(long teamId, long skuId, long warehouseId) {
        return "INSERT INTO replenishment_policies (team_id, sku_id, warehouse_id) "
                + "VALUES (" + teamId + ", " + skuId + ", " + warehouseId + ")";
    }

    private static void insertBalance(long teamId, long whId, long skuId,
                                      String onHand, String inTransit, String committed) throws Exception {
        execute("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, "
                + "on_hand_qty, in_transit_qty, committed_qty) "
                + "VALUES (" + teamId + ", " + whId + ", " + skuId + ", "
                + onHand + ", " + inTransit + ", " + committed + ")");
    }

    /** 构造一条流水 INSERT；{@code reversedTxnId} 为 {@code null} 时写 SQL NULL。 */
    private static String txnInsert(long teamId, long whId, long skuId, String txnType, String bizType,
                                    String delta, String before, String after, String idem, Long reversedTxnId) {
        return "INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                + "qty_before, qty_after, biz_type, idempotency_key, reversed_txn_id, occurred_at) "
                + "VALUES (" + teamId + ", " + whId + ", " + skuId + ", '" + txnType + "', " + delta + ", "
                + before + ", " + after + ", '" + bizType + "', '" + idem + "', "
                + (reversedTxnId == null ? "NULL" : reversedTxnId) + ", '2026-10-17 08:00:00.000')";
    }

    /** 插入一条合法流水并返回主键（供「冲销指向原流水」用例使用）。 */
    private static long insertTxn(long teamId, long whId, long skuId, String txnType, String bizType,
                                  String delta, String before, String after, String idem,
                                  Long reversedTxnId) throws Exception {
        execute(txnInsert(teamId, whId, skuId, txnType, bizType, delta, before, after, idem, reversedTxnId));
        return queryLong("SELECT id FROM inventory_transactions WHERE team_id = " + teamId
                + " AND idempotency_key = '" + idem + "'");
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

    /** 可抛受检异常的 SQL 动作。 */
    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }
}
