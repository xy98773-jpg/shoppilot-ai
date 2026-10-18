package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
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
 * <b>M3-B11（M3-T19 硬门槛）：导入/重导/部分失败/对账的端到端硬验</b>
 * ——真栈（Spring 上下文 + Testcontainers MySQL 8.0.36 + 真实 HTTP + 真实文件落盘）。
 *
 * <p>本类只做「既有能力的硬验」，<b>不改任何 main 代码</b>（B11 白名单仅测试 + tools + 验收记录）。
 * 6 个方法对应任务书【2】的 A1/A2/B1/B2/C1/D1，每条断言都钉住一条可被后续批次改坏的<b>行为</b>，
 * 而不是复述实现细节。</p>
 *
 * <h2>与既有测试的分工（为什么不是「再写一遍」）</h2>
 * <ul>
 *   <li>{@code ImportReimportIntegrationTest} 覆盖<b>单次</b>重导（R84-e/f/g/h/i/j）；
 *       本类 <b>A1</b> 覆盖<b>链式</b>重导 A→B→C：{@code attempt_no} 2/3 的递推、
 *       两次槽位转移、审计「恰好 2 条」的<b>作用域限定</b>（同 team + 同 {@code file_hash} 的批次集合）。</li>
 *   <li>{@code ImportDataConfirmIntegrationTest#R79-2} 覆盖部分失败的<b>前半段</b>（终态/行级码/成功行可查）；
 *       本类 <b>B1</b> 的增量是后两项：{@code success + failed = total} 恒等式、
 *       以及<b>失败行不留审计残留</b>（Pass 1 的审计缓冲必须随整批回滚一起丢弃）。</li>
 *   <li>{@code R83-a} 覆盖「文件内重复 → confirm 被 IMP-4009 拦下」；本类 <b>B2</b> 把
 *       「重复」与「失败行」<b>叠加</b>后再验「零落库 / 零审计」，并把「成功恰 1 条 + 余额恰 +1 行量」
 *       放在<b>漂移失败</b>批上（原因见方法 javadoc 的偏差说明）。</li>
 * </ul>
 *
 * <h2>★ 本类刻意保留的两条「反弱断言」</h2>
 * <ol>
 *   <li><b>A2</b> 不用「余额没翻倍」这种弱断言，而是断言余额<b>绝对值</b>仍为 {@code 10.000}
 *       （既不翻倍、也不被清零、也不被主数据重导写坏）。</li>
 *   <li><b>D1</b> 是<b>反向误杀</b>用例：全合法文件必须 {@code COMPLETED}，
 *       显式断言 {@code status != PARTIAL_SUCCESS} 且 ERROR 行 = 0——
 *       否则「把所有文件都判成部分失败」也能让正向用例全绿。</li>
 * </ol>
 *
 * <h2>种子与对账口径</h2>
 * <p>{@link #resetAndSeed()} 建两条余额行（SKU-A / SKU-B 各 {@code 10.000}），
 * <b>并且</b>为每条余额行配一条等额 {@code INIT_IMPORT} 流水 ⇒ 种子阶段对账即为 0 行
 * （{@code TEST_STRATEGY §7.3}；B10.8 已补 {@code seed-init-9003} 的教训）。
 * 冲销流水的 {@code qty_delta} 是原值的相反数（{@code InventoryWriteService}），
 * 故对账 SQL 直接 {@code SUM(qty_delta)} 即净额，不再额外过滤 {@code txn_type}。</p>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class M3HardThresholdTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 上传落盘目录指向临时目录：绝不在仓库工作树里留下上传物。 */
    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-m3-hard-threshold-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;
    private static final long OWNER_ID = 1004L;
    private static final long OTHER_OWNER_ID = 2004L;

    private static final long PRODUCT_ID = 9001L;
    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;
    private static final long SKU_C = 9006L;
    private static final long WH_MAIN = 9004L;
    private static final long WH_B = 9005L;

    /** 过去日期（{@code SAL-6003} 只拦「晚于今天 / 早于上架日」；写死避免时钟抖动）。 */
    private static final String PAST_DATE = "2026-10-01";
    /** 明确非法：晚于今天。 */
    private static final String FUTURE_DATE = "2999-01-01";

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
        jdbc.execute("DELETE FROM warehouses");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);
        insertUser(OWNER_ID, "owner01");
        insertUser(OPERATOR_ID, OPERATOR);
        insertUser(VIEWER_ID, "viewer01");
        insertUser(OTHER_OWNER_ID, "owner8");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), "
                        + "(?, ?, 'VIEWER', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (?, ?, 'P-BAG-001', '箱包', 'ACTIVE')", PRODUCT_ID, TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                + "(?, ?, ?, 'SKU-A', 'Sku A', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-B', 'Sku B', 'ACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_ID, SKU_B, TEAM_ID, PRODUCT_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) "
                + "VALUES (?, ?, 'WH-MAIN', '主仓', 'ACTIVE')", WH_MAIN, TEAM_ID);

        // 余额 + 等额期初流水（对账在种子阶段就必须是 0 行；否则后续三时点断言全是假 FAIL）
        seedBalanceWithInitTxn(SKU_A);
        seedBalanceWithInitTxn(SKU_B);
    }

    // =====================================================================
    // A1：链式重导 A→B→C
    // =====================================================================

    @Test
    @DisplayName("B11-A1 链式重导 A→B→C：attempt_no=2/3、槽位逐级转移、FORCE_REIMPORT 审计恰 2 条（同 hash 作用域）")
    void chainedReimportProducesAttemptTwoAndThree() throws Exception {
        String op = token(OPERATOR);
        byte[] csv = csv("sku_code,product_code\nSKU-B11-A1,P-BAG-001\n");
        String hash = sha256(csv);

        long a = completedBatch(op, "b11-a1-chain.csv", csv, "SKU");
        Map<String, Object> aRow = batchRow(a);
        assertEquals(1, attemptNo(a), "首次上传 attempt_no=1");
        assertEquals(hash, aRow.get("dedupe_hash"), "首批占用槽位");
        Object storagePath = aRow.get("storage_path");
        assertNotNull(storagePath, "首批必须有落盘路径（重导复用同一份原文）");

        // ---- 第一次重导：A → B ----
        ResponseEntity<String> respB = reimport(op, a);
        assertEquals(HttpStatus.CREATED, respB.getStatusCode(), respB.getBody());
        long b = data(respB).get("batch_id").asLong();
        assertNotEquals(a, b);
        assertEquals(2, attemptNo(b), "B.attempt_no = 2");
        assertEquals(hash, batchRow(b).get("dedupe_hash"), "B 必须占用槽位（dedupe_hash = file_hash）");
        assertEquals(storagePath, batchRow(b).get("storage_path"), "B 复用原文件，不搬移");
        assertEquals(b, supersededBy(a), "A.superseded_by_batch_id = B");
        assertNull(dedupeHash(a), "A 的槽位必须释放");
        assertEquals("COMPLETED", batchRow(a).get("status"), "重导不改原批状态（它是历史事实）");

        // ---- B 必须走到终态才能继续重导（非终态 422 IMP-4005 已有 R84-f 覆盖，这里只作前置） ----
        assertEquals("COMPLETED", completeExistingBatch(op, b, "b11-a1-chain.csv"), "B 应能再次导入成功");

        // ---- 第二次重导：B → C ----
        ResponseEntity<String> respC = reimport(op, b);
        assertEquals(HttpStatus.CREATED, respC.getStatusCode(), respC.getBody());
        long c = data(respC).get("batch_id").asLong();
        assertEquals(3, attemptNo(c), "C.attempt_no = 3（在 B 的基础上递推，不是恒为 2）");
        assertEquals(hash, batchRow(c).get("dedupe_hash"));
        assertEquals(storagePath, batchRow(c).get("storage_path"), "整条链共用同一份原文");
        assertEquals(c, supersededBy(b), "B.superseded_by_batch_id = C");
        assertNull(dedupeHash(b));
        assertNull(supersededBy(c), "C 是链尾，未被取代");
        assertEquals("UPLOADED", batchRow(c).get("status"), "新批从 UPLOADED 重新走流程");

        // ---- 槽位唯一性：同 (team, hash, biz) 恰好 1 行占用，且是链尾 ----
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM import_batches "
                + "WHERE team_id = ? AND dedupe_hash = ? AND biz_type = 'SKU'", Integer.class, TEAM_ID, hash));
        assertEquals(c, jdbc.queryForObject("SELECT id FROM import_batches "
                + "WHERE team_id = ? AND dedupe_hash = ? AND biz_type = 'SKU'", Long.class, TEAM_ID, hash));

        // ---- 审计：恰好 2 条，作用域 = 本 team + 同 file_hash 的批次集合 ----
        List<Map<String, Object>> audits = jdbc.queryForList("SELECT biz_id, biz_type, biz_no, result, "
                + "change_summary, after_json FROM audit_logs "
                + "WHERE action = 'IMPORT_BATCH_FORCE_REIMPORT' AND team_id = ? "
                + "AND biz_id IN (SELECT id FROM import_batches WHERE team_id = ? AND file_hash = ?) "
                + "ORDER BY biz_id", TEAM_ID, TEAM_ID, hash);
        assertEquals(2, audits.size(), "链式重导必须恰好 2 条 FORCE_REIMPORT（每次重导 1 条，不多不少）");
        assertEquals(Set.of(b, c), audits.stream()
                .map(row -> ((Number) row.get("biz_id")).longValue()).collect(Collectors.toSet()),
                "两条审计的 biz_id 分别 = B、C（A 是被取代方，不是操作结果）");

        Map<String, Object> auditB = audits.get(0);
        Map<String, Object> auditC = audits.get(1);
        for (Map<String, Object> audit : List.of(auditB, auditC)) {
            assertEquals("IMPORT_BATCH", audit.get("biz_type"));
            assertEquals("SUCCESS", audit.get("result"));
            assertNotNull(audit.get("biz_no"), "biz_no 必须是新批 batch_no（否则无法反查）");
        }
        assertTrue(String.valueOf(auditB.get("change_summary")).contains("原批次 id=" + a),
                "B 的审计须记原批 A：" + auditB.get("change_summary"));
        assertTrue(String.valueOf(auditC.get("change_summary")).contains("原批次 id=" + b),
                "C 的审计须记原批 B：" + auditC.get("change_summary"));
        assertTrue(String.valueOf(auditB.get("after_json")).contains("\"original_batch_id\": " + a)
                        && String.valueOf(auditB.get("after_json")).contains("\"new_batch_id\": " + b)
                        && String.valueOf(auditB.get("after_json")).contains("\"attempt_no\": 2"),
                "B 的 after_json 必须机读可还原（原批/新批/第几次）：" + auditB.get("after_json"));
        assertTrue(String.valueOf(auditC.get("after_json")).contains("\"original_batch_id\": " + b)
                        && String.valueOf(auditC.get("after_json")).contains("\"new_batch_id\": " + c)
                        && String.valueOf(auditC.get("after_json")).contains("\"attempt_no\": 3"),
                "C 的 after_json 必须机读可还原：" + auditC.get("after_json"));

        // ---- 链式重导不得重复建 SKU ----
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM skus WHERE team_id = ? "
                + "AND BINARY sku_code = 'SKU-B11-A1'", Integer.class, TEAM_ID), "三次导入只应有一行 SKU");
        assertEquals(3, countSkus(), "种子 2 行 + 新 1 行");
    }

    // =====================================================================
    // A2：重导不重复建 SKU，余额绝对值不变
    // =====================================================================

    @Test
    @DisplayName("B11-A2 主数据重导：SKU 行数与 id 不变、编码字面量不变、余额绝对值仍 10.000（非翻倍/非清零）、对账 0")
    void reimportMasterDataKeepsSkuIdentityAndBalanceAbsolute() throws Exception {
        String op = token(OPERATOR);
        BigDecimal before = balanceOf(WH_MAIN, SKU_A);
        assertEquals(0, new BigDecimal("10.000").compareTo(before), "种子余额应为 10.000");
        int skusBefore = countSkus();
        int txnsBefore = countInventoryTxns();

        byte[] csv = csv("sku_code,product_code,name\nSKU-A,P-BAG-001,重导后的名字\n");
        long a = completedBatch(op, "b11-a2-master.csv", csv, "SKU");
        assertEquals(skusBefore, countSkus(), "命中既有编码 ⇒ UPDATE，不得新增 SKU 行");
        assertEquals("重导后的名字", skuName(SKU_A), "可变字段必须被更新（否则等于没导入）");

        long b = data(reimport(op, a)).get("batch_id").asLong();
        assertEquals("COMPLETED", completeExistingBatch(op, b, "b11-a2-master.csv"),
                "重导出来的新批必须能再次成功导入");

        // ---- 硬断言：行数、身份、编码字面量、余额绝对值、流水 ----
        assertEquals(skusBefore, countSkus(), "重导不得重复建 SKU（行数不变）");
        assertEquals(SKU_A, skuIdByCode("SKU-A"), "SKU 身份（id）不得因重导而更换");
        assertEquals("重导后的名字", skuName(SKU_A));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM skus WHERE team_id = ? "
                + "AND BINARY sku_code = 'SKU-A'", Integer.class, TEAM_ID), "编码字面量不得被折叠/改写（R86 不回退）");

        BigDecimal after = balanceOf(WH_MAIN, SKU_A);
        assertNotNull(after, "主数据重导不得删除/重建余额行");
        assertEquals(0, new BigDecimal("10.000").compareTo(after),
                "余额绝对值必须仍是 10.000（不是 20.000 的翻倍，也不是 0 的清零），实际=" + after);
        assertEquals(txnsBefore, countInventoryTxns(), "主数据导入不得写库存流水");
        assertEquals(0, reconciliationDiffRows(), "主数据重导后对账必须为 0 行");
    }

    // =====================================================================
    // B1：SALES_DAILY 部分失败 + 无审计残留
    // =====================================================================

    @Test
    @DisplayName("B11-B1 SALES_DAILY 部分失败：PARTIAL_SUCCESS + 恒等式 + 行级 SAL-6003（≠COM-0002）+ 失败行无审计残留")
    void salesPartialFailureLeavesNoAuditResidue() throws Exception {
        String op = token(OPERATOR);
        byte[] csv = csv("sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",4.000\n"
                + "SKU-B,WH-MAIN," + PAST_DATE + ",5.000\n");
        long batchId = validatedBatch(op, "b11-b1-sales-partial.csv", csv, "SALES_DAILY", 0, 0);

        // 导入校验只拦「晚于今天」，不查 listed_date ⇒ 校验后把上架日改晚于销售日，
        // confirm 时 SalesWriteService#validate 必抛 SAL-6003（写服务内部失败，行级隔离）。
        jdbc.update("UPDATE skus SET listed_date = ? WHERE team_id = ? AND id = ?",
                java.sql.Date.valueOf("2026-10-10"), TEAM_ID, SKU_B);

        ResponseEntity<String> resp = confirm(op, batchId, "b11-b1-sales", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARTIAL_SUCCESS", data.get("status").asText(), resp.getBody());
        assertEquals(1, data.get("success_rows").asInt());
        assertEquals(1, data.get("failed_rows").asInt());
        assertEquals(2, data.get("total_rows").asInt());
        assertEquals(data.get("total_rows").asInt(),
                data.get("success_rows").asInt() + data.get("failed_rows").asInt(),
                "不变式：success_rows + failed_rows = total_rows");

        Map<String, Object> error = jdbc.queryForMap("SELECT row_no, error_code, error_level "
                + "FROM import_row_errors WHERE batch_id = ? AND error_level = 'ERROR'", batchId);
        assertEquals(2L, ((Number) error.get("row_no")).longValue(), "失败的是第 2 行（SKU-B）");
        assertEquals("SAL-6003", error.get("error_code"));
        assertNotEquals("COM-0002", error.get("error_code"), "行级业务码不得被通用码顶替");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sales_daily WHERE team_id = ?",
                Integer.class, TEAM_ID), "成功行已落库");
        assertEquals(SKU_A, jdbc.queryForObject("SELECT sku_id FROM sales_daily WHERE team_id = ?",
                Long.class, TEAM_ID));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales_daily WHERE team_id = ? AND sku_id = ?",
                Integer.class, TEAM_ID, SKU_B), "失败行不得落库");

        // ---- 增量断言①：失败行不留审计残留 ----
        // ★ SALES_DAILY_UPSERT 的 AuditEvent 不带 biz_id（SalesWriteService 只填 biz_type），
        //   故「作用域限定该批 biz_id」在该动作上不可用；改用「本方法内 audit_logs 已被清空 +
        //   team + action」限定，并同时用 biz_id 可用的 IMPORT_BATCH_COMPLETE 交叉核对。
        List<String> summaries = jdbc.queryForList("SELECT change_summary FROM audit_logs "
                + "WHERE action = 'SALES_DAILY_UPSERT' AND team_id = ?", String.class, TEAM_ID);
        assertEquals(1, summaries.size(),
                "成功 1 行 ⇒ 恰好 1 条 SALES_DAILY_UPSERT；>1 说明 Pass 1 的审计缓冲没随整批回滚丢弃");
        assertTrue(summaries.get(0).contains("共 1 行"),
                "审计摘要必须是「共 1 行」（失败行不得进入审计）：" + summaries.get(0));
        assertFalse(summaries.get(0).contains("共 2 行"),
                "审计摘要出现「共 2 行」即为失败行残留：" + summaries.get(0));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                        + "WHERE action = 'IMPORT_BATCH_COMPLETE' AND biz_id = ?", Integer.class, batchId),
                "批次完成审计恰好 1 条（作用域限定本批 biz_id）");
    }

    // =====================================================================
    // B2：文件内重复 + 失败行叠加
    // =====================================================================

    @Test
    @DisplayName("B11-B2 文件内重复（大小写变体）+ 失败行叠加：重复批 422 零落库零审计；漂移批成功恰 1 条、余额恰 +1 行量、对账 0")
    void duplicateRowsPlusFailingRowKeepExactlyOneWrite() throws Exception {
        String op = token(OPERATOR);

        // ---------------- ① 文件内重复 + 非法日期：叠加后必须「零落库」 ----------------
        // ★ 偏差说明（任务书 B2 原文）：原文把「文件内重复」与「成功恰 1 条」放在同一批。
        //   但任何 ERROR 行都会让 confirm 在状态推进前被 IMP-4009 硬门拦下
        //   （ImportConfirmService:277-279），故「同批既重复又成功 1 条」在当前规则下不可达。
        //   本方法拆成两个批次：① 重复+非法日期 → 验「零落库 / 零审计 / 对账仍 0」；
        //   ② 合法文件 + 校验后引用漂移 → 验「成功恰 1 条 / 余额恰 +1 行量 / 对账 0」。
        //   两条合起来覆盖原文要求的全部断言点，且不弱化任何一条。
        byte[] dupCsv = csv("sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-A,WH-MAIN," + PAST_DATE + ",5.000\n"
                + "sku-a,WH-MAIN," + PAST_DATE + ",7.000\n"
                + "SKU-B,WH-MAIN," + FUTURE_DATE + ",3.000\n");
        long dupBatch = validatedBatch(op, "b11-b2-dup.csv", dupCsv, "SALES_DAILY", 2, 0);

        List<Map<String, Object>> errors = jdbc.queryForList("SELECT row_no, error_code FROM import_row_errors "
                + "WHERE batch_id = ? AND error_level = 'ERROR' ORDER BY row_no", dupBatch);
        assertEquals(2, errors.size(), "两行各一条 ERROR（重复行 + 非法日期行）");
        assertEquals(2L, ((Number) errors.get(0).get("row_no")).longValue());
        assertEquals("SAL-6004", errors.get(0).get("error_code"), "大小写变体的同键行必须判重复（R85 不回退）");
        assertEquals(3L, ((Number) errors.get(1).get("row_no")).longValue());
        assertEquals("SAL-6003", errors.get(1).get("error_code"));

        ResponseEntity<String> blocked = confirm(op, dupBatch, "b11-b2-dup", Map.of());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, blocked.getStatusCode(), blocked.getBody());
        JsonNode blockedBody = objectMapper.readTree(blocked.getBody());
        assertEquals("IMP-4009", blockedBody.get("code").asText());
        assertEquals(2, blockedBody.get("data").get("error_rows").asInt());
        assertEquals(dupBatch, blockedBody.get("data").get("batch_id").asLong());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales_daily WHERE team_id = ?",
                Integer.class, TEAM_ID), "被 IMP-4009 拦下时不得有任何部分落库");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE action = 'SALES_DAILY_UPSERT' AND team_id = ?", Integer.class, TEAM_ID),
                "零落库 ⇒ 零 SALES_DAILY_UPSERT 审计（审计残留即为假成功）");
        assertEquals(0, new BigDecimal("10.000").compareTo(balanceOf(WH_MAIN, SKU_A)), "余额不得被动过");
        assertEquals(0, reconciliationDiffRows());

        // ---------------- ② 合法文件 + 校验后引用漂移：成功恰 1 条、余额恰 +1 行量 ----------------
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) "
                + "VALUES (?, ?, ?, 'SKU-C', 'Sku C', 'ACTIVE')", SKU_C, TEAM_ID, PRODUCT_ID);
        byte[] snapCsv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,5.000\n"
                + "SKU-C,WH-MAIN,6.000\n");
        // ★ 第 1 行命中既有余额行 ⇒ IMP-4006 WARNING（§7.5 增量累加提示），这是正确行为、不是错误；
        //   第 2 行（SKU-C）无余额行 ⇒ 无告警。故期望 0 ERROR / 1 WARNING。
        long snapBatch = validatedBatch(op, "b11-b2-snap.csv", snapCsv, "INVENTORY_SNAPSHOT", 0, 1);

        // 校验通过后删除被引用的 SKU ⇒ confirm 阶段行级 CAT-3001（唯一能到达 PARTIAL_SUCCESS 的路径）
        jdbc.update("DELETE FROM skus WHERE team_id = ? AND id = ?", TEAM_ID, SKU_C);

        ResponseEntity<String> partial = confirm(op, snapBatch, "b11-b2-snap", Map.of());
        assertEquals(HttpStatus.OK, partial.getStatusCode(), partial.getBody());
        JsonNode pd = data(partial);
        assertEquals("PARTIAL_SUCCESS", pd.get("status").asText(), partial.getBody());
        assertEquals(2, pd.get("total_rows").asInt());
        assertEquals(1, pd.get("success_rows").asInt(), "成功恰 1 条");
        assertEquals(1, pd.get("failed_rows").asInt());
        assertEquals("CAT-3001", jdbc.queryForObject("SELECT error_code FROM import_row_errors "
                + "WHERE batch_id = ? AND error_level = 'ERROR'", String.class, snapBatch));

        assertEquals(0, new BigDecimal("15.000").compareTo(balanceOf(WH_MAIN, SKU_A)),
                "余额只加 1 行量（10.000 + 5.000），不得两行都落账");
        assertNull(balanceOf(WH_MAIN, SKU_C), "失败行不得建余额行");
        assertEquals(3, countInventoryTxns(), "种子 2 条 + 成功 1 条（失败行不得写流水）");
        assertEquals(0, reconciliationDiffRows(), "部分失败后对账仍必须为 0 行");
    }

    // =====================================================================
    // C1：对账三时点
    // =====================================================================

    @Test
    @DisplayName("B11-C1 对账三时点连续断言：seed 后=0 → 导入后=0 → 重导后=0（每点打印差异行数）")
    void reconciliationIsZeroAtThreePoints() throws Exception {
        String op = token(OPERATOR);

        int atSeed = reconciliationDiffRows();
        System.out.println("[B11-C1] 时点1（seed 后）对账差异行数=" + atSeed
                + "，余额=" + balanceSnapshot());
        assertEquals(0, atSeed, "种子阶段对账就必须为 0 行（种子资产缺陷会造成假 FAIL）");

        byte[] csv = csv("sku_code,warehouse_code,qty\nSKU-A,WH-MAIN,5.000\n");
        long batchId = completedBatch(op, "b11-c1-snapshot.csv", csv, "INVENTORY_SNAPSHOT");
        int afterImport = reconciliationDiffRows();
        System.out.println("[B11-C1] 时点2（导入后）对账差异行数=" + afterImport
                + "，余额=" + balanceSnapshot());
        assertEquals(0, afterImport, "期初快照导入后对账必须为 0 行");
        assertEquals(0, new BigDecimal("15.000").compareTo(balanceOf(WH_MAIN, SKU_A)));

        long newBatch = data(reimport(op, batchId)).get("batch_id").asLong();
        assertEquals("COMPLETED", completeExistingBatch(op, newBatch, "b11-c1-snapshot.csv"));
        int afterReimport = reconciliationDiffRows();
        System.out.println("[B11-C1] 时点3（重导后）对账差异行数=" + afterReimport
                + "，余额=" + balanceSnapshot());
        assertEquals(0, afterReimport, "重导并再次确认后对账必须仍为 0 行");

        // ★ 语义记录（不是本批要改的东西）：重导产生的是【新批次】，期初导入的幂等键含
        //   biz_ref_id=新批 id ⇒ applyInitialImport 会再按【增量】落账一次（R78-4「余额增量」口径）。
        //   故 10.000 → 15.000 → 20.000。此处钉死绝对值，避免「余额被清零/被漏记」这类缺陷混过去。
        assertEquals(0, new BigDecimal("20.000").compareTo(balanceOf(WH_MAIN, SKU_A)),
                "重导后余额 = 10 + 5 + 5（新批次按增量落账，既有语义）");
        assertEquals(4, countInventoryTxns(), "种子 2 条 + 两次导入各 1 条");
    }

    // =====================================================================
    // D1：反向误杀
    // =====================================================================

    @Test
    @DisplayName("B11-D1 反向误杀：全合法文件（同 SKU 跨两仓）必须 COMPLETED、ERROR 行=0、不得 PARTIAL_SUCCESS")
    void fullyLegalFileCompletesNotPartialSuccess() throws Exception {
        String op = token(OPERATOR);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) "
                + "VALUES (?, ?, 'WH-B', '分仓', 'ACTIVE')", WH_B, TEAM_ID);

        byte[] csv = csv("sku_code,warehouse_code,qty\n"
                + "SKU-A,WH-MAIN,4.000\n"
                + "SKU-A,WH-B,6.000\n"
                + "SKU-B,WH-MAIN,2.000\n");
        // ★ 第 1/3 行命中既有余额行 ⇒ 两条 IMP-4006 WARNING（覆盖/增量提示，非错误）；
        //   第 2 行（WH-B 无余额行）无告警。本用例的判据是「ERROR=0 且终态 COMPLETED」，告警不参与。
        long batchId = validatedBatch(op, "b11-d1-legal.csv", csv, "INVENTORY_SNAPSHOT", 0, 2);

        ResponseEntity<String> resp = confirm(op, batchId, "b11-d1-legal", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText(), "全合法文件必须 COMPLETED：" + resp.getBody());
        assertNotEquals("PARTIAL_SUCCESS", data.get("status").asText(), "反向误杀：不得把全合法文件判成部分失败");
        assertEquals(3, data.get("total_rows").asInt());
        assertEquals(3, data.get("success_rows").asInt());
        assertEquals(0, data.get("failed_rows").asInt());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors "
                + "WHERE batch_id = ? AND error_level = 'ERROR'", Integer.class, batchId), "ERROR 行必须为 0");

        assertEquals(0, new BigDecimal("14.000").compareTo(balanceOf(WH_MAIN, SKU_A)), "主仓 10+4");
        assertEquals(0, new BigDecimal("6.000").compareTo(balanceOf(WH_B, SKU_A)), "分仓 0+6");
        assertEquals(0, new BigDecimal("12.000").compareTo(balanceOf(WH_MAIN, SKU_B)), "SKU-B 10+2");
        assertEquals(0, reconciliationDiffRows());
    }

    // =====================================================================
    // 辅助：导入流程
    // =====================================================================

    /** 上传 → 预览 → 映射（服务端建议）→ 校验 → 确认，断言终态 {@code COMPLETED}，返回批次 id。 */
    private long completedBatch(String bearer, String fileName, byte[] content, String bizType) throws Exception {
        long batchId = uploadOk(bearer, fileName, content, bizType);
        assertEquals("COMPLETED", completeExistingBatch(bearer, batchId, fileName),
                "批次 " + batchId + " 应导入成功");
        return batchId;
    }

    /**
     * 把一个已存在的批次从当前状态推进到终态（预览 → 映射 → 校验 → 确认），返回终态字符串。
     * 用于「重导出来的新批」——它的 {@code status=UPLOADED}，必须重新走一遍流程。
     */
    private String completeExistingBatch(String bearer, long batchId, String fileName) throws Exception {
        JsonNode suggested = data(preview(bearer, batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        assertEquals(HttpStatus.OK, putMapping(bearer, batchId, map).getStatusCode(), "映射应成功");

        ResponseEntity<String> validated = validate(bearer, batchId);
        assertEquals(HttpStatus.OK, validated.getStatusCode(), validated.getBody());
        assertEquals("VALIDATED", data(validated).get("status").asText(), validated.getBody());

        ResponseEntity<String> confirmed = confirm(bearer, batchId, "key-b11-" + batchId, Map.of());
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        return data(confirmed).get("status").asText();
    }

    /**
     * 上传 → 预览 → 映射（服务端建议）→ 校验，并断言 {@code VALIDATED}、
     * {@code error_rows} 与 {@code warning_rows} 的期望值。
     */
    private long validatedBatch(String bearer, String fileName, byte[] content, String bizType,
                                int expectedErrorRows, int expectedWarningRows) throws Exception {
        long batchId = uploadOk(bearer, fileName, content, bizType);
        JsonNode suggested = data(preview(bearer, batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        assertEquals(HttpStatus.OK, putMapping(bearer, batchId, map).getStatusCode());

        ResponseEntity<String> resp = validate(bearer, batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "校验应成功（非全失败）：" + resp.getBody());
        JsonNode data = data(resp);
        assertEquals("VALIDATED", data.get("status").asText(), resp.getBody());
        assertEquals(expectedErrorRows, data.get("error_rows").asInt(), "校验错误行数：" + resp.getBody());
        assertEquals(expectedWarningRows, data.get("warning_rows").asInt(), "校验告警行数：" + resp.getBody());
        return batchId;
    }

    // =====================================================================
    // 辅助：HTTP
    // =====================================================================

    private ResponseEntity<String> reimport(String bearer, long batchId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/reimport", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> upload(String bearer, String fileName, byte[] content, String bizType) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(content, fileName));
        body.add("bizType", bizType);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(bearer);
        return rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private long uploadOk(String bearer, String fileName, byte[] content, String bizType) throws Exception {
        ResponseEntity<String> response = upload(bearer, fileName, content, bizType);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return data(response).get("batch_id").asLong();
    }

    private ResponseEntity<String> preview(String bearer, long batchId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/preview", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> putMapping(String bearer, long batchId, Map<String, String> fieldMapping) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        Map<String, Object> body = Map.of("field_mapping_json", fieldMapping, "parse_options_json", Map.of());
        return rest.exchange("/api/v1/import/batches/" + batchId + "/mapping", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> validate(String bearer, long batchId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/validate", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> confirm(String bearer, long batchId, String idempotencyKey,
                                           Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    // =====================================================================
    // 辅助：断言 / 数据
    // =====================================================================

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private Map<String, Object> batchRow(long id) {
        return jdbc.queryForMap("SELECT * FROM import_batches WHERE id = ?", id);
    }

    private int attemptNo(long batchId) {
        return ((Number) batchRow(batchId).get("attempt_no")).intValue();
    }

    private Long supersededBy(long batchId) {
        Object value = batchRow(batchId).get("superseded_by_batch_id");
        return value == null ? null : ((Number) value).longValue();
    }

    private Object dedupeHash(long batchId) {
        return batchRow(batchId).get("dedupe_hash");
    }

    private int countSkus() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM skus WHERE team_id = ?", Integer.class, TEAM_ID);
    }

    private int countInventoryTxns() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE team_id = ?",
                Integer.class, TEAM_ID);
    }

    private long skuIdByCode(String code) {
        return jdbc.queryForObject("SELECT id FROM skus WHERE team_id = ? AND BINARY sku_code = ?",
                Long.class, TEAM_ID, code);
    }

    private String skuName(long skuId) {
        return jdbc.queryForObject("SELECT name FROM skus WHERE id = ?", String.class, skuId);
    }

    /** 指定仓库 + SKU 的 {@code on_hand_qty}；行不存在时返回 {@code null}。 */
    private BigDecimal balanceOf(long warehouseId, long skuId) {
        List<BigDecimal> rows = jdbc.queryForList("SELECT on_hand_qty FROM inventory_balances "
                + "WHERE team_id = ? AND warehouse_id = ? AND sku_id = ?", BigDecimal.class,
                TEAM_ID, warehouseId, skuId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String balanceSnapshot() {
        List<String> rows = jdbc.queryForList("SELECT CONCAT(warehouse_id, '/', sku_id, '=', on_hand_qty) "
                + "FROM inventory_balances WHERE team_id = ? ORDER BY warehouse_id, sku_id", String.class, TEAM_ID);
        return String.join(" ", rows);
    }

    /**
     * {@code TEST_STRATEGY §7.3} 对账 SQL 返回的<b>差异行数</b>（期望恒为 0）。
     *
     * <p>冲销流水的 {@code qty_delta} 是原值的相反数（{@code InventoryWriteService}），
     * 故直接 {@code SUM(qty_delta)} 即净额，无需按 {@code txn_type} 过滤。</p>
     */
    private int reconciliationDiffRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ("
                + "SELECT b.team_id, b.warehouse_id, b.sku_id, b.on_hand_qty, "
                + "COALESCE(SUM(t.qty_delta), 0) AS derived_qty "
                + "FROM inventory_balances b "
                + "LEFT JOIN inventory_transactions t "
                + "  ON t.team_id = b.team_id AND t.warehouse_id = b.warehouse_id AND t.sku_id = b.sku_id "
                + "WHERE b.team_id = ? "
                + "GROUP BY b.team_id, b.warehouse_id, b.sku_id, b.on_hand_qty "
                + "HAVING b.on_hand_qty <> COALESCE(SUM(t.qty_delta), 0)) x", Integer.class, TEAM_ID);
    }

    /** 建一条 {@code 10.000} 的余额行，并配一条等额 {@code INIT_IMPORT} 流水（对账从种子起即为 0）。 */
    private void seedBalanceWithInitTxn(long skuId) {
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                        + "in_transit_qty, committed_qty, created_by, updated_by, version) "
                        + "VALUES (?, ?, ?, 10.000, 0.000, 0.000, ?, ?, 0)",
                TEAM_ID, WH_MAIN, skuId, OPERATOR_ID, OPERATOR_ID);
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                        + "qty_before, qty_after, biz_type, idempotency_key, reason, operator_id, "
                        + "occurred_at, created_by) "
                        + "VALUES (?, ?, ?, 'INIT_IMPORT', 10.000, 0.000, 10.000, 'IMPORT', ?, "
                        + "'B11 种子期初', ?, NOW(3), ?)",
                TEAM_ID, WH_MAIN, skuId, "b11-seed-init-" + skuId, OPERATOR_ID, OPERATOR_ID);
    }

    private String token(String username) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username
                + "\",\"password\":\"" + TestCredentials.RAW_PASSWORD + "\"}";
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + username + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", TestCredentials.PASSWORD_HASH, username);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 带文件名的 {@link ByteArrayResource}（multipart 需要 {@code filename} 参与 Content-Disposition）。 */
    private static final class NamedResource extends ByteArrayResource {

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
