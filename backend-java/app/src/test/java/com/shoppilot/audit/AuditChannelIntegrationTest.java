package com.shoppilot.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.audit.application.AuditSanitizer;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.infrastructure.persistence.mapper.AuditLogMapper;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-T11 审计写入通道 —— 端到端集成测试（真实 MySQL + 真实 Spring 上下文 + 真实 HTTP）。
 *
 * <p><b>真栈</b>：MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移链（V1.0.0 → V1.0.5）
 * + 真实登录/JWT/租户线 + {@code @Audited} 切面 + {@code AuditWriteService}（REQUIRES_NEW）。
 *
 * <p>覆盖任务书 §6 硬门槛：迁移全链、5 索引 team_id 最左、CHECK、零外键/零唯一、
 * Mapper 仅追加、脱敏、七个关键操作埋点落库、系统域（{@code team_id=0}）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuditChannelIntegrationTest {

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
    private static final long OPERATOR_ID = 1001L;
    private static final long VIEWER_ID = 1003L;
    private static final long TEMP_USER_ID = 1009L;
    private static final long NEW_MEMBER_ID = 1010L;

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
        jdbc.execute("DELETE FROM audit_logs");
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
        insertUser(OPERATOR_ID, "operator01", "小王");
        insertUser(VIEWER_ID, "viewer01", "观察者");
        insertUser(1005L, "approver01", "审批员");
        insertUser(TEMP_USER_ID, "tempuser01", "临时成员");
        insertUser(NEW_MEMBER_ID, "newmember01", "新成员");
        insertUser(2004L, "owner8", "别队老板");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'VIEWER', 'ACTIVE'), "
                        + "(?, ?, 'APPROVER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID, TEAM_ID, VIEWER_ID,
                TEAM_ID, 1005L, TEAM_ID, TEMP_USER_ID, OTHER_TEAM_ID, 2004L);
    }

    // =====================================================================
    // §6-1 Flyway 全链：V1.0.0 → V1.0.5 共 6 行 success
    // =====================================================================

    @Test
    @DisplayName("§6-1 Flyway 全链：V1.0.0→V1.0.5 共 6 行、全部 success=1")
    void flywayChainAppliedThroughV104() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(6, rows.size(),
                "迁移链应为 6 条（baseline/identity/refresh_tokens/catalog/audit/importdata）");
        assertEquals(List.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5"),
                rows.stream().map(r -> String.valueOf(r.get("version"))).toList());
        for (Map<String, Object> row : rows) {
            Object success = row.get("success");
            int successInt = success instanceof Boolean ? (((Boolean) success) ? 1 : 0)
                    : ((Number) success).intValue();
            assertEquals(1, successInt, "每条迁移都应 success=1：" + row.get("version"));
        }
    }

    // =====================================================================
    // §6-2 索引最左：5 条索引 SEQ_IN_INDEX=1 逐条为 team_id
    // =====================================================================

    @Test
    @DisplayName("§6-2 索引最左：audit_logs 5 条索引 SEQ_IN_INDEX=1 逐条为 team_id")
    void fiveIndexesTeamIdLeftmost() {
        List<Map<String, Object>> leftmost = jdbc.queryForList(
                "SELECT INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_logs' "
                        + "AND SEQ_IN_INDEX = 1 AND INDEX_NAME <> 'PRIMARY' ORDER BY INDEX_NAME");
        Set<String> names = leftmost.stream().map(r -> String.valueOf(r.get("INDEX_NAME")))
                .collect(Collectors.toSet());
        assertEquals(Set.of("idx_al_team_biz_time", "idx_al_team_operator_time", "idx_al_team_action_time",
                        "idx_al_team_request", "idx_al_team_result_time"),
                names, "audit_logs 应有且仅有 5 条二级索引");
        for (Map<String, Object> row : leftmost) {
            assertEquals("team_id", row.get("COLUMN_NAME"),
                    "索引 " + row.get("INDEX_NAME") + " 最左列必须为 team_id（DESIGN_BASIS §6 规则 6）");
        }
    }

    // =====================================================================
    // §6-3 CHECK：result='BOGUS' 被拒；SUCCESS/FAILURE 通过
    // =====================================================================

    @Test
    @DisplayName("§6-3 CHECK ck_al_result：'BOGUS' 被拒，'SUCCESS'/'FAILURE' 可写入")
    void resultCheckConstraint() {
        jdbc.update("INSERT INTO audit_logs (team_id, action, biz_type, result, occurred_at) "
                + "VALUES (?, 'X', 'USER', 'SUCCESS', CURRENT_TIMESTAMP(3))", TEAM_ID);
        jdbc.update("INSERT INTO audit_logs (team_id, action, biz_type, result, occurred_at) "
                + "VALUES (?, 'X', 'USER', 'FAILURE', CURRENT_TIMESTAMP(3))", TEAM_ID);
        assertEquals(2, countAuditLogs(), "SUCCESS/FAILURE 均应写入成功");

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO audit_logs (team_id, action, biz_type, result, occurred_at) "
                        + "VALUES (?, 'X', 'USER', 'BOGUS', CURRENT_TIMESTAMP(3))", TEAM_ID),
                "result='BOGUS' 必须被 CHECK ck_al_result 拒绝");
    }

    // =====================================================================
    // §6-4 零外键 / 零唯一约束（PRIMARY 除外）
    // =====================================================================

    @Test
    @DisplayName("§6-4 零外键 / 零唯一约束：audit_logs 无 FK、无 UNIQUE（仅 PRIMARY）")
    void noForeignKeyNoUniqueConstraint() {
        Integer foreignKeys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_logs' "
                        + "AND CONSTRAINT_TYPE = 'FOREIGN KEY'", Integer.class);
        assertEquals(0, foreignKeys, "audit_logs 必须零外键（多态逻辑关联 + 只追加 + 需记录已停用对象）");

        Integer uniques = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_logs' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE'", Integer.class);
        assertEquals(0, uniques, "audit_logs 必须零唯一约束（同一操作可产生多条审计）");

        Integer primary = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_logs' "
                        + "AND CONSTRAINT_TYPE = 'PRIMARY KEY'", Integer.class);
        assertEquals(1, primary, "仅保留主键");
    }

    // =====================================================================
    // §6-5 仅追加：Mapper 接口只含 insert + 只读查询（无 update*/delete*）
    // =====================================================================

    @Test
    @DisplayName("§6-5 仅追加：AuditLogMapper 不含任何 update*/delete* 方法，且不继承 BaseMapper")
    void auditLogMapperAppendOnly() {
        assertEquals(0, AuditLogMapper.class.getInterfaces().length,
                "AuditLogMapper 不得继承 BaseMapper 等父接口（否则自带 updateById/deleteById）");

        Set<String> declared = Arrays.stream(AuditLogMapper.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("insert", "selectRecentByBiz"), declared,
                "Mapper 只应声明 insert（写）与 selectRecentByBiz（只读）");

        for (Method method : AuditLogMapper.class.getMethods()) {
            String name = method.getName();
            assertFalse(name.startsWith("update") || name.startsWith("delete")
                            || name.startsWith("remove") || name.startsWith("replace"),
                    "Mapper 不得暴露写方法：" + name);
            if (!Object.class.equals(method.getDeclaringClass())) {
                assertTrue(name.startsWith("insert") || name.startsWith("select"),
                        "Mapper 方法只允许 insert / select 前缀：" + name);
            }
        }
    }

    // =====================================================================
    // §6-6 脱敏：黑名单字段 / 手机号 / 邮箱 / request_uri 剥查询串
    // =====================================================================

    @Test
    @DisplayName("§6-6 脱敏：黑名单字段掩码、手机号 138****8000、邮箱 a***@b.com、uri 剥查询串")
    void sanitizerMasksSensitiveData() throws Exception {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("username", "alice");
        before.put("password", "p@ssw0rd");
        before.put("password_hash", "$2a$10$abcdefghij");
        before.put("token", "eyJhbGciOiJIUzI1NiJ9");
        before.put("secret", "s3cr3t-value");
        before.put("api_key", "ak-123456");
        before.put("authorization", "Bearer abc.def");
        before.put("contact_phone", "13812345678");
        before.put("email", "alice@example.com");
        before.put("note", "备用电话 13900001111，邮箱 bob@test.com");

        AuditSanitizer.Sanitized sanitized = AuditSanitizer.sanitize(before, null);
        assertNotNull(sanitized.beforeJson());
        assertNull(sanitized.afterJson(), "after 为 null 时对应分量为 null");

        JsonNode node = objectMapper.readTree(sanitized.beforeJson());
        assertEquals("alice", node.get("username").asText(), "普通字段不受影响");
        for (String blacklisted : List.of("password", "password_hash", "token", "secret", "api_key",
                "authorization")) {
            assertEquals("***", node.get(blacklisted).asText(),
                    "黑名单字段 " + blacklisted + " 必须掩码（§6-J.3）");
        }
        assertEquals("138****5678", node.get("contact_phone").asText(), "手机号留前 3 后 4");
        assertEquals("a***@example.com", node.get("email").asText(), "邮箱本地位留首字符");
        String note = node.get("note").asText();
        assertTrue(note.contains("139****1111"), "自由文本内手机号同样掩码：" + note);
        assertTrue(note.contains("b***@test.com"), "自由文本内邮箱同样掩码：" + note);

        assertEquals("/api/v1/auth/login",
                AuditSanitizer.stripQuery("/api/v1/auth/login?token=abc&next=/home"),
                "request_uri 必须剥离查询串");
        assertEquals("/api/v1/auth/login",
                AuditSanitizer.stripQuery("/api/v1/auth/login"), "无查询串时原样返回");
        assertEquals(255, AuditSanitizer.truncate("a".repeat(300), 255).length(), "user_agent 截断至 255");
        assertEquals(1000, AuditSanitizer.truncate("b".repeat(1500), 1000).length(),
                "change_summary 截断至 1000");
        assertTrue(AuditSanitizer.isBlacklisted("X-Auth-Token"), "黑名单大小写不敏感、含子串匹配");
        assertFalse(AuditSanitizer.isBlacklisted("username"), "普通字段不误伤");
    }

    // =====================================================================
    // §6-7 / §6-8 埋点落库：登录成功（选中团队）与登录失败（系统域 team_id=0）
    // =====================================================================

    @Test
    @DisplayName("§6-7/8 登录埋点：成功记选中团队；失败 team_id=0 系统域 + error_code=AUTH-1002")
    void loginAudits() throws Exception {
        // #1 登录成功
        String token = loginAccessToken("operator01");
        assertNotNull(token);
        Map<String, Object> success = latestAudit("AUTH_LOGIN_SUCCESS");
        assertEquals("USER", success.get("biz_type"));
        assertEquals("SUCCESS", success.get("result"));
        assertEquals(TEAM_ID, ((Number) success.get("team_id")).longValue(), "成功时 team_id = 选中的会话团队");
        assertEquals(OPERATOR_ID, ((Number) success.get("operator_id")).longValue());
        assertEquals("operator01", success.get("operator_name"));
        assertEquals("OPERATOR", success.get("operator_role"), "operator_role 为当时角色快照");
        assertEquals("POST", success.get("http_method"), "HTTP 上下文由 audit 模块自动采集");
        assertEquals("/api/v1/auth/login", success.get("request_uri"), "request_uri 不含查询串");
        assertNotNull(success.get("request_id"), "request_id 应随请求链路落库");

        // #2 登录失败（错误口令）→ 401 + FAILURE 审计，team_id = 0 系统域
        ResponseEntity<String> failed = post("/api/v1/auth/login",
                "{\"username\":\"operator01\",\"password\":\"definitely-wrong-999\"}", null);
        assertEquals(HttpStatus.UNAUTHORIZED, failed.getStatusCode());

        Map<String, Object> failure = latestAudit("AUTH_LOGIN_FAILED");
        assertEquals("FAILURE", failure.get("result"));
        assertEquals("AUTH-1002", failure.get("error_code"));
        assertEquals(0L, ((Number) failure.get("team_id")).longValue(),
                "★ 登录失败无团队上下文 → team_id=0（系统域），且不得因租户线抛 AUTH-1001 而写不进去");
        assertNull(failure.get("operator_id"), "失败时 operator_id 为 NULL");
        assertEquals("operator01", failure.get("operator_name"), "失败时记提交的登录名快照");
        assertEquals("POST", failure.get("http_method"));
    }

    // =====================================================================
    // §6-7 用户状态变更埋点
    // =====================================================================

    @Test
    @DisplayName("§6-7 用户禁用埋点：USER_STATUS_CHANGE / USER / SUCCESS，操作者与目标快照正确")
    void userStatusChangeAudit() throws Exception {
        String owner = loginAccessToken("owner01");
        ResponseEntity<String> response = patch("/api/v1/users/" + TEMP_USER_ID + "/status",
                "{\"status\":\"DISABLED\"}", owner);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());

        Map<String, Object> row = latestAudit("USER_STATUS_CHANGE");
        assertEquals("USER", row.get("biz_type"));
        assertEquals("SUCCESS", row.get("result"));
        assertEquals(TEAM_ID, ((Number) row.get("team_id")).longValue());
        assertEquals(OWNER_ID, ((Number) row.get("operator_id")).longValue());
        assertEquals("owner01", row.get("operator_name"));
        assertEquals("OWNER", row.get("operator_role"));
        assertEquals(TEMP_USER_ID, ((Number) row.get("biz_id")).longValue());
        assertEquals("tempuser01", row.get("biz_no"), "biz_no 为目标登录名快照");
        assertEquals("用户状态 ACTIVE → DISABLED", row.get("change_summary"));
        assertEquals("PATCH", row.get("http_method"));
        assertEquals("/api/v1/users/" + TEMP_USER_ID + "/status", row.get("request_uri"));
    }

    // =====================================================================
    // §6-7 成员加入 / 角色变更 / 移出 埋点
    // =====================================================================

    @Test
    @DisplayName("§6-7 成员三操作埋点：TEAM_MEMBER_ADD / ROLE_CHANGE / REMOVE 各一条")
    void teamMemberAudits() throws Exception {
        String owner = loginAccessToken("owner01");

        // #28 加入
        ResponseEntity<String> added = post("/api/v1/teams/" + TEAM_ID + "/members",
                "{\"user_id\":" + NEW_MEMBER_ID + ",\"role\":\"OPERATOR\"}", owner);
        assertEquals(HttpStatus.OK, added.getStatusCode(), added.getBody());
        Map<String, Object> addRow = latestAudit("TEAM_MEMBER_ADD");
        assertEquals("TEAM_MEMBER", addRow.get("biz_type"));
        assertEquals("SUCCESS", addRow.get("result"));
        assertEquals(TEAM_ID, ((Number) addRow.get("team_id")).longValue());
        assertEquals(OWNER_ID, ((Number) addRow.get("operator_id")).longValue());
        assertEquals(NEW_MEMBER_ID, ((Number) addRow.get("biz_id")).longValue());
        assertEquals("newmember01", addRow.get("biz_no"));
        assertEquals("加入团队，角色=OPERATOR", addRow.get("change_summary"));

        // #4 角色变更
        ResponseEntity<String> roleChanged = patch(
                "/api/v1/teams/" + TEAM_ID + "/members/" + NEW_MEMBER_ID,
                "{\"role\":\"VIEWER\"}", owner);
        assertEquals(HttpStatus.OK, roleChanged.getStatusCode(), roleChanged.getBody());
        Map<String, Object> roleRow = latestAudit("TEAM_MEMBER_ROLE_CHANGE");
        assertEquals("TEAM_MEMBER", roleRow.get("biz_type"));
        assertEquals(NEW_MEMBER_ID, ((Number) roleRow.get("biz_id")).longValue());
        assertEquals("角色 OPERATOR → VIEWER", roleRow.get("change_summary"));

        // #5 移出
        ResponseEntity<String> removed = delete(
                "/api/v1/teams/" + TEAM_ID + "/members/" + NEW_MEMBER_ID, owner);
        assertEquals(HttpStatus.OK, removed.getStatusCode(), removed.getBody());
        Map<String, Object> removeRow = latestAudit("TEAM_MEMBER_REMOVE");
        assertEquals("TEAM_MEMBER", removeRow.get("biz_type"));
        assertEquals(NEW_MEMBER_ID, ((Number) removeRow.get("biz_id")).longValue());
        assertTrue(String.valueOf(removeRow.get("change_summary")).contains("移出团队"),
                "移出摘要应说明软删语义：" + removeRow.get("change_summary"));

        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE biz_type = 'TEAM_MEMBER'", Integer.class),
                "三个成员操作应各写一条审计");
    }

    // =====================================================================
    // §6-7 主数据创建 / 更新 / 停用 / 启用 全生命周期埋点
    // =====================================================================

    @Test
    @DisplayName("§6-7 主数据全生命周期：创建(显式)/更新(切面)/停用/启用 各一条，biz_no 快照正确")
    void masterDataLifecycleAudits() throws Exception {
        String operator = loginAccessToken("operator01");

        // 创建 → CatalogWriteService 显式 record（带 biz_no 快照）
        long wh = createAndGetId("/api/v1/warehouses",
                "{\"code\":\"W-AUD\",\"name\":\"审计仓\"}", operator);
        Map<String, Object> created = latestAudit("MASTER_DATA_STATUS_CHANGE");
        assertEquals("WAREHOUSE", created.get("biz_type"));
        assertEquals("SUCCESS", created.get("result"));
        assertEquals(TEAM_ID, ((Number) created.get("team_id")).longValue());
        assertEquals(OPERATOR_ID, ((Number) created.get("operator_id")).longValue());
        assertEquals(wh, ((Number) created.get("biz_id")).longValue());
        assertEquals("W-AUD", created.get("biz_no"), "biz_no 为编码快照（对象可能已停用）");
        assertEquals("创建仓库", created.get("change_summary"));

        // 更新 → Controller @Audited 切面（biz_id 取路径；切面不读业务表故 biz_no 为空）
        ResponseEntity<String> updated = patch("/api/v1/warehouses/" + wh,
                "{\"remark\":\"改备注\"}", operator);
        assertEquals(HttpStatus.OK, updated.getStatusCode(), updated.getBody());
        Map<String, Object> updatedRow = latestAudit("MASTER_DATA_STATUS_CHANGE");
        assertEquals("WAREHOUSE", updatedRow.get("biz_type"));
        assertEquals(wh, ((Number) updatedRow.get("biz_id")).longValue());
        assertEquals("更新仓库", updatedRow.get("change_summary"));
        assertNull(updatedRow.get("biz_no"), "切面不读业务表，biz_no 为空（显式埋点才带快照）");

        // 停用（DELETE 软删）→ 显式 record
        ResponseEntity<String> deactivated = delete("/api/v1/warehouses/" + wh, operator);
        assertEquals(HttpStatus.OK, deactivated.getStatusCode(), deactivated.getBody());
        assertEquals("停用仓库", latestAudit("MASTER_DATA_STATUS_CHANGE").get("change_summary"));

        // 启用（PATCH status）→ 显式 record
        ResponseEntity<String> reactivated = patch("/api/v1/warehouses/" + wh + "/status",
                "{\"status\":\"ACTIVE\"}", operator);
        assertEquals(HttpStatus.OK, reactivated.getStatusCode(), reactivated.getBody());
        assertEquals("启用仓库", latestAudit("MASTER_DATA_STATUS_CHANGE").get("change_summary"));

        assertEquals(4, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE action = 'MASTER_DATA_STATUS_CHANGE'",
                        Integer.class),
                "创建/更新/停用/启用 四条操作应各写一条审计");
        assertEquals(5, countAuditLogs(), "另有一条登录成功审计（AUTH_LOGIN_SUCCESS），共 5 条");
    }

    // =====================================================================
    // AuditAction 枚举完整性：35 条权威清单
    // =====================================================================

    @Test
    @DisplayName("§2-1 AuditAction 恰为 35 条（DESIGN_BASIS §9.17 权威清单）")
    void auditActionHasThirtyFiveValues() {
        assertEquals(AuditAction.expectedCount(), AuditAction.values().length,
                "AuditAction 必须 35 条齐全（缺条会导致后续批次反复改动）");
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private long countAuditLogs() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class);
    }

    /** 取指定 action 的最新一条审计（{@code ORDER BY id DESC}）。 */
    private Map<String, Object> latestAudit(String action) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, biz_type, biz_id, biz_no, operator_id, operator_name, operator_role, "
                        + "result, error_code, change_summary, http_method, request_uri, request_id, team_id "
                        + "FROM audit_logs WHERE action = ? ORDER BY id DESC LIMIT 1", action);
        assertEquals(1, rows.size(), "应存在一条 " + action + " 审计（实际 " + rows.size() + " 条）");
        return rows.get(0);
    }

    private void insertUser(long id, String username, String displayName) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, displayName);
    }

    private long createAndGetId(String path, String jsonBody, String bearer) throws Exception {
        ResponseEntity<String> response = post(path, jsonBody, bearer);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "创建应返回 201：" + path + " / " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("id").asLong();
    }

    private String loginAccessToken(String username) throws Exception {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "登录应成功：" + username + " / " + response.getBody());
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
}
