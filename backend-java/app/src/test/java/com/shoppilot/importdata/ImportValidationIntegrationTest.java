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
import java.nio.file.Path;
import java.time.LocalDate;
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
 * M3-T03/T04/T05 导入生命周期 —— 端到端集成测试（真实 Spring 上下文 + Testcontainers MySQL 8.0.36 + 真实 HTTP）。
 *
 * <p>覆盖任务书 §4 的解析预览 / 字段映射 / 全量校验 / 错误报告四类端点：状态机推进、
 * 两级映射校验、§10.1 通用校验 + §10.2 各类型特有校验（引用存在性/同团队/文件内唯一/范围特例）、
 * 全部失败 → {@code 422 IMP-4010}、错误明细分页与过滤、团队隔离、VIEWER 越权。</p>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportValidationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-test").toString();
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

    private static final String PAST_DATE = LocalDate.now().minusDays(1).toString();
    private static final String FUTURE_DATE = LocalDate.now().plusYears(2).toString();

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
        // 主数据（sku/supplier/product 含 FK，须先于其父表删除）
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);
        insertUser(OWNER_ID, "owner01");
        insertUser(OPERATOR_ID, "operator01");
        insertUser(VIEWER_ID, "viewer01");
        insertUser(OTHER_OWNER_ID, "owner8");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), "
                        + "(?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);

        // 校验所需的同团队主数据（team 7）
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status) VALUES (9003, ?, 'SUP-001', 'sup', 'ACTIVE')",
                TEAM_ID);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) VALUES (9001, ?, 'P-BAG-001', 'Bag', 'ACTIVE')",
                TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, status) VALUES (9002, ?, 9001, 'SKU-BAG-001-BLK', 'ACTIVE')",
                TEAM_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) VALUES (9004, ?, 'WH-MAIN', 'Main', 'ACTIVE')",
                TEAM_ID);
    }

    // =====================================================================
    // T03 解析预览
    // =====================================================================

    @Test
    @DisplayName("T03 上传 CSV → 首次 preview 推进 UPLOADED→PARSED，返回 total_rows/columns/suggested_mapping")
    void previewUploadedToParsed() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", salesCsv(1, "SKU-BAG-001-BLK", PAST_DATE, "12"), "SALES_DAILY");
        ResponseEntity<String> resp = preview(token("operator01"), batchId, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARSED", data.get("status").asText());
        assertEquals(1, data.get("total_rows").asInt());
        assertTrue(data.get("columns").size() >= 4, "应返回全部源列");
        assertEquals(4, data.get("suggested_mapping").size(), "四列应全部自动映射");
        Map<String, Object> row = batchRow(batchId);
        assertEquals("PARSED", row.get("status"));
    }

    @Test
    @DisplayName("T03 预览不存在的批次 → 404 IMP-4007（含跨团队语义）")
    void previewMissingBatchReturns404() throws Exception {
        ResponseEntity<String> resp = preview(token("operator01"), 9_999_999L, null);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4007", code(resp));
    }

    @Test
    @DisplayName("T03 已 MAPPED 批次再次预览幂等，返回当前状态 MAPPED")
    void previewAfterMappedReturnsMapped() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", salesCsv(1, "SKU-BAG-001-BLK", PAST_DATE, "12"), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = preview(token("operator01"), batchId, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("MAPPED", data(resp).get("status").asText());
    }

    // =====================================================================
    // T04 字段映射
    // =====================================================================

    @Test
    @DisplayName("T04 提交合法映射（用自动映射建议）→ 200，status=MAPPED")
    void mappingValidToMapped() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", salesCsv(1, "SKU-BAG-001-BLK", PAST_DATE, "12"), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        ResponseEntity<String> resp = mapping(token("operator01"), batchId, suggestedMapping(batchId));
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("MAPPED", data(resp).get("status").asText());
        assertEquals("MAPPED", batchRow(batchId).get("status"));
    }

    @Test
    @DisplayName("T04 映射缺必填列 → 400 IMP-4003")
    void mappingMissingRequiredFieldReturnsImp4003() throws Exception {
        // SKU 必填 sku_code + product_code；故意漏掉 product_code
        long batchId = uploadOk(token("operator01"), "sku.csv", skuCsv(), "SKU");
        preview(token("operator01"), batchId, null);
        Map<String, String> partial = Map.of("sku_code", "sku_code");
        ResponseEntity<String> resp = mapping(token("operator01"), batchId, partial);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4003", code(resp));
    }

    @Test
    @DisplayName("T04 映射含非法目标字段 → 400 IMP-4008")
    void mappingInvalidTargetFieldReturnsImp4008() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", salesCsv(1, "SKU-BAG-001-BLK", PAST_DATE, "12"), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        Map<String, String> illegal = Map.of("not_a_field", "sku_code", "warehouse_code", "warehouse_code",
                "sale_date", "sale_date", "qty", "qty");
        ResponseEntity<String> resp = mapping(token("operator01"), batchId, illegal);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4008", code(resp));
    }

    @Test
    @DisplayName("T04 在 UPLOADED（未解析）状态提交映射 → 422 IMP-4005")
    void mappingOnUploadedStatusReturnsImp4005() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", salesCsv(1, "SKU-BAG-001-BLK", PAST_DATE, "12"), "SALES_DAILY");
        // 未调用 preview → 仍是 UPLOADED
        ResponseEntity<String> resp = mapping(token("operator01"), batchId, suggestedMapping2());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4005", code(resp));
    }

    // =====================================================================
    // T05 全量校验
    // =====================================================================

    @Test
    @DisplayName("T05 在 PARSED（未映射）状态校验 → 422 IMP-4005")
    void validateOnParsedStatusReturnsImp4005() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", salesCsv(1, "SKU-BAG-001-BLK", PAST_DATE, "12"), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        ResponseEntity<String> resp = validate(token("operator01"), batchId);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4005", code(resp));
    }

    @Test
    @DisplayName("T05 SALES_DAILY 引用不存在的 SKU → 200（部分失败），error_rows=1，写入 import_row_errors")
    void validateSalesDailyBadSkuErrors() throws Exception {
        String csv = "sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-BAG-001-BLK,WH-MAIN," + PAST_DATE + ",12\n"
                + "SKU-NOWHERE,WH-MAIN," + LocalDate.now().minusDays(2) + ",12\n";
        long batchId = uploadOk(token("operator01"), "sales.csv", csv.getBytes(StandardCharsets.UTF_8), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = validate(token("operator01"), batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("VALIDATED", data.get("status").asText());
        assertEquals(1, data.get("error_rows").asInt());
        assertEquals(0, data.get("warning_rows").asInt());

        assertEquals(1, countRowErrors(batchId));
        ResponseEntity<String> errResp = errors(token("operator01"), batchId, 1, 50, null, null);
        JsonNode errData = data(errResp);
        assertEquals(1, errData.get("total").asInt());
        assertEquals("CAT-3001", errData.get("list").get(0).get("error_code").asText());
        assertTrue(errData.get("list").get(0).has("raw_data"), "失败行应回带 raw_data");
    }

    @Test
    @DisplayName("T05 SALES_DAILY 全部行校验失败 → 422 IMP-4010 + data，批次 FAILED 且释放槽位")
    void validateSalesDailyAllBadReturnsImp4010() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", salesCsv(3, "SKU-NOWHERE", PAST_DATE, "12"), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = validate(token("operator01"), batchId);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("IMP-4010", body.get("code").asText());
        assertEquals(3, body.get("data").get("total_rows").asLong());
        assertEquals(3, body.get("data").get("error_rows").asLong());

        Map<String, Object> row = batchRow(batchId);
        assertEquals("FAILED", row.get("status"));
        assertNullRow(row.get("dedupe_hash"), "全失败时释放幂等槽位");
    }

    @Test
    @DisplayName("T05 SALES_DAILY 合法数据（sku+warehouse 同团队存在）→ 200，error_rows=0/warning_rows=0")
    void validateSalesDailyValid() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", salesCsv(1, "SKU-BAG-001-BLK", PAST_DATE, "12"), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = validate(token("operator01"), batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("VALIDATED", data.get("status").asText());
        assertEquals(0, data.get("error_rows").asInt());
        assertEquals(0, data.get("warning_rows").asInt());
        assertEquals(0, countRowErrors(batchId));
    }

    @Test
    @DisplayName("T05 SALES_DAILY 同 (wh,sku,date) 文件内重复 → ERROR 行 SAL-6004（仅第二行）")
    void validateSalesDailyDuplicateKeySal6004() throws Exception {
        String csv = "sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-BAG-001-BLK,WH-MAIN," + PAST_DATE + ",12\n"
                + "SKU-BAG-001-BLK,WH-MAIN," + PAST_DATE + ",12\n";
        long batchId = uploadOk(token("operator01"), "sales.csv", csv.getBytes(StandardCharsets.UTF_8), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = validate(token("operator01"), batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals(1, data.get("error_rows").asInt());
        assertEquals("SAL-6004", onlyErrorLevel(data, "ERROR").get("error_code").asText());
    }

    @Test
    @DisplayName("T05 SALES_DAILY 未来销售日期 → ERROR SAL-6003")
    void validateSalesDailyFutureDateSal6003() throws Exception {
        String csv = "sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-BAG-001-BLK,WH-MAIN," + PAST_DATE + ",12\n"
                + "SKU-BAG-001-BLK,WH-MAIN," + FUTURE_DATE + ",12\n";
        long batchId = uploadOk(token("operator01"), "sales.csv", csv.getBytes(StandardCharsets.UTF_8), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = validate(token("operator01"), batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("SAL-6003", onlyErrorLevel(data(resp), "ERROR").get("error_code").asText());
    }

    @Test
    @DisplayName("T05 SALES_DAILY 负销量 → ERROR SAL-6002（范围特例，非 COM-0001）")
    void validateSalesDailyNegativeQtySal6002() throws Exception {
        String csv = "sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-BAG-001-BLK,WH-MAIN," + PAST_DATE + ",12\n"
                + "SKU-BAG-001-BLK,WH-MAIN," + LocalDate.now().minusDays(2) + ",-5\n";
        long batchId = uploadOk(token("operator01"), "sales.csv", csv.getBytes(StandardCharsets.UTF_8), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = validate(token("operator01"), batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("SAL-6002", onlyErrorLevel(data(resp), "ERROR").get("error_code").asText());
    }

    @Test
    @DisplayName("T05 PRODUCT 合法 → 200，error_rows=0")
    void validateProductValid() throws Exception {
        String csv = "product_code,name\nP-BAG-002,Bag2\n";
        long batchId = uploadOk(token("operator01"), "product.csv", csv.getBytes(StandardCharsets.UTF_8), "PRODUCT");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = validate(token("operator01"), batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals(0, data(resp).get("error_rows").asInt());
    }

    @Test
    @DisplayName("T05 PRODUCT 文件内商品编码重复 → ERROR CAT-3002")
    void validateProductFileInternalDupCat3002() throws Exception {
        String csv = "product_code,name\nP-BAG-002,Bag2\nP-BAG-002,Bag2b\n";
        long batchId = uploadOk(token("operator01"), "product.csv", csv.getBytes(StandardCharsets.UTF_8), "PRODUCT");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = validate(token("operator01"), batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals(1, data(resp).get("error_rows").asInt());
        assertEquals("CAT-3002", onlyErrorLevel(data(resp), "ERROR").get("error_code").asText());
    }

    // =====================================================================
    // 错误报告分页 + 团队隔离 + 越权
    // =====================================================================

    @Test
    @DisplayName("T05 错误明细分页与过滤：3 条 ERROR，pageSize=2 → list=2/total=3；errorLevel=ERROR → total=3")
    void errorsPaginationAndFilter() throws Exception {
        String csv = "sku_code,warehouse_code,sale_date,qty\n"
                + "SKU-NOWHERE,WH-MAIN," + PAST_DATE + ",12\n"
                + "SKU-NOWHERE,WH-MAIN," + LocalDate.now().minusDays(2) + ",12\n"
                + "SKU-NOWHERE,WH-MAIN," + LocalDate.now().minusDays(3) + ",12\n";
        long batchId = uploadOk(token("operator01"), "sales.csv", csv.getBytes(StandardCharsets.UTF_8), "SALES_DAILY");
        preview(token("operator01"), batchId, null);
        mapping(token("operator01"), batchId, suggestedMapping(batchId));
        validate(token("operator01"), batchId); // 全失败（3 行 SKU 不存在）→ 3 条 ERROR

        ResponseEntity<String> p1 = errors(token("operator01"), batchId, 1, 2, null, null);
        JsonNode d1 = data(p1);
        assertEquals(3, d1.get("total").asInt());
        assertEquals(2, d1.get("list").size());

        ResponseEntity<String> filtered = errors(token("operator01"), batchId, 1, 50, "ERROR", null);
        assertEquals(3, data(filtered).get("total").asInt());
    }

    @Test
    @DisplayName("T05 错误报告访问不存在批次 → 404 IMP-4007")
    void errorsMissingBatchReturns404() throws Exception {
        ResponseEntity<String> resp = errors(token("operator01"), 9_999_999L, 1, 20, null, null);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4007", code(resp));
    }

    @Test
    @DisplayName("T03/T04/T05 VIEWER 调用预览/映射/校验/错误报告 → 403 TEAM-2003")
    void viewerForbidden() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", salesCsv(1, "SKU-BAG-001-BLK", PAST_DATE, "12"), "SALES_DAILY");
        assertEquals(HttpStatus.FORBIDDEN, preview(token("viewer01"), batchId, null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, mapping(token("viewer01"), batchId, suggestedMapping2()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, validate(token("viewer01"), batchId).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, errors(token("viewer01"), batchId, 1, 20, null, null).getStatusCode());
        for (ResponseEntity<String> r : List.of(
                preview(token("viewer01"), batchId, null),
                mapping(token("viewer01"), batchId, suggestedMapping2()),
                validate(token("viewer01"), batchId),
                errors(token("viewer01"), batchId, 1, 20, null, null))) {
            assertEquals("TEAM-2003", code(r), r.getBody());
        }
    }

    @Test
    @DisplayName("T03 跨团队批次对其它团队不可见 → preview 返回 404 IMP-4007")
    void crossTeamBatchNotAccessible() throws Exception {
        long otherBatch = uploadOk(token("owner8"), "sales.csv", salesCsv(1, "SKU-BAG-001-BLK", PAST_DATE, "12"), "SALES_DAILY");
        ResponseEntity<String> resp = preview(token("operator01"), otherBatch, null);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4007", code(resp));
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private ResponseEntity<String> preview(String bearer, long batchId, Integer limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        String url = "/api/v1/import/batches/" + batchId + "/preview"
                + (limit == null ? "" : "?limit=" + limit);
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> mapping(String bearer, long batchId, Map<String, String> fieldMapping) {
        return mapping(bearer, batchId, fieldMapping, Map.of());
    }

    private ResponseEntity<String> mapping(String bearer, long batchId, Map<String, String> fieldMapping, Map<?, ?> options) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        Map<String, Object> body = Map.of("field_mapping_json", fieldMapping, "parse_options_json", options);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/mapping", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> validate(String bearer, long batchId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/validate", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> errors(String bearer, long batchId, int page, int pageSize,
                                         String errorLevel, Integer rowNo) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        StringBuilder url = new StringBuilder("/api/v1/import/batches/" + batchId + "/errors?page=" + page
                + "&pageSize=" + pageSize);
        if (errorLevel != null) {
            url.append("&errorLevel=").append(errorLevel);
        }
        if (rowNo != null) {
            url.append("&rowNo=").append(rowNo);
        }
        return rest.exchange(url.toString(), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** 取批次的自动映射建议（先 preview 一次），返回 target→source。 */
    private Map<String, String> suggestedMapping(long batchId) throws Exception {
        JsonNode suggested = data(preview(token("operator01"), batchId, null)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        return map;
    }

    /** 与 suggestedMapping 同构但用于「未解析状态」的占位映射（仅测试 422 分支，不要求合法）。 */
    private Map<String, String> suggestedMapping2() {
        return Map.of("sku_code", "sku_code", "warehouse_code", "warehouse_code",
                "sale_date", "sale_date", "qty", "qty");
    }

    private long uploadOk(String bearer, String fileName, byte[] content, String bizType) throws Exception {
        ResponseEntity<String> response = upload(bearer, fileName, content, bizType);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return data(response).get("batch_id").asLong();
    }

    private ResponseEntity<String> upload(String bearer, String fileName, byte[] content, String bizType) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(content, fileName));
        if (bizType != null) {
            body.add("bizType", bizType);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(bearer);
        return rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
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

    /** 取错误明细中第一条指定级别的记录（用于断言错误码）。 */
    private JsonNode onlyErrorLevel(JsonNode validationData, String level) {
        JsonNode errors = validationData.get("errors");
        for (JsonNode e : errors) {
            if (level.equals(e.get("error_level").asText())) {
                return e;
            }
        }
        throw new AssertionError("未找到级别为 " + level + " 的错误明细：" + validationData);
    }

    private Map<String, Object> batchRow(long id) {
        return jdbc.queryForMap("SELECT * FROM import_batches WHERE id = ?", id);
    }

    private int countRowErrors(long batchId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors WHERE batch_id = ?",
                Integer.class, batchId);
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

    /** 构造 SALES_DAILY CSV：指定行数（均同 sku/date/qty，便于测试重复/全失败/全成功）。 */
    private byte[] salesCsv(int rows, String skuCode, String date, String qty) {
        StringBuilder sb = new StringBuilder("sku_code,warehouse_code,sale_date,qty\n");
        for (int i = 0; i < rows; i++) {
            sb.append(skuCode).append(",WH-MAIN,").append(date).append(",").append(qty).append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 构造 SKU CSV（含 sku_code + product_code 两必填列）。 */
    private byte[] skuCsv() {
        return ("sku_code,product_code,name\nSKU-X,BAG-001,X\n").getBytes(StandardCharsets.UTF_8);
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
