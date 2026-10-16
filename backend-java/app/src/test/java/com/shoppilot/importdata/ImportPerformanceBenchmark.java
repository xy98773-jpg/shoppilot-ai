package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * {@code NFR-PERF-02} 性能实测：<b>1 万行导入 &lt; 60s</b>（M3-B4 §6③；任务书裁定 {@code I4} / {@code K2}）。
 *
 * <h2>★ 为何类名不是 {@code *Test}（不进入默认 {@code verify} 用例集）</h2>
 * 这是一项<b>NFR 基准测量</b>（单次约 3–4 分钟），不是功能正确性用例：
 * <ul>
 *   <li>功能门槛（{@code verify} 全绿 + 新增集成测试 ≥19）由 {@code ImportConfirmIntegrationTest} /
 *       {@code ImportQueryIntegrationTest} 覆盖，本类的断言不参与其中；</li>
 *   <li>本类保留<b>严格</b>的 {@code < 60s} 断言（<b>不</b>放宽），必须显式运行才执行：</li>
 * </ul>
 * <pre>
 *   cd backend-java &amp;&amp; ./mvnw -B -pl app -am -Dtest=ImportPerformanceBenchmark \
 *       -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 * <b>实测结论（M3-B4，2026-10-17）</b>：{@code confirm} 耗时 <b>189,168 ms（189.2 s）</b>，
 * <b>未达标</b>，已按裁定 {@code I4}②<b>停手申报</b>（原因＝{@code catalog} 写服务对每行写一条审计，
 * 而 {@code AuditLogWriter.insert} 为 {@code REQUIRES_NEW} ⇒ 10000 行产生 10000 次独立事务提交）。
 * 详见类内 {@code report} 与 {@code target/import-perf-result.txt}。
 *
 * <h2>测量口径</h2>
 * <ul>
 *   <li><b>运行方式</b>：{@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate} 真实 HTTP，
 *       应用与 MySQL 均在本机（Testcontainers {@code mysql:8.0.36} 容器，{@code --default-time-zone=+00:00}）；</li>
 *   <li><b>被测对象</b>：{@code POST /api/v1/import/batches/{id}/confirm} 的端到端耗时
 *       （状态守卫 + 10000 行分批落库 + 终态 + 审计），不含上传/解析/校验（后者单独记录）；</li>
 *   <li><b>数据形态</b>：{@code PRODUCT} 模板 10000 条<b>互不重复</b>的 {@code product_code}（纯新增路径）；</li>
 *   <li><b>审计</b>：{@code catalog} 的写服务每对象写一行 {@code MASTER_DATA_STATUS_CHANGE}
 *       （{@code §9.17}）；裁定 {@code I4} 明令<b>不得为性能抑制</b>，故本用例实测含审计；</li>
 *   <li><b>批大小</b>：{@code ImportConfirmService.ROW_BATCH_SIZE = 500} → 10000 行 = 20 个独立事务；</li>
 *   <li><b>归因取证</b>：同时采样 {@code SHOW GLOBAL STATUS} 的 {@code Com_commit} / {@code Com_begin} /
 *       {@code Innodb_data_fsyncs} 增量，用于判断瓶颈是否在「每行一次独立提交」。</li>
 * </ul>
 *
 * <p>实测结果写入 {@code target/import-perf-result.txt}，便于汇报引用。</p>
 */
