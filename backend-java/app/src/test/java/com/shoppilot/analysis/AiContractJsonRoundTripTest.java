package com.shoppilot.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppilot.analysis.core.AiProperties;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiResultItem;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRiskFinding;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunResultData;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>R137 回归闸：Python 回包 → Jackson → 入站 DTO 的「真实 JSON 往返」测试。</b>
 *
 * <h2>为什么必须单独一个类（不能只靠 {@code AiServiceClientTest}）</h2>
 * <p>R137 的表现是 <b>静默</b>的：6 个入站 DTO 全部缺 {@code @JsonProperty}、全仓无
 * {@code property-naming-strategy}，于是 Python 的 snake_case 回包被绑定成一片 {@code null}，
 * 而 {@code FAIL_ON_UNKNOWN_PROPERTIES} 默认关闭 ⇒ <b>不抛任何异常</b>。
 * 端到端表现是「创建 202 → 轮询 80 秒永远 {@code RUNNING}、{@code analysis_items} 0 行、
 * result 端点 0 次调用」。
 *
 * <p>而 B20.2 的 29 条 {@code AnalysisResultWriteServiceTest} 全部 {@code @MockitoBean} 掉
 * {@code AiServiceClient} 并 <b>手工构造 DTO</b> ⇒ 它们测的是「落库逻辑」，
 * <b>永远测不到</b>「Python 的 JSON 能不能被 Jackson 读进来」。这就是「测试替身掩盖真实链路」，
 * 让一个 P1 阻断缺陷潜伏到项目负责人复核才暴露。
 *
 * <h2>本类怎么补上这一层</h2>
 * <p>用 <b>{@code AI_SERVICE_CONTRACT.md §3.5 的完整示例 JSON 原文</b>（不是手拼的最小片段）
 * 作为假对端响应体，经 <b>真实 HTTP + 真实 RestClient + 真实 Jackson</b> 走一遍
 * {@code fetchResult}，然后逐字段断言取回值。任何 DTO 少一个 {@code @JsonProperty}、
 *或键名与契约/Python 模型不一致，这里立刻红。
 *
 * <p>★ 覆盖两个易错点：{@code items[0].inventory_position} 取<b>负值</b> {@code "-5.000"}
 * （{@code R118}：IP 允许为负，Schema 已放宽为 {@code SignedDecimal3Str}）、
 * {@code items[0].cv} 取 <b>4 位小数</b> {@code "0.4200"}（{@code DECIMAL(18,4)}）——
 * 两者都不能被「数量非负 / 3 位」的假设处理掉。
 */
class AiContractJsonRoundTripTest {

    /**
     * {@code AI_SERVICE_CONTRACT.md §3.5} 的响应示例 <b>原文</b>（202 之外的成功响应体）。
     *
     * <p>逐字取自契约的 {@code ```json} 代码块，<b>不做任何改写</b>：
     * 它的作用是「契约原文能否被 Java 读进来」，一改写就失去意义。
     */
    private static final String CONTRACT_RESULT_JSON = """
            {
              "code": "OK",
              "data": {
                "analysis_run_id": 30001,
                "execution_status": "COMPLETED",
                "algorithm_version": "avg-ma-3sigma-v1",
                "prompt_version": "explain_risk.v1",
                "model_name": "gpt-4o-mini",
                "llm_status": "TIMEOUT",
                "data_start_date": "2026-09-16",
                "data_end_date": "2026-10-13",
                "summary": {
                  "total_skus": 3,
                  "risk_sku_count": 2,
                  "suggestion_count": 1,
                  "truncated_count": 0,
                  "by_risk_level": { "HIGH": 1, "UNKNOWN": 1, "NONE": 1 }
                },
                "items": [
                  {
                    "sku_id": 5001,
                    "warehouse_id": 100,
                    "avg_daily_sales": "6.250",
                    "sales_qty_total": "162.500",
                    "valid_days": 26,
                    "data_quality": "GOOD",
                    "risk_level": "HIGH",
                    "risk_types": ["STOCKOUT", "LOW_STOCK"],
                    "risk_findings": [
                      {
                        "rule_code": "R-STOCKOUT",
                        "inputs": { "on_hand_qty": "8.000", "D": "6.250" },
                        "threshold": "on_hand_qty / D <= 2",
                        "result": "HIGH"
                      },
                      {
                        "rule_code": "R-LOWSTOCK",
                        "inputs": { "IP": "8.000", "D": "6.250", "L": 7, "R": 7 },
                        "threshold": "IP < D * (L + R) = 87.500",
                        "result": "MEDIUM"
                      }
                    ],
                    "on_hand_qty": "8.000",
                    "effective_in_transit_qty": "0.000",
                    "committed_qty": "0.000",
                    "inventory_position": "8.000",
                    "safety_stock": "25.000",
                    "lead_time_days": 7,
                    "lead_time_source": "SKU",
                    "coverage_days": 7,
                    "cv": "0.4200",
                    "volatility_adjusted": false,
                    "q_base": "104.500",
                    "moq_applied": "50.000",
                    "order_multiple_applied": "10.000",
                    "q_suggest": "110.000",
                    "truncated_by_budget": false,
                    "llm_explanation": null,
                    "llm_evidence": [],
                    "llm_status": "TIMEOUT",
                    "warnings": ["LLM 解释超时，已降级为无解释结果"]
                  },
                  {
                    "sku_id": 5003,
                    "warehouse_id": 100,
                    "avg_daily_sales": null,
                    "sales_qty_total": "9.000",
                    "valid_days": 3,
                    "data_quality": "INSUFFICIENT",
                    "risk_level": "UNKNOWN",
                    "risk_types": ["DATA_INSUFFICIENT"],
                    "risk_findings": [
                      {
                        "rule_code": "R-DATAINSUFFICIENT",
                        "inputs": { "valid_days": 3, "min_days": 7 },
                        "threshold": "n < MIN_DAYS(7)",
                        "result": "UNKNOWN"
                      }
                    ],
                    "on_hand_qty": "0.000",
                    "effective_in_transit_qty": "0.000",
                    "committed_qty": "0.000",
                    "inventory_position": "0.000",
                    "safety_stock": null,
                    "lead_time_days": 7,
                    "lead_time_source": "DEFAULT",
                    "coverage_days": 7,
                    "cv": null,
                    "volatility_adjusted": false,
                    "q_base": null,
                    "moq_applied": null,
                    "order_multiple_applied": null,
                    "q_suggest": null,
                    "truncated_by_budget": false,
                    "llm_explanation": null,
                    "llm_evidence": [],
                    "llm_status": "SKIPPED",
                    "warnings": ["有效销售天数不足，未产出补货建议"]
                  }
                ]
              },
              "request_id": "req_anl_003"
            }
            """;

    private HttpServer server;
    private AiProperties properties;

    /**
     * 假对端本次要回的响应体。
     *
     * <p>★ 做成<b>可变字段</b>而不是「每个用例新起一个 server」：HttpServer 的
     * {@code createContext} 只能注册一次路径，重复注册会抛
     * {@code IllegalArgumentException}；而「改字段」能让所有变体用例共用同一段夹具代码。
     */
    private volatile String responseBody = CONTRACT_RESULT_JSON;

    @BeforeEach
    void startFakePeer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream ignored = exchange.getRequestBody()) {
                byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } catch (Exception ex) {
                // 客户端提前断开：属预期
            } finally {
                exchange.close();
            }
        });
        server.start();

        properties = new AiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setInternalToken("round-trip-token");
    }

    @AfterEach
    void stopFakePeer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiServiceClient newClient() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        RestClient.Builder builder = RestClient.builder()
                .messageConverters(converters ->
                        converters.add(0, new MappingJackson2HttpMessageConverter(mapper)));
        return new AiServiceClient(builder, properties);
    }

    // =====================================================================
    // R137 主体：契约原文的逐字段往返
    // =====================================================================

    @Test
    @DisplayName("R137：§3.5 契约原文经真实 HTTP + Jackson 往返后，data 顶层 10 字段逐值相等")
    void contractResultJsonBindsEveryTopLevelField() {
        AiRunResultData data = newClient().fetchResult(30001L);

        assertNotNull(data, "data 必须被绑定（曾因缺 @JsonProperty 而为 null）");
        assertEquals(30001L, data.analysisRunId());
        assertEquals("COMPLETED", data.executionStatus());
        assertEquals("avg-ma-3sigma-v1", data.algorithmVersion());
        assertEquals("explain_risk.v1", data.promptVersion());
        assertEquals("gpt-4o-mini", data.modelName());
        assertEquals("TIMEOUT", data.llmStatus());
        assertEquals("2026-09-16", data.dataStartDate());
        assertEquals("2026-10-13", data.dataEndDate());

        assertNotNull(data.summary(), "summary 必须被绑定");
        assertEquals(3, data.summary().totalSkus());
        assertEquals(2, data.summary().riskSkuCount());
        assertEquals(1, data.summary().suggestionCount());
        assertEquals(0, data.summary().truncatedCount());
        assertEquals(Map.of("HIGH", 1, "UNKNOWN", 1, "NONE", 1), data.summary().byRiskLevel(),
                "★ by_risk_level 是 Map，键为 snake_case 下的等级名；漏标会得到 null");

        assertEquals(2, data.itemsOrEmpty().size());
    }

    @Test
    @DisplayName("R137：items[0] 的 28 字段逐值相等（含 cv 4 位、risk_findings、risk_types）")
    void contractResultItemBindsEveryField() {
        AiResultItem item = newClient().fetchResult(30001L).itemsOrEmpty().get(0);

        assertEquals(5001L, item.skuId());
        assertEquals(100L, item.warehouseId());
        assertEquals("6.250", item.avgDailySales());
        assertEquals("162.500", item.salesQtyTotal());
        assertEquals(26, item.validDays());
        assertEquals("GOOD", item.dataQuality());
        assertEquals("HIGH", item.riskLevel());
        assertEquals(List.of("STOCKOUT", "LOW_STOCK"), item.riskTypesOrEmpty());
        assertEquals("8.000", item.onHandQty());
        assertEquals("0.000", item.effectiveInTransitQty());
        assertEquals("0.000", item.committedQty());
        assertEquals("8.000", item.inventoryPosition());
        assertEquals("25.000", item.safetyStock());
        assertEquals(7, item.leadTimeDays());
        assertEquals("SKU", item.leadTimeSource());
        assertEquals(7, item.coverageDays());
        assertEquals("0.4200", item.cv(), "★ cv 是 4 位小数，不得被 float 化或截成 3 位");
        assertEquals(Boolean.FALSE, item.volatilityAdjusted());
        assertEquals("104.500", item.qBase());
        assertEquals("50.000", item.moqApplied());
        assertEquals("10.000", item.orderMultipleApplied());
        assertEquals("110.000", item.qSuggest());
        assertEquals(Boolean.FALSE, item.truncatedByBudget());
        assertNull(item.llmExplanation(), "契约示例该字段为 null");
        assertEquals(List.of(), item.llmEvidenceOrEmpty());
        assertEquals("TIMEOUT", item.llmStatus());
        assertEquals(List.of("LLM 解释超时，已降级为无解释结果"), item.warnings());

        List<AiRiskFinding> findings = item.riskFindingsOrEmpty();
        assertEquals(2, findings.size(), "★ risk_findings 是嵌套对象数组，漏标会得到空数组");
        AiRiskFinding stockout = findings.get(0);
        assertEquals("R-STOCKOUT", stockout.ruleCode());
        assertEquals("on_hand_qty / D <= 2", stockout.threshold());
        assertEquals("HIGH", stockout.result());
        assertEquals(Map.of("on_hand_qty", "8.000", "D", "6.250"), stockout.inputs(),
                "inputs 是自由 Map，键原样保留");
        assertEquals("R-LOWSTOCK", findings.get(1).ruleCode());
    }

    @Test
    @DisplayName("R137：items[1] 数据不足条目——null 字段仍是 null，空数组仍是空数组")
    void insufficientItemKeepsNullsAndEmptyArrays() {
        AiResultItem item = newClient().fetchResult(30001L).itemsOrEmpty().get(1);

        assertEquals(5003L, item.skuId());
        assertEquals("INSUFFICIENT", item.dataQuality());
        assertEquals("UNKNOWN", item.riskLevel());
        assertEquals(List.of("DATA_INSUFFICIENT"), item.riskTypesOrEmpty());
        assertNull(item.avgDailySales(), "数据不足 ⇒ D 为 null");
        assertNull(item.safetyStock());
        assertNull(item.cv(), "mean=0 ⇒ cv 为 null");
        assertNull(item.qBase());
        assertNull(item.qSuggest());
        assertEquals("DEFAULT", item.leadTimeSource());
        assertEquals(1, item.riskFindingsOrEmpty().size());
        assertEquals("R-DATAINSUFFICIENT", item.riskFindingsOrEmpty().get(0).ruleCode());
    }

    /**
     * R137 的核心断言：<b>没有一个字段是静默的 {@code null}</b>。
     *
     * <p>为什么单独一条：若某字段漏标 {@code @JsonProperty}，上面几条断言里对应的
     * {@code assertEquals} 会红，但这类失败信息（「expected X but was null」）不指向根因。
     * 这一条把所有<b>契约示例里非 null</b> 的标量字段一次性列出，
     * 失败时能一眼看出「哪个键没绑上」。
     */
    /**
     * V5 取证：把「契约原文 → 真实 HTTP + Jackson → 入站 DTO」的每个字段取值<b>打印</b>出来。
     *
     * <p>为什么要有这条「只打印不断言」的用例：评审要看的是「逐字段实际取到了什么」，
     * 而不是「测试通过」这一句话。R137 的失败模式恰好是「静默 null」，
     * 只有把取回值原样摊开，才能一眼看出哪个键没绑上。
     */
    @Test
    @DisplayName("V5 取证：打印契约原文往返后的逐字段实际取值")
    void evidencePrintRoundTrip() {
        AiRunResultData data = newClient().fetchResult(30001L);

        System.out.println("[V5] envelope.data = analysis_run_id=" + data.analysisRunId()
                + " execution_status=" + data.executionStatus()
                + " algorithm_version=" + data.algorithmVersion()
                + " prompt_version=" + data.promptVersion()
                + " model_name=" + data.modelName()
                + " llm_status=" + data.llmStatus()
                + " data_start_date=" + data.dataStartDate()
                + " data_end_date=" + data.dataEndDate());
        System.out.println("[V5] summary = total_skus=" + data.summary().totalSkus()
                + " risk_sku_count=" + data.summary().riskSkuCount()
                + " suggestion_count=" + data.summary().suggestionCount()
                + " truncated_count=" + data.summary().truncatedCount()
                + " by_risk_level=" + data.summary().byRiskLevel());
        for (int i = 0; i < data.itemsOrEmpty().size(); i++) {
            AiResultItem item = data.itemsOrEmpty().get(i);
            System.out.println("[V5] items[" + i + "] = sku_id=" + item.skuId()
                    + " warehouse_id=" + item.warehouseId()
                    + " avg_daily_sales=" + item.avgDailySales()
                    + " sales_qty_total=" + item.salesQtyTotal()
                    + " valid_days=" + item.validDays()
                    + " data_quality=" + item.dataQuality()
                    + " risk_level=" + item.riskLevel()
                    + " risk_types=" + item.riskTypesOrEmpty()
                    + " risk_findings=" + item.riskFindingsOrEmpty().stream()
                    .map(f -> f.ruleCode() + "/" + f.result() + "/inputs=" + f.inputs()
                            + "/threshold=" + f.threshold())
                    .toList()
                    + " on_hand_qty=" + item.onHandQty()
                    + " effective_in_transit_qty=" + item.effectiveInTransitQty()
                    + " committed_qty=" + item.committedQty()
                    + " inventory_position=" + item.inventoryPosition()
                    + " safety_stock=" + item.safetyStock()
                    + " lead_time_days=" + item.leadTimeDays()
                    + " lead_time_source=" + item.leadTimeSource()
                    + " coverage_days=" + item.coverageDays()
                    + " cv=" + item.cv()
                    + " volatility_adjusted=" + item.volatilityAdjusted()
                    + " q_base=" + item.qBase()
                    + " moq_applied=" + item.moqApplied()
                    + " order_multiple_applied=" + item.orderMultipleApplied()
                    + " q_suggest=" + item.qSuggest()
                    + " truncated_by_budget=" + item.truncatedByBudget()
                    + " llm_explanation=" + item.llmExplanation()
                    + " llm_evidence=" + item.llmEvidenceOrEmpty()
                    + " llm_status=" + item.llmStatus()
                    + " warnings=" + item.warnings());
        }
        // 负 IP 变体（R118）
        AiRunResultData neg = fetchWith(CONTRACT_RESULT_JSON.replace(
                "\"inventory_position\": \"8.000\"", "\"inventory_position\": \"-5.000\""));
        System.out.println("[V5] negative-IP variant items[0].inventory_position = "
                + neg.itemsOrEmpty().get(0).inventoryPosition()
                + " | q_base=" + neg.itemsOrEmpty().get(0).qBase());
    }

    @Test
    @DisplayName("R137：契约示例中所有非 null 字段都非 null（漏标即红，且一眼看出漏了哪个键）")
    void noNonNullFieldIsSilentlyNull() {
        AiResultData_Probe probe = new AiResultData_Probe(newClient().fetchResult(30001L));
        List<String> nulls = probe.nullNonNullFields();
        assertTrue(nulls.isEmpty(), "以下字段在契约示例中非 null，实际绑定成了 null：" + nulls);
    }

    /** 把「契约示例中非 null 的字段」列出来逐个查空的探针。 */
    private record AiResultData_Probe(AiRunResultData data) {

        /** @return 契约示例中非 null、实际却绑定成 null 的字段名（空 = 全绑上了）。 */
        List<String> nullNonNullFields() {
            List<String> out = new java.util.ArrayList<>();
            check(out, "analysis_run_id", data.analysisRunId());
            check(out, "execution_status", data.executionStatus());
            check(out, "algorithm_version", data.algorithmVersion());
            check(out, "prompt_version", data.promptVersion());
            check(out, "model_name", data.modelName());
            check(out, "llm_status", data.llmStatus());
            check(out, "data_start_date", data.dataStartDate());
            check(out, "data_end_date", data.dataEndDate());
            check(out, "summary", data.summary());
            if (data.summary() != null) {
                check(out, "summary.total_skus", data.summary().totalSkus());
                check(out, "summary.risk_sku_count", data.summary().riskSkuCount());
                check(out, "summary.suggestion_count", data.summary().suggestionCount());
                check(out, "summary.truncated_count", data.summary().truncatedCount());
                check(out, "summary.by_risk_level", data.summary().byRiskLevel());
            }
            for (int i = 0; i < data.itemsOrEmpty().size(); i++) {
                AiResultItem item = data.itemsOrEmpty().get(i);
                String p = "items[" + i + "].";
                check(out, p + "sku_id", item.skuId());
                check(out, p + "warehouse_id", item.warehouseId());
                check(out, p + "sales_qty_total", item.salesQtyTotal());
                check(out, p + "valid_days", item.validDays());
                check(out, p + "data_quality", item.dataQuality());
                check(out, p + "risk_level", item.riskLevel());
                check(out, p + "risk_types", item.riskTypesOrEmpty());
                check(out, p + "risk_findings", item.riskFindingsOrEmpty());
                check(out, p + "on_hand_qty", item.onHandQty());
                check(out, p + "effective_in_transit_qty", item.effectiveInTransitQty());
                check(out, p + "committed_qty", item.committedQty());
                check(out, p + "inventory_position", item.inventoryPosition());
                check(out, p + "lead_time_days", item.leadTimeDays());
                check(out, p + "lead_time_source", item.leadTimeSource());
                check(out, p + "coverage_days", item.coverageDays());
                check(out, p + "volatility_adjusted", item.volatilityAdjusted());
                check(out, p + "truncated_by_budget", item.truncatedByBudget());
                check(out, p + "llm_status", item.llmStatus());
                check(out, p + "warnings", item.warnings());
            }
            return out;
        }

        private static void check(List<String> out, String field, Object value) {
            if (value == null) {
                out.add(field);
            }
        }
    }

    // =====================================================================
    // 变体：负 IP（R118）+ 错误体（AiErrorDetailData 的三个键）
    // =====================================================================

    /**
     * 负 {@code inventory_position} 的往返（{@code R118}）。
     *
     * <p>契约示例里该字段是 {@code "8.000"}（正数），但真实数据允许为负（超卖：
     * {@code on_hand + eff_in_transit − committed < 0}）。{@code schemas/analysis_response.py}
     * 已把它放宽为 {@code SignedDecimal3Str}，Java 侧<b>不得</b>把它按「数量非负」处理。
     */
    @Test
    @DisplayName("R137+R118：inventory_position 为负值 -5.000 时原样绑定，不被截断/拒绝")
    void negativeInventoryPositionBindsVerbatim() {
        String negativeIp = CONTRACT_RESULT_JSON.replace("\"inventory_position\": \"8.000\"",
                "\"inventory_position\": \"-5.000\"");

        AiRunResultData data = fetchWith(negativeIp);

        assertEquals("-5.000", data.itemsOrEmpty().get(0).inventoryPosition(),
                "★ 负 IP 必须原样保留（超卖是合法业务状态，R118）");
        assertEquals("104.500", data.itemsOrEmpty().get(0).qBase(), "其余字段不受影响");
    }

    /** {@code execution_status=FAILED} 时 {@code error} 是 {@code AiErrorDetailData}（3 键）。 */
    @Test
    @DisplayName("R137：error 对象的 code/message/detail 三键逐值绑定（AiErrorDetailData）")
    void errorObjectBindsAllThreeKeys() {
        String failed = """
                {"code":"OK","request_id":"req_err_1","data":{
                  "analysis_run_id":30001,"execution_status":"FAILED","progress":5,
                  "current_step":"RISK_RULES","total_skus":10,"processed_skus":3,
                  "llm_status":"NOT_REQUESTED","started_at":"2026-10-19T08:00:00Z",
                  "finished_at":"2026-10-19T08:00:05Z",
                  "error":{"code":"AI-3001","message":"规则引擎执行失败",
                           "detail":{"stage":"RISK_RULES","reason":"boom"}}}}
                """;

        var status = fetchStatusWith(failed);

        assertEquals(30001L, status.analysisRunId());
        assertEquals("FAILED", status.executionStatus());
        assertEquals(5, status.progress());
        assertEquals("RISK_RULES", status.currentStep());
        assertEquals(10, status.totalSkus());
        assertEquals(3, status.processedSkus());
        assertEquals("NOT_REQUESTED", status.llmStatus());
        assertEquals("2026-10-19T08:00:00Z", status.startedAt());
        assertEquals("2026-10-19T08:00:05Z", status.finishedAt());
        assertNotNull(status.error(), "★ error 曾因缺 @JsonProperty 而静默为 null");
        assertEquals("AI-3001", status.error().code());
        assertEquals("规则引擎执行失败", status.error().message());
        assertNotNull(status.error().detail(), "detail 是结构化补充信息");
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 用变体响应体发一次 {@code fetchResult}（真实 HTTP + 真实 Jackson）。 */
    private AiRunResultData fetchWith(String body) {
        responseBody = body;
        return newClient().fetchResult(30001L);
    }

    /** 用变体响应体发一次 {@code fetchStatus}（真实 HTTP + 真实 Jackson）。 */
    private com.shoppilot.analysis.infrastructure.ai.dto.AiRunStatusData fetchStatusWith(String body) {
        responseBody = body;
        return newClient().fetchStatus(30001L);
    }
}
