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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-T04 成员管理 + M2-T05 团队配置 —— 端到端集成测试。
 *
 * <p><b>真栈</b>：真实 MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移 + 完整 Spring 上下文
 * （{@code SecurityFilterChain} + {@code JwtAuthenticationFilter} + 租户线拦截器）+ 真实 HTTP。
 *
 * <p>覆盖任务书 §3 硬门槛：成员 CRUD、<b>禁止移除 OWNER</b>、<b>软删</b>（行保留 + {@code REMOVED}）、
 * 越权矩阵（{@code TEAM-2003}）、跨团队（{@code TEAM-2001}）、团队配置与停用（{@code TEAM-2002}）、
 * 契约一致性。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TeamManagementIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = "S3cret!Pass";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(RAW_PASSWORD);

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;

    private static final long OWNER_ID = 1004L;
    private static final long ADMIN_ID = 1005L;
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;
    /** 无任何团队成员关系，仅用于「被添加」。 */
    private static final long OUTSIDER_ID = 1006L;
    /** 第二个未入团用户，专用于「新增第二名 OWNER」场景（避免先命中 TEAM-2005）。 */
    private static final long OUTSIDER2_ID = 1007L;

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

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);
        insertUser(OWNER_ID, "owner01", "老板");
        insertUser(ADMIN_ID, "admin01", "主管");
        insertUser(OPERATOR_ID, "operator01", "小王");
        insertUser(VIEWER_ID, "viewer01", "观察者");
        insertUser(OUTSIDER_ID, "outsider01", "外来户");
        insertUser(OUTSIDER2_ID, "outsider02", "外来户二");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'ADMIN', 'ACTIVE'), "
                        + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, ADMIN_ID, TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID);
    }

    // =====================================================================
    // §3-1 成员 CRUD
    // =====================================================================

    @Test
    @DisplayName("§3-1 GET 成员列表：OWNER 可见，含 user_id/username/role/status")
    void listMembers() throws Exception {
        ResponseEntity<String> response = get("/api/v1/teams/" + TEAM_ID + "/members", ownerToken());
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertEquals(4, data.size(), "应返回 4 名成员");
        JsonNode first = data.get(0);
        assertEquals(OWNER_ID, first.get("user_id").asLong());
        assertEquals("owner01", first.get("username").asText());
        assertEquals("OWNER", first.get("role").asText());
        assertEquals("ACTIVE", first.get("status").asText());
    }

    @Test
    @DisplayName("§3-1 POST 添加成员：成功；重复添加 TEAM-2005；第二名 OWNER → TEAM-2007")
    void addMember() throws Exception {
        String owner = ownerToken();

        ResponseEntity<String> added = post("/api/v1/teams/" + TEAM_ID + "/members",
                "{\"user_id\":" + OUTSIDER_ID + ",\"role\":\"OPERATOR\"}", owner);
        assertEquals(HttpStatus.OK, added.getStatusCode(), added.getBody());
        JsonNode data = objectMapper.readTree(added.getBody()).get("data");
        assertEquals(OUTSIDER_ID, data.get("user_id").asLong());
        assertEquals("OPERATOR", data.get("role").asText());
        assertEquals("ACTIVE", data.get("status").asText());

        // 重复添加（ALREADY ACTIVE）→ 409 TEAM-2005
        ResponseEntity<String> dup = post("/api/v1/teams/" + TEAM_ID + "/members",
                "{\"user_id\":" + OUTSIDER_ID + ",\"role\":\"VIEWER\"}", owner);
        assertEquals(HttpStatus.CONFLICT, dup.getStatusCode());
        assertEquals("TEAM-2005", code(dup));

        // 新增第二名 OWNER（团队已有 OWNER）→ 409 TEAM-2007（用另一个未入团用户，避免先命中 TEAM-2005）
        ResponseEntity<String> secondOwner = post("/api/v1/teams/" + TEAM_ID + "/members",
                "{\"user_id\":" + OUTSIDER2_ID + ",\"role\":\"OWNER\"}", owner);
        assertEquals(HttpStatus.CONFLICT, secondOwner.getStatusCode());
        assertEquals("TEAM-2007", code(secondOwner));
    }

    @Test
    @DisplayName("§3-1 PATCH 修改成员角色：成功；目标不存在 → TEAM-2004")
    void updateMemberRole() throws Exception {
        String owner = ownerToken();
        ResponseEntity<String> patched = patch("/api/v1/teams/" + TEAM_ID + "/members/" + OPERATOR_ID,
                "{\"role\":\"ADMIN\"}", owner);
        assertEquals(HttpStatus.OK, patched.getStatusCode(), patched.getBody());
        assertEquals("ADMIN", objectMapper.readTree(patched.getBody()).get("data").get("role").asText());
        assertEquals("ADMIN", jdbc.queryForObject(
                "SELECT role FROM team_members WHERE team_id=? AND user_id=?", String.class,
                TEAM_ID, OPERATOR_ID));

        ResponseEntity<String> missing = patch("/api/v1/teams/" + TEAM_ID + "/members/999999",
                "{\"role\":\"ADMIN\"}", owner);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("TEAM-2004", code(missing));
    }

    @Test
    @DisplayName("§3-2 禁止移除 OWNER：ADMIN 移除 OWNER → 403 TEAM-2006（且行未被改动）")
    void adminCannotRemoveOwner() throws Exception {
        ResponseEntity<String> response = delete("/api/v1/teams/" + TEAM_ID + "/members/" + OWNER_ID,
                adminToken());
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertEquals("TEAM-2006", code(response));
        // 行未被改动
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM team_members WHERE team_id=? AND user_id=?", String.class,
                TEAM_ID, OWNER_ID));
    }

    @Test
    @DisplayName("§3-2b 不能移除自己 → 403 TEAM-2009")
    void cannotRemoveSelf() throws Exception {
        ResponseEntity<String> response = delete("/api/v1/teams/" + TEAM_ID + "/members/" + ADMIN_ID,
                adminToken());
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("TEAM-2009", code(response));
    }

    @Test
    @DisplayName("§3-3 移出是软删：DELETE 后行仍在且 status='REMOVED'（非物理删除）")
    void removeIsSoftDelete() throws Exception {
        ResponseEntity<String> response = delete("/api/v1/teams/" + TEAM_ID + "/members/" + OPERATOR_ID,
                ownerToken());
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertEquals("REMOVED", objectMapper.readTree(response.getBody()).get("data").get("status").asText());

        // ★ 行仍在（软删而非物理删），且 status='REMOVED'、removed_at 非空
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id=? AND user_id=?", Integer.class,
                TEAM_ID, OPERATOR_ID);
        assertEquals(1, rows, "team_members 行必须保留（软删）");
        assertEquals("REMOVED", jdbc.queryForObject(
                "SELECT status FROM team_members WHERE team_id=? AND user_id=?", String.class,
                TEAM_ID, OPERATOR_ID));
        assertNotNull(jdbc.queryForObject(
                "SELECT removed_at FROM team_members WHERE team_id=? AND user_id=?", Object.class,
                TEAM_ID, OPERATOR_ID), "removed_at 应已写入");
    }

    // =====================================================================
    // §3-4 越权矩阵
    // =====================================================================

    @Test
    @DisplayName("§3-4 越权矩阵：VIEWER/OPERATOR 调成员管理 → 403 TEAM-2003（写与读都拒）")
    void memberManagementDeniedForNonManagers() throws Exception {
        for (String token : new String[]{viewerToken(), operatorToken()}) {
            assertEquals("TEAM-2003", code(get("/api/v1/teams/" + TEAM_ID + "/members", token)),
                    "成员列表应拒绝非管理角色");
            assertEquals("TEAM-2003", code(post("/api/v1/teams/" + TEAM_ID + "/members",
                    "{\"user_id\":" + OUTSIDER_ID + ",\"role\":\"VIEWER\"}", token)), "添加成员应被拒");
            assertEquals("TEAM-2003", code(patch("/api/v1/teams/" + TEAM_ID + "/members/" + ADMIN_ID,
                    "{\"role\":\"VIEWER\"}", token)), "改角色应被拒");
            assertEquals("TEAM-2003", code(delete("/api/v1/teams/" + TEAM_ID + "/members/" + ADMIN_ID,
                    token)), "移出成员应被拒");
            assertEquals("TEAM-2003", code(get("/api/v1/users", token)), "用户列表应被拒");
        }
    }

    // =====================================================================
    // §3-5 跨团队
    // =====================================================================

    @Test
    @DisplayName("§3-5 跨团队：{teamId} 与 JWT 不符 → 403 TEAM-2001（先于角色判定）")
    void crossTeamDenied() throws Exception {
        // 拿的是 team 7 的 OWNER token，却访问 team 8 → TEAM-2001（即使他是 team7 的 OWNER）
        assertEquals("TEAM-2001", code(get("/api/v1/teams/" + OTHER_TEAM_ID, ownerToken())));
        assertEquals("TEAM-2001", code(get("/api/v1/teams/" + OTHER_TEAM_ID + "/members", ownerToken())));
        assertEquals("TEAM-2001", code(patch("/api/v1/teams/" + OTHER_TEAM_ID,
                "{\"name\":\"越权改名\"}", ownerToken())));
    }

    // =====================================================================
    // §3-6 团队配置
    // =====================================================================

    @Test
    @DisplayName("§3-6 团队配置：GET/PATCH 生效；非管理角色 PATCH → TEAM-2003")
    void teamConfig() throws Exception {
        String owner = ownerToken();
        JsonNode before = objectMapper.readTree(get("/api/v1/teams/" + TEAM_ID, owner).getBody()).get("data");
        assertEquals(TEAM_ID, before.get("team_id").asLong());
        assertEquals("星海优选", before.get("team_name").asText());
        assertEquals("ACTIVE", before.get("status").asText());

        ResponseEntity<String> patched = patch("/api/v1/teams/" + TEAM_ID,
                "{\"name\":\"星海优选·改\",\"default_timezone\":\"Asia/Tokyo\"}", owner);
        assertEquals(HttpStatus.OK, patched.getStatusCode(), patched.getBody());
        JsonNode after = objectMapper.readTree(patched.getBody()).get("data");
        assertEquals("星海优选·改", after.get("team_name").asText());
        assertEquals("Asia/Tokyo", after.get("default_timezone").asText());

        // 非管理角色改配置 → TEAM-2003
        assertEquals("TEAM-2003", code(patch("/api/v1/teams/" + TEAM_ID, "{\"name\":\"x\"}", viewerToken())));
        // 任何已登录成员可读
        assertEquals(HttpStatus.OK, get("/api/v1/teams/" + TEAM_ID, viewerToken()).getStatusCode());
    }

    @Test
    @DisplayName("§3-6b 团队停用后相应接口 → 403 TEAM-2002")
    void disabledTeamDenied() throws Exception {
        String owner = ownerToken();
        jdbc.update("UPDATE teams SET status='DISABLED' WHERE id=?", TEAM_ID);

        assertEquals("TEAM-2002", code(get("/api/v1/teams/" + TEAM_ID, owner)));
        assertEquals("TEAM-2002", code(patch("/api/v1/teams/" + TEAM_ID, "{\"name\":\"x\"}", owner)));
        assertEquals("TEAM-2002", code(get("/api/v1/teams/" + TEAM_ID + "/members", owner)));
        assertEquals("TEAM-2002", code(get("/api/v1/users", owner)));
    }

    // =====================================================================
    // 团队列表 / 用户列表 / 契约
    // =====================================================================

    @Test
    @DisplayName("GET /teams：返回当前用户所属团队列表（含 role）")
    void listMyTeams() throws Exception {
        ResponseEntity<String> response = get("/api/v1/teams", ownerToken());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertEquals(1, data.size());
        assertEquals(TEAM_ID, data.get(0).get("team_id").asLong());
        assertEquals("星海优选", data.get(0).get("team_name").asText());
        assertEquals("OWNER", data.get(0).get("role").asText());
    }

    @Test
    @DisplayName("GET /users：OWNER 可取到用户列表（含 outsider）；VIEWER → TEAM-2003")
    void listUsers() throws Exception {
        ResponseEntity<String> response = get("/api/v1/users", ownerToken());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertTrue(data.size() >= 5, "至少 5 个用户");
        boolean hasOutsider = false;
        for (JsonNode u : data) {
            if (u.get("user_id").asLong() == OUTSIDER_ID) {
                hasOutsider = true;
                assertEquals("outsider01", u.get("username").asText());
            }
        }
        assertTrue(hasOutsider, "用户列表应包含未入团的 outsider01（供选人）");

        assertEquals("TEAM-2003", code(get("/api/v1/users", viewerToken())));
    }

    @Test
    @DisplayName("§3-9 契约一致性：requestId 回显 X-Request-Id；timestamp 带 Z；包络 code=OK")
    void contractEnvelope() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken());
        headers.set("X-Request-Id", "req_team_test_001");
        ResponseEntity<String> response = rest.exchange("/api/v1/teams/" + TEAM_ID, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("OK", body.get("code").asText());
        assertEquals("success", body.get("message").asText());
        assertEquals("req_team_test_001", body.get("requestId").asText());
        assertEquals("req_team_test_001", response.getHeaders().getFirst("X-Request-Id"));
        assertTrue(body.get("timestamp").asText().endsWith("Z"));
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private void insertUser(long id, String username, String displayName) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, displayName);
    }

    private String ownerToken() throws Exception {
        return loginAccessToken("owner01");
    }

    private String adminToken() throws Exception {
        return loginAccessToken("admin01");
    }

    private String operatorToken() throws Exception {
        return loginAccessToken("operator01");
    }

    private String viewerToken() throws Exception {
        return loginAccessToken("viewer01");
    }

    private String loginAccessToken(String username) throws Exception {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + username + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    private ResponseEntity<String> post(String path, String jsonBody, String bearer) {
        return rest.exchange(path, HttpMethod.POST, entity(jsonBody, bearer), String.class);
    }

    private ResponseEntity<String> patch(String path, String jsonBody, String bearer) {
        return rest.exchange(path, HttpMethod.PATCH, entity(jsonBody, bearer), String.class);
    }

    private ResponseEntity<String> delete(String path, String bearer) {
        return rest.exchange(path, HttpMethod.DELETE, entity(null, bearer), String.class);
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