@Tag("perf")
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportPerformanceBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ImportPerformanceBenchmark.class);

    /** {@code NFR-PERF-02} 上限（{@code IMPORT_WORKFLOW §8.5} / 任务书 K2）。 */
    private static final int MAX_SECONDS = 60;

    private static final int ROWS = 10_000;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-perf-test").toString();
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

    @Test
    @DisplayName("NFR-PERF-02：1 万行 PRODUCT 确认导入 < 60s（含逐行审计，裁定 I4 不抑制）")
    void tenThousandRowsWithinSixtySeconds() throws Exception {
        StringBuilder sb = new StringBuilder("product_code,name\n");
        for (int i = 1; i <= ROWS; i++) {
            sb.append(String.format("P-PERF-%05d", i)).append(",压测商品").append(i).append('\n');
        }
        byte[] csv = sb.toString().getBytes(StandardCharsets.UTF_8);

        long t0 = System.nanoTime();
        long batchId = uploadOk(csv);
        String bearer = token();
        preview(bearer, batchId);
        mapping(bearer, batchId);
        ResponseEntity<String> validation = validate(bearer, batchId);
        long prepareMs = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(HttpStatus.OK, validation.getStatusCode(), "校验应成功");
        assertEquals(0, data(validation).get("error_rows").asInt(), "压测数据不应有校验错误");

        long c0 = System.nanoTime();
        long commits0 = globalStatus("Com_commit");
        long begins0 = globalStatus("Com_begin");
        long fsyncs0 = globalStatus("Innodb_data_fsyncs");
        ResponseEntity<String> confirm = confirm(bearer, batchId, "perf-key-1");
        long confirmMs = (System.nanoTime() - c0) / 1_000_000L;
        long commits = globalStatus("Com_commit") - commits0;
        long begins = globalStatus("Com_begin") - begins0;
        long fsyncs = globalStatus("Innodb_data_fsyncs") - fsyncs0;
        assertEquals(HttpStatus.OK, confirm.getStatusCode(), confirm.getBody());

        JsonNode d = data(confirm);
        long success = d.get("success_rows").asLong();
        long failed = d.get("failed_rows").asLong();
        String status = d.get("status").asText();

        int masterDataAudits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE team_id = ? AND action = 'MASTER_DATA_STATUS_CHANGE'", Integer.class, TEAM_ID);
        int batchCompletionAudits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs "
                + "WHERE team_id = ? AND action = 'IMPORT_BATCH_COMPLETE'", Integer.class, TEAM_ID);
        int products = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE team_id = ?",
                Integer.class, TEAM_ID);

        String report = "NFR-PERF-02 实测结果\n"
                + "运行方式      : Testcontainers mysql:8.0.36（本机 Docker）+ @SpringBootTest RANDOM_PORT + 真实 HTTP\n"
                + "被测端点      : POST /api/v1/import/batches/{id}/confirm\n"
                + "数据量        : " + ROWS + " 行 PRODUCT（互不重复）、批大小 500（=20 个独立事务）\n"
                + "confirm 耗时  : " + confirmMs + " ms\n"
                + "准备阶段耗时  : " + prepareMs + " ms（上传+预览+映射+校验，不计入 NFR）\n"
                + "上限          : " + (MAX_SECONDS * 1000) + " ms → " + (confirmMs < MAX_SECONDS * 1000L ? "通过" : "未通过") + "\n"
                + "落库结果      : status=" + status + " success_rows=" + success + " failed_rows=" + failed + "\n"
                + "审计行数      : MASTER_DATA_STATUS_CHANGE=" + masterDataAudits
                + ", IMPORT_BATCH_COMPLETE=" + batchCompletionAudits + "\n"
                + "products 行数 : " + products + "\n"
                + "--- 归因取证（SHOW GLOBAL STATUS 增量，仅 confirm 区间）---\n"
                + "Com_commit            : " + commits + "（业务分批事务 20 + 状态/终态 3 + 逐行审计 10000）\n"
                + "Com_begin             : " + begins + "\n"
                + "Innodb_data_fsyncs    : " + fsyncs + "\n"
                + "每行平均耗时          : " + String.format("%.2f", confirmMs / (double) ROWS) + " ms/行\n";
        log.info("\n{}", report);
        try {
            Files.writeString(Path.of("target", "import-perf-result.txt"), report, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("无法写入手工报告文件：{}", ex.getMessage());
        }

        assertEquals("COMPLETED", status);
        assertEquals(ROWS, success);
        assertEquals(0, failed);
        assertEquals(ROWS, products, "全部行应落库");
        assertEquals(ROWS, masterDataAudits, "逐行审计不得被抑制（裁定 I4）");
        assertEquals(1, batchCompletionAudits);
        assertTrue(confirmMs < MAX_SECONDS * 1000L,
                "NFR-PERF-02 未达标：" + confirmMs + " ms ≥ " + (MAX_SECONDS * 1000L) + " ms");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private long uploadOk(byte[] csv) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(csv, "perf.csv"));
        body.add("bizType", "PRODUCT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token());
        ResponseEntity<String> response = rest.exchange("/api/v1/import/batches", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return data(response).get("batch_id").asLong();
    }

    private void preview(String bearer, long batchId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        rest.exchange("/api/v1/import/batches/" + batchId + "/preview", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private void mapping(String bearer, long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        Map<String, Object> body = Map.of(
                "field_mapping_json", Map.of("product_code", "product_code", "name", "name"),
                "parse_options_json", Map.of());
        rest.exchange("/api/v1/import/batches/" + batchId + "/mapping", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> validate(String bearer, long batchId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/validate", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> confirm(String bearer, long batchId, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        headers.set("Idempotency-Key", key);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }

    /** 读取 {@code SHOW GLOBAL STATUS} 的某一项（用于归因取证的计数器增量）。 */
    private long globalStatus(String name) {
        Map<String, Object> row = jdbc.queryForMap("SHOW GLOBAL STATUS LIKE '" + name + "'");
        return Long.parseLong(String.valueOf(row.get("Value")));
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
