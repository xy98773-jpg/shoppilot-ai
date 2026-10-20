package com.shoppilot.purchasing;

import com.shoppilot.common.tenant.TenantTables;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
 * M6-T01 采购四表迁移与约束 —— 端到端结构测试（真实 MySQL 8.0.36 + 真实 Flyway）。
 *
 * <p>覆盖 C1–C7：
 * <ul>
 *   <li>四表存在、迁移版本 <b>12</b>（V1.0.0 → V1.0.11）；</li>
 *   <li>★ <b>两个幂等键 NULL 性相反</b>：{@code purchase_orders.idempotency_key} 可空、
 *       {@code goods_receipts.idempotency_key} <b>NOT NULL</b>；</li>
 *   <li>★ {@code goods_receipt_items} <b>无</b> {@code updated_at}/{@code updated_by}/{@code version}
 *       （集合 B），前三表 {@code version} 为 {@code int unsigned NOT NULL}；</li>
 *   <li>6 条唯一键的列集合（含两条<b>不带 team_id</b>）、11 条索引且 team_id 最左、
 *       9 条外键全 RESTRICT、四表 {@code team_id} NOT NULL 且无 DEFAULT；</li>
 *   <li>★ {@code ck_poi_received_range} 边界三例（= / +0.001 / −0.001）与
 *       {@code ordered_qty=0} 被拒；</li>
 *   <li>其余 CHECK 逐条负向；唯一键撞键；外键 RESTRICT 行为。</li>
 * </ul>
 *
 * <p>不启动 Spring 上下文：手工装配 Flyway + {@link DriverManagerDataSource}，断言最终数据库状态。
 */
