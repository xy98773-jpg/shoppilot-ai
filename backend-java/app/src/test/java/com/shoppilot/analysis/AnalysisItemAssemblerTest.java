package com.shoppilot.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.shoppilot.analysis.application.AnalysisAppService;
import com.shoppilot.analysis.application.dto.CreateAnalysisRunRequest;
import com.shoppilot.analysis.application.dto.CreateRunOutcome;
import com.shoppilot.analysis.domain.AnalysisParams;
import com.shoppilot.analysis.domain.AnalysisScope;
import com.shoppilot.analysis.domain.AnalysisScopeType;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateEnvelope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateRequest;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunItem;
import com.shoppilot.analysis.infrastructure.ai.dto.AiValidateRequest;
import com.shoppilot.analysis.infrastructure.sampling.AnalysisItemAssembler;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.time.LocalDate;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M4-B20.1 {@code AnalysisItemAssembler} 集成测试 —— 真实 MySQL + 真实租户线（{@code TEST_STRATEGY §3.2}：
 * 装配相关必须 DB 集成，<b>禁止 Mock Mapper</b>；只对纯函数（交期来源优先级）用数据驱动断言）。
 *
 * <p>覆盖 {@code §5.36} 裁定 #3/#4/#6~#10 与 Q1~Q13：三种范围、跨团队/非 ACTIVE SKU、
 * 默认仓缺失与多行、缺余额行兜 0、交期四来源、销量序列不补 0、{@code is_enabled=0} 策略忽略、
 * 出站字段完整性（V4）、默认仓解析 + 幂等重投回归（V5 / Q3）。
 */
