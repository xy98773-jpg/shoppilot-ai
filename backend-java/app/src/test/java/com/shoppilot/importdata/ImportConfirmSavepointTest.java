package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>G5 / D7：去逐行 {@code SAVEPOINT}</b> 的<b>可复现</b>断言（{@code ADR-004} D5）—— 真栈。
 *
 * <h2>怎么测（为什么用 JDBC 代理）</h2>
 * {@code SAVEPOINT} 是 <b>JDBC 层</b>动作（{@code Connection.setSavepoint}/{@code releaseSavepoint}/
 * {@code rollback(Savepoint)}），MyBatis 与 SQL 日志都看不到它，MySQL 的 {@code SHOW STATUS} 也没有对应
 * 计数器。故本类用一个 {@link DelegatingDataSource} + {@link Proxy} 把 {@code Connection} 包起来，
 * <b>只计数、全部委派</b>（不改变任何驱动行为），使「Pass 1 不建 {@code SAVEPOINT}」成为一条
 * 可重复执行的机器断言，而不是靠日志推断。
 *
 * <h2>两个用例（含<b>阴性对照</b>）</h2>
 * <ul>
 *   <li><b>D7a</b> 全成功批（100 行）→ {@code setSavepoint} 计数 <b>恰为 0</b>；同时断言审计 100 条
 *       （证明「去 SAVEPOINT」没有把审计一起去掉）；</li>
 *   <li><b>D7b</b>（阴性对照，{@code TEST_STRATEGY} 的「真失败必须仍被判为失败」）→ 批内 1 行引用漂移，
 *       走 Pass 2 逐行重放 ⇒ {@code setSavepoint} 计数<b>必须 &gt; 0</b>。若计数器失灵，
 *       D7a 的「0」将毫无意义——这条用来排除「探针本身是死的」。</li>
 * </ul>
 */
