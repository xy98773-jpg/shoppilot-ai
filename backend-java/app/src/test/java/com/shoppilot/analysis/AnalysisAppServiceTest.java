package com.shoppilot.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.analysis.application.AnalysisAppService;
import com.shoppilot.analysis.application.dto.CreateAnalysisRunRequest;
import com.shoppilot.analysis.application.dto.CreateRunOutcome;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateEnvelope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateRequest;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * M4-T03（B15）{@code AnalysisAppService.createRun} 集成测试 —— 真实 MySQL + 真实租户线 + 真实审计。
 *
 * <p>覆盖任务书 V2：
 * <ul>
 *   <li>幂等四态（缺 key / 同键同体 / 同键异体 / 同键进行中；缺 key 的 HTTP 语义另由
 *       {@code AnalysisApiIntegrationTest} 验）；</li>
 *   <li>并发三 scope：{@code SELECTED_SKUS} 冲突/放行/跨团队放行、{@code ALL_ACTIVE} 冲突、
 *       {@code CATEGORY} 同类目冲突 + 跨类型不冲突（反向验证）；</li>
 *   <li>{@code run_no} 格式 {@code ^RUN-\d{8}-\d{4}$} 与同日递增；</li>
 *   <li>发起失败置 {@code FAILED}（{@code error_code=ANL-7002}、{@code error_message} 脱敏、
 *       {@code finished_at} 非空、不留 {@code PENDING} 悬挂）；</li>
 *   <li>参数校验 {@code ANL-7007 / 7009 / 7013} 与 {@code COM-0001}；</li>
 *   <li>{@code team_id} 只来自 {@link TeamContext}。</li>
 * </ul>
 *
 * <p>★ {@link AiServiceClient} 用 {@code @MockitoBean} 替换：本类只验证「Java 侧编排与落库」，
 * 真实 HTTP 行为由 {@code AiServiceClientTest}（假对端）与 V4 真栈（404 → ANL-7002）覆盖。
 */
