package com.shoppilot.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-T02 认证端点（login / refresh / logout / me）—— 端到端集成测试。
 *
 * <p><b>真栈</b>：真实 MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移 + <b>完整 Spring 上下文</b>
 * （{@code SecurityFilterChain} + {@code JwtAuthenticationFilter} + 租户线拦截器）+ 真实 HTTP
 * （{@code TestRestTemplate} 打到随机端口）。因此本测试覆盖的正是任务书 §4 的硬门槛，
 * 而不是「手工装配的近似物」。
 *
 * <p>覆盖：白名单可达性、登录响应字段对齐、<b>防枚举</b>、刷新轮换与登出失效、
 * {@code /me} 权限位、<b>明文红线</b>、不引入 Redis。
 *
 * <p>用户启停（{@code PATCH /users/{id}/status}）与「禁用后旧令牌实时被拒」见
 * {@link UserStatusIntegrationTest}（M2-T03），拆开以保证两笔提交各自独立可测。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthFlowIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 全部测试账号共用同一口令的 BCrypt 哈希（只算一次，避免每用例重复 BCrypt 开销）。 */
    private static final String RAW_PASSWORD = com.shoppilot.TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = com.shoppilot.TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // 测试专用密钥（仅存在于测试进程，非真实密钥；生产中由 JWT_SECRET 环境变量注入）
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
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) "
                + "VALUES (?, ?, 'OPERATOR', 'ACTIVE')", TEAM_ID, OPERATOR_ID);
    }

    // =====================================================================
    // §4-1 白名单生效
    // =====================================================================

    @Test
    @DisplayName("§4-1 白名单：/auth/login、/auth/refresh 匿名可达；/logout、/me 仍需认证")
    void whitelistAllowsLoginAndRefreshButNotLogoutOrMe() throws Exception {
        // 登录可达：参数缺失应走到 Controller 的 Bean Validation（400 + COM-0001），
        // 而不是被 Security 拦成 401 + AUTH-1001
        ResponseEntity<String> login = post("/api/v1/auth/login", "{}", null);
        assertEquals(HttpStatus.BAD_REQUEST, login.getStatusCode(), "登录端点应可达（未被 AUTH-1001 拦截）");
        assertEquals("COM-0001", code(login), "应为参数校验错误（证明已到达 Controller）");

        // refresh 同样可达（无令牌 → AUTH-1006 而非 AUTH-1001）
        ResponseEntity<String> refresh = post("/api/v1/auth/refresh", "{}", null);
        assertEquals(HttpStatus.UNAUTHORIZED, refresh.getStatusCode());
        assertEquals("AUTH-1006", code(refresh), "空令牌应报 AUTH-1006（端点可达）");

        // logout / me 仍需认证
        assertEquals("AUTH-1001", code(post("/api/v1/auth/logout", "{}", null)));
        assertEquals("AUTH-1001", code(get("/api/v1/auth/me", null)));
    }

    // =====================================================================
    // §4-2 登录成功响应与契约 §11.1 逐字段一致
    // =====================================================================

    @Test
    @DisplayName("§4-2 登录成功：data 与契约 §11.1 逐字段一致（expiresIn=7200 / tokenType=Bearer）")
    void loginSuccessMatchesContract() throws Exception {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                "{\"username\":\"operator01\",\"password\":\"" + RAW_PASSWORD + "\"}", null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());

        assertEquals("OK", body.get("code").asText());
        assertEquals("success", body.get("message").asText());
        assertTrue(body.get("timestamp").asText().endsWith("Z"), "timestamp 应为 ISO-8601 UTC 带 Z");
        assertEquals(response.getHeaders().getFirst("X-Request-Id"), body.get("requestId").asText(),
                "§4-9 requestId 必须与响应头 X-Request-Id 一致");

        JsonNode data = body.get("data");
        assertNotNull(data.get("accessToken").asText());
        assertTrue(data.get("refreshToken").asText().startsWith("rt_"), "refreshToken 应为 rt_ 前缀不透明串");
        assertEquals("Bearer", data.get("tokenType").asText());
        assertEquals(7200, data.get("expiresIn").asLong(), "D-01：Access Token 2h = 7200s");
        assertEquals("OPERATOR", data.get("role").asText());

        JsonNode user = data.get("user");
        assertEquals(OPERATOR_ID, user.get("user_id").asLong(), "契约字段为 snake_case：user_id");
        assertEquals("operator01", user.get("username").asText());
        assertEquals("小王", user.get("display_name").asText());
        assertEquals("ACTIVE", user.get("status").asText());

        JsonNode team = data.get("team");
        assertEquals(TEAM_ID, team.get("team_id").asLong());
        assertEquals("星海优选", team.get("team_name").asText());
        assertEquals("Asia/Shanghai", team.get("default_timezone").asText());

        // D-01 传输：Set-Cookie（HttpOnly + Secure）
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "应下发 refresh_token Cookie");
        assertTrue(setCookie.contains("refresh_token="));
        assertTrue(setCookie.toLowerCase().contains("httponly"), "Cookie 必须 HttpOnly");
        assertTrue(setCookie.toLowerCase().contains("secure"), "Cookie 必须 Secure");
    }

    // =====================================================================
    // §4-3 防枚举
    // =====================================================================

    @Test
    @DisplayName("§4-3 防枚举：用户不存在 vs 密码错误 → 同一 AUTH-1002、同一文案、同为 401")
    void antiEnumerationSameResponse() throws Exception {
        ResponseEntity<String> notExist = post("/api/v1/auth/login",
                "{\"username\":\"no-such-user\",\"password\":\"" + RAW_PASSWORD + "\"}", null);
        ResponseEntity<String> wrongPassword = post("/api/v1/auth/login",
                "{\"username\":\"operator01\",\"password\":\"definitely-wrong\"}", null);

        assertEquals(HttpStatus.UNAUTHORIZED, notExist.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, wrongPassword.getStatusCode());

        JsonNode a = objectMapper.readTree(notExist.getBody());
        JsonNode b = objectMapper.readTree(wrongPassword.getBody());
        assertEquals("AUTH-1002", a.get("code").asText());
        assertEquals("AUTH-1002", b.get("code").asText());
        assertEquals(a.get("message").asText(), b.get("message").asText(), "两者提示文案必须完全相同");
        assertEquals("用户名或密码错误", a.get("message").asText());
    }

    // =====================================================================
    // §4-5 轮换与登出
    // =====================================================================

    @Test
    @DisplayName("§4-5a 刷新轮换：新令牌可用，旧令牌立即失效（401 AUTH-1006），库内写 replaced_by")
    void refreshRotatesAndInvalidatesOldToken() throws Exception {
        String rawRefresh = loginRefreshToken("operator01");

        ResponseEntity<String> refreshed = post("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + rawRefresh + "\"}", null);
        assertEquals(HttpStatus.OK, refreshed.getStatusCode(), "刷新应成功：" + refreshed.getBody());
        JsonNode data = objectMapper.readTree(refreshed.getBody()).get("data");
        String newRefresh = data.get("refreshToken").asText();
        assertNotNull(data.get("accessToken").asText());
        assertNotEquals(rawRefresh, newRefresh, "轮换必须产生新的 Refresh Token");

        // 旧令牌再用 → 失效
        ResponseEntity<String> reuse = post("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + rawRefresh + "\"}", null);
        assertEquals(HttpStatus.UNAUTHORIZED, reuse.getStatusCode());
        assertEquals("AUTH-1006", code(reuse), "已轮换的旧 Refresh Token 必须失效");

        // 新令牌仍可用
        assertEquals(HttpStatus.OK, post("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + newRefresh + "\"}", null).getStatusCode());

        // 库内取证：旧行 revoked_at 非空且 replaced_by 指向新摘要（§4-5 轮换链）
        Long revoked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL AND replaced_by IS NOT NULL",
                Long.class);
        assertNotNull(revoked);
        assertTrue(revoked >= 1, "轮换后应有至少 1 行写入 revoked_at + replaced_by");
    }

    @Test
    @DisplayName("§4-5b 登出：Refresh 失效（401 AUTH-1006）且幂等（重复登出不报错）")
    void logoutInvalidatesRefreshAndIsIdempotent() throws Exception {
        JsonNode login = objectMapper.readTree(post("/api/v1/auth/login",
                "{\"username\":\"operator01\",\"password\":\"" + RAW_PASSWORD + "\"}", null).getBody())
                .get("data");
        String access = login.get("accessToken").asText();
        String refresh = login.get("refreshToken").asText();

        ResponseEntity<String> logout = post("/api/v1/auth/logout",
                "{\"refreshToken\":\"" + refresh + "\"}", access);
        assertEquals(HttpStatus.OK, logout.getStatusCode(), "登出应成功：" + logout.getBody());

        // 登出后该 Refresh Token 失效
        ResponseEntity<String> reuse = post("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + refresh + "\"}", null);
        assertEquals(HttpStatus.UNAUTHORIZED, reuse.getStatusCode());
        assertEquals("AUTH-1006", code(reuse), "登出后 Refresh Token 必须失效");

        // 幂等：重复登出不报错
        assertEquals(HttpStatus.OK,
                post("/api/v1/auth/logout", "{\"refreshToken\":\"" + refresh + "\"}", access).getStatusCode());
    }

    // =====================================================================
    // §4-9 + /me 权限位
    // =====================================================================

    @Test
    @DisplayName("§4-9 /auth/me：返回用户+团队+角色+权限位，且 requestId == X-Request-Id")
    void meReturnsProfileAndPermissionBits() throws Exception {
        String access = loginAccessToken("operator01");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(access);
        headers.set("X-Request-Id", "req_test_me_001");
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("req_test_me_001", body.get("requestId").asText(), "应回显客户端 X-Request-Id");
        assertEquals("req_test_me_001", response.getHeaders().getFirst("X-Request-Id"),
                "requestId 必须与响应头一致（契约 §2.1）");

        JsonNode data = body.get("data");
        assertEquals("OPERATOR", data.get("role").asText());
        assertEquals(OPERATOR_ID, data.get("user").get("user_id").asLong());
        assertEquals(TEAM_ID, data.get("team").get("team_id").asLong());

        JsonNode permissions = data.get("permissions");
        // OPERATOR：可提交审核、不可审批、不可成员管理、不可查看审计（USER_ROLES §2.2 直查）
        assertTrue(permissions.get("submit_review").asBoolean());
        assertFalse(permissions.get("approve").asBoolean(), "OPERATOR 不能审批（矩阵 #8 ❌）");
        assertFalse(permissions.get("manage_members").asBoolean(), "OPERATOR 不能管理成员（矩阵 #12 ❌）");
        assertFalse(permissions.get("view_audit").asBoolean(), "OPERATOR 不能查看审计（矩阵 #11 ❌）");
    }

    // =====================================================================
    // §4-6 / §4-7 明文红线与不引入 Redis
    // =====================================================================

    @Test
    @DisplayName("§4-6 明文红线：refresh_tokens 只存 SHA-256 摘要（64 位十六进制），绝无明文令牌")
    void refreshTokenStoresOnlyHash() throws Exception {
        String rawRefresh = loginRefreshToken("operator01");

        List<String> hashes = jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class);
        assertFalse(hashes.isEmpty(), "登录后应有 Refresh 记录");
        for (String hash : hashes) {
            assertEquals(64, hash.length(), "应为 SHA-256 十六进制（64 位）");
            assertTrue(hash.matches("[0-9a-f]{64}"), "应为小写十六进制：" + hash);
            assertNotEquals(rawRefresh, hash, "库内绝不得出现明文令牌");
        }
        // 明文令牌也不得出现在其它列
        Long leaked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ? OR replaced_by = ?",
                Long.class, rawRefresh, rawRefresh);
        assertEquals(0L, leaked, "明文令牌不得落库");
    }

    @Test
    @DisplayName("§4-7 不引入 Redis / Session 存储（类路径上不存在 Redis 客户端）")
    void noRedisOnClasspath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.data.redis.core.RedisTemplate"),
                "首期不引入 Redis（DESIGN_BASIS §15 D-07 / API_CONTRACT §5.5）");
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

    private String loginRefreshToken(String username) throws Exception {
        JsonNode data = objectMapper.readTree(post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null).getBody())
                .get("data");
        return data.get("refreshToken").asText();
    }

    private ResponseEntity<String> post(String path, String jsonBody, String bearer) {
        return rest.exchange(path, HttpMethod.POST, entity(jsonBody, bearer), String.class);
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

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private String code(ResponseEntity<String> response) throws Exception {
        return body(response).get("code").asText();
    }
}