@SpringBootTest(classes = {ShopPilotApplication.class, ImportConfirmSavepointTest.SavepointProbeConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportConfirmSavepointTest {

    /** {@code setSavepoint} 累计次数（全类共享；{@code @BeforeEach} 归零）。 */
    private static final AtomicInteger SET_SAVEPOINT = new AtomicInteger();
    private static final AtomicInteger RELEASE_SAVEPOINT = new AtomicInteger();
    private static final AtomicInteger ROLLBACK_TO_SAVEPOINT = new AtomicInteger();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-savepoint-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;
    private static final String OPERATOR = "operator01";

    /**
     * 把容器里的 {@link DataSource} 包一层计数代理（只计数，全委派）。
     *
     * <p>用 {@code static} {@link BeanPostProcessor}：BeanPostProcessor 必须早于普通单例注册，
     * 否则 {@code DataSourceAutoConfiguration} 造出来的 DataSource 拿不到包装。
     *
     * <p>★ <b>必须把本类显式列进 {@code @SpringBootTest(classes = ...)}</b>：一旦给
     * {@code @SpringBootTest} 写了 {@code classes}，Spring Boot 就<b>不再</b>自动纳入测试类的
     * 嵌套 {@code @TestConfiguration}（自动纳入只在未指定 {@code classes} 时发生）。漏掉这一步的
     * 后果极其隐蔽：探针静默失效 ⇒ {@code setSavepoint} 恒为 0 ⇒ D7a 的「零 SAVEPOINT」
     * 变成假阳性。故 {@link #resetAndSeed()} 里有一条「探针活性」断言兜底，D7b 是它的阴性对照。
     */
    @TestConfiguration
    static class SavepointProbeConfig {

        @Bean
        static BeanPostProcessor savepointCountingPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource && !(bean instanceof CountingDataSource)) {
                        return new CountingDataSource(dataSource);
                    }
                    return bean;
                }
            };
        }
    }

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

    @Autowired
    private DataSource dataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetAndSeed() {
        // 探针活性守门：计数没装上时，任何「SAVEPOINT 为 0」的断言都不成立（假阳性）
        assertTrue(dataSource instanceof CountingDataSource,
                "SAVEPOINT 计数探针未生效：DataSource 实际类型为 "
                        + dataSource.getClass().getName() + "（应为 CountingDataSource）");

        SET_SAVEPOINT.set(0);
        RELEASE_SAVEPOINT.set(0);
        ROLLBACK_TO_SAVEPOINT.set(0);

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

        SET_SAVEPOINT.set(0);
        RELEASE_SAVEPOINT.set(0);
        ROLLBACK_TO_SAVEPOINT.set(0);
    }

    // =====================================================================
    // D7a 全成功批：零 SAVEPOINT
    // =====================================================================

    @Test
    @DisplayName("D7a 全成功批（100 行）→ 零 SAVEPOINT/RELEASE 往返；审计仍 100 条")
    void successfulBatchCreatesNoSavepoint() throws Exception {
        StringBuilder sb = new StringBuilder("product_code,name\n");
        for (int i = 1; i <= 100; i++) {
            sb.append(String.format("P-SP-%03d", i)).append(",无保存点商品").append(i).append('\n');
        }
        long batchId = validatedBatch("savepoint.csv", csv(sb.toString()));

        ResponseEntity<String> resp = confirm("key-d7a", batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText(), resp.getBody());

        assertEquals(0, SET_SAVEPOINT.get(), "Pass 1 整批一个事务，行内不得建立 SAVEPOINT");
        assertEquals(0, RELEASE_SAVEPOINT.get(), "无建立即无释放");
        assertEquals(0, ROLLBACK_TO_SAVEPOINT.get());
        assertEquals(100, count("products"));
        assertEquals(100, audits("PRODUCT", "MASTER_DATA_STATUS_CHANGE"), "去 SAVEPOINT 不得连带去掉审计");
    }

    // =====================================================================
    // D7b 阴性对照：部分失败 → Pass 2 逐行重放必须产生 SAVEPOINT
    // =====================================================================

    @Test
    @DisplayName("D7b 阴性对照：批内 1 行引用漂移 → Pass 2 逐行重放，SAVEPOINT 计数必须 > 0")
    void failingBatchUsesPerRowSavepoint() throws Exception {
        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status) "
                + "VALUES (9501, ?, 'SUP-SP', '临时供应商', 'ACTIVE')", TEAM_ID);

        byte[] csv = csv("product_code,name,default_supplier_code\n"
                + "P-SPF-001,首行,SUP-SP\n"
                + "P-SPF-002,次行\n"
                + "P-SPF-003,末行\n");
        long batchId = validatedBatch("savepoint-fail.csv", csv);
        jdbc.update("DELETE FROM suppliers WHERE code = 'SUP-SP'");

        ResponseEntity<String> resp = confirm("key-d7b", batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("PARTIAL_SUCCESS", data(resp).get("status").asText(), resp.getBody());

        assertTrue(SET_SAVEPOINT.get() >= 3,
                "Pass 2 逐行重放 3 行 ⇒ 至少 3 个 SAVEPOINT（实际 " + SET_SAVEPOINT.get() + "）");
        assertTrue(ROLLBACK_TO_SAVEPOINT.get() >= 1, "失败行必须 rollbackToSavepoint（实际 "
                + ROLLBACK_TO_SAVEPOINT.get() + "）");
        assertEquals(2, count("products"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors WHERE batch_id = ?",
                Integer.class, batchId));
    }

    // =====================================================================
    // 计数型 DataSource / Connection 包装（只计数、全委派）
    // =====================================================================

    /** 包装 {@link DataSource}，把取出的 {@link Connection} 换成计数代理。 */
    static final class CountingDataSource extends DelegatingDataSource {

        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }

        private static Connection wrap(Connection target) {
            if (target == null) {
                return null;
            }
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setSavepoint" -> SET_SAVEPOINT.incrementAndGet();
                            case "releaseSavepoint" -> RELEASE_SAVEPOINT.incrementAndGet();
                            case "rollback" -> {
                                if (args != null && args.length == 1 && args[0] instanceof Savepoint) {
                                    ROLLBACK_TO_SAVEPOINT.incrementAndGet();
                                }
                            }
                            case "equals" -> {
                                return proxy == args[0];
                            }
                            case "hashCode" -> {
                                return System.identityHashCode(proxy);
                            }
                            default -> {
                                // 其余方法一律原样委派（不改驱动行为）
                            }
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getTargetException();
                        }
                    });
        }
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
