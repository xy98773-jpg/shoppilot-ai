package com.shoppilot.catalog;

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
 * M2-T10 主数据启停 —— 端到端集成测试（真实 MySQL + 真实 Spring 上下文 + 真实 HTTP）。
 *
 * <p><b>真栈</b>：MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移 + {@code SecurityFilterChain}
 * + {@code JwtAuthenticationFilter} + 租户线 + {@code @RequireRole} 切面。
 *
 * <p>覆盖任务书 §7 硬门槛：6 端点（4×DELETE 软删 + warehouses/skus 的 PATCH status）可用、
 * 角色矩阵（<b>APPROVER 成功</b>、VIEWER 403 TEAM-2003）、跨团队 403 TEAM-2001 与不存在 404
 * （六端点一致）、软删<b>行仍在</b>、幂等 200、{@code CAT-3009} 正反两例、
 * {@code PATCH .../status} 入参校验（INACTIVE/ACTIVE/BOGUS/缺失）、默认仓 {@code is_default} 置 0、
 * 范围红线（suppliers/products 无 {@code /status} → 404 COM-0003）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CatalogStatusIntegrationTest {

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
    private static final long APPROVER_ID = 1008L;
    private static final long OTHER_OWNER_ID = 2004L;

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
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
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
        insertUser(APPROVER_ID, "approver01", "审批员");
        insertUser(OTHER_OWNER_ID, "owner8", "别队老板");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'ADMIN', 'ACTIVE'), "
                        + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), "
                        + "(?, ?, 'APPROVER', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, ADMIN_ID, TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID,
                TEAM_ID, APPROVER_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);
    }

    // =====================================================================
    // §7-1 / §7-2 6 端点可用 + 角色矩阵（APPROVER 成功、VIEWER 403 TEAM-2003）
    // =====================================================================

    @Test
    @DisplayName("§7-1/2 角色矩阵：APPROVER/OPERATOR 启停成功 200；VIEWER 403 TEAM-2003（六端点）")
    void writeRoleMatrix() throws Exception {
        long wh = createAndGetId("/api/v1/warehouses", "{\"code\":\"W-RM\",\"name\":\"角色仓\"}", operatorToken());
        long sup = createAndGetId("/api/v1/suppliers", "{\"code\":\"S-RM\",\"name\":\"角色供\"}", operatorToken());
        long prod = createAndGetId("/api/v1/products", "{\"product_code\":\"P-RM\",\"name\":\"角色品\"}", operatorToken());
        long sku = createAndGetId("/api/v1/skus",
                "{\"product_id\":" + prod + ",\"sku_code\":\"K-RM\",\"name\":\"角色SKU\"}", operatorToken());

        String ap = approverToken();
        // APPROVER 属写角色（USER_ROLES §2.2 #2），6 端点全部可执行
        assertEquals(HttpStatus.OK, delete("/api/v1/warehouses/" + wh, ap).getStatusCode(), "APPROVER 停用仓库");
        assertEquals(HttpStatus.OK,
                patch("/api/v1/warehouses/" + wh + "/status", "{\"status\":\"ACTIVE\"}", ap).getStatusCode(),
                "APPROVER 启用仓库");
        assertEquals(HttpStatus.OK, delete("/api/v1/suppliers/" + sup, ap).getStatusCode(), "APPROVER 停用供应商");
        assertEquals(HttpStatus.OK, delete("/api/v1/skus/" + sku, ap).getStatusCode(), "APPROVER 停用 SKU");
        assertEquals(HttpStatus.OK, delete("/api/v1/products/" + prod, ap).getStatusCode(), "APPROVER 停用商品");

        // OPERATOR 同样可执行
        long wh2 = createAndGetId("/api/v1/warehouses", "{\"code\":\"W-OP2\",\"name\":\"操作仓\"}", operatorToken());
        assertEquals(HttpStatus.OK, delete("/api/v1/warehouses/" + wh2, operatorToken()).getStatusCode());

        // VIEWER → 任一启停 → 403 TEAM-2003
        long wh3 = createAndGetId("/api/v1/warehouses", "{\"code\":\"W-VW\",\"name\":\"观察仓\"}", operatorToken());
        long sup3 = createAndGetId("/api/v1/suppliers", "{\"code\":\"S-VW\",\"name\":\"观察供\"}", operatorToken());
        long prod3 = createAndGetId("/api/v1/products", "{\"product_code\":\"P-VW\",\"name\":\"观察品\"}", operatorToken());
        long sku3 = createAndGetId("/api/v1/skus",
                "{\"product_id\":" + prod3 + ",\"sku_code\":\"K-VW\",\"name\":\"观察SKU\"}", operatorToken());
        String vw = viewerToken();

        ResponseEntity<String> d1 = delete("/api/v1/warehouses/" + wh3, vw);
        assertEquals(HttpStatus.FORBIDDEN, d1.getStatusCode());
        assertEquals("TEAM-2003", code(d1), "VIEWER DELETE 仓库必须 403 TEAM-2003");
        assertEquals("TEAM-2003", code(patch("/api/v1/warehouses/" + wh3 + "/status", "{\"status\":\"INACTIVE\"}", vw)));
        assertEquals("TEAM-2003", code(delete("/api/v1/suppliers/" + sup3, vw)));
        assertEquals("TEAM-2003", code(delete("/api/v1/products/" + prod3, vw)));
        assertEquals("TEAM-2003", code(delete("/api/v1/skus/" + sku3, vw)));
        assertEquals("TEAM-2003", code(patch("/api/v1/skus/" + sku3 + "/status", "{\"status\":\"INACTIVE\"}", vw)));
    }

    // =====================================================================
    // §7-3 跨团队 403 TEAM-2001 与不存在 404（六端点一致）
    // =====================================================================

    @Test
    @DisplayName("§7-3 跨团队真实资源 → 403 TEAM-2001；id 不存在 → 404（CAT-3003/3001），六端点一致")
    void crossTeamAndNotFound() throws Exception {
        String oo = otherOwnerToken();
        long wh8 = createAndGetId("/api/v1/warehouses", "{\"code\":\"W8\",\"name\":\"别队仓\"}", oo);
        long sup8 = createAndGetId("/api/v1/suppliers", "{\"code\":\"S8\",\"name\":\"别队供\"}", oo);
        long prod8 = createAndGetId("/api/v1/products", "{\"product_code\":\"P8\",\"name\":\"别队品\"}", oo);
        long sku8 = createAndGetId("/api/v1/skus",
                "{\"product_id\":" + prod8 + ",\"sku_code\":\"K8\",\"name\":\"别队SKU\"}", oo);

        String op = operatorToken();
        // 六端点：跨团队真实资源 → 403 TEAM-2001
        assertEquals("TEAM-2001", code(delete("/api/v1/warehouses/" + wh8, op)));
        assertEquals("TEAM-2001", code(patch("/api/v1/warehouses/" + wh8 + "/status", "{\"status\":\"INACTIVE\"}", op)));
        assertEquals("TEAM-2001", code(delete("/api/v1/suppliers/" + sup8, op)));
        assertEquals("TEAM-2001", code(delete("/api/v1/products/" + prod8, op)));
        assertEquals("TEAM-2001", code(delete("/api/v1/skus/" + sku8, op)));
        assertEquals("TEAM-2001", code(patch("/api/v1/skus/" + sku8 + "/status", "{\"status\":\"INACTIVE\"}", op)));

        // 六端点：id 不存在 → 404（仓库 CAT-3003；其余 CAT-3001）
        assertEquals("CAT-3003", code(delete("/api/v1/warehouses/999999", op)));
        assertEquals("CAT-3003", code(patch("/api/v1/warehouses/999999/status", "{\"status\":\"INACTIVE\"}", op)));
        assertEquals("CAT-3001", code(delete("/api/v1/suppliers/999999", op)));
        assertEquals("CAT-3001", code(delete("/api/v1/products/999999", op)));
        assertEquals("CAT-3001", code(delete("/api/v1/skus/999999", op)));
        assertEquals("CAT-3001", code(patch("/api/v1/skus/999999/status", "{\"status\":\"INACTIVE\"}", op)));
    }

    // =====================================================================
    // §7-4 / §7-5 软删（行仍在）+ 幂等 200
    // =====================================================================

    @Test
    @DisplayName("§7-4/5 软删：status=INACTIVE 且物理行保留；重复 DELETE 仍 200（幂等）")
    void softDeletePersistsRowAndIsIdempotent() throws Exception {
        String op = operatorToken();
        long wh = createAndGetId("/api/v1/warehouses", "{\"code\":\"W-SD\",\"name\":\"软删仓\"}", op);

        ResponseEntity<String> first = delete("/api/v1/warehouses/" + wh, op);
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        assertEquals("INACTIVE", objectMapper.readTree(first.getBody()).get("data").get("status").asText());

        // 行仍在（软删，非物理删除）
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE id=?", Integer.class, wh);
        assertEquals(1, rows, "DELETE 必须软删：物理行仍保留");
        assertEquals("INACTIVE", jdbc.queryForObject("SELECT status FROM warehouses WHERE id=?", String.class, wh));

        // 幂等：对已 INACTIVE 对象再次 DELETE → 仍 200，不报 409/422
        ResponseEntity<String> second = delete("/api/v1/warehouses/" + wh, op);
        assertEquals(HttpStatus.OK, second.getStatusCode(), "重复 DELETE 必须 200（幂等）：" + second.getBody());
    }

    // =====================================================================
    // §7-6 CAT-3009：商品下有 ACTIVE SKU → 422；停用 SKU 后 → 200
    // =====================================================================

    @Test
    @DisplayName("§7-6 CAT-3009：商品含 ACTIVE SKU 时停用 → 422；先停 SKU 再停商品 → 200")
    void productWithActiveSkuCannotDeactivate() throws Exception {
        String op = operatorToken();
        long prod = createAndGetId("/api/v1/products", "{\"product_code\":\"P-3009\",\"name\":\"有SKU商品\"}", op);
        long sku = createAndGetId("/api/v1/skus",
                "{\"product_id\":" + prod + ",\"sku_code\":\"K-3009\",\"name\":\"启用SKU\"}", op);

        // 反例：商品下存在 ACTIVE SKU → 停用商品 → 422 CAT-3009
        ResponseEntity<String> blocked = delete("/api/v1/products/" + prod, op);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, blocked.getStatusCode(), blocked.getBody());
        assertEquals("CAT-3009", code(blocked));
        // 商品未被停用
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM products WHERE id=?", String.class, prod));

        // 先停用该 SKU，再停用商品 → 200（证明按状态判定，非恒拒）
        assertEquals(HttpStatus.OK, delete("/api/v1/skus/" + sku, op).getStatusCode());
        ResponseEntity<String> ok = delete("/api/v1/products/" + prod, op);
        assertEquals(HttpStatus.OK, ok.getStatusCode(), ok.getBody());
        assertEquals("INACTIVE", objectMapper.readTree(ok.getBody()).get("data").get("status").asText());
    }

    // =====================================================================
    // §7-7 PATCH .../status：INACTIVE / ACTIVE / BOGUS / 缺失
    // =====================================================================

    @Test
    @DisplayName("§7-7 PATCH status：INACTIVE 落库；ACTIVE 可重新启用；BOGUS 与缺 status → 400 COM-0001")
    void patchStatusValidation() throws Exception {
        String op = operatorToken();
        long wh = createAndGetId("/api/v1/warehouses", "{\"code\":\"W-ST\",\"name\":\"状态仓\"}", op);

        ResponseEntity<String> off = patch("/api/v1/warehouses/" + wh + "/status", "{\"status\":\"INACTIVE\"}", op);
        assertEquals(HttpStatus.OK, off.getStatusCode(), off.getBody());
        assertEquals("INACTIVE", jdbc.queryForObject("SELECT status FROM warehouses WHERE id=?", String.class, wh));

        ResponseEntity<String> on = patch("/api/v1/warehouses/" + wh + "/status", "{\"status\":\"ACTIVE\"}", op);
        assertEquals(HttpStatus.OK, on.getStatusCode(), on.getBody());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM warehouses WHERE id=?", String.class, wh),
                "应支持重新启用");

        // 非法枚举 → 400 COM-0001 + details[]
        ResponseEntity<String> bogus = patch("/api/v1/warehouses/" + wh + "/status", "{\"status\":\"BOGUS\"}", op);
        assertEquals(HttpStatus.BAD_REQUEST, bogus.getStatusCode(), bogus.getBody());
        assertEquals("COM-0001", code(bogus));
        JsonNode details = objectMapper.readTree(bogus.getBody()).get("details");
        assertNotNull(details, "非法 status 应带 details[]");
        assertEquals("status", details.get(0).get("field").asText());

        // 缺失 status → 400 COM-0001
        ResponseEntity<String> missing = patch("/api/v1/warehouses/" + wh + "/status", "{}", op);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode(), missing.getBody());
        assertEquals("COM-0001", code(missing));

        // SKU 的 PATCH status 同样生效
        long prod = createAndGetId("/api/v1/products", "{\"product_code\":\"P-KS\",\"name\":\"SKU状态品\"}", op);
        long sku = createAndGetId("/api/v1/skus",
                "{\"product_id\":" + prod + ",\"sku_code\":\"K-KS\",\"name\":\"SKU状态\"}", op);
        assertEquals(HttpStatus.OK,
                patch("/api/v1/skus/" + sku + "/status", "{\"status\":\"INACTIVE\"}", op).getStatusCode());
        assertEquals("INACTIVE", jdbc.queryForObject("SELECT status FROM skus WHERE id=?", String.class, sku));
    }

    // =====================================================================
    // §7-8 默认仓边界：停用 is_default=1 仓库 → is_default 置 0 且 status=INACTIVE
    // =====================================================================

    @Test
    @DisplayName("§7-8 默认仓边界：停用默认仓 → is_default=0 且 status=INACTIVE（同事务）")
    void deactivateDefaultWarehouseClearsFlag() throws Exception {
        String op = operatorToken();
        long wh = createAndGetId("/api/v1/warehouses",
                "{\"code\":\"W-DEF\",\"name\":\"默认仓\",\"is_default\":true}", op);
        assertEquals(Boolean.TRUE,
                jdbc.queryForObject("SELECT is_default FROM warehouses WHERE id=?", Boolean.class, wh),
                "创建时 is_default=true 应落库");

        ResponseEntity<String> deleted = delete("/api/v1/warehouses/" + wh, op);
        assertEquals(HttpStatus.OK, deleted.getStatusCode(), deleted.getBody());

        assertEquals("INACTIVE", jdbc.queryForObject("SELECT status FROM warehouses WHERE id=?", String.class, wh));
        assertEquals(Boolean.FALSE,
                jdbc.queryForObject("SELECT is_default FROM warehouses WHERE id=?", Boolean.class, wh),
                "停用默认仓须同事务把 is_default 置 0（任务书 §3-4）");
    }

    // =====================================================================
    // §7-9 范围红线：suppliers/products 无 /status 端点 → 404 COM-0003
    // =====================================================================

    @Test
    @DisplayName("§7-9 范围：PATCH /suppliers/{id}/status 与 /products/{id}/status 均 404 COM-0003（契约无此路径）")
    void noStatusEndpointForSupplierOrProduct() throws Exception {
        String op = operatorToken();
        long sup = createAndGetId("/api/v1/suppliers", "{\"code\":\"S-NS\",\"name\":\"无状态供\"}", op);
        long prod = createAndGetId("/api/v1/products", "{\"product_code\":\"P-NS\",\"name\":\"无状态品\"}", op);

        ResponseEntity<String> supStatus = patch("/api/v1/suppliers/" + sup + "/status", "{\"status\":\"INACTIVE\"}", op);
        assertEquals(HttpStatus.NOT_FOUND, supStatus.getStatusCode(), supStatus.getBody());
        assertEquals("COM-0003", code(supStatus));

        ResponseEntity<String> prodStatus = patch("/api/v1/products/" + prod + "/status", "{\"status\":\"INACTIVE\"}", op);
        assertEquals(HttpStatus.NOT_FOUND, prodStatus.getStatusCode(), prodStatus.getBody());
        assertEquals("COM-0003", code(prodStatus));
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private void insertUser(long id, String username, String displayName) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, displayName);
    }

    private long createAndGetId(String path, String jsonBody, String bearer) throws Exception {
        ResponseEntity<String> response = post(path, jsonBody, bearer);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), "创建应返回 201：" + path + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("id").asLong();
    }

    private String operatorToken() throws Exception {
        return loginAccessToken("operator01");
    }

    private String viewerToken() throws Exception {
        return loginAccessToken("viewer01");
    }

    private String approverToken() throws Exception {
        return loginAccessToken("approver01");
    }

    private String otherOwnerToken() throws Exception {
        return loginAccessToken("owner8");
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
        String body = response.getBody();
        assertNotNull(body, "响应体不应为空（HTTP " + response.getStatusCode() + "）");
        return objectMapper.readTree(body).get("code").asText();
    }
}
