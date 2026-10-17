package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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
 * {@code B4-PERF} 审计语义端到端测试（{@code ADR-004} D1–D4/D6）—— 真栈
 * （Spring 上下文 + Testcontainers MySQL 8.0.36 + 真实 HTTP）。
 *
 * <h2>覆盖（任务书 D 组）</h2>
 * <ul>
 *   <li><b>D1</b> 全新建 500 行 → 审计 500 条（{@code MASTER_DATA_STATUS_CHANGE}/{@code PRODUCT}），
 *       {@code success=500 failed=0}；</li>
 *   <li><b>D2</b> 全更新 500 行 → 审计 500 条（<b>{@code R48}</b> 导入更新补审计），
 *       {@code biz_id} 指向既有行 id、{@code change_summary} 为「导入更新商品」；</li>
 *   <li><b>D3</b> 更新 + 状态变更同一行 → <b>2 条</b>审计（导入更新 1 + 停用 1）；</li>
 *   <li><b>D4</b> 500 行中 1 行业务失败（引用漂移 {@code CAT-3001}）→ {@code success=499 failed=1}、
 *       审计 499、{@code import_row_errors} 1 条；</li>
 *   <li><b>D8</b> {@code K1}（{@code R43}）：{@code total_rows=200001} → {@code 400 IMP-4013}，
 *       批次状态<b>不推进</b>；</li>
 *   <li><b>D10</b> REST {@code PATCH} 主数据 → 审计仍<b>恰好 1 条</b>（{@code @Audited} 未被
 *       {@code B1} 的导入更新审计重复化）。</li>
 * </ul>
 *
 * <p><b>审计计数口径</b>：一律用 SQL {@code COUNT(*)} 对 {@code audit_logs} 断言，<b>不</b>只看耗时
 * （{@code ADR-004 §6.4}：条数须用 SQL 计数断言）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportConfirmAuditIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-audit-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final long TEAM_ID = 7L;
    private static final long OWNER_ID = 1004L;
    private static final long OPERATOR_ID = 1001L;

    private static final String OPERATOR = "operator01";
    private static final String OWNER = "owner01";

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
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) "
                + "VALUES (?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);
        insertUser(OWNER_ID, OWNER);
        insertUser(OPERATOR_ID, OPERATOR);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID);
    }

    // =====================================================================
    // D1 全新建 500 行 → 审计 500 条
    // =====================================================================

    @Test
    @DisplayName("D1 全新建 500 行 → success=500 failed=0，审计恰 500 条（条数不抑制）")
    void createFiveHundredProducesFiveHundredAudits() throws Exception {
        byte[] csv = productCsv("P-NEW", 500, null);
        long batchId = validatedBatch("create500.csv", csv, "PRODUCT", 0);

        ResponseEntity<String> resp = confirm("key-d1", batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText());
        assertEquals(500, data.get("success_rows").asInt());
        assertEquals(0, data.get("failed_rows").asInt());

        assertEquals(500, count("products"), "500 行应全部落库");
        assertEquals(500, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"),
                "审计条数必须等于行数：ADR-004 D3 禁止抑制/采样/合并");
        assertEquals(1, audits("IMPORT_BATCH", "IMPORT_BATCH_COMPLETE"));
    }

    // =====================================================================
    // D2 全更新 500 行 → 审计 500 条（R48）
    // =====================================================================

    @Test
    @DisplayName("D2 全更新 500 行 → 审计恰 500 条（R48），biz_id 为既有行 id、摘要为「导入更新商品」")
    void updateFiveHundredProducesFiveHundredAudits() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            rows.add(new Object[] {TEAM_ID, String.format("P-UPD-%04d", i), "旧名称"});
        }
        jdbc.batchUpdate("INSERT INTO products (team_id, product_code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                rows);
        assertEquals(500, count("products"));

        byte[] csv = productCsv("P-UPD", 500, null);
        long batchId = validatedBatch("update500.csv", csv, "PRODUCT", 0);

        ResponseEntity<String> resp = confirm("key-d2", batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText());
        assertEquals(500, data.get("success_rows").asInt(), "全部命中既有行 → 全为更新");
        assertEquals(0, data.get("failed_rows").asInt());

        assertEquals(500, count("products"), "更新不得新增行");
        assertEquals(500, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"),
                "R48：导入更新路径必须逐行产生审计（1 万行 = 1 万条同源）");

        Integer named = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE action = 'MASTER_DATA_STATUS_CHANGE' AND change_summary = '导入更新商品'",
                Integer.class);
        assertEquals(500, named, "change_summary 须能区分「导入更新」与「导入新建」");

        // biz_id 必须是既有行 id（而不是新建 id）：取一条与新名称交叉核对
        Map<String, Object> one = jdbc.queryForMap("SELECT a.biz_id AS biz_id, p.id AS product_id "
                + "FROM audit_logs a JOIN products p ON p.product_code = a.biz_no "
                + "WHERE a.action = 'MASTER_DATA_STATUS_CHANGE' AND a.biz_type = 'PRODUCT' LIMIT 1");
        assertEquals(((Number) one.get("product_id")).longValue(), ((Number) one.get("biz_id")).longValue(),
                "审计 biz_id 应等于既有商品 id");
    }

    // =====================================================================
    // D3 更新 + 状态变更 → 2 条审计
    // =====================================================================

    @Test
    @DisplayName("D3 同一行「更新 + 状态变更」→ 审计 2 条（导入更新 1 + 停用 1）")
    void updateWithStatusChangeProducesTwoAudits() throws Exception {
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (9101, ?, 'P-STAT-001', '旧名称', 'ACTIVE')", TEAM_ID);

        byte[] csv = csv("product_code,name,status\nP-STAT-001,新名称,INACTIVE\n");
        long batchId = validatedBatch("stat.csv", csv, "PRODUCT", 0);

        ResponseEntity<String> resp = confirm("key-d3", batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText());

        assertEquals(2, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"),
                "更新 1 条 + 状态变更 1 条 = 2 条（B1 明确语义）");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE change_summary = '导入更新商品'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE change_summary = '停用商品'",
                Integer.class));
        assertEquals("INACTIVE", jdbc.queryForObject("SELECT status FROM products WHERE id = 9101", String.class));
    }

    // =====================================================================
    // D4 500 行中 1 行业务失败
    // =====================================================================

    @Test
    @DisplayName("D4 500 行中 1 行引用漂移失败 → success=499 failed=1、审计 499、错误行 1")
    void oneBusinessFailureOutOfFiveHundred() throws Exception {
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status) "
                + "VALUES (9201, ?, 'SUP-TMP', '临时供应商', 'ACTIVE')", TEAM_ID);

        // 第 300 行引用 SUP-TMP，其余行不引用供应商
        StringBuilder sb = new StringBuilder("product_code,name,default_supplier_code\n");
        for (int i = 1; i <= 500; i++) {
            sb.append(String.format("P-MIX-%04d", i)).append(",混批商品")
                    .append(i == 300 ? ",SUP-TMP" : "").append('\n');
        }
        long batchId = validatedBatch("mix.csv", csv(sb.toString()), "PRODUCT", 0);

        // validate 与 confirm 之间制造「引用漂移」（R40 D3 场景）：删除被引用供应商
        jdbc.update("DELETE FROM suppliers WHERE code = 'SUP-TMP'");

        ResponseEntity<String> resp = confirm("key-d4", batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARTIAL_SUCCESS", data.get("status").asText(), resp.getBody());
        assertEquals(499, data.get("success_rows").asInt());
        assertEquals(1, data.get("failed_rows").asInt());

        assertEquals(499, count("products"), "失败行不得落库");
        assertEquals(499, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"),
                "失败行不产生审计（Pass 1 的缓冲随回滚丢弃，Pass 2 只收集成功的 499 行）");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors WHERE batch_id = ?",
                Integer.class, batchId));
        assertEquals("CAT-3001", jdbc.queryForObject(
                "SELECT error_code FROM import_row_errors WHERE batch_id = ?", String.class, batchId));
        assertEquals(300, jdbc.queryForObject(
                "SELECT row_no FROM import_row_errors WHERE batch_id = ?", Integer.class, batchId));
    }

    // =====================================================================
    // D8 K1：total_rows > 200000 → 400 IMP-4013 且状态不推进
    // =====================================================================

    @Test
    @DisplayName("D8 K1（R43）：total_rows=200001 → 400 IMP-4013，批次状态保持 VALIDATED")
    void overRowLimitIsRejected() throws Exception {
        long batchId = validatedBatch("k1.csv", csv("product_code,name\nP-K1-001,K1\n"), "PRODUCT", 0);
        // 直接把 total_rows 改成超限值（模拟超大文件的元数据；解析结果无关，K1 在解析前判定）
        jdbc.update("UPDATE import_batches SET total_rows = 200001 WHERE id = ?", batchId);

        ResponseEntity<String> resp = confirm("key-d8", batchId);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4013", code(resp));
        assertEquals("VALIDATED", batchRow(batchId).get("status"), "K1 判定须在状态推进之前");
        assertEquals(0, count("products"));
        assertEquals(0, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"));
    }

    // =====================================================================
    // D10 REST PATCH 不因 B1 变成 2 条审计
    // =====================================================================

    @Test
    @DisplayName("D10 REST PATCH /products/{id} → 审计仍恰 1 条（@Audited 未被导入审计重复化）")
    void restUpdateStillProducesSingleAudit() throws Exception {
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (9301, ?, 'P-REST-001', '旧名称', 'ACTIVE')", TEAM_ID);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(OPERATOR));
        ResponseEntity<String> resp = rest.exchange("/api/v1/products/9301", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("name", "REST 改名"), headers), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());

        assertEquals(1, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"),
                "REST 路径只由 @Audited 记一条；B1 只在导入 by-code 更新分支补审计");
        assertEquals("REST 改名", jdbc.queryForObject("SELECT name FROM products WHERE id = 9301", String.class));
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** 生成 {@code rows} 行商品 CSV（{@code prefix} + 4 位序号）；{@code supplierCode} 非空时每行都带。 */
    private static byte[] productCsv(String prefix, int rows, String supplierCode) {
        StringBuilder sb = new StringBuilder("product_code,name,default_supplier_code\n");
        for (int i = 1; i <= rows; i++) {
            sb.append(String.format("%s-%04d", prefix, i)).append(",压测商品")
                    .append(supplierCode == null ? "" : "," + supplierCode).append('\n');
        }
        return csv(sb.toString());
    }

    private long validatedBatch(String fileName, byte[] csv, String bizType, int expectedErrorRows)
            throws Exception {
        long batchId = uploadOk(fileName, csv, bizType);
        preview(batchId);
        mapping(batchId);
        ResponseEntity<String> resp = validate(batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "校验应成功：" + resp.getBody());
        assertEquals("VALIDATED", data(resp).get("status").asText(), resp.getBody());
        assertEquals(expectedErrorRows, data(resp).get("error_rows").asInt(), resp.getBody());
        return batchId;
    }

    private ResponseEntity<String> preview(long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(OPERATOR));
        return rest.exchange("/api/v1/import/batches/" + batchId + "/preview", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private void mapping(long batchId) throws Exception {
        JsonNode suggested = data(preview(batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(OPERATOR));
        Map<String, Object> body = Map.of("field_mapping_json", map, "parse_options_json", Map.of());
        rest.exchange("/api/v1/import/batches/" + batchId + "/mapping", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> validate(long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(OPERATOR));
        return rest.exchange("/api/v1/import/batches/" + batchId + "/validate", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> confirm(String key, long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(OPERATOR));
        headers.set("Idempotency-Key", key);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers), String.class);
    }

    private long uploadOk(String fileName, byte[] content, String bizType) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(content, fileName));
        body.add("bizType", bizType);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token(OPERATOR));
        ResponseEntity<String> response = rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return data(response).get("batch_id").asLong();
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

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE team_id = ?", Integer.class, TEAM_ID);
    }

    private int audits(String bizType, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE team_id = ? "
                + "AND action = ? AND biz_type = ?", Integer.class, TEAM_ID, action, bizType);
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
