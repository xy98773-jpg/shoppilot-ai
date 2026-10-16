package com.shoppilot.app.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
 * M2-T13 §1-A：M2 越权矩阵（参数化，核心）—— 端到端集成测试。
 *
 * <h2>权威依据</h2>
 * <ul>
 *   <li>{@code USER_ROLES.md §2.2}（权限矩阵唯一权威）—— 角色以<b>角色名</b>直查，禁 ordinal / 继承链；</li>
 *   <li>{@code TEST_STRATEGY.md §6.2}（角色 × 关键操作矩阵，原文注明「本矩阵用于驱动参数化测试」）；</li>
 *   <li>{@code TEST_STRATEGY.md §6.1}（跨团队 5 场景）；</li>
 *   <li>{@code USER_ROLES.md §4.1}（判定顺序：认证 → 用户状态 → 团队归属 → <b>团队状态</b> → 角色授权）。</li>
 * </ul>
 *
 * <h2>本类覆盖的是「M2 可用操作子集」</h2>
 * 只取 M2 已落地的端点（identity 成员/配置 + catalog 四类主数据）。矩阵中
 * 「查看审计」「手工调整」「审批」「采购」「到货」等行属 M4/M6，<b>本批不测也不「补全」</b>。
 *
 * <h2>与既有用例的关系（不重复声明）</h2>
 * 既有 {@code TeamManagementIntegrationTest} §3-4/§5-1/§5-2、{@code CatalogCrudIntegrationTest} §7-1/§7-2、
 * {@code CatalogStatusIntegrationTest} §7-1/§7-3 已以<b>散点</b>形式覆盖部分格子。本类以<b>穷举参数化</b>
 * 重述该子集以形成可机械核验的矩阵；<b>其中与既有重叠的格子仅作参数化回归，不计入本批新增覆盖</b>
 * （逐格对照见 M2-T13 交付汇报 §0）。真正的新增格：APPROVER 全操作、OWNER/ADMIN 主数据写、
 * 「团队停用 × 非管理角色 → TEAM-2002」（顺序证明）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class M2_AuthorizationMatrixIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 测试专用口令（仅存在于测试进程；真实口令由环境变量注入，不入仓）。 */
    private static final String RAW_PASSWORD = "S3cret!Pass";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(RAW_PASSWORD);

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;

    private static final long OWNER_ID = 1001L;
    private static final long ADMIN_ID = 1002L;
    private static final long OPERATOR_ID = 1003L;
    private static final long APPROVER_ID = 1004L;
    private static final long VIEWER_ID = 1005L;
    /** 未入团用户，供「添加成员」成功路径消耗（每用例前重置种子，故可复用同一 id）。 */
    private static final long SPARE_USER_ID = 1006L;
    /** 异团队（team 8）的 OWNER，用于构造「跨团队真实资源」。 */
    private static final long OTHER_TEAM_OWNER_ID = 2001L;

    private static final String OK = "OK";
    private static final String TEAM_2001 = "TEAM-2001";
    private static final String TEAM_2002 = "TEAM-2002";
    private static final String TEAM_2003 = "TEAM-2003";

    /** M2 权威角色全集（{@code USER_ROLES §1.1}）。 */
    private static final List<String> M2_ROLES = List.of("OWNER", "ADMIN", "OPERATOR", "APPROVER", "VIEWER");

    /** 用户名 → Access Token 缓存（账号 id 在各用例间稳定，故可跨用例复用，避免 45 次 BCrypt）。 */
    private static final Map<String, String> TOKEN_CACHE = new ConcurrentHashMap<>();

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

    /** team 8 的仓库 id（跨团队资源探测目标），每用例前重建。 */
    private long otherTeamWarehouseId;

    /** team 7 的种子商品 id（SKU 创建需引用）。 */
    private long seedProductId;

    @BeforeEach
    void resetAndSeed() {
        // 清表顺序：先子后父（catalog 四表外键全 RESTRICT）
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                        + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                        + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')",
                TEAM_ID, OTHER_TEAM_ID);

        insertUser(OWNER_ID, "owner01", "老板");
        insertUser(ADMIN_ID, "admin01", "主管");
        insertUser(OPERATOR_ID, "operator01", "小王");
        insertUser(APPROVER_ID, "approver01", "审批人");
        insertUser(VIEWER_ID, "viewer01", "观察者");
        insertUser(SPARE_USER_ID, "spare01", "备用成员");
        insertUser(OTHER_TEAM_OWNER_ID, "other-owner01", "别队老板");

        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'ADMIN', 'ACTIVE'), "
                        + "(?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'APPROVER', 'ACTIVE'), "
                        + "(?, ?, 'VIEWER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, ADMIN_ID, TEAM_ID, OPERATOR_ID,
                TEAM_ID, APPROVER_ID, TEAM_ID, VIEWER_ID);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE')",
                OTHER_TEAM_ID, OTHER_TEAM_OWNER_ID);

        // 种子主数据：team 7 一个商品（供 SKU 创建）；team 8 一个仓库（供跨团队读取）
        jdbc.update("INSERT INTO products (team_id, product_code, name) VALUES (?, 'PRD-SEED-T13', '种子商品')",
                TEAM_ID);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE team_id = ? AND product_code = 'PRD-SEED-T13'", Long.class, TEAM_ID);
        assertNotNull(productId, "种子商品应已插入");
        seedProductId = productId;

        jdbc.update("INSERT INTO warehouses (team_id, code, name) VALUES (?, 'WH-OTHER-T13', '别队仓库')",
                OTHER_TEAM_ID);
        Long whId = jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE team_id = ? AND code = 'WH-OTHER-T13'", Long.class, OTHER_TEAM_ID);
        assertNotNull(whId, "异团队仓库应已插入");
        otherTeamWarehouseId = whId;
    }

    // =====================================================================
    // §1-A 主矩阵：角色 × 操作 → 期望码（TEST_STRATEGY §6.2 的 M2 子集）
    // =====================================================================

    /**
     * 构建 M2 越权矩阵。
     *
     * <p>每行 = {@code (角色, 操作, 期望码)}，共 {@code 5 角色 × 9 操作 = 45} 格。
     * 期望值来源：{@code TEST_STRATEGY §6.2}（管理团队成员 / 管理团队配置 / 维护主数据 /
     * 跨团队任意操作）+ {@code §6.1 #1}（路径越权 → 跨团队）。
     */
    static Stream<Arguments> authorizationMatrix() {
        List<Arguments> rows = new ArrayList<>();
        for (String role : M2_ROLES) {
            boolean manager = "OWNER".equals(role) || "ADMIN".equals(role);
            // 管理团队成员（§6.2：OWNER ✅ / ADMIN ✅（不能移除 OWNER）/ 其余 ⛔ TEAM-2003）
            rows.add(row(role, Op.MEMBERS_LIST, manager ? OK : TEAM_2003));
            rows.add(row(role, Op.MEMBERS_ADD, manager ? OK : TEAM_2003));
            // 管理团队配置（§6.2：OWNER ✅ / ADMIN ✅ / 其余 ⛔ TEAM-2003）
            rows.add(row(role, Op.TEAM_CONFIG_UPDATE, manager ? OK : TEAM_2003));
            // 维护主数据（§6.2：OWNER·ADMIN·OPERATOR·APPROVER ✅ / VIEWER ⛔ TEAM-2003）
            boolean canWriteCatalog = !"VIEWER".equals(role);
            for (Op op : List.of(Op.WAREHOUSE_CREATE, Op.SUPPLIER_CREATE, Op.PRODUCT_CREATE, Op.SKU_CREATE)) {
                rows.add(row(role, op, canWriteCatalog ? OK : TEAM_2003));
            }
            // 跨团队任意操作（含 OWNER）→ §6.2 末二行 / §6.1 #1：一律 TEAM-2001
            rows.add(row(role, Op.CROSS_TEAM_MEMBERS_LIST, TEAM_2001));
            rows.add(row(role, Op.CROSS_TEAM_WAREHOUSE_RESOURCE, TEAM_2001));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "[{index}] {0} × {1} → 期望 {2}")
    @MethodSource("authorizationMatrix")
    @DisplayName("TEST_STRATEGY §6.2 M2 子集：角色 × 操作 → 期望码（45 格参数化）")
    void authorizationMatrixHolds(String role, Op op, String expectedCode) throws Exception {
        ResponseEntity<String> response = execute(op, tokenOf(role));
        String actualCode = codeOf(response);

        assertEquals(expectedCode, actualCode,
                () -> "角色=" + role + " 操作=" + op + " 期望=" + expectedCode
                        + " 实测=" + actualCode + " HTTP=" + response.getStatusCode() + " body=" + response.getBody());

        if (OK.equals(expectedCode)) {
            assertTrue(response.getStatusCode().is2xxSuccessful(),
                    () -> role + " × " + op + " 应成功，实测 HTTP=" + response.getStatusCode()
                            + " body=" + response.getBody());
        } else {
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                    () -> role + " × " + op + " 应被拒为 403，实测 HTTP=" + response.getStatusCode());
        }
    }

    // =====================================================================
    // §1-A 顺序证明：团队停用（TEAM-2002）必须【先于】角色判定（TEAM-2003）
    // =====================================================================

    /**
     * 「先判团队状态、再判角色」的顺序证明（{@code USER_ROLES §4.1} 第 5 步先于第 7 步）。
     *
     * <p>选用 <b>{@code GET /api/v1/teams/{teamId}}</b>：该端点读端点在契约中对「任何已登录成员」开放、
     * <b>不带</b> {@code @RequireRole}，因此团队状态判定是其路径上唯一的门禁。若实现按
     * 「团队状态与角色无关」执行，则 5 个角色应得到<b>同一个</b> {@code TEAM-2002}；
     * 实测正是如此，故本条同时证明「团队状态判定不依赖角色」。
     *
     * <p><b>为何必须 5 个角色全跑</b>：既有 {@code TeamManagementIntegrationTest §3-6b} 只用 OWNER ——
     * 而 OWNER 在角色判定上本就「通过」，<b>无法区分两种实现顺序</b>。只有把非管理角色
     * （OPERATOR/APPROVER/VIEWER）纳入，顺序才被真正约束住。
     *
     * <p>★ 受 {@code @RequireRole} 保护的端点上同样成立（M2-T13.5 修复 R21 后）：团队状态判定
     * 已在认证过滤链统一执行、先于角色切面。见下方 {@link #disabledTeamDeniedBeforeRoleCheck()}。
     */
    @ParameterizedTest(name = "[{index}] 团队停用 × {0} × 读团队详情 → TEAM-2002")
    @EnumSource(value = Role.class)
    @DisplayName("§1-A 顺序证明：团队停用后任意角色读团队详情 → TEAM-2002（团队状态判定不依赖角色）")
    void disabledTeamDeniedForEveryRole(Role role) throws Exception {
        // ★ 必须先取令牌（此时团队仍 ACTIVE）：登录本身就会校验团队状态
        //   （AuthService → TEAM-2002）。若在停用后再登录，用例会因「登录失败」而红，
        //   从而测不到目标路径（「已持令牌的成员访问业务接口」）。
        String token = tokenOf(role.name());

        jdbc.update("UPDATE teams SET status = 'DISABLED' WHERE id = ?", TEAM_ID);
        try {
            ResponseEntity<String> response = get("/api/v1/teams/" + TEAM_ID, token);
            assertEquals(TEAM_2002, codeOf(response),
                    () -> "团队停用后 " + role + " 读团队详情应得 TEAM-2002（团队状态先于角色）；实测="
                            + response.getBody());
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        } finally {
            jdbc.update("UPDATE teams SET status = 'ACTIVE' WHERE id = ?", TEAM_ID);
        }
    }

    /**
     * R21 修复证据（M2-T13.5）：团队状态判定（第 5 步）已在认证过滤链统一执行，
     * 先于 {@code @RequireRole} 切面（角色判定，第 7 步）。因此团队停用后，<b>无论</b>何种角色
     * 访问任何业务端点（含受 {@code @RequireRole} 保护的 {@code GET /teams/{id}/members}）
     * 都先得 {@code TEAM-2002}，而非 {@code TEAM-2003}。
     *
     * <p>5 个角色全跑：OWNER/ADMIN 作为对照组（本就穿过角色判定）；OPERATOR/APPROVER/VIEWER
     * 作为修复前会先撞 {@code TEAM-2003} 的角色——修复后一律 {@code TEAM-2002}。
     */
    @Test
    @DisplayName("§1-A R21 修复：团队停用 × 任意角色访问 @RequireRole 端点 → 统一 TEAM-2002（先于角色判定）")
    void disabledTeamDeniedBeforeRoleCheck() throws Exception {
        for (Role role : Role.values()) {
            String token = tokenOf(role.name());
            jdbc.update("UPDATE teams SET status = 'DISABLED' WHERE id = ?", TEAM_ID);
            try {
                String actual = codeOf(get("/api/v1/teams/" + TEAM_ID + "/members", token));
                assertEquals(TEAM_2002, actual,
                        role + " 团队停用后应得 TEAM-2002（团队状态先于角色判定）；实测=" + actual);
            } finally {
                jdbc.update("UPDATE teams SET status = 'ACTIVE' WHERE id = ?", TEAM_ID);
            }
        }
    }

    /**
     * R20 修复证据（M2-T13.5）：团队停用后，catalog 全部四类主数据端点必须统一返回 403 TEAM-2002。
     *
     * <p><b>修复方式</b>：把「团队状态」上移到认证过滤链（第 5 步），<b>不</b>在 catalog 各服务方法散落补校验
     * （那正是 R20 的成因——散落必漏，catalog 曾整域缺失）。故本用例断言<b>所有</b> catalog 端点
     * （含 {@code GET} 列表/详情、{@code POST}、{@code PATCH}、{@code DELETE}、{@code PATCH /status}）
     * 在团队停用后一律 {@code TEAM-2002}，且无需任何 catalog 代码改动即自动获得。
     *
     * <p>对照组：团队恢复 {@code ACTIVE} 后，同一批请求恢复 2xx——
     * 证明失败的唯一原因是「团队停用」，而非数据/权限等其它因素。
     */
    @Test
    @DisplayName("§1-A R20 修复：团队停用后 catalog 四表全端点 → 403 TEAM-2002；恢复 ACTIVE 后 → 2xx")
    void catalogDeniedWhenTeamDisabled() throws Exception {
        String token = tokenOf("OWNER"); // 即便 OWNER（修复前可写 catalog）也须被 TEAM-2002 拦

        // ---- 阶段一：团队 ACTIVE 时，为 team 7 建种子资源并验证 2xx ----
        long whId = createResource("/api/v1/warehouses", "{\"code\":\"WH-SEED\",\"name\":\"种子仓库\"}", token);
        long supId = createResource("/api/v1/suppliers", "{\"code\":\"SUP-SEED\",\"name\":\"种子供应商\"}", token);
        long prdId = createResource("/api/v1/products", "{\"product_code\":\"PRD-SEED\",\"name\":\"种子商品\"}", token);
        long skuId = createResource("/api/v1/skus",
                "{\"product_id\":" + prdId + ",\"sku_code\":\"SKU-SEED\",\"name\":\"种子SKU\"}", token);
        assert2xx(get("/api/v1/warehouses", token));

        // ---- 阶段二：停用团队，四类主数据全端点应为 TEAM-2002 ----
        jdbc.update("UPDATE teams SET status = 'DISABLED' WHERE id = ?", TEAM_ID);
        try {
            // warehouses：列表 / 详情 / 新建 / 改名 / 停用(DELETE 软删) / 启用(PATCH status)
            assertTeam2002(get("/api/v1/warehouses", token));
            assertTeam2002(get("/api/v1/warehouses/" + whId, token));
            assertTeam2002(post("/api/v1/warehouses", "{\"code\":\"WH-X\",\"name\":\"x\"}", token));
            assertTeam2002(patch("/api/v1/warehouses/" + whId, "{\"name\":\"x\"}", token));
            assertTeam2002(delete("/api/v1/warehouses/" + whId, token));
            assertTeam2002(patch("/api/v1/warehouses/" + whId + "/status", "{\"status\":\"ACTIVE\"}", token));
            // suppliers：无 PATCH status
            assertTeam2002(get("/api/v1/suppliers", token));
            assertTeam2002(get("/api/v1/suppliers/" + supId, token));
            assertTeam2002(post("/api/v1/suppliers", "{\"code\":\"SP-X\",\"name\":\"x\"}", token));
            assertTeam2002(patch("/api/v1/suppliers/" + supId, "{\"name\":\"x\"}", token));
            assertTeam2002(delete("/api/v1/suppliers/" + supId, token));
            // products：无 PATCH status
            assertTeam2002(get("/api/v1/products", token));
            assertTeam2002(get("/api/v1/products/" + prdId, token));
            assertTeam2002(post("/api/v1/products", "{\"product_code\":\"PRD-X\",\"name\":\"x\"}", token));
            assertTeam2002(patch("/api/v1/products/" + prdId, "{\"name\":\"x\"}", token));
            assertTeam2002(delete("/api/v1/products/" + prdId, token));
            // skus：含 PATCH status
            assertTeam2002(get("/api/v1/skus", token));
            assertTeam2002(get("/api/v1/skus/" + skuId, token));
            assertTeam2002(post("/api/v1/skus",
                    "{\"product_id\":" + prdId + ",\"sku_code\":\"SKU-X\",\"name\":\"x\"}", token));
            assertTeam2002(patch("/api/v1/skus/" + skuId, "{\"name\":\"x\"}", token));
            assertTeam2002(delete("/api/v1/skus/" + skuId, token));
            assertTeam2002(patch("/api/v1/skus/" + skuId + "/status", "{\"status\":\"ACTIVE\"}", token));
        } finally {
            jdbc.update("UPDATE teams SET status = 'ACTIVE' WHERE id = ?", TEAM_ID);
        }

        // ---- 阶段三：恢复 ACTIVE 后，同一批请求恢复 2xx（证明失败唯一原因 = 团队停用）----
        assert2xx(get("/api/v1/warehouses", token));
        assert2xx(post("/api/v1/warehouses", "{\"code\":\"WH-Y\",\"name\":\"y\"}", token));
    }

    // =====================================================================
    // 操作定义
    // =====================================================================

    /** M2 可用操作子集（TEST_STRATEGY §6.2 的 M2 落地行）。 */
    enum Op {
        MEMBERS_LIST("查看团队成员列表"),
        MEMBERS_ADD("添加团队成员"),
        TEAM_CONFIG_UPDATE("修改团队配置"),
        WAREHOUSE_CREATE("新建仓库"),
        SUPPLIER_CREATE("新建供应商"),
        PRODUCT_CREATE("新建商品"),
        SKU_CREATE("新建 SKU"),
        CROSS_TEAM_MEMBERS_LIST("跨团队查看成员列表（team 8）"),
        CROSS_TEAM_WAREHOUSE_RESOURCE("跨团队读取仓库资源（team 8）");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 角色枚举（供 {@code @EnumSource} 驱动，与 {@link #M2_ROLES} 一一对应）。 */
    enum Role {
        OWNER, ADMIN, OPERATOR, APPROVER, VIEWER
    }

    // =====================================================================
    // 执行与辅助
    // =====================================================================

    private ResponseEntity<String> execute(Op op, String token) {
        return switch (op) {
            case MEMBERS_LIST -> get("/api/v1/teams/" + TEAM_ID + "/members", token);
            case MEMBERS_ADD -> post("/api/v1/teams/" + TEAM_ID + "/members",
                    "{\"user_id\":" + SPARE_USER_ID + ",\"role\":\"VIEWER\"}", token);
            case TEAM_CONFIG_UPDATE -> patch("/api/v1/teams/" + TEAM_ID, "{\"name\":\"矩阵改名\"}", token);
            case WAREHOUSE_CREATE -> post("/api/v1/warehouses",
                    "{\"code\":\"WH-MX\",\"name\":\"矩阵仓库\"}", token);
            case SUPPLIER_CREATE -> post("/api/v1/suppliers",
                    "{\"code\":\"SUP-MX\",\"name\":\"矩阵供应商\"}", token);
            case PRODUCT_CREATE -> post("/api/v1/products",
                    "{\"product_code\":\"PRD-MX\",\"name\":\"矩阵商品\"}", token);
            case SKU_CREATE -> post("/api/v1/skus",
                    "{\"product_id\":" + seedProductId + ",\"sku_code\":\"SKU-MX\",\"name\":\"矩阵SKU\"}", token);
            case CROSS_TEAM_MEMBERS_LIST -> get("/api/v1/teams/" + OTHER_TEAM_ID + "/members", token);
            case CROSS_TEAM_WAREHOUSE_RESOURCE -> get("/api/v1/warehouses/" + otherTeamWarehouseId, token);
        };
    }

    private static Arguments row(String role, Op op, String expectedCode) {
        return Arguments.of(role, op, expectedCode);
    }

    /** 取该角色的 Access Token（首次登录后缓存；账号 id 稳定，可跨用例复用）。 */
    private String tokenOf(String roleName) throws Exception {
        String username = switch (roleName) {
            case "OWNER" -> "owner01";
            case "ADMIN" -> "admin01";
            case "OPERATOR" -> "operator01";
            case "APPROVER" -> "approver01";
            case "VIEWER" -> "viewer01";
            default -> throw new IllegalArgumentException("未知角色：" + roleName);
        };
        String cached = TOKEN_CACHE.get(username);
        if (cached != null) {
            return cached;
        }
        ResponseEntity<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + username + " / " + response.getBody());
        String token = objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
        TOKEN_CACHE.put(username, token);
        return token;
    }

    private void insertUser(long id, String username, String displayName) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, displayName);
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

    private String codeOf(ResponseEntity<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getBody());
        return body.get("code").asText();
    }

    /** 期望响应为 TEAM-2002（团队停用）且 HTTP 403。 */
    private void assertTeam2002(ResponseEntity<String> response) throws Exception {
        assertEquals(TEAM_2002, codeOf(response),
                () -> "团队停用后业务端点应得 TEAM-2002，实测=" + response.getBody());
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    /** 期望响应为 2xx（团队 ACTIVE）。 */
    private void assert2xx(ResponseEntity<String> response) {
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                () -> "团队 ACTIVE 时应 2xx，实测=" + response.getStatusCode() + " " + response.getBody());
    }

    /** 以 OWNER 令牌创建资源并解析 data.id（阶段一：团队 ACTIVE 时）。 */
    private long createResource(String path, String jsonBody, String token) throws Exception {
        ResponseEntity<String> r = post(path, jsonBody, token);
        assertTrue(r.getStatusCode().is2xxSuccessful(),
                () -> "创建资源应 2xx：" + path + " 实测=" + r.getStatusCode() + " " + r.getBody());
        return objectMapper.readTree(r.getBody()).get("data").get("id").asLong();
    }
}