@Testcontainers
class PurchasingSchemaConstraintTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 唯一键名（ASCII 字典序：{@code uk_gr_*} < {@code uk_gri_*} < {@code uk_po_*}）。 */
    private static final List<String> EXPECTED_UNIQUE_INDEXES = List.of(
            "uk_gr_team_idem", "uk_gr_team_no", "uk_gri_receipt_poi",
            "uk_po_team_idem", "uk_po_team_no", "uk_poi_po_lino");

    /** 业务索引名（ASCII 字典序：{@code idx_gr_*} < {@code idx_gri_*} < {@code idx_po_*}）。 */
    private static final List<String> EXPECTED_INDEXES = List.of(
            "idx_gr_team_date", "idx_gr_team_po_status", "idx_gr_team_status_time",
            "idx_gri_team_poi_time", "idx_gri_team_receipt",
            "idx_po_team_expected", "idx_po_team_status_time", "idx_po_team_supplier_status",
            "idx_poi_team_po", "idx_poi_team_repl", "idx_poi_team_sku");

    /** 外键名（ASCII 字典序）。 */
    private static final List<String> EXPECTED_FKS = List.of(
            "fk_gr_po", "fk_gr_warehouse", "fk_gri_poi", "fk_gri_receipt", "fk_gri_sku",
            "fk_po_supplier", "fk_po_warehouse", "fk_poi_po", "fk_poi_sku");

    private static DataSource dataSource;

    private static long team7;
    private static long supplier7;
    private static long warehouse7;
    private static long product7;
    private static long sku7;

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
        execute("DELETE FROM goods_receipt_items");
        execute("DELETE FROM goods_receipts");
        execute("DELETE FROM purchase_order_items");
        execute("DELETE FROM purchase_orders");
        execute("DELETE FROM skus");
        execute("DELETE FROM products");
        execute("DELETE FROM suppliers");
        execute("DELETE FROM warehouses");
        execute("DELETE FROM team_members");
        execute("DELETE FROM users");
        execute("DELETE FROM teams");

        team7 = insertTeam("T-PUR-7", "采购组");
        supplier7 = insertSupplier(team7, "SUP-P7");
        warehouse7 = insertWarehouse(team7, "WH-P7");
        product7 = insertProduct(team7, "P-P7");
        sku7 = insertSku(team7, product7, "SKU-P7");
    }

    // =====================================================================
    // C1：表 / 迁移版本 / 列
    // =====================================================================

    @Test
    @DisplayName("C1 四表存在且迁移版本 12；幂等键 NULL 性相反；集合 B 表无 version；精度列正确")
    void tablesColumnsAndVersionCount() throws Exception {
        List<String> tables = queryStrings("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = DATABASE()");
        assertTrue(tables.contains("purchase_orders"));
        assertTrue(tables.contains("purchase_order_items"));
        assertTrue(tables.contains("goods_receipts"));
        assertTrue(tables.contains("goods_receipt_items"));

        List<String> versions = queryStrings(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        assertEquals(12, versions.size(), "应应用 V1.0.0 → V1.0.11 共 12 条迁移");
        assertEquals("1.0.11", versions.get(11), "最新一条应为 V1.0.11（purchasing）");

        // ★ 两个幂等键 NULL 性相反
        assertEquals("YES", columnNullable("purchase_orders", "idempotency_key"),
                "purchase_orders.idempotency_key 必须可空");
        assertEquals("NO", columnNullable("goods_receipts", "idempotency_key"),
                "★ goods_receipts.idempotency_key 必须 NOT NULL");

        // ★ 集合 B 表：goods_receipt_items 无 updated_at / updated_by / version
        Set<String> griCols = new TreeSet<>(columnNames("goods_receipt_items"));
        assertFalse(griCols.contains("updated_at"), "★ 到货明细仅追加：不得有 updated_at");
        assertFalse(griCols.contains("updated_by"), "★ 到货明细仅追加：不得有 updated_by");
        assertFalse(griCols.contains("version"), "★ 到货明细仅追加：不得有 version");
        assertTrue(griCols.containsAll(List.of("created_at", "created_by")), "应含集合 B 字段");

        // 前三表 version = int unsigned NOT NULL
        for (String table : List.of("purchase_orders", "purchase_order_items", "goods_receipts")) {
            assertEquals("int unsigned", columnType(table, "version"), table + ".version 类型");
            assertEquals("NO", columnNullable(table, "version"), table + ".version 必须 NOT NULL");
            assertEquals("0", columnDefault(table, "version"), table + ".version 默认 0");
        }

        // 精度列（§14.2）
        assertEquals("decimal(18,3)", columnType("purchase_orders", "total_qty"));
        assertEquals("decimal(18,3)", columnType("purchase_orders", "received_qty"));
        assertEquals("decimal(18,2)", columnType("purchase_orders", "total_amount"));
        assertEquals("decimal(18,3)", columnType("purchase_order_items", "ordered_qty"));
        assertEquals("decimal(18,3)", columnType("purchase_order_items", "received_qty"));
        assertEquals("decimal(18,4)", columnType("purchase_order_items", "unit_price"));
        assertEquals("decimal(18,2)", columnType("purchase_order_items", "amount"));
        assertEquals("decimal(18,3)", columnType("goods_receipt_items", "received_qty"));
        assertEquals("decimal(18,4)", columnType("goods_receipt_items", "unit_price"));
        assertEquals("decimal(18,2)", columnType("goods_receipt_items", "amount"));
        assertEquals("date", columnType("purchase_orders", "expected_arrival_date"));
        assertEquals("date", columnType("goods_receipts", "receipt_date"));
        assertEquals("datetime(3)", columnType("purchase_orders", "created_at"));
        assertEquals("int unsigned", columnType("purchase_order_items", "line_no"));

        // 四表 team_id NOT NULL 且无 DEFAULT
        for (String table : List.of("purchase_orders", "purchase_order_items",
                "goods_receipts", "goods_receipt_items")) {
            assertEquals("NO", columnNullable(table, "team_id"), table + ".team_id 必须 NOT NULL");
            assertNull(columnDefault(table, "team_id"), table + ".team_id 不得有 DEFAULT");
            assertTrue(TenantTables.hasTeamIdColumn(table), table + " 不得登记进黑名单");
        }
        // C7：M2 黑名单未变
        assertEquals(3, Set.of("users", "teams", "refresh_tokens").size(), "M2 黑名单仍只有 3 张表");
    }

    // =====================================================================
    // C1（续）：唯一键 / 索引 / 外键
    // =====================================================================

    @Test
    @DisplayName("C1 6 条唯一键列集合正确（两条不带 team_id）；11 条索引均 team_id 最左；9 条外键全 RESTRICT")
    void uniqueKeysIndexesAndForeignKeys() throws Exception {
        // 唯一键（按名字精确比对列集合）
        assertEquals(List.of("team_id", "po_no"), indexColumns("purchase_orders", "uk_po_team_no"));
        assertEquals(List.of("team_id", "idempotency_key"), indexColumns("purchase_orders", "uk_po_team_idem"));
        assertEquals(List.of("purchase_order_id", "line_no"), indexColumns("purchase_order_items", "uk_poi_po_lino"));
        assertEquals(List.of("team_id", "receipt_no"), indexColumns("goods_receipts", "uk_gr_team_no"));
        assertEquals(List.of("team_id", "idempotency_key"), indexColumns("goods_receipts", "uk_gr_team_idem"));
        assertEquals(List.of("goods_receipt_id", "purchase_order_item_id"),
                indexColumns("goods_receipt_items", "uk_gri_receipt_poi"));
        // ★ 两条不带 team_id（由同团队父表派生）
        assertFalse(indexColumns("purchase_order_items", "uk_poi_po_lino").contains("team_id"));
        assertFalse(indexColumns("goods_receipt_items", "uk_gri_receipt_poi").contains("team_id"));

        // 唯一键总数 6
        List<String> uniques = queryStrings("SELECT DISTINCT index_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND non_unique = 0 AND index_name LIKE 'uk\\_%' "
                + "AND table_name IN ('purchase_orders','purchase_order_items','goods_receipts',"
                + "'goods_receipt_items') ORDER BY index_name");
        assertEquals(EXPECTED_UNIQUE_INDEXES, uniques);

        // 业务索引 11 条，均 team_id 最左
        List<String> indexes = queryStrings("SELECT DISTINCT index_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND index_name LIKE 'idx\\_%' "
                + "AND table_name IN ('purchase_orders','purchase_order_items','goods_receipts',"
                + "'goods_receipt_items') ORDER BY index_name");
        assertEquals(EXPECTED_INDEXES, indexes, "业务索引应为 #43–#53 共 11 条");
        for (String idx : indexes) {
            String table = indexTable(idx);
            assertEquals("team_id", leftmostColumn(table, idx), "★ 索引 " + idx + " 最左列必须是 team_id");
        }

        // 外键 9 条，全 RESTRICT
        List<String> fks = queryStrings("SELECT constraint_name FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND table_name IN ('purchase_orders',"
                + "'purchase_order_items','goods_receipts','goods_receipt_items') ORDER BY constraint_name");
        assertEquals(EXPECTED_FKS, fks);
        for (String fk : fks) {
            assertEquals("RESTRICT", fkRule(fk, "delete_rule"), fk + " 删除规则");
            assertEquals("RESTRICT", fkRule(fk, "update_rule"), fk + " 更新规则");
        }
        assertEquals("suppliers", fkReferenced("fk_po_supplier"));
        assertEquals("warehouses", fkReferenced("fk_po_warehouse"));
        assertEquals("purchase_orders", fkReferenced("fk_poi_po"));
        assertEquals("skus", fkReferenced("fk_poi_sku"));
        assertEquals("purchase_orders", fkReferenced("fk_gr_po"));
        assertEquals("warehouses", fkReferenced("fk_gr_warehouse"));
        assertEquals("goods_receipts", fkReferenced("fk_gri_receipt"));
        assertEquals("purchase_order_items", fkReferenced("fk_gri_poi"));
        assertEquals("skus", fkReferenced("fk_gri_sku"));
    }

    // =====================================================================
    // C2：幂等键（验收达标 1）
    // =====================================================================

    @Test
    @DisplayName("C2 goods_receipts 省略/NULL idempotency_key ⇒ DB 拒绝；同键第二条 ⇒ uk_gr_team_idem 拒绝")
    void goodsReceiptIdempotencyKeyIsMandatory() throws Exception {
        long po = insertPo("PO-C2-1", null, "DRAFT", "MANUAL");

        // ① 省略 idempotency_key ⇒ NOT NULL 拒绝
        assertIntegrityViolation(() -> execute("INSERT INTO goods_receipts (team_id, receipt_no, "
                + "purchase_order_id, warehouse_id, receipt_date) VALUES ("
                + team7 + ", 'GR-C2-A', " + po + ", " + warehouse7 + ", '2026-03-01')"));

        // ② 显式 NULL ⇒ 同样拒绝
        assertIntegrityViolation(() -> execute("INSERT INTO goods_receipts (team_id, receipt_no, "
                + "purchase_order_id, warehouse_id, receipt_date, idempotency_key) VALUES ("
                + team7 + ", 'GR-C2-B', " + po + ", " + warehouse7 + ", '2026-03-01', NULL)"));

        // ③ 同键第二条 ⇒ uk_gr_team_idem 拒绝
        insertGr("GR-C2-C", "idem-C2", po);
        assertIntegrityViolation(() -> insertGr("GR-C2-D", "idem-C2", po));

        // ④ 对照：purchase_orders 的幂等键可空（多行 NULL 合法）
        insertPo("PO-C2-2", null, "DRAFT", "MANUAL");
        insertPo("PO-C2-3", null, "DRAFT", "MANUAL");
        assertEquals(3L, queryLong("SELECT COUNT(*) FROM purchase_orders "
                + "WHERE idempotency_key IS NULL"),
                "purchase_orders 幂等键可空 ⇒ PO-C2-1/2/3 三行 NULL 可同时存在（唯一键对 NULL 不去重）");
    }

    // =====================================================================
    // C3：ck_poi_received_range 边界（验收达标 2）
    // =====================================================================

    @Test
    @DisplayName("C3 ck_poi_received_range：=ordered 通过；+0.001 拒绝；−0.001 拒绝；ordered_qty=0 被拒")
    void receivedQtyRangeBoundary() throws Exception {
        long po = insertPo("PO-C3", null, "DRAFT", "MANUAL");

        // ① 等于 ⇒ 通过
        insertPoi(po, 1, "10.000", "10.000");

        // ② 超额 +0.001 ⇒ 拒绝
        assertIntegrityViolation(() -> insertPoi(po, 2, "10.000", "10.001"));

        // ③ 负数 −0.001 ⇒ 拒绝
        assertIntegrityViolation(() -> insertPoi(po, 3, "10.000", "-0.001"));

        // ④ ordered_qty = 0 ⇒ 被 ck_poi_ordered_positive 拒绝
        assertIntegrityViolation(() -> insertPoi(po, 4, "0.000", "0.000"));

        // ⑤ 中间值合法（部分到货）
        insertPoi(po, 5, "10.000", "5.000");

        assertEquals(2L, queryLong("SELECT COUNT(*) FROM purchase_order_items WHERE purchase_order_id = " + po));
    }

    // =====================================================================
    // C4：其余 CHECK 负向
    // =====================================================================

    @Test
    @DisplayName("C4 其余 CHECK：非法状态/来源/负汇总/零到货量 全部拒绝")
    void otherChecksRejectInvalidValues() throws Exception {
        // ck_po_status / ck_po_source
        assertIntegrityViolation(() -> insertPo("PO-C4-1", null, "BOGUS", "MANUAL"));
        assertIntegrityViolation(() -> insertPo("PO-C4-2", null, "DRAFT", "BOGUS"));

        // ck_po_total_qty / ck_po_received_qty
        assertIntegrityViolation(() -> execute("INSERT INTO purchase_orders (team_id, po_no, supplier_id, "
                + "warehouse_id, total_qty) VALUES (" + team7 + ", 'PO-C4-3', " + supplier7 + ", "
                + warehouse7 + ", -1)"));
        assertIntegrityViolation(() -> execute("INSERT INTO purchase_orders (team_id, po_no, supplier_id, "
                + "warehouse_id, received_qty) VALUES (" + team7 + ", 'PO-C4-4', " + supplier7 + ", "
                + warehouse7 + ", -0.001)"));

        long po = insertPo("PO-C4-5", null, "DRAFT", "MANUAL");
        long poi = insertPoi(po, 1, "10.000", "0.000");

        // ck_gr_status
        assertIntegrityViolation(() -> execute("INSERT INTO goods_receipts (team_id, receipt_no, "
                + "purchase_order_id, warehouse_id, receipt_date, idempotency_key, status) VALUES ("
                + team7 + ", 'GR-C4-1', " + po + ", " + warehouse7 + ", '2026-03-01', 'idem-C4-1', 'BOGUS')"));

        // ck_gr_total_qty
        assertIntegrityViolation(() -> execute("INSERT INTO goods_receipts (team_id, receipt_no, "
                + "purchase_order_id, warehouse_id, receipt_date, idempotency_key, total_qty) VALUES ("
                + team7 + ", 'GR-C4-2', " + po + ", " + warehouse7 + ", '2026-03-01', 'idem-C4-2', -1)"));

        // ck_gri_received_positive（= 0 被拒）
        long grC4 = insertGr("GR-C4-3", "idem-C4-3", po);
        assertIntegrityViolation(() -> insertGri(grC4, poi, "0.000"));
    }

    // =====================================================================
    // C5：唯一键撞键
    // =====================================================================

    @Test
    @DisplayName("C5 uk_poi_po_lino / uk_gri_receipt_poi 撞键拒绝，换父通过")
    void derivedUniqueKeysRejectDuplicates() throws Exception {
        long po = insertPo("PO-C5", null, "DRAFT", "MANUAL");
        long poi = insertPoi(po, 1, "10.000", "0.000");

        // 同 (po, line_no) ⇒ 拒绝；换行号 ⇒ 通过
        assertIntegrityViolation(() -> insertPoi(po, 1, "20.000", "0.000"));
        insertPoi(po, 2, "20.000", "0.000");

        // 同 (gr, poi) ⇒ 拒绝；换到货单 ⇒ 通过
        long gr = insertGr("GR-C5-1", "idem-C5-1", po);
        insertGri(gr, poi, "1.000");
        assertIntegrityViolation(() -> insertGri(gr, poi, "2.000"));
        long gr2 = insertGr("GR-C5-2", "idem-C5-2", po);
        insertGri(gr2, poi, "3.000");
    }

    // =====================================================================
    // C6：外键 RESTRICT 行为
    // =====================================================================

    @Test
    @DisplayName("C6 外键 RESTRICT：删被引用的采购单 / 到货单 均失败")
    void foreignKeysRestrictDeletes() throws Exception {
        long po = insertPo("PO-C6", null, "DRAFT", "MANUAL");
        long poi = insertPoi(po, 1, "10.000", "0.000");
        long gr = insertGr("GR-C6", "idem-C6", po);
        insertGri(gr, poi, "1.000");

        assertIntegrityViolation(() -> execute("DELETE FROM purchase_orders WHERE id = " + po));
        assertIntegrityViolation(() -> execute("DELETE FROM goods_receipts WHERE id = " + gr));
        assertIntegrityViolation(() -> execute("DELETE FROM purchase_order_items WHERE id = " + poi));
    }

    // =====================================================================
    // 种子与工具
    // =====================================================================

    private long insertTeam(String code, String name) throws Exception {
        execute("INSERT INTO teams (code, name, status, default_timezone) VALUES ('" + code + "', '"
                + name + "', 'ACTIVE', 'Asia/Shanghai')");
        return queryLong("SELECT id FROM teams WHERE code = '" + code + "'");
    }

    private long insertSupplier(long teamId, String code) throws Exception {
        execute("INSERT INTO suppliers (team_id, code, name) VALUES (" + teamId + ", '" + code + "', '"
                + code + "')");
        return queryLong("SELECT id FROM suppliers WHERE team_id = " + teamId + " AND code = '" + code + "'");
    }

    private long insertWarehouse(long teamId, String code) throws Exception {
        execute("INSERT INTO warehouses (team_id, code, name) VALUES (" + teamId + ", '" + code + "', '"
                + code + "')");
        return queryLong("SELECT id FROM warehouses WHERE team_id = " + teamId + " AND code = '" + code + "'");
    }

    private long insertProduct(long teamId, String code) throws Exception {
        execute("INSERT INTO products (team_id, product_code, name) VALUES (" + teamId + ", '" + code
                + "', '" + code + "')");
        return queryLong("SELECT id FROM products WHERE team_id = " + teamId + " AND product_code = '"
                + code + "'");
    }

    private long insertSku(long teamId, long productId, String code) throws Exception {
        execute("INSERT INTO skus (team_id, product_id, sku_code) VALUES (" + teamId + ", " + productId
                + ", '" + code + "')");
        return queryLong("SELECT id FROM skus WHERE team_id = " + teamId + " AND sku_code = '" + code + "'");
    }

    private long insertPo(String poNo, String idemKey, String status, String source) throws Exception {
        String idem = idemKey == null ? "NULL" : "'" + idemKey + "'";
        execute("INSERT INTO purchase_orders (team_id, po_no, supplier_id, warehouse_id, status, source, "
                + "idempotency_key) VALUES (" + team7 + ", '" + poNo + "', " + supplier7 + ", "
                + warehouse7 + ", '" + status + "', '" + source + "', " + idem + ")");
        return queryLong("SELECT id FROM purchase_orders WHERE team_id = " + team7 + " AND po_no = '"
                + poNo + "'");
    }

    private long insertPoi(long poId, int lineNo, String orderedQty, String receivedQty) throws Exception {
        execute("INSERT INTO purchase_order_items (team_id, purchase_order_id, line_no, sku_id, product_id, "
                + "ordered_qty, received_qty) VALUES (" + team7 + ", " + poId + ", " + lineNo + ", "
                + sku7 + ", " + product7 + ", " + orderedQty + ", " + receivedQty + ")");
        return queryLong("SELECT id FROM purchase_order_items WHERE purchase_order_id = " + poId
                + " AND line_no = " + lineNo);
    }

    private long insertGr(String receiptNo, String idemKey, long poId) throws Exception {
        execute("INSERT INTO goods_receipts (team_id, receipt_no, purchase_order_id, warehouse_id, "
                + "receipt_date, idempotency_key) VALUES (" + team7 + ", '" + receiptNo + "', " + poId
                + ", " + warehouse7 + ", '2026-03-01', '" + idemKey + "')");
        return queryLong("SELECT id FROM goods_receipts WHERE team_id = " + team7 + " AND receipt_no = '"
                + receiptNo + "'");
    }

    private long insertGri(long grId, long poiId, String receivedQty) throws Exception {
        execute("INSERT INTO goods_receipt_items (team_id, goods_receipt_id, purchase_order_item_id, "
                + "sku_id, received_qty) VALUES (" + team7 + ", " + grId + ", " + poiId + ", " + sku7
                + ", " + receivedQty + ")");
        return queryLong("SELECT id FROM goods_receipt_items WHERE goods_receipt_id = " + grId
                + " AND purchase_order_item_id = " + poiId);
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

    private static List<String> indexColumns(String table, String index) throws Exception {
        return queryStrings("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name = '" + index + "' ORDER BY seq_in_index");
    }

    private static String indexTable(String index) throws Exception {
        return queryString("SELECT table_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND index_name = '" + index + "' LIMIT 1");
    }

    private static String leftmostColumn(String table, String index) throws Exception {
        return queryString("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                + "AND index_name = '" + index + "' AND seq_in_index = 1");
    }

    private static String fkRule(String fk, String column) throws Exception {
        return queryString("SELECT " + column + " FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND constraint_name = '" + fk + "'");
    }

    private static String fkReferenced(String fk) throws Exception {
        return queryString("SELECT referenced_table_name FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND constraint_name = '" + fk + "'");
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }

    private static void assertIntegrityViolation(SqlAction sql) {
        SQLException ex;
        try {
            sql.run();
            throw new AssertionError("期望触发完整性约束（唯一/外键/CHECK/NOT NULL），但语句成功执行");
        } catch (SQLException e) {
            ex = e;
        } catch (Exception e) {
            throw new AssertionError("期望触发完整性约束（SQLState 23xx），实际抛出的不是 SQLException：" + e, e);
        }
        String state = ex.getSQLState();
        String msg = ex.getMessage();
        boolean isIntegrity = (state != null && state.startsWith("23"))
                || (msg != null && (msg.contains("Check constraint")
                        || msg.contains("cannot be null")
                        || msg.contains("doesn't have a default value")));
        assertTrue(isIntegrity,
                "应触发完整性约束（唯一/外键 SQLState 23xx、CHECK 或 NOT NULL），实际：" + state + " / " + msg);
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
