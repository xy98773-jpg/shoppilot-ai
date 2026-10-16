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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-T09 主数据 CRUD —— 端到端集成测试（真实 MySQL + 真实 Spring 上下文 + 真实 HTTP）。
 *
 * <p><b>真栈</b>：MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移 + {@code SecurityFilterChain}
 * + {@code JwtAuthenticationFilter} + 租户线 + 分页拦截器 + {@code @RequireRole} 切面。
 *
 * <p>覆盖任务书 §7 硬门槛：18 端点可用、角色矩阵（含 <b>APPROVER 写成功</b>、VIEWER 写 403）、
 * 跨团队 403 与不存在 404（对 4 资源 × GET/PATCH 一致）、{@code CAT-3002→409}（非 500）、
 * {@code CAT-3005}/{@code CAT-3004}、客户端 {@code team_id} 被忽略、PATCH 部分更新、
 * {@code pageSize>200 → COM-0007}、{@code category}/{@code keyword} 筛选、契约包络与小数当字符串。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CatalogCrudIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = com.shoppilot.TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = com.shoppilot.TestCredentials.PASSWORD_HASH;

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
    // §7-1 角色矩阵（含 APPROVER 写成功、VIEWER 写被拒）
    // =====================================================================

    @Test
    @DisplayName("§7-1 写角色矩阵：APPROVER/OPERATOR 建仓库 201；VIEWER 403 TEAM-2003")
    void writeRoleMatrix() throws Exception {
        assertEquals(HttpStatus.CREATED,
                post("/api/v1/warehouses", "{\"code\":\"W-APP\",\"name\":\"审批员仓\"}", approverToken())
                        .getStatusCode(), "APPROVER 属写角色（USER_ROLES §2.2 #2），必须可写");
        assertEquals(HttpStatus.CREATED,
                post("/api/v1/warehouses", "{\"code\":\"W-OP\",\"name\":\"操作员仓\"}", operatorToken())
                        .getStatusCode());

        ResponseEntity<String> denied = post("/api/v1/warehouses",
                "{\"code\":\"W-VIEW\",\"name\":\"观察仓\"}", viewerToken());
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("TEAM-2003", code(denied));
    }

    // =====================================================================
    // §7-2 跨团队 403 与不存在 404（对 4 资源 × GET/PATCH 一致）
    // =====================================================================

    @Test
    @DisplayName("§7-2 跨团队 → 403 TEAM-2001；不存在 → 404（CAT-3001/3003），GET/PATCH 一致")
    void crossTeamAndNotFound() throws Exception {
        // 用「别队 OWNER」在其团队造 4 类资源
        long wh8 = createAndGetId("/api/v1/warehouses", "{\"code\":\"W8\",\"name\":\"别队仓\"}", otherOwnerToken());
        long sup8 = createAndGetId("/api/v1/suppliers", "{\"code\":\"S8\",\"name\":\"别队供\"}", otherOwnerToken());
        long prod8 = createAndGetId("/api/v1/products",
                "{\"product_code\":\"P8\",\"name\":\"别队品\"}", otherOwnerToken());
        long sku8 = createAndGetId("/api/v1/skus",
                "{\"product_id\":" + prod8 + ",\"sku_code\":\"K8\",\"name\":\"别队SKU\"}", otherOwnerToken());

        String op = operatorToken();
        // ---- 详情：异团队 → 403 ----
        assertEquals("TEAM-2001", code(get("/api/v1/warehouses/" + wh8, op)));
        assertEquals("TEAM-2001", code(get("/api/v1/suppliers/" + sup8, op)));
        assertEquals("TEAM-2001", code(get("/api/v1/products/" + prod8, op)));
        assertEquals("TEAM-2001", code(get("/api/v1/skus/" + sku8, op)));

        // ---- 详情：不存在 → 404（CAT-3003 仓库；CAT-3001 其余）----
        assertEquals("CAT-3003", code(get("/api/v1/warehouses/999999", op)));
        assertEquals("CAT-3001", code(get("/api/v1/suppliers/999999", op)));
        assertEquals("CAT-3001", code(get("/api/v1/products/999999", op)));
        assertEquals("CAT-3001", code(get("/api/v1/skus/999999", op)));

        // ---- PATCH：异团队 → 403；不存在 → 404 ----
        assertEquals("TEAM-2001", code(patch("/api/v1/warehouses/" + wh8, "{\"name\":\"x\"}", op)));
        assertEquals("TEAM-2001", code(patch("/api/v1/products/" + prod8, "{\"name\":\"x\"}", op)));
        assertEquals("TEAM-2001", code(patch("/api/v1/suppliers/" + sup8, "{\"name\":\"x\"}", op)));
        assertEquals("TEAM-2001", code(patch("/api/v1/skus/" + sku8, "{\"name\":\"x\"}", op)));
        assertEquals("CAT-3003", code(patch("/api/v1/warehouses/999999", "{\"name\":\"x\"}", op)));
        assertEquals("CAT-3001", code(patch("/api/v1/products/999999", "{\"name\":\"x\"}", op)));
        assertEquals("CAT-3001", code(patch("/api/v1/suppliers/999999", "{\"name\":\"x\"}", op)));
        assertEquals("CAT-3001", code(patch("/api/v1/skus/999999", "{\"name\":\"x\"}", op)));
    }

    // =====================================================================
    // §7-3 唯一约束冲突 → 409 CAT-3002（不是 500）
    // =====================================================================

    @Test
    @DisplayName("§7-3 编码重复 → 409 CAT-3002（唯一约束翻译，非 500）")
    void duplicateCodeIsConflictNotServerError() throws Exception {
        String op = operatorToken();
        assertEquals(HttpStatus.CREATED, post("/api/v1/warehouses",
                "{\"code\":\"W-DUP\",\"name\":\"仓一\"}", op).getStatusCode());

        ResponseEntity<String> dup = post("/api/v1/warehouses", "{\"code\":\"W-DUP\",\"name\":\"仓二\"}", op);
        assertEquals(HttpStatus.CONFLICT, dup.getStatusCode(), "唯一约束冲突必须是 409，绝不能是 500");
        assertEquals("CAT-3002", code(dup));
        JsonNode details = objectMapper.readTree(dup.getBody()).get("details");
        assertNotNull(details, "重复编码应带 details[]");
        assertEquals("code", details.get(0).get("field").asText());
    }

    // =====================================================================
    // §7-4 客户端 team_id 被忽略（以 DB 为准）
    // =====================================================================

    @Test
    @DisplayName("§7-4 客户端传入 team_id 被忽略：落库 team_id 恒为 JWT 团队")
    void clientTeamIdIgnored() throws Exception {
        ResponseEntity<String> created = post("/api/v1/warehouses",
                "{\"code\":\"W-SNEAK\",\"name\":\"越权仓\",\"team_id\":" + OTHER_TEAM_ID + "}", operatorToken());
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        long id = objectMapper.readTree(created.getBody()).get("data").get("id").asLong();
        assertEquals(TEAM_ID, jdbc.queryForObject("SELECT team_id FROM warehouses WHERE id=?", Long.class, id),
                "客户端传的 team_id 必须被忽略，落库以 JWT 团队为准");
    }

    // =====================================================================
    // §7-5 PATCH 真·部分更新（未传字段不被覆盖 / null）
    // =====================================================================

    @Test
    @DisplayName("§7-5 PATCH 部分更新：只改传入字段，其余保持不变")
    void patchIsTruePartialUpdate() throws Exception {
        long id = createAndGetId("/api/v1/products",
                "{\"product_code\":\"P-PART\",\"name\":\"原名\",\"category\":\"箱包\",\"brand\":\"UPack\",\"unit\":\"个\"}",
                operatorToken());
        ResponseEntity<String> patched = patch("/api/v1/products/" + id, "{\"name\":\"新名\"}", operatorToken());
        assertEquals(HttpStatus.OK, patched.getStatusCode(), patched.getBody());
        JsonNode data = objectMapper.readTree(patched.getBody()).get("data");
        assertEquals("新名", data.get("name").asText());
        assertEquals("箱包", data.get("category").asText(), "未传 category 必须保留");
        assertEquals("UPack", data.get("brand").asText(), "未传 brand 必须保留");
        assertEquals("个", data.get("unit").asText(), "未传 unit 必须保留");
    }

    // =====================================================================
    // §7-6 关联同团队 CAT-3005；product_id 不匹配 CAT-3004；缺 product_id COM-0001
    // =====================================================================

    @Test
    @DisplayName("§7-6 关联校验：default_supplier_id 跨团队 → 403 CAT-3005；product_id 路径不一致 → 422 CAT-3004")
    void associationChecks() throws Exception {
        String op = operatorToken();
        long sup8 = createAndGetId("/api/v1/suppliers", "{\"code\":\"S8B\",\"name\":\"别队供\"}", otherOwnerToken());
        long prod7 = createAndGetId("/api/v1/products", "{\"product_code\":\"P7A\",\"name\":\"本品\"}", op);
        long prod8 = createAndGetId("/api/v1/products", "{\"product_code\":\"P8B\",\"name\":\"别队品\"}", otherOwnerToken());

        // default_supplier_id 指向别队供应商 → 403 CAT-3005
        ResponseEntity<String> crossSupplier = post("/api/v1/products",
                "{\"product_code\":\"P-CROSS\",\"name\":\"跨团队供\",\"default_supplier_id\":" + sup8 + "}", op);
        assertEquals(HttpStatus.FORBIDDEN, crossSupplier.getStatusCode(), crossSupplier.getBody());
        assertEquals("CAT-3005", code(crossSupplier));

        // 不存在的供应商同样 → CAT-3005（任务书 §3.3）
        assertEquals("CAT-3005", code(post("/api/v1/products",
                "{\"product_code\":\"P-GHOST\",\"name\":\"幽灵供\",\"default_supplier_id\":999999}", op)));

        // 路径 product 与请求体 product_id 不一致 → 422 CAT-3004
        ResponseEntity<String> mismatch = post("/api/v1/products/" + prod7 + "/skus",
                "{\"product_id\":" + prod8 + ",\"sku_code\":\"K-MM\",\"name\":\"错配SKU\"}", op);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, mismatch.getStatusCode(), mismatch.getBody());
        assertEquals("CAT-3004", code(mismatch));

        // 独立 POST /skus 缺 product_id → 400 COM-0001
        ResponseEntity<String> missing = post("/api/v1/skus",
                "{\"sku_code\":\"K-NOP\",\"name\":\"无商品\"}", op);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals("COM-0001", code(missing));

        // 独立 POST /skus 带不存在 / 跨团队的 product_id → 422 CAT-3004（§7-6）
        ResponseEntity<String> ghost = post("/api/v1/skus",
                "{\"product_id\":999999,\"sku_code\":\"K-GHOST\",\"name\":\"幽灵品\"}", op);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ghost.getStatusCode(), ghost.getBody());
        assertEquals("CAT-3004", code(ghost));

        ResponseEntity<String> crossProd = post("/api/v1/skus",
                "{\"product_id\":" + prod8 + ",\"sku_code\":\"K-CROSS\",\"name\":\"跨团队品\"}", op);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, crossProd.getStatusCode(), crossProd.getBody());
        assertEquals("CAT-3004", code(crossProd));
    }

    // =====================================================================
    // §7-7 分页 / 排序（pageSize>200 → COM-0007；非法 sort → COM-0007；分页元信息齐全）
    // =====================================================================

    @Test
    @DisplayName("§7-7 分页：pageSize=201 → 400 COM-0007；非法 sort → 400 COM-0007；正常分页含 list/page/pageSize/total")
    void paginationRules() throws Exception {
        String op = operatorToken();
        for (int i = 0; i < 3; i++) {
            post("/api/v1/warehouses", "{\"code\":\"W-PG-" + i + "\",\"name\":\"仓" + i + "\"}", op);
        }

        ResponseEntity<String> tooBig = get("/api/v1/warehouses?pageSize=201", op);
        assertEquals(HttpStatus.BAD_REQUEST, tooBig.getStatusCode());
        assertEquals("COM-0007", code(tooBig));

        assertEquals("COM-0007", code(get("/api/v1/warehouses?sort=bogus_field,desc", op)));

        ResponseEntity<String> ok = get("/api/v1/warehouses?page=1&pageSize=2&sort=code,asc", op);
        assertEquals(HttpStatus.OK, ok.getStatusCode(), ok.getBody());
        JsonNode data = objectMapper.readTree(ok.getBody()).get("data");
        assertEquals(2, data.get("list").size(), "pageSize=2 应返回 2 条");
        assertEquals(1, data.get("page").asInt());
        assertEquals(2, data.get("pageSize").asInt());
        assertEquals(3, data.get("total").asInt());
    }

    // =====================================================================
    // §7-8 筛选：products?category= / skus?keyword=
    // =====================================================================

    @Test
    @DisplayName("§7-8 筛选：GET /products?category= 与 GET /skus?keyword= 生效")
    void filters() throws Exception {
        String op = operatorToken();
        createAndGetId("/api/v1/products",
                "{\"product_code\":\"P-CAT-A\",\"name\":\"箱包A\",\"category\":\"箱包\"}", op);
        createAndGetId("/api/v1/products",
                "{\"product_code\":\"P-CAT-B\",\"name\":\"鞋服B\",\"category\":\"鞋服\"}", op);

        JsonNode byCat = objectMapper.readTree(get("/api/v1/products?category=箱包", op).getBody()).get("data");
        assertEquals(1, byCat.get("list").size(), "category=箱包 应只返回 1 条");
        assertEquals("箱包", byCat.get("list").get(0).get("category").asText());

        long prod = createAndGetId("/api/v1/products", "{\"product_code\":\"P-KW\",\"name\":\"关键字品\"}", op);
        createAndGetId("/api/v1/skus",
                "{\"product_id\":" + prod + ",\"sku_code\":\"SKU-ALPHA-001\",\"name\":\"阿尔法\"}", op);
        createAndGetId("/api/v1/skus",
                "{\"product_id\":" + prod + ",\"sku_code\":\"SKU-BETA-002\",\"name\":\"贝塔\"}", op);

        JsonNode byKw = objectMapper.readTree(get("/api/v1/skus?keyword=ALPHA", op).getBody()).get("data");
        assertEquals(1, byKw.get("list").size(), "keyword=ALPHA 应只匹配 1 条");
        assertEquals("SKU-ALPHA-001", byKw.get("list").get(0).get("sku_code").asText());
    }

    // =====================================================================
    // §7-9 商品下 SKU 子资源 + 独立 SKU 创建
    // =====================================================================

    @Test
    @DisplayName("§7-9 SKU 双入口：POST /products/{id}/skus 与 POST /skus 均可；原样返回商品/SKU")
    void skuUnderProductAndStandalone() throws Exception {
        String op = operatorToken();
        long prod = createAndGetId("/api/v1/products", "{\"product_code\":\"P-SKU\",\"name\":\"SKU品\"}", op);

        long s1 = createAndGetId("/api/v1/products/" + prod + "/skus",
                "{\"sku_code\":\"SKU-NEST\",\"name\":\"嵌套建\",\"moq\":\"50.000\"}", op);
        long s2 = createAndGetId("/api/v1/skus",
                "{\"product_id\":" + prod + ",\"sku_code\":\"SKU-FLAT\",\"name\":\"独立建\"}", op);

        JsonNode listed = objectMapper.readTree(get("/api/v1/products/" + prod + "/skus", op).getBody()).get("data");
        assertEquals(2, listed.get("list").size(), "商品下应列出 2 个 SKU");

        // 小数按字符串输出且保留标度
        JsonNode s1data = objectMapper.readTree(get("/api/v1/skus/" + s1, op).getBody()).get("data");
        assertTrue(s1data.get("moq").isTextual(), "moq 必须以字符串传输");
        assertEquals("50.000", s1data.get("moq").asText(), "DECIMAL(18,3) 标度必须保留");
        assertEquals(TEAM_ID, s1data.get("team_id").asLong(), "SKU 响应含 team_id 且等于 JWT 团队");
        assertNotEquals(s1, s2);
    }

    // =====================================================================
    // §7-10 供应商配置校验 CAT-3011
    // =====================================================================

    @Test
    @DisplayName("§7-10 CAT-3011：lead_time_days<0 或 order_multiple<=0 → 400")
    void supplierConfigInvalid() throws Exception {
        String op = operatorToken();
        assertEquals("CAT-3011", code(post("/api/v1/suppliers",
                "{\"code\":\"S-NEG\",\"name\":\"负交期\",\"lead_time_days\":-1}", op)));
        assertEquals("CAT-3011", code(post("/api/v1/suppliers",
                "{\"code\":\"S-ZERO\",\"name\":\"零倍数\",\"order_multiple\":\"0.000\"}", op)));
    }

    // =====================================================================
    // §7-11 契约包络：requestId 回显、timestamp 带 Z、created_at 带 Z、code=OK
    // =====================================================================

    @Test
    @DisplayName("§7-11 契约包络：requestId 回显 X-Request-Id；timestamp/created_at 带 Z；code=OK")
    void contractEnvelope() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(operatorToken());
        headers.set("X-Request-Id", "req_cat_test_001");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = rest.exchange("/api/v1/warehouses", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"W-ENV\",\"name\":\"包络仓\"}", headers), String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());

        JsonNode body = objectMapper.readTree(created.getBody());
        assertEquals("OK", body.get("code").asText());
        assertEquals("success", body.get("message").asText());
        assertEquals("req_cat_test_001", body.get("requestId").asText());
        assertEquals("req_cat_test_001", created.getHeaders().getFirst("X-Request-Id"));
        assertTrue(body.get("timestamp").asText().endsWith("Z"), "timestamp 应带 Z");
        assertTrue(body.get("data").get("created_at").asText().endsWith("Z"), "created_at 应带 Z");
        assertEquals("ACTIVE", body.get("data").get("status").asText(), "新建主数据默认 ACTIVE");
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

    private String ownerToken() throws Exception {
        return loginAccessToken("owner01");
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
