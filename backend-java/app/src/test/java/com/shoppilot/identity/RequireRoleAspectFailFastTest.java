package com.shoppilot.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.web.ApiResponse;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M2-T08 P-2：切面「显式声明 teamIdParam 却取不到参数」必须 fail-fast（不静默放行）。
 *
 * <p>真栈：真实 MySQL 8.0.36（Testcontainers）+ 完整 Spring 上下文（Security + 切面）。
 * 用一个<b>测试专用</b>探针 Controller 暴露两种端点：
 * <ul>
 *   <li>{@code /no-team-id-param}：标注 {@code @RequireRole(teamIdParam="teamId")} 但方法<b>无</b> {@code teamId} 参数
 *       → 切面解析失败必须抛 {@link IllegalStateException} → 全局异常 → 500 + {@code COM-0002}（不得静默放行）；</li>
 *   <li>{@code /with-team-id/{teamId}}：标注同上且方法<b>有</b> {@code teamId} 参数（正控制）→ 200 + {@code OK}。</li>
 * </ul>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RequireRoleAspectFailFastTest.ProbeController.class)
@Testcontainers
class RequireRoleAspectFailFastTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = com.shoppilot.TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = com.shoppilot.TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM skus");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES (?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (1004, 'owner01', 'owner01@example.com', ?, '老板', 'ACTIVE')", PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES (?, 1004, 'OWNER', 'ACTIVE')", TEAM_ID);
    }

    @Test
    @DisplayName("P-2 fail-fast：声明 teamIdParam 但方法无该参数 → 不静默放行（500 + COM-0002）")
    void declaredTeamIdParamButMissingArg_NotSilentlyPassed() throws Exception {
        String owner = loginAccessToken("owner01");
        ResponseEntity<String> response = get("/api/v1/__t08_probe/no-team-id-param", owner);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(), response.getBody());
        assertEquals("COM-0002", code(response), "配置错误的 teamIdParam 必须快速暴露（fail-fast），不得静默放行");
    }

    @Test
    @DisplayName("P-2 正控制：teamIdParam 可解析时切面正常工作（200 + OK）")
    void teamIdParamResolvable_AspectWorks() throws Exception {
        String owner = loginAccessToken("owner01");
        ResponseEntity<String> response = get("/api/v1/__t08_probe/with-team-id/" + TEAM_ID, owner);

        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertEquals("OK", code(response), "teamId 可解析时应正常放行");
    }

    // =====================================================================
    // 测试专用探针 Controller（@RestControllerAdvice 包络 + 切面均生效）
    // =====================================================================

    @RestController
    @RequestMapping("/api/v1/__t08_probe")
    static class ProbeController {

        /** 声明 teamIdParam="teamId"，但方法<b>无</b> teamId 参数 → 触发 fail-fast。 */
        @RequireRole(value = {"OWNER", "ADMIN"}, teamIdParam = "teamId")
        @GetMapping("/no-team-id-param")
        public ApiResponse<Void> noTeamIdParam() {
            return ApiResponse.success();
        }

        /** 正控制：声明 teamIdParam="teamId" 且方法有 teamId 参数（路径变量）。 */
        @RequireRole(value = {"OWNER", "ADMIN"}, teamIdParam = "teamId")
        @GetMapping("/with-team-id/{teamId}")
        public ApiResponse<Void> withTeamId(@PathVariable("teamId") Long teamId) {
            return ApiResponse.success();
        }
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private String loginAccessToken(String username) throws Exception {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + username + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, entity(null, bearer), String.class);
    }

    private ResponseEntity<String> post(String path, String jsonBody, String bearer) {
        return rest.exchange(path, HttpMethod.POST, entity(jsonBody, bearer), String.class);
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

    private String code(ResponseEntity<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getBody());
        if (body == null || body.get("code") == null) {
            throw new IllegalStateException("响应无 code 字段：" + response.getBody());
        }
        return body.get("code").asText();
    }
}
