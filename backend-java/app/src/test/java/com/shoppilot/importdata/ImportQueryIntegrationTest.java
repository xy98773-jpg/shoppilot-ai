package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * M3-T07 批次列表 / 详情 / 错误报告导出 / 模板清单端到端集成测试 —— 真栈
 * （Spring 上下文 + Testcontainers MySQL 8.0.36 + 真实 HTTP）。
 *
 * <p><b>依据</b>：{@code .workbuddy/M3-T06-T07-任务书-v2.md} 裁定 {@code A5}/{@code J1}–{@code J4}
 * 与 §6② 验收项 ㊻–㊾（及 {@code J4} 分页参数校验）。</p>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportQueryIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-query-test").toString();
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
    private static final String OTHER_OWNER = "owner8";

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

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);
        insertUser(OWNER_ID, "owner01");
        insertUser(OPERATOR_ID, OPERATOR);
        insertUser(VIEWER_ID, VIEWER);
        insertUser(OTHER_OWNER_ID, OTHER_OWNER);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), "
                        + "(?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);

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
    // ㊻ 批次列表：字段名 list / 筛选 / 团队隔离
    // =====================================================================

    @Test
    @DisplayName("㊻ 列表：分页字段 list、只含本团队、按 status/bizType 过滤生效")
    void listBatchesPaginationAndFilter() throws Exception {
        long productBatch = uploadOk(OPERATOR, "p.csv", csv("product_code,name\nP-L-001,X\n"), "PRODUCT");
        long supplierBatch = uploadOk(OPERATOR, "s.csv", csv("code,name\nSUP-L-1,Y\n"), "SUPPLIER");
        preview(OPERATOR, supplierBatch); // → PARSED
        long otherTeamBatch = uploadOk(OTHER_OWNER, "o.csv", csv("product_code,name\nP-O-001,Z\n"), "PRODUCT");

        ResponseEntity<String> resp = list(OPERATOR, "");
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode d = data(resp);
        assertTrue(d.has("list"), "分页字段名必须是 list（裁定 J1）");
        assertEquals(2, d.get("total").asInt(), "只含本团队");
        assertEquals(2, d.get("list").size());
        Set<Long> ids = new HashSet<>();
        for (JsonNode n : d.get("list")) {
            ids.add(n.get("batch_id").asLong());
            assertEquals("T-HX", jdbc.queryForObject("SELECT t.code FROM teams t "
                    + "JOIN import_batches b ON b.team_id = t.id WHERE b.id = ?", String.class,
                    n.get("batch_id").asLong()));
        }
        assertTrue(ids.contains(productBatch) && ids.contains(supplierBatch));
        assertTrue(!ids.contains(otherTeamBatch), "异团队批次不得出现");

        JsonNode byStatus = data(list(OPERATOR, "?status=PARSED"));
        assertEquals(1, byStatus.get("total").asInt());
        assertEquals(supplierBatch, byStatus.get("list").get(0).get("batch_id").asLong());

        JsonNode byBiz = data(list(OPERATOR, "?bizType=PRODUCT"));
        assertEquals(1, byBiz.get("total").asInt());
        assertEquals(productBatch, byBiz.get("list").get(0).get("batch_id").asLong());

        JsonNode byPage = data(list(OPERATOR, "?page=1&pageSize=1"));
        assertEquals(2, byPage.get("total").asInt());
        assertEquals(1, byPage.get("list").size());
        assertEquals(1, byPage.get("page").asInt());
        assertEquals(1, byPage.get("pageSize").asInt());
    }

    // =====================================================================
    // J4 分页参数非法 → 400 COM-0007
    // =====================================================================

    @Test
    @DisplayName("J4 分页参数非法（page=0 / pageSize=0 / pageSize=201）→ 400 COM-0007")
    void invalidPaginationReturnsCom0007() throws Exception {
        for (String query : List.of("?page=0", "?pageSize=0", "?pageSize=201")) {
            ResponseEntity<String> resp = list(OPERATOR, query);
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), query + " → " + resp.getBody());
            assertEquals("COM-0007", code(resp), query);
        }
        // 日期格式非法 → 400 COM-0001
        ResponseEntity<String> badDate = list(OPERATOR, "?from=2026/10/01");
        assertEquals(HttpStatus.BAD_REQUEST, badDate.getStatusCode(), badDate.getBody());
        assertEquals("COM-0001", code(badDate));
    }

    // =====================================================================
    // ㊼ 详情：统计字段 + 404 从严
    // =====================================================================

    @Test
    @DisplayName("㊼ 详情：错误统计正确；跨团队 / 不存在 id → 404 IMP-4007")
    void detailStatisticsAndNotFound() throws Exception {
        long batchId = uploadOk(OPERATOR, "p.csv",
                csv("product_code,name\nP-D-001,A\nP-D-001,B\nP-D-002,C\n"), "PRODUCT");
        preview(OPERATOR, batchId);
        mapping(OPERATOR, batchId, suggestedMapping(OPERATOR, batchId));
        validate(OPERATOR, batchId); // 1 条 ERROR（文件内重复）

        ResponseEntity<String> resp = detail(OPERATOR, batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode d = data(resp);
        assertEquals(batchId, d.get("batch_id").asLong());
        assertEquals("PRODUCT", d.get("biz_type").asText());
        assertEquals("VALIDATED", d.get("status").asText());
        assertEquals(3, d.get("total_rows").asInt());
        assertEquals(1, d.get("error_rows").asLong(), "实时统计 ERROR 行数");
        assertEquals(0, d.get("warning_rows").asLong());
        assertNotNull(d.get("batch_no").asText());
        assertTrue(d.has("file_hash") && d.has("file_size") && d.has("attempt_no"));
        assertTrue(d.get("started_at").isNull(), "未落库前 started_at 应为 null");

        long otherTeamBatch = uploadOk(OTHER_OWNER, "o.csv", csv("product_code,name\nP-D-OTHER,Z\n"), "PRODUCT");
        ResponseEntity<String> cross = detail(OPERATOR, otherTeamBatch);
        assertEquals(HttpStatus.NOT_FOUND, cross.getStatusCode(), cross.getBody());
        assertEquals("IMP-4007", code(cross));

        ResponseEntity<String> missing = detail(OPERATOR, 9_999_999L);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode(), missing.getBody());
        assertEquals("IMP-4007", code(missing));
    }

    // =====================================================================
    // ㊽ 导出：CSV + BOM + 行数一致 + 文件名含 batch_no
    // =====================================================================

    @Test
    @DisplayName("㊽ 导出：text/csv + UTF-8 BOM + 行数与 import_row_errors 一致 + 文件名含 batch_no")
    void exportErrorsCsv() throws Exception {
        long batchId = uploadOk(OPERATOR, "p.csv",
                csv("product_code,name\nP-E-001,A\nP-E-001,B\nP-E-002,C\nP-E-002,D\n"), "PRODUCT");
        preview(OPERATOR, batchId);
        mapping(OPERATOR, batchId, suggestedMapping(OPERATOR, batchId));
        validate(OPERATOR, batchId);

        Integer dbRows = jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors WHERE batch_id = ?",
                Integer.class, batchId);
        String batchNo = jdbc.queryForObject("SELECT batch_no FROM import_batches WHERE id = ?",
                String.class, batchId);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(OPERATOR));
        ResponseEntity<byte[]> resp = rest.exchange("/api/v1/import/batches/" + batchId + "/errors/export",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        byte[] body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.length >= 3, "响应体不应为空");
        assertEquals((byte) 0xEF, body[0], "UTF-8 BOM");
        assertEquals((byte) 0xBB, body[1], "UTF-8 BOM");
        assertEquals((byte) 0xBF, body[2], "UTF-8 BOM");

        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFF"), "BOM 须在流首");
        String[] lines = text.substring(1).split("\n", -1);
        assertEquals("row_no,error_level,error_code,error_field,error_message,原始值", lines[0], "表头口径");
        long dataLines = 0;
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                dataLines++;
            }
        }
        assertEquals(dbRows.longValue(), dataLines, "导出行数须与 import_row_errors 记录数一致");

        String contentType = String.valueOf(resp.getHeaders().getContentType());
        assertTrue(contentType.contains("text/csv"), "Content-Type 应为 text/csv，实际 " + contentType);
        String disposition = String.valueOf(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertTrue(disposition.contains(batchNo), "附件名应含 batch_no：" + disposition);

        // 筛选语义与 GET /{id}/errors 一致
        ResponseEntity<byte[]> filtered = rest.exchange(
                "/api/v1/import/batches/" + batchId + "/errors/export?rowNo=2",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        String filteredText = new String(filtered.getBody(), StandardCharsets.UTF_8);
        long filteredLines = 0;
        String[] fl = filteredText.substring(1).split("\n", -1);
        for (int i = 1; i < fl.length; i++) {
            if (!fl[i].isBlank()) {
                filteredLines++;
            }
        }
        assertEquals(1, filteredLines, "rowNo 过滤应只导出 1 行");
    }

    @Test
    @DisplayName("㊽-2 导出不存在 / 跨团队批次 → 404 IMP-4007")
    void exportMissingBatchReturns404() throws Exception {
        long otherTeamBatch = uploadOk(OTHER_OWNER, "o.csv", csv("product_code,name\nP-X-001,X\n"), "PRODUCT");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(OPERATOR));
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/import/batches/" + otherTeamBatch + "/errors/export",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4007", code(resp));
    }

    // =====================================================================
    // ㊾ 模板清单：6 类 bizType；VIEWER → 403
    // =====================================================================

    @Test
    @DisplayName("㊾ GET /import/templates：6 类 bizType + 字段清单；VIEWER → 403 TEAM-2003")
    void templatesReturnsSixBizTypes() throws Exception {
        ResponseEntity<String> resp = templates(OPERATOR);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode list = data(resp);
        assertEquals(6, list.size(), "6 类 bizType（裁定 A5）");

        Set<String> bizTypes = new HashSet<>();
        for (JsonNode item : list) {
            bizTypes.add(item.get("biz_type").asText());
            assertEquals("v1", item.get("template_version").asText());
            assertTrue(item.get("fields").size() > 0, item.get("biz_type").asText() + " 应有字段清单");
            for (JsonNode f : item.get("fields")) {
                assertNotNull(f.get("name").asText());
                assertNotNull(f.get("label").asText());
                assertTrue(f.has("required"));
                assertNotNull(f.get("type").asText());
            }
        }
        assertEquals(Set.of("PRODUCT", "SKU", "SUPPLIER", "WAREHOUSE",
                "INVENTORY_SNAPSHOT", "SALES_DAILY"), bizTypes);

        // PRODUCT 的 product_code 必填，SUPPLIER 的 code 必填（§7.1 / §7.3）
        JsonNode product = findByBizType(list, "PRODUCT");
        assertTrue(field(product, "product_code").get("required").asBoolean());
        assertTrue(field(product, "name").get("required").asBoolean());
        assertTrue(field(findByBizType(list, "SUPPLIER"), "code").get("required").asBoolean());

        ResponseEntity<String> forbidden = templates(VIEWER);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode(), forbidden.getBody());
        assertEquals("TEAM-2003", code(forbidden));
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private ResponseEntity<String> list(String user, String query) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(user));
        return rest.exchange("/api/v1/import/batches" + query, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> detail(String user, long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(user));
        return rest.exchange("/api/v1/import/batches/" + batchId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> templates(String user) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(user));
        return rest.exchange("/api/v1/import/templates", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

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

    private long uploadOk(String user, String fileName, byte[] content, String bizType) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(content, fileName));
        body.add("bizType", bizType);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token(user));
        ResponseEntity<String> response = rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return data(response).get("batch_id").asLong();
    }

    private Map<String, String> suggestedMapping(String user, long batchId) throws Exception {
        JsonNode suggested = data(preview(user, batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        return map;
    }

    private static JsonNode findByBizType(JsonNode list, String bizType) {
        for (JsonNode n : list) {
            if (bizType.equals(n.get("biz_type").asText())) {
                return n;
            }
        }
        throw new AssertionError("未找到 bizType=" + bizType);
    }

    private static JsonNode field(JsonNode templateItem, String name) {
        for (JsonNode f : templateItem.get("fields")) {
            if (name.equals(f.get("name").asText())) {
                return f;
            }
        }
        throw new AssertionError("未找到字段 " + name);
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
