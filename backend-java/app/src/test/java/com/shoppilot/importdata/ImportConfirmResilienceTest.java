package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.catalog.application.CatalogWriteService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code B4-PERF} 两遍式落库的<b>异常路径</b>与审计一致性（{@code ADR-004} D2/D5/D6）—— 真栈。
 *
 * <h2>覆盖（任务书 D 组）</h2>
 * <ul>
 *   <li><b>D5</b> 非业务异常（{@code C2}）→ 整批回滚、该批全部行记 {@code ERROR}、
 *       该批审计 <b>0 条</b>（<b>{@code R45}</b> 孤儿审计消除；旧实现会留下「已记成功但数据未落库」）；</li>
 *   <li><b>D6</b> Pass 1 命中行级失败 → <b>自动转 Pass 2 逐行重放</b>：用 spy 断言
 *       「整批入口恰好 1 次 + 逐行入口恰好 N 次」，并验证不重复落库、不重复审计；</li>
 *   <li><b>D9</b> {@code C1b}（{@code R43}）：{@code DuplicateKeyException} 被按<b>行级</b>失败处理，
 *       译为 {@code CAT-3002} → {@code PARTIAL_SUCCESS}（并发同编码场景的确定性复现）。</li>
 * </ul>
 *
 * <h2>为什么用 spy 注入异常（{@code R43} 认可的口径）</h2>
 * {@code C2} 与 {@code C1b} 都依赖「非预期异常 / 唯一键并发冲突」，靠真实并发复现既慢又 flaky。
 * {@code DESIGN_REVIEW R43} 明确接受「在 {@code catalog} 侧注入 {@code DuplicateKeyException} 验证译码」。
 * 本类注入的是<b>异常本身</b>，被测代码路径（分类 {@code isRowLevelFailure}、两遍式切换、
 * {@code CAT-3002} 译码、审计缓冲丢弃）全部是真实实现，未做任何绕过。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportConfirmResilienceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-resilience-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;
    private static final String OPERATOR = "operator01";

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
        registry.add("app.import.storage-dir", () -> STORAGE_DIR);
    }

    @MockitoSpyBean
    private CatalogWriteService catalogWriteService;

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
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                OPERATOR_ID, OPERATOR, OPERATOR + "@example.com", TestCredentials.PASSWORD_HASH, OPERATOR);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) "
                + "VALUES (?, ?, 'OPERATOR', 'ACTIVE')", TEAM_ID, OPERATOR_ID);
    }

    // =====================================================================
    // D5 C2：非业务异常 → 整批回滚 + 全部行 ERROR + 审计 0 条（R45）
    // =====================================================================

    @Test
    @DisplayName("D5 非业务异常 → 整批回滚、5 行全 ERROR、该批审计 0 条（R45 孤儿审计消除）")
    void nonBusinessExceptionRollsBackWithoutOrphanAudits() throws Exception {
        // 前两行的「更新 + 停用」都已成功执行并产生审计事件（入缓冲）；
        // 第 3 行注入非业务异常 → 整批回滚 → 缓冲必须随之丢弃。
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 3) {
                throw new IllegalStateException("模拟非业务异常（连接/驱动层面的不可预期错误）");
            }
            return invocation.callRealMethod();
        }).when(catalogWriteService).changeStatus(any(), any(), any());

        StringBuilder sb = new StringBuilder("product_code,name,status\n");
        for (int i = 1; i <= 5; i++) {
            sb.append(String.format("P-C2-%03d", i)).append(",回滚商品").append(i).append(",INACTIVE\n");
        }
        long batchId = validatedBatch("c2.csv", csv(sb.toString()));

        ResponseEntity<String> resp = confirm("key-d5", batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("FAILED", data.get("status").asText(), resp.getBody());
        assertEquals(0, data.get("success_rows").asInt(), "整批回滚 ⇒ 无成功行");
        assertEquals(5, data.get("failed_rows").asInt(), "完整不变式：整批 5 行全部记失败");

        assertEquals(0, count("products"), "整批回滚不得留下任何商品行");
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors WHERE batch_id = ?",
                Integer.class, batchId), "该批全部行记 ERROR（C2 语义）");
        assertEquals(0, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"),
                "R45：回滚批不得留下孤儿审计（旧实现会留下已记成功但数据不存在的记录）");
    }

    // =====================================================================
    // D6 Pass 1 失败 → Pass 2 重放：不重复数据、不重复审计
    // =====================================================================

    @Test
    @DisplayName("D6 Pass 1 命中行级失败 → 自动转 Pass 2 逐行重放：布局一致、无重复数据/审计")
    void passOneFailureFallsBackToPassTwoWithoutDuplicates() throws Exception {
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status) "
                + "VALUES (9401, ?, 'SUP-ONE', '一次性供应商', 'ACTIVE')", TEAM_ID);
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status) "
                + "VALUES (9402, ?, 'SUP-TMP', '临时供应商', 'ACTIVE')", TEAM_ID);

        // 3 行：第 2 行引用 SUP-TMP（validate 后删除 ⇒ 落库期 CAT-3001）
        byte[] csv = csv("product_code,name,default_supplier_code\n"
                + "P-P2-001,首行,SUP-ONE\n"
                + "P-P2-002,次行,SUP-TMP\n"
                + "P-P2-003,末行,SUP-ONE\n");
        long batchId = validatedBatch("pass2.csv", csv);
        jdbc.update("DELETE FROM suppliers WHERE code = 'SUP-TMP'");

        ResponseEntity<String> resp = confirm("key-d6", batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARTIAL_SUCCESS", data.get("status").asText(), resp.getBody());
        assertEquals(2, data.get("success_rows").asInt());
        assertEquals(1, data.get("failed_rows").asInt());

        // ★ 两遍式的直接证据：整批入口 1 次（Pass 1）+ 逐行入口 3 次（Pass 2 全量重放）
        verify(catalogWriteService, times(1)).upsertProductsByCode(anyList());
        verify(catalogWriteService, times(3)).upsertProductByCode(any(JsonNode.class));

        // 不重复落库：每个编码恰好 1 行
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE product_code = 'P-P2-001'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE product_code = 'P-P2-003'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE product_code = 'P-P2-002'", Integer.class));
        assertEquals(2, count("products"));

        // 不重复审计：Pass 1 的缓冲随回滚丢弃，Pass 2 只收 2 条
        assertEquals(2, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"),
                "Pass 1 回滚后缓冲必须丢弃，Pass 2 重新收集 ⇒ 不得出现 5 条或 3 条");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors WHERE batch_id = ? "
                + "AND error_code = 'CAT-3001'", Integer.class, batchId));
    }

    // =====================================================================
    // D9 C1b：DuplicateKeyException → 行级失败 → CAT-3002 → PARTIAL_SUCCESS
    // =====================================================================

    @Test
    @DisplayName("D9 C1b（R43）：DuplicateKeyException 按行级失败译码 CAT-3002 → PARTIAL_SUCCESS")
    void duplicateKeyIsTreatedAsRowLevelFailure() throws Exception {
        // 模拟「并发两批次插入同编码」：Pass 1 整体撞唯一键，Pass 2 中 P-RACE-A 仍然撞、P-RACE-B 正常
        doThrow(new DuplicateKeyException("uk_prod_team_code")).when(catalogWriteService)
                .upsertProductsByCode(anyList());
        doAnswer(invocation -> {
            JsonNode body = invocation.getArgument(0);
            if ("P-RACE-A".equals(body.path("product_code").asText())) {
                throw new DuplicateKeyException("Duplicate entry 'P-RACE-A' for key 'uk_prod_team_code'");
            }
            return invocation.callRealMethod();
        }).when(catalogWriteService).upsertProductByCode(any(JsonNode.class));

        byte[] csv = csv("product_code,name\nP-RACE-A,撞编码\nP-RACE-B,正常\n");
        long batchId = validatedBatch("c1b.csv", csv);

        ResponseEntity<String> resp = confirm("key-d9", batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("PARTIAL_SUCCESS", data.get("status").asText(), resp.getBody());
        assertEquals(1, data.get("success_rows").asInt());
        assertEquals(1, data.get("failed_rows").asInt());

        Map<String, Object> err = jdbc.queryForMap("SELECT * FROM import_row_errors WHERE batch_id = ?", batchId);
        assertEquals("CAT-3002", err.get("error_code"), "C1b：唯一键冲突统一译为「编码被占用」");
        assertEquals(1L, ((Number) err.get("row_no")).longValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE product_code = 'P-RACE-B'", Integer.class));
        assertEquals(1, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"), "只有成功行产生审计");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private long validatedBatch(String fileName, byte[] csv) throws Exception {
        long batchId = uploadOk(fileName, csv);
        preview(batchId);
        mapping(batchId);
        ResponseEntity<String> resp = validate(batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "校验应成功：" + resp.getBody());
        assertEquals("VALIDATED", data(resp).get("status").asText(), resp.getBody());
        assertEquals(0, data(resp).get("error_rows").asInt(), resp.getBody());
        return batchId;
    }

    private ResponseEntity<String> preview(long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token());
        return rest.exchange("/api/v1/import/batches/" + batchId + "/preview", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private void mapping(long batchId) throws Exception {
        JsonNode suggested = data(preview(batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token());
        Map<String, Object> body = Map.of("field_mapping_json", map, "parse_options_json", Map.of());
        rest.exchange("/api/v1/import/batches/" + batchId + "/mapping", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> validate(long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token());
        return rest.exchange("/api/v1/import/batches/" + batchId + "/validate", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> confirm(String key, long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token());
        headers.set("Idempotency-Key", key);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers), String.class);
    }

    private long uploadOk(String fileName, byte[] content) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(content, fileName));
        body.add("bizType", "PRODUCT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token());
        ResponseEntity<String> response = rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return data(response).get("batch_id").asLong();
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE team_id = ?", Integer.class, TEAM_ID);
    }

    private int audits(String bizType, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE team_id = ? "
                + "AND action = ? AND biz_type = ?", Integer.class, TEAM_ID, action, bizType);
    }

    private String token() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + OPERATOR + "\",\"password\":\"" + TestCredentials.RAW_PASSWORD + "\"}";
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
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
