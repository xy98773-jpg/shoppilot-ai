package com.shoppilot.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppilot.analysis.core.AiContract;
import com.shoppilot.analysis.core.AiProperties;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiParams;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateEnvelope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiResultItem;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateRequest;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunResultData;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunStatusData;
import com.shoppilot.analysis.infrastructure.ai.dto.AiScope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiValidateParams;
import com.shoppilot.analysis.infrastructure.ai.dto.AiValidateRequest;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.RequestIdContext;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-T04（B15）{@link AiServiceClient} 单测 —— <b>纯单测，不起 Spring</b>。
 *
 * <p>假对端用 JDK 自带的 {@link HttpServer}（不引入新依赖），覆盖任务书 V3 六类：
 * <ol>
 *   <li>头构造：{@code X-Internal-Token} 与 {@code X-Request-Id} 均透写；路径必须是
 *       {@code /internal/v1/analysis:validate}（<b>冒号</b>，{@code R93}）；</li>
 *   <li>超时：默认常量 5000 / 10000（getter），注入小超时 + 服务端 sleep → {@code ANL-7003}；</li>
 *   <li>重试：{@code create} 仅网络错误重试 1 次（以「create 连接次数 = validate 连接次数 × 2」为证，
 *       对 JDK {@code HttpURLConnection} 自身是否内部重试不敏感）；{@code validate} 0 重试；
 *       HTTP 500 不重试；</li>
 *   <li>错误映射：§2.11 全 19 码逐条 + 未知码 {@code AI-9999} + {@code null} code 兜底；</li>
 *   <li>业务映射：2xx {@code analyzable=false} → {@code ANL-7008}；2xx 空 data → {@code ANL-7004}；
 *       {@code analysis_run_id} 与请求不一致 → {@code ANL-7004}；</li>
 *   <li>判空：令牌空 / 基址空 → {@code ANL-7002}，且<b>零请求</b>发出。</li>
 * </ol>
 *
 * <p>★ 断言只针对「对外码」与「请求是否发出」，不断言日志文本——日志是运维线索，不是契约。
 */
class AiServiceClientTest {

    private static final String TOKEN = "test-internal-token";

    private final ObjectMapper json = new ObjectMapper();

    private HttpServer server;
    private AiProperties properties;

    /** 最近一次请求（方法/路径/头/体）。 */
    private final List<Recorded> recorded = new ArrayList<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";
    private volatile long responseDelayMs = 0L;

    /**
     * 接下来 N 个请求「不发响应直接断开连接」（R145：构造传输层失败）。
     *
     * <p>请求体会被正常读走并计入 {@link #recorded}（请求确实到达过对端），随后
     * {@code exchange.close()} 不发任何响应 ⇒ 客户端读响应时拿到 EOF/IOException，
     * 即 {@code AiServiceClient#isTransportFailure} 判定的「网络错误」——重试分支的输入。
     */
    private volatile int dropNextResponses = 0;

