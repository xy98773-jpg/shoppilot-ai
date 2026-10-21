package com.shoppilot.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M6-T09 审计查询端点 {@code GET /api/v1/audit/logs} —— 端到端集成测试（L2）。
 *
 * <p><b>真栈</b>：MySQL 8.0.36（Testcontainers）+ 真实 Flyway 迁移链 + 真实登录/JWT/租户线
 * + 真实 {@code RequireRole} 切面 + 真实 Mapper（{@code @Select} 动态 SQL）。
 *
 * <h2>覆盖（任务书 §三）</h2>
 * <ol>
 *   <li>分页：{@code total} 与 {@code list.size} 一致；第 2 页与第 1 页无重叠；</li>
 *   <li>{@code biz_type + biz_id} 过滤；</li>
 *   <li>{@code operator_id} 过滤；</li>
 *   <li>{@code result=FAILURE}（含非空 {@code error_code}）；</li>
 *   <li>{@code date_from/date_to} 边界（含当天、跨天）；</li>
 *   <li>★ 跨团队不可见：团队 8 的 OWNER 查不到团队 7 的任何记录；</li>
 *   <li>{@code OPERATOR} → 403 {@code TEAM-2003}；</li>
 *   <li>未登录 → 401 {@code AUTH-1001}；</li>
 *   <li>非法参数：{@code result=FOO}、只给 {@code biz_id}、非法 {@code sort} → 400 {@code COM-0001}；</li>
 *   <li>{@code pageSize=500} → 按 200 截断；</li>
 *   <li>响应字段口径（snake_case、无 {@code team_id}/{@code created_by}）。</li>
 * </ol>
 *
 * <h2>造数与断言纪律</h2>
 * 审计行统一经 {@link AuditWriteService#record}/{@code recordAll} 写入（与生产同一路径，含脱敏与列宽截断）；
 * 登录本身也会写一条 {@code AUTH_LOGIN_SUCCESS}，故每个用例在<b>登录之后</b>清空 {@code audit_logs}
 * 再造数，避免登录审计污染计数。筛选断言同时使用 {@code biz_type}，与登录行（{@code biz_type=USER}）隔离。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuditLogQueryIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;

    private static final long OWNER_ID = 1004L;
    private static final long OPERATOR_ID = 1001L;
    private static final long OTHER_OWNER_ID = 2004L;

    private static final String LOGS_PATH = "/api/v1/audit/logs";

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

    @Autowired
    private AuditWriteService auditWriteService;

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
        insertUser(OTHER_OWNER_ID, "owner8", "别队老板");
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES "
                        + "(?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE'), (?, ?, 'OWNER', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID, OTHER_TEAM_ID, OTHER_OWNER_ID);
    }

    // =====================================================================
    // 1. 分页
    // =====================================================================

    @Test
    @DisplayName("1 分页：total/list 一致，pageSize 生效，第 2 页与第 1 页无重叠")
    void paginationKeepsTotalAndPagesDisjoint() throws Exception {
        String token = loginAndClearAudit("owner01");
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        for (int i = 1; i <= 5; i++) {
            record(TEAM_ID, AuditAction.MASTER_DATA_STATUS_CHANGE, "WAREHOUSE",
                    100L + i, "W-" + i, OWNER_ID, "OWNER", AuditResult.SUCCESS, null,
                    base.minusHours(i));
        }

        JsonNode page1 = data(get(LOGS_PATH + "?biz_type=WAREHOUSE&page=1&pageSize=2", token));
        assertEquals(5, page1.get("total").asLong(), "total 为满足筛选条件的总数");
        assertEquals(2, page1.get("list").size(), "list.size 应等于 pageSize");
        assertEquals(1, page1.get("page").asInt());
        assertEquals(2, page1.get("pageSize").asInt());

        JsonNode page2 = data(get(LOGS_PATH + "?biz_type=WAREHOUSE&page=2&pageSize=2", token));
        assertEquals(5, page2.get("total").asLong(), "翻页不改变 total");
        assertEquals(2, page2.get("page").asInt());

        Set<Long> ids1 = auditLogIds(page1);
        Set<Long> ids2 = auditLogIds(page2);
        assertEquals(2, ids1.size());
        assertEquals(2, ids2.size());
        Set<Long> overlap = new HashSet<>(ids1);
        overlap.retainAll(ids2);
        assertTrue(overlap.isEmpty(), "第 1 页与第 2 页不得重叠：" + overlap);
        assertTrue(page1.get("list").get(0).get("occurred_at").asText()
                        .compareTo(page2.get("list").get(0).get("occurred_at").asText()) > 0,
                "缺省排序为 occurred_at DESC（第 1 页首条应晚于第 2 页首条）");
    }

    // =====================================================================
    // 2. biz_type + biz_id
    // =====================================================================

    @Test
    @DisplayName("2 biz_type + biz_id 过滤：只命中目标业务对象")
    void filterByBizTypeAndBizId() throws Exception {
        String token = loginAndClearAudit("owner01");
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        record(TEAM_ID, AuditAction.MASTER_DATA_STATUS_CHANGE, "WAREHOUSE", 101L, "W-101",
                OWNER_ID, "OWNER", AuditResult.SUCCESS, null, base.minusMinutes(3));
        record(TEAM_ID, AuditAction.MASTER_DATA_STATUS_CHANGE, "WAREHOUSE", 102L, "W-102",
                OWNER_ID, "OWNER", AuditResult.SUCCESS, null, base.minusMinutes(2));
        record(TEAM_ID, AuditAction.MASTER_DATA_STATUS_CHANGE, "PRODUCT", 101L, "P-101",
                OWNER_ID, "OWNER", AuditResult.SUCCESS, null, base.minusMinutes(1));

        JsonNode data = data(get(LOGS_PATH + "?biz_type=WAREHOUSE&biz_id=101", token));
        assertEquals(1, data.get("total").asLong());
        JsonNode row = data.get("list").get(0);
        assertEquals("WAREHOUSE", row.get("biz_type").asText());
        assertEquals(101L, row.get("biz_id").asLong());
        assertEquals("W-101", row.get("biz_no").asText());
    }

    // =====================================================================
    // 3. operator_id
    // =====================================================================

    @Test
    @DisplayName("3 operator_id 过滤：只返回该操作人的记录")
    void filterByOperatorId() throws Exception {
        String token = loginAndClearAudit("owner01");
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        record(TEAM_ID, AuditAction.AUTH_LOGIN_SUCCESS, "USER", OPERATOR_ID, "operator01",
                OPERATOR_ID, "OPERATOR", AuditResult.SUCCESS, null, base.minusMinutes(3));
        record(TEAM_ID, AuditAction.AUTH_LOGIN_SUCCESS, "USER", OPERATOR_ID, "operator01",
                OPERATOR_ID, "OPERATOR", AuditResult.SUCCESS, null, base.minusMinutes(2));
        record(TEAM_ID, AuditAction.AUTH_LOGIN_SUCCESS, "USER", OWNER_ID, "owner01",
                OWNER_ID, "OWNER", AuditResult.SUCCESS, null, base.minusMinutes(1));

        JsonNode data = data(get(LOGS_PATH + "?operator_id=" + OPERATOR_ID, token));
        assertEquals(2, data.get("total").asLong());
        for (JsonNode row : data.get("list")) {
            assertEquals(OPERATOR_ID, row.get("operator_id").asLong());
            assertEquals("OPERATOR", row.get("operator_role").asText());
        }
    }

    // =====================================================================
    // 4. result=FAILURE
    // =====================================================================

    @Test
    @DisplayName("4 result=FAILURE：只命中失败记录且 error_code 非空")
    void filterByFailureResult() throws Exception {
        String token = loginAndClearAudit("owner01");
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        record(TEAM_ID, AuditAction.INVENTORY_MANUAL_ADJUST, "INVENTORY_BALANCE", 501L, "B-501",
                OPERATOR_ID, "OPERATOR", AuditResult.SUCCESS, null, base.minusMinutes(2));
        record(TEAM_ID, AuditAction.INVENTORY_MANUAL_ADJUST, "INVENTORY_BALANCE", 502L, "B-502",
                OPERATOR_ID, "OPERATOR", AuditResult.FAILURE, "INV-5002", base.minusMinutes(1));

        JsonNode data = data(get(LOGS_PATH + "?result=FAILURE", token));
        assertEquals(1, data.get("total").asLong());
        JsonNode row = data.get("list").get(0);
        assertEquals("FAILURE", row.get("result").asText());
        assertFalse(row.get("error_code").isNull(), "失败记录必须带 error_code");
        assertEquals("INV-5002", row.get("error_code").asText());
    }

    // =====================================================================
    // 5. 日期边界
    // =====================================================================

    @Test
    @DisplayName("5 日期边界：date_from 含当天 00:00；date_to 含全天；跨天区间连续")
    void filterByDateBoundaries() throws Exception {
        String token = loginAndClearAudit("owner01");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime startOfToday = today.atStartOfDay();
        record(TEAM_ID, AuditAction.IMPORT_BATCH_CREATE, "IMPORT_BATCH", 1L, "YESTERDAY-LAST-SECOND",
                OWNER_ID, "OWNER", AuditResult.SUCCESS, null, startOfToday.minusSeconds(1));
        record(TEAM_ID, AuditAction.IMPORT_BATCH_CREATE, "IMPORT_BATCH", 2L, "TODAY-START",
                OWNER_ID, "OWNER", AuditResult.SUCCESS, null, startOfToday);
        record(TEAM_ID, AuditAction.IMPORT_BATCH_CREATE, "IMPORT_BATCH", 3L, "TODAY-10AM",
                OWNER_ID, "OWNER", AuditResult.SUCCESS, null, startOfToday.plusHours(10));
        record(TEAM_ID, AuditAction.IMPORT_BATCH_CREATE, "IMPORT_BATCH", 4L, "TOMORROW-START",
                OWNER_ID, "OWNER", AuditResult.SUCCESS, null, startOfToday.plusDays(1));

        // date_from=date_to=当天：00:00 与 10:00 命中；次日 00:00 不命中（右开区间）
        JsonNode fromOnly = data(get(LOGS_PATH + "?date_from=" + today + "&date_to=" + today, token));
        assertEquals(2, fromOnly.get("total").asLong(),
                "当天区间应包含 00:00 起的记录，且不含次日 00:00");
        assertEquals(Set.of("TODAY-START", "TODAY-10AM"), bizNos(fromOnly));

        // 跨天：昨天 23:59:59 + 今天两条 = 3（date_to=today 含当天全天）
        JsonNode crossDay = data(get(LOGS_PATH + "?date_from=" + today.minusDays(1)
                + "&date_to=" + today, token));
        assertEquals(3, crossDay.get("total").asLong(), "跨天区间应包含昨天末秒与今天全部记录");
        assertEquals(Set.of("YESTERDAY-LAST-SECOND", "TODAY-START", "TODAY-10AM"), bizNos(crossDay));
    }

    // =====================================================================
    // 6. 跨团队隔离
    // =====================================================================

    @Test
    @DisplayName("6 跨团队不可见：团队 8 的 OWNER 查不到团队 7 的任何审计")
    void crossTeamRecordsInvisible() throws Exception {
        String token8 = loginAndClearAudit("owner8");
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        for (int i = 1; i <= 3; i++) {
            record(TEAM_ID, AuditAction.MASTER_DATA_STATUS_CHANGE, "WAREHOUSE",
                    700L + i, "TEAM7-" + i, OWNER_ID, "OWNER", AuditResult.SUCCESS, null,
                    base.minusMinutes(10L + i));
        }
        record(OTHER_TEAM_ID, AuditAction.MASTER_DATA_STATUS_CHANGE, "WAREHOUSE",
                800L, "TEAM8-1", OTHER_OWNER_ID, "OWNER", AuditResult.SUCCESS, null, base.minusMinutes(2));
        record(OTHER_TEAM_ID, AuditAction.MASTER_DATA_STATUS_CHANGE, "WAREHOUSE",
                801L, "TEAM8-2", OTHER_OWNER_ID, "OWNER", AuditResult.SUCCESS, null, base.minusMinutes(1));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class),
                "造数应共 5 条（团队 7 三条 + 团队 8 两条）");

        JsonNode data = data(get(LOGS_PATH + "?biz_type=WAREHOUSE", token8));
        assertEquals(2, data.get("total").asLong(), "团队 8 只应看到自己团队的 2 条");
        assertEquals(Set.of("TEAM8-1", "TEAM8-2"), bizNos(data),
                "★ 不得返回任何团队 7 的记录（租户线自动追加 team_id）");
    }

    // =====================================================================
    // 7. 角色
    // =====================================================================

    @Test
    @DisplayName("7 OPERATOR 调用 → 403 TEAM-2003")
    void operatorIsForbidden() throws Exception {
        String token = loginAndClearAudit("operator01");
        ResponseEntity<String> response = get(LOGS_PATH, token);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertEquals("TEAM-2003", errorCode(response));
    }

    // =====================================================================
    // 8. 未登录
    // =====================================================================

    @Test
    @DisplayName("8 未登录 → 401 AUTH-1001")
    void unauthenticatedIsRejected() throws Exception {
        ResponseEntity<String> response = get(LOGS_PATH, null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), response.getBody());
        assertEquals("AUTH-1001", errorCode(response));
    }

    // =====================================================================
    // 9. 非法参数
    // =====================================================================

    @Test
    @DisplayName("9 非法参数：result 非枚举 / 缺 biz_type / 非法 sort / 非法日期 → 400 COM-0001")
    void invalidParametersAreRejected() throws Exception {
        String token = loginAndClearAudit("owner01");

        assertBadRequest(get(LOGS_PATH + "?result=FOO", token), "result 非法");
        assertBadRequest(get(LOGS_PATH + "?biz_id=101", token), "只给 biz_id 不给 biz_type");
        assertBadRequest(get(LOGS_PATH + "?sort=occurred_at,sideways", token), "sort 方向非法");
        assertBadRequest(get(LOGS_PATH + "?sort=action,asc", token), "sort 字段不在白名单");
        assertBadRequest(get(LOGS_PATH + "?date_from=2026-13-01", token), "日期格式非法");
        assertBadRequest(get(LOGS_PATH + "?date_from=2026-10-20&date_to=2026-10-19", token),
                "date_from 晚于 date_to");
    }

    // =====================================================================
    // 10. pageSize 上限
    // =====================================================================

    @Test
    @DisplayName("10 pageSize=500：按 200 截断，list.size <= 200")
    void pageSizeIsClampedTo200() throws Exception {
        String token = loginAndClearAudit("owner01");
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        List<AuditEvent> events = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            events.add(AuditEvent.builder()
                    .teamId(TEAM_ID)
                    .action(AuditAction.IMPORT_BATCH_CREATE)
                    .bizType("IMPORT_BATCH")
                    .bizId((long) i)
                    .bizNo("BULK-" + i)
                    .operatorId(OWNER_ID)
                    .operatorName("老板")
                    .operatorRole("OWNER")
                    .result(AuditResult.SUCCESS)
                    .occurredAt(base.minusSeconds(205L - i))
                    .build());
        }
        auditWriteService.recordAll(events);
        assertEquals(205, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class),
                "批量造数应写满 205 条（recordAll 一个事务、条数不变）");

        JsonNode data = data(get(LOGS_PATH + "?pageSize=500", token));
        assertEquals(205, data.get("total").asLong());
        assertEquals(200, data.get("pageSize").asInt(), "pageSize 应回显截断后的 200");
        assertTrue(data.get("list").size() <= 200,
                "list.size 不得超过 200，实际 " + data.get("list").size());
        assertEquals(200, data.get("list").size());
    }

    // =====================================================================
    // 11. 响应字段口径
    // =====================================================================

    @Test
    @DisplayName("11 响应字段：snake_case 逐字段返回，且不含 team_id / created_by")
    void responseShapeFollowsContract() throws Exception {
        String token = loginAndClearAudit("owner01");
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "ACTIVE");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "DISABLED");
        auditWriteService.record(AuditEvent.builder()
                .teamId(TEAM_ID)
                .action(AuditAction.MASTER_DATA_STATUS_CHANGE)
                .bizType("WAREHOUSE")
                .bizId(900L)
                .bizNo("W-900")
                .operatorId(OWNER_ID)
                .operatorName("老板")
                .operatorRole("OWNER")
                .result(AuditResult.SUCCESS)
                .changeSummary("状态 ACTIVE → DISABLED")
                .before(before)
                .after(after)
                .occurredAt(LocalDateTime.now(ZoneOffset.UTC).withNano(0))
                .build());

        JsonNode data = data(get(LOGS_PATH + "?biz_type=WAREHOUSE", token));
        assertEquals(1, data.get("total").asLong());
        JsonNode row = data.get("list").get(0);
        for (String field : List.of("audit_log_id", "action", "biz_type", "biz_id", "biz_no",
                "operator_id", "operator_name", "operator_role", "result", "error_code",
                "change_summary", "request_id", "ip", "occurred_at", "before_json", "after_json")) {
            assertTrue(row.has(field), "响应元素缺少字段：" + field);
        }
        assertFalse(row.has("team_id"), "★ 不得返回 team_id（只用于服务端过滤）");
        assertFalse(row.has("created_by"), "★ 不得返回 created_by");
        assertFalse(row.has("id"), "主键字段名为 audit_log_id，不是 id");
        assertFalse(row.has("created_at"), "时间口径是 occurred_at，不是 created_at");

        assertTrue(row.get("audit_log_id").asLong() > 0);
        assertTrue(row.get("before_json").isTextual(), "before_json 以 JSON 字符串返回");
        assertTrue(row.get("before_json").asText().contains("ACTIVE"));
        assertTrue(row.get("after_json").asText().contains("DISABLED"));
        assertTrue(row.get("occurred_at").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "occurred_at 为 ISO-8601 UTC 毫秒字面量：" + row.get("occurred_at").asText());
    }

    // =====================================================================
    // 辅助：造数
    // =====================================================================

    /** 经唯一写入口（{@code AuditWriteService#record}，REQUIRES_NEW）写一条审计。 */
    private void record(long teamId, AuditAction action, String bizType, Long bizId, String bizNo,
                        Long operatorId, String operatorRole, AuditResult result, String errorCode,
                        LocalDateTime occurredAt) {
        AuditEvent.Builder builder = AuditEvent.builder()
                .teamId(teamId)
                .action(action)
                .bizType(bizType)
                .bizId(bizId)
                .bizNo(bizNo)
                .operatorId(operatorId)
                .operatorName(operatorId == null ? null : "user-" + operatorId)
                .operatorRole(operatorRole)
                .result(result)
                .occurredAt(occurredAt);
        if (errorCode != null) {
            builder.errorCode(errorCode);
        }
        auditWriteService.record(builder.build());
    }

    // =====================================================================
    // 辅助：HTTP / JSON
    // =====================================================================

    /** 登录（会写一条登录审计），随后清空审计表，使本用例的计数只含自己造的数据。 */
    private String loginAndClearAudit(String username) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                entity("{\"username\":\"" + username + "\",\"password\":\"" + RAW_PASSWORD + "\"}", null),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + response.getBody());
        String token = objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
        jdbc.execute("DELETE FROM audit_logs");
        return token;
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, entity(null, bearer), String.class);
    }

    private HttpEntity<String> entity(String jsonBody, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (jsonBody != null) {
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        }
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return new HttpEntity<>(jsonBody, headers);
    }

    /** 断言 200 并返回 {@code data}。 */
    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals("OK", root.get("code").asText(), response.getBody());
        assertNotNull(root.get("data"));
        return root.get("data");
    }

    private void assertBadRequest(ResponseEntity<String> response, String scenario) throws Exception {
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                scenario + " 应返回 400：" + response.getBody());
        assertEquals("COM-0001", errorCode(response), scenario + " 应返回 COM-0001");
    }

    private String errorCode(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("code").asText();
    }

    private static Set<Long> auditLogIds(JsonNode page) {
        Set<Long> ids = new HashSet<>();
        for (JsonNode row : page.get("list")) {
            ids.add(row.get("audit_log_id").asLong());
        }
        return ids;
    }

    private static Set<String> bizNos(JsonNode page) {
        Set<String> bizNos = new HashSet<>();
        for (JsonNode row : page.get("list")) {
            bizNos.add(row.get("biz_no").asText());
        }
        return bizNos;
    }

    private void insertUser(long id, String username, String displayName) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", PASSWORD_HASH, displayName);
    }
}
