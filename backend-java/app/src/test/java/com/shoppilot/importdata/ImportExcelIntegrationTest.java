package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * B3-hotfix 回修单 —— Excel（{@code .xlsx} / {@code .xls}）解析端到端集成测试（真栈 Testcontainers）。
 *
 * <h2>回归目标（R36）</h2>
 * 修复前 {@link com.shoppilot.importdata.infrastructure.parser.ExcelImportParser} 在
 * {@code sheetIt.next()} 之前调用 {@code getSheetName()} → POI 抛 NPE → 被 {@code catch(Exception)}
 * 吞为 {@code IMP-4002}，任何 {@code .xlsx} 均无法导入。本类用<b>POI 在测试内现造夹具</b>
 * （{@link XSSFWorkbook} / {@link HSSFWorkbook}，不依赖仓库外样例文件、不引入新依赖）
 * 覆盖正/反向共 7 项：单表解析、多表选择与切换、隐藏表跳过、缺必填列进入映射、{@code .xls} 解析、
 * 损坏文件仍判 {@code IMP-4002}+{@code FAILED}、预览内容与编码。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportExcelIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-excel-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OWNER_ID = 1004L;
    private static final long OPERATOR_ID = 1001L;

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

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);
        insertUser(OWNER_ID, "owner01");
        insertUser(OPERATOR_ID, "operator01");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID);
    }

    // =====================================================================
    // ① .xlsx 单表 → 200 PARSED，total_rows / columns 与夹具一致
    // =====================================================================

    @Test
    @DisplayName("Excel① .xlsx 单表（UTF-8）→ 200 PARSED，total_rows 与夹具一致，columns 数=表头数")
    void xlsxSingleSheetParses() throws Exception {
        long batchId = uploadOk(token("operator01"), "product.xlsx", xlsxSingleSheet("Sheet1", 3), "PRODUCT");
        ResponseEntity<String> resp = preview(token("operator01"), batchId, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARSED", data.get("status").asText());
        assertEquals(3, data.get("total_rows").asInt());
        assertEquals(2, data.get("columns").size(), "表头有 2 列，columns 应为 2");
        assertEquals("PARSED", batchRow(batchId).get("status"));
    }

    // =====================================================================
    // ⑦（附加）预览内容 + 编码 + 表名 + 映射状态
    // =====================================================================

    @Test
    @DisplayName("Excel⑦ .xlsx 预览回带真实单元格值 + encoding=UTF-8 + sheet_name + 源列映射状态")
    void xlsxPreviewContentAndEncoding() throws Exception {
        long batchId = uploadOk(token("operator01"), "product.xlsx", xlsxSingleSheet("Sheet1", 2), "PRODUCT");
        JsonNode data = data(preview(token("operator01"), batchId, null));
        assertEquals("UTF-8", data.get("encoding").asText());
        assertEquals("Sheet1", data.get("sheet_name").asText());
        assertEquals(2, data.get("preview").size(), "默认预览行数应等于数据行数（≤20）");
        JsonNode firstRow = data.get("preview").get(0);
        assertEquals(1, firstRow.get("row_no").asInt());
        assertEquals("P-EXCEL-1", firstRow.get("product_code").asText());
        assertEquals("Excel商品1", firstRow.get("name").asText());
        // 源列描述应回带列名与（尚未映射时为 null 的）目标字段
        assertEquals("product_code", data.get("columns").get(0).get("source_name").asText());
    }

    // =====================================================================
    // ② .xlsx 多表：默认第一张可见表；parse_options.sheet_name 指定第二张 → 解析该表
    // =====================================================================

    @Test
    @DisplayName("Excel② .xlsx 多表 → 默认取第一张可见表；sheet_name 指定第二张 → 重新解析该表")
    void xlsxMultiSheetDefaultAndExplicitSwitch() throws Exception {
        byte[] xlsx = xlsxTwoSheets();
        long batchId = uploadOk(token("operator01"), "multi.xlsx", xlsx, "PRODUCT");

        JsonNode first = data(preview(token("operator01"), batchId, null));
        assertEquals("FirstSheet", first.get("sheet_name").asText(), "默认应取第一张可见表");
        assertEquals(1, first.get("total_rows").asInt());
        assertTrue(first.get("sheets").size() >= 2, "应回带可见工作表名单");

        // ★ 工作表切换：随 parse_options 提交（§8.3）→ 服务重新解析第二张表
        ResponseEntity<String> mapResp = mapping(token("operator01"), batchId,
                Map.of("product_code", "product_code", "name", "name"),
                Map.of("sheet_name", "SecondSheet"));
        assertEquals(HttpStatus.OK, mapResp.getStatusCode(), mapResp.getBody());
        assertEquals("MAPPED", data(mapResp).get("status").asText());
        assertEquals(2L, ((Number) batchRow(batchId).get("total_rows")).longValue(),
                "total_rows 应刷新为第二张表的数据行数");

        JsonNode second = data(preview(token("operator01"), batchId, null));
        assertEquals("SecondSheet", second.get("sheet_name").asText(), "切换后应指向第二张表");
        assertEquals(2, second.get("total_rows").asInt());
    }

    // =====================================================================
    // ③ .xlsx 隐藏表跳过：第一张隐藏 + 第二张可见 → 默认取可见的那张（§8.3）
    // =====================================================================

    @Test
    @DisplayName("Excel③ .xlsx 首页隐藏 + 次页可见 → 默认取可见表（隐藏表跳过）")
    void xlsxHiddenFirstSheetIsSkipped() throws Exception {
        long batchId = uploadOk(token("operator01"), "hidden.xlsx", xlsxHiddenFirstSheet(), "PRODUCT");
        JsonNode data = data(preview(token("operator01"), batchId, null));
        assertEquals("VisibleSheet", data.get("sheet_name").asText(), "默认应跳过隐藏表取第一个可见表");
        assertEquals(2, data.get("total_rows").asInt());
        List<String> sheets = new ArrayList<>();
        data.get("sheets").forEach(n -> sheets.add(n.asText()));
        assertEquals(List.of("VisibleSheet"), sheets, "可见表名单不应包含隐藏表");
    }

    // =====================================================================
    // ④ .xlsx 缺必填列 → 映射阶段 400 IMP-4003（证明 Excel 解析结果能进入后续流程）
    // =====================================================================

    @Test
    @DisplayName("Excel④ .xlsx 缺必填列（无 name）→ 映射 400 IMP-4003")
    void xlsxMissingRequiredColumnMappingReturnsImp4003() throws Exception {
        long batchId = uploadOk(token("operator01"), "missing.xlsx", xlsxOnlyProductCode(), "PRODUCT");
        assertEquals(HttpStatus.OK, preview(token("operator01"), batchId, null).getStatusCode());
        // 只映射 product_code，模板必填的 name 未映射 → IMP-4003
        ResponseEntity<String> resp = mapping(token("operator01"), batchId, Map.of("product_code", "product_code"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4003", code(resp));
    }

    // =====================================================================
    // ⑤ .xls（HSSF 夹具）→ 200 PARSED，total_rows 一致
    // =====================================================================

    @Test
    @DisplayName("Excel⑤ .xls（HSSF）→ 200 PARSED，total_rows 与夹具一致")
    void xlsHssfParses() throws Exception {
        long batchId = uploadOk(token("operator01"), "legacy.xls", xlsSingleSheet("Sheet1", 2), "PRODUCT");
        ResponseEntity<String> resp = preview(token("operator01"), batchId, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARSED", data.get("status").asText());
        assertEquals(2, data.get("total_rows").asInt());
        assertEquals(2, data.get("columns").size());
    }

    // =====================================================================
    // ⑥ 反向：损坏的 .xlsx（非 zip 内容但扩展名正确）→ 400 IMP-4002 + 批次 FAILED
    // =====================================================================

    @Test
    @DisplayName("Excel⑥ 损坏的 .xlsx（非 zip）→ 400 IMP-4002 且批次置 FAILED（反向用例）")
    void corruptedXlsxReturnsImp4002AndFailed() throws Exception {
        byte[] bogus = "this is definitely not a zip archive".getBytes(StandardCharsets.UTF_8);
        long batchId = uploadOk(token("operator01"), "broken.xlsx", bogus, "PRODUCT");
        ResponseEntity<String> resp = preview(token("operator01"), batchId, null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4002", code(resp));
        assertEquals("FAILED", batchRow(batchId).get("status"), "解析真失败时批次必须置 FAILED");
    }

    // =====================================================================
    // POI 夹具（测试内现造，不依赖仓库外样例文件）
    // =====================================================================

    /** PRODUCT 模板：表头 product_code/name + n 行数据。 */
    private byte[] xlsxSingleSheet(String sheetName, int dataRows) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            writeProductSheet(wb.createSheet(sheetName), dataRows, "P-EXCEL");
            return toBytes(wb);
        }
    }

    /** 双表：FirstSheet（1 行）+ SecondSheet（2 行）。 */
    private byte[] xlsxTwoSheets() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            writeProductSheet(wb.createSheet("FirstSheet"), 1, "P-FIRST");
            writeProductSheet(wb.createSheet("SecondSheet"), 2, "P-SECOND");
            return toBytes(wb);
        }
    }

    /** 首页隐藏（HiddenSheet，3 行）+ 次页可见（VisibleSheet，2 行）。 */
    private byte[] xlsxHiddenFirstSheet() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            writeProductSheet(wb.createSheet("HiddenSheet"), 3, "P-HIDDEN");
            writeProductSheet(wb.createSheet("VisibleSheet"), 2, "P-VISIBLE");
            wb.setSheetVisibility(0, SheetVisibility.HIDDEN);
            return toBytes(wb);
        }
    }

    /** 仅含 product_code 一列（缺必填 name）。 */
    private byte[] xlsxOnlyProductCode() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("product_code");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("P-ONLY-1");
            return toBytes(wb);
        }
    }

    private byte[] xlsSingleSheet(String sheetName, int dataRows) throws IOException {
        try (Workbook wb = new HSSFWorkbook()) {
            writeProductSheet(wb.createSheet(sheetName), dataRows, "P-XLS");
            return toBytes(wb);
        }
    }

    private static void writeProductSheet(Sheet sheet, int dataRows, String codePrefix) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("product_code");
        header.createCell(1).setCellValue("name");
        for (int i = 0; i < dataRows; i++) {
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(codePrefix + "-" + (i + 1));
            row.createCell(1).setCellValue("Excel商品" + (i + 1));
        }
    }

    private static byte[] toBytes(Workbook wb) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    // =====================================================================
    // HTTP / 数据辅助
    // =====================================================================

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

    private ResponseEntity<String> mapping(String bearer, long batchId, Map<String, String> fieldMapping,
                                           Map<?, ?> options) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        Map<String, Object> body = Map.of("field_mapping_json", fieldMapping, "parse_options_json", options);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/mapping", HttpMethod.PUT,
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

    private Map<String, Object> batchRow(long id) {
        return jdbc.queryForMap("SELECT * FROM import_batches WHERE id = ?", id);
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
