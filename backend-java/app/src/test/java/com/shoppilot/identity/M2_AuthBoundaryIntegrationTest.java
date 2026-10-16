package com.shoppilot.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-T13 §1-B：认证边界补齐 —— 端到端集成测试。
 *
 * <h2>权威依据</h2>
 * <ul>
 *   <li>{@code ERROR_CODES.md §2.1}：<b>{@code AUTH-1005} = 401 Access Token 已过期</b>（{@code exp} 已过）——
 *       与 {@code AUTH-1001}（未登录/令牌失效）是<b>两个不同错误码</b>；</li>
 *   <li>{@code JwtAuthenticationFilter}：过期 → {@code AUTH-1005}；签名不符/非法 → {@code AUTH-1001}；
 *       非 {@code Bearer} 前缀 → {@code AUTH-1011}；</li>
 *   <li>{@code API_CONTRACT §5.1}：{@code /auth/login}、{@code /auth/refresh} 免认证。</li>
 * </ul>
 *
 * <h2>★ 为什么不清一色用 {@code Thread.sleep} 造过期令牌</h2>
 * 本类以<b>预签 past-exp JWT</b>（用测试注入的同一签名密钥在进程内直接签发 {@code exp} 已过期的令牌）构造，
 * 全程无 {@code Thread.sleep}、无时钟等待，确定性且瞬时。
 *
 * <h2>与既有用例的关系（不重复声明）</h2>
 * 「<b>禁用</b>用户后既有 Access Token → 403 {@code AUTH-1003}」已由
 * {@code UserStatusIntegrationTest §4-4} 覆盖，本类<b>不再重测</b>。
 * 本类补齐的是：<b>过期</b>（{@code AUTH-1005}）、<b>签名不符</b>（{@code AUTH-1001}）、
 * <b>非 Bearer 前缀</b>（{@code AUTH-1011}）三处边界。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class M2_AuthBoundaryIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 测试专用口令（仅存在于测试进程）。 */
    private static final String RAW_PASSWORD = com.shoppilot.TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = com.shoppilot.TestCredentials.PASSWORD_HASH;

    /** 与 {@code @DynamicPropertySource} 注入的签名密钥保持一致（仅测试进程可见）。 */
    private static final String TEST_JWT_SECRET = "test-only-jwt-secret-0123456789abcdef";
    private static final String ISSUER = "shoppilot-ai";
    private static final String AUDIENCE = "frontend-web";

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> TEST_JWT_SECRET);
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
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE')",
                OPERATOR_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) "
                + "VALUES (?, ?, 'OPERATOR', 'ACTIVE')", TEAM_ID, OPERATOR_ID);
    }

    // =====================================================================
    // §1-B-1 Access Token 过期 → 401 AUTH-1005
    // =====================================================================

    @Test
    @DisplayName("§1-B-1 过期 Access Token → 401 AUTH-1005（★ 非 AUTH-1001；ERROR_CODES §2.1 两码语义不同）")
    void expiredAccessTokenReturnsAuth1005() throws Exception {
        Instant now = Instant.now();
        String expired = issueToken(TEST_JWT_SECRET, now.minusSeconds(7200), now.minusSeconds(3600));

        ResponseEntity<String> response = get("/api/v1/warehouses", expired);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "过期令牌必须 401：" + response.getBody());
        String actual = code(response);
        assertEquals("AUTH-1005", actual,
                "★ 过期应报 AUTH-1005（ERROR_CODES.md §2.1「Access Token 已过期」）；实测=" + actual);
        assertNotEquals("AUTH-1001", actual,
                "过期与「失效」是不同错误码——若实现返回 AUTH-1001，前端将无法提示「用 Refresh Token 换取」");
    }

    @Test
    @DisplayName("§1-B-1b 过期令牌不影响免认证端点：持过期令牌访问 /auth/login 仍可达（400 COM-0001）")
    void expiredTokenDoesNotBlockAnonymousEndpoint() throws Exception {
        Instant now = Instant.now();
        String expired = issueToken(TEST_JWT_SECRET, now.minusSeconds(7200), now.minusSeconds(3600));

        // 空 body → 应走到 Controller 的 Bean Validation（400 COM-0001），
        // 而非被 Security 拦成 401（JwtAuthenticationFilter.ANONYMOUS_PATHS 免解析）
        ResponseEntity<String> response = post("/api/v1/auth/login", "{}", expired);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "登录端点必须可达（不被过期令牌拦成 401）：" + response.getBody());
        assertEquals("COM-0001", code(response));
    }

    // =====================================================================
    // §1-B-2 签名不符 → 401 AUTH-1001
    // =====================================================================

    @Test
    @DisplayName("§1-B-2 签名不符（异密钥签发）→ 401 AUTH-1001（未过期也不行）")
    void tamperedSignatureReturnsAuth1001() throws Exception {
        Instant now = Instant.now();
        // claim 全部合法、未过期，仅签名密钥不同 → 必须被拒
        String forged = issueToken("wrong-secret-but-long-enough-0123456789", now, now.plusSeconds(7200));

        ResponseEntity<String> response = get("/api/v1/warehouses", forged);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), response.getBody());
        assertEquals("AUTH-1001", code(response), "签名不符属「令牌失效」→ AUTH-1001");
    }

    // =====================================================================
    // §1-B-3 非 Bearer 前缀 → 401 AUTH-1011
    // =====================================================================

    @Test
    @DisplayName("§1-B-3 认证头非 Bearer 前缀（如 Token xxx）→ 401 AUTH-1011（凭证格式错误）")
    void malformedAuthorizationSchemeReturnsAuth1011() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token abc.def.ghi");
        ResponseEntity<String> response = rest.exchange("/api/v1/warehouses", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), response.getBody());
        assertEquals("AUTH-1011", code(response), "非 Bearer 前缀 → AUTH-1011（ERROR_CODES §2.1）");
    }

    // =====================================================================
    // 对照：合法令牌可通行（证明上述用例的「拒绝」不是端点本身不可达）
    // =====================================================================

    @Test
    @DisplayName("对照：同一端点用【真实登录】取得的令牌 → 200（证明上述 401 均由令牌本身导致）")
    void validTokenFromRealLoginSucceeds() throws Exception {
        ResponseEntity<String> login = post("/api/v1/auth/login",
                "{\"username\":\"operator01\",\"password\":\"" + RAW_PASSWORD + "\"}", null);
        assertEquals(HttpStatus.OK, login.getStatusCode(), login.getBody());
        String access = objectMapper.readTree(login.getBody()).get("data").get("accessToken").asText();

        ResponseEntity<String> response = get("/api/v1/warehouses", access);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "合法令牌应可访问：HTTP=" + response.getStatusCode() + " body=" + response.getBody());
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** 在测试进程内用给定密钥签发一枚 Access Token（claim 布局与 {@code JwtTokenProviderImpl} 一致）。 */
    private static String issueToken(String secret, Instant issuedAt, Instant expiration) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(OPERATOR_ID))
                .claim("username", "operator01")
                .claim("team_id", TEAM_ID)
                .claim("role", "OPERATOR")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
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

    private String code(ResponseEntity<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getBody());
        return body.get("code").asText();
    }
}
