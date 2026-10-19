package com.shoppilot.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.analysis.application.AnalysisAppService;
import com.shoppilot.analysis.domain.AnalysisRunStatus;
import com.shoppilot.analysis.application.dto.CreateAnalysisRunRequest;
import com.shoppilot.analysis.application.dto.CreateRunOutcome;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateEnvelope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateRequest;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisRunMapper;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static org.mockito.Mockito.doReturn;
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
    /** M4-B20.1 装配种子用（{@code skus.product_id} 有真实外键，必须先有商品行）。 */
    private static final long PRODUCT_A = 9101L;
    private static final long PRODUCT_B = 9102L;
    /** Q14：{@code OTHER_TEAM_ID} 的 SKU（切团队后创建 run 必须用本团队 SKU）。 */
    private static final long SKU_OTHER = 9005L;
    private static final long PRODUCT_OTHER = 9103L;
    private static final long WAREHOUSE_OTHER = 9006L;

    private static final String ALGO = "avg-ma-3sigma-v1";
    private static final String PROMPT = "explain_risk.v1";
    private static final String MODEL = "gpt-4o-mini";
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

    /** R108/A2：用于让 insert 抛 DuplicateKeyException，驱动「并发撞键后回查」分支。 */
    @MockitoSpyBean
    private AnalysisRunMapper runMapper;

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

        // ★ M4-B20.1：items 装配所需主数据种子（仅新增，不断言）
        seedCatalogForAssembly();

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
    // R108（B15.1）：幂等体比对必须覆盖 §6.3 的全部请求字段
    // =====================================================================

    @Test
    @DisplayName("R108-改 date_from：同键 → 409 COM-0009，不新建")
    void sameKeyDifferentDateFromRejected() {
        appService.createRun(request(DATE_FROM, DATE_TO, PROMPT, MODEL), "r108-from");

        BizException ex = assertThrows(BizException.class, () ->
                appService.createRun(request(DATE_FROM.plusDays(1), DATE_TO, PROMPT, MODEL), "r108-from"));

        assertEquals(ErrorCodes.COM_0009, ex.getErrorCode());
        assertEquals(1L, countRuns(), "异体不得新建");
    }

    @Test
    @DisplayName("R108-改 date_to：同键 → 409 COM-0009")
    void sameKeyDifferentDateToRejected() {
        appService.createRun(request(DATE_FROM, DATE_TO, PROMPT, MODEL), "r108-to");

        BizException ex = assertThrows(BizException.class, () ->
                appService.createRun(request(DATE_FROM, DATE_TO.minusDays(1), PROMPT, MODEL), "r108-to"));

        assertEquals(ErrorCodes.COM_0009, ex.getErrorCode());
    }

    @Test
    @DisplayName("R108-改 prompt_version：同键 → 409 COM-0009")
    void sameKeyDifferentPromptVersionRejected() {
        appService.createRun(request(DATE_FROM, DATE_TO, PROMPT, MODEL), "r108-prompt");

        BizException ex = assertThrows(BizException.class, () ->
                appService.createRun(request(DATE_FROM, DATE_TO, "explain_risk.v2", MODEL), "r108-prompt"));

        assertEquals(ErrorCodes.COM_0009, ex.getErrorCode());
    }

    @Test
    @DisplayName("R108-改 model_name：同键 → 409 COM-0009")
    void sameKeyDifferentModelNameRejected() {
        appService.createRun(request(DATE_FROM, DATE_TO, PROMPT, MODEL), "r108-model");

        BizException ex = assertThrows(BizException.class, () ->
                appService.createRun(request(DATE_FROM, DATE_TO, PROMPT, "gpt-4o"), "r108-model"));

        assertEquals(ErrorCodes.COM_0009, ex.getErrorCode());
    }

    @Test
    @DisplayName("R108-仅改日期也必须落库可比对（data_start_date/data_end_date 为载体）")
    void requestWindowIsPersistedForComparison() {
        CreateRunOutcome outcome = appService.createRun(
                request(DATE_FROM, DATE_TO, PROMPT, MODEL), "r108-window");

        assertEquals(DATE_FROM, jdbc.queryForObject(
                "SELECT data_start_date FROM analysis_runs WHERE id=?", LocalDate.class,
                outcome.data().runId()));
        assertEquals(DATE_TO, jdbc.queryForObject(
                "SELECT data_end_date FROM analysis_runs WHERE id=?", LocalDate.class,
                outcome.data().runId()));
    }

    @Test
    @DisplayName("R108/A2：insert 撞键后回查到异体 → COM-0009（禁止直接返回 concurrent）")
    void concurrentIdempotentHitIsReconciled() {
        // 先落一条同键 run（scope = SKU_A），作为「并发窗口里抢先落库」的那一行
        CreateRunOutcome first = appService.createRun(
                selectedSkus(List.of(SKU_A)), "r108-race");
        AnalysisRunEntity existing = runMapper.selectById(first.data().runId());
        assertNotNull(existing);

        // c 步查不到（空）、d 步无进行中（空）、catch 回查命中既有 → 必须走 reconcile
        doReturn(List.of())
                .doReturn(List.of())
                .doReturn(List.of(existing))
                .when(runMapper).selectList(any());
        doThrow(new DuplicateKeyException(
                "Duplicate entry 'r108-race' for key 'analysis_runs.uk_run_team_idem'"))
                .when(runMapper).insert(any(AnalysisRunEntity.class));

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(selectedSkus(List.of(SKU_B)), "r108-race"));

        assertEquals(ErrorCodes.COM_0009, ex.getErrorCode(),
                "并发同键的异体必须报 COM-0009，不得把既有 run 直接当重放返回");
        assertEquals(1L, countRuns());
    }

    // =====================================================================
    // R109（B15.1）：scope_json 必须由 JSON 序列化生成（含引号/反斜杠不炸）
    // =====================================================================

    @Test
    @DisplayName("R109-CATEGORY 含双引号：202 落库为合法 JSON，category 保留原值")
    void categoryWithQuoteIsEscaped() throws Exception {
        String category = "A\"B";

        CreateRunOutcome outcome = appService.createRun(category(category), "r109-quote");

        String scopeJson = jdbc.queryForObject(
                "SELECT scope_json FROM analysis_runs WHERE id=?", String.class, outcome.data().runId());
        JsonNode parsed = json.readTree(scopeJson); // 非法 JSON 会在此抛异常
        assertEquals(category, parsed.get("category").asText());
    }

    @Test
    @DisplayName("R109-CATEGORY 含反斜杠与换行：同样落库为合法 JSON 且值不丢失")
    void categoryWithBackslashIsEscaped() throws Exception {
        String category = "A\\B\nC";

        CreateRunOutcome outcome = appService.createRun(category(category), "r109-backslash");

        String scopeJson = jdbc.queryForObject(
                "SELECT scope_json FROM analysis_runs WHERE id=?", String.class, outcome.data().runId());
        assertEquals(category, json.readTree(scopeJson).get("category").asText());
    }

    @Test
    @DisplayName("R109-含引号的 CATEGORY：同键同体 → 重放；改类目 → COM-0009")
    void categoryQuoteIdempotency() {
        CreateRunOutcome first = appService.createRun(category("A\"B"), "r109-idem");
        assertFalse(first.replayed());

        CreateRunOutcome replay = appService.createRun(category("A\"B"), "r109-idem");
        assertTrue(replay.replayed(), "同键同体（含转义）必须判重放");
        assertEquals(first.data().runId(), replay.data().runId());

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(category("A\"C"), "r109-idem"));
        assertEquals(ErrorCodes.COM_0009, ex.getErrorCode());
    }

    @Test
    @DisplayName("R109 回归：SELECTED_SKUS 的 scope_json 结构不变（sku_ids 保序、不去重）")
    void selectedSkusScopeJsonShapeUnchanged() throws Exception {
        CreateRunOutcome outcome = appService.createRun(
                selectedSkus(List.of(SKU_B, SKU_A)), "r109-skus");

        String scopeJson = jdbc.queryForObject(
                "SELECT scope_json FROM analysis_runs WHERE id=?", String.class, outcome.data().runId());
        JsonNode parsed = json.readTree(scopeJson);
        assertEquals(2, parsed.get("sku_ids").size());
        assertEquals(SKU_B, parsed.get("sku_ids").get(0).asLong(), "数组必须保序");
        assertEquals(SKU_A, parsed.get("sku_ids").get(1).asLong());
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

    /**
     * ★ Q13①：保留 B15 原意图——「比较对 NULL 安全」。
     *
     * <p>用 {@code insertRun(..., warehouseId=null, ...)} 造一条 {@code warehouse_id IS NULL} 的
     * 进行中行（模拟 B20.1 之前的历史数据：默认仓是<b>后来</b>才解析并落库的），
     * 再发一个<b>指定 9004</b> 的请求 ⇒ 两者不是同一仓库范围，必须放行。
     */
    @Test
    @DisplayName("并发-历史 NULL 仓库行与指定仓库视为不同范围 → 放行（null 安全比较）")
    void nullWarehouseRowIsNotEqualToConcreteWarehouse() {
        insertRun(TEAM_ID, "RUN-20200101-0001", AnalysisRunStatus.RUNNING.name(),
                "SELECTED_SKUS", skuIdsJson(List.of(SKU_A)).toString(), null, "legacy-null-wh");

        CreateRunOutcome outcome = appService.createRun(
                selectedSkus(List.of(SKU_A), null, WAREHOUSE_ID), "wh-a");

        assertFalse(outcome.replayed(), "仓库不同（NULL vs 9004）不应判冲突");
    }

    /**
     * ★ Q13②：Q3 的正向语义——请求不指定仓时解析为默认仓 9004，
     * 与「仓库=9004 的进行中 run」是<b>同一范围</b> ⇒ 必须 {@code ANL-7001}（409）。
     *
     * <p>这正是 B20.1 之前那条测试的前提被推翻的地方：旧断言认为「NULL ≠ 9004 ⇒ 放行」，
     * 而解析默认仓之后二者本就是同一个仓。
     */
    @Test
    @DisplayName("并发-Q3：未指定仓库解析为默认仓 9004，与 9004 的进行中 run 同范围 ⇒ ANL-7001")
    void resolvedDefaultWarehouseConflictsWithSameWarehouse() {
        insertRun(TEAM_ID, "RUN-20200101-0002", AnalysisRunStatus.RUNNING.name(),
                "SELECTED_SKUS", skuIdsJson(List.of(SKU_A)).toString(), WAREHOUSE_ID, "wh-c");

        BizException ex = assertThrows(BizException.class,
                () -> appService.createRun(selectedSkus(List.of(SKU_A), null, null), "wh-d"));

        assertEquals(ErrorCodes.ANL_7001, ex.getErrorCode());
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
        // ★ R135（§5.38 裁定 #14，M4-B20.2）：total_skus 的语义由「请求声明数」改为
        //   「装配后实际数」。旧断言「非 SELECTED_SKUS 的 total_skus 为 0」的前提
        //   （ALL_ACTIVE 无法从请求推出数量）已不成立——本类夹具里有 2 个 ACTIVE SKU。
        assertEquals(2, outcome.data().totalSkus(),
                "R135：ALL_ACTIVE 的 total_skus 是装配后的实际 SKU 数（团队 7 有 SKU_A/SKU_B "
                        + "两个 ACTIVE；SKU_OTHER 属团队 8 不计），不再是 0");
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
        // ★ Q14：切到团队 8 后必须用**该团队**的 SKU（裁定 #3：SELECTED_SKUS 逐个校验归属）
        // ★ R128（§5.38 第四节，M4-B20.2）：同时不得显式传**他团队**的仓——那会 COM-0001
        //   （details.reason=仓库不属于当前团队）。故第二个 run 不传仓，走团队 8 的默认仓解析。
        appService.createRun(selectedSkus(List.of(SKU_OTHER), null, null), "team-2");

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

    /**
     * ★ M4-B20.1（{@code §5.36} Q2 授权）：补<b>最小主数据种子</b>，让 {@code createRun} 的
     * items 装配能找到 SKU / 仓库。<b>本方法只增数据，不改任何既有断言</b>。
     *
     * <p>为什么需要：装配新增了「SELECTED_SKUS 必须属本团队且 ACTIVE」「默认仓必须存在」
     * 两道校验，而本类此前只种了 {@code teams}（SKU 9002/9003、仓库 9004 是<b>凭空写进请求</b>的），
     * 不补种子就会全部落 {@code COM-0001}。
     */
    private void seedCatalogForAssembly() {
        jdbc.update("DELETE FROM inventory_balances WHERE warehouse_id IN (?, ?)", WAREHOUSE_ID, WAREHOUSE_OTHER);
        // ★ 顺序要紧：skus.product_id 有真实外键（RESTRICT），必须先删 SKU 再删 product
        jdbc.update("DELETE FROM skus WHERE id IN (?, ?, ?)", SKU_A, SKU_B, SKU_OTHER);
        jdbc.update("DELETE FROM warehouses WHERE id IN (?, ?)", WAREHOUSE_ID, WAREHOUSE_OTHER);
        jdbc.update("DELETE FROM products WHERE id IN (?, ?, ?)", PRODUCT_A, PRODUCT_B, PRODUCT_OTHER);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, category, status) VALUES "
                        + "(?, ?, 'P-A', '商品A', 'CAT-01', 'ACTIVE'), "
                        + "(?, ?, 'P-B', '商品B', 'CAT-02', 'ACTIVE'), "
                        + "(?, ?, 'P-X', '他团队商品', 'CAT-01', 'ACTIVE')",
                PRODUCT_A, TEAM_ID, PRODUCT_B, TEAM_ID, PRODUCT_OTHER, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status, is_default) "
                        + "VALUES (?, ?, 'WH-MAIN', '主仓', 'ACTIVE', 1)",
                WAREHOUSE_ID, TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                        + "(?, ?, ?, 'SKU-A', '规格A', 'ACTIVE'), "
                        + "(?, ?, ?, 'SKU-B', '规格B', 'ACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_A, SKU_B, TEAM_ID, PRODUCT_B);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status, is_default) "
                        + "VALUES (?, ?, 'WH-OTHER', '他团队仓', 'ACTIVE', 1)",
                WAREHOUSE_OTHER, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) "
                        + "VALUES (?, ?, ?, 'SKU-X', '他团队规格', 'ACTIVE')",
                SKU_OTHER, OTHER_TEAM_ID, PRODUCT_OTHER);
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                        + "in_transit_qty, committed_qty) VALUES (?, ?, ?, 10.000, 0.000, 0.000), "
                        + "(?, ?, ?, 10.000, 0.000, 0.000)",
                TEAM_ID, WAREHOUSE_ID, SKU_A, TEAM_ID, WAREHOUSE_ID, SKU_B);
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

    /** R108 用：固定 SKU_A，仅变日期 / prompt / model 的请求。 */
    private CreateAnalysisRunRequest request(LocalDate from, LocalDate to, String prompt, String model) {
        return new CreateAnalysisRunRequest(
                "SELECTED_SKUS", skuIdsJson(List.of(SKU_A)), WAREHOUSE_ID,
                from, to, null, ALGO, prompt, model);
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
        // ★ 夹具也必须走 JSON 序列化：手工拼接 "{category:\"A\"B\"}" 会在测试侧就抛 JsonParseException，
        //   掩盖真正的实现缺陷（R109 首轮 3 error 即此因，已自纠）
        com.fasterxml.jackson.databind.node.ObjectNode scope = json.createObjectNode();
        scope.put("category", category);
        return new CreateAnalysisRunRequest(
                "CATEGORY", scope, WAREHOUSE_ID, DATE_FROM, DATE_TO, null, ALGO, PROMPT, null);
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
