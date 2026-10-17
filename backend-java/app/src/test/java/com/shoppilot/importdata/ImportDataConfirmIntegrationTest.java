package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>M3-B10.5（R78）：{@code INVENTORY_SNAPSHOT} / {@code SALES_DAILY} 导入落库</b>端到端集成测试
 * ——真栈（Spring 上下文 + Testcontainers MySQL 8.0.36 + 真实 HTTP）。
 *
 * <p><b>缺陷背景</b>：{@code ImportConfirmService.SUPPORTED_BIZ_TYPES} 旧实现只含四类主数据，
 * 两类数据走完 上传→预览→映射→校验 后在 confirm 必返 {@code 400 COM-0001}
 * （{@code details[bizType]}），导致销售数据唯一入口失效、期初库存无法导入。</p>
 *
 * <p><b>本类覆盖</b>（任务书【3】E 的 1–10 项，其中第 10 项「既有四类回归」由
 * {@code ImportConfirmIntegrationTest} 全量保留承担）：</p>
 * <ol>
 *   <li>{@code SALES_DAILY} 全链路 → 字段逐一对齐 {@code sales_daily}；</li>
 *   <li>{@code SALES_DAILY} 二次 confirm → 重放语义，不重复写入；</li>
 *   <li>{@code SALES_DAILY} 命中库内同键行 → <b>覆盖</b>（不累加）+ {@code IMP-4006}；
 *       并验证 {@code SALES_DAILY_UPSERT} 审计 {@code change_summary} 含被覆盖行数；</li>
 *   <li>{@code INVENTORY_SNAPSHOT} 全链路 → 余额<b>增量</b> + {@code INIT_IMPORT} 流水
 *       （{@code biz_type=IMPORT}、{@code biz_ref_id=batchId}、幂等键字面形态）；</li>
 *   <li>{@code INVENTORY_SNAPSHOT} 二次 confirm → 不重复累加；</li>
 *   <li>{@code INVENTORY_SNAPSHOT} 已有余额行 → {@code IMP-4006} {@code WARNING} 可见；</li>
 *   <li>未知 {@code sku_code} → 行级 {@code CAT-3001} + {@code PARTIAL_SUCCESS}；</li>
 *   <li>未知 {@code warehouse_code} → 行级 {@code CAT-3003}；</li>
 *   <li>跨团队 {@code sku_code} → 行级失败，数据不落本队。</li>
 * </ol>
 *
 * <p>★ 第 7/8/9 项走的是<b>引用漂移</b>场景（校验通过后再改库）——这是唯一能真实到达
 * {@code confirm} 行级失败分支的路径：若在文件里直接写不存在的编码，
 * {@code validate} 阶段就会给出 {@code ERROR}，{@code confirm} 会被 {@code IMP-4009} 前置门拦下
 * （那是正确行为，不是本批要改的）。</p>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportDataConfirmIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-data-confirm-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OWNER_ID = 1004L;
    private static final long OPERATOR_ID = 1001L;
    private static final long OTHER_OWNER_ID = 2004L;

    /** 过去日期（{@code SAL-6003} 只拦未来日期；写死过去日期避免时钟抖动）。 */
    private static final String PAST_DATE = "2026-10-01";
    private static final String PAST_DATE_2 = "2026-10-02";

    private static final String OPERATOR = "operator01";

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
        registry.add("app.import.storage-dir", () -> STORAGE_DIR);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM import_row_errors");
        jdbc.execute("DELETE FROM import_batches");
        jdbc.execute("DELETE FROM audit_logs");
        // ★ 数据类表必须先于 skus / warehouses 清理（外键均 RESTRICT）
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM sales_daily");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);
        insertUser(OWNER_ID, "owner01");
        insertUser(OPERATOR_ID, OPERATOR);
        insertUser(OTHER_OWNER_ID, "owner8");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), "
                        + "(?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);

        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status) "
                + "VALUES (9003, ?, 'SUP-001', 'sup', 'ACTIVE')", TEAM_ID);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (9001, ?, 'P-BAG-001', 'Bag', 'ACTIVE')", TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                + "(9002, ?, 9001, 'SKU-A', 'Sku A', 'ACTIVE'), "
                + "(9003, ?, 9001, 'SKU-B', 'Sku B', 'ACTIVE')", TEAM_ID, TEAM_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) "
                + "VALUES (9004, ?, 'WH-MAIN', 'Main', 'ACTIVE')", TEAM_ID);
    }

    // =====================================================================
    // 1) SALES_DAILY 全链路 → 字段逐一对齐
    // =====================================================================

    @Test
    @DisplayName("R78-1 SALES_DAILY 全链路：confirm 200 COMPLETED，sales_daily 字段逐一对齐")
    void salesDailyHappyPathWritesRows() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",12.000,1.000,1080.00,8\n"
                + "SKU-B,WH-MAIN," + PAST_DATE_2 + ",5.500,0.000,495.00,4\n");
        long batchId = validatedBatch("sales.csv", csv, "SALES_DAILY", 0, 0);

        ResponseEntity<String> resp = confirm(batchId, "key-r78-sales-1", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText(), resp.getBody());
        assertEquals(2, data.get("total_rows").asInt());
        assertEquals(2, data.get("success_rows").asInt());
        assertEquals(0, data.get("failed_rows").asInt());

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sales_daily "
                + "WHERE team_id = ? ORDER BY sale_date", TEAM_ID);
        assertEquals(2, rows.size(), "sales_daily 应落 2 行");
        Map<String, Object> first = rows.get(0);
        assertEquals(9002L, ((Number) first.get("sku_id")).longValue());
        assertEquals(9004L, ((Number) first.get("warehouse_id")).longValue());
        assertEquals(9001L, ((Number) first.get("product_id")).longValue(), "product_id 由 skus 派生");
        assertEquals(PAST_DATE, String.valueOf(first.get("sale_date")));
        assertEquals(0, new BigDecimal("12.000").compareTo((BigDecimal) first.get("qty")));
        assertEquals(0, new BigDecimal("1.000").compareTo((BigDecimal) first.get("return_qty")));
        assertEquals(0, new BigDecimal("1080.00").compareTo((BigDecimal) first.get("sales_amount")));
        assertEquals(8, ((Number) first.get("order_count")).intValue());
        assertEquals("IMPORT", first.get("source"), "导入落库 source 必须是 IMPORT");
        assertEquals(batchId, ((Number) first.get("batch_id")).longValue(), "batch_id 须回指本批次");

        // 审计：SALES_DAILY_UPSERT 由 SalesWriteService 写（DATABASE_DESIGN §6-J.1 #17）
        Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE action = 'SALES_DAILY_UPSERT' AND biz_type = 'SALES_DAILY'", Integer.class);
        assertEquals(1, audits, "一个落库批次写一条 SALES_DAILY_UPSERT");
    }

    // =====================================================================
    // 2) SALES_DAILY 二次 confirm → 重放
    // =====================================================================

    @Test
    @DisplayName("R78-2 SALES_DAILY 重复确认：200 + Idempotent-Replayed，sales_daily 行数不变")
    void salesDailyRepeatConfirmIsReplayed() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",7.000\n");
        long batchId = validatedBatch("sales-replay.csv", csv, "SALES_DAILY", 0, 0);

        ResponseEntity<String> first = confirm(batchId, "key-r78-sales-2", Map.of());
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        assertEquals("COMPLETED", data(first).get("status").asText());
        assertEquals(1, count("sales_daily", TEAM_ID));

        ResponseEntity<String> second = confirm(batchId, "key-r78-sales-2", Map.of());
        assertEquals(HttpStatus.OK, second.getStatusCode(), second.getBody());
        assertEquals("true", second.getHeaders().getFirst("Idempotent-Replayed"),
                "重放须回 Idempotent-Replayed: true");
        assertEquals(1, count("sales_daily", TEAM_ID), "重放不得重复写入");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE action = 'SALES_DAILY_UPSERT'", Integer.class), "重放不得重复写审计");
    }

    // =====================================================================
    // 3) SALES_DAILY 覆盖 + IMP-4006 + 审计被覆盖行数
    // =====================================================================

    @Test
    @DisplayName("R78-3 SALES_DAILY 命中库内同键行：覆盖不累加 + IMP-4006 WARNING 可见")
    void salesDailyOverwritesExistingRowAndWarns() throws Exception {
        // 库内已有 (9002, 9004, PAST_DATE) 一行，qty=3.000
        jdbc.update("INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, "
                        + "qty, return_qty, sales_amount, order_count, source) "
                        + "VALUES (?, 9004, 9002, 9001, ?, 3.000, 0.000, 100.00, 1, 'MANUAL')",
                TEAM_ID, java.time.LocalDate.parse(PAST_DATE));

        byte[] csv = csv("sku_code,warehouse_code,sale_date,qty,sales_amount\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",9.000,900.00\n");
        // 校验阶段就应产生 1 行 WARNING（IMP-4006），且无 ERROR
        long batchId = validatedBatch("sales-overwrite.csv", csv, "SALES_DAILY", 0, 1);

        List<Map<String, Object>> warnings = jdbc.queryForList("SELECT row_no, error_code, error_level, "
                + "error_field, error_message FROM import_row_errors WHERE batch_id = ?", batchId);
        assertEquals(1, warnings.size(), "应写 1 条 IMP-4006 告警");
        assertEquals("WARNING", warnings.get(0).get("error_level"));
        assertEquals("IMP-4006", warnings.get(0).get("error_code"));
        assertEquals(1L, ((Number) warnings.get(0).get("row_no")).longValue());
        assertEquals("sale_date", warnings.get(0).get("error_field"));
        assertEquals("该日期已存在销售数据，确认导入后将覆盖", warnings.get(0).get("error_message"));

        ResponseEntity<String> resp = confirm(batchId, "key-r78-sales-3", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText(),
                "WARNING 不得阻断确认（IMP-4006 是 200 语义）");

        assertEquals(1, count("sales_daily", TEAM_ID), "覆盖：行数仍为 1（不累加）");
        Map<String, Object> row = jdbc.queryForMap("SELECT qty, sales_amount, source, batch_id "
                + "FROM sales_daily WHERE team_id = ?", TEAM_ID);
        assertEquals(0, new BigDecimal("9.000").compareTo((BigDecimal) row.get("qty")), "qty 被覆盖为新值");
        assertEquals(0, new BigDecimal("900.00").compareTo((BigDecimal) row.get("sales_amount")));
        assertEquals("IMPORT", row.get("source"), "source 亦被覆盖");
        assertEquals(batchId, ((Number) row.get("batch_id")).longValue());

        String summary = jdbc.queryForObject("SELECT change_summary FROM audit_logs "
                + "WHERE action = 'SALES_DAILY_UPSERT' ORDER BY id DESC LIMIT 1", String.class);
        assertNotNull(summary);
        assertTrue(summary.contains("覆盖 1 行"), "change_summary 必须含被覆盖行数，实际：" + summary);
    }

    // =====================================================================
    // 4) INVENTORY_SNAPSHOT 全链路 → 增量 + INIT_IMPORT 流水
    // =====================================================================

    @Test
    @DisplayName("R78-4 INVENTORY_SNAPSHOT 全链路：余额增量 + INIT_IMPORT 流水（biz_type=IMPORT，biz_ref_id=batchId）")
    void inventorySnapshotHappyPathIncrementsBalance() throws Exception {
        // 库内已有余额 10.000
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty, version) VALUES (?, 9004, 9002, 10.000, 0.000, 0.000, 0)",
                TEAM_ID);

        byte[] csv = csv("sku_code,warehouse_code,qty,snapshot_date,remark\n"
                + "SKU-A,WH-MAIN,120.000," + PAST_DATE + ",期初导入\n");
        // 已有余额 → 校验阶段给出 1 行 IMP-4006（增量累加提示）
        long batchId = validatedBatch("snapshot.csv", csv, "INVENTORY_SNAPSHOT", 0, 1);

        ResponseEntity<String> resp = confirm(batchId, "key-r78-inv-1", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText(), resp.getBody());
        assertEquals(1, data.get("success_rows").asInt());

        BigDecimal onHand = jdbc.queryForObject("SELECT on_hand_qty FROM inventory_balances "
                + "WHERE team_id = ? AND warehouse_id = 9004 AND sku_id = 9002", BigDecimal.class, TEAM_ID);
        assertEquals(0, new BigDecimal("130.000").compareTo(onHand), "10.000 + 120.000 = 130.000（增量）");

        List<Map<String, Object>> txns = jdbc.queryForList("SELECT * FROM inventory_transactions "
                + "WHERE team_id = ?", TEAM_ID);
        assertEquals(1, txns.size(), "应写 1 条 INIT_IMPORT 流水");
        Map<String, Object> txn = txns.get(0);
        assertEquals("INIT_IMPORT", txn.get("txn_type"));
        assertEquals("IMPORT", txn.get("biz_type"));
        assertEquals(batchId, ((Number) txn.get("biz_ref_id")).longValue(), "biz_ref_id 必须是批次 ID");
        assertEquals("INIT_IMPORT:IMPORT:" + batchId + ":9002", txn.get("idempotency_key"),
                "幂等键须按 DATABASE_DESIGN §5.10 要点 4 的字面构造");
        assertEquals(0, new BigDecimal("120.000").compareTo((BigDecimal) txn.get("qty_delta")));
        assertEquals(0, new BigDecimal("10.000").compareTo((BigDecimal) txn.get("qty_before")));
        assertEquals(0, new BigDecimal("130.000").compareTo((BigDecimal) txn.get("qty_after")));
    }

    // =====================================================================
    // 5) INVENTORY_SNAPSHOT 二次 confirm → 不重复累加
    // =====================================================================

    @Test
    @DisplayName("R78-5 INVENTORY_SNAPSHOT 重复确认：重放语义，余额与流水均不新增")
    void inventorySnapshotRepeatConfirmDoesNotAccumulateAgain() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,qty\nSKU-B,WH-MAIN,50.000\n");
        long batchId = validatedBatch("snapshot-replay.csv", csv, "INVENTORY_SNAPSHOT", 0, 0);

        ResponseEntity<String> first = confirm(batchId, "key-r78-inv-2", Map.of());
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        BigDecimal afterFirst = jdbc.queryForObject("SELECT on_hand_qty FROM inventory_balances "
                + "WHERE team_id = ? AND warehouse_id = 9004 AND sku_id = 9003", BigDecimal.class, TEAM_ID);
        assertEquals(0, new BigDecimal("50.000").compareTo(afterFirst), "首次导入后余额 50.000");

        ResponseEntity<String> second = confirm(batchId, "key-r78-inv-2", Map.of());
        assertEquals(HttpStatus.OK, second.getStatusCode(), second.getBody());
        assertEquals("true", second.getHeaders().getFirst("Idempotent-Replayed"));

        BigDecimal afterSecond = jdbc.queryForObject("SELECT on_hand_qty FROM inventory_balances "
                + "WHERE team_id = ? AND warehouse_id = 9004 AND sku_id = 9003", BigDecimal.class, TEAM_ID);
        assertEquals(0, new BigDecimal("50.000").compareTo(afterSecond), "重放不得重复累加");
        assertEquals(1, count("inventory_transactions", TEAM_ID), "重放不得新增流水");
    }

    // =====================================================================
    // 6) INVENTORY_SNAPSHOT 已有余额行 → IMP-4006 WARNING
    // =====================================================================

    @Test
    @DisplayName("R78-6 INVENTORY_SNAPSHOT 已有余额行 → IMP-4006 WARNING（error_level=WARNING，不阻断）")
    void inventorySnapshotWarnsWhenBalanceExists() throws Exception {
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty, version) VALUES (?, 9004, 9002, 1.000, 0.000, 0.000, 0)",
                TEAM_ID);

        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,5.000\n"
                + "SKU-B,WH-MAIN,6.000\n");
        long batchId = validatedBatch("snapshot-warn.csv", csv, "INVENTORY_SNAPSHOT", 0, 1);

        List<Map<String, Object>> errors = jdbc.queryForList("SELECT row_no, error_code, error_level, "
                + "error_message FROM import_row_errors WHERE batch_id = ? ORDER BY row_no", batchId);
        assertEquals(1, errors.size(), "只有命中已有余额的第 1 行产生告警");
        assertEquals(1L, ((Number) errors.get(0).get("row_no")).longValue());
        assertEquals("IMP-4006", errors.get(0).get("error_code"));
        assertEquals("WARNING", errors.get(0).get("error_level"));
        assertTrue(String.valueOf(errors.get(0).get("error_message")).contains("增量累加"),
                "文案须说明是增量累加（§7.5）");

        ResponseEntity<String> resp = confirm(batchId, "key-r78-inv-3", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText());
        assertEquals(2, count("inventory_transactions", TEAM_ID), "两行都要落库");
    }

    // =====================================================================
    // 7) 未知 sku_code（引用漂移）→ 行级 CAT-3001 + PARTIAL_SUCCESS
    // =====================================================================

    @Test
    @DisplayName("R78-7 未知 sku_code（校验后漂移）→ 行级 CAT-3001 + PARTIAL_SUCCESS + 不变式成立")
    void unknownSkuCodeYieldsPartialSuccess() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",4.000\n"
                + "SKU-B,WH-MAIN," + PAST_DATE + ",5.000\n");
        long batchId = validatedBatch("sales-drift-sku.csv", csv, "SALES_DAILY", 0, 0);

        // 校验通过后把 SKU-B 改名：confirm 时按编码解析不到 → 行级失败
        jdbc.update("UPDATE skus SET sku_code = 'SKU-B-RENAMED' WHERE team_id = ? AND id = 9003", TEAM_ID);

        ResponseEntity<String> resp = confirm(batchId, "key-r78-drift-sku", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARTIAL_SUCCESS", data.get("status").asText(), resp.getBody());
        assertEquals(1, data.get("success_rows").asInt());
        assertEquals(1, data.get("failed_rows").asInt());
        assertEquals(data.get("total_rows").asInt(),
                data.get("success_rows").asInt() + data.get("failed_rows").asInt(), "不变式");

        List<Map<String, Object>> errors = jdbc.queryForList("SELECT row_no, error_code, error_level, "
                + "raw_data FROM import_row_errors WHERE batch_id = ?", batchId);
        assertEquals(1, errors.size());
        assertEquals(2L, ((Number) errors.get(0).get("row_no")).longValue());
        assertEquals("CAT-3001", errors.get(0).get("error_code"));
        assertEquals("ERROR", errors.get(0).get("error_level"));
        assertNotNull(errors.get(0).get("raw_data"), "失败行须回带原始全列");

        assertEquals(1, count("sales_daily", TEAM_ID), "成功行照常提交");
        assertEquals(9002L, jdbc.queryForObject("SELECT sku_id FROM sales_daily WHERE team_id = ?",
                Long.class, TEAM_ID));
    }

    // =====================================================================
    // 8) 未知 warehouse_code（引用漂移）→ 行级 CAT-3003
    // =====================================================================

    @Test
    @DisplayName("R78-8 未知 warehouse_code（校验后漂移）→ 行级 CAT-3003")
    void unknownWarehouseCodeYieldsCat3003() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,qty\nSKU-A,WH-MAIN,30.000\n");
        long batchId = validatedBatch("snapshot-drift-wh.csv", csv, "INVENTORY_SNAPSHOT", 0, 0);

        jdbc.update("UPDATE warehouses SET code = 'WH-RENAMED' WHERE team_id = ? AND id = 9004", TEAM_ID);

        ResponseEntity<String> resp = confirm(batchId, "key-r78-drift-wh", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("FAILED", data.get("status").asText(), "唯一一行失败 → 终态 FAILED");
        assertEquals(0, data.get("success_rows").asInt());
        assertEquals(1, data.get("failed_rows").asInt());

        assertEquals("CAT-3003", jdbc.queryForObject("SELECT error_code FROM import_row_errors "
                + "WHERE batch_id = ?", String.class, batchId));
        assertEquals(0, count("inventory_transactions", TEAM_ID), "失败不得写流水");
        assertEquals(0, count("inventory_balances", TEAM_ID), "失败不得写余额");
    }

    // =====================================================================
    // 9) 跨团队 sku_code → 行级失败，不落本队
    // =====================================================================

    @Test
    @DisplayName("R78-9 跨团队 sku_code（改 team_id 后）→ 行级 CAT-3001，数据不落本队")
    void crossTeamSkuCodeDoesNotLand() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,sale_date,qty\nSKU-A,WH-MAIN," + PAST_DATE + ",2.000\n");
        long batchId = validatedBatch("sales-cross-team.csv", csv, "SALES_DAILY", 0, 0);

        // 把 SKU 划到别的团队：租户线使 getIdsByCodes 查不到 ⇒ 本队不得写入
        jdbc.update("UPDATE skus SET team_id = ? WHERE id = 9002", OTHER_TEAM_ID);

        ResponseEntity<String> resp = confirm(batchId, "key-r78-cross-team", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("FAILED", data(resp).get("status").asText());
        assertEquals("CAT-3001", jdbc.queryForObject("SELECT error_code FROM import_row_errors "
                + "WHERE batch_id = ?", String.class, batchId));
        assertEquals(0, count("sales_daily", TEAM_ID), "跨团队 SKU 不得落到本队");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales_daily WHERE sku_id = 9002",
                Integer.class), "也不得写到别队（本批根本没解析出 id）");
    }

    // =====================================================================
    // 10) 两类都必须经写服务（审计与流水形态是间接证据）
    // =====================================================================

    @Test
    @DisplayName("R78-10 数据类落库走写服务：INIT_IMPORT 流水 operator_id 来自登录用户，余额 version 自增")
    void writesGoThroughModuleServices() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,qty\nSKU-A,WH-MAIN,15.000\n");
        long batchId = validatedBatch("snapshot-svc.csv", csv, "INVENTORY_SNAPSHOT", 0, 0);

        ResponseEntity<String> resp = confirm(batchId, "key-r78-svc", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());

        Map<String, Object> txn = jdbc.queryForMap("SELECT operator_id, occurred_at, created_at "
                + "FROM inventory_transactions WHERE team_id = ?", TEAM_ID);
        assertEquals(OPERATOR_ID, ((Number) txn.get("operator_id")).longValue(),
                "operator_id 由 TeamContext 注入（直写 SQL 不会有）");
        assertNotNull(txn.get("occurred_at"));
        assertNotNull(txn.get("created_at"));

        Map<String, Object> balance = jdbc.queryForMap("SELECT on_hand_qty, available_qty, version, "
                + "last_txn_id FROM inventory_balances WHERE team_id = ? AND sku_id = 9002", TEAM_ID);
        assertEquals(0, new BigDecimal("15.000").compareTo((BigDecimal) balance.get("on_hand_qty")));
        assertEquals(0, new BigDecimal("15.000").compareTo((BigDecimal) balance.get("available_qty")),
                "available_qty 是生成列（on_hand - committed），仅由写服务维护");
        assertNotNull(balance.get("last_txn_id"), "写服务应回填 last_txn_id");
        assertTrue(((Number) balance.get("version")).intValue() >= 1, "写服务应递增 version");
    }

    // =====================================================================
    // 辅助：HTTP
    // =====================================================================

    private long uploadOk(String fileName, byte[] content, String bizType) throws Exception {
        ResponseEntity<String> response = upload(fileName, content, bizType);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return data(response).get("batch_id").asLong();
    }

    private ResponseEntity<String> upload(String fileName, byte[] content, String bizType) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(content, fileName));
        body.add("bizType", bizType);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token(OPERATOR));
        return rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> preview(long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(OPERATOR));
        return rest.exchange("/api/v1/import/batches/" + batchId + "/preview", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> mapping(long batchId, Map<String, String> fieldMapping) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(OPERATOR));
        Map<String, Object> body = Map.of("field_mapping_json", fieldMapping, "parse_options_json", Map.of());
        return rest.exchange("/api/v1/import/batches/" + batchId + "/mapping", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> validate(long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(OPERATOR));
        return rest.exchange("/api/v1/import/batches/" + batchId + "/validate", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> confirm(long batchId, String idempotencyKey, Map<String, Object> body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(OPERATOR));
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange("/api/v1/import/batches/" + batchId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /**
     * 上传 → 预览 → 映射（用服务端自动映射建议）→ 校验，并断言
     * {@code status=VALIDATED}、{@code error_rows} 与 {@code warning_rows} 的期望值。
     */
    private long validatedBatch(String fileName, byte[] csv, String bizType,
                                int expectedErrorRows, int expectedWarningRows) throws Exception {
        long batchId = uploadOk(fileName, csv, bizType);
        JsonNode suggested = data(preview(batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        assertEquals(HttpStatus.OK, mapping(batchId, map).getStatusCode());

        ResponseEntity<String> resp = validate(batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "校验应成功（非全失败）：" + resp.getBody());
        JsonNode data = data(resp);
        assertEquals("VALIDATED", data.get("status").asText(), resp.getBody());
        assertEquals(expectedErrorRows, data.get("error_rows").asInt(), "校验错误行数：" + resp.getBody());
        assertEquals(expectedWarningRows, data.get("warning_rows").asInt(), "校验告警行数：" + resp.getBody());
        return batchId;
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private int count(String table, long teamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE team_id = ?",
                Integer.class, teamId);
    }

    private String token(String username) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username
                + "\",\"password\":\"" + TestCredentials.RAW_PASSWORD + "\"}";
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + username);
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", TestCredentials.PASSWORD_HASH, username);
    }

    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class NamedResource extends org.springframework.core.io.ByteArrayResource {
        private final String filename;

        NamedResource(byte[] data, String filename) {
            super(data);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
