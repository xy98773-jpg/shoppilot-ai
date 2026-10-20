package com.shoppilot.analysis.infrastructure.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.analysis.core.AiContract;
import com.shoppilot.analysis.core.AiOutboundHeaders;
import com.shoppilot.analysis.core.AiProperties;
import com.shoppilot.analysis.infrastructure.ai.dto.AiErrorEnvelope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateEnvelope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateRequest;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunResultData;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunResultEnvelope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunStatusData;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunStatusEnvelope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiValidateEnvelope;
import com.shoppilot.analysis.infrastructure.ai.dto.AiValidateRequest;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Python {@code service-ai} 的内部 HTTP 客户端（M4-T04）。
 *
 * <p><b>权威来源</b>：
 * <ul>
 *   <li>端点与请求/响应结构 —— {@code AI_SERVICE_CONTRACT.md §3.1/§3.2}（路径常量见 {@link AiContract}，
 *       ★ {@code analysis:validate} 是<b>冒号</b>动作后缀，{@code R93}）；</li>
 *   <li>超时与重试 —— {@code DESIGN_BASIS.md §13.4}：预检 5s / 0 次，发起 10s / 仅网络错误 1 次；</li>
 *   <li>错误映射 —— {@code ERROR_CODES.md §2.11} 全 19 条（{@link AiErrorMapper}）+ {@code R101} 口径
 *       （{@code AI-1003} 只表「路由不存在」，业务 404 用 {@code AI-6001}）；</li>
 *   <li>两条不变量 —— {@code AI_SERVICE_CONTRACT.md §12}：{@code AI-xxxx} 一律不得透传前端；
 *       LLM 降级不破坏规则结果。</li>
 * </ul>
 *
 * <h2>★ 三个刻意的实现选择</h2>
 * <ol>
 *   <li><b>两个 RestClient，各自固定超时</b>：{@link AiProperties} 只描述「地址 + 令牌」，超时是契约常量
 *       （{@link AiContract#TIMEOUT_VALIDATE_MS} / {@link AiContract#TIMEOUT_CREATE_MS}）。用
 *       {@code builder.clone()} 复制 Spring Boot 自动配置的 {@code RestClient.Builder}，
 *       避免为「客户端配置」再引入一套自定义属性（也让测试可用四参构造器注入毫秒级超时）。</li>
 *   <li><b>不在本类做启动期 fail-fast</b>：{@code R92} 裁定 —— 令牌缺失时在<b>调用点</b>判空并抛
 *       {@code ANL-7002}（{@link #requireConfigured(String)}），而不是让 34 个 {@code @SpringBootTest}
 *       因缺一个属性起不来。</li>
 *   <li><b>错误体解析独立于传输层</b>：{@code onStatus} 先把原始响应当作字符串读出，再单独映射为
 *       {@code AiErrorEnvelope}——即使 Python 返回的是 FastAPI 默认形态（无 {@code code}），
 *       也能落到「未知码 → {@code ANL-7002}」兜底，而不是抛一个 500。</li>
 * </ol>
 *
 * <h2>★ 重试边界（{@code §13.4}）</h2>
 * <ul>
 *   <li>{@code analysis:validate}：<b>0 次</b>重试（快速失败，调用方决定是否继续）；</li>
 *   <li>{@code POST /analysis/runs}：<b>仅网络错误</b>重试 <b>1 次</b>（固定 1s 退避，整体重发请求）；
 *       HTTP 4xx/5xx <b>不</b>重试（业务/契约错误重试必然同样失败）；读超时<b>不</b>重试
 *       （{@code ANL-7003}，避免把 10s 超时放大成 21s）；</li>
 *   <li>{@code POST /analysis/runs/{id}:cancel}（{@link #cancelBestEffort}）：<b>5s</b> 超时，
 *       <b>仅网络错误（连接失败 / 读超时）</b>重试 <b>1 次</b>（固定 <b>500ms</b> 退避）；
 *       HTTP 4xx/5xx <b>不</b>重试；最终失败只 WARN + 返回 {@code false}，<b>不抛</b>
 *       （{@code R145}，best-effort 语义）。</li>
 * </ul>
 *
 * <p>线程安全：无状态；{@link RestClient} 本身线程安全。
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    /** 网络错误重试的固定退避（{@code §13.4}：1 次重试；退避时长取 1s，不引入随机抖动）。 */
    private static final long RETRY_BACKOFF_MS = 1000L;

    /** 网络错误的最大尝试次数（首次 + 1 次重试）。 */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * 取消的最大尝试次数（首次 + 1 次重试；{@code R145}：仅网络错误重试）。
     *
     * <p>与 {@link #MAX_ATTEMPTS} 同值但语义不同：create 的读超时<b>不</b>重试，
     * cancel 的读超时<b>要</b>重试（取消是廉价操作，且 5s 已是端到端预算的上限而非执行预算）。
     */
    private static final int CANCEL_MAX_ATTEMPTS = 2;

    /** 取消重试的固定退避（{@code R145}：500ms，比 create 的 1s 短——取消是资源回收的善意尝试）。 */
    private static final long CANCEL_RETRY_BACKOFF_MS = 500L;

    /** 错误信封解析器（只做 JSON → record，不参与业务判定）。 */
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 契约错误码里唯一表示「受理成功」的业务码（{@code AI_SERVICE_CONTRACT §3}）。 */
    private static final String ACCEPTED_STATUS = "ACCEPTED";

    private final AiProperties aiProperties;
    private final RestClient validateClient;
    private final RestClient createClient;
    private final RestClient statusClient;
    private final RestClient resultClient;
    private final int validateTimeoutMs;
    private final int createTimeoutMs;
    private final int statusTimeoutMs;
    private final int resultTimeoutMs;

    /**
     * Spring 装配用构造器：读契约常量超时（5s / 10s / 3s / 10s）。
     *
     * <p>{@code @Autowired} 标在此处而非多参构造器：Spring 需要的是一个「全部入参可由容器提供」的
     * 构造器，几个 {@code int} 只能由测试显式传入。
     */
    @Autowired
    public AiServiceClient(RestClient.Builder builder, AiProperties aiProperties) {
        this(builder, aiProperties,
                AiContract.TIMEOUT_VALIDATE_MS, AiContract.TIMEOUT_CREATE_MS,
                AiContract.TIMEOUT_STATUS_MS, AiContract.TIMEOUT_RESULT_MS);
    }

    /**
     * 可注入超时的构造器（供单测缩短等待）。
     *
     * @param builder           Spring Boot 自动配置的 {@code RestClient.Builder}（clone 后各建一个客户端）
     * @param aiProperties      AI 服务地址与令牌
     * @param validateTimeoutMs 预检超时（毫秒）
     * @param createTimeoutMs   发起超时（毫秒）
     */
    public AiServiceClient(RestClient.Builder builder, AiProperties aiProperties,
                           int validateTimeoutMs, int createTimeoutMs) {
        this(builder, aiProperties, validateTimeoutMs, createTimeoutMs,
                AiContract.TIMEOUT_STATUS_MS, AiContract.TIMEOUT_RESULT_MS);
    }

    /**
     * 六参构造器（M4-B20.2：把状态 3s / 结果 10s 也做成可注入，便于超时单测）。
     *
     * <p>四参构造器保留：{@code AiServiceClientTest} 的既有用例按 4 参构造，
     * 状态/结果超时走契约常量即可，<b>不必</b>让每个既有用例都多传两个数。
     */
    public AiServiceClient(RestClient.Builder builder, AiProperties aiProperties,
                           int validateTimeoutMs, int createTimeoutMs,
                           int statusTimeoutMs, int resultTimeoutMs) {
        this.aiProperties = aiProperties;
        this.validateTimeoutMs = validateTimeoutMs;
        this.createTimeoutMs = createTimeoutMs;
        this.statusTimeoutMs = statusTimeoutMs;
        this.resultTimeoutMs = resultTimeoutMs;
        this.validateClient = builder.clone()
                .requestFactory(requestFactory(validateTimeoutMs))
                .build();
        this.createClient = builder.clone()
                .requestFactory(requestFactory(createTimeoutMs))
                .build();
        this.statusClient = builder.clone()
                .requestFactory(requestFactory(statusTimeoutMs))
                .build();
        this.resultClient = builder.clone()
                .requestFactory(requestFactory(resultTimeoutMs))
                .build();
    }

    /** 预检读/连超时（毫秒）；供测试断言契约常量。 */
    public int getValidateTimeoutMs() {
        return validateTimeoutMs;
    }

    /** 发起读/连超时（毫秒）；供测试断言契约常量。 */
    public int getCreateTimeoutMs() {
        return createTimeoutMs;
    }

    /** 状态查询读/连超时（毫秒）；供测试断言契约常量 {@code TIMEOUT_STATUS_MS=3000}。 */
    public int getStatusTimeoutMs() {
        return statusTimeoutMs;
    }

    /** 结果查询读/连超时（毫秒）；供测试断言契约常量 {@code TIMEOUT_RESULT_MS=10000}。 */
    public int getResultTimeoutMs() {
        return resultTimeoutMs;
    }

    // =====================================================================
    // 数据质量预检（POST /internal/v1/analysis:validate，5s，0 重试）
    // =====================================================================

    /**
     * 数据质量预检（{@code AI_SERVICE_CONTRACT.md §3.1}）。
     *
     * @param request 预检请求（items 装配在 B16+ 接入；本批恒空数组）
     * @return 预检结果（{@code analyzable=true}）
     * @throws BizException {@code ANL-7002}（未配置/连接失败/HTTP 错误）、{@code ANL-7003}（超时）、
     *                      {@code ANL-7004}（2xx 但响应体不可解析/无 data）、
     *                      {@code ANL-7008}（{@code analyzable=false}）、映射后的其它 {@code ANL-xxxx}
     */
    public AiValidateEnvelope.AiValidateResult validate(AiValidateRequest request) {
        requireConfigured("validate");
        AiValidateEnvelope envelope;
        try {
            envelope = validateClient.post()
                    .uri(uri(AiContract.PATH_VALIDATE))
                    .headers(headers -> headers.setAll(outboundHeaders()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw translateError(res.getStatusCode().value(), res, AiContract.PATH_VALIDATE);
                    })
                    .body(AiValidateEnvelope.class);
        } catch (RestClientException ex) {
            // ★ 传输类异常不一定被包装成 ResourceAccessException：读响应体/写请求阶段抛出的
            //   SocketException 会被 DefaultRestClient 包成普通 RestClientException，
            //   其 cause 是 IOException。故必须按 cause 链分类：
            //   超时 → 7003；连接类 → 7002；其余（JSON 解析/结构不符）→ 7004。
            if (isReadTimeout(ex)) {
                throw transportFailure(ex, "validate", true);
            }
            if (isTransportFailure(ex)) {
                throw transportFailure(ex, "validate", false);
            }
            throw unreadableResponse("validate", ex);
        }

        if (envelope == null || envelope.data() == null) {
            throw unreadableResponse("validate", null);
        }
        if (!envelope.data().analyzable()) {
            // ERROR_CODES §2.8：ANL-7008 的「用户可见提示」即本句，不得改写为技术描述
            throw new BizException(ErrorCodes.ANL_7008, "所选数据不满足分析条件：有效数据不足");
        }
        return envelope.data();
    }

    // =====================================================================
    // 创建分析任务（POST /internal/v1/analysis/runs，10s，仅网络错误重试 1 次）
    // =====================================================================

    /**
     * 发起分析任务（{@code AI_SERVICE_CONTRACT.md §3.2}）。
     *
     * @param request 创建请求（{@code analysis_run_id} 由 Java 生成，Python 只使用不回写）
     * @return 受理信息（{@code execution_status=ACCEPTED}）
     * @throws BizException {@code ANL-7003}（读超时，不重试）、{@code ANL-7002}（未配置/连接失败 2 次/
     *                      HTTP 错误/未知码）、{@code ANL-7004}（2xx 但响应体不可解析、无 data、
     *                      {@code analysis_run_id} 缺失或与请求不一致）
     */
    public AiRunCreateEnvelope.AiRunAccepted createAnalysisRun(AiRunCreateRequest request) {
        requireConfigured("create");
        try {
            return createWithRetry(request);
        } catch (RestClientException ex) {
            if (isReadTimeout(ex)) {
                throw transportFailure(ex, "create", true);
            }
            if (isTransportFailure(ex)) {
                // 两次连接类尝试均失败（createWithRetry 已重试 1 次）
                throw transportFailure(ex, "create", false);
            }
            throw unreadableResponse("create", ex);
        }
    }

    /** 发起请求 + 仅网络错误重试；读超时立即转 {@code ANL-7003}（不重试）。 */
    private AiRunCreateEnvelope.AiRunAccepted createWithRetry(AiRunCreateRequest request) {
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doCreate(request);
            } catch (RestClientException ex) {
                if (!isTransportFailure(ex)) {
                    // 业务/解析类失败：重试必然同样失败，直接交给外层（7004）
                    throw ex;
                }
                if (isReadTimeout(ex)) {
                    throw transportFailure(ex, "create", true);
                }
                lastFailure = ex;
                log.warn("AI 服务连接失败（第 {}/{} 次）：path={} err={}",
                        attempt, MAX_ATTEMPTS, AiContract.PATH_RUNS, ex.toString());
                if (attempt < MAX_ATTEMPTS) {
                    backoff();
                }
            }
        }
        // 连接类错误重试用尽：交给调用方按 502 处理（ERROR_CODES §2.8 ANL-7002）
        throw lastFailure == null
                ? new BizException(ErrorCodes.ANL_7002, userMessage(ErrorCodes.ANL_7002))
                : lastFailure;
    }

    /** 单次发起（不重试、不捕获传输异常）。 */
    private AiRunCreateEnvelope.AiRunAccepted doCreate(AiRunCreateRequest request) {
        AiRunCreateEnvelope envelope = createClient.post()
                .uri(uri(AiContract.PATH_RUNS))
                .headers(headers -> headers.setAll(outboundHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw translateError(res.getStatusCode().value(), res, AiContract.PATH_RUNS);
                })
                .body(AiRunCreateEnvelope.class);

        if (envelope == null || envelope.data() == null
                || envelope.data().analysisRunId() == null
                || !envelope.data().analysisRunId().equals(request.analysisRunId())) {
            throw unreadableResponse("create", null);
        }
        if (!ACCEPTED_STATUS.equals(envelope.data().executionStatus())) {
            // 只告警不失败：契约首期恒 ACCEPTED，出现其它值属对端演进，业务仍可轮询状态
            log.warn("AI 服务返回非 ACCEPTED 的受理状态：runId={} executionStatus={} requestId={}",
                    envelope.data().analysisRunId(), envelope.data().executionStatus(), envelope.requestId());
        }
        return envelope.data();
    }

    // =====================================================================
    // 状态查询（GET /internal/v1/analysis/runs/{id}，3s，仅网络错误重试 1 次）
    // =====================================================================

    /**
     * 查询分析任务状态（{@code AI_SERVICE_CONTRACT.md §3.4}）。
     *
     * <p>调用方：{@code AnalysisRunSyncService}（定时扫描与 {@code GET /runs/{id}} 读时同步共用）。
     *
     * <p>★ 返回值<b>不</b>做业务判定：{@code execution_status} 是 Python 的执行态，
     * 映射成 Java 的 {@code AnalysisRunStatus} 是应用层的职责（{@code §13.2} Java 是权威）。
     * 本方法只负责「拿到结构化状态」与「把 HTTP 层失败翻译成对外码」。
     *
     * @throws BizException {@code ANL-7005}（{@code AI-6001} 任务不存在）、
     *                      {@code ANL-7003}（读超时）、{@code ANL-7002}（未配置/连接失败 2 次/HTTP 错误）、
     *                      {@code ANL-7004}（2xx 但响应体不可解析或无 data）
     */
    public AiRunStatusData fetchStatus(long analysisRunId) {
        requireConfigured("fetchStatus");
        String path = AiContract.PATH_RUN.replace("{analysisRunId}", Long.toString(analysisRunId));
        try {
            AiRunStatusEnvelope envelope = getWithRetry(statusClient, path, "fetchStatus",
                    AiRunStatusEnvelope.class);
            if (envelope == null || envelope.data() == null) {
                throw unreadableResponse("fetchStatus", null);
            }
            return envelope.data();
        } catch (RestClientException ex) {
            if (isReadTimeout(ex)) {
                throw transportFailure(ex, "fetchStatus", true);
            }
            if (isTransportFailure(ex)) {
                throw transportFailure(ex, "fetchStatus", false);
            }
            throw unreadableResponse("fetchStatus", ex);
        }
    }

    // =====================================================================
    // 结果查询（GET /internal/v1/analysis/runs/{id}/result，10s，仅网络错误重试 1 次）
    // =====================================================================

    /**
     * 拉取分析结果（{@code AI_SERVICE_CONTRACT.md §3.5}）。
     *
     * <p>★ 只在 {@code execution_status=COMPLETED} 后调用；本方法<b>不</b>二次校验，
     * 校验在 {@code AnalysisResultWriteService}（18 项，{@code §7}）。
     *
     * @throws BizException {@code ANL-7012}（{@code AI-6002} 结果未就绪）、{@code ANL-7005}
     *                      （{@code AI-6001} 任务不存在）、{@code ANL-7003}（读超时）、
     *                      {@code ANL-7002}（未配置/连接失败/HTTP 错误）、{@code ANL-7004}（响应不可解析）
     */
    public AiRunResultData fetchResult(long analysisRunId) {
        requireConfigured("fetchResult");
        String path = AiContract.PATH_RUN_RESULT.replace("{analysisRunId}", Long.toString(analysisRunId));
        try {
            AiRunResultEnvelope envelope = getWithRetry(resultClient, path, "fetchResult",
                    AiRunResultEnvelope.class);
            if (envelope == null || envelope.data() == null) {
                throw unreadableResponse("fetchResult", null);
            }
            return envelope.data();
        } catch (RestClientException ex) {
            if (isReadTimeout(ex)) {
                throw transportFailure(ex, "fetchResult", true);
            }
            if (isTransportFailure(ex)) {
                throw transportFailure(ex, "fetchResult", false);
            }
            throw unreadableResponse("fetchResult", ex);
        }
    }

    /**
     * 取消分析任务（{@code AI_SERVICE_CONTRACT.md §3.6}），<b>尽力而为</b>。
     *
     * <p>★ 只在超时兜底路径调用（{@code §5.38} 裁定 #5）：本地已置 {@code TIMEOUT}，
     * Python 侧的任务若还在跑会白白占额度。取消失败<b>只 WARN</b>、不抛异常、
     * 不影响本地终态——本地 {@code TIMEOUT} 是权威（{@code §13.2}），远端取消只是资源回收的善意尝试。
     *
     * <h2>★ 超时与重试（R145，M4-B22 契约口径补齐）</h2>
     * <ul>
     *   <li><b>5s 超时</b>：复用 {@link #validateClient}（契约 {@code §6.1}：cancel 5s），
     *       不新增 client 字段；</li>
     *   <li><b>仅网络错误（连接失败 / 读超时）重试 1 次</b>，固定 500ms 退避；</li>
     *   <li><b>HTTP 4xx/5xx 一律不重试</b>（业务/契约错误，重试必然同样失败）。</li>
     * </ul>
     *
     * @return 是否取消成功（供调用方记日志；失败不抛）
     */
    public boolean cancelBestEffort(long analysisRunId) {
        if (!aiProperties.isConfigured()) {
            return false;
        }
        String path = AiContract.PATH_RUN_CANCEL.replace("{analysisRunId}", Long.toString(analysisRunId));
        for (int attempt = 1; attempt <= CANCEL_MAX_ATTEMPTS; attempt++) {
            try {
                validateClient.post()
                        .uri(uri(path))
                        .headers(headers -> headers.setAll(outboundHeaders()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of())
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw translateError(res.getStatusCode().value(), res, path);
                        })
                        .toBodilessEntity();
                return true;
            } catch (BizException ex) {
                // HTTP 4xx/5xx（已按 19 码映射）：业务/契约错误 ⇒ 不重试（R145）
                log.warn("取消分析任务失败（不影响本地终态）：runId={} code={} http 状态类错误不重试",
                        analysisRunId, ex.getErrorCode());
                return false;
            } catch (RuntimeException ex) {
                if (!isTransportFailure(ex)) {
                    // 解析/结构类异常：重试必然同样失败
                    log.warn("取消分析任务异常（不影响本地终态）：runId={} err={}",
                            analysisRunId, ex.toString());
                    return false;
                }
                if (attempt >= CANCEL_MAX_ATTEMPTS) {
                    log.warn("取消分析任务网络失败（不影响本地终态）：runId={} err={}",
                            analysisRunId, ex.toString());
                    return false;
                }
                log.warn("取消分析任务网络失败（第 {}/{} 次，将重试）：runId={}",
                        attempt, CANCEL_MAX_ATTEMPTS, analysisRunId);
                if (!cancelBackoff()) {
                    // 退避被中断：放弃重试，保持 best-effort 语义（仍不抛）
                    log.warn("取消分析任务退避被中断，放弃重试：runId={}", analysisRunId);
                    return false;
                }
            }
        }
        // 循环内所有分支均已 return；此行为编译可达性兜底
        return false;
    }

    /**
     * GET + 仅网络错误重试 1 次（状态/结果两个读端点共用）。
     *
     * <p>与 POST 的 {@link #createWithRetry} 同构：HTTP 4xx/5xx 与解析失败都<b>不</b>重试
     * （重试必然同样失败），只有「连接类 I/O 失败」重试 1 次；读超时直接转 {@code ANL-7003}
     * （{@code §13.4}：状态 3s / 结果 10s 都是端到端预算，重试会把它放大一倍）。
     */
    private <T> T getWithRetry(RestClient client, String path, String operation, Class<T> bodyType) {
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return client.get()
                        .uri(uri(path))
                        .headers(headers -> headers.setAll(outboundHeaders()))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw translateError(res.getStatusCode().value(), res, path);
                        })
                        .body(bodyType);
            } catch (RestClientException ex) {
                if (!isTransportFailure(ex)) {
                    throw ex;
                }
                if (isReadTimeout(ex)) {
                    throw transportFailure(ex, operation, true);
                }
                lastFailure = ex;
                log.warn("AI 服务连接失败（第 {}/{} 次）：path={} err={}",
                        attempt, MAX_ATTEMPTS, path, ex.toString());
                if (attempt < MAX_ATTEMPTS) {
                    backoff();
                }
            }
        }
        throw lastFailure == null
                ? new BizException(ErrorCodes.ANL_7002, userMessage(ErrorCodes.ANL_7002))
                : lastFailure;
    }

    // =====================================================================
    // 内部：配置判空 / 传输异常归类 / 错误体映射
    // =====================================================================

    /**
     * 调用点判空（{@code R92} Java 侧口径）：未配置令牌或基址为空 → 不发请求，直接 {@code ANL-7002}。
     *
     * <p>★ 不做启动期 fail-fast：见类注释。日志记 ERROR 便于运维定位「忘记注入环境变量」。
     */
    private void requireConfigured(String operation) {
        if (!aiProperties.isConfigured()
                || aiProperties.getBaseUrl() == null
                || aiProperties.getBaseUrl().isBlank()) {
            log.error("AI 服务未配置，拒绝发起调用：operation={} tokenConfigured={} baseUrl={}",
                    operation, aiProperties.isConfigured(), aiProperties.getBaseUrl());
            throw new BizException(ErrorCodes.ANL_7002, userMessage(ErrorCodes.ANL_7002));
        }
    }

    /**
     * 传输层异常 → 对外码。
     *
     * @param readTimeout {@code true} ⇒ 读超时 {@code ANL-7003}；{@code false} ⇒ 连接失败/其它 I/O {@code ANL-7002}
     */
    private BizException transportFailure(Throwable ex, String operation, boolean readTimeout) {
        String code = readTimeout ? ErrorCodes.ANL_7003 : ErrorCodes.ANL_7002;
        if (readTimeout) {
            log.warn("AI 服务调用超时：operation={} timeoutMs={} err={}",
                    operation, timeoutOf(operation), ex.toString());
        } else {
            log.warn("AI 服务连接失败：operation={} err={}", operation, ex.toString());
        }
        return new BizException(code, userMessage(code));
    }

    /** 2xx 但响应体不可解析 / 结构不完整 → {@code ANL-7004}（二次校验未过）。 */
    private BizException unreadableResponse(String operation, Throwable cause) {
        log.warn("AI 服务响应不可解析或结构不完整：operation={} err={}",
                operation, cause == null ? "empty body" : cause.toString());
        return new BizException(ErrorCodes.ANL_7004, userMessage(ErrorCodes.ANL_7004));
    }

    /**
     * HTTP 4xx/5xx → 解析四字段错误信封 → 19 码映射（{@code R101}）。
     *
     * <p>原 AI 码、HTTP 状态、对端 requestId <b>只进 WARN 日志</b>，绝不进响应体（{@code §12} 不变量）。
     */
    private BizException translateError(int httpStatus, ClientHttpResponse response, String path) {
        String body = "";
        try {
            body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("读取 AI 错误响应体失败：path={} http={} err={}", path, httpStatus, ex.toString());
        }
        AiErrorEnvelope envelope = parseErrorEnvelope(body);
        String aiCode = envelope == null ? null : envelope.code();
        String externalCode = AiErrorMapper.toExternalCode(aiCode);
        log.warn("AI 服务返回错误：path={} http={} aiCode={} known={} externalCode={} requestId={} detail={}",
                path, httpStatus, aiCode, AiErrorMapper.isKnown(aiCode), externalCode,
                envelope == null ? null : envelope.requestId(),
                envelope == null ? truncateForLog(body) : envelope.message());
        return new BizException(externalCode, userMessage(externalCode));
    }

    /** 错误体 → 信封；非法 JSON / 空体返回 {@code null}（按未知码兜底）。 */
    private static AiErrorEnvelope parseErrorEnvelope(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return ERROR_MAPPER.readValue(body, AiErrorEnvelope.class);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 对外中文提示（{@code ERROR_CODES.md §2} 的「用户可见提示」列，逐字对齐）。
     *
     * <p>只覆盖本 client 可能产出的码；未知码兜底取 {@code ANL-7002} 的文案。
     */
    private static String userMessage(String code) {
        return switch (code) {
            case ErrorCodes.ANL_7001 -> "该 SKU 范围已有正在运行的分析任务，请稍后或取消后重试";
            case ErrorCodes.ANL_7002 -> "AI 分析服务暂时不可用，请稍后重试";
            case ErrorCodes.ANL_7003 -> "AI 分析服务响应超时，请稍后重试";
            case ErrorCodes.ANL_7004 -> "分析结果校验未通过，请稍后重试";
            case ErrorCodes.ANL_7005 -> "分析任务不存在";
            case ErrorCodes.ANL_7007 -> "单次分析的 SKU 数量过多，请缩小范围";
            case ErrorCodes.ANL_7008 -> "所选数据不满足分析条件：有效数据不足";
            case ErrorCodes.ANL_7009 -> "分析参数不合法";
            case ErrorCodes.ANL_7012 -> "分析结果尚未生成，请等待任务完成";
            case ErrorCodes.ANL_7013 -> "分析时间区间不合法";
            default -> "AI 分析服务暂时不可用，请稍后重试";
        };
    }

    // =====================================================================
    // 内部：工具
    // =====================================================================

    /** 出站头（令牌 + request-id；两者都取不到时为空映射——由 {@link #requireConfigured} 兜底）。 */
    private Map<String, String> outboundHeaders() {
        return AiOutboundHeaders.forInternalCall(aiProperties.getInternalToken());
    }

    /** 拼接内部端点（baseUrl 允许带尾斜杠；路径含冒号动作后缀，故用 {@link URI} 不做模板解析）。 */
    private URI uri(String path) {
        String base = aiProperties.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private int timeoutOf(String operation) {
        return switch (operation) {
            case "validate" -> validateTimeoutMs;
            case "create" -> createTimeoutMs;
            case "fetchStatus" -> statusTimeoutMs;
            default -> resultTimeoutMs;
        };
    }

    /**
     * 读超时判定：cause 链含 {@link SocketTimeoutException}。
     *
     * <p>★ JDK {@code HttpURLConnection} 的<b>连接</b>超时也抛 {@code SocketTimeoutException}
     * （消息为 {@code connect timed out}），与「读超时」必须区分：连接超时属网络错误、可重试；
     * 读超时若重试会把 10s 上限放大成 21s（{@code §13.4} 的 10s 是端到端预算）。
     */
    private static boolean isReadTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                String message = current.getMessage();
                boolean connectTimeout = message != null
                        && message.toLowerCase(Locale.ROOT).contains("connect timed out");
                return !connectTimeout;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 是否为「传输层」失败（{@code §13.4} 中「仅网络错误可重试」的判定）。
     *
     * <p>两类载体都要识别：① Spring 显式包装的 {@link ResourceAccessException}；
     * ② 被 {@code DefaultRestClient} 包成普通 {@link RestClientException} 的 IOException
     * ——读响应体/写请求阶段抛出的 {@link SocketException}、{@link EOFException} 走这条路径。
     * <p>JSON 解析失败（{@code JsonProcessingException}）<b>不</b>属此类：那是 422（{@code ANL-7004}），
     * 重试必然同样失败。
     */
    private static boolean isTransportFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ResourceAccessException
                    || current instanceof SocketException
                    || current instanceof SocketTimeoutException
                    || current instanceof EOFException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 固定 1s 退避；中断即恢复标记并放弃重试（{@code ANL-7002}）。 */
    private static void backoff() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCodes.ANL_7002, userMessage(ErrorCodes.ANL_7002), ex);
        }
    }

    /**
     * 取消重试的固定 500ms 退避；被中断时恢复中断标记并返回 {@code false}（调用方放弃重试）。
     *
     * <p>★ 与 {@link #backoff()} 的差异：cancel 是 best-effort 语义，<b>不得因退避失败而抛异常</b>
     * （会把「取消失败」升级成「取消调用方 500」）。
     */
    private static boolean cancelBackoff() {
        try {
            Thread.sleep(CANCEL_RETRY_BACKOFF_MS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 每个客户端一份独立的请求工厂：读/连超时都取同一值（契约只给单值超时）。 */
    private static SimpleClientHttpRequestFactory requestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    private static String truncateForLog(String body) {
        if (body == null) {
            return null;
        }
        String singleLine = body.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 256 ? singleLine : singleLine.substring(0, 256) + "…";
    }
}