    @BeforeEach
    void startFakePeer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                recorded.add(new Recorded(exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders(), body));
                if (dropNextResponses > 0) {
                    // R145：不发响应直接断开（finally 里 close），制造传输层失败
                    dropNextResponses--;
                    return;
                }
                if (responseDelayMs > 0) {
                    Thread.sleep(responseDelayMs);
                }
                byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, payload.length);
                exchange.getResponseBody().write(payload);
            } catch (Exception ex) {
                // 客户端已超时断开：属预期，忽略
            } finally {
                exchange.close();
            }
        });
        server.start();

        properties = new AiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setInternalToken(TOKEN);
        RequestIdContext.set("req_b15_client_0001");
    }

    @AfterEach
    void stopFakePeer() {
        if (server != null) {
            server.stop(0);
        }
        RequestIdContext.clear();
    }

    // =====================================================================
    // 1. 头构造
    // =====================================================================

    @Test
    @DisplayName("validate 出站：X-Internal-Token + X-Request-Id 透写，路径为冒号动作后缀")
    void validateSendsContractHeadersAndColonPath() throws Exception {
        responseBody = okValidateBody(true);

        newClient().validate(validateRequest());

        Recorded req = onlyRequest();
        assertEquals("POST", req.method());
        assertEquals("/internal/v1/analysis:validate", req.path(),
                "预检路径必须是 analysis:validate（冒号，R93）");
        assertEquals(TOKEN, req.header("X-Internal-Token"));
        assertEquals("req_b15_client_0001", req.header("X-Request-Id"));
    }

    @Test
    @DisplayName("create 出站：路径为资源路径 /internal/v1/analysis/runs，请求体逐字段对齐契约")
    void createSendsContractBody() throws Exception {
        AiRunCreateRequest request = createRequest(30001L);
        responseBody = okCreateBody(30001L);

        newClient().createAnalysisRun(request);

        Recorded req = onlyRequest();
        assertEquals("/internal/v1/analysis/runs", req.path(), "发起端点是资源路径（斜杠）");
        JsonNode body = json.readTree(req.body());
        assertEquals(30001L, body.get("analysis_run_id").asLong());
        assertEquals(7L, body.get("team_id").asLong());
        assertEquals(100L, body.get("warehouse_id").asLong());
        assertEquals("SELECTED_SKUS", body.get("scope").get("scope_type").asText());
        assertEquals(2, body.get("scope").get("sku_ids").size());
        assertEquals("2026-09-16", body.get("date_from").asText());
        assertEquals("2026-10-13", body.get("date_to").asText());
        assertEquals(28, body.get("params").get("window_days").asInt());
        assertEquals("0.5000", body.get("params").get("ss_ratio").asText(), "比率按 4 位字符串传输");
        assertEquals("1.0000", body.get("params").get("cv_high").asText());
        assertFalse(body.get("params").has("budget_limit"), "budget_limit 为 null 时整体省略");
        assertEquals("avg-ma-3sigma-v1", body.get("algorithm_version").asText());
        assertEquals("explain_risk.v1", body.get("prompt_version").asText());
        assertEquals("gpt-4o-mini", body.get("model_name").asText());
        assertTrue(body.get("items").isArray());
        assertEquals(0, body.get("items").size(), "B15 裁定②：items 恒空数组");
    }

    // =====================================================================
    // 2. 超时
    // =====================================================================

    @Test
    @DisplayName("默认超时取契约常量：validate 5000ms / create 10000ms")
    void defaultTimeoutsFollowContract() {
        AiServiceClient client = newClient();
        assertEquals(AiContract.TIMEOUT_VALIDATE_MS, client.getValidateTimeoutMs());
        assertEquals(5000, client.getValidateTimeoutMs());
        assertEquals(AiContract.TIMEOUT_CREATE_MS, client.getCreateTimeoutMs());
        assertEquals(10000, client.getCreateTimeoutMs());
    }

    @Test
    @DisplayName("读超时：注入 150ms + 服务端 sleep 600ms → ANL-7003（不重试，只发 1 次）")
    void readTimeoutMapsToAnl7003() {
        responseDelayMs = 600L;
        responseBody = okValidateBody(true);
        AiServiceClient client = new AiServiceClient(restClientBuilder(), properties, 150, 150);

        BizException ex = assertThrows(BizException.class, () -> client.createAnalysisRun(createRequest(30002L)));

        assertEquals("ANL-7003", ex.getErrorCode());
        assertEquals(1, recorded.size(), "读超时不重试");
    }

    // =====================================================================
    // 3. 重试
    // =====================================================================

    @Test
    @DisplayName("create 连接失败：客户端重试 1 次（1s 退避），最终 ANL-7002；validate 0 重试")
    void createRetriesNetworkFailureOnce() {
        // ★ B15.1 自纠：B15 用「create 连接数 = validate 连接数 × 2」的比值断言，
        //   全量 verify 下实测 validate=2 / create=3（JDK HttpURLConnection 自身也会内部重试一次，
        //   两次探测的内部重试次数并不一致）⇒ 比值不稳定。改为三条确定性证据：
        //   ① create 的尝试数严格大于 validate；② create 实际经历 ≥900ms 固定退避；
        //   ③ validate 无退避。三者共同证明「客户端对 create 重试了 1 次」，
        //   且对 JDK 内部是否重试不敏感。
        Probe validate = probe(() -> {
            AiServiceClient client = new AiServiceClient(restClientBuilder(), properties, 300, 300);
            BizException ex = assertThrows(BizException.class, () -> client.validate(validateRequest()));
            assertEquals("ANL-7002", ex.getErrorCode(), "连接失败（非超时）应映射 7002");
        });

        Probe create = probe(() -> {
            AiServiceClient client = new AiServiceClient(restClientBuilder(), properties, 300, 300);
            BizException ex = assertThrows(BizException.class,
                    () -> client.createAnalysisRun(createRequest(30003L)));
            assertEquals("ANL-7002", ex.getErrorCode());
        });

        assertTrue(create.attempts() > validate.attempts(),
                "create 必须比 validate 多尝试（网络错误重试 1 次）：" + create + " vs " + validate);
        assertTrue(create.elapsedMs() >= 900L,
                "create 必须经历固定 1s 退避（客户端重试的直接证据）：" + create);
        assertTrue(create.elapsedMs() < 2500L,
                "create 只允许一次退避（若重试 2 次会 ≥2000ms）：" + create);
        assertTrue(validate.elapsedMs() < 900L,
                "validate 0 重试（不得出现退避）：" + validate);
    }

    @Test
    @DisplayName("HTTP 500 不重试：只发 1 次，映射 ANL-7002（AI-9001）")
    void http500IsNotRetried() {
        responseStatus = 500;
        responseBody = errorBody("AI-9001", "internal error");

        BizException ex = assertThrows(BizException.class,
                () -> newClient().createAnalysisRun(createRequest(30004L)));

        assertEquals("ANL-7002", ex.getErrorCode());
        assertEquals(1, recorded.size(), "HTTP 错误不重试");
    }

    // =====================================================================
    // 4. 错误映射（19 码全覆盖 + 未知码兜底）
    // =====================================================================

    @Test
    @DisplayName("§2.11 全 19 条 AI 码逐条映射到 ERROR_CODES 的对外码")
    void allNineteenCodesAreMapped() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("AI-0001", "ANL-7002");
        expected.put("AI-1001", "ANL-7004");
        expected.put("AI-1002", "ANL-7004");
        expected.put("AI-1003", "ANL-7002");
        expected.put("AI-1004", "ANL-7002");
        expected.put("AI-2001", "ANL-7004");
        expected.put("AI-2002", "ANL-7008");
        expected.put("AI-3001", "ANL-7002");
        expected.put("AI-4001", "ANL-7002");
        expected.put("AI-5001", "ANL-7002");
        expected.put("AI-5002", "ANL-7002");
        expected.put("AI-5003", "ANL-7002");
        expected.put("AI-5004", "ANL-7002");
        expected.put("AI-6001", "ANL-7005");
        expected.put("AI-6002", "ANL-7012");
        expected.put("AI-6003", "ANL-7002");
        expected.put("AI-7001", "ANL-7001");
        expected.put("AI-7002", "ANL-7007");
        expected.put("AI-9001", "ANL-7002");
        assertEquals(19, expected.size(), "§2.11 条目数必须是 19");

        AiServiceClient client = newClient();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            recorded.clear();
            responseStatus = 400;
            responseBody = errorBody(entry.getKey(), "mapped");

            BizException ex = assertThrows(BizException.class,
                    () -> client.createAnalysisRun(createRequest(31000L)),
                    "AI 码 " + entry.getKey() + " 应抛 BizException");
            assertEquals(entry.getValue(), ex.getErrorCode(),
                    "AI 码 " + entry.getKey() + " 的对外码不符");
            assertFalse(ex.getMessage().contains("AI-"),
                    "AI-xxxx 不得透传给用户：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("未知码 AI-9999 与 null code 一律兜底 ANL-7002，原码不外泄")
    void unknownCodesFallBackToAnl7002() {
        AiServiceClient client = newClient();

        responseStatus = 502;
        responseBody = errorBody("AI-9999", "weird");
        BizException unknown = assertThrows(BizException.class,
                () -> client.createAnalysisRun(createRequest(32001L)));
        assertEquals("ANL-7002", unknown.getErrorCode());
        assertFalse(unknown.getMessage().contains("AI-9999"), "原码只进日志");

        responseBody = "{\"message\":\"no code\",\"detail\":null,\"request_id\":\"req_x\"}";
        BizException noCode = assertThrows(BizException.class,
                () -> client.createAnalysisRun(createRequest(32002L)));
        assertEquals("ANL-7002", noCode.getErrorCode());

        responseBody = "not-a-json";
        BizException garbage = assertThrows(BizException.class,
                () -> client.createAnalysisRun(createRequest(32003L)));
        assertEquals("ANL-7002", garbage.getErrorCode(), "非 JSON 错误体按未知码兜底");
    }

    // =====================================================================
    // 5. 业务映射
    // =====================================================================

    @Test
    @DisplayName("2xx analyzable=false → ANL-7008（文案取 ERROR_CODES §2.8）")
    void analyzableFalseMapsToAnl7008() {
        responseBody = okValidateBody(false);

        BizException ex = assertThrows(BizException.class, () -> newClient().validate(validateRequest()));

        assertEquals("ANL-7008", ex.getErrorCode());
        assertEquals("所选数据不满足分析条件：有效数据不足", ex.getMessage());
    }

    @Test
    @DisplayName("2xx 但 data 为空 → ANL-7004")
    void emptyDataMapsToAnl7004() {
        responseBody = "{\"code\":\"OK\",\"data\":null,\"request_id\":\"req_x\"}";

        BizException ex = assertThrows(BizException.class, () -> newClient().validate(validateRequest()));

        assertEquals("ANL-7004", ex.getErrorCode());
    }

    @Test
    @DisplayName("2xx 但 analysis_run_id 与请求不一致 → ANL-7004（§13.2：Java 生成、Python 只使用）")
    void runIdMismatchMapsToAnl7004() {
        responseBody = okCreateBody(99999L);

        BizException ex = assertThrows(BizException.class,
                () -> newClient().createAnalysisRun(createRequest(30005L)));

        assertEquals("ANL-7004", ex.getErrorCode());
    }

    @Test
    @DisplayName("2xx execution_status 非 ACCEPTED 只告警不失败（仍返回受理信息）")
    void nonAcceptedExecutionStatusIsNotFatal() {
        responseBody = "{\"code\":\"OK\",\"data\":{\"analysis_run_id\":30006,"
                + "\"execution_status\":\"QUEUED\",\"accepted_at\":\"2026-10-19T00:00:00.000Z\"},"
                + "\"request_id\":\"req_x\"}";

        AiRunCreateEnvelope.AiRunAccepted accepted =
                newClient().createAnalysisRun(createRequest(30006L));

        assertNotNull(accepted);
        assertEquals(30006L, accepted.analysisRunId());
        assertEquals("QUEUED", accepted.executionStatus());
    }

    // =====================================================================
    // 6. 判空
    // =====================================================================

    @Test
    @DisplayName("令牌未配置 → ANL-7002 且零请求发出（R92 Java 侧口径）")
    void missingTokenFailsFastWithoutRequest() {
        properties.setInternalToken("");

        BizException ex = assertThrows(BizException.class,
                () -> newClient().createAnalysisRun(createRequest(30007L)));

        assertEquals("ANL-7002", ex.getErrorCode());
        assertEquals(0, recorded.size(), "未配置时不得发出请求");
    }

    @Test
    @DisplayName("基址为空白 → ANL-7002 且零请求发出")
    void blankBaseUrlFailsFastWithoutRequest() {
        properties.setBaseUrl("   ");

        BizException ex = assertThrows(BizException.class, () -> newClient().validate(validateRequest()));

        assertEquals("ANL-7002", ex.getErrorCode());
        assertEquals(0, recorded.size());
    }

    // =====================================================================
    // M4-B20.2：状态查询（3s）与结果查询（10s）
    // =====================================================================

    @Test
    @DisplayName("fetchStatus：GET 状态端点、透写头、按契约字段解析；超时取契约常量 3000")
    void fetchStatusReadsStatusEndpoint() {
        responseStatus = 200;
        responseBody = """
                {"code":"OK","request_id":"req_b202_status",
                 "data":{"analysis_run_id":30001,"execution_status":"RUNNING","progress":40,
                         "current_step":"RISK_RULES","total_skus":3,"processed_skus":2,
                         "llm_status":"PENDING","started_at":"2026-10-19T08:00:01.000Z",
                         "finished_at":null,"error":null}}
                """;

        AiServiceClient client = newClient();
        AiRunStatusData data = client.fetchStatus(30001L);

        assertEquals(30001L, data.analysisRunId());
        assertEquals("RUNNING", data.executionStatus());
        assertEquals(40, data.progress());
        assertEquals("RISK_RULES", data.currentStep());
        assertEquals("PENDING", data.llmStatus());
        assertEquals(3000, client.getStatusTimeoutMs(), "§13.4：状态查询 3s");

        Recorded last = recorded.get(recorded.size() - 1);
        assertEquals("GET", last.method());
        assertEquals("/internal/v1/analysis/runs/30001", last.path());
        assertEquals(TOKEN, last.headers().getFirst("X-Internal-Token"));
        assertEquals("req_b15_client_0001", last.headers().getFirst("X-Request-Id"), "链路头须透传");
    }

    @Test
    @DisplayName("fetchResult：取结果端点、28 字段 item、超时取契约常量 10000")
    void fetchResultReadsResultEndpoint() {
        responseBody = """
                {"code":"OK","request_id":"req_b202_result",
                 "data":{"analysis_run_id":30001,"execution_status":"COMPLETED",
                         "algorithm_version":"avg-ma-3sigma-v1","prompt_version":"explain_risk.v1",
                         "model_name":"gpt-4o-mini","llm_status":"TIMEOUT",
                         "data_start_date":"2026-09-16","data_end_date":"2026-10-13",
                         "summary":{"total_skus":1,"risk_sku_count":1,"suggestion_count":1,
                                    "truncated_count":0,"by_risk_level":{"HIGH":1}},
                         "items":[{"sku_id":5001,"warehouse_id":100,"avg_daily_sales":"6.250",
                                   "sales_qty_total":"162.500","valid_days":26,"data_quality":"GOOD",
                                   "risk_level":"HIGH","risk_types":["STOCKOUT","LOW_STOCK"],
                                   "risk_findings":[{"rule_code":"R-LOWSTOCK",
                                                     "inputs":{"IP":"8.000"},
                                                     "threshold":"IP < D * (L + R) = 87.500",
                                                     "result":"MEDIUM"}],
                                   "on_hand_qty":"8.000","effective_in_transit_qty":"0.000",
                                   "committed_qty":"0.000","inventory_position":"-5.000",
                                   "safety_stock":"25.000","lead_time_days":7,"lead_time_source":"SKU",
                                   "coverage_days":7,"cv":"0.4200","volatility_adjusted":false,
                                   "q_base":"104.500","moq_applied":"50.000",
                                   "order_multiple_applied":"10.000","q_suggest":"110.000",
                                   "truncated_by_budget":false,"llm_explanation":null,
                                   "llm_evidence":[],"llm_status":"TIMEOUT","warnings":[]}]}}
                """;

        AiServiceClient client = newClient();
        AiRunResultData data = client.fetchResult(30001L);

        assertEquals(30001L, data.analysisRunId());
        assertEquals("TIMEOUT", data.llmStatus());
        assertEquals(10000, client.getResultTimeoutMs(), "§3.5：结果 10s（结果可能较大）");
        assertEquals(1, data.itemsOrEmpty().size());
        AiResultItem item = data.itemsOrEmpty().get(0);
        assertEquals("-5.000", item.inventoryPosition(), "★ IP 允许负值（R118），解析层不得截断/拒绝");
        assertEquals("0.4200", item.cv(), "cv 保持 4 位字符串，不得被 float 化");
        assertEquals(List.of("STOCKOUT", "LOW_STOCK"), item.riskTypesOrEmpty());
        assertEquals("R-LOWSTOCK", item.riskFindingsOrEmpty().get(0).ruleCode());
        assertEquals("/internal/v1/analysis/runs/30001/result",
                recorded.get(recorded.size() - 1).path());
    }

    @Test
    @DisplayName("AI-6001（任务不存在）→ ANL-7005；AI-6002（结果未就绪）→ ANL-7012；均不外泄 AI- 前缀")
    void statusAndResultErrorsMapToExternalCodes() {
        responseStatus = 404;
        responseBody = """
                {"code":"AI-6001","message":"Internal run not found","detail":{},"request_id":"r1"}
                """;
        BizException notFound = assertThrows(BizException.class, () -> newClient().fetchStatus(30001L));
        assertEquals("ANL-7005", notFound.getErrorCode());

        responseStatus = 409;
        responseBody = """
                {"code":"AI-6002","message":"Result not ready","detail":{},"request_id":"r2"}
                """;
        BizException notReady = assertThrows(BizException.class, () -> newClient().fetchResult(30001L));
        assertEquals("ANL-7012", notReady.getErrorCode());

        assertFalse(notFound.getMessage().contains("AI-"),
                "对外文案不得含 AI- 前缀（§12 不变量）");
        assertFalse(notReady.getMessage().contains("AI-"));
    }

    @Test
    @DisplayName("状态/结果：2xx 但无 data ⇒ ANL-7004；判空 ⇒ ANL-7002 且零请求")
    void unreadableOrUnconfiguredStatusIsRejected() {
        responseStatus = 200;
        responseBody = """
                {"code":"OK","request_id":"r","data":null}
                """;
        assertEquals("ANL-7004",
                assertThrows(BizException.class, () -> newClient().fetchStatus(30001L)).getErrorCode());

        recorded.clear();
        properties.setInternalToken("");
        assertEquals("ANL-7002",
                assertThrows(BizException.class, () -> newClient().fetchResult(30001L)).getErrorCode());
        assertEquals(0, recorded.size(), "判空时不得发请求（R92）");
    }

    @Test
    @DisplayName("cancelBestEffort：失败只返回 false、不抛（超时兜底不得被远端失败拖垮）")
    void cancelIsBestEffort() {
        responseStatus = 409;
        responseBody = """
                {"code":"AI-6002","message":"already terminal","detail":{},"request_id":"r"}
                """;
        assertFalse(newClient().cancelBestEffort(30001L));

        responseStatus = 200;
        responseBody = """
                {"code":"OK","data":{"analysis_run_id":30001,"execution_status":"CANCELLED",
                 "cancelled_at":"2026-10-19T08:00:00Z"},"request_id":"r"}
                """;
        assertTrue(newClient().cancelBestEffort(30001L));
        assertEquals("POST", recorded.get(recorded.size() - 1).method());
        assertEquals("/internal/v1/analysis/runs/30001:cancel",
                recorded.get(recorded.size() - 1).path(), "★ 冒号动作后缀（R93）");
    }

    @Test
    @DisplayName("R145-cancel 首次网络异常：500ms 退避后重试成功 ⇒ true 且请求 2 次")
    void cancelRetriesOnceAfterNetworkFailure() {
        dropNextResponses = 1; // 第一次连接不发响应直接断开 ⇒ 传输层失败

        assertTrue(newClient().cancelBestEffort(30001L), "重试成功后必须返回 true");

        assertEquals(2, recorded.size(), "首次网络异常后必须重试 1 次（共 2 次请求）");
        assertEquals("/internal/v1/analysis/runs/30001:cancel", recorded.get(1).path());
    }

    @Test
    @DisplayName("R145-cancel 两次都网络异常：false 且请求恰 2 次（重试上限 1 次）")
    void cancelGivesUpAfterTwoNetworkFailures() {
        dropNextResponses = 2; // 两次都断 ⇒ 重试用尽

        assertFalse(newClient().cancelBestEffort(30001L), "最终失败只返回 false，不得抛");

        assertEquals(2, recorded.size(), "重试上限 1 次 ⇒ 恰 2 次请求");
    }

    @Test
    @DisplayName("R145-cancel HTTP 4xx：false 且仅 1 次（业务错误不重试）")
    void cancelDoesNotRetryOnHttpError() {
        responseStatus = 409;
        responseBody = """
                {"code":"AI-6002","message":"already terminal","detail":{},"request_id":"r"}
                """;

        assertFalse(newClient().cancelBestEffort(30001L));

        assertEquals(1, recorded.size(), "HTTP 4xx/5xx 一律不重试");
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private AiServiceClient newClient() {
        return new AiServiceClient(restClientBuilder(), properties);
    }

    /**
     * 模拟 Spring Boot 自动配置的 {@code RestClient.Builder}。
     *
     * <p>★ 为什么不能直接用 {@code RestClient.builder()}：它自带的 Jackson converter 使用裸
     * {@code ObjectMapper}（{@code WRITE_DATES_AS_TIMESTAMPS=true}），会把 {@code LocalDate} 写成
     * {@code [2026,9,16]}；而 Boot 的 {@code HttpMessageConverters} 用的是已禁用该特性的 ObjectMapper，
     * 输出 {@code "2026-09-16"}。不模拟这一层，测试就会对「测试夹具的错误」而非「实现」报警。
     */
    private static RestClient.Builder restClientBuilder() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return RestClient.builder()
                .messageConverters(converters ->
                        converters.add(0, new MappingJackson2HttpMessageConverter(mapper)));
    }

    private Recorded onlyRequest() {
        assertEquals(1, recorded.size(), "应恰好发出 1 次请求");
        return recorded.get(0);
    }

    /**
     * 用「接受后立即 RST」的裸 ServerSocket 计量连接次数与耗时。
     *
     * <p>为什么不用 {@link HttpServer}：它无法在不写响应的情况下断开连接，
     * 而「连接类 IOException」正是重试分支的输入。
     *
     * <p>★ 只暴露「尝试次数 + 耗时」，不做「次数 = 2」的硬编码：JDK {@code HttpURLConnection}
     * 自身也会对失败连接内部重试一次，且不同请求形态下次数不一致；判定客户端是否重试，
     * 用「相对次数 + 是否发生 1s 退避」更稳（见 {@code createRetriesNetworkFailureOnce}）。
     */
    private Probe probe(Runnable action) {
        try (ServerSocket socket = new ServerSocket(0)) {
            AtomicInteger accepted = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        accepted.incrementAndGet();
                        client.setSoLinger(true, 0);
                    } catch (IOException ex) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            String originalBaseUrl = properties.getBaseUrl();
            properties.setBaseUrl("http://127.0.0.1:" + socket.getLocalPort());
            long startedAt = System.nanoTime();
            try {
                action.run();
            } finally {
                // ★ 只做清理，绝不在 finally 里 return——那会吞掉 action 抛出的 AssertionError
                properties.setBaseUrl(originalBaseUrl);
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响断言
                }
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            return new Probe(accepted.get(), elapsedMs);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建假对端 ServerSocket", ex);
        }
    }

    /** 一次连接探测结果：被接受的连接数 + 调用总耗时。 */
    private record Probe(int attempts, long elapsedMs) {
    }

    private static AiRunCreateRequest createRequest(long runId) {
        return new AiRunCreateRequest(
                runId,
                7L,
                100L,
                new AiScope("SELECTED_SKUS", List.of(5001L, 5002L), null),
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 10, 13),
                new AiParams(28, 7, 7, 7, "0.5000", "1.0000", null, true),
                "avg-ma-3sigma-v1",
                "explain_risk.v1",
                "gpt-4o-mini",
                List.of());
    }

    private static AiValidateRequest validateRequest() {
        return new AiValidateRequest(
                7L, 100L, new AiScope("ALL_ACTIVE", null, null),
                LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 13),
                new AiValidateParams(28, 7), List.of());
    }

    private static String okValidateBody(boolean analyzable) {
        return "{\"code\":\"OK\",\"data\":{\"analyzable\":" + analyzable
                + ",\"window_days\":28,\"total_skus\":3,\"analyzable_skus\":3,"
                + "\"insufficient_skus\":0,\"warnings\":[]},\"request_id\":\"req_x\"}";
    }

    private static String okCreateBody(long runId) {
        return "{\"code\":\"OK\",\"data\":{\"analysis_run_id\":" + runId
                + ",\"execution_status\":\"ACCEPTED\","
                + "\"accepted_at\":\"2026-10-19T00:00:00.000Z\"},\"request_id\":\"req_x\"}";
    }

    private static String errorBody(String code, String message) {
        return "{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"detail\":null,\"request_id\":\"req_ai\"}";
    }

    /** 一次被假对端记录的请求。 */
    private record Recorded(String method, String path, Headers headers, String body) {

        String header(String name) {
            return headers.getFirst(name);
        }
    }
}
