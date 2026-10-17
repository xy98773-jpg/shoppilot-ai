package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.IOException;
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
 * M3-T06 确认导入（分批落库）端到端集成测试 —— 真栈（Spring 上下文 + Testcontainers MySQL 8.0.36 + 真实 HTTP）。
 *
 * <p><b>依据</b>：{@code .workbuddy/M3-T06-T07-任务书-v2.md} 裁定 {@code A}/{@code B}/{@code C}/{@code D}/
 * {@code E}/{@code G}/{@code H}/{@code I}/{@code K}/{@code M} 与 §6② 验收项 ㊱–㊿、⓵–⓹。</p>
 *
 * <p><b>关键断言点</b>：三阶段事务（状态守卫 / 分批独立提交 / 终态 + 不变式）、
 * 行级 SAVEPOINT 语义（{@code NESTED} 偏差的真实栈验证）、幂等重放、{@code IMP-4009} 前置门、
 * {@code A2} 未支持类型、{@code K1} 行数上限、{@code R38} 校验语义订正（M1/M2/M3）。</p>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportConfirmIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-confirm-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OWNER_ID = 1004L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;
    private static final long OTHER_OWNER_ID = 2004L;

    private static final String PAST_DATE = "2026-10-01";

    private static final String OPERATOR = "operator01";
    private static final String VIEWER = "viewer01";

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
        // ★ M3-B10.5（R78）：INVENTORY_SNAPSHOT / SALES_DAILY 落库会写这三张表，
        //   外键均 RESTRICT ⇒ 必须在删 skus / warehouses 之前清理，否则后续用例的种子会失败。
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
        insertUser(VIEWER_ID, VIEWER);
        insertUser(OTHER_OWNER_ID, "owner8");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), "
                        + "(?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);

        // 同团队主数据（team 7）
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status) "
                + "VALUES (9003, ?, 'SUP-001', 'sup', 'ACTIVE')", TEAM_ID);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (9001, ?, 'P-BAG-001', 'Bag', 'ACTIVE')", TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) "
                + "VALUES (9002, ?, 9001, 'SKU-BAG-001-BLK', 'Bag-Black', 'ACTIVE')", TEAM_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) "
                + "VALUES (9004, ?, 'WH-MAIN', 'Main', 'ACTIVE')", TEAM_ID);
    }

    // =====================================================================
    // ㊱ 合法 PRODUCT CSV 全流程 → COMPLETED
    // =====================================================================

    @Test
    @DisplayName("㊱ PRODUCT 全流程（上传→mapping→validate→confirm）→ 200 COMPLETED，真栈 DB 断言")
    void productHappyPathCompletes() throws Exception {
        byte[] csv = csv("product_code,name,category\n"
                + "P-NEW-001,新产品一,箱包\n"
                + "P-NEW-002,新产品二,箱包\n");
        long batchId = validatedBatch(OPERATOR, "product.csv", csv, "PRODUCT", 0);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-happy-1", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText());
        assertEquals(2, data.get("total_rows").asInt());
        assertEquals(2, data.get("success_rows").asInt());
        assertEquals(0, data.get("failed_rows").asInt());
        assertFalse(data.get("started_at").isNull(), "started_at 应非空");
        assertFalse(data.get("finished_at").isNull(), "finished_at 应非空");
        assertTrue(resp.getHeaders().getFirst("Idempotent-Replayed") == null, "首次确认不应带重放头");

        Integer created = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE team_id = ? "
                + "AND product_code IN ('P-NEW-001','P-NEW-002')", Integer.class, TEAM_ID);
        assertEquals(2, created, "真栈应出现对应商品记录");
        assertEquals(3, count("products", TEAM_ID), "products 总数 = 种子 1 + 新增 2");
    }

    // =====================================================================
    // ㊲ 重复 confirm → 重放，不重复落库
    // =====================================================================

    @Test
    @DisplayName("㊲ 重复 confirm → 200 + Idempotent-Replayed: true，目标表记录数不变")
    void repeatConfirmIsReplayed() throws Exception {
        byte[] csv = csv("product_code,name\nP-REPLAY-001,重放商品\n");
        long batchId = validatedBatch(OPERATOR, "replay.csv", csv, "PRODUCT", 0);

        ResponseEntity<String> first = confirm(OPERATOR, batchId, "key-replay", Map.of());
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        assertEquals("COMPLETED", data(first).get("status").asText());
        int afterFirst = count("products", TEAM_ID);

        ResponseEntity<String> second = confirm(OPERATOR, batchId, "key-replay", Map.of());
        assertEquals(HttpStatus.OK, second.getStatusCode(), second.getBody());
        assertEquals("true", second.getHeaders().getFirst("Idempotent-Replayed"),
                "重放须回 Idempotent-Replayed: true");
        assertEquals("COMPLETED", data(second).get("status").asText());
        assertEquals(afterFirst, count("products", TEAM_ID), "重放不得重复落库");
    }

    // =====================================================================
    // ㊳ 状态不允许 → 422 IMP-4005
    // =====================================================================

    @Test
    @DisplayName("㊳ 对 PARSED 批次 confirm → 422 IMP-4005")
    void confirmOnParsedStatusReturnsImp4005() throws Exception {
        long batchId = uploadOk(OPERATOR, "product.csv",
                csv("product_code,name\nP-PARSED-001,X\n"), "PRODUCT");
        preview(OPERATOR, batchId); // UPLOADED → PARSED

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-parsed", Map.of());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4005", code(resp));
        assertEquals("PARSED", batchRow(batchId).get("status"));
    }

    // =====================================================================
    // ㊴ 存在 ERROR 行 → 422 IMP-4009 前置门
    // =====================================================================

    @Test
    @DisplayName("㊴ 存在 ERROR 行的 VALIDATED 批次 confirm → 422 IMP-4009 + data{batch_id,status,error_rows}")
    void confirmWithErrorRowsReturnsImp4009() throws Exception {
        // 文件内重复 → 后行 ERROR CAT-3002，批次仍可 VALIDATED
        byte[] csv = csv("product_code,name\nP-DUP-001,A\nP-DUP-001,B\n");
        long batchId = validatedBatch(OPERATOR, "dup.csv", csv, "PRODUCT", 1);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-blocked", Map.of());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("IMP-4009", body.get("code").asText());
        JsonNode d = body.get("data");
        assertNotNull(d, "IMP-4009 必须回带 data（契约 §11.3）");
        assertEquals(batchId, d.get("batch_id").asLong());
        assertEquals("VALIDATED", d.get("status").asText());
        assertEquals(1, d.get("error_rows").asLong());

        assertEquals("VALIDATED", batchRow(batchId).get("status"), "前置门不得推进批次状态");
        assertEquals(0, count("products", TEAM_ID) - 1, "目标表不得被触碰（仅种子 1 行）");
    }

    // =====================================================================
    // ㊵ 引用漂移 → PARTIAL_SUCCESS（裁定 D3 订正后的场景）
    // =====================================================================

    @Test
    @DisplayName("㊵ 引用漂移（validate 通过后删除被引用供应商）→ 200 PARTIAL_SUCCESS + 不变式成立 + raw_data")
    void referenceDriftYieldsPartialSuccess() throws Exception {
        byte[] csv = csv("product_code,name,default_supplier_code\n"
                + "P-DRIFT-1,漂移一,\n"
                + "P-DRIFT-2,漂移二,SUP-001\n");
        long batchId = validatedBatch(OPERATOR, "drift.csv", csv, "PRODUCT", 0);

        // 校验通过后、confirm 之前：把被引用的供应商从库内删除
        jdbc.update("DELETE FROM suppliers WHERE team_id = ? AND code = 'SUP-001'", TEAM_ID);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-drift", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode d = data(resp);
        assertEquals("PARTIAL_SUCCESS", d.get("status").asText());
        assertEquals(2, d.get("total_rows").asInt());
        assertEquals(1, d.get("success_rows").asInt());
        assertEquals(1, d.get("failed_rows").asInt());
        assertEquals(d.get("total_rows").asInt(),
                d.get("success_rows").asInt() + d.get("failed_rows").asInt(), "不变式 success+failed=total");

        List<Map<String, Object>> errors = jdbc.queryForList("SELECT row_no, error_code, raw_data "
                + "FROM import_row_errors WHERE batch_id = ? AND error_level = 'ERROR'", batchId);
        assertEquals(1, errors.size(), "漂移行应写一条 ERROR");
        assertEquals(2L, ((Number) errors.get(0).get("row_no")).longValue());
        assertEquals("CAT-3001", errors.get(0).get("error_code"));
        assertNotNull(errors.get(0).get("raw_data"), "失败行须回带原始全列");

        Integer drift = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE team_id = ? "
                + "AND product_code = 'P-DRIFT-1'", Integer.class, TEAM_ID);
        assertEquals(1, drift, "行级回滚只影响失败行，成功行必须落库（NESTED 语义真栈验证）");
    }

    // =====================================================================
    // ㊶ 全部行落库失败 → FAILED
    // =====================================================================

    @Test
    @DisplayName("㊶ 全部行落库失败（被引用商品落库前被删）→ 终态 FAILED，success_rows=0")
    void allRowsFailYieldsFailed() throws Exception {
        byte[] csv = csv("sku_code,product_code\nSKU-NEW-9,P-BAG-001\n");
        long batchId = validatedBatch(OPERATOR, "sku.csv", csv, "SKU", 0);

        // 库内既有 SKU 引用 9001，须先删 SKU 再删商品（FK RESTRICT）
        jdbc.update("DELETE FROM skus WHERE team_id = ?", TEAM_ID);
        jdbc.update("DELETE FROM products WHERE team_id = ? AND product_code = 'P-BAG-001'", TEAM_ID);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-allfail", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode d = data(resp);
        assertEquals("FAILED", d.get("status").asText());
        assertEquals(0, d.get("success_rows").asInt());
        assertEquals(1, d.get("failed_rows").asInt());
        assertNullRow(batchRow(batchId).get("dedupe_hash"), "终态 FAILED 须释放幂等槽位");
    }

    // =====================================================================
    // ㊷ 缺 Idempotency-Key → 400 COM-0001
    // =====================================================================

    @Test
    @DisplayName("㊷ 缺 Idempotency-Key → 400 COM-0001 + details[0].field=Idempotency-Key")
    void missingIdempotencyKeyReturnsCom0001() throws Exception {
        long batchId = validatedBatch(OPERATOR, "product.csv",
                csv("product_code,name\nP-KEY-001,K\n"), "PRODUCT", 0);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, null, Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("COM-0001", body.get("code").asText());
        assertEquals("Idempotency-Key", body.get("details").get(0).get("field").asText());
        assertEquals("VALIDATED", batchRow(batchId).get("status"), "缺幂等键不得推进状态");
    }

    // =====================================================================
    // ㊸ expected_total_rows 不符 → 409 COM-0009
    // =====================================================================

    @Test
    @DisplayName("㊸ expected_total_rows 不符 → 409 COM-0009 + details")
    void expectedTotalRowsMismatchReturnsCom0009() throws Exception {
        long batchId = validatedBatch(OPERATOR, "product.csv",
                csv("product_code,name\nP-EXP-001,X\n"), "PRODUCT", 0);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-exp", Map.of("expected_total_rows", 99));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode(), resp.getBody());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("COM-0009", body.get("code").asText());
        assertEquals("expected_total_rows", body.get("details").get(0).get("field").asText());
        assertEquals("VALIDATED", batchRow(batchId).get("status"));
    }

    // =====================================================================
    // ㊹ 未支持类型（A2）→ 400 COM-0001 + details，批次保持 VALIDATED
    // =====================================================================

    @Test
    @DisplayName("㊹ INVENTORY_SNAPSHOT 批次 confirm → 200 COMPLETED，余额与 INIT_IMPORT 流水落库（R78）")
    void inventorySnapshotBizTypeIsSupported() throws Exception {
        // ★ M3-B10.5（R78）断言反转：本用例原先断言「400 COM-0001 + details[bizType]」，
        //   即 R78 的缺陷本身。依赖表（V1.0.6 / V1.0.8）与两个写服务均已就绪，
        //   SUPPORTED_BIZ_TYPES 已扩到六类，故此处改为断言「真的落库」。
        //   细化断言（增量/幂等/IMP-4006/CAT-3001 等）见 ImportDataConfirmIntegrationTest。
        byte[] csv = csv("sku_code,warehouse_code,qty,snapshot_date\n"
                + "SKU-BAG-001-BLK,WH-MAIN,120.000," + PAST_DATE + "\n");
        long batchId = validatedBatch(OPERATOR, "inv.csv", csv, "INVENTORY_SNAPSHOT", 0);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-inv", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText(), resp.getBody());
        assertEquals(1, data.get("success_rows").asInt());

        assertEquals(1, count("inventory_transactions", TEAM_ID), "应写 1 条 INIT_IMPORT 流水");
        assertEquals(0, new java.math.BigDecimal("120.000").compareTo(
                        jdbc.queryForObject("SELECT on_hand_qty FROM inventory_balances "
                                        + "WHERE team_id = ? AND sku_id = 9002 AND warehouse_id = 9004",
                                java.math.BigDecimal.class, TEAM_ID)),
                "余额应为 120.000（原无余额行，自动建行）");
        assertEquals(1, count("products", TEAM_ID), "主数据不得被本类型触碰");
        assertEquals(1, count("skus", TEAM_ID), "主数据不得被本类型触碰");
    }

    // =====================================================================
    // ㊺ 多批（1200 行 > 500）→ 批间独立提交
    // =====================================================================

    @Test
    @DisplayName("㊺ 1200 行（3 个落库批次）→ 全部成功，success_rows=1200")
    void multiBatch1200RowsAllSucceed() throws Exception {
        StringBuilder sb = new StringBuilder("product_code,name\n");
        for (int i = 1; i <= 1200; i++) {
            sb.append(String.format("P-BULK-%04d", i)).append(",批量商品").append(i).append('\n');
        }
        long batchId = validatedBatch(OPERATOR, "bulk.csv", csv(sb.toString()), "PRODUCT", 0);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-bulk", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode d = data(resp);
        assertEquals("COMPLETED", d.get("status").asText());
        assertEquals(1200, d.get("total_rows").asInt());
        assertEquals(1200, d.get("success_rows").asInt());
        assertEquals(0, d.get("failed_rows").asInt());
        assertEquals(1201, count("products", TEAM_ID), "种子 1 + 批量 1200");
    }

    // =====================================================================
    // ⓵ VIEWER 越权 → 403 TEAM-2003
    // =====================================================================

    @Test
    @DisplayName("⓵ VIEWER 调 confirm → 403 TEAM-2003")
    void viewerConfirmForbidden() throws Exception {
        long batchId = validatedBatch(OPERATOR, "product.csv",
                csv("product_code,name\nP-VIEW-001,V\n"), "PRODUCT", 0);

        ResponseEntity<String> resp = confirm(VIEWER, batchId, "key-viewer", Map.of());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(), resp.getBody());
        assertEquals("TEAM-2003", code(resp));
        assertEquals("VALIDATED", batchRow(batchId).get("status"), "越权不得推进状态");
    }

    // =====================================================================
    // ⓶ 回归：同一文件二次上传（未 confirm）→ 409 IMP-4004
    // =====================================================================

    @Test
    @DisplayName("⓶ 回归：同一文件第二次上传（不 confirm）→ 409 IMP-4004")
    void secondUploadOfSameFileIsRejected() throws Exception {
        byte[] csv = csv("product_code,name\nP-REGRESS-001,R\n");
        uploadOk(OPERATOR, "regress.csv", csv, "PRODUCT");
        ResponseEntity<String> second = upload(OPERATOR, "regress.csv", csv, "PRODUCT");
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode(), second.getBody());
        assertEquals("IMP-4004", code(second));
    }

    // =====================================================================
    // ㊿ 审计：confirm 成功写 IMPORT_BATCH_COMPLETE
    // =====================================================================

    @Test
    @DisplayName("㊿ confirm 成功 → 真栈断言 audit_logs 写一行 IMPORT_BATCH_COMPLETE")
    void confirmWritesImportBatchCompleteAudit() throws Exception {
        long batchId = validatedBatch(OPERATOR, "product.csv",
                csv("product_code,name\nP-AUDIT-001,A\n"), "PRODUCT", 0);
        confirm(OPERATOR, batchId, "key-audit", Map.of());

        Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE action = 'IMPORT_BATCH_COMPLETE' AND biz_type = 'IMPORT_BATCH' AND biz_id = ?",
                Integer.class, batchId);
        assertEquals(1, audits, "须写且只写一行批次完成审计（I1）");
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT result FROM audit_logs "
                + "WHERE action = 'IMPORT_BATCH_COMPLETE' AND biz_id = ?", String.class, batchId));
    }

    // =====================================================================
    // ⓷ R38-1：库内已存在 product_code → 不报错 + upsert 更新
    // =====================================================================

    @Test
    @DisplayName("⓷ R38-1 库内已存在 product_code → validate 无 ERROR + confirm 更新库内行（行数不变）")
    void r38ProductInDbIsUpserted() throws Exception {
        long batchId = validatedBatch(OPERATOR, "product.csv",
                csv("product_code,name\nP-BAG-001,Bag-Updated\n"), "PRODUCT", 0);

        assertEquals("Bag", jdbc.queryForObject("SELECT name FROM products WHERE team_id = ? "
                + "AND product_code = 'P-BAG-001'", String.class, TEAM_ID), "confirm 前仍是旧值");

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-r38-1", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText());
        assertEquals(1, count("products", TEAM_ID), "products 行数不变（upsert 更新而非新增）");
        assertEquals("Bag-Updated", jdbc.queryForObject("SELECT name FROM products WHERE team_id = ? "
                + "AND product_code = 'P-BAG-001'", String.class, TEAM_ID), "非键字段应被更新");
    }

    // =====================================================================
    // ⓸ R38-2：SKU 同编码同商品 → 更新；同编码异商品 → CAT-3002
    // =====================================================================

    @Test
    @DisplayName("⓸a R38-2 库内已有 sku_code 且 product_code 一致 → validate 通过 + confirm 更新成功")
    void r38SkuSameProductIsUpserted() throws Exception {
        long batchId = validatedBatch(OPERATOR, "sku.csv",
                csv("sku_code,product_code,name\nSKU-BAG-001-BLK,P-BAG-001,改后的名字\n"), "SKU", 0);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-r38-2a", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText());
        assertEquals(1, count("skus", TEAM_ID), "skus 行数不变");
        assertEquals("改后的名字", jdbc.queryForObject("SELECT name FROM skus WHERE team_id = ? "
                + "AND sku_code = 'SKU-BAG-001-BLK'", String.class, TEAM_ID));
    }

    @Test
    @DisplayName("⓸b R38-2 同 sku_code 指向不同 product_code → CAT-3002（判定层＝catalog 落库层）")
    void r38SkuDifferentProductIsRejected() throws Exception {
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (9005, ?, 'P-OTHER', '别的商品', 'ACTIVE')", TEAM_ID);
        // 校验阶段两条引用（sku_code 自身 / product_code 引用）都合法 → 可达 VALIDATED
        long batchId = validatedBatch(OPERATOR, "sku.csv",
                csv("sku_code,product_code\nSKU-BAG-001-BLK,P-OTHER\n"), "SKU", 0);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-r38-2b", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("FAILED", data(resp).get("status").asText());

        Map<String, Object> err = jdbc.queryForMap("SELECT error_code, error_field FROM import_row_errors "
                + "WHERE batch_id = ?", batchId);
        assertEquals("CAT-3002", err.get("error_code"), "product_id 不可变（§7.2）");
        assertEquals("sku_code", err.get("error_field"));
        assertEquals(9001L, jdbc.queryForObject("SELECT product_id FROM skus WHERE team_id = ? "
                + "AND sku_code = 'SKU-BAG-001-BLK'", Long.class, TEAM_ID), "库内 product_id 不得被改挂");
    }

    // =====================================================================
    // ⓹ R38-3：SUPPLIER / WAREHOUSE 文件内重复 → 后行 ERROR CAT-3002
    // =====================================================================

    @Test
    @DisplayName("⓹a R38-3 SUPPLIER 文件内重复 → 后行 ERROR CAT-3002")
    void r38SupplierInFileDuplicate() throws Exception {
        long batchId = validatedBatch(OPERATOR, "sup.csv",
                csv("code,name\nSUP-002,供应商甲\nSUP-002,供应商乙\n"), "SUPPLIER", 1);

        Map<String, Object> err = jdbc.queryForMap("SELECT row_no, error_code FROM import_row_errors "
                + "WHERE batch_id = ?", batchId);
        assertEquals(2L, ((Number) err.get("row_no")).longValue(), "只判后行");
        assertEquals("CAT-3002", err.get("error_code"));
    }

    @Test
    @DisplayName("⓹b R38-3 WAREHOUSE 文件内重复 → 后行 ERROR CAT-3002")
    void r38WarehouseInFileDuplicate() throws Exception {
        long batchId = validatedBatch(OPERATOR, "wh.csv",
                csv("code,name\nWH-002,仓库甲\nWH-002,仓库乙\n"), "WAREHOUSE", 1);

        Map<String, Object> err = jdbc.queryForMap("SELECT row_no, error_code FROM import_row_errors "
                + "WHERE batch_id = ?", batchId);
        assertEquals(2L, ((Number) err.get("row_no")).longValue());
        assertEquals("CAT-3002", err.get("error_code"));
    }

    // =====================================================================
    // R38 反向守卫：库内已存在不再报错（PRODUCT / SKU / SUPPLIER / WAREHOUSE 全覆盖）
    // =====================================================================

    @Test
    @DisplayName("R38-守卫 四类主数据库内已存在（编码命中 uk_*_team_code）→ 全部 validate 无 ERROR")
    void r38InDbExistenceNeverErrors() throws Exception {
        long productBatch = validatedBatch(OPERATOR, "p.csv",
                csv("product_code,name\nP-BAG-001,Bag2\n"), "PRODUCT", 0);
        long skuBatch = validatedBatch(OPERATOR, "s.csv",
                csv("sku_code,product_code\nSKU-BAG-001-BLK,P-BAG-001\n"), "SKU", 0);
        long supBatch = validatedBatch(OPERATOR, "sup.csv",
                csv("code,name\nSUP-001,sup2\n"), "SUPPLIER", 0);
        long whBatch = validatedBatch(OPERATOR, "wh.csv",
                csv("code,name\nWH-MAIN,Main2\n"), "WAREHOUSE", 0);

        for (long id : List.of(productBatch, skuBatch, supBatch, whBatch)) {
            assertEquals("VALIDATED", batchRow(id).get("status"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors WHERE batch_id = ?",
                    Integer.class, id));
        }
    }

    // =====================================================================
    // 跨团队同编码互不影响（§7.1）—— 反向守卫：不得误判 CAT-3005
    // =====================================================================

    @Test
    @DisplayName("R38-守卫 同编码存在于其它团队 → 本团队导入不报错（§7.1 跨团队同编码互不影响）")
    void crossTeamSameCodeDoesNotError() throws Exception {
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (8001, ?, 'P-SHARED', '别团队的', 'ACTIVE')", OTHER_TEAM_ID);

        long batchId = validatedBatch(OPERATOR, "p.csv",
                csv("product_code,name\nP-SHARED,本团队的\n"), "PRODUCT", 0);

        ResponseEntity<String> resp = confirm(OPERATOR, batchId, "key-cross", Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE product_code = 'P-SHARED'",
                Integer.class), "两个团队各持有自己的行");
    }

    // =====================================================================
    // 辅助：构造「已验证（VALIDATED）」的批次
    // =====================================================================

    /** 上传 → 预览 → 映射 → 校验，断言校验结果（{@code errorRows}）与状态 {@code VALIDATED}。 */
    private long validatedBatch(String user, String fileName, byte[] csv, String bizType, int expectedErrorRows)
            throws Exception {
        long batchId = uploadOk(user, fileName, csv, bizType);
        preview(user, batchId);
        mapping(user, batchId, suggestedMapping(user, batchId));
        ResponseEntity<String> resp = validate(user, batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "校验应成功（非全失败）：" + resp.getBody());
        assertEquals("VALIDATED", data(resp).get("status").asText(), resp.getBody());
        assertEquals(expectedErrorRows, data(resp).get("error_rows").asInt(), "校验错误行数：" + resp.getBody());
        return batchId;
    }

    // =====================================================================
    // 辅助：HTTP
    // =====================================================================

    private ResponseEntity<String> preview(String user, long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(user));
        return rest.exchange("/api/v1/import/batches/" + batchId + "/preview", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> mapping(String user, long batchId, Map<String, String> fieldMapping)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(user));
        Map<String, Object> body = Map.of("field_mapping_json", fieldMapping, "parse_options_json", Map.of());
        return rest.exchange("/api/v1/import/batches/" + batchId + "/mapping", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> validate(String user, long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(user));
        return rest.exchange("/api/v1/import/batches/" + batchId + "/validate", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> confirm(String user, long batchId, String idempotencyKey,
                                           Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(user));
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange("/api/v1/import/batches/" + batchId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private long uploadOk(String user, String fileName, byte[] content, String bizType) throws Exception {
        ResponseEntity<String> response = upload(user, fileName, content, bizType);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return data(response).get("batch_id").asLong();
    }

    private ResponseEntity<String> upload(String user, String fileName, byte[] content, String bizType)
            throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(content, fileName));
        body.add("bizType", bizType);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token(user));
        return rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /** 取批次自动映射建议（先 preview 一次），返回 target→source。 */
    private Map<String, String> suggestedMapping(String user, long batchId) throws Exception {
        JsonNode suggested = data(preview(user, batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        return map;
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private String code(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).get("code").asText();
        } catch (Exception ex) {
            return "";
        }
    }

    private Map<String, Object> batchRow(long id) {
        return jdbc.queryForMap("SELECT * FROM import_batches WHERE id = ?", id);
    }

    private int count(String table, long teamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE team_id = ?", Integer.class, teamId);
    }

    private void assertNullRow(Object value, String hint) {
        assertTrue(value == null || "null".equals(String.valueOf(value)), hint + "：实际 " + value);
    }

    private String token(String username) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + TestCredentials.RAW_PASSWORD + "\"}";
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
