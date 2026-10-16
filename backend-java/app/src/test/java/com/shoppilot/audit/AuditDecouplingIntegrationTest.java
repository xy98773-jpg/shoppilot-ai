package com.shoppilot.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.audit.infrastructure.persistence.mapper.AuditLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M2-T11 审计<b>解耦</b>专项（任务书 §6-9）：审计写入抛异常时<b>业务不回滚</b>，且有 ERROR 日志。
 *
 * <p>做法：把 {@link AuditLogMapper} 替换为故障 Mock（{@code insert} 一律抛
 * {@link DataIntegrityViolationException}），然后真实走 HTTP 登录 + 创建仓库——
 * 两条路径内部都会触发审计写入，若审计失败会阻塞业务，则请求必然失败。
 *
 * <p>同时验证 {@code AuditWriteService.record} 的兜底契约：只记 ERROR 日志、不向业务抛异常。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuditDecouplingIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = "S3cret!Pass";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(RAW_PASSWORD);

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    /** 审计表 Mapper 故障注入：insert 一律抛异常（模拟库抖动 / 数据非法）。 */
    @MockitoBean
    private AuditLogMapper auditLogMapper;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ListAppender<ILoggingEvent> logAppender;
    private Logger auditWriteLogger;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) "
                + "VALUES (7, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (1001, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE')",
                PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) "
                + "VALUES (7, 1001, 'OPERATOR', 'ACTIVE')");

        // 捕获 audit 写入服务的 ERROR 日志（§6-9 要求：审计失败有 ERROR 日志作为补偿线索）
        auditWriteLogger = (Logger) LoggerFactory.getLogger(
                "com.shoppilot.audit.application.AuditWriteService");
        logAppender = new ListAppender<>();
        logAppender.start();
        auditWriteLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (auditWriteLogger != null && logAppender != null) {
            auditWriteLogger.detachAppender(logAppender);
        }
    }

    @Test
    @DisplayName("§6-9 解耦：审计 insert 抛异常 → 登录与创建仓库仍成功（业务不回滚）且有 ERROR 日志")
    void auditWriteFailureDoesNotAffectBusiness() throws Exception {
        when(auditLogMapper.insert(any())).thenThrow(new DataIntegrityViolationException("注入的审计故障"));

        // ① 登录（内部写 AUTH_LOGIN_SUCCESS 审计）仍返回 200 —— 审计失败不得阻塞登录
        String token = loginAccessToken("operator01");
        assertNotNull(token, "审计写入失败时登录仍应成功");

        // ② 创建仓库（内部写 MASTER_DATA_STATUS_CHANGE 审计）仍返回 201 且数据落库
        long wh = createAndGetId("/api/v1/warehouses", "{\"code\":\"W-DCP\",\"name\":\"解耦仓\"}", token);
        assertTrue(wh > 0, "审计写入失败时创建仍应成功");
        assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM warehouses WHERE id = ? AND code = 'W-DCP'", Integer.class, wh),
                "业务事务必须已提交（未被审计失败回滚）");

        // ③ 审计行缺失（解耦的已知代价：业务成功而审计缺失，由 ERROR 日志兜底排查）
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class),
                "注入故障下审计行写不进去（解耦策略的已知代价）");

        // ④ 审计写入确被尝试，且失败只记 ERROR 日志、不冒泡
        verify(auditLogMapper, atLeastOnce()).insert(any());
        boolean errorLogged = logAppender.list.stream()
                .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                        && e.getFormattedMessage().contains("审计写入失败"));
        assertTrue(errorLogged, "审计写入失败必须记 ERROR 日志（含 action/bizType/requestId 补偿线索）");
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private String loginAccessToken(String username) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                entity("{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private long createAndGetId(String path, String jsonBody, String bearer) throws Exception {
        ResponseEntity<String> response = rest.exchange(path, HttpMethod.POST,
                entity(jsonBody, bearer), String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "创建应返回 201：" + path + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("id").asLong();
    }

    private HttpEntity<String> entity(String jsonBody, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return new HttpEntity<>(jsonBody, headers);
    }
}
