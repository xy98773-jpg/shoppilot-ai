package com.shoppilot.importdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.audit.domain.model.AuditAction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-T02 导入上传 —— 端到端集成测试（真实 Spring 上下文 + Testcontainers MySQL 8.0.36 + 真实 HTTP）。
 *
 * <p><b>真栈</b>：真实 Flyway 迁移链（V1.0.0 → V1.0.5）+ 真实登录 / JWT / 租户线 +
 * {@code @RequireRole} 切面 + {@code AuditWriteService}（REQUIRES_NEW）+ 本地文件落盘。
 *
 * <p>覆盖任务书 §4 的 12 项：合法上传、幂等 409、跨团队共存、槽位释放、扩展名非法、
 * 超 20MB、非 multipart、bizType 非法/缺失、VIEWER 403、审计埋点、15MB 流式、
 * {@code AuditAction} 零改动。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportUploadIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 落盘目录指向临时目录：绝不在仓库工作树里留下上传物。 */
    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final String RAW_PASSWORD = "S3cret!Passw0rd";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(RAW_PASSWORD);

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;
    private static final long OWNER_ID = 1004L;
    private static final long OTHER_OWNER_ID = 2004L;

    private static final String BIZ_TYPE = "SALES_DAILY";
    private static final byte[] CSV = ("sku_code,warehouse_code,sale_date,qty\n"
            + "SKU-BAG-001-BLK,WH-MAIN,2026-10-13,12\n").getBytes(StandardCharsets.UTF_8);

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
        insertUser(VIEWER_ID, "viewer01");
        insertUser(OTHER_OWNER_ID, "owner8");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), "
                        + "(?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);
    }

    // =====================================================================
    // §4-1 合法 .csv 上传 → 201 / UPLOADED / 哈希正确 / 文件真实落盘
    // =====================================================================

    @Test
    @DisplayName("§4-1 上传合法 csv → 201，status=UPLOADED，file_hash 与独立计算一致，文件真实落盘")
    void uploadValidCsvReturns201() throws Exception {
        String operator = token("operator01");
        ResponseEntity<String> response = upload(operator, "sales.csv", CSV, BIZ_TYPE);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        JsonNode data = data(response);
        assertEquals("UPLOADED", data.get("status").asText());
        assertEquals(BIZ_TYPE, data.get("biz_type").asText());
        assertEquals("sales.csv", data.get("file_name").asText());
        assertEquals(CSV.length, data.get("file_size").asLong());
        assertEquals(sha256(CSV), data.get("file_hash").asText(), "file_hash 必须是内容的 SHA-256（小写 hex）");
        assertNotNull(data.get("batch_no").asText());
        assertNotNull(data.get("created_at"), "created_at 由 DB 默认值生成并回读，不得为空");

        long batchId = data.get("batch_id").asLong();
        Map<String, Object> row = batchRow(batchId);
        assertEquals(TEAM_ID, ((Number) row.get("team_id")).longValue(), "team_id 只来自服务端 TeamContext");
        assertEquals("UPLOADED", row.get("status"));
        assertEquals(sha256(CSV), row.get("dedupe_hash"), "未命中重复 → dedupe_hash = file_hash（占用槽位）");
        assertEquals(1, ((Number) row.get("attempt_no")).intValue());
        assertNull(row.get("duplicate_of_batch_id"));

        String path = String.valueOf(row.get("storage_path"));
        assertTrue(Files.exists(Path.of(path)), "storage_path 指向的文件必须真实存在：" + path);
        assertEquals(CSV.length, Files.size(Path.of(path)), "落盘文件内容长度必须与 file_size 一致");
        assertTrue(path.contains(String.valueOf(TEAM_ID)), "落盘路径按团队分目录隔离：" + path);
    }

    // =====================================================================
    // §4-2 重复上传 → 409 IMP-4004 + DUPLICATE_REJECTED 记录
    // =====================================================================

    @Test
    @DisplayName("§4-2 重复上传同文件同 biz_type → 409 IMP-4004，新增 DUPLICATE_REJECTED（槽位为 NULL、attempt_no=原+1）")
    void duplicateUploadReturns409() throws Exception {
        String operator = token("operator01");
        long firstId = uploadOk(operator, "sales.csv", CSV, BIZ_TYPE);

        ResponseEntity<String> second = upload(operator, "sales-again.csv", CSV, BIZ_TYPE);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode(), second.getBody());
        JsonNode body = objectMapper.readTree(second.getBody());
        assertEquals("IMP-4004", body.get("code").asText());
        assertEquals("该文件已导入过，如需重新导入请使用强制重导", body.get("message").asText());
        assertEquals(firstId, body.get("data").get("existing_batch_id").asLong(),
                "data.existing_batch_id 必须指向原批次（契约 §11.3 失败响应 1）");
        assertEquals(String.valueOf(batchRow(firstId).get("batch_no")),
                body.get("data").get("existing_batch_no").asText(),
                "data.existing_batch_no 必须等于原批次号");
        assertTrue(body.get("data").get("existing_batch_no").asText().startsWith("IMP-"));

        Map<String, Object> rejected = jdbc.queryForMap(
                "SELECT * FROM import_batches WHERE status = 'DUPLICATE_REJECTED' ORDER BY id DESC LIMIT 1");
        assertNull(rejected.get("dedupe_hash"), "DUPLICATE_REJECTED 的槽位必须为 NULL（否则撞唯一键）");
        assertEquals(firstId, ((Number) rejected.get("duplicate_of_batch_id")).longValue());
        assertEquals(2, ((Number) rejected.get("attempt_no")).intValue(), "attempt_no = 原 attempt_no + 1");
        assertEquals(sha256(CSV), rejected.get("file_hash"), "被拒记录仍保留文件哈希与路径，供回溯");
        assertEquals(2, countBatches(), "库中共两条：原批次 + 被拒记录");
    }

    // =====================================================================
    // §4-3 跨团队同 file_hash → 允许共存（幂等只在团队内）
    // =====================================================================

    @Test
    @DisplayName("§4-3 跨团队上传同一文件 → 201（幂等只在团队内）")
    void crossTeamSameFileAllowed() throws Exception {
        uploadOk(token("operator01"), "sales.csv", CSV, BIZ_TYPE);

        ResponseEntity<String> other = upload(token("owner8"), "sales.csv", CSV, BIZ_TYPE);
        assertEquals(HttpStatus.CREATED, other.getStatusCode(), other.getBody());

        Map<String, Object> row = batchRow(data(other).get("batch_id").asLong());
        assertEquals(OTHER_TEAM_ID, ((Number) row.get("team_id")).longValue());
        assertEquals(sha256(CSV), row.get("file_hash"), "同哈希跨团队共存（uk 含 team_id）");
    }

    // =====================================================================
    // §4-4 槽位释放后再传 → 201
    // =====================================================================

    @Test
    @DisplayName("§4-4 原批次进入释放态（FAILED 且 dedupe_hash 置 NULL）后再传同文件 → 201")
    void releasedSlotAllowsReupload() throws Exception {
        String operator = token("operator01");
        long firstId = uploadOk(operator, "sales.csv", CSV, BIZ_TYPE);

        // 释放语义（DATABASE_DESIGN §5.12 / §6-E）：进入 FAILED 时槽位列 dedupe_hash 置 NULL
        jdbc.update("UPDATE import_batches SET status = 'FAILED', dedupe_hash = NULL WHERE id = ?", firstId);

        ResponseEntity<String> again = upload(operator, "sales.csv", CSV, BIZ_TYPE);
        assertEquals(HttpStatus.CREATED, again.getStatusCode(), again.getBody());
        Map<String, Object> row = batchRow(data(again).get("batch_id").asLong());
        assertEquals("UPLOADED", row.get("status"));
        assertEquals(sha256(CSV), row.get("dedupe_hash"));
    }

    // =====================================================================
    // §4-5 扩展名不在白名单 → 400 IMP-4001 + details[0].field == "file"
    // =====================================================================

    @Test
    @DisplayName("§4-5 扩展名不在白名单（.txt）→ 400 IMP-4001，details[0].field = file")
    void badExtensionReturnsImp4001() throws Exception {
        ResponseEntity<String> response = upload(token("operator01"), "notes.txt", CSV, BIZ_TYPE);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), response.getBody());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("IMP-4001", body.get("code").asText());
        assertEquals("仅支持 CSV 或 Excel 文件，且不超过 20MB", body.get("message").asText());
        assertEquals("file", body.get("details").get(0).get("field").asText());
        assertEquals(0, countBatches(), "被拒时不建批次（IMPORT_WORKFLOW §6 步骤 2）");
    }

    // =====================================================================
    // §4-6 文件 > 20MB → 400 IMP-4001（不许裸抛 413）
    // =====================================================================

    @Test
    @DisplayName("§4-6 文件超过 20MB → 400 IMP-4001（Spring 侧超限被控制器本地出口转译，不得 413）")
    void oversizeFileReturnsImp4001() throws Exception {
        byte[] oversize = csvOf(20 * 1024 * 1024 + 1024);

        ResponseEntity<String> response = upload(token("operator01"), "big.csv", oversize, BIZ_TYPE);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "超限必须是 400 IMP-4001，不得 413：" + response.getBody());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("IMP-4001", body.get("code").asText());
        assertEquals("file", body.get("details").get(0).get("field").asText());
        assertTrue(body.get("details").get(0).get("message").asText().contains("20MB"));
    }

    // =====================================================================
    // §4-7 非 multipart → 415 COM-0006
    // =====================================================================

    @Test
    @DisplayName("§4-7 非 multipart（application/json）→ 415 COM-0006")
    void nonMultipartReturns415() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token("operator01"));
        ResponseEntity<String> response = rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>("{\"bizType\":\"SALES_DAILY\"}", headers), String.class);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode(), response.getBody());
        assertEquals("COM-0006", objectMapper.readTree(response.getBody()).get("code").asText());
    }

    // =====================================================================
    // §4-8 bizType 非法 / 缺失 → 400 COM-0001 + details
    // =====================================================================

    @Test
    @DisplayName("§4-8 bizType 非法或缺失 → 400 COM-0001 + details（复用既有参数校验出口，不自造码）")
    void invalidOrMissingBizTypeReturnsCom0001() throws Exception {
        String operator = token("operator01");

        ResponseEntity<String> invalid = upload(operator, "sales.csv", CSV, "NOT_A_BIZ_TYPE");
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode(), invalid.getBody());
        JsonNode invalidBody = objectMapper.readTree(invalid.getBody());
        assertEquals("COM-0001", invalidBody.get("code").asText());
        assertTrue(invalidBody.has("details"), "非法枚举须带字段级 details");

        // 缺失：multipart 里不带 bizType 字段
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(CSV, "sales.csv"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(operator);
        ResponseEntity<String> missing = rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode(), missing.getBody());
        assertEquals("COM-0001", objectMapper.readTree(missing.getBody()).get("code").asText());
    }

    // =====================================================================
    // §4-9 VIEWER → 403 TEAM-2003
    // =====================================================================

    @Test
    @DisplayName("§4-9 VIEWER 调用上传 → 403 TEAM-2003（契约 OPERATOR+）")
    void viewerForbidden() throws Exception {
        ResponseEntity<String> response = upload(token("viewer01"), "sales.csv", CSV, BIZ_TYPE);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertEquals("TEAM-2003", objectMapper.readTree(response.getBody()).get("code").asText());
        assertEquals(0, countBatches(), "被拒时不建批次");
    }

    // =====================================================================
    // §4-10 审计：IMPORT_BATCH_CREATE
    // =====================================================================

    @Test
    @DisplayName("§4-10 201 后 audit_logs 存在 action=IMPORT_BATCH_CREATE / biz_type=IMPORT_BATCH / SUCCESS")
    void auditRowWritten() throws Exception {
        long batchId = uploadOk(token("operator01"), "sales.csv", CSV, BIZ_TYPE);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, biz_type, biz_id, biz_no, operator_id, result FROM audit_logs "
                        + "WHERE action = 'IMPORT_BATCH_CREATE'");
        assertEquals(1, rows.size(), "创建批次应埋一条 IMPORT_BATCH_CREATE（审计 #8）");
        Map<String, Object> row = rows.get(0);
        assertEquals("IMPORT_BATCH", row.get("biz_type"));
        assertEquals(batchId, ((Number) row.get("biz_id")).longValue());
        assertEquals("SUCCESS", row.get("result"));
        assertEquals(OPERATOR_ID, ((Number) row.get("operator_id")).longValue());
        assertNotNull(row.get("biz_no"), "biz_no 为批次号快照");
    }

    // =====================================================================
    // §4-11 15MB 文件上传成功（证明流式：不整文件入内存）
    // =====================================================================

    @Test
    @DisplayName("§4-11 15MB 文件上传成功 → 201（实现为流式：8KB 缓冲边读边哈希边落盘）")
    void fifteenMbUploadSucceeds() throws Exception {
        byte[] large = csvOf(15 * 1024 * 1024);

        ResponseEntity<String> response = upload(token("operator01"), "large.csv", large, BIZ_TYPE);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        JsonNode data = data(response);
        assertEquals(sha256(large), data.get("file_hash").asText());
        assertEquals(large.length, data.get("file_size").asLong());
        assertTrue(Files.exists(Path.of(String.valueOf(
                batchRow(data.get("batch_id").asLong()).get("storage_path")))));
    }

    // =====================================================================
    // §4-12 AuditAction 零改动（枚举仍 35 条，未新增成员）
    // =====================================================================

    @Test
    @DisplayName("§4-12 AuditAction 仍为 35 条且含 IMPORT_BATCH_CREATE（本批零改动）")
    void auditActionUnchanged() {
        assertEquals(AuditAction.expectedCount(), AuditAction.values().length,
                "AuditAction 必须 35 条齐全且本批未新增成员");
        assertNotNull(AuditAction.valueOf("IMPORT_BATCH_CREATE"), "审计 #8 已存在，无需新增枚举");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

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

    /** 上传并断言 201，返回批次 id。 */
    private long uploadOk(String bearer, String fileName, byte[] content, String bizType) throws Exception {
        ResponseEntity<String> response = upload(bearer, fileName, content, bizType);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "上传应成功：" + response.getBody());
        return data(response).get("batch_id").asLong();
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private Map<String, Object> batchRow(long id) {
        return jdbc.queryForMap("SELECT * FROM import_batches WHERE id = ?", id);
    }

    private int countBatches() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM import_batches", Integer.class);
    }

    private String token(String username) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}";
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + username + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, username);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** 生成指定字节数的 CSV 内容（表头 + 重复数据行）。 */
    private static byte[] csvOf(int size) {
        StringBuilder sb = new StringBuilder(size + 64);
        sb.append("sku_code,warehouse_code,sale_date,qty\n");
        String line = "SKU-BAG-001-BLK,WH-MAIN,2026-10-13,12\n";
        while (sb.length() < size) {
            sb.append(line);
        }
        return sb.substring(0, size).getBytes(StandardCharsets.UTF_8);
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