@SpringBootTest(classes = ShopPilotApplication.class)
@Testcontainers
class AnalysisItemAssemblerTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OPERATOR_ID = 1001L;

    private static final long PRODUCT_A = 8101L;
    private static final long PRODUCT_B = 8102L;
    private static final long PRODUCT_OTHER = 8103L;
    private static final long SUPPLIER = 8201L;
    private static final long WAREHOUSE_DEFAULT = 8301L;
    private static final long WAREHOUSE_SECOND = 8302L;

    /** 无策略、无 SKU 交期、无供应商 → 走 DEFAULT；用于「缺余额行」与 DEFAULT 来源断言。 */
    private static final long SKU_PLAIN = 8401L;
    /** SKU 级交期（覆盖 DEFAULT）。 */
    private static final long SKU_LEAD = 8402L;
    /** 供应商级交期（SKU 与策略都没有）。 */
    private static final long SKU_SUPPLIER = 8403L;
    /** 策略级交期（优先级最高）。 */
    private static final long SKU_POLICY = 8404L;
    /** 非 ACTIVE，ALL_ACTIVE / CATEGORY 都不应命中。 */
    private static final long SKU_INACTIVE = 8405L;
    /** 他团队 SKU。 */
    private static final long SKU_OTHER_TEAM = 8406L;
    /** 策略被停用（is_enabled=0）→ 交期与采购参数一律不生效。 */
    private static final long SKU_DISABLED_POLICY = 8407L;

    private static final String ALGO = "avg-ma-3sigma-v1";
    private static final LocalDate DATE_FROM = LocalDate.of(2026, 9, 16);
    private static final LocalDate DATE_TO = LocalDate.of(2026, 10, 13);

    private final ObjectMapper json = new ObjectMapper();

    /** Spring 托管的 ObjectMapper（注册了 JavaTimeModule，能序列化 {@code LocalDate}）——
     *  真实的出站序列化也用它，故取证必须用同一个，否则会误报「不支持 Java 8 时间类型」。 */
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper springJson;

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
    private AnalysisItemAssembler assembler;

    @Autowired
    private AnalysisAppService appService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM sales_daily");
        jdbc.execute("DELETE FROM replenishment_policies");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM analysis_items");
        jdbc.execute("DELETE FROM analysis_runs");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                        + "(?, 'T-7', '七组', 'ACTIVE', 'Asia/Shanghai'), "
                        + "(?, 'T-8', '八组', 'ACTIVE', 'Asia/Shanghai')",
                TEAM_ID, OTHER_TEAM_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name, category, status) VALUES "
                        + "(?, ?, 'P-A', '商品A', 'CAT-A', 'ACTIVE'), "
                        + "(?, ?, 'P-B', '商品B', 'CAT-B', 'ACTIVE'), "
                        + "(?, ?, 'P-X', '他团队商品', 'CAT-A', 'ACTIVE')",
                PRODUCT_A, TEAM_ID, PRODUCT_B, TEAM_ID, PRODUCT_OTHER, OTHER_TEAM_ID);

        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status, lead_time_days) "
                        + "VALUES (?, ?, 'SUP-1', '供应商一', 'ACTIVE', 21)", SUPPLIER, TEAM_ID);

        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status, is_default) VALUES "
                        + "(?, ?, 'WH-D', '默认仓', 'ACTIVE', 1), "
                        + "(?, ?, 'WH-S', '次仓', 'ACTIVE', 0)",
                WAREHOUSE_DEFAULT, TEAM_ID, WAREHOUSE_SECOND, TEAM_ID);

        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status, supplier_id, "
                        + "default_lead_time_days, listed_date) VALUES "
                        + "(?, ?, ?, 'SKU-PLAIN', '素 SKU', 'ACTIVE', NULL, NULL, '2026-01-01'), "
                        + "(?, ?, ?, 'SKU-LEAD', 'SKU 交期', 'ACTIVE', NULL, 3, NULL), "
                        + "(?, ?, ?, 'SKU-SUP', '供应商交期', 'ACTIVE', ?, NULL, NULL), "
                        + "(?, ?, ?, 'SKU-POL', '策略交期', 'ACTIVE', NULL, NULL, NULL), "
                        + "(?, ?, ?, 'SKU-OFF', '停用', 'INACTIVE', NULL, NULL, NULL), "
                        + "(?, ?, ?, 'SKU-X', '他团队', 'ACTIVE', NULL, NULL, NULL), "
                        + "(?, ?, ?, 'SKU-DIS', '策略停用', 'ACTIVE', NULL, NULL, NULL)",
                SKU_PLAIN, TEAM_ID, PRODUCT_A,
                SKU_LEAD, TEAM_ID, PRODUCT_A,
                SKU_SUPPLIER, TEAM_ID, PRODUCT_A, SUPPLIER,
                SKU_POLICY, TEAM_ID, PRODUCT_B,
                SKU_INACTIVE, TEAM_ID, PRODUCT_B,
                SKU_OTHER_TEAM, OTHER_TEAM_ID, PRODUCT_OTHER,
                SKU_DISABLED_POLICY, TEAM_ID, PRODUCT_B);

        // 策略：SKU_POLICY 全量；SKU_DISABLED_POLICY 停用（is_enabled=0）
        jdbc.update("INSERT INTO replenishment_policies (team_id, sku_id, warehouse_id, lead_time_days, "
                        + "safety_stock_qty, moq, order_multiple, qty_step, is_enabled) VALUES "
                        + "(?, ?, ?, 5, 12.500, 50.000, 10.000, 2.000, 1), "
                        + "(?, ?, ?, 9, 99.000, 99.000, 99.000, 99.000, 0)",
                TEAM_ID, SKU_POLICY, WAREHOUSE_DEFAULT,
                TEAM_ID, SKU_DISABLED_POLICY, WAREHOUSE_DEFAULT);

        // 余额：只给 SKU_LEAD 建一行（其余 SKU 走「缺行 ⇒ 0.000」分支）
        jdbc.update("INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, "
                        + "in_transit_qty, committed_qty) VALUES (?, ?, ?, 8.000, 5.000, 1.000)",
                TEAM_ID, WAREHOUSE_DEFAULT, SKU_LEAD);

        // 销量：只给 SKU_LEAD 三天记录（含一条窗口外记录，用于证明按窗口过滤）
        jdbc.update("INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, qty) "
                        + "VALUES (?, ?, ?, ?, '2026-09-16', 6.000), "
                        + "(?, ?, ?, ?, '2026-09-18', 7.500), "
                        + "(?, ?, ?, ?, '2026-10-20', 99.000)",
                TEAM_ID, WAREHOUSE_DEFAULT, SKU_LEAD, PRODUCT_A,
                TEAM_ID, WAREHOUSE_DEFAULT, SKU_LEAD, PRODUCT_A,
                TEAM_ID, WAREHOUSE_DEFAULT, SKU_LEAD, PRODUCT_A);

        TeamContext.set(TeamContext.Snapshot.of(TEAM_ID, List.of("OPERATOR"), OPERATOR_ID, "operator01"));
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
    // 默认仓解析（Q1 / R125）
    // =====================================================================
    @Test
    @DisplayName("默认仓：请求带仓原样返回；不带仓时取 warehouses.is_default=1 且 ACTIVE 的那一个")
    void resolvesDefaultWarehouse() {
        assertEquals(WAREHOUSE_DEFAULT, assembler.resolveWarehouseId(TEAM_ID, null));
        assertEquals(WAREHOUSE_SECOND, assembler.resolveWarehouseId(TEAM_ID, WAREHOUSE_SECOND));
    }

    @Test
    @DisplayName("默认仓：没有 is_default=1 ⇒ COM-0001，details.reason=未配置默认仓")
    void missingDefaultWarehouseRejected() {
        jdbc.update("UPDATE warehouses SET is_default = 0 WHERE team_id = ?", TEAM_ID);

        BizException ex = assertThrows(BizException.class,
                () -> assembler.resolveWarehouseId(TEAM_ID, null));

        assertEquals(ErrorCodes.COM_0001, ex.getErrorCode());
        assertNotNull(ex.getDetails());
        assertTrue(ex.getDetails().get(0).message().contains("未配置默认仓"),
                "details 必须写明「未配置默认仓」，实际=" + ex.getDetails().get(0).message());
    }

    @Test
    @DisplayName("默认仓：命中多行 ⇒ COM-0001（数据异常不猜，不静默取第一行）")
    void multipleDefaultWarehousesRejected() {
        jdbc.update("UPDATE warehouses SET is_default = 1 WHERE id = ?", WAREHOUSE_SECOND);

        BizException ex = assertThrows(BizException.class,
                () -> assembler.resolveWarehouseId(TEAM_ID, null));

        assertEquals(ErrorCodes.COM_0001, ex.getErrorCode());
        assertTrue(ex.getDetails().get(0).message().contains("默认仓数据异常"),
                "实际=" + ex.getDetails().get(0).message());
    }

    @Test
    @DisplayName("默认仓：INACTIVE 的仓不算默认仓")
    void inactiveDefaultWarehouseIgnored() {
        jdbc.update("UPDATE warehouses SET is_default = 1, status = 'INACTIVE' WHERE id = ?",
                WAREHOUSE_SECOND);
        jdbc.update("UPDATE warehouses SET is_default = 0 WHERE id = ?", WAREHOUSE_DEFAULT);

        assertThrows(BizException.class, () -> assembler.resolveWarehouseId(TEAM_ID, null));
    }

    // =====================================================================
    // 范围选取（裁定 #3）
    // =====================================================================
    @Test
    @DisplayName("SELECTED_SKUS：逐个校验团队与 ACTIVE，命中才装配")
    void assemblesSelectedSkus() {
        List<AiRunItem> items = assembler.assemble(TEAM_ID, WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.SELECTED_SKUS, List.of(SKU_LEAD), null), DATE_FROM, DATE_TO);

        assertEquals(1, items.size());
        assertEquals(SKU_LEAD, items.get(0).skuId());
    }

    @Test
    @DisplayName("SELECTED_SKUS：跨团队 sku_id ⇒ COM-0001（租户线查不到行，不泄漏他团队数据）")
    void crossTeamSkuRejected() {
        BizException ex = assertThrows(BizException.class, () -> assembler.assemble(TEAM_ID,
                WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.SELECTED_SKUS, List.of(SKU_OTHER_TEAM), null),
                DATE_FROM, DATE_TO));

        assertEquals(ErrorCodes.COM_0001, ex.getErrorCode());
        assertEquals(SKU_OTHER_TEAM, ex.getDetails().get(0).rejectedValue());
        assertTrue(ex.getDetails().get(0).message().contains("不属于当前团队"));
    }

    @Test
    @DisplayName("SELECTED_SKUS：INACTIVE sku ⇒ COM-0001，details 指明当前状态")
    void inactiveSkuRejected() {
        BizException ex = assertThrows(BizException.class, () -> assembler.assemble(TEAM_ID,
                WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.SELECTED_SKUS, List.of(SKU_INACTIVE), null),
                DATE_FROM, DATE_TO));

        assertEquals(ErrorCodes.COM_0001, ex.getErrorCode());
        assertTrue(ex.getDetails().get(0).message().contains("INACTIVE"));
    }

    @Test
    @DisplayName("ALL_ACTIVE：本团队 ACTIVE SKU（含 SKU 级配置），不含 INACTIVE 与他团队")
    void assemblesAllActive() {
        List<AiRunItem> items = assembler.assemble(TEAM_ID, WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.ALL_ACTIVE, null, null), DATE_FROM, DATE_TO);

        List<Long> ids = items.stream().map(AiRunItem::skuId).toList();
        assertTrue(ids.contains(SKU_PLAIN) && ids.contains(SKU_LEAD) && ids.contains(SKU_POLICY));
        assertTrue(ids.contains(SKU_SUPPLIER) && ids.contains(SKU_DISABLED_POLICY));
        assertTrue(!ids.contains(SKU_INACTIVE), "INACTIVE 不得进入 ALL_ACTIVE");
        assertTrue(!ids.contains(SKU_OTHER_TEAM), "他团队 SKU 不得进入（租户线）");
        assertEquals(ids.stream().sorted().toList(), ids, "必须按 SKU id 升序，保证出站可复现");
    }

    @Test
    @DisplayName("CATEGORY：按 products.category 等值取 SKU，不额外过滤 products.status")
    void assemblesByCategory() {
        List<AiRunItem> catA = assembler.assemble(TEAM_ID, WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.CATEGORY, null, "CAT-A"), DATE_FROM, DATE_TO);
        List<AiRunItem> catB = assembler.assemble(TEAM_ID, WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.CATEGORY, null, "CAT-B"), DATE_FROM, DATE_TO);

        assertEquals(List.of(SKU_PLAIN, SKU_LEAD, SKU_SUPPLIER),
                catA.stream().map(AiRunItem::skuId).sorted().toList());
        assertEquals(List.of(SKU_POLICY, SKU_DISABLED_POLICY),
                catB.stream().map(AiRunItem::skuId).sorted().toList());
    }

    // =====================================================================
    // 字段映射（裁定 #6~#10 / Q9 / Q10 / V4）
    // =====================================================================
    @Test
    @DisplayName("字段映射：库存快照 + 交期 POLICY + 采购参数 + 上架日 + 销量序列（V4 出站证据）")
    void mapsAllItemFields() {
        AiRunItem item = onlyItem(SKU_POLICY);

        // SKU_POLICY 没建余额行 ⇒ 三项库存兜 0.000（有余额行的映射见 balanceRowIsEchoed）
        assertEquals("0.000", item.onHandQty());
        assertEquals("0.000", item.committedQty());
        assertEquals("0.000", item.effectiveInTransitQty());
        assertEquals(5, item.leadTimeDays());
        assertEquals("POLICY", item.leadTimeSource());
        assertEquals("12.500", item.safetyStockOverride());
        assertEquals("50.000", item.moq());
        assertEquals("10.000", item.orderMultiple());
        assertEquals("2.000", item.qtyStep());
        assertNull(item.listedDate(), "SKU_POLICY 未配上架日 ⇒ 不下发该字段");
        assertNotNull(item.salesSeries());
    }

    @Test
    @DisplayName("缺余额行 ⇒ 三项库存一律 0.000（不是报错）")
    void missingBalanceRowBecomesZero() {
        AiRunItem item = onlyItem(SKU_PLAIN);

        assertEquals("0.000", item.onHandQty());
        assertEquals("0.000", item.committedQty());
        assertEquals("0.000", item.effectiveInTransitQty());
    }

    @Test
    @DisplayName("有余额行 ⇒ 原样出站（含 R124 的 in_transit_qty）")
    void balanceRowIsEchoed() {
        AiRunItem item = onlyItem(SKU_LEAD);

        assertEquals("8.000", item.onHandQty());
        assertEquals("1.000", item.committedQty());
        assertEquals("5.000", item.effectiveInTransitQty());
        assertEquals(3, item.leadTimeDays());
        assertEquals("SKU", item.leadTimeSource());
    }

    @Test
    @DisplayName("交期四来源优先级：POLICY > SKU > SUPPLIER > DEFAULT(7)")
    void leadTimeSourcePriority() {
        assertEquals("POLICY", onlyItem(SKU_POLICY).leadTimeSource());
        assertEquals("SKU", onlyItem(SKU_LEAD).leadTimeSource());
        assertEquals("SUPPLIER", onlyItem(SKU_SUPPLIER).leadTimeSource());
        assertEquals(21, onlyItem(SKU_SUPPLIER).leadTimeDays());
        AiRunItem fallback = onlyItem(SKU_PLAIN);
        assertEquals("DEFAULT", fallback.leadTimeSource());
        assertEquals(7, fallback.leadTimeDays());
    }

    @Test
    @DisplayName("is_enabled=0 的策略不生效：交期回 SKU/默认，采购参数不下发")
    void disabledPolicyIsIgnored() {
        AiRunItem item = onlyItem(SKU_DISABLED_POLICY);

        assertEquals("DEFAULT", item.leadTimeSource());
        assertEquals(7, item.leadTimeDays());
        assertNull(item.safetyStockOverride());
        assertNull(item.moq());
        assertNull(item.orderMultiple());
        assertNull(item.qtyStep());
    }

    @Test
    @DisplayName("销量序列只含有记录的日子（不补 0），且按窗口过滤")
    void salesSeriesKeepsOnlyRecordedDays() {
        AiRunItem item = onlyItem(SKU_LEAD);

        // 2026-09-16 / 2026-09-18 两条在窗口内；2026-10-20 在窗口外必须被过滤；
        // 09-17 等缺失日**不得**补 0 出现在序列里。
        assertEquals(2, item.salesSeries().size());
        assertEquals("2026-09-16", item.salesSeries().get(0).date().toString());
        assertEquals("6.000", item.salesSeries().get(0).qty());
        assertEquals("2026-09-18", item.salesSeries().get(1).date().toString());
        assertEquals("7.500", item.salesSeries().get(1).qty());
        assertTrue(item.salesSeries().stream().noneMatch(p -> p.date().toString().equals("2026-09-17")),
                "缺失日不得补 0");
    }

    @Test
    @DisplayName("出站 JSON：字段名与 Python Item 逐项对齐（extra=forbid，多一个字段就 400）")
    void outboundJsonMatchesContract() throws Exception {
        AiRunItem item = onlyItem(SKU_POLICY);
        String jsonText = new ObjectMapper().writeValueAsString(item);

        assertTrue(jsonText.contains("\"sku_id\""));
        assertTrue(jsonText.contains("\"on_hand_qty\":\"0.000\""));
        assertTrue(jsonText.contains("\"committed_qty\":\"0.000\""));
        assertTrue(jsonText.contains("\"effective_in_transit_qty\":\"0.000\""));
        assertTrue(jsonText.contains("\"lead_time_days\":5"));
        assertTrue(jsonText.contains("\"lead_time_source\":\"POLICY\""));
        assertTrue(jsonText.contains("\"safety_stock_override\":\"12.500\""));
        assertTrue(jsonText.contains("\"sales_series\":[]"));
        assertTrue(!jsonText.contains("listed_date"), "可空字段为 null 时必须整体省略");
    }

    // =====================================================================
    // Q15 / Q16 边界裁定
    // =====================================================================
    @Test
    @DisplayName("Q15：CATEGORY / ALL_ACTIVE 匹配为空 ⇒ 放行空 items（不是用户指定了不存在的对象）")
    void emptyCategoryAndAllActiveAreAllowed() {
        // CATEGORY 命中为空
        assertEquals(List.of(), assembler.assemble(TEAM_ID, WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.CATEGORY, null, "NO-SUCH-CATEGORY"), DATE_FROM, DATE_TO));
        // ALL_ACTIVE 命中为空（本团队没有任何 ACTIVE SKU）
        jdbc.update("UPDATE skus SET status = 'INACTIVE' WHERE team_id = ?", TEAM_ID);
        assertEquals(List.of(), assembler.assemble(TEAM_ID, WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.ALL_ACTIVE, null, null), DATE_FROM, DATE_TO));
    }

    @Test
    @DisplayName("Q15：范围为空时 createRun 仍建 run，交由 Python 判 AI-2002（Java 不报错）")
    void emptyCategoryStillCreatesRun() {
        CreateRunOutcome outcome = appService.createRun(
                new CreateAnalysisRunRequest(AnalysisScopeType.CATEGORY.name(),
                        json.createObjectNode().put("category", "NO-SUCH-CATEGORY"),
                        null, DATE_FROM, DATE_TO, null, ALGO, "explain_risk.v1", "gpt-4o-mini"),
                "idem-empty");

        assertEquals(1L, count("analysis_runs"));
        assertNotNull(outcome.data().runId());
    }

    @Test
    @DisplayName("Q16：2001 个 SKU 早于存在性校验 ⇒ ANL-7007（不是 COM-0001）")
    void skuLimitIsCheckedBeforeExistence() {
        List<Long> many = new java.util.ArrayList<>();
        for (long id = 700_001L; id < 700_001L + 2001L; id++) {
            many.add(id);
        }

        BizException ex = assertThrows(BizException.class, () -> appService.createRun(
                request(AnalysisScopeType.SELECTED_SKUS, many, WAREHOUSE_DEFAULT), "idem-too-many"));

        assertEquals(ErrorCodes.ANL_7007, ex.getErrorCode());
        assertEquals(0L, count("analysis_runs"));
    }

    // =====================================================================
    // 接线：默认仓落库 + 幂等重投（Q3 / V5）
    // =====================================================================
    @Test
    @DisplayName("默认仓解析结果落进 analysis_runs.warehouse_id（请求不带仓时）")
    void resolvedWarehousePersisted() {
        CreateRunOutcome outcome = appService.createRun(
                request(AnalysisScopeType.SELECTED_SKUS, List.of(SKU_LEAD), null), "idem-wh");

        assertEquals(WAREHOUSE_DEFAULT, jdbc.queryForObject(
                "SELECT warehouse_id FROM analysis_runs WHERE id = ?",
                Long.class, outcome.data().runId()));
    }

    @Test
    @DisplayName("Q3 回归：请求不带仓时，同 Idempotency-Key 重投判「同体」并重放（不得 COM-0009）")
    void replayWithoutWarehouseStaysSameBody() {
        CreateAnalysisRunRequest req = request(AnalysisScopeType.SELECTED_SKUS, List.of(SKU_LEAD), null);

        CreateRunOutcome first = appService.createRun(req, "idem-replay-wh");
        CreateRunOutcome second = appService.createRun(req, "idem-replay-wh");

        assertEquals(first.data().runId(), second.data().runId());
        assertTrue(second.replayed(), "同体重投必须标记 replayed");
    }

    @Test
    @DisplayName("R123：预检出站带同一装配器的 items（不再是空数组）")
    void validateRequestCarriesItems() {
        AiValidateRequest validateRequest = appService.buildValidateRequest(
                scope(AnalysisScopeType.SELECTED_SKUS, List.of(SKU_LEAD), null),
                AnalysisParams.resolve(null), DATE_FROM, DATE_TO, null);

        assertEquals(WAREHOUSE_DEFAULT, validateRequest.warehouseId());
        assertEquals(1, validateRequest.items().size());
        assertEquals(SKU_LEAD, validateRequest.items().get(0).skuId());
        assertEquals(2, validateRequest.items().get(0).salesSeries().size());
    }

    @Test
    @DisplayName("装配失败（跨团队 SKU）不建 run、不写审计（Q5）")
    void assemblyFailureLeavesNoRun() {
        assertThrows(BizException.class, () -> appService.createRun(
                request(AnalysisScopeType.SELECTED_SKUS, List.of(SKU_OTHER_TEAM), null), "idem-bad"));

        assertEquals(0L, count("analysis_runs"));
        assertEquals(0L, count("audit_logs"));
    }


    @Test
    @DisplayName("V4/V5 取证：打印出站 items（≥2 SKU 完整字段）与默认仓落库/重投结果")
    void evidencePrint() throws Exception {
        List<AiRunItem> items = assembler.assemble(TEAM_ID, WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.SELECTED_SKUS, List.of(SKU_LEAD, SKU_POLICY), null),
                DATE_FROM, DATE_TO);
        System.out.println("[V4] outbound items (2 SKUs) = "
                + springJson.writeValueAsString(items));

        CreateAnalysisRunRequest req = request(AnalysisScopeType.SELECTED_SKUS, List.of(SKU_LEAD), null);
        Long resolved = assembler.resolveWarehouseId(TEAM_ID, null);
        CreateRunOutcome first = appService.createRun(req, "idem-evidence");
        Long stored = jdbc.queryForObject(
                "SELECT warehouse_id FROM analysis_runs WHERE id = ?", Long.class, first.data().runId());
        CreateRunOutcome replay = appService.createRun(req, "idem-evidence");
        System.out.println("[V5] request.warehouse_id=null → resolved=" + resolved
                + " | analysis_runs.warehouse_id=" + stored
                + " | replay runId=" + replay.data().runId()
                + " replayed=" + replay.replayed()
                + " sameRun=" + first.data().runId().equals(replay.data().runId())
                + " | analysis_runs rows=" + count("analysis_runs"));
    }

    // =====================================================================
    // 工具
    // =====================================================================
    private AiRunItem onlyItem(long skuId) {
        List<AiRunItem> items = assembler.assemble(TEAM_ID, WAREHOUSE_DEFAULT,
                scope(AnalysisScopeType.SELECTED_SKUS, List.of(skuId), null), DATE_FROM, DATE_TO);
        assertEquals(1, items.size());
        return items.get(0);
    }

    private AnalysisScope scope(AnalysisScopeType type, List<Long> skuIds, String category) {
        ArrayNode node = json.createArrayNode();
        if (skuIds != null) {
            skuIds.forEach(node::add);
        }
        var object = json.createObjectNode();
        if (skuIds != null) {
            object.set("sku_ids", node);
        }
        if (category != null) {
            object.put("category", category);
        }
        return AnalysisScope.resolve(type, object);
    }

    private CreateAnalysisRunRequest request(AnalysisScopeType type, List<Long> skuIds, Long warehouseId) {
        ArrayNode node = json.createArrayNode();
        skuIds.forEach(node::add);
        var object = json.createObjectNode();
        object.set("sku_ids", node);
        return new CreateAnalysisRunRequest(type.name(), object, warehouseId,
                DATE_FROM, DATE_TO, null, ALGO, "explain_risk.v1", "gpt-4o-mini");
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }
}