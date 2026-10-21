package com.shoppilot.replenishment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.replenishment.application.ReplenishmentGenerationResult;
import com.shoppilot.replenishment.application.ReplenishmentGenerationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * M5-T02 补货建议生成（{@code ReplenishmentGenerationService.generateFromRun}）集成测试。
 *
 * <p>真 MySQL 8.0.36 + 真实 Flyway + 真实 Spring 上下文（租户线 / 乐观锁插件全部生效），
 * <b>不 Mock 任何 Mapper/Service</b>。覆盖任务书 C1–C9：
 * 正常生成、幂等、状态守卫（4 种非成功态）、跨团队、{@code qty_suggest=0} 不生成、
 * {@code reason} 按字符截断/规则回退、供应商回退链、期望到货日、空结果不报错；
 * 另用测试端点取证 V5（真实 HTTP + code）。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(m5t02test.M5T02FaultController.class)
@Testcontainers
class ReplenishmentGenerationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM7 = 7L;
    private static final long TEAM8 = 8L;
    private static final long OPERATOR_ID = 1001L;

    /** SKU 9002：自带供应商 + 单价。 */
    private static final long SKU_WITH_SUPPLIER = 9002L;
    /** SKU 9003：无供应商，回退商品默认供应商。 */
    private static final long SKU_FALLBACK = 9003L;
    /** SKU 9004：无供应商且商品无默认供应商。 */
    private static final long SKU_NO_SUPPLIER = 9004L;

    private static final long PRODUCT_DEFAULT_SUPPLIER = 9001L;
    private static final long PRODUCT_NO_DEFAULT = 9003L;
    private static final long WAREHOUSE7 = 9004L;
    private static final long SUPPLIER_SKU = 8001L;
    private static final long SUPPLIER_PRODUCT = 8002L;

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReplenishmentGenerationService generationService;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM replenishment_reviews");
        jdbc.execute("DELETE FROM replenishment_items");
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM replenishment_policies");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-GEN-7', '生成组', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-GEN-8', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM7, TEAM8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES "
                + "(?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE')",
                OPERATOR_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES (?, ?, 'OPERATOR', 'ACTIVE')",
                TEAM7, OPERATOR_ID);

        jdbc.update("INSERT INTO suppliers (id, team_id, code, name) VALUES "
                + "(?, ?, 'SUP-A', '供应商A'), (?, ?, 'SUP-B', '供应商B')",
                SUPPLIER_SKU, TEAM7, SUPPLIER_PRODUCT, TEAM7);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, default_supplier_id) VALUES "
                        + "(?, ?, 'P-DEF', '有默认供应商', ?), "
                        + "(?, ?, 'P-NODEF', '无默认供应商', NULL)",
                PRODUCT_DEFAULT_SUPPLIER, TEAM7, SUPPLIER_PRODUCT, PRODUCT_NO_DEFAULT, TEAM7);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name) VALUES (?, ?, 'WH-GEN', '生成仓')",
                WAREHOUSE7, TEAM7);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, supplier_id, purchase_price) VALUES "
                        + "(?, ?, ?, 'SKU-SUP', '自带供应商', ?, 12.3456), "
                        + "(?, ?, ?, 'SKU-FB', '回退默认供应商', NULL, NULL), "
                        + "(?, ?, ?, 'SKU-NONE', '无供应商', NULL, 7.5000)",
                SKU_WITH_SUPPLIER, TEAM7, PRODUCT_DEFAULT_SUPPLIER, SUPPLIER_SKU,
                SKU_FALLBACK, TEAM7, PRODUCT_DEFAULT_SUPPLIER,
                SKU_NO_SUPPLIER, TEAM7, PRODUCT_NO_DEFAULT);

        TeamContext.set(new TeamContext.Snapshot(TEAM7, List.of("OPERATOR"), OPERATOR_ID, "operator01"));
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    // =====================================================================
    // C1/C5：正常生成 + qty_suggest=0 不生成
    // =====================================================================

    @Test
    @DisplayName("C1/C5 只生成 qty_suggest>0：3 条快照（110/50/0）⇒ 2 条 SUGGESTED，字段逐项正确")
    void generatesOnlyPositiveSuggestions() {
        long runId = insertRun(TEAM7, "RUN-GEN-C1", "SUCCEEDED");
        long item1 = insertItem(runId, SKU_WITH_SUPPLIER, PRODUCT_DEFAULT_SUPPLIER, "110.000", "HIGH", null, 7);
        long item2 = insertItem(runId, SKU_FALLBACK, PRODUCT_DEFAULT_SUPPLIER, "50.000", "MEDIUM", null, null);
        long item3 = insertItem(runId, SKU_NO_SUPPLIER, PRODUCT_NO_DEFAULT, "0.000", "NONE", null, 7);

        ReplenishmentGenerationResult result = generationService.generateFromRun(runId);

        assertEquals(2, result.createdCount());
        assertFalse(result.idempotent());
        assertEquals(2L, countItems(runId), "qty_suggest=0 的快照不得生成建议");
        assertEquals(0L, countBySource(item3), "0 量快照不应有对应建议");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_analysis_item_id, sku_id, product_id, warehouse_id, status, "
                        + "suggested_qty, qty, risk_level, supplier_id, purchase_price "
                        + "FROM replenishment_items WHERE source_analysis_item_id = ?", item1);
        assertEquals(item1, ((Number) row.get("source_analysis_item_id")).longValue());
        assertEquals(SKU_WITH_SUPPLIER, ((Number) row.get("sku_id")).longValue());
        assertEquals(PRODUCT_DEFAULT_SUPPLIER, ((Number) row.get("product_id")).longValue());
        assertEquals(WAREHOUSE7, ((Number) row.get("warehouse_id")).longValue());
        assertEquals("SUGGESTED", row.get("status"));
        assertEquals(0, new BigDecimal("110.000").compareTo((BigDecimal) row.get("suggested_qty")),
                "suggested_qty 应等于 qty_suggest 原值");
        assertEquals(0, new BigDecimal("110.000").compareTo((BigDecimal) row.get("qty")),
                "qty 初始应等于 suggested_qty");
        assertEquals("HIGH", row.get("risk_level"));
        assertEquals(SUPPLIER_SKU, ((Number) row.get("supplier_id")).longValue());
        assertEquals(0, new BigDecimal("12.3456").compareTo((BigDecimal) row.get("purchase_price")));

        // V4 取证：DB 原始输出（生成后）
        System.out.println("[M5T02-EVIDENCE] after generate: " + itemsOf(runId));
    }

    // =====================================================================
    // C2：幂等
    // =====================================================================

    @Test
    @DisplayName("C2 幂等：同 runId 再调 ⇒ 不新增，idempotent=true 且返回既有条数")
    void secondCallIsIdempotent() {
        long runId = insertRun(TEAM7, "RUN-GEN-C2", "SUCCEEDED");
        insertItem(runId, SKU_WITH_SUPPLIER, PRODUCT_DEFAULT_SUPPLIER, "10.000", "LOW", null, 7);
        insertItem(runId, SKU_FALLBACK, PRODUCT_DEFAULT_SUPPLIER, "20.000", "LOW", null, 7);

        ReplenishmentGenerationResult first = generationService.generateFromRun(runId);
        assertEquals(2, first.createdCount());
        System.out.println("[M5T02-EVIDENCE] first  call: " + itemsOf(runId));

        ReplenishmentGenerationResult second = generationService.generateFromRun(runId);
        assertEquals(0, second.createdCount(), "第二次不得新增");
        assertEquals(2, second.existingCount(), "应回显既有条数");
        assertTrue(second.idempotent(), "第二次应标记幂等命中");
        assertEquals(2L, countItems(runId), "总行数仍为 2");
        System.out.println("[M5T02-EVIDENCE] second call: " + itemsOf(runId));
    }

    // =====================================================================
    // C3：状态守卫
    // =====================================================================

    @Test
    @DisplayName("C3 状态守卫：FAILED/TIMEOUT/CANCELLED/RUNNING 全部拒绝（RPL-8001），且不落行")
    void rejectsNonGeneratableRunStatus() {
        for (String status : List.of("FAILED", "TIMEOUT", "CANCELLED", "RUNNING", "PENDING")) {
            long runId = insertRun(TEAM7, "RUN-GEN-" + status, status);
            BizException ex = assertThrows(BizException.class,
                    () -> generationService.generateFromRun(runId), "status=" + status);
            assertEquals(ErrorCodes.RPL_8001, ex.getErrorCode(), "status=" + status);
            assertEquals(0L, countItems(runId), "被拒时不得落任何建议：status=" + status);
        }
    }

    @Test
    @DisplayName("C3b DEGRADED 可生成（LLM 降级不影响规则结果）")
    void degradedRunIsGeneratable() {
        long runId = insertRun(TEAM7, "RUN-GEN-DEG", "DEGRADED");
        insertItem(runId, SKU_WITH_SUPPLIER, PRODUCT_DEFAULT_SUPPLIER, "30.000", "HIGH", null, 3);

        assertEquals(1, generationService.generateFromRun(runId).createdCount());
    }

    // =====================================================================
    // C4：跨团队
    // =====================================================================

    @Test
    @DisplayName("C4 跨团队 run ⇒ ANL-7005（404，与详情端点同码），且不落行")
    void rejectsOtherTeamRun() {
        long otherRun = insertRun(TEAM8, "RUN-GEN-OTHER", "SUCCEEDED");

        BizException ex = assertThrows(BizException.class, () -> generationService.generateFromRun(otherRun));

        assertEquals(ErrorCodes.ANL_7005, ex.getErrorCode());
        assertEquals(0L, countItems(otherRun));
    }

    // =====================================================================
    // C6：reason 截断与回退
    // =====================================================================

    @Test
    @DisplayName("C6 reason：llm_explanation 600 字符 ⇒ 落库 500（按字符，非字节）；为空 ⇒ 规则摘要")
    void reasonIsTruncatedByCharacters() {
        long runId = insertRun(TEAM7, "RUN-GEN-C6", "SUCCEEDED");
        String longLlm = "测".repeat(600);
        long withLlm = insertItem(runId, SKU_WITH_SUPPLIER, PRODUCT_DEFAULT_SUPPLIER, "110.000", "HIGH", longLlm, 7);
        long noLlm = insertItem(runId, SKU_FALLBACK, PRODUCT_DEFAULT_SUPPLIER, "50.000", "MEDIUM", null, 7);

        generationService.generateFromRun(runId);

        String truncated = jdbc.queryForObject(
                "SELECT reason FROM replenishment_items WHERE source_analysis_item_id = ?", String.class, withLlm);
        assertNotNull(truncated);
        assertEquals(500, truncated.length(), "★ 必须按字符截断到 500（字节截断会得到 166）");
        assertTrue(truncated.chars().allMatch(c -> c == '测'), "截断后应全为原字符");

        String fallback = jdbc.queryForObject(
                "SELECT reason FROM replenishment_items WHERE source_analysis_item_id = ?", String.class, noLlm);
        assertEquals("中风险，建议补货 50 件", fallback, "规则摘要模板固定可测");
    }

    // =====================================================================
    // C7/C8：供应商回退 + 期望到货日
    // =====================================================================

    @Test
    @DisplayName("C7/C8 supplier 回退链（SKU→商品默认→NULL）与 expected_arrival_date（UTC+lead/L为空）")
    void supplierFallbackAndExpectedArrival() {
        long runId = insertRun(TEAM7, "RUN-GEN-C7", "SUCCEEDED");
        long a = insertItem(runId, SKU_WITH_SUPPLIER, PRODUCT_DEFAULT_SUPPLIER, "10.000", "LOW", null, 7);
        long b = insertItem(runId, SKU_FALLBACK, PRODUCT_DEFAULT_SUPPLIER, "10.000", "LOW", null, null);
        long c = insertItem(runId, SKU_NO_SUPPLIER, PRODUCT_NO_DEFAULT, "10.000", "LOW", null, 0);

        generationService.generateFromRun(runId);

        Map<String, Object> rowA = itemRow(a);
        assertEquals(SUPPLIER_SKU, ((Number) rowA.get("supplier_id")).longValue(),
                "SKU 自带供应商优先");
        assertEquals(LocalDate.now(ZoneOffset.UTC).plusDays(7), ((java.sql.Date) rowA.get("expected_arrival_date")).toLocalDate(),
                "lead_time_days=7 ⇒ today(UTC)+7");
        assertEquals(0, new BigDecimal("12.3456").compareTo((BigDecimal) rowA.get("purchase_price")));

        Map<String, Object> rowB = itemRow(b);
        assertEquals(SUPPLIER_PRODUCT, ((Number) rowB.get("supplier_id")).longValue(),
                "SKU 无供应商 ⇒ 回退商品默认供应商");
        assertNull(rowB.get("expected_arrival_date"), "lead_time_days=NULL ⇒ 到货日为 NULL");

        Map<String, Object> rowC = itemRow(c);
        assertNull(rowC.get("supplier_id"), "SKU 与商品都无供应商 ⇒ NULL");
        assertEquals(LocalDate.now(ZoneOffset.UTC), ((java.sql.Date) rowC.get("expected_arrival_date")).toLocalDate(),
                "lead_time_days=0 ⇒ today(UTC)");
        assertEquals(0, new BigDecimal("7.5000").compareTo((BigDecimal) rowC.get("purchase_price")));
    }

    // =====================================================================
    // C9：空结果
    // =====================================================================

    @Test
    @DisplayName("C9 全部 qty_suggest<=0 ⇒ 返回 0 条，不报错、不落行")
    void emptyResultIsNotAnError() {
        long runId = insertRun(TEAM7, "RUN-GEN-C9", "SUCCEEDED");
        insertItem(runId, SKU_WITH_SUPPLIER, PRODUCT_DEFAULT_SUPPLIER, "0.000", "NONE", null, 7);
        insertItem(runId, SKU_FALLBACK, PRODUCT_DEFAULT_SUPPLIER, "0.000", "LOW", null, 7);

        ReplenishmentGenerationResult result = generationService.generateFromRun(runId);

        assertEquals(0, result.createdCount());
        assertFalse(result.idempotent());
        assertEquals(0L, countItems(runId));
    }

    // =====================================================================
    // V5：真实 HTTP 响应（状态守卫 / 跨团队）
    // =====================================================================

    @Test
    @DisplayName("V5 真实 HTTP：FAILED run ⇒ 422 RPL-8001；他团队 run ⇒ 404 ANL-7005")
    void httpResponsesForGuards() throws Exception {
        long failedRun = insertRun(TEAM7, "RUN-GEN-HTTP-F", "FAILED");
        long otherRun = insertRun(TEAM8, "RUN-GEN-HTTP-O", "SUCCEEDED");
        String token = loginAccessToken();

        ResponseEntity<String> status = get("/api/v1/test/m5t02/generate?run_id=" + failedRun, token);
        System.out.println("[M5T02-EVIDENCE] HTTP status  : " + status.getStatusCode().value() + " " + status.getBody());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, status.getStatusCode());
        assertEquals("RPL-8001", json.readTree(status.getBody()).get("code").asText());

        ResponseEntity<String> crossTeam = get("/api/v1/test/m5t02/generate?run_id=" + otherRun, token);
        System.out.println("[M5T02-EVIDENCE] HTTP cross   : " + crossTeam.getStatusCode().value()
                + " " + crossTeam.getBody());
        assertEquals(HttpStatus.NOT_FOUND, crossTeam.getStatusCode());
        assertEquals("ANL-7005", json.readTree(crossTeam.getBody()).get("code").asText());

        // 成功路径的 HTTP 形状（200 + createdCount）
        long okRun = insertRun(TEAM7, "RUN-GEN-HTTP-OK", "SUCCEEDED");
        insertItem(okRun, SKU_WITH_SUPPLIER, PRODUCT_DEFAULT_SUPPLIER, "10.000", "LOW", null, 7);
        ResponseEntity<String> ok = get("/api/v1/test/m5t02/generate?run_id=" + okRun, token);
        System.out.println("[M5T02-EVIDENCE] HTTP ok      : " + ok.getStatusCode().value() + " " + ok.getBody());
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals(1, json.readTree(ok.getBody()).get("createdCount").asInt());
    }

    // =====================================================================
    // E-2 编排出口：AnalysisRunCompletedEvent → 建议生成（M7-T01 补）
    // =====================================================================

    @Test
    @DisplayName("E-2 事件提交后自动生成建议；重放同一事件幂等（仍 1 条）")
    void completedEventTriggersGenerationIdempotently() {
        long runId = insertRun(TEAM7, "RUN-GEN-EVT", "SUCCEEDED");
        insertItem(runId, SKU_WITH_SUPPLIER, PRODUCT_DEFAULT_SUPPLIER, "110.000", "HIGH", null, 7);
        assertEquals(0L, countItems(runId), "事件发布前不得有建议");

        // AFTER_COMMIT 监听器只在事务提交后触发，因此必须在事务内发布
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(
                        new com.shoppilot.analysis.application.event.AnalysisRunCompletedEvent(runId, TEAM7)));
        assertEquals(1L, countItems(runId), "事件提交后应生成 1 条建议");

        // 重放：generateFromRun 先查后插，幂等
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(
                        new com.shoppilot.analysis.application.event.AnalysisRunCompletedEvent(runId, TEAM7)));
        assertEquals(1L, countItems(runId), "重复事件不得重复插入");
    }

    @Test
    @DisplayName("E-2 非成功态 run 的事件被吞掉：不生成建议、不抛异常（不污染已成功的分析）")
    void completedEventOnNonGeneratableRunIsSwallowed() {
        long runId = insertRun(TEAM7, "RUN-GEN-EVT-F", "FAILED");
        insertItem(runId, SKU_WITH_SUPPLIER, PRODUCT_DEFAULT_SUPPLIER, "110.000", "HIGH", null, 7);

        // 监听器内部 catch RuntimeException 只记 ERROR：不得向上抛
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(
                        new com.shoppilot.analysis.application.event.AnalysisRunCompletedEvent(runId, TEAM7)));
        assertEquals(0L, countItems(runId), "FAILED run 不得生成建议");
    }

    // =====================================================================
    // 种子与工具
    // =====================================================================

    private long insertRun(long teamId, String runNo, String status) {
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, algorithm_version) "
                + "VALUES (?, ?, ?, 'ALL_ACTIVE', 'avg-ma-3sigma-v1')", teamId, runNo, status);
        return jdbc.queryForObject("SELECT id FROM analysis_runs WHERE team_id = ? AND run_no = ?",
                Long.class, teamId, runNo);
    }

    private long insertItem(long runId, long skuId, long productId, String qtySuggest,
                            String riskLevel, String llmExplanation, Integer leadTimeDays) {
        jdbc.update("INSERT INTO analysis_items (team_id, run_id, sku_id, product_id, warehouse_id, "
                        + "on_hand_qty, risk_level, lead_time_source, avg_daily_sales, qty_base, qty_suggest, "
                        + "safety_stock_qty, llm_explanation, lead_time_days) VALUES (?, ?, ?, ?, ?, "
                        + "8.000, ?, 'SKU', 6.250, 104.500, ?, 25.000, ?, ?)",
                TEAM7, runId, skuId, productId, WAREHOUSE7, riskLevel,
                new BigDecimal(qtySuggest), llmExplanation, leadTimeDays);
        return jdbc.queryForObject("SELECT id FROM analysis_items WHERE run_id = ? AND sku_id = ?",
                Long.class, runId, skuId);
    }

    private long countItems(long runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_items WHERE run_id = ?", Long.class, runId);
    }

    private long countBySource(long sourceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_items WHERE source_analysis_item_id = ?",
                Long.class, sourceId);
    }

    private Map<String, Object> itemRow(long sourceId) {
        return jdbc.queryForMap("SELECT supplier_id, purchase_price, expected_arrival_date, reason "
                + "FROM replenishment_items WHERE source_analysis_item_id = ?", sourceId);
    }

    /** V4 取证：一行式 DB 快照（ASCII，避免控制台编码问题）。 */
    private String itemsOf(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, source_analysis_item_id, suggested_qty, qty, status FROM replenishment_items "
                        + "WHERE run_id = ? ORDER BY id", runId);
        StringBuilder sb = new StringBuilder("count=" + rows.size());
        for (Map<String, Object> row : rows) {
            sb.append(" | id=").append(row.get("id"))
                    .append(" src=").append(row.get("source_analysis_item_id"))
                    .append(" suggested=").append(row.get("suggested_qty"))
                    .append(" qty=").append(row.get("qty"))
                    .append(" status=").append(row.get("status"));
        }
        return sb.toString();
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String loginAccessToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"operator01\",\"password\":\"" + RAW_PASSWORD + "\"}", headers),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + response.getBody());
        return json.readTree(response.getBody()).get("data").get("accessToken").asText();
    }
}