@SpringBootTest(classes = ShopPilotApplication.class)
@Testcontainers
class AnalysisAppServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OPERATOR_ID = 1001L;
    private static final long OTHER_OPERATOR_ID = 2001L;

    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;
    private static final long WAREHOUSE_ID = 9004L;

    private static final String ALGO = "avg-ma-3sigma-v1";
    private static final String PROMPT = "explain_risk.v1";
    private static final LocalDate DATE_FROM = LocalDate.of(2026, 9, 16);
    private static final LocalDate DATE_TO = LocalDate.of(2026, 10, 13);

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
    private AnalysisAppService appService;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetSeedAndStub() {
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                        + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                        + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')",
                TEAM_ID, OTHER_TEAM_ID);

        TeamContext.set(TeamContext.Snapshot.of(TEAM_ID, List.of("OPERATOR"), OPERATOR_ID, "operator01"));

        // 默认：Python 受理成功并回显同一个 run id（与真实 client 的结构校验一致）
        when(aiServiceClient.createAnalysisRun(any())).thenAnswer(invocation -> {
            AiRunCreateRequest request = invocation.getArgument(0);
            return new AiRunCreateEnvelope.AiRunAccepted(
                    request.analysisRunId(), "ACCEPTED", "2026-10-19T00:00:00.000Z");
        });
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    // =====================================================================
    // 幂等四态
    // =====================================================================

    @Test
    @DisplayName("幂等-同键同体：第二次返回首次 run，replayed=true，库内仍 1 行、审计仍 1 条")
    void sameKeySameBodyReplaysFirstRun() {
        CreateAnalysisRunRequest request = selectedSkus(List.of(SKU_A));

        CreateRunOutcome first = appService.createRun(request, "idem-same");
        CreateRunOutcome second = appService.createRun(request, "idem-same");

        assertFalse(first.replayed());
        assertTrue(second.replayed(), "同键同体必须标记为重放");
        assertEquals(first.data().runId(), second.data().runId(), "重放必须返回同一个 run");
        assertEquals(first.data().runNo(), second.data().runNo());
        assertEquals(1L, countRuns(), "不得新建第二行");
        assertEquals(1L, countAudits("ANALYSIS_RUN_CREATE"), "重放不得重复写创建审计");
    }

    @Test
    @DisplayName("幂等-同键异体：scope 不一致 → COM-0009（409），库内仍 1 行")
    void sameKeyDifferentBodyRejected() {
        appService.createRun(selectedSkus(List.of(SKU_A)), "idem-diff");

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(selectedSkus(List.of(SKU_B)), "idem-diff"));

        assertEquals(ErrorCodes.COM_0009, ex.getErrorCode());
        assertEquals(1L, countRuns());
    }

    @Test
    @DisplayName("幂等-同键异体（params 数值写法不同但归一后相同）→ 视为同体重放")
    void sameKeyEquivalentParamsAreReplayed() {
        CreateAnalysisRunRequest decimalRatio = selectedSkus(List.of(SKU_A), jsonNode("{\"ss_ratio\":0.5}"));
        CreateAnalysisRunRequest trailingZeros = selectedSkus(List.of(SKU_A), jsonNode("{\"ss_ratio\":0.5000}"));

        appService.createRun(decimalRatio, "idem-normalize");
        CreateRunOutcome replay = appService.createRun(trailingZeros, "idem-normalize");

        assertTrue(replay.replayed(), "JSON 归一化后同体应重放（CanonicalJson 去尾零）");
    }

    @Test
    @DisplayName("幂等-同键进行中：既有 run 处于 PENDING 时仍返回既有 run（202 语义）")
    void sameKeyInFlightReturnsExistingRun() {
        CreateRunOutcome first = appService.createRun(selectedSkus(List.of(SKU_A)), "idem-inflight");
        jdbc.update("UPDATE analysis_runs SET status='PENDING' WHERE id=?", first.data().runId());

        CreateRunOutcome second = appService.createRun(selectedSkus(List.of(SKU_A)), "idem-inflight");

        assertTrue(second.replayed());
        assertEquals("PENDING", second.data().status(), "返回的是既有 run 的当前状态");
        assertEquals(first.data().runId(), second.data().runId());
        assertEquals(1L, countRuns());
    }

    @Test
    @DisplayName("幂等-缺 key：服务层兜底 COM-0010（控制器另有一道）")
    void missingIdempotencyKeyRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(selectedSkus(List.of(SKU_A)), "  "));

        assertEquals(ErrorCodes.COM_0010, ex.getErrorCode());
        assertEquals(0L, countRuns());
    }

    // =====================================================================
    // 并发保护 ANL-7001
    // =====================================================================

    @Test
    @DisplayName("并发-SELECTED_SKUS 相交 → ANL-7001（409）")
    void selectedSkusOverlapConflicts() {
        appService.createRun(selectedSkus(List.of(SKU_A, SKU_B)), "conflict-a");

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(selectedSkus(List.of(SKU_B)), "conflict-b"));

        assertEquals(ErrorCodes.ANL_7001, ex.getErrorCode());
        assertEquals(1L, countRuns(), "冲突请求不得落库");
    }

    @Test
    @DisplayName("并发-SELECTED_SKUS 无交集 → 放行（202）")
    void selectedSkusDisjointAllowed() {
        appService.createRun(selectedSkus(List.of(SKU_A)), "disjoint-a");

        CreateRunOutcome second = appService.createRun(selectedSkus(List.of(SKU_B)), "disjoint-b");

        assertFalse(second.replayed());
        assertEquals(2L, countRuns());
    }

    @Test
    @DisplayName("并发-跨团队同 SKU → 放行（租户线过滤，ANL-7001 只看本团队）")
    void crossTeamSameScopeAllowed() {
        insertRun(OTHER_TEAM_ID, "RUN-20261019-0001", "RUNNING", "SELECTED_SKUS",
                "{\"sku_ids\":[" + SKU_A + "]}", WAREHOUSE_ID, "other-key");

        CreateRunOutcome outcome = appService.createRun(selectedSkus(List.of(SKU_A)), "own-key");

        assertFalse(outcome.replayed());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_runs", Long.class),
                "两团队各一行");
    }

    @Test
    @DisplayName("并发-ALL_ACTIVE 任一进行中即冲突")
    void allActiveConflicts() {
        appService.createRun(allActive(), "all-a");

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(allActive(), "all-b"));

        assertEquals(ErrorCodes.ANL_7001, ex.getErrorCode());
    }

    @Test
    @DisplayName("并发-ALL_ACTIVE 与 SELECTED_SKUS 属不同 scope_type → 不冲突（反向验证）")
    void differentScopeTypesDoNotConflict() {
        appService.createRun(allActive(), "mixed-a");

        CreateRunOutcome outcome = appService.createRun(selectedSkus(List.of(SKU_A)), "mixed-b");

        assertFalse(outcome.replayed());
        assertEquals(2L, countRuns());
    }

    @Test
    @DisplayName("并发-CATEGORY 同类目冲突、异类目放行")
    void categoryConflictAndPass() {
        appService.createRun(category("箱包"), "cat-a");

        BizException conflict = assertThrows(BizException.class,
                () -> appService.createRun(category("箱包"), "cat-b"));
        assertEquals(ErrorCodes.ANL_7001, conflict.getErrorCode());

        CreateRunOutcome other = appService.createRun(category("鞋靴"), "cat-c");
        assertFalse(other.replayed());
    }

    @Test
    @DisplayName("并发-未指定仓库（NULL）与指定仓库视为不同范围 → 放行（null 安全比较）")
    void nullWarehouseIsNotEqualToConcreteWarehouse() {
        appService.createRun(selectedSkus(List.of(SKU_A), null, WAREHOUSE_ID), "wh-a");

        CreateRunOutcome outcome = appService.createRun(selectedSkus(List.of(SKU_A), null, null), "wh-b");

        assertFalse(outcome.replayed(), "仓库不同（NULL vs 9004）不应判冲突");
    }

    // =====================================================================
    // run_no
    // =====================================================================

    @Test
    @DisplayName("run_no 格式 RUN-yyyyMMdd-NNNN，同日第二单递增 1，审计 biz_no == run_no")
    void runNoFormatAndSequence() {
        CreateRunOutcome first = appService.createRun(selectedSkus(List.of(SKU_A)), "no-1");
        CreateRunOutcome second = appService.createRun(selectedSkus(List.of(SKU_B)), "no-2");

        String runNo1 = first.data().runNo();
        String runNo2 = second.data().runNo();
        assertTrue(runNo1.matches("^RUN-\\d{8}-\\d{4}$"), "run_no 格式不符：" + runNo1);
        int seq1 = Integer.parseInt(runNo1.substring(runNo1.length() - 4));
        int seq2 = Integer.parseInt(runNo2.substring(runNo2.length() - 4));
        assertEquals(seq1 + 1, seq2, "同日第二单序号应递增 1");

        assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE action='ANALYSIS_RUN_CREATE' AND biz_no=?",
                        Long.class, runNo1),
                "审计 biz_no 应为 run_no");
    }

    // =====================================================================
    // 发起失败
    // =====================================================================

    @Test
    @DisplayName("发起失败：run 置 FAILED（ANL-7002、脱敏文案、finished_at 非空），不留 PENDING 悬挂")
    void aiFailureMarksRunFailed() {
        // ★ doThrow(...).when(...) 而非 when(...).thenThrow(...)：后者会先触发一次 mock 调用，
        //   把 @BeforeEach 的成功 answer 打穿（参数为 null → NPE）
        doThrow(new BizException(ErrorCodes.ANL_7002, "AI 分析服务暂时不可用，请稍后重试"))
                .when(aiServiceClient).createAnalysisRun(any());

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(selectedSkus(List.of(SKU_A)), "fail-1"));
        assertEquals(ErrorCodes.ANL_7002, ex.getErrorCode());

        String status = jdbc.queryForObject("SELECT status FROM analysis_runs", String.class);
        assertEquals("FAILED", status, "发起失败必须落 FAILED，不得留 PENDING");
        assertEquals(ErrorCodes.ANL_7002,
                jdbc.queryForObject("SELECT error_code FROM analysis_runs", String.class));
        String message = jdbc.queryForObject("SELECT error_message FROM analysis_runs", String.class);
        assertNotNull(message);
        assertFalse(message.contains("Exception") || message.contains("at com."),
                "error_message 必须脱敏（不得含堆栈）：" + message);
        assertNotNull(jdbc.queryForObject("SELECT finished_at FROM analysis_runs", Object.class),
                "FAILED 必须写 finished_at");
        assertEquals(1L, countAudits("ANALYSIS_RUN_FINISH"), "失败也必须留痕（result=FAILURE）");
        assertEquals("FAILURE", jdbc.queryForObject(
                "SELECT result FROM audit_logs WHERE action='ANALYSIS_RUN_FINISH'", String.class));
        assertEquals(0L, countAudits("ANALYSIS_RUN_CREATE"), "失败不得写创建成功审计");
    }

    // =====================================================================
    // 参数校验
    // =====================================================================

    @Test
    @DisplayName("参数校验-SKU 超 2000 → ANL-7007，零落库")
    void tooManySkusRejected() {
        List<Long> skus = new ArrayList<>();
        for (long i = 1; i <= 2001; i++) {
            skus.add(10000L + i);
        }

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(selectedSkus(skus), "many-1"));

        assertEquals(ErrorCodes.ANL_7007, ex.getErrorCode());
        assertEquals(0L, countRuns());
    }

    @Test
    @DisplayName("参数校验-window_days 越界 → ANL-7009")
    void invalidParamsRejected() {
        CreateAnalysisRunRequest request = selectedSkus(List.of(SKU_A), jsonNode("{\"window_days\":0}"));

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(request, "param-1"));

        assertEquals(ErrorCodes.ANL_7009, ex.getErrorCode());
        assertEquals(0L, countRuns());
    }

    @Test
    @DisplayName("参数校验-date_from > date_to → ANL-7013")
    void invalidDateRangeRejected() {
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest(
                "SELECTED_SKUS", skuIdsJson(List.of(SKU_A)), WAREHOUSE_ID,
                DATE_TO, DATE_FROM, null, ALGO, PROMPT, null);

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(request, "date-1"));

        assertEquals(ErrorCodes.ANL_7013, ex.getErrorCode());
        assertEquals(0L, countRuns());
    }

    @Test
    @DisplayName("参数校验-scope_type 非法 → COM-0001")
    void invalidScopeTypeRejected() {
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest(
                "EVERYTHING", null, WAREHOUSE_ID, DATE_FROM, DATE_TO, null, ALGO, PROMPT, null);

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(request, "type-1"));

        assertEquals(ErrorCodes.COM_0001, ex.getErrorCode());
    }

    @Test
    @DisplayName("参数校验-enable_llm=true 但 prompt_version 空白 → COM-0001 details[prompt_version]")
    void missingPromptVersionRejected() {
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest(
                "SELECTED_SKUS", skuIdsJson(List.of(SKU_A)), WAREHOUSE_ID,
                DATE_FROM, DATE_TO, null, ALGO, "  ", null);

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(request, "prompt-1"));

        assertEquals(ErrorCodes.COM_0001, ex.getErrorCode());
        assertNotNull(ex.getDetails());
        assertEquals("prompt_version", ex.getDetails().get(0).field());
    }

    @Test
    @DisplayName("参数校验-ALL_ACTIVE 不要求 scope_json，且 scope_json 落库为 NULL")
    void allActiveHasNoScopeJson() {
        CreateRunOutcome outcome = appService.createRun(allActive(), "all-null");

        assertNull(jdbc.queryForObject("SELECT scope_json FROM analysis_runs WHERE id=?",
                String.class, outcome.data().runId()));
        assertEquals(0, outcome.data().totalSkus(), "非 SELECTED_SKUS 的 total_skus 为 0");
    }

    // =====================================================================
    // team_id 来源
    // =====================================================================

    @Test
    @DisplayName("team_id 只来自 TeamContext：切换团队上下文后落库团队随之改变")
    void teamIdComesFromContextOnly() {
        appService.createRun(selectedSkus(List.of(SKU_A)), "team-1");
        assertEquals(TEAM_ID, jdbc.queryForObject(
                "SELECT team_id FROM analysis_runs WHERE idempotency_key='team-1'", Long.class));

        TeamContext.set(TeamContext.Snapshot.of(
                OTHER_TEAM_ID, List.of("OPERATOR"), OTHER_OPERATOR_ID, "operator8"));
        appService.createRun(selectedSkus(List.of(SKU_A)), "team-2");

        assertEquals(OTHER_TEAM_ID, jdbc.queryForObject(
                "SELECT team_id FROM analysis_runs WHERE idempotency_key='team-2'", Long.class));
        // ★ run_no 只是「团队内」唯一（uk_run_team_no），跨团队可同号 ⇒ 审计查询必须同时限定 team_id
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action='ANALYSIS_RUN_CREATE' AND team_id=? "
                        + "AND biz_no=(SELECT run_no FROM analysis_runs WHERE idempotency_key='team-2')",
                Long.class, OTHER_TEAM_ID));
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private long countRuns() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM analysis_runs", Long.class);
        return value == null ? 0L : value;
    }

    private long countAudits(String action) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action=?", Long.class, action);
        return value == null ? 0L : value;
    }

    private void insertRun(long teamId, String runNo, String status, String scopeType,
                           String scopeJson, Long warehouseId, String idempotencyKey) {
        jdbc.update("INSERT INTO analysis_runs (team_id, run_no, status, scope_type, scope_json, "
                        + "warehouse_id, params_json, algorithm_version, llm_status, progress, idempotency_key) "
                        + "VALUES (?, ?, ?, ?, ?, ?, '{}', ?, 'NOT_REQUESTED', 0, ?)",
                teamId, runNo, status, scopeType, scopeJson, warehouseId, ALGO, idempotencyKey);
    }

    private CreateAnalysisRunRequest selectedSkus(List<Long> skuIds) {
        return selectedSkus(skuIds, null, WAREHOUSE_ID);
    }

    private CreateAnalysisRunRequest selectedSkus(List<Long> skuIds, JsonNode params) {
        return selectedSkus(skuIds, params, WAREHOUSE_ID);
    }

    private CreateAnalysisRunRequest selectedSkus(List<Long> skuIds, JsonNode params, Long warehouseId) {
        return new CreateAnalysisRunRequest(
                "SELECTED_SKUS", skuIdsJson(skuIds), warehouseId,
                DATE_FROM, DATE_TO, params, ALGO, PROMPT, "gpt-4o-mini");
    }

    private CreateAnalysisRunRequest allActive() {
        return new CreateAnalysisRunRequest(
                "ALL_ACTIVE", null, WAREHOUSE_ID, DATE_FROM, DATE_TO, null, ALGO, PROMPT, null);
    }

    private CreateAnalysisRunRequest category(String category) {
        return new CreateAnalysisRunRequest(
                "CATEGORY", jsonNode("{\"category\":\"" + category + "\"}"), WAREHOUSE_ID,
                DATE_FROM, DATE_TO, null, ALGO, PROMPT, null);
    }

    private JsonNode skuIdsJson(List<Long> skuIds) {
        StringBuilder sb = new StringBuilder("{\"sku_ids\":[");
        for (int i = 0; i < skuIds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(skuIds.get(i));
        }
        return jsonNode(sb.append("]}").toString());
    }

    private JsonNode jsonNode(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("测试夹具 JSON 非法：" + raw, ex);
        }
    }
}
