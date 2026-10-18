package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.web.BizException;
import com.shoppilot.inventory.application.InventoryWriteService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 *
 * <h2>★ M3-B10.6（R79 + R80）追加的第 11–15 项</h2>
 * <ol start="11">
 *   <li><b>R80-1</b> 同批同 SKU 跨两仓 → {@code COMPLETED}、两仓余额各自正确、
 *       两条幂等键后缀分别是两个 {@code warehouse_id}（修复前：第二条被误判重复提交）；</li>
 *   <li><b>R79-1</b> 同批同 SKU 同仓两行（数量不同）→ {@code PARTIAL_SUCCESS} + 行级
 *       {@code INV-5003}（<b>不是</b> {@code COM-0002}）+ 成功行已落库（修复前：整批 0 行）；</li>
 *   <li><b>R79-2</b> {@code SALES_DAILY} 写服务内部失败（校验后改 {@code listed_date}）→
 *       {@code PARTIAL_SUCCESS} + 行级 {@code SAL-6003}；</li>
 *   <li><b>R80-2</b> 跨仓批次二次确认 → {@code 200} + {@code Idempotent-Replayed: true}，
 *       两仓余额与流水均不增；</li>
 *   <li><b>R79-3/R79-4</b> 主数据 {@code PARTIAL_SUCCESS} 回归（{@code NESTED} 改动不得带坏主数据路径）
 *       + {@code PARTIAL_SUCCESS} 场景审计条数（成功行恰好 1 条、失败行不入审计、批次完成审计 1 条）。</li>
 * </ol>
 *
 * <p>★ 第 12/13 项（{@code R79}）的探针有效性已做<b>负向验证</b>：把两个写服务入口临时改回
 * {@code REQUIRED} 后，两项均失败且现象与缺陷报告一致（{@code status=FAILED}、
 * {@code success_rows=0}、{@code failed_rows=2}）；第 11 项（{@code R80}）同样在「键不含仓库」时
 * 失败（{@code PARTIAL_SUCCESS} 而非 {@code COMPLETED}）。见 {@code tools/b10.6/}。</p>
 *
 * <h2>★ M3-B10.7（R83）追加的第 16 节</h2>
 * <ol start="17">
 *   <li><b>R83-a</b> 同仓同 SKU <b>同数量</b>两行 → 后行 {@code ERROR COM-0001}、
 *       {@code confirm} 被 {@code IMP-4009} 拦下、余额与流水均不变；且该行
 *       <b>{@code ERROR} 与 {@code IMP-4006 WARNING} 并存</b>（R83 要求 5）；</li>
 *   <li><b>R83-b</b> 同仓同 SKU <b>不同数量</b>两行 → 同样后行 {@code ERROR}
 *       （<b>不是</b>留到 {@code confirm} 报 {@code INV-5003}）；</li>
 *   <li><b>R83-c</b> 同 SKU <b>跨两仓</b> → 不误杀，{@code COMPLETED}、两仓余额各自正确
 *       （{@code R80} 能力不得回退）；</li>
 *   <li><b>R83-d</b> 回归：{@code SALES_DAILY} 的 {@code SAL-6004} 与主数据的 {@code CAT-3002}
 *       行为一字不变。</li>
 * </ol>
 *
 * <p>★ 第 12 项（{@code R79-1}）在 B10.7 被<b>迁移到服务层</b>：R83 之后「同批同仓同 SKU 两行」
 * 在校验阶段即被拦下，{@code INV-5003} 在导入路径上不可达（这正是 R83 的目的），
 * 故改为「外层事务 + 同幂等键两次调用」直接验证 {@code NESTED} 行级隔离——
 * 探针强度不变，见方法内注释。</p>
 *
 * <h2>★ M3-B10.8（R85）追加的第 17 节：编码大小写一律不敏感等价</h2>
 * <p>依据 {@code IMPORT_WORKFLOW §8.6} 末行裁定；物理依据是唯一键 {@code (team_id, <code>)}
 * 受 {@code utf8mb4_0900_ai_ci} 约束 ⇒ 库内不可能并存 {@code SKU-A} 与 {@code sku-a}。
 * <ol start="18">
 *   <li><b>R85-a</b> 快照同仓 {@code SKU-A} + {@code sku-a} → 后行 {@code ERROR COM-0001}
 *       + {@code confirm} 422 {@code IMP-4009}；</li>
 *   <li><b>R85-b</b> 销售同 {@code (WH-MAIN, SKU-A, 同日期)} 但大小写不同 → 后行 {@code SAL-6004}；</li>
 *   <li><b>R85-c</b> SKU 主数据 {@code SKU-NEW-1} + {@code sku-new-1} → 后行 {@code CAT-3002}
 *       （证明第三处 {@code duplicateCode} 也改了）；</li>
 *   <li><b>R85-d</b> 回归：同仓同 SKU <b>同大小写</b>两行仍判重复（{@code R83} 不回退）；</li>
 *   <li><b>R85-e</b> <b>任务书未列</b>：单行<b>全小写</b>编码应能成功导入
 *       （覆盖 {@code §8.6} 同条款「所有 {@code code} 字段的<b>引用解析</b>」——
 *       {@code getIdsByCodes} 返回键是<b>库中原样写法</b>，不折叠则小写编码会被误报
 *       {@code CAT-3001}/{@code CAT-3003}）；</li>
 *   <li><b>R85-f</b> 同上：全小写编码也必须命中已有余额行并产生 {@code IMP-4006}
 *       （否则会被当作「引用不存在」而<b>漏报</b>告警）。</li>
 * </ol>
 *
 * <p>★ 这两项的探针有效性已负向验证：把 {@code ImportCodeKeys#normalize} 改成恒等返回后，
 * {@code R85-e}/{@code R85-f} 立即失败（{@code CAT-3001} 误报）。见 {@code tools/b10.8/}。</p>
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

    /** R79-1 的服务层探针（R83 之后该场景在导入路径上不可达，见方法注释）。 */
    @Autowired
    private InventoryWriteService inventoryWriteService;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        assertEquals("INIT_IMPORT:IMPORT:" + batchId + ":9002:9004", txn.get("idempotency_key"),
                "幂等键须按 DATABASE_DESIGN §5.10 要点 4 构造（R80：追加 :<warehouse_id>）");
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
    // 11) R80：同批同 SKU 跨两仓 → 合法输入（幂等键含仓库维度）
    // =====================================================================

    @Test
    @DisplayName("R80-1 同批同 SKU 跨两仓：COMPLETED、两仓余额各自正确、幂等键后缀为各自 warehouse_id")
    void sameSkuAcrossTwoWarehousesIsLegal() throws Exception {
        insertSecondWarehouse();
        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,5.000\n"
                + "SKU-A,WH-B,7.000\n");
        // 两行仓库不同 ⇒ 不构成文件内重复；库内无余额 ⇒ 0 告警
        long batchId = validatedBatch("snapshot-cross-wh.csv", csv, "INVENTORY_SNAPSHOT", 0, 0);

        ResponseEntity<String> resp = confirm(batchId, "key-r80-cross-wh", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText(),
                "R80：同一批次内同一 SKU 跨仓是合法输入，不得被判为「重复提交」：" + resp.getBody());
        assertEquals(2, data.get("success_rows").asInt());
        assertEquals(0, data.get("failed_rows").asInt());

        assertEquals(0, new BigDecimal("5.000").compareTo(balanceOf(9004, 9002)), "WH-MAIN 余额");
        assertEquals(0, new BigDecimal("7.000").compareTo(balanceOf(9005, 9002)), "WH-B 余额");

        List<Map<String, Object>> txns = jdbc.queryForList("SELECT warehouse_id, idempotency_key, "
                + "qty_delta FROM inventory_transactions WHERE team_id = ? ORDER BY warehouse_id", TEAM_ID);
        assertEquals(2, txns.size(), "两条流水（不是第二条被当成重放而吞掉）");
        assertEquals(9004L, ((Number) txns.get(0).get("warehouse_id")).longValue());
        assertEquals(9005L, ((Number) txns.get(1).get("warehouse_id")).longValue());
        assertEquals("INIT_IMPORT:IMPORT:" + batchId + ":9002:9004", txns.get(0).get("idempotency_key"),
                "R80：幂等键必须追加 :<warehouse_id>");
        assertEquals("INIT_IMPORT:IMPORT:" + batchId + ":9002:9005", txns.get(1).get("idempotency_key"));
        assertEquals(0, new BigDecimal("5.000").compareTo((BigDecimal) txns.get(0).get("qty_delta")));
        assertEquals(0, new BigDecimal("7.000").compareTo((BigDecimal) txns.get(1).get("qty_delta")));
    }

    // =====================================================================
    // 12) R79：写服务内部抛 BizException → 行级隔离（不是整批 COM-0002）
    // =====================================================================

    @Test
    @DisplayName("R79-1（B10.7 迁移）库存写服务行级隔离：外层事务内第二行 INV-5003 只回滚到 SAVEPOINT，外层仍提交")
    void inventoryWriteServiceIsolatesRowLevelFailure() {
        // ★ M3-B10.7（R83）之后，本用例原来的载体（同批同仓同 SKU 两行 → 第二行 INV-5003）
        //   在导入路径上已不可达：同批同仓同 SKU 的重复行现在由校验阶段直接判 ERROR（COM-0001），
        //   confirm 被 IMP-4009 前置门拦下——这正是 R83 的目的，不是能力回退。
        //   故把探针下移到服务层，直接验证 R79 的机制本身（探针强度不变）：
        //   外层事务 + 同幂等键两次调用（数量不同 ⇒ 第二次 INV-5003）。
        //   若 applyInitialImport 退回 REQUIRED，行内 BizException 会把共享事务标 rollback-only，
        //   外层提交抛 UnexpectedRollbackException ⇒ 本用例失败。
        //   负向有效性同源做法见 tools/b10.6/README.md 第七节。
        TeamContext.set(new TeamContext.Snapshot(TEAM_ID, List.of("OPERATOR"), OPERATOR_ID, OPERATOR));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                inventoryWriteService.applyInitialImport(9002L, 9004L, new BigDecimal("5.000"), 999L,
                        "INIT_IMPORT:IMPORT:999:9002:9004");
                BizException second = assertThrows(BizException.class, () ->
                                inventoryWriteService.applyInitialImport(9002L, 9004L, new BigDecimal("7.000"),
                                        999L, "INIT_IMPORT:IMPORT:999:9002:9004"),
                        "同键不同数量 ⇒ 写服务内部必抛 INV-5003");
                assertEquals("INV-5003", second.getErrorCode(), "行级业务码不得被替换成通用码");
            });
        } finally {
            TeamContext.clear();
        }
        assertEquals(1, count("inventory_transactions", TEAM_ID), "第一行必须已提交，不得被第二行连坐回滚");
        assertEquals(0, new BigDecimal("5.000").compareTo(balanceOf(9004, 9002)), "余额只累加一次");
    }

    @Test
    @DisplayName("R79-2 SALES_DAILY 写服务内部失败（校验后改 listed_date）：PARTIAL_SUCCESS + 行级 SAL-6003")
    void salesDailyWriteFailureIsRowLevel() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",4.000\n"
                + "SKU-B,WH-MAIN," + PAST_DATE + ",5.000\n");
        long batchId = validatedBatch("sales-listed-drift.csv", csv, "SALES_DAILY", 0, 0);

        // 导入校验只拦「晚于今天」，不查 listed_date ⇒ 校验后把上架日改晚于销售日，
        // confirm 时 SalesWriteService#validate 必抛 SAL-6003（写服务内部失败）
        jdbc.update("UPDATE skus SET listed_date = ? WHERE team_id = ? AND id = 9003",
                java.sql.Date.valueOf("2026-10-10"), TEAM_ID);

        ResponseEntity<String> resp = confirm(batchId, "key-r79-sales-listed", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARTIAL_SUCCESS", data.get("status").asText(), resp.getBody());
        assertEquals(1, data.get("success_rows").asInt());
        assertEquals(1, data.get("failed_rows").asInt());

        Map<String, Object> error = jdbc.queryForMap("SELECT row_no, error_code FROM import_row_errors "
                + "WHERE batch_id = ?", batchId);
        assertEquals(2L, ((Number) error.get("row_no")).longValue());
        assertEquals("SAL-6003", error.get("error_code"));
        assertNotEquals("COM-0002", error.get("error_code"));

        assertEquals(1, count("sales_daily", TEAM_ID), "成功行已落库");
        assertEquals(9002L, jdbc.queryForObject("SELECT sku_id FROM sales_daily WHERE team_id = ?",
                Long.class, TEAM_ID));
    }

    // =====================================================================
    // 13) R80：跨仓批次二次确认 → 纯重放
    // =====================================================================

    @Test
    @DisplayName("R80-2 跨仓批次二次确认：200 + Idempotent-Replayed，两仓余额与流水均不增")
    void crossWarehouseBatchReplayIsPureReplay() throws Exception {
        insertSecondWarehouse();
        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,5.000\n"
                + "SKU-A,WH-B,7.000\n");
        long batchId = validatedBatch("snapshot-cross-wh-replay.csv", csv, "INVENTORY_SNAPSHOT", 0, 0);

        ResponseEntity<String> first = confirm(batchId, "key-r80-cross-wh-2", Map.of());
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        assertEquals(2, count("inventory_transactions", TEAM_ID));

        ResponseEntity<String> second = confirm(batchId, "key-r80-cross-wh-2", Map.of());
        assertEquals(HttpStatus.OK, second.getStatusCode(), second.getBody());
        assertEquals("true", second.getHeaders().getFirst("Idempotent-Replayed"),
                "重放须回 Idempotent-Replayed: true");
        assertEquals(2, count("inventory_transactions", TEAM_ID), "重放不得新增流水");
        assertEquals(0, new BigDecimal("5.000").compareTo(balanceOf(9004, 9002)), "重放不得重复累加");
        assertEquals(0, new BigDecimal("7.000").compareTo(balanceOf(9005, 9002)));
    }

    // =====================================================================
    // 14) 主数据 PARTIAL_SUCCESS 回归（NESTED 改动不得带坏主数据路径）
    // =====================================================================

    @Test
    @DisplayName("R79-3 主数据 SKU PARTIAL_SUCCESS 回归：行级隔离与整批回滚语义不变")
    void masterDataPartialSuccessUnaffected() throws Exception {
        // 第二行引用「校验时存在、confirm 前被删」的商品 ⇒ confirm 阶段行级 CAT-3001
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (9010, ?, 'P-TMP-001', 'tmp', 'ACTIVE')", TEAM_ID);
        byte[] csv = csv("sku_code,product_code\n"
                + "SKU-NEW-1,P-BAG-001\n"
                + "SKU-NEW-2,P-TMP-001\n");
        long batchId = validatedBatch("sku-partial.csv", csv, "SKU", 0, 0);

        jdbc.update("DELETE FROM products WHERE team_id = ? AND id = 9010", TEAM_ID);

        ResponseEntity<String> resp = confirm(batchId, "key-r79-master-data", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARTIAL_SUCCESS", data.get("status").asText(),
                "主数据 PARTIAL_SUCCESS 是既有能力，不得被本批改动带坏：" + resp.getBody());
        assertEquals(1, data.get("success_rows").asInt());
        assertEquals(1, data.get("failed_rows").asInt());

        assertEquals("CAT-3001", jdbc.queryForObject("SELECT error_code FROM import_row_errors "
                + "WHERE batch_id = ?", String.class, batchId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM skus WHERE team_id = ? "
                + "AND sku_code = 'SKU-NEW-1'", Integer.class, TEAM_ID), "成功行已落库");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM skus WHERE team_id = ? "
                + "AND sku_code = 'SKU-NEW-2'", Integer.class, TEAM_ID), "失败行不得落库");
    }

    // =====================================================================
    // 15) PARTIAL_SUCCESS 的审计条数：不重复、不丢
    // =====================================================================

    @Test
    @DisplayName("R79-4 PARTIAL_SUCCESS 审计：成功行有且仅有 1 条，失败行不入审计，批次完成审计 1 条")
    void partialSuccessAuditIsRecordedExactlyOnce() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",4.000\n"
                + "SKU-B,WH-MAIN," + PAST_DATE + ",5.000\n");
        long batchId = validatedBatch("sales-audit-partial.csv", csv, "SALES_DAILY", 0, 0);
        jdbc.update("UPDATE skus SET listed_date = ? WHERE team_id = ? AND id = 9003",
                java.sql.Date.valueOf("2026-10-10"), TEAM_ID);

        ResponseEntity<String> resp = confirm(batchId, "key-r79-audit-partial", Map.of());
        assertEquals("PARTIAL_SUCCESS", data(resp).get("status").asText(), resp.getBody());

        // Pass 1 的审计缓冲因整批回滚被丢弃，Pass 2 重放成功行时只写一次 ⇒ 恰好 1 条
        List<String> salesAudits = jdbc.queryForList("SELECT change_summary FROM audit_logs "
                + "WHERE action = 'SALES_DAILY_UPSERT'", String.class);
        assertEquals(1, salesAudits.size(), "成功 1 行 → 恰好 1 条 SALES_DAILY_UPSERT（不重复、不丢）");
        assertTrue(salesAudits.get(0).contains("覆盖 0 行"),
                "失败行不得出现在审计摘要里：" + salesAudits.get(0));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE action = 'IMPORT_BATCH_COMPLETE' AND biz_id = ?", Integer.class, batchId),
                "批次完成审计恰好 1 条");
    }

    // =====================================================================
    // 16) R83：INVENTORY_SNAPSHOT 文件内重复（wh, sku）→ 后行 ERROR（B10.7）
    // =====================================================================

    @Test
    @DisplayName("R83-a 同仓同 SKU 同数量两行：后行 ERROR COM-0001，confirm 422 IMP-4009，WARNING 并存")
    void snapshotDuplicateWithSameQtyIsError() throws Exception {
        // 已有余额行 ⇒ 两行都会命中 IMP-4006（WARNING）；第 2 行再叠加「文件内重复」的 ERROR
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty) "
                + "VALUES (?, 9004, 9002, 10.000)", TEAM_ID);
        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,2.000\n"
                + "SKU-A,WH-MAIN,2.000\n");
        long batchId = validatedBatch("snapshot-dup-same-qty.csv", csv, "INVENTORY_SNAPSHOT", 1, 1);

        Map<String, Object> error = jdbc.queryForMap("SELECT row_no, error_level, error_code, error_message, "
                + "raw_data FROM import_row_errors WHERE batch_id = ? AND error_level = 'ERROR'", batchId);
        assertEquals(2L, ((Number) error.get("row_no")).longValue(), "后行（第 2 行）才报错");
        assertEquals("COM-0001", error.get("error_code"), "R83 口径：复用 COM-0001（不新增错误码）");
        assertEquals("同一仓库、同一 SKU 在文件内重复", error.get("error_message"));
        assertNotNull(error.get("raw_data"), "失败行须回带原始全列");

        // 要求 5：WARNING（IMP-4006）与 ERROR 并存，不因该行已有 ERROR 就丢弃 WARNING
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors "
                        + "WHERE batch_id = ? AND error_level = 'WARNING' AND error_code = 'IMP-4006'",
                Integer.class, batchId), "两行都命中已有余额 ⇒ 2 条 IMP-4006（含第 2 行）");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors "
                + "WHERE batch_id = ? AND row_no = 2 AND error_level = 'WARNING'", Integer.class, batchId),
                "同一 row_no 下 ERROR 与 WARNING 可并存");

        // 有 ERROR 行 ⇒ confirm 被 IMP-4009 前置门拦下（不聚合、不自动相加）
        ResponseEntity<String> resp = confirm(batchId, "key-r83-dup-same-qty", Map.of());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("IMP-4009", body.get("code").asText());
        assertEquals(1, body.get("data").get("error_rows").asInt());

        assertEquals(0, new BigDecimal("10.000").compareTo(balanceOf(9004, 9002)), "被拦下的批次不得改余额");
        assertEquals(0, count("inventory_transactions", TEAM_ID), "被拦下的批次不得写流水");
    }

    @Test
    @DisplayName("R83-b 同仓同 SKU 不同数量两行：同样是后行 ERROR（不是留到 confirm 报 INV-5003）")
    void snapshotDuplicateWithDifferentQtyIsError() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,2.000\n"
                + "SKU-A,WH-MAIN,3.000\n");
        long batchId = validatedBatch("snapshot-dup-diff-qty.csv", csv, "INVENTORY_SNAPSHOT", 1, 0);

        Map<String, Object> error = jdbc.queryForMap("SELECT row_no, error_code, error_message "
                + "FROM import_row_errors WHERE batch_id = ? AND error_level = 'ERROR'", batchId);
        assertEquals(2L, ((Number) error.get("row_no")).longValue());
        assertEquals("COM-0001", error.get("error_code"));
        assertEquals("同一仓库、同一 SKU 在文件内重复", error.get("error_message"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors "
                        + "WHERE batch_id = ? AND error_code = 'INV-5003'", Integer.class, batchId),
                "校验阶段已拦下，不应再留到 confirm 报 INV-5003");

        ResponseEntity<String> resp = confirm(batchId, "key-r83-dup-diff-qty", Map.of());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());

        assertNull(balanceOf(9004, 9002), "无余额行（未落库）");
        assertEquals(0, count("inventory_transactions", TEAM_ID));
    }

    @Test
    @DisplayName("R83-c 同 SKU 跨两仓 → 不误杀：0 错 0 警、COMPLETED、两仓余额各自正确（R80 不回退）")
    void snapshotSameSkuAcrossWarehousesIsNotDuplicate() throws Exception {
        insertSecondWarehouse();
        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,5.000\n"
                + "SKU-A,WH-B,7.000\n");
        long batchId = validatedBatch("snapshot-cross-wh-r83.csv", csv, "INVENTORY_SNAPSHOT", 0, 0);

        ResponseEntity<String> resp = confirm(batchId, "key-r83-cross-wh", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText(), resp.getBody());
        assertEquals(2, count("inventory_transactions", TEAM_ID), "两仓各 1 条 INIT_IMPORT");
        assertEquals(0, new BigDecimal("5.000").compareTo(balanceOf(9004, 9002)));
        assertEquals(0, new BigDecimal("7.000").compareTo(balanceOf(9005, 9002)));
    }

    @Test
    @DisplayName("R83-d 回归：SALES_DAILY 的 SAL-6004 与主数据的 CAT-3002 行为一字不变")
    void existingDuplicateChecksUnchanged() throws Exception {
        // SALES_DAILY：三元组 (wh, sku, date) 重复 → 后行 SAL-6004（不是 COM-0001）
        long salesBatch = validatedBatch("sales-dup-r83.csv", csv("sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",1.000\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",2.000\n"), "SALES_DAILY", 1, 0);
        Map<String, Object> salesError = jdbc.queryForMap("SELECT row_no, error_code FROM import_row_errors "
                + "WHERE batch_id = ? AND error_level = 'ERROR'", salesBatch);
        assertEquals("SAL-6004", salesError.get("error_code"), "R83 不得影响 SALES_DAILY 的既有错误码");
        assertEquals(2L, ((Number) salesError.get("row_no")).longValue());

        // 主数据 SKU：编码重复 → 后行 CAT-3002（不是 COM-0001）
        long skuBatch = validatedBatch("sku-dup-r83.csv", csv("sku_code,product_code\n"
                + "SKU-NEW-DUP,P-BAG-001\n"
                + "SKU-NEW-DUP,P-BAG-001\n"), "SKU", 1, 0);
        Map<String, Object> skuError = jdbc.queryForMap("SELECT row_no, error_code FROM import_row_errors "
                + "WHERE batch_id = ? AND error_level = 'ERROR'", skuBatch);
        assertEquals("CAT-3002", skuError.get("error_code"), "R83 不得影响主数据的既有错误码");
        assertEquals(2L, ((Number) skuError.get("row_no")).longValue());
    }

    // =====================================================================
    // 17) R85：编码大小写一律不敏感等价（IMPORT_WORKFLOW §8.6 末行裁定）
    // =====================================================================

    @Test
    @DisplayName("R85-a 快照同仓 SKU-A + sku-a：后行 ERROR COM-0001 + confirm 422 IMP-4009")
    void snapshotCaseInsensitiveDuplicateIsError() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,2.000\n"
                + "sku-a,WH-MAIN,3.000\n");
        // R85 前：error_rows=0（判定键大小写敏感）→ confirm PARTIAL_SUCCESS 且余额只 +2.000
        long batchId = validatedBatch("snapshot-case.csv", csv, "INVENTORY_SNAPSHOT", 1, 0);

        Map<String, Object> error = jdbc.queryForMap("SELECT row_no, error_code, error_level "
                + "FROM import_row_errors WHERE batch_id = ? AND error_level = 'ERROR'", batchId);
        assertEquals(2L, ((Number) error.get("row_no")).longValue(), "后行才是重复行");
        assertEquals("COM-0001", error.get("error_code"), "R83 既定错误码不得改变");
        assertEquals("ERROR", error.get("error_level"), "必须是 ERROR，不能降级成 WARNING（相加会静默改数）");

        ResponseEntity<String> resp = confirm(batchId, "key-r85-a", Map.of());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4009", code(resp), "有 ERROR 行时 confirm 必须被前置门拦下");
        assertEquals(1, data(resp).get("error_rows").asInt());
        assertNull(balanceOf(9004L, 9002L), "被拦下则一行都不该落库");
    }

    @Test
    @DisplayName("R85-b 销售同 (WH-MAIN, SKU-A, 同日期) 但 sku_code 大小写不同：后行 ERROR SAL-6004")
    void salesDailyCaseInsensitiveDuplicateIsError() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",12.000,0.000,1080.00,8\n"
                + "sku-a,wh-main," + PAST_DATE + ",6.000,0.000,540.00,4\n");
        long batchId = validatedBatch("sales-case.csv", csv, "SALES_DAILY", 1, 0);

        Map<String, Object> error = jdbc.queryForMap("SELECT row_no, error_code, error_level "
                + "FROM import_row_errors WHERE batch_id = ? AND error_level = 'ERROR'", batchId);
        assertEquals(2L, ((Number) error.get("row_no")).longValue());
        assertEquals("SAL-6004", error.get("error_code"), "SALES_DAILY 既定错误码不得改变");
        assertEquals("ERROR", error.get("error_level"));

        ResponseEntity<String> resp = confirm(batchId, "key-r85-b", Map.of());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4009", code(resp));
        assertEquals(0, count("sales_daily", TEAM_ID), "被拦下则一行都不该落库");
    }

    @Test
    @DisplayName("R85-c SKU 主数据 SKU-NEW-1 + sku-new-1：后行 ERROR CAT-3002（证明第三处也改了）")
    void masterDataCaseInsensitiveDuplicateIsError() throws Exception {
        // 第三处 = applyBusinessUnique → duplicateCode（四类主数据的 code 判定键）
        long batchId = validatedBatch("master-case.csv", csv("sku_code,product_code\n"
                + "SKU-NEW-1,P-BAG-001\n"
                + "sku-new-1,P-BAG-001\n"), "SKU", 1, 0);

        Map<String, Object> error = jdbc.queryForMap("SELECT row_no, error_code, error_level "
                + "FROM import_row_errors WHERE batch_id = ? AND error_level = 'ERROR'", batchId);
        assertEquals(2L, ((Number) error.get("row_no")).longValue(), "后行才是重复行");
        assertEquals("CAT-3002", error.get("error_code"), "主数据既定错误码不得改变");
        assertEquals("ERROR", error.get("error_level"), "必须是 ERROR，不能降级成 WARNING");
    }

    @Test
    @DisplayName("R85-d 回归：同仓同 SKU 同大小写两行仍判重复（R83 行为不回退）")
    void snapshotExactCaseDuplicateStillError() throws Exception {
        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,2.000\n"
                + "SKU-A,WH-MAIN,3.000\n");
        long batchId = validatedBatch("snapshot-exact.csv", csv, "INVENTORY_SNAPSHOT", 1, 0);
        Map<String, Object> error = jdbc.queryForMap("SELECT error_code, error_level "
                + "FROM import_row_errors WHERE batch_id = ? AND error_level = 'ERROR'", batchId);
        assertEquals("COM-0001", error.get("error_code"));
        assertEquals("ERROR", error.get("error_level"));
    }

    @Test
    @DisplayName("R85-e 引用解析：单行小写 sku_code/wh 编码应能成功导入（不误报 CAT-3001/CAT-3003）")
    void lowercaseCodeResolvesAndImportsSuccessfully() throws Exception {
        // 这一项覆盖任务书未列、但 §8.6 同条款（「所有 code 字段的引用解析」）要求的第四处：
        // getIdsByCodes 返回的 Map 键是【库里存的写法】，直接用文件原始大小写 get 会落空
        // ⇒ R85 前，小写编码会在 confirm 阶段被误判「引用的SKU不存在」（CAT-3001）。
        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "sku-a,wh-main,4.000\n");
        long batchId = validatedBatch("snapshot-lower.csv", csv, "INVENTORY_SNAPSHOT", 0, 0);

        ResponseEntity<String> resp = confirm(batchId, "key-r85-e", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText(), resp.getBody());
        assertEquals(1, data.get("success_rows").asInt(), "小写编码必须被解析成功，而不是报 CAT-3001");
        assertEquals(0, new BigDecimal("4.000").compareTo(balanceOf(9004L, 9002L)),
                "小写编码解析到的是同一个 SKU-A（库中原样写法），余额应正常累加");
        assertEquals(0, count("import_row_errors", TEAM_ID), "不应产生任何行级错误");
    }

    @Test
    @DisplayName("R85-f IMP-4006 命中判定：全小写编码也必须命中已有余额行（不被当作引用不存在而漏报）")
    void lowercaseCodeStillTriggersOverwriteWarning() throws Exception {
        // 为 SKU-B(9003) 建一条余额行（CSV 用 sku-b 指向它）
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                + "in_transit_qty, committed_qty, created_by, updated_by, version) "
                + "VALUES (?, 9004, 9003, 5.000, 0.000, 0.000, ?, ?, 0)",
                TEAM_ID, OWNER_ID, OWNER_ID);
        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "sku-b,wh-main,1.000\n");
        long batchId = validatedBatch("snapshot-lower-warn.csv", csv, "INVENTORY_SNAPSHOT", 0, 1);
        Map<String, Object> warn = jdbc.queryForMap("SELECT error_code, error_level "
                + "FROM import_row_errors WHERE batch_id = ? AND error_level = 'WARNING'", batchId);
        assertEquals("IMP-4006", warn.get("error_code"), "全小写编码也必须命中已有余额行");
        assertEquals("WARNING", warn.get("error_level"));
    }

    // =====================================================================
    // 辅助：数据断言
    // =====================================================================

    /** 追加第二个仓库（{@code WH-B}，id 9005），供「同批同 SKU 跨两仓」用例使用。 */
    private void insertSecondWarehouse() {
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) "
                + "VALUES (9005, ?, 'WH-B', 'Warehouse B', 'ACTIVE')", TEAM_ID);
    }

    /** 指定仓库 + SKU 的 {@code on_hand_qty}；行不存在时返回 {@code null}。 */
    private BigDecimal balanceOf(long warehouseId, long skuId) {
        List<BigDecimal> rows = jdbc.queryForList("SELECT on_hand_qty FROM inventory_balances "
                + "WHERE team_id = ? AND warehouse_id = ? AND sku_id = ?", BigDecimal.class,
                TEAM_ID, warehouseId, skuId);
        return rows.isEmpty() ? null : rows.get(0);
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

    /** 错误响应的顶层业务码（{@code {"code":"IMP-4009", ...}}）。 */
    private String code(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("code").asText();
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
