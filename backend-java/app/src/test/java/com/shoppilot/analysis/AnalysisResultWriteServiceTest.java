package com.shoppilot.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.shoppilot.analysis.application.AnalysisAppService;
import com.shoppilot.analysis.application.AnalysisResultWriteService;
import com.shoppilot.analysis.application.AnalysisRunSyncService;
import com.shoppilot.analysis.application.dto.AnalysisRunStatusResponse;
import com.shoppilot.analysis.domain.AnalysisRunStatus;
import com.shoppilot.analysis.domain.LlmStatus;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiErrorDetailData;
import com.shoppilot.analysis.infrastructure.ai.dto.AiResultItem;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRiskFinding;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunResultData;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunStatusData;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunSummary;
import com.shoppilot.analysis.infrastructure.scheduling.AnalysisRunPollTask;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * M4-B20.2 {@code AnalysisResultWriteService} + {@code AnalysisRunSyncService} 集成测试
 * —— 真实 MySQL + 真实租户线（{@code TEST_STRATEGY §3.2}：<b>禁止 Mock Mapper</b>）。
 *
 * <p>只 Mock {@link AiServiceClient}（外部 HTTP 对端），DB / Mapper / 租户线 / 事务 / 审计全真。
 *
 * <p>覆盖：五态映射、{@code COMPLETED} 落库全列、§8 降级四例 + PENDING fail-closed、
 * 18 项校验的八条负向（整批零落库）、{@code timeout_at} 兜底、{@code #15} 截断、
 * {@code #13} 去重、{@code data_start_date} 红线、定时任务默认关闭、{@code GET /runs/{id}}。
 */
@SpringBootTest(classes = ShopPilotApplication.class)
@Testcontainers
class AnalysisResultWriteServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OPERATOR_ID = 1001L;

    private static final long PRODUCT_A = 7101L;
    private static final long PRODUCT_OTHER = 7102L;
    private static final long WAREHOUSE = 7301L;
    private static final long WAREHOUSE_OTHER = 7302L;
    private static final long SKU_A = 7401L;
    private static final long SKU_B = 7402L;
    private static final long SKU_OTHER_TEAM = 7403L;
    private static final long SKU_INACTIVE = 7404L;

    private static final String ALGO = "avg-ma-3sigma-v1";
    private static final LocalDate DATE_FROM = LocalDate.of(2026, 9, 16);
    private static final LocalDate DATE_TO = LocalDate.of(2026, 10, 13);

    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @MockitoBean
    private AiServiceClient aiServiceClient;

    @Autowired
    private AnalysisRunSyncService syncService;

    @Autowired
    private AnalysisResultWriteService writeService;

    @Autowired
    private AnalysisAppService appService;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisRunMapper runMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM replenishment_policies");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM sales_daily");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-7', '七组', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-8', '八组', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, category, status) VALUES "
                        + "(?, ?, 'P-A', '商品A', 'CAT-A', 'ACTIVE'), "
                        + "(?, ?, 'P-X', '他团队商品', 'CAT-A', 'ACTIVE')",
                PRODUCT_A, TEAM_ID, PRODUCT_OTHER, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status, is_default) VALUES "
                        + "(?, ?, 'WH-A', '主仓', 'ACTIVE', 1), "
                        + "(?, ?, 'WH-X', '他团队仓', 'ACTIVE', 1)",
                WAREHOUSE, TEAM_ID, WAREHOUSE_OTHER, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                        + "(?, ?, ?, 'SKU-A', '规格A', 'ACTIVE'), "
                        + "(?, ?, ?, 'SKU-B', '规格B', 'ACTIVE'), "
                        + "(?, ?, ?, 'SKU-X', '他团队规格', 'ACTIVE'), "
                        + "(?, ?, ?, 'SKU-OFF', '停用', 'INACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_A, SKU_B, TEAM_ID, PRODUCT_A,
                SKU_OTHER_TEAM, OTHER_TEAM_ID, PRODUCT_OTHER, SKU_INACTIVE, TEAM_ID, PRODUCT_A);
        // 余额：SKU_A 有在途（验证 R132 回读），SKU_B 缺行（验证兜 0）
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                        + "in_transit_qty, committed_qty) VALUES (?, ?, ?, 8.000, 5.000, 1.000)",
                TEAM_ID, WAREHOUSE, SKU_A);

        TeamContext.set(TeamContext.Snapshot.of(TEAM_ID, List.of("OPERATOR"), OPERATOR_ID, "operator01"));
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    // =====================================================================
    // 五态映射（§5.38 裁定 #6）
    // =====================================================================

    @Test
    @DisplayName("ACCEPTED：只同步 progress/current_step/processed_skus，状态不变")
    void acceptedKeepsStatusAndSyncsProgress() {
        long runId = insertRunningRun("RUN-ACCEPT", 1);
        stubStatus(runId, "ACCEPTED", 0, null, 0, 1);

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        Map<String, Object> row = runRow(runId);
        assertEquals("RUNNING", row.get("status"), "ACCEPTED 不得改变本地状态");
        assertEquals(0L, num(row.get("progress")), "progress 直接取 Python 值（此处 0）");
        assertEquals(1L, num(row.get("processed_skus")), "processed_skus 同步为 Python 回传值");
    }

    @Test
    @DisplayName("RUNNING：PENDING→RUNNING 补 started_at，四列同步（progress 不自算，R119）")
    void runningAdvancesPendingAndSyncsProgress() {
        long runId = insertPendingRun("RUN-RUNNING", 1);
        stubStatus(runId, "RUNNING", 40, "RISK_RULES", 1, 1);

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        Map<String, Object> row = runRow(runId);
        assertEquals("RUNNING", row.get("status"));
        assertEquals(40L, num(row.get("progress")), "progress 必须直接取 Python 的档位值（§13.4 表）");
        assertEquals("RISK_RULES", row.get("current_step"));
        assertEquals(1L, num(row.get("processed_skus")));
        assertNotNull(row.get("started_at"), "PENDING→RUNNING 必须补 started_at");
        assertEquals("NOT_REQUESTED", row.get("llm_status"),
                "Python 未回 llm_status 时不得改动（初值由建行写入）");
    }

    @Test
    @DisplayName("FAILED：置 FAILED + 映射后的 error_code（不落库），Python 原码不进 DB")
    void failedMapsRemoteErrorCode() {
        long runId = insertRunningRun("RUN-FAILED", 1);
        when(aiServiceClient.fetchStatus(anyLong())).thenReturn(new AiRunStatusData(runId, "FAILED",
                40, "RISK_RULES", 1, 0, "SKIPPED", null, null,
                new AiErrorDetailData("AI-3001", "规则引擎执行失败", Map.of("stage", "RISK_RULES"))));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        Map<String, Object> row = runRow(runId);
        assertEquals("FAILED", row.get("status"));
        assertEquals(ErrorCodes.ANL_7002, row.get("error_code"), "AI-3001 → ANL-7002");
        assertNotNull(row.get("finished_at"));
        assertEquals(0L, count("analysis_items"), "FAILED 绝不落库");
        assertEquals(1L, finishAuditCount(runId), "FINISH 审计 1 条且 result=FAILURE");
    }

    @Test
    @DisplayName("CANCELLED：置 CANCELLED（不落库），FINISH 审计 result=SUCCESS")
    void cancelledAdvancesWithoutItems() {
        long runId = insertRunningRun("RUN-CANCELLED", 1);
        stubStatus(runId, "CANCELLED", 60, "REPLENISHMENT", 1, 1);

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        Map<String, Object> row = runRow(runId);
        assertEquals("CANCELLED", row.get("status"));
        assertEquals(0L, count("analysis_items"));
        assertEquals(1L, finishAuditCount(runId));
    }

    // =====================================================================
    // COMPLETED → 落库（V4）
    // =====================================================================

    @Test
    @DisplayName("V4 COMPLETED：items 落全列（含 R132 三列 + available_qty），run 置 SUCCEEDED/DONE/100")
    void completedWritesItemsAndFinishesRun() {
        long runId = insertRunningRun("RUN-OK", 2);
        stubStatus(runId, "COMPLETED", 100, "DONE", 2, 2);
        stubResult(runId, LlmStatus.SUCCEEDED.name(), resultItem(SKU_A, WAREHOUSE), resultItem(SKU_B, WAREHOUSE));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        assertEquals(2L, count("analysis_items"));
        Map<String, Object> item = itemRow(runId, SKU_A);
        assertEquals(PRODUCT_A, ((Number) item.get("product_id")).longValue(), "R132：product_id 回读主数据");
        assertEquals(new BigDecimal("5.000"), strip(item.get("in_transit_qty")), "R132：在途回读余额列");
        assertEquals(new BigDecimal("8.000"), strip(item.get("on_hand_qty")));
        assertEquals(new BigDecimal("7.000"), strip(item.get("available_qty")),
                "available = on_hand − committed = 7（R67：仅展示，不入 Q_base/IP）");
        assertEquals(new BigDecimal("7.000"), strip(item.get("inventory_position_qty")),
                "IP = on_hand + 有效在途 − committed = 8 + 0 − 1");
        assertEquals(new BigDecimal("25.000"), strip(item.get("qty_base")));
        assertEquals(new BigDecimal("110.000"), strip(item.get("qty_suggest")));
        assertEquals("HIGH", item.get("risk_level"));
        assertEquals(28L, num(item.get("sales_window_days")), "R132：窗口取请求 params");
        assertNotNull(item.get("risk_detail_json"));
        assertNotNull(item.get("data_quality_json"));
        // §8 细化规则 2：LLM 明细单独一列，不混装进 risk/data_quality JSON
        assertTrue(String.valueOf(item.get("llm_detail_json")).contains("llm_status"));
        assertFalse(String.valueOf(item.get("risk_detail_json")).contains("llm_status"),
                "risk_detail_json 不得混入 llm_status");
        assertFalse(String.valueOf(item.get("data_quality_json")).contains("llm_status"),
                "data_quality_json 不得混入 llm_status");

        // 缺余额行的 SKU_B：in_transit 兜 0
        assertEquals(new BigDecimal("0.000"), strip(itemRow(runId, SKU_B).get("in_transit_qty")));

        Map<String, Object> row = runRow(runId);
        assertEquals("SUCCEEDED", row.get("status"));
        assertEquals("DONE", row.get("current_step"));
        assertEquals(100L, num(row.get("progress")));
        assertEquals(2L, num(row.get("total_skus")));
        assertEquals(2L, num(row.get("processed_skus")));
        assertEquals(2L, num(row.get("risk_sku_count")), "两条目 risk_level=HIGH ⇒ 2（自算，不采信 Python 的 summary）");
        assertEquals(2L, num(row.get("suggestion_count")), "两条目 q_suggest=110 > 0 ⇒ 2（自算，裁定 #19）");
        assertNotNull(row.get("summary_json"));
        assertNotNull(row.get("finished_at"));
        assertEquals(1L, finishAuditCount(runId));
    }

    // =====================================================================
    // 取证打印（V4 / V5 / V6 / V7；不参与断言，只把原始 SQL 结果落到 surefire 输出）
    // =====================================================================

    @Test
    @DisplayName("取证：V4 落库原始行 / V5 降级 / V6 超时 / V7 零落库")
    void evidencePrints() {
        // ---- V4：真实 COMPLETED 结果的 analysis_items 原始行 ----
        long okRun = insertRunningRun("RUN-EV-OK", 2);
        stubStatus(okRun, "COMPLETED", 100, "DONE", 2, 2);
        stubResult(okRun, "SUCCEEDED", resultItem(SKU_A, WAREHOUSE), resultItem(SKU_B, WAREHOUSE));
        syncService.syncFromPython(loadRun(okRun));
        System.out.println("[V4] run status=" + runRow(okRun).get("status")
                + " llm=" + runRow(okRun).get("llm_status")
                + " progress=" + runRow(okRun).get("progress")
                + " risk_sku_count=" + runRow(okRun).get("risk_sku_count")
                + " suggestion_count=" + runRow(okRun).get("suggestion_count"));
        System.out.println("[V4] " + jdbc.queryForList(
                "SELECT sku_id, product_id, on_hand_qty, in_transit_qty, available_qty, "
                        + "inventory_position_qty, risk_level, qty_base, qty_suggest, llm_detail_json "
                        + "FROM analysis_items WHERE run_id = ? ORDER BY sku_id", okRun));

        // ---- V5：llm_status=TIMEOUT ⇒ DEGRADED 但规则结果照常落库、解释为 NULL ----
        long degRun = insertRunningRun("RUN-EV-DEG", 1);
        stubStatus(degRun, "COMPLETED", 100, "DONE", 1, 1);
        stubResult(degRun, "TIMEOUT", resultItem(SKU_A, WAREHOUSE));
        syncService.syncFromPython(loadRun(degRun));
        System.out.println("[V5] status=" + runRow(degRun).get("status")
                + " llm_status=" + runRow(degRun).get("llm_status")
                + " llm_error_message=" + runRow(degRun).get("llm_error_message")
                + " | items=" + count("analysis_items")
                + " | llm_explanation IS NULL="
                + (jdbc.queryForObject("SELECT llm_explanation IS NULL FROM analysis_items WHERE run_id=?",
                        Boolean.class, degRun)));

        // ---- V6：timeout_at 已过 ⇒ TIMEOUT / ANL-7003 / FINISH 审计 1 条 ----
        long toRun = insertRunningRun("RUN-EV-TO", 1);
        jdbc.update("UPDATE analysis_runs SET timeout_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5), toRun);
        syncService.markTimeout(loadRun(toRun));
        System.out.println("[V6] status=" + runRow(toRun).get("status")
                + " error_code=" + runRow(toRun).get("error_code")
                + " error_message=" + runRow(toRun).get("error_message")
                + " | FINISH audit=" + finishAuditCount(toRun));

        // ---- V7：注入跨团队 sku_id ⇒ 零落库 ----
        long badRun = insertRunningRun("RUN-EV-BAD", 1);
        jdbc.update("UPDATE analysis_runs SET total_skus = 2 WHERE id = ?", badRun);
        stubStatus(badRun, "COMPLETED", 100, "DONE", 2, 2);
        stubResult(badRun, "SUCCEEDED", itemWithSku(SKU_OTHER_TEAM));
        int before = (int) count("analysis_items");
        syncService.syncFromPython(loadRun(badRun));
        System.out.println("[V7] items before=" + before + " after=" + count("analysis_items")
                + " | status=" + runRow(badRun).get("status")
                + " error_code=" + runRow(badRun).get("error_code"));
    }

    // =====================================================================
    // §8 降级
    // =====================================================================

    @Test
    @DisplayName("§8：llm_status=FAILED/TIMEOUT/VALIDATION_FAILED ⇒ DEGRADED 且规则结果照常落库")
    void degradedLlmStillPersistsRuleResults() {
        for (String llmStatus : List.of("FAILED", "TIMEOUT", "VALIDATION_FAILED")) {
            resetTables();
            long runId = insertRunningRun("RUN-DEG-" + llmStatus, 1);
            stubStatus(runId, "COMPLETED", 100, "DONE", 1, 1);
            AiResultItem item = resultItem(SKU_A, WAREHOUSE);
            stubResult(runId, llmStatus, item.llmExplanation() == null
                    ? item : itemItemWith(item, llmStatus, null));

            assertTrue(syncService.syncFromPython(loadRun(runId)), llmStatus);

            assertEquals("DEGRADED", runRow(runId).get("status"), llmStatus + " ⇒ DEGRADED");
            assertEquals(1L, count("analysis_items"), llmStatus + " 时规则结果必须照常落库");
            assertNotNull(runRow(runId).get("llm_error_message"), "DEGRADED 必须记录降级原因");
            assertNull(strip(itemRow(runId, SKU_A).get("llm_explanation")),
                    llmStatus + " 时 llm_explanation 为 NULL");
        }
    }

    @Test
    @DisplayName("§8：SKIPPED / NOT_REQUESTED ⇒ SUCCEEDED（不视为降级）")
    void skippedAndNotRequestedAreSucceeded() {
        for (String llmStatus : List.of("SKIPPED", "NOT_REQUESTED")) {
            resetTables();
            long runId = insertRunningRun("RUN-SK-" + llmStatus, 1);
            stubStatus(runId, "COMPLETED", 100, "DONE", 1, 1);
            stubResult(runId, llmStatus, resultItem(SKU_A, WAREHOUSE));

            assertTrue(syncService.syncFromPython(loadRun(runId)), llmStatus);

            assertEquals("SUCCEEDED", runRow(runId).get("status"), llmStatus + " ⇒ SUCCEEDED");
            assertNull(runRow(runId).get("llm_error_message"));
            assertEquals(1L, count("analysis_items"));
        }
    }

    @Test
    @DisplayName("裁定 #17：COMPLETED 却回 llm_status=PENDING ⇒ FAILED + ANL-7004，零落库（fail-closed）")
    void pendingLlmOnCompletedFailsClosed() {
        long runId = insertRunningRun("RUN-PENDING-LLM", 1);
        stubStatus(runId, "COMPLETED", 100, "DONE", 1, 1);
        stubResult(runId, "PENDING", resultItem(SKU_A, WAREHOUSE));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        Map<String, Object> row = runRow(runId);
        assertEquals("FAILED", row.get("status"));
        assertEquals(ErrorCodes.ANL_7004, row.get("error_code"));
        assertEquals(0L, count("analysis_items"));
    }

    // =====================================================================
    // 18 项校验：八条负向，每条都必须「整批零落库」
    // =====================================================================

    @Test
    @DisplayName("§7 #2：跨团队 sku_id ⇒ FAILED/ANL-7004 + 零落库（V7）")
    void crossTeamSkuRejected() {
        assertValidationFailure("跨团队 SKU", itemWithSku(SKU_OTHER_TEAM));
    }

    @Test
    @DisplayName("§7 #2：INACTIVE sku ⇒ FAILED/ANL-7004 + 零落库")
    void inactiveSkuRejected() {
        assertValidationFailure("停用 SKU", itemWithSku(SKU_INACTIVE));
    }

    @Test
    @DisplayName("§7 #3：跨团队 warehouse_id ⇒ FAILED/ANL-7004 + 零落库")
    void crossTeamWarehouseRejected() {
        long runId = insertRunningRun("RUN-WH", 1);
        stubStatus(runId, "COMPLETED", 100, "DONE", 1, 1);
        stubResult(runId, "SKIPPED", resultItem(SKU_A, WAREHOUSE_OTHER));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        assertFailedWithZeroItems(runId);
    }

    @Test
    @DisplayName("§7 #4：非法 risk_level（CRITICAL）⇒ FAILED/ANL-7004 + 零落库（fail-closed）")
    void illegalRiskLevelRejected() {
        assertValidationFailure("非法 risk_level", itemWith("riskLevel", "CRITICAL"));
    }

    @Test
    @DisplayName("§7 #5：risk_types 含枚举外取值 ⇒ FAILED/ANL-7004 + 零落库")
    void illegalRiskTypeRejected() {
        assertValidationFailure("非法 risk_types", itemWith("riskTypes", List.of("LOWSTOCK")));
    }

    @Test
    @DisplayName("§7 #7：q_suggest 为负 ⇒ FAILED/ANL-7004 + 零落库")
    void negativeSuggestRejected() {
        assertValidationFailure("负 q_suggest", itemWith("qSuggest", "-1.000"));
    }

    @Test
    @DisplayName("§7 #8：数量 4 位小数 ⇒ FAILED/ANL-7004 + 零落库")
    void excessiveScaleRejected() {
        assertValidationFailure("4 位小数", itemWith("qBase", "1.2345"));
    }

    @Test
    @DisplayName("§7 #11：algorithm_version 与请求不一致 ⇒ FAILED/ANL-7004 + 零落库")
    void algorithmVersionMismatchRejected() {
        long runId = insertRunningRun("RUN-ALGO", 1);
        stubStatus(runId, "COMPLETED", 100, "DONE", 1, 1);
        stubResultWithAlgo(runId, "SKIPPED", "other-algo-v9", resultItem(SKU_A, WAREHOUSE));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        assertFailedWithZeroItems(runId);
    }

    @Test
    @DisplayName("§7 #12：条数超过 run.total_skus ⇒ FAILED/ANL-7004 + 零落库")
    void tooManyItemsRejected() {
        long runId = insertRunningRun("RUN-TOOMANY", 1);
        stubStatus(runId, "COMPLETED", 100, "DONE", 2, 2);
        stubResult(runId, "SKIPPED", resultItem(SKU_A, WAREHOUSE), resultItem(SKU_B, WAREHOUSE));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        assertFailedWithZeroItems(runId);
    }

    @Test
    @DisplayName("§7 #16：SKU 不在 SELECTED_SKUS 范围 ⇒ FAILED/ANL-7004 + 零落库")
    void outOfScopeSkuRejected() {
        // run 的 scope 只含 SKU_A，但结果返回 SKU_B
        long runId = insertRunWithScope("RUN-SCOPE", List.of(SKU_A), 2);
        stubStatus(runId, "COMPLETED", 100, "DONE", 2, 2);
        stubResult(runId, "SKIPPED", resultItem(SKU_B, WAREHOUSE));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        assertFailedWithZeroItems(runId);
    }

    // =====================================================================
    // 兜底行为
    // =====================================================================

    @Test
    @DisplayName("§7 #15：llm_explanation 超 2000 字符 ⇒ 截断且【不】 FAILED")
    void longExplanationTruncatedNotFailed() {
        long runId = insertRunningRun("RUN-LONG-LLM", 1);
        stubStatus(runId, "COMPLETED", 100, "DONE", 1, 1);
        stubResult(runId, "SUCCEEDED",
                itemItemWith(resultItem(SKU_A, WAREHOUSE), "SUCCEEDED", "x".repeat(2500)));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        assertEquals("SUCCEEDED", runRow(runId).get("status"), "超长解释不得导致 FAILED（§7 第 15 项）");
        String stored = jdbc.queryForObject(
                "SELECT llm_explanation FROM analysis_items WHERE run_id = ?", String.class, runId);
        assertEquals(2000, stored.length(), "应截断到 2000 字符");
    }

    @Test
    @DisplayName("§7 #13：(sku_id, warehouse_id) 重复 ⇒ 保留首个，落 1 行")
    void duplicateSkuKeepsFirst() {
        long runId = insertRunningRun("RUN-DUP", 2);
        stubStatus(runId, "COMPLETED", 100, "DONE", 2, 2);
        stubResult(runId, "SUCCEEDED",
                resultItem(SKU_A, WAREHOUSE),
                itemItemWith(resultItem(SKU_A, WAREHOUSE), "SUCCEEDED", null));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        assertEquals(1L, count("analysis_items"), "重复项必须去重后落 1 行");
        assertEquals("SUCCEEDED", runRow(runId).get("status"));
    }

    @Test
    @DisplayName("§5.38 三节红线：已有 data_start_date/data_end_date 时不得被结果覆盖")
    void dataWindowIsNotOverwritten() {
        // run 的窗口是 2026-09-16..2026-10-13；结果回传一个不同但合法的区间
        long runId = insertRunningRun("RUN-WINDOW", 1);
        stubStatus(runId, "COMPLETED", 100, "DONE", 1, 1);
        stubResultWithWindow(runId, "SKIPPED", ALGO, "2026-10-01", "2026-10-05",
                resultItem(SKU_A, WAREHOUSE));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        assertEquals("SUCCEEDED", runRow(runId).get("status"), "回传区间在请求窗口内 ⇒ 合法");
        assertEquals(DATE_FROM, date(runRow(runId).get("data_start_date")), "已有值不得被覆盖");
        assertEquals(DATE_TO, date(runRow(runId).get("data_end_date")), "已有值不得被覆盖");
    }

    @Test
    @DisplayName("§7 #10：结果区间超出请求窗口 ⇒ FAILED/ANL-7004 + 零落库")
    void outOfWindowResultRejected() {
        long runId = insertRunningRun("RUN-OUTWIN", 1);
        stubStatus(runId, "COMPLETED", 100, "DONE", 1, 1);
        stubResultWithWindow(runId, "SKIPPED", ALGO, "2026-01-01", "2026-01-31",
                resultItem(SKU_A, WAREHOUSE));

        assertTrue(syncService.syncFromPython(loadRun(runId)));

        assertFailedWithZeroItems(runId);
    }

    // =====================================================================
    // timeout_at 兜底（V6）
    // =====================================================================

    @Test
    @DisplayName("V6 timeout_at 已过 ⇒ TIMEOUT + ANL-7003 + FINISH 审计 1 条")
    void timeoutFallbackMarksTimeout() {
        long runId = insertRunningRun("RUN-TIMEOUT", 1);
        jdbc.update("UPDATE analysis_runs SET timeout_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5), runId);

        assertTrue(syncService.markTimeout(loadRun(runId)));

        Map<String, Object> row = runRow(runId);
        assertEquals("TIMEOUT", row.get("status"));
        assertEquals(ErrorCodes.ANL_7003, row.get("error_code"));
        assertEquals("分析任务执行超时（300s）", row.get("error_message"));
        assertNotNull(row.get("finished_at"));
        assertEquals(1L, finishAuditCount(runId));
        assertEquals(0L, count("analysis_items"), "超时绝不落库");
    }

    @Test
    @DisplayName("timeout 兜底幂等：已是终态的行再调 ⇒ 影响 0 行，不覆盖既有终态")
    void timeoutFallbackIsIdempotent() {
        long runId = insertRunningRun("RUN-TIMEOUT2", 1);
        jdbc.update("UPDATE analysis_runs SET timeout_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5), runId);

        assertTrue(syncService.markTimeout(loadRun(runId)));
        assertFalse(syncService.markTimeout(loadRun(runId)), "CAS 失败 ⇒ 直接跳过");

        assertEquals("TIMEOUT", runRow(runId).get("status"));
        assertEquals(1L, finishAuditCount(runId), "不得重复写审计");
    }

    // =====================================================================
    // 定时任务与对外端点
    // =====================================================================

    @Test
    @DisplayName("定时任务默认关闭：@SpringBootTest 环境下 AnalysisRunPollTask Bean 不存在")
    void pollTaskDisabledByDefault() {
        assertEquals(0, context.getBeanNamesForType(AnalysisRunPollTask.class).length,
                "shoppilot.analysis.poll.enabled 默认 false ⇒ 定时任务与调度都不应装配"
                        + "（§5.38 裁定 #8：34+ 个 @SpringBootTest 不能在后台打 Python）");
    }

    @Test
    @DisplayName("GET /runs/{id}：中间态触发同步；Python 不可用 ⇒ 200 + 库内现状（R134 静默降级）")
    void readTimeSyncDegradesSilently() {
        long runId = insertRunningRun("RUN-READ", 1);
        when(aiServiceClient.fetchStatus(anyLong()))
                .thenThrow(new BizException(ErrorCodes.ANL_7003, "AI 分析服务响应超时，请稍后重试"));

        AnalysisRunStatusResponse response = appService.getRunStatus(runId);

        assertNotNull(response);
        assertEquals("RUNNING", response.status());
        assertEquals(runId, response.runId());
        assertEquals(AnalysisRunStatus.RUNNING.name(), runRow(runId).get("status"),
                "同步失败不得改变库内状态");
    }

    @Test
    @DisplayName("GET /runs/{id}：跨团队 / 不存在 ⇒ ANL-7005（不区分两者）")
    void crossTeamOrMissingRunIsAnl7005() {
        long otherRun = insertRunForTeam(OTHER_TEAM_ID, "RUN-OTHER-TEAM");

        assertEquals(ErrorCodes.ANL_7005,
                assertThrows(BizException.class, () -> appService.getRunStatus(otherRun)).getErrorCode());
        assertEquals(ErrorCodes.ANL_7005,
                assertThrows(BizException.class, () -> appService.getRunStatus(999999L)).getErrorCode());
    }

    @Test
    @DisplayName("GET /runs/{id}：终态不触发同步（Java 是 AnalysisRunStatus 权威）")
    void terminalRunDoesNotSync() {
        long runId = insertRunningRun("RUN-TERM", 1);
        jdbc.update("UPDATE analysis_runs SET status = 'SUCCEEDED', finished_at = NOW(3) WHERE id = ?", runId);

        AnalysisRunStatusResponse response = appService.getRunStatus(runId);

        assertEquals("SUCCEEDED", response.status());
        org.mockito.Mockito.verify(aiServiceClient, org.mockito.Mockito.never()).fetchStatus(anyLong());
    }

    @Test
    @DisplayName("R135：createRun 落库的 total_skus 是装配后实际数（ALL_ACTIVE 不再是 0）")
    void totalSkusIsAssembledCount() {
        AnalysisRunStatusResponse response = appService.getRunStatus(
                createAllActiveRun("idem-r135"));
        assertEquals(2, response.totalSkus(),
                "本夹具有 2 个 ACTIVE SKU（SKU_A / SKU_B；SKU_INACTIVE 不计）"
                        + " ⇒ total_skus 应为装配后实际数而非 0（R135）");
    }

    // =====================================================================
    // 夹具与工具
    // =====================================================================

    private void assertValidationFailure(String label, AiResultItem item) {
        resetTables();
        long runId = insertRunningRun("RUN-NEG-" + label.hashCode(), 1);
        stubStatus(runId, "COMPLETED", 100, "DONE", 1, 1);
        stubResult(runId, "SUCCEEDED", item);

        assertTrue(syncService.syncFromPython(loadRun(runId)), label);
        assertFailedWithZeroItems(runId);
    }

    private void assertFailedWithZeroItems(long runId) {
        Map<String, Object> row = runRow(runId);
        assertEquals("FAILED", row.get("status"));
        assertEquals(ErrorCodes.ANL_7004, row.get("error_code"), "二次校验失败一律 ANL-7004");
        assertNotNull(row.get("finished_at"));
        assertEquals(0L, count("analysis_items"), "任一 FAILED 级失败 ⇒ 整批零落库");
        assertEquals(1L, finishAuditCount(runId));
    }

    private void resetTables() {
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM audit_logs");
    }

    private void stubStatus(long runId, String executionStatus, Integer progress, String step,
                            Integer totalSkus, Integer processedSkus) {
        when(aiServiceClient.fetchStatus(runId)).thenReturn(new AiRunStatusData(runId, executionStatus,
                progress, step, totalSkus, processedSkus, null, null, null, null));
    }

    private void stubResult(long runId, String llmStatus, AiResultItem... items) {
        stubResultWithWindow(runId, llmStatus, ALGO, DATE_FROM.toString(), DATE_TO.toString(), items);
    }

    private void stubResultWithAlgo(long runId, String llmStatus, String algorithmVersion,
                                    AiResultItem... items) {
        stubResultWithWindow(runId, llmStatus, algorithmVersion,
                DATE_FROM.toString(), DATE_TO.toString(), items);
    }

    private void stubResultWithWindow(long runId, String llmStatus, String algorithmVersion,
                                     String dataStart, String dataEnd, AiResultItem... items) {
        when(aiServiceClient.fetchResult(runId)).thenReturn(new AiRunResultData(runId, "COMPLETED",
                algorithmVersion, "explain_risk.v1", "gpt-4o-mini", llmStatus,
                dataStart, dataEnd,
                new AiRunSummary(items.length, 1, 1, 0, Map.of("HIGH", 1)),
                List.of(items)));
    }

    /** 合法条目（默认 SKU_A + 默认仓）。 */
    private AiResultItem resultItem(long skuId, Long warehouseId) {
        return new AiResultItem(skuId, warehouseId, "6.250", "175.000", 28, "GOOD", "HIGH",
                List.of("STOCKOUT", "LOW_STOCK"),
                List.of(new AiRiskFinding("R-LOWSTOCK", Map.of("IP", "8.000"),
                        "IP < D * (L + R) = 87.500", "MEDIUM")),
                "8.000", "0.000", "1.000", "7.000", "25.000", 7, "SKU", 7, "0.4200",
                false, "25.000", "50.000", "10.000", "110.000", false,
                null, List.of(), "SKIPPED", List.of());
    }

    private AiResultItem itemWithSku(long skuId) {
        return new AiResultItem(skuId, WAREHOUSE, "6.250", "175.000", 28, "GOOD", "HIGH",
                List.of(), List.of(), "8.000", "0.000", "1.000", "7.000", "25.000", 7, "SKU", 7,
                "0.4200", false, "25.000", "50.000", "10.000", "110.000", false,
                null, List.of(), "SKIPPED", List.of());
    }

    /** 只替换一个字段（其余保持合法）。 */
    private AiResultItem itemWith(String field, Object value) {
        AiResultItem base = itemWithSku(SKU_A);
        return switch (field) {
            case "riskLevel" -> new AiResultItem(base.skuId(), base.warehouseId(), base.avgDailySales(),
                    base.salesQtyTotal(), base.validDays(), base.dataQuality(), (String) value,
                    base.riskTypes(), base.riskFindings(), base.onHandQty(), base.effectiveInTransitQty(),
                    base.committedQty(), base.inventoryPosition(), base.safetyStock(), base.leadTimeDays(),
                    base.leadTimeSource(), base.coverageDays(), base.cv(), base.volatilityAdjusted(),
                    base.qBase(), base.moqApplied(), base.orderMultipleApplied(), base.qSuggest(),
                    base.truncatedByBudget(), base.llmExplanation(), base.llmEvidence(), base.llmStatus(),
                    base.warnings());
            case "riskTypes" -> new AiResultItem(base.skuId(), base.warehouseId(), base.avgDailySales(),
                    base.salesQtyTotal(), base.validDays(), base.dataQuality(), base.riskLevel(),
                    castStringList(value), base.riskFindings(), base.onHandQty(), base.effectiveInTransitQty(),
                    base.committedQty(), base.inventoryPosition(), base.safetyStock(), base.leadTimeDays(),
                    base.leadTimeSource(), base.coverageDays(), base.cv(), base.volatilityAdjusted(),
                    base.qBase(), base.moqApplied(), base.orderMultipleApplied(), base.qSuggest(),
                    base.truncatedByBudget(), base.llmExplanation(), base.llmEvidence(), base.llmStatus(),
                    base.warnings());
            default -> new AiResultItem(base.skuId(), base.warehouseId(), base.avgDailySales(),
                    base.salesQtyTotal(), base.validDays(), base.dataQuality(), base.riskLevel(),
                    base.riskTypes(), base.riskFindings(), base.onHandQty(), base.effectiveInTransitQty(),
                    base.committedQty(), base.inventoryPosition(), base.safetyStock(), base.leadTimeDays(),
                    base.leadTimeSource(), base.coverageDays(), base.cv(), base.volatilityAdjusted(),
                    base.qBase(), base.moqApplied(), base.orderMultipleApplied(), (String) value,
                    base.truncatedByBudget(), base.llmExplanation(), base.llmEvidence(), base.llmStatus(),
                    base.warnings());
        };
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object value) {
        return (List<String>) value;
    }

    private AiResultItem itemItemWith(AiResultItem base, String llmStatus, String explanation) {
        return new AiResultItem(base.skuId(), base.warehouseId(), base.avgDailySales(),
                base.salesQtyTotal(), base.validDays(), base.dataQuality(), base.riskLevel(),
                base.riskTypes(), base.riskFindings(), base.onHandQty(), base.effectiveInTransitQty(),
                base.committedQty(), base.inventoryPosition(), base.safetyStock(), base.leadTimeDays(),
                base.leadTimeSource(), base.coverageDays(), base.cv(), base.volatilityAdjusted(),
                base.qBase(), base.moqApplied(), base.orderMultipleApplied(), base.qSuggest(),
                base.truncatedByBudget(), explanation, base.llmEvidence(), llmStatus, base.warnings());
    }

    private long insertRunningRun(String runNo, int totalSkus) {
        return insertRunWithScope(runNo, List.of(SKU_A, SKU_B), totalSkus, "RUNNING", TEAM_ID);
    }

    private long insertPendingRun(String runNo, int totalSkus) {
        return insertRunWithScope(runNo, List.of(SKU_A, SKU_B), totalSkus, "PENDING", TEAM_ID);
    }

    private long insertRunWithScope(String runNo, List<Long> skuIds, int totalSkus) {
        return insertRunWithScope(runNo, skuIds, totalSkus, "RUNNING", TEAM_ID);
    }

    private long insertRunWithScope(String runNo, List<Long> skuIds, int totalSkus,
                                    String status, long teamId) {
        ArrayNode node = json.createArrayNode();
        skuIds.forEach(node::add);
        var scopeNode = json.createObjectNode();
        scopeNode.set("sku_ids", node);
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, scope_json, "
                        + "warehouse_id, params_json, algorithm_version, prompt_version, model_name, "
                        + "llm_status, data_start_date, data_end_date, total_skus, processed_skus, "
                        + "progress, idempotency_key, triggered_by, timeout_at) "
                        + "VALUES (?, ?, ?, 'SELECTED_SKUS', ?, ?, ?, ?, 'explain_risk.v1', 'gpt-4o-mini', "
                        + "'NOT_REQUESTED', ?, ?, ?, 0, 0, ?, ?, NOW(3) + INTERVAL 300 SECOND)",
                teamId, runNo, status, scopeNode.toString(), WAREHOUSE,
                "{\"window_days\":28,\"min_days\":7,\"coverage_days\":7,\"lead_time_default\":7,"
                        + "\"ss_ratio\":0.5000,\"cv_high\":1.0000,\"budget_limit\":null,"
                        + "\"enable_llm\":false}",
                ALGO, DATE_FROM.toString(), DATE_TO.toString(), totalSkus,
                "idem-" + runNo, OPERATOR_ID);
        return jdbc.queryForObject("SELECT id FROM analysis_runs WHERE run_no = ?", Long.class, runNo);
    }

    private long insertRunForTeam(long teamId, String runNo) {
        return insertRunWithScope(runNo, List.of(SKU_A), 1, "RUNNING", teamId);
    }

    private long createAllActiveRun(String idempotencyKey) {
        var object = json.createObjectNode();
        return appService.createRun(new com.shoppilot.analysis.application.dto.CreateAnalysisRunRequest(
                        "ALL_ACTIVE", null, WAREHOUSE, DATE_FROM, DATE_TO, null, ALGO,
                        "explain_risk.v1", "gpt-4o-mini"), idempotencyKey).data().runId();
    }

    /** 从库里读回 run（真实 Mapper + 真实租户线；TeamContext 已由 @BeforeEach 建立）。 */
    private com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity loadRun(long runId) {
        return runMapper.selectById(runId);
    }

    private Map<String, Object> runRow(long runId) {
        return jdbc.queryForMap("SELECT status, progress, current_step, processed_skus, total_skus, "
                + "risk_sku_count, suggestion_count, error_code, error_message, llm_status, "
                + "llm_error_message, started_at, finished_at, data_start_date, data_end_date, "
                + "summary_json FROM analysis_runs WHERE id = ?", runId);
    }

    private Map<String, Object> itemRow(long runId, long skuId) {
        return jdbc.queryForMap("SELECT product_id, on_hand_qty, in_transit_qty, "
                + "effective_in_transit_qty, committed_qty, inventory_position_qty, available_qty, "
                + "avg_daily_sales, cv, sales_window_days, valid_days, qty_base, qty_suggest, "
                + "moq_applied, order_multiple_applied, risk_level, risk_types, risk_detail_json, "
                + "data_quality_json, llm_detail_json, llm_explanation, lead_time_days, lead_time_source "
                + "FROM analysis_items WHERE run_id = ? AND sku_id = ?", runId, skuId);
    }

    private long finishAuditCount(long runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE biz_id = ? "
                + "AND action = 'ANALYSIS_RUN_FINISH'", Long.class, runId);
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    /** JDBC 对 MySQL 整型列可能返回 Integer/Long，统一成 long 再断言（避免装箱类型假失败）。 */
    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    /** JDBC 对 DATE 列返回 {@link java.sql.Date}，统一成 {@link LocalDate} 再断言。 */
    private static LocalDate date(Object value) {
        return value == null ? null : ((java.sql.Date) value).toLocalDate();
    }

    private static BigDecimal strip(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value)).setScale(3, java.math.RoundingMode.HALF_UP);
    }
}