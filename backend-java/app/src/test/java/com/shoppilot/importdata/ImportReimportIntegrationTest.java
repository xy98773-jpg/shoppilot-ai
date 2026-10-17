package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>M3-B10.7（R84）：{@code POST /api/v1/import/batches/{id}/reimport}（强制重导）</b>端到端集成测试
 * ——真栈（Spring 上下文 + Testcontainers MySQL 8.0.36 + 真实 HTTP + 真实文件落盘）。
 *
 * <p><b>缺陷背景</b>：{@code API_CONTRACT:455} 明写「允许强制重导」、{@code ImportDuplicateView} 的
 * javadoc 引用该端点、{@code AuditAction.IMPORT_BATCH_FORCE_REIMPORT} 早已建好，但
 * {@code ImportBatchController} <b>没有</b>该映射。后果：同一份文件导入成功一次后
 * <b>永远无法重导</b>（改名也绕不过 {@code file_hash}，{@code IMPORT_WORKFLOW §8.9} 要点 3），
 * 导错数据只能等运维改库。</p>
 *
 * <h2>本类覆盖（任务书【2】e–h + 两条边界）</h2>
 * <ol>
 *   <li><b>e</b> 正常导入到 {@code COMPLETED} → reimport → {@code 201}、新批 {@code UPLOADED}、
 *       原批 {@code superseded_by_batch_id} 回填且 {@code dedupe_hash = NULL}、审计恰好 1 条；</li>
 *   <li><b>e2</b> 副本字段逐项对齐（{@code storage_path}/{@code file_hash}/{@code file_size}/
 *       {@code file_name}/{@code biz_type}/{@code template_version}）+ {@code attempt_no = 原 + 1}；</li>
 *   <li><b>f</b> 未终结批次（{@code UPLOADED} / {@code VALIDATED}）→ {@code 422 IMP-4005}；</li>
 *   <li><b>g</b> 已被取代的原批再 reimport → {@code 422 IMP-4005}（禁止链条分叉）；</li>
 *   <li><b>g2</b> 不存在 / 跨团队 → {@code 404 IMP-4007}（<b>不是</b> {@code IMP-4004}）；</li>
 *   <li><b>h</b> 槽位语义：reimport 后同文件再 upload → {@code 409 IMP-4004} 且
 *       {@code existing_batch_id} 指向<b>新批</b>（旧槽位确已释放、新槽位确已占用）；</li>
 *   <li><b>h2</b> 新批进 {@code FAILED}（释放槽位）后同文件 upload → {@code 201}（唯一键语义未变）；</li>
 *   <li><b>i</b> 原文件丢失 → {@code 410 IMP-4011}；</li>
 *   <li><b>j</b> {@code VIEWER} → {@code 403 TEAM-2003}（权限与 upload 一致）。</li>
 * </ol>
 *
 * <p>★ 用 {@code SKU} 主数据导入做「成功批次」的载体：它的落库路径最短（只碰 {@code products}/
 * {@code skus}），不会给库存/销售表留下与本批无关的副作用。种子与清理见 {@link #resetAndSeed()}。</p>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportReimportIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 落盘目录指向临时目录：绝不在仓库工作树里留下上传物。 */
    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-reimport-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;
    private static final long OWNER_ID = 1004L;
    private static final long OTHER_OWNER_ID = 2004L;

    private static final String BIZ_TYPE = "SKU";

    /** 最小可导入的 SKU 文件（前缀 + 序号，避免与种子里的 SKU 编码撞车）。 */
    private static final byte[] SKU_CSV = ("sku_code,product_code\n"
            + "SKU-REIMP-001,P-BAG-001\n").getBytes(StandardCharsets.UTF_8);

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

        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (9001, ?, 'P-BAG-001', 'Bag', 'ACTIVE')", TEAM_ID);
    }

    // =====================================================================
    // e / e2：正常导入成功后重导 → 201，副本字段与槽位三步全部到位
    // =====================================================================

    @Test
    @DisplayName("R84-e 导入成功 → reimport → 201 UPLOADED、原批被取代且槽位释放、审计 1 条、副本字段逐项对齐")
    void reimportSuccessfulBatchTransfersSlotAndAudits() throws Exception {
        String operator = token("operator01");
        long originId = completedBatch(operator, "sku-reimport.csv", SKU_CSV);
        String hash = sha256(SKU_CSV);
        Map<String, Object> origin = batchRow(originId);
        assertEquals("COMPLETED", origin.get("status"));

        ResponseEntity<String> resp = reimport(operator, originId);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        long newId = data.get("batch_id").asLong();

        // ---- 响应结构与 upload 的 201 同一视图（同一 DTO、同一字段名） ----
        for (String field : List.of("batch_id", "batch_no", "biz_type", "file_name", "file_hash",
                "file_size", "status", "created_at")) {
            assertNotNull(data.get(field), "201 响应缺字段：" + field);
        }
        assertEquals("UPLOADED", data.get("status").asText());
        assertEquals(hash, data.get("file_hash").asText());
        assertEquals(SKU_CSV.length, data.get("file_size").asLong());
        assertEquals("sku-reimport.csv", data.get("file_name").asText());
        assertEquals(BIZ_TYPE, data.get("biz_type").asText());

        // ---- 新批次行 ----
        Map<String, Object> created = batchRow(newId);
        assertEquals(TEAM_ID, ((Number) created.get("team_id")).longValue());
        assertEquals("UPLOADED", created.get("status"));
        assertEquals(2, ((Number) created.get("attempt_no")).intValue(), "attempt_no = 原 + 1");
        assertEquals(hash, created.get("dedupe_hash"), "⑤ 新批次占用槽位（dedupe_hash = file_hash）");
        assertNull(created.get("duplicate_of_batch_id"), "主动重导不是「被拒」，duplicate_of_batch_id 必须为 NULL");
        assertNull(created.get("superseded_by_batch_id"), "新批次本身未被取代");
        assertEquals(0, ((Number) created.get("total_rows")).intValue(), "计数列归零，不复制原批");
        assertEquals(origin.get("storage_path"), created.get("storage_path"), "沿用同一份原始文件");
        assertEquals(origin.get("file_hash"), created.get("file_hash"));
        assertEquals(origin.get("file_size"), created.get("file_size"));
        assertEquals(origin.get("file_name"), created.get("file_name"));
        assertEquals(origin.get("biz_type"), created.get("biz_type"));
        assertEquals(origin.get("template_version"), created.get("template_version"));
        assertTrue(Files.isRegularFile(Path.of(String.valueOf(created.get("storage_path")))),
                "重导不搬移文件：storage_path 指向的原文件仍须存在");

        // ---- 原批次行：被取代 + 槽位释放（顺序正确的直接证据） ----
        Map<String, Object> old = batchRow(originId);
        assertEquals(newId, ((Number) old.get("superseded_by_batch_id")).longValue(), "原批 superseded_by_batch_id 回填");
        assertNull(old.get("dedupe_hash"), "原批槽位必须释放（否则撞 uk_imp_team_hash_biz）");
        assertEquals("COMPLETED", old.get("status"), "重导不改原批的状态（它是历史事实）");

        // ---- 槽位唯一性：同 (team, hash, biz) 有且仅有 1 行占用，且是新批 ----
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM import_batches "
                        + "WHERE team_id = ? AND dedupe_hash = ? AND biz_type = ?",
                Integer.class, TEAM_ID, hash, BIZ_TYPE), "槽位只能有一行占用");
        assertEquals(newId, jdbc.queryForObject("SELECT id FROM import_batches "
                        + "WHERE team_id = ? AND dedupe_hash = ? AND biz_type = ?",
                Long.class, TEAM_ID, hash, BIZ_TYPE));

        // ---- 审计：恰好 1 条，biz_id = 新批，原批 id 有留痕 ----
        List<Map<String, Object>> audits = jdbc.queryForList("SELECT biz_id, biz_type, biz_no, result, "
                + "change_summary, after_json FROM audit_logs WHERE action = 'IMPORT_BATCH_FORCE_REIMPORT'");
        assertEquals(1, audits.size(), "审计必须恰好 1 条（FORCE_REIMPORT 已建枚举，首次使用）");
        Map<String, Object> audit = audits.get(0);
        assertEquals(newId, ((Number) audit.get("biz_id")).longValue(), "biz_id = 新批次 id");
        assertEquals("IMPORT_BATCH", audit.get("biz_type"));
        assertEquals("SUCCESS", audit.get("result"));
        assertTrue(String.valueOf(audit.get("change_summary")).contains("原批次 id=" + originId),
                "change_summary 须记原批次 id：" + audit.get("change_summary"));
        assertTrue(String.valueOf(audit.get("after_json")).contains("\"original_batch_id\": " + originId),
                "after_json 须记原批次 id（机读）：" + audit.get("after_json"));

        // ---- 新批次可继续走流程（它是真的 UPLOADED，不是死记录） ----
        assertEquals(HttpStatus.OK, preview(operator, newId).getStatusCode(), "新批次必须能继续预览");
    }

    // =====================================================================
    // f：未终结批次 → 422 IMP-4005
    // =====================================================================

    @Test
    @DisplayName("R84-f 未终结批次（UPLOADED / VALIDATED）reimport → 422 IMP-4005")
    void reimportNonTerminalBatchIsRejected() throws Exception {
        String operator = token("operator01");

        long uploaded = uploadOk(operator, "sku-f1.csv", SKU_CSV, BIZ_TYPE);
        ResponseEntity<String> onUploaded = reimport(operator, uploaded);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, onUploaded.getStatusCode(), onUploaded.getBody());
        assertEquals("IMP-4005", objectMapper.readTree(onUploaded.getBody()).get("code").asText());
        assertEquals("UPLOADED", batchRow(uploaded).get("status"), "被拒时不得改动原批状态");
        assertNull(batchRow(uploaded).get("superseded_by_batch_id"));

        long validated = validatedBatch(operator, "sku-f2.csv",
                ("sku_code,product_code\nSKU-REIMP-002,P-BAG-001\n").getBytes(StandardCharsets.UTF_8));
        ResponseEntity<String> onValidated = reimport(operator, validated);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, onValidated.getStatusCode(), onValidated.getBody());
        assertEquals("IMP-4005", objectMapper.readTree(onValidated.getBody()).get("code").asText());

        assertEquals(2, countBatches(), "两次被拒都不得建新批次");
        assertEquals(0, countAudits(), "被拒不写 FORCE_REIMPORT 审计");
    }

    // =====================================================================
    // g：已被取代的原批再 reimport → 422 IMP-4005（禁止链条分叉）
    // =====================================================================

    @Test
    @DisplayName("R84-g 已被取代的原批再次 reimport → 422 IMP-4005（不产生第二个新批次、审计仍 1 条）")
    void reimportSupersededBatchIsRejected() throws Exception {
        String operator = token("operator01");
        long originId = completedBatch(operator, "sku-g.csv", SKU_CSV);
        assertEquals(HttpStatus.CREATED, reimport(operator, originId).getStatusCode());

        ResponseEntity<String> again = reimport(operator, originId);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, again.getStatusCode(), again.getBody());
        JsonNode body = objectMapper.readTree(again.getBody());
        assertEquals("IMP-4005", body.get("code").asText());
        assertEquals("superseded_by_batch_id", body.get("details").get(0).get("field").asText(),
                "details 须指出是「已被取代」而不是「状态不对」");

        assertEquals(2, countBatches(), "原批 + 第一次重导的新批，共 2 条（不得分叉）");
        assertEquals(1, countAudits(), "审计仍恰好 1 条");
    }

    // =====================================================================
    // g2：不存在 / 跨团队 → 404 IMP-4007（不是 IMP-4004）
    // =====================================================================

    @Test
    @DisplayName("R84-g2 不存在或跨团队批次 reimport → 404 IMP-4007（不得用 IMP-4004）")
    void reimportUnknownOrCrossTeamBatchReturns404() throws Exception {
        String operator = token("operator01");

        ResponseEntity<String> missing = reimport(operator, 9_999_999L);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode(), missing.getBody());
        assertEquals("IMP-4007", objectMapper.readTree(missing.getBody()).get("code").asText());

        long otherTeamBatch = uploadOk(token("owner8"), "sku-other-team.csv", SKU_CSV, BIZ_TYPE);
        ResponseEntity<String> crossTeam = reimport(operator, otherTeamBatch);
        assertEquals(HttpStatus.NOT_FOUND, crossTeam.getStatusCode(), crossTeam.getBody());
        assertEquals("IMP-4007", objectMapper.readTree(crossTeam.getBody()).get("code").asText());
        assertNull(batchRow(otherTeamBatch).get("superseded_by_batch_id"), "跨团队被拒时不得改动别队批次");
        assertEquals(OTHER_TEAM_ID, ((Number) batchRow(otherTeamBatch).get("team_id")).longValue());
    }

    // =====================================================================
    // h / h2：槽位真的释放了，且新槽位真的生效了
    // =====================================================================

    @Test
    @DisplayName("R84-h reimport 后同文件 upload → 409 且指向新批；新批释放槽位后 upload → 201")
    void reimportReleasesOriginSlotAndOccupiesNewSlot() throws Exception {
        String operator = token("operator01");
        long originId = completedBatch(operator, "sku-h.csv", SKU_CSV);
        long newId = data(reimport(operator, originId)).get("batch_id").asLong();

        // ① 旧槽位已释放、新槽位已占用：upload 命中的必须是「新批」，不是原批
        ResponseEntity<String> uploadAgain = upload(operator, "sku-h.csv", SKU_CSV, BIZ_TYPE);
        assertEquals(HttpStatus.CONFLICT, uploadAgain.getStatusCode(), uploadAgain.getBody());
        JsonNode body = objectMapper.readTree(uploadAgain.getBody());
        assertEquals("IMP-4004", body.get("code").asText());
        assertEquals(newId, body.get("data").get("existing_batch_id").asLong(),
                "命中的占用者必须是重导出来的新批次（证明原批槽位确已释放）");

        // ② 新批重导后（模拟改为 FAILED，释放槽位）同文件 upload → 201（唯一键语义未变）
        jdbc.update("UPDATE import_batches SET status = 'FAILED', dedupe_hash = NULL WHERE id = ?", newId);
        ResponseEntity<String> third = upload(operator, "sku-h.csv", SKU_CSV, BIZ_TYPE);
        assertEquals(HttpStatus.CREATED, third.getStatusCode(), third.getBody());
        Map<String, Object> thirdRow = batchRow(data(third).get("batch_id").asLong());
        assertEquals("UPLOADED", thirdRow.get("status"));
        assertEquals(sha256(SKU_CSV), thirdRow.get("dedupe_hash"));
    }

    // =====================================================================
    // i：原文件丢失 → 410 IMP-4011
    // =====================================================================

    @Test
    @DisplayName("R84-i 原文件已丢失 → 410 IMP-4011，不建新批次")
    void reimportWithMissingOriginalFileReturns410() throws Exception {
        String operator = token("operator01");
        long originId = completedBatch(operator, "sku-i.csv", SKU_CSV);
        Path stored = Path.of(String.valueOf(batchRow(originId).get("storage_path")));
        Files.delete(stored);

        ResponseEntity<String> resp = reimport(operator, originId);
        assertEquals(HttpStatus.GONE, resp.getStatusCode(), resp.getBody());
        assertEquals("IMP-4011", objectMapper.readTree(resp.getBody()).get("code").asText());
        assertEquals(1, countBatches(), "文件丢失时不建新批次");
        assertNull(batchRow(originId).get("superseded_by_batch_id"), "原批不得被标记为已取代");
        assertEquals(0, countAudits(), "失败不写 FORCE_REIMPORT 审计（否则是「假成功」留痕）");
    }

    // =====================================================================
    // j：VIEWER → 403 TEAM-2003（权限与 upload 一致）
    // =====================================================================

    @Test
    @DisplayName("R84-j VIEWER 调用 reimport → 403 TEAM-2003（与 upload 同一角色门槛）")
    void viewerCannotReimport() throws Exception {
        String operator = token("operator01");
        long originId = completedBatch(operator, "sku-j.csv", SKU_CSV);

        ResponseEntity<String> resp = reimport(token("viewer01"), originId);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(), resp.getBody());
        assertEquals("TEAM-2003", objectMapper.readTree(resp.getBody()).get("code").asText());
        assertNull(batchRow(originId).get("superseded_by_batch_id"), "被拒时不得改动原批");
        assertEquals(0, countAudits());
    }

    // =====================================================================
    // 辅助：导入流程
    // =====================================================================

    /** 上传 → 预览 → 映射（服务端建议）→ 校验 → 确认，断言终态 {@code COMPLETED}，返回批次 id。 */
    private long completedBatch(String bearer, String fileName, byte[] content) throws Exception {
        long batchId = uploadOk(bearer, fileName, content, BIZ_TYPE);
        JsonNode suggested = data(preview(bearer, batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        assertEquals(HttpStatus.OK, putMapping(bearer, batchId, map).getStatusCode(), "映射应成功");

        ResponseEntity<String> validated = validate(bearer, batchId);
        assertEquals(HttpStatus.OK, validated.getStatusCode(), validated.getBody());
        assertEquals("VALIDATED", data(validated).get("status").asText(), validated.getBody());

        ResponseEntity<String> confirmed = confirm(bearer, batchId, "key-" + fileName, Map.of());
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        assertEquals("COMPLETED", data(confirmed).get("status").asText(), confirmed.getBody());
        return batchId;
    }

    /** 只走到 {@code VALIDATED}（不 confirm），用于「未终结批次」负向用例。 */
    private long validatedBatch(String bearer, String fileName, byte[] content) throws Exception {
        long batchId = uploadOk(bearer, fileName, content, BIZ_TYPE);
        JsonNode suggested = data(preview(bearer, batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        assertEquals(HttpStatus.OK, putMapping(bearer, batchId, map).getStatusCode());
        ResponseEntity<String> validated = validate(bearer, batchId);
        assertEquals(HttpStatus.OK, validated.getStatusCode(), validated.getBody());
        assertEquals("VALIDATED", data(validated).get("status").asText(), validated.getBody());
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

    private int countBatches() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM import_batches", Integer.class);
    }

    private int countAudits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE action = 'IMPORT_BATCH_FORCE_REIMPORT'", Integer.class);
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
