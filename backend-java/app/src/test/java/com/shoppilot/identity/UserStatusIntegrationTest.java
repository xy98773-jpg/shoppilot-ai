package com.shoppilot.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M2-T03 用户启停（{@code PATCH /api/v1/users/{userId}/status}）—— 端到端集成测试。
 *
 * <p><b>真栈</b>：真实 MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移 + 完整 Spring 上下文
 * （{@code SecurityFilterChain} + {@code JwtAuthenticationFilter} + 租户线拦截器）+ 真实 HTTP。
 *
 * <p>与 {@link AuthFlowIntegrationTest} 分文件的原因：本类依赖 T03 才落地的用户启停端点，
 * 拆开后「认证」与「用户启停」两笔提交可各自独立构建并通过测试。
 *
 * <p>覆盖硬门槛：§4-4 <b>禁用后旧 Access Token 下一请求即被拒（实时校验）</b>、
 * 以及 M2-T03 的授权规则（矩阵直查：OWNER/ADMIN 可管理成员、不得禁用自己、目标不存在）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserStatusIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = com.shoppilot.TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = com.shoppilot.TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VICTIM_ID = 1002L;
    private static final long VIEWER_ID = 1003L;
    private static final long OWNER_ID = 1004L;

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

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) "
                + "VALUES (?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);
        insertUser(OPERATOR_ID, "operator01", "小王");
        insertUser(VICTIM_ID, "victim01", "小李");
        insertUser(VIEWER_ID, "viewer01", "观察者");
        insertUser(OWNER_ID, "owner01", "老板");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), "
                        + "(?, ?, 'VIEWER', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OPERATOR_ID, TEAM_ID, VICTIM_ID, TEAM_ID, VIEWER_ID, TEAM_ID, OWNER_ID);
    }

    // =====================================================================
    // §4-4 禁用用户 → 旧 Access Token 下一请求即被拒
    // =====================================================================

    @Test
    @DisplayName("§4-4 禁用实时拦截：禁用后旧 Access Token 请求受保护端点 → 403 AUTH-1003")
    void disabledUserIsRejectedWithOldToken() throws Exception {
        String operatorToken = loginAccessToken("operator01");
        // 前置：未禁用时 /me 可用
        assertEquals(HttpStatus.OK, get("/api/v1/auth/me", operatorToken).getStatusCode());

        String ownerToken = loginAccessToken("owner01");
        ResponseEntity<String> patch = patch("/api/v1/users/" + OPERATOR_ID + "/status",
                "{\"status\":\"DISABLED\"}", ownerToken);
        assertEquals(HttpStatus.OK, patch.getStatusCode(), "OWNER 应可禁用成员，实际：" + patch.getBody());

        // 用【旧】Access Token 请求受保护端点 → 实时校验 users.status → 403 AUTH-1003
        ResponseEntity<String> afterDisable = get("/api/v1/auth/me", operatorToken);
        assertEquals(HttpStatus.FORBIDDEN, afterDisable.getStatusCode(),
                "禁用后旧令牌应立即失效（证明是每请求实时校验，而非仅登录时校验）");
        assertEquals("AUTH-1003", code(afterDisable));

        // 被禁用用户重新登录同样被拒（同一 AUTH-1003）
        assertEquals("AUTH-1003", code(post("/api/v1/auth/login",
                "{\"username\":\"operator01\",\"password\":\"" + RAW_PASSWORD + "\"}", null)));
    }

    @Test
    @DisplayName("M2-T03 越权：VIEWER 无权改用户状态（TEAM-2003）；ADMIN/OWNER 不得禁用自己（AUTH-1010）")
    void userStatusAuthorizationRules() throws Exception {
        String viewerToken = loginAccessToken("viewer01");
        ResponseEntity<String> byViewer = patch("/api/v1/users/" + VICTIM_ID + "/status",
                "{\"status\":\"DISABLED\"}", viewerToken);
        assertEquals(HttpStatus.FORBIDDEN, byViewer.getStatusCode());
        assertEquals("TEAM-2003", code(byViewer), "矩阵 #12 成员管理 = OWNER/ADMIN，VIEWER 应被拒");

        String ownerToken = loginAccessToken("owner01");
        ResponseEntity<String> selfDisable = patch("/api/v1/users/" + OWNER_ID + "/status",
                "{\"status\":\"DISABLED\"}", ownerToken);
        assertEquals(HttpStatus.FORBIDDEN, selfDisable.getStatusCode());
        assertEquals("AUTH-1010", code(selfDisable), "不得禁用自己");

        ResponseEntity<String> notExist = patch("/api/v1/users/999999/status",
                "{\"status\":\"DISABLED\"}", ownerToken);
        assertEquals(HttpStatus.NOT_FOUND, notExist.getStatusCode());
        assertEquals("AUTH-1004", code(notExist), "目标用户不存在 → AUTH-1004");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private void insertUser(long id, String username, String displayName) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, displayName);
    }

    private String loginAccessToken(String username) throws Exception {
        JsonNode data = objectMapper.readTree(post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null).getBody())
                .get("data");
        return data.get("accessToken").asText();
    }

    private ResponseEntity<String> post(String path, String jsonBody, String bearer) {
        return rest.exchange(path, HttpMethod.POST, entity(jsonBody, bearer), String.class);
    }

    private ResponseEntity<String> patch(String path, String jsonBody, String bearer) {
        return rest.exchange(path, HttpMethod.PATCH, entity(jsonBody, bearer), String.class);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, entity(null, bearer), String.class);
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
        return objectMapper.readTree(response.getBody()).get("code").asText();
    }
}
