package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>M3-B12（R87）：可重导状态集口径</b>——真栈集成测试（Spring 上下文 + Testcontainers MySQL 8.0.36
 * + 真实 HTTP + 真实文件落盘）。
 *
 * <h2>缺陷（R87）</h2>
 * <p>{@code REIMPORTABLE_STATUSES} 旧实现含 {@code DUPLICATE_REJECTED}，但该状态批次的
 * {@code dedupe_hash} <b>恒为 {@code NULL}</b>（{@code §6-E.4}：置空才能绕过唯一键）⇒
 * 三步重导的第 ② 步「释放旧槽位」释放的是它自己（空转），第 ③ 步占 {@code file_hash} 槽位时
 * <b>原成功批次仍占着该槽位</b> ⇒ {@code DuplicateKeyException} ⇒
 * {@code 409 IMP-4004}「该文件已有占用中的导入批次，请刷新后查看」——<b>刷新无效</b>，用户无出路。</p>
 *
 * <h2>裁定（{@code DESIGN_REVIEW R87/R88}，2026-10-18）</h2>
 * <ul>
 *   <li>可重导集 = {@code COMPLETED} / {@code PARTIAL_SUCCESS} / {@code FAILED} / {@code CANCELLED}
 *       四个终态；</li>
 *   <li>{@code DUPLICATE_REJECTED} <b>不是</b>重导入口——{@code DESIGN_BASIS §9.5:389} 明写
 *       「用户在<b>既有批次</b>上调用 {@code reimport}」，即 {@code 409} 回带的
 *       {@code existing_batch_id} 那一批；对 D 自身重导 → {@code 422 IMP-4005}。</li>
 * </ul>
 *
 * <p>★ 本类只钉两件事：① D 自身重导被拒且提示语义准确（列出四个合法状态、不出现 D 这个非法状态名）；
 * ② {@code COMPLETED} 批重导<b>不受影响</b>（防「修 R87 时误伤已证行为」）。</p>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportReimportStatusSetTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 落盘目录指向临时目录：绝不在仓库工作树里留下上传物。 */
    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-reimport-status-set-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;
    private static final long OWNER_ID = 1004L;

    private static final String OPERATOR = "operator01";
    private static final String BIZ_TYPE = "SKU";

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

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) "
                + "VALUES (?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);
        insertUser(OWNER_ID, "owner01");
        insertUser(OPERATOR_ID, OPERATOR);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (9001, ?, 'P-BAG-001', '箱包', 'ACTIVE')", TEAM_ID);
    }

    // =====================================================================
    // ① D 自身重导 → 422 IMP-4005（不是 409，也不产生任何副作用）
    // =====================================================================

    @Test
    @DisplayName("B12-R87 D(DUPLICATE_REJECTED) 自身重导 → 422 IMP-4005；details.status 只列四个合法状态；无新批次/无审计/原批槽位未动")
    void duplicateRejectedBatchReimportRejected() throws Exception {
        String op = token(OPERATOR);
        byte[] csv = csv("sku_code,product_code\nSKU-B12-D,P-BAG-001\n");
        String hash = sha256(csv);

        long a = completedBatch(op, "b12-dup.csv", csv);

        // ---- 同文件再传 → 409 IMP-4004，并落一条 DUPLICATE_REJECTED（槽位 NULL）----
        ResponseEntity<String> duplicate = upload(op, "b12-dup.csv", csv, BIZ_TYPE);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode(), duplicate.getBody());
        JsonNode duplicateBody = objectMapper.readTree(duplicate.getBody());
        assertEquals("IMP-4004", duplicateBody.get("code").asText());
        assertEquals(a, duplicateBody.get("data").get("existing_batch_id").asLong(),
                "409 必须回带原成功批次 id（这正是 D 的【正确】重导入口）");

        Map<String, Object> dRow = jdbc.queryForMap("SELECT * FROM import_batches "
                + "WHERE team_id = ? AND status = 'DUPLICATE_REJECTED' ORDER BY id DESC LIMIT 1", TEAM_ID);
        long d = ((Number) dRow.get("id")).longValue();
        assertEquals("DUPLICATE_REJECTED", dRow.get("status"));
        assertNull(dRow.get("dedupe_hash"), "D 的槽位恒为 NULL（这是它无法自身重导的物理原因）");
        assertEquals(a, ((Number) dRow.get("duplicate_of_batch_id")).longValue());
        assertEquals(2, ((Number) dRow.get("attempt_no")).intValue());
        assertEquals(2, countBatches(), "A + D 共 2 条");

        // ---- 对 D 自身重导 → 422 IMP-4005 ----
        ResponseEntity<String> resp = reimport(op, d);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), resp.getBody());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("IMP-4005", body.get("code").asText(), "状态集不含 D ⇒ 必须是 4005，不是 409「请刷新」");

        JsonNode detail = body.get("details").get(0);
        assertEquals("status", detail.get("field").asText(), "details 必须指出是「状态」不合格");
        String message = detail.get("message").asText();
        for (String legal : new String[]{"COMPLETED", "PARTIAL_SUCCESS", "FAILED", "CANCELLED"}) {
            assertTrue(message.contains(legal), "提示语必须列出合法状态 " + legal + "：" + message);
        }
        assertFalse(message.contains("DUPLICATE_REJECTED"),
                "提示语不得把 D 说成可重导状态（否则用户会一直重试）：" + message);
        assertEquals("DUPLICATE_REJECTED", detail.get("rejectedValue").asText(),
                "当前状态仍须机读可见（只是不再写进 message）");

        // ---- 副作用为零：不建新批次、不写 FORCE_REIMPORT 审计、A 的槽位与状态未变 ----
        assertEquals(2, countBatches(), "被拒时不得产生新批次");
        assertEquals(0, countReimportAudits(), "被拒不写 FORCE_REIMPORT 审计（否则是「假成功」留痕）");
        Map<String, Object> aRow = batchRow(a);
        assertEquals(hash, aRow.get("dedupe_hash"), "A 的槽位必须原样保留（D 的重导入口正是它）");
        assertNull(aRow.get("superseded_by_batch_id"), "A 不得被标记为已取代");
        assertEquals("COMPLETED", aRow.get("status"), "A 的状态是历史事实，不得被这次被拒改动");
    }

    // =====================================================================
    // ② COMPLETED 批重导不受影响（防误伤）
    // =====================================================================

    @Test
    @DisplayName("B12-R87b COMPLETED 批重导仍 201：新批 attempt_no=2 且占槽位、原批释放并被标记取代")
    void completedBatchReimportStillWorks() throws Exception {
        String op = token(OPERATOR);
        byte[] csv = csv("sku_code,product_code\nSKU-B12-OK,P-BAG-001\n");
        String hash = sha256(csv);
        long a = completedBatch(op, "b12-ok.csv", csv);

        ResponseEntity<String> resp = reimport(op, a);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(), resp.getBody());
        long created = data(resp).get("batch_id").asLong();

        Map<String, Object> createdRow = batchRow(created);
        assertEquals("UPLOADED", createdRow.get("status"));
        assertEquals(2, ((Number) createdRow.get("attempt_no")).intValue(), "attempt_no = 原 + 1");
        assertEquals(hash, createdRow.get("dedupe_hash"), "新批占用槽位");
        assertNull(createdRow.get("duplicate_of_batch_id"), "主动重导不是「被拒」");
        assertNotNull(createdRow.get("storage_path"));

        Map<String, Object> origin = batchRow(a);
        assertNull(origin.get("dedupe_hash"), "原批槽位必须释放");
        assertEquals(created, ((Number) origin.get("superseded_by_batch_id")).longValue(),
                "原批必须指向新批（重导链条可追溯）");
        assertEquals("COMPLETED", origin.get("status"), "原批状态不改（历史事实）");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM import_batches "
                + "WHERE team_id = ? AND dedupe_hash = ? AND biz_type = ?",
                Integer.class, TEAM_ID, hash, BIZ_TYPE), "同 hash 只能有一行占用槽位");
        assertEquals(1, countReimportAudits(), "成功重导恰好 1 条 FORCE_REIMPORT 审计");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** 上传 → 预览 → 映射（服务端建议）→ 校验 → 确认，断言终态 {@code COMPLETED}，返回批次 id。 */
    private long completedBatch(String bearer, String fileName, byte[] content) throws Exception {
        long batchId = uploadOk(bearer, fileName, content);
        JsonNode suggested = data(preview(bearer, batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        assertEquals(HttpStatus.OK, putMapping(bearer, batchId, map).getStatusCode(), "映射应成功");

        ResponseEntity<String> validated = validate(bearer, batchId);
        assertEquals(HttpStatus.OK, validated.getStatusCode(), validated.getBody());
        assertEquals("VALIDATED", data(validated).get("status").asText(), validated.getBody());

        ResponseEntity<String> confirmed = confirm(bearer, batchId, "key-b12-" + batchId);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        assertEquals("COMPLETED", data(confirmed).get("status").asText(), confirmed.getBody());
        return batchId;
    }

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

    private long uploadOk(String bearer, String fileName, byte[] content) throws Exception {
        ResponseEntity<String> response = upload(bearer, fileName, content, BIZ_TYPE);
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

    private ResponseEntity<String> confirm(String bearer, long batchId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private Map<String, Object> batchRow(long id) {
        return jdbc.queryForMap("SELECT * FROM import_batches WHERE id = ?", id);
    }

    private int countBatches() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM import_batches WHERE team_id = ?",
                Integer.class, TEAM_ID);
    }

    private int countReimportAudits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE action = 'IMPORT_BATCH_FORCE_REIMPORT' AND team_id = ?", Integer.class, TEAM_ID);
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
