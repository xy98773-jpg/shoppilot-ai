package com.shoppilot.analysis.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.shoppilot.analysis.application.dto.CreateAnalysisRunRequest;
import com.shoppilot.analysis.application.dto.CreateRunOutcome;
import com.shoppilot.analysis.application.dto.CreateRunResponse;
import com.shoppilot.analysis.domain.AnalysisParams;
import com.shoppilot.analysis.domain.AnalysisRunStatus;
import com.shoppilot.analysis.domain.AnalysisScope;
import com.shoppilot.analysis.domain.AnalysisScopeType;
import com.shoppilot.analysis.domain.CanonicalJson;
import com.shoppilot.analysis.domain.LlmStatus;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiParams;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunCreateRequest;
import com.shoppilot.analysis.infrastructure.ai.dto.AiScope;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisRunMapper;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.RequestIdContext;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 分析运行的应用服务（M4-T03：{@code createRun}）。
 *
 * <p><b>权威依据</b>：{@code API_CONTRACT.md §11.5}（端点与响应）、{@code §6}（幂等键语义）、
 * {@code ERROR_CODES.md §2.8}（{@code ANL-7001/7007/7009/7013}）、{@code DESIGN_BASIS.md §9.7}
 * （状态机）、{@code §13.2}（Java 先落 {@code PENDING} 再把 id 交给 Python）、
 * {@code §13.4}（{@code timeout_at = 创建 + 300s}）、{@code D-25}（{@code run_no} 团队内日期前缀 + 序列）。
 *
 * <h2>★ 为什么<b>不</b>加 {@code @Transactional}</h2>
 * <p>本方法由「多条<b>彼此独立</b>的单语句写」组成：插入（自动提交）→ 调 Python（不可回滚的外部副作用）
 * → 更新状态 → 写审计。若把整段包进一个事务：① 插入在调 Python 前不提交，Python 拿到的 {@code
 * analysis_run_id} 在库里尚不存在，B20 的轮询会 404；② Python 失败后即便 catch 住并改成 {@code FAILED}，
 * 外层事务仍可能因异常被标记 rollback-only → {@code UnexpectedRollbackException}（{@code R79} 同一坑）；
 * ③ 审计走 {@code REQUIRES_NEW}，与业务事务混在一起只会增加死锁面。故按「单语句自动提交 + 显式状态补偿」
 * 推进，审计用 {@link AuditWriteService#record}（永不抛、旁路）。
 *
 * <h2>★ 幂等四态（{@code API_CONTRACT §6}）</h2>
 * <ol>
 *   <li>缺 key → 控制器 {@code COM-0010}（本类兜底再判一次，防绕过控制器直调）；</li>
 *   <li>同键同体 → 返回既有 run，{@code replayed=true}（控制器加 {@code Idempotent-Replayed: true}）；</li>
 *   <li>同键异体 → {@code COM-0009}（409）；比对范围：{@code scope_type / scope_json / warehouse_id /
 *       params_json / algorithm_version}（JSON 经 {@link CanonicalJson} 归一化后比较）；</li>
 *   <li>同键 + 首次进行中 → 同样返回既有 run（202），不新建、不报错。</li>
 * </ol>
 *
 * <h2>★ 并发保护 {@code ANL-7001}（409）</h2>
 * <p>同团队 + 同 {@code scope_type} + {@code warehouse_id} 相等（{@code null} 安全）+ 范围有交集
 * （{@link AnalysisScope#overlaps}：{@code ALL_ACTIVE} 任一进行中即冲突；{@code SELECTED_SKUS} 集合相交；
 * {@code CATEGORY} 同类目）且存在 {@code PENDING}/{@code RUNNING}。
 */
@Service
public class AnalysisAppService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisAppService.class);

    /** 审计业务类型（{@code DATABASE_DESIGN §6-J.1}：{@code ANALYSIS_RUN}）。 */
    private static final String AUDIT_BIZ_TYPE = "ANALYSIS_RUN";

    /** 运行编号前缀（{@code D-25}：日期前缀 + 团队内日内序列）。 */
    private static final String RUN_NO_PREFIX = "RUN-";

    /** 运行编号日期前缀（UTC 自然日；裁定③：analysis 不依赖 identity 取团队时区）。 */
    private static final DateTimeFormatter RUN_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 单次分析整体超时（{@code DESIGN_BASIS §13.4}：默认 300s，由 B20 的超时扫描消费）。 */
    private static final long TIMEOUT_SECONDS = 300L;

    /** {@code run_no} 撞唯一键时的最大尝试次数（任务书 §三.2：上限 3 次）。 */
    private static final int MAX_RUN_NO_ATTEMPTS = 3;

    /** 幂等键最大长度（{@code API_CONTRACT §6}：1..128）。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 失败原因列宽（{@code analysis_runs.error_message VARCHAR(500)}）。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    private final AnalysisRunMapper runMapper;
    private final AiServiceClient aiServiceClient;
    private final AuditWriteService auditWriteService;

    public AnalysisAppService(AnalysisRunMapper runMapper,
                              AiServiceClient aiServiceClient,
                              AuditWriteService auditWriteService) {
        this.runMapper = runMapper;
        this.aiServiceClient = aiServiceClient;
        this.auditWriteService = auditWriteService;
    }

    /**
     * 创建分析运行（{@code POST /api/v1/analysis/runs}）。
     *
     * @param request        请求体（{@code team_id} 不在其中——只从 {@link TeamContext} 取）
     * @param idempotencyKey {@code Idempotency-Key} 请求头（非空，长度 1..128）
     * @return 响应数据 + 是否幂等重放
     * @throws BizException {@code COM-0010}（缺/非法幂等键）、{@code COM-0001}（结构/字段非法）、
     *                      {@code COM-0009}（同键异体）、{@code ANL-7001}（并发冲突）、
     *                      {@code ANL-7007}（SKU 超限）、{@code ANL-7009}（参数越界）、
     *                      {@code ANL-7013}（日期区间非法）、以及 client 透出的 {@code ANL-7002/7003/7004}
     */
    public CreateRunOutcome createRun(CreateAnalysisRunRequest request, String idempotencyKey) {
        // a. 团队/用户上下文（team_id 绝不来自请求体）
        TeamContext.Snapshot snapshot = TeamContext.require();
        String key = requireIdempotencyKey(idempotencyKey);

        // b. 参数校验（顺序固定：scope → params → 日期区间 → LLM 配置）
        AnalysisScope scope = resolveScope(request);
        AnalysisParams params = AnalysisParams.resolve(request.params());
        validateDateRange(request);
        validatePromptVersion(request, params);

        // c. 幂等：命中槽位 → 比对；一致直接返回既有 run
        AnalysisRunEntity existing = findByIdempotencyKey(key);
        if (existing != null) {
            return reconcile(existing, request, scope, params);
        }

        // d. 并发保护 ANL-7001（同团队 + 同 scope_type + 仓库相等 + 范围相交 + 进行中）
        assertNoConflictingRun(scope, request.warehouseId());

        // e. 落库（PENDING 快照；run_no 撞键重取序列，上限 3 次）
        //    ★ R108/A2：撞键后若回查到同键 run（并发窗口），必须回到 c 的幂等判定——
        //    同体返回既有（replayed=true），异体 COM-0009；禁止直接返回 concurrent 跳过比对。
        AnalysisRunEntity saved;
        try {
            saved = insertPending(snapshot, request, scope, params, key);
        } catch (IdempotentRace race) {
            return reconcile(race.existing(), request, scope, params);
        }

        // f/g. 发起；失败置 FAILED（不留 PENDING 悬挂）后原样上抛
        try {
            aiServiceClient.createAnalysisRun(toAiRequest(saved, scope, params, request));
        } catch (BizException ex) {
            markFailed(saved, ex.getErrorCode(), ex.getMessage());
            auditFinish(snapshot, saved, AuditResult.FAILURE,
                    "分析任务发起失败：" + ex.getErrorCode(), ex.getErrorCode());
            throw ex;
        } catch (RuntimeException ex) {
            markFailed(saved, ErrorCodes.COM_0002, "系统繁忙，请稍后重试");
            auditFinish(snapshot, saved, AuditResult.FAILURE, "分析任务发起异常", ErrorCodes.COM_0002);
            throw ex;
        }

        markRunning(saved, params.enableLlm());
        auditCreate(snapshot, saved, scope);

        log.info("分析任务已创建：runId={} runNo={} teamId={} scopeType={} totalSkus={}",
                saved.getId(), saved.getRunNo(), snapshot.teamId(), saved.getScopeType(), saved.getTotalSkus());
        return new CreateRunOutcome(CreateRunResponse.from(saved), false);
    }

    // =====================================================================
    // b. 参数校验
    // =====================================================================

    /** 范围类型 + 明细（{@code COM-0001} / {@code ANL-7007}，全部由 {@link AnalysisScope} 负责）。 */
    private static AnalysisScope resolveScope(CreateAnalysisRunRequest request) {
        AnalysisScopeType type = AnalysisScopeType.fromCode(request.scopeType());
        if (type == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("scope_type",
                            "必须是 ALL_ACTIVE / SELECTED_SKUS / CATEGORY 之一")));
        }
        return AnalysisScope.resolve(type, request.scopeJson());
    }

    /** 日期区间：缺失 → {@code COM-0001}；{@code date_from > date_to} → {@code ANL-7013}。 */
    private static void validateDateRange(CreateAnalysisRunRequest request) {
        if (request.dateFrom() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("date_from", "date_from 不能为空")));
        }
        if (request.dateTo() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("date_to", "date_to 不能为空")));
        }
        if (request.dateFrom().isAfter(request.dateTo())) {
            throw new BizException(ErrorCodes.ANL_7013, "分析时间区间不合法");
        }
    }

    /** {@code enable_llm=true} 时 {@code prompt_version} 必填（否则 Python 无法选模板）。 */
    private static void validatePromptVersion(CreateAnalysisRunRequest request, AnalysisParams params) {
        if (Boolean.TRUE.equals(params.enableLlm())
                && (request.promptVersion() == null || request.promptVersion().isBlank())) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("prompt_version",
                            "enable_llm=true 时 prompt_version 不能为空")));
        }
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCodes.COM_0010, "缺少必要的请求信息");
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw BizException.withDetails(ErrorCodes.COM_0010, "缺少必要的请求信息",
                    List.of(ApiErrorDetail.of("Idempotency-Key", "长度必须在 1..128 之间")));
        }
        return trimmed;
    }

    // =====================================================================
    // c. 幂等
    // =====================================================================

    /** 按 {@code (team_id, idempotency_key)} 查既有 run（租户线自动追加 team_id）。 */
    private AnalysisRunEntity findByIdempotencyKey(String key) {
        List<AnalysisRunEntity> rows = runMapper.selectList(Wrappers.<AnalysisRunEntity>lambdaQuery()
                .eq(AnalysisRunEntity::getIdempotencyKey, key)
                .orderByAsc(AnalysisRunEntity::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 同键同体 → 返回既有 run（{@code replayed=true}）；异体 → {@code COM-0009}。
     *
     * <p><b>★ R108（B15.1）：比对范围必须覆盖 {@code API_CONTRACT §6.3} 的全部请求字段</b>，
     * 顺序：{@code scope_type → scope_json → warehouse_id → date_from → date_to →
     * params_json → algorithm_version → prompt_version → model_name}。
     * B15 只比了其中 5 项，导致「同键仅改日期」返回 202 静默重放旧 run（客户端完全无法察觉）。
     *
     * <p>口径：
     * <ul>
     *   <li>日期用 {@link Objects#equals}（{@code LocalDate} 值相等）；</li>
     *   <li>{@code prompt_version} / {@code model_name} 两侧先「空白 → null」再比（与
     *       {@code insertPending} 的落库口径一致，避免 {@code ""} 与 {@code null} 造成的假异体）；</li>
     *   <li>JSON 两列仍走 {@link CanonicalJson#jsonEquals}（键序/数值归一），不弱化。</li>
     * </ul>
     */
    private static CreateRunOutcome reconcile(AnalysisRunEntity existing,
                                              CreateAnalysisRunRequest request,
                                              AnalysisScope scope,
                                              AnalysisParams params) {
        boolean sameBody = Objects.equals(existing.getScopeType(), scope.scopeType().name())
                && CanonicalJson.jsonEquals(CanonicalJson.parseOrNull(existing.getScopeJson()),
                        CanonicalJson.parseOrNull(scope.canonicalJson()))
                && Objects.equals(existing.getWarehouseId(), request.warehouseId())
                // ★ date_from / date_to 的落库载体：data_start_date / data_end_date
                //   （DATABASE_DESIGN §5.14 定义即「输入销售数据最早/最晚日期」= 请求窗口；
                //   表无独立 date_from/date_to 列，且本批禁新增迁移）
                && Objects.equals(existing.getDataStartDate(), request.dateFrom())
                && Objects.equals(existing.getDataEndDate(), request.dateTo())
                && CanonicalJson.jsonEquals(CanonicalJson.parseOrNull(existing.getParamsJson()),
                        CanonicalJson.parseOrNull(params.canonicalJson()))
                && Objects.equals(existing.getAlgorithmVersion(), request.algorithmVersion())
                && Objects.equals(blankToNull(existing.getPromptVersion()),
                        blankToNull(request.promptVersion()))
                && Objects.equals(blankToNull(existing.getModelName()),
                        blankToNull(request.modelName()));
        if (!sameBody) {
            throw new BizException(ErrorCodes.COM_0009, "相同的幂等键已用于另一笔请求，请更换幂等键");
        }
        return new CreateRunOutcome(CreateRunResponse.from(existing), true);
    }

    /**
     * 撞键后回查到同键 run 的内部信号（{@code R108/A2}）。
     *
     * <p>用异常而非返回值：{@code insertPending} 正常只返回「刚插入的实体」，
     * 把「并发命中」塞进返回类型会让调用方多一个 {@code null} 分支、也容易再次漏掉比对。
     */
    private static final class IdempotentRace extends RuntimeException {

        private final transient AnalysisRunEntity existing;

        private IdempotentRace(AnalysisRunEntity existing) {
            super("idempotent-key race", null, false, false);
            this.existing = existing;
        }

        private AnalysisRunEntity existing() {
            return existing;
        }
    }

    // =====================================================================
    // d. 并发保护
    // =====================================================================

    /** 存在同范围进行中 run → {@code ANL-7001}（409）。 */
    private void assertNoConflictingRun(AnalysisScope scope, Long warehouseId) {
        List<AnalysisRunEntity> inFlight = runMapper.selectList(Wrappers.<AnalysisRunEntity>lambdaQuery()
                .in(AnalysisRunEntity::getStatus,
                        AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name())
                .eq(AnalysisRunEntity::getScopeType, scope.scopeType().name()));
        for (AnalysisRunEntity other : inFlight) {
            if (!Objects.equals(other.getWarehouseId(), warehouseId)) {
                continue;
            }
            AnalysisScope otherScope = storedScope(other);
            if (otherScope != null && scope.overlaps(otherScope)) {
                log.warn("分析任务并发冲突：existingRunId={} existingRunNo={} scopeType={}",
                        other.getId(), other.getRunNo(), other.getScopeType());
                throw new BizException(ErrorCodes.ANL_7001,
                        "该 SKU 范围已有正在运行的分析任务，请稍后或取消后重试");
            }
        }
    }

    /**
     * 解析进行中 run 的入库范围。
     *
     * <p>★ {@link AnalysisScope#parse} 对 {@code null} 返回 {@code null}；而 {@code ALL_ACTIVE} 的
     * {@code scope_json} 本来就是 {@code NULL}——若直接透传会把「全部 SKU 的进行中任务」误判为无范围、
     * 从而漏报 {@code ANL-7001}。故先按 {@code scope_type} 还原，再解析明细。
     */
    private static AnalysisScope storedScope(AnalysisRunEntity entity) {
        AnalysisScopeType type = AnalysisScopeType.fromCode(entity.getScopeType());
        if (type == null) {
            return null;
        }
        if (type == AnalysisScopeType.ALL_ACTIVE) {
            return new AnalysisScope(type, null, null);
        }
        AnalysisScope parsed = AnalysisScope.parse(CanonicalJson.parseOrNull(entity.getScopeJson()));
        return parsed != null && parsed.scopeType() == type ? parsed : null;
    }

    // =====================================================================
    // e. 落库
    // =====================================================================

    /** 插入 {@code PENDING} 快照；撞 {@code uk_run_team_no} 时重取序列（上限 3 次）。 */
    private AnalysisRunEntity insertPending(TeamContext.Snapshot snapshot,
                                            CreateAnalysisRunRequest request,
                                            AnalysisScope scope,
                                            AnalysisParams params,
                                            String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String dayPrefix = RUN_NO_PREFIX + RUN_NO_DATE.format(now) + "-";

        AnalysisRunEntity entity = new AnalysisRunEntity();
        entity.setStatus(AnalysisRunStatus.PENDING.name());
        entity.setScopeType(scope.scopeType().name());
        entity.setScopeJson(scope.canonicalJson());
        entity.setWarehouseId(request.warehouseId());
        entity.setParamsJson(params.canonicalJson());
        entity.setAlgorithmVersion(request.algorithmVersion());
        entity.setPromptVersion(blankToNull(request.promptVersion()));
        entity.setModelName(blankToNull(request.modelName()));
        entity.setLlmStatus(LlmStatus.NOT_REQUESTED.name());
        // ★ R108：请求窗口落 data_start_date/data_end_date——这两列是幂等比对里
        //   date_from/date_to 的唯一载体（表无独立列，本批禁新增迁移）。
        //   B19/B20 回写结果时不得覆盖已有值，否则重放判定会漂移。
        entity.setDataStartDate(request.dateFrom());
        entity.setDataEndDate(request.dateTo());
        entity.setTotalSkus(scope.plannedSkuCount());
        entity.setProcessedSkus(0);
        entity.setProgress(0);
        entity.setRequestId(RequestIdContext.get());
        entity.setIdempotencyKey(idempotencyKey);
        entity.setTriggeredBy(snapshot.userId());
        entity.setTimeoutAt(now.plusSeconds(TIMEOUT_SECONDS));
        entity.setCreatedBy(snapshot.userId());
        entity.setUpdatedBy(snapshot.userId());

        for (int attempt = 1; attempt <= MAX_RUN_NO_ATTEMPTS; attempt++) {
            // ★ 重置自增主键：上一次失败若已回填 id，重复 insert 会带主键 → 撞 PRIMARY
            entity.setId(null);
            entity.setRunNo(nextRunNo(dayPrefix));
            try {
                runMapper.insert(entity);
                // 读回数据库生成列（created_at / updated_at），保证响应与库内一字不差
                AnalysisRunEntity saved = runMapper.selectById(entity.getId());
                return saved == null ? entity : saved;
            } catch (DuplicateKeyException ex) {
                // 并发同键：另一请求已抢先落库 → 交给 createRun 走 c 的幂等判定（R108/A2）
                AnalysisRunEntity concurrent = findByIdempotencyKey(idempotencyKey);
                if (concurrent != null) {
                    throw new IdempotentRace(concurrent);
                }
                log.warn("run_no 撞唯一键，重取序列：attempt={}/{} runNo={}",
                        attempt, MAX_RUN_NO_ATTEMPTS, entity.getRunNo());
                if (attempt == MAX_RUN_NO_ATTEMPTS) {
                    throw new BizException(ErrorCodes.COM_0001, "运行编号生成冲突，请重试");
                }
            }
        }
        // 循环体在第 3 次失败时已抛出，此行仅为编译可达性
        throw new BizException(ErrorCodes.COM_0001, "运行编号生成冲突，请重试");
    }

    /** 团队内当日下一序号（{@code count + 1}；撞键由调用方重试）。 */
    private String nextRunNo(String dayPrefix) {
        Long count = runMapper.selectCount(Wrappers.<AnalysisRunEntity>lambdaQuery()
                .likeRight(AnalysisRunEntity::getRunNo, dayPrefix));
        long sequence = (count == null ? 0L : count) + 1L;
        if (sequence > 9999L) {
            throw new BizException(ErrorCodes.COM_0001, "当日分析任务数量已达上限，请明日再试");
        }
        return dayPrefix + String.format("%04d", sequence);
    }

    // =====================================================================
    // f/g. 状态推进 + 审计
    // =====================================================================

    /** {@code PENDING → RUNNING}（{@code §11.5} 202 示例：{@code progress=5}、{@code current_step=VALIDATING}）。 */
    private void markRunning(AnalysisRunEntity run, Boolean enableLlm) {
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);
        AnalysisRunEntity patch = new AnalysisRunEntity();
        patch.setId(run.getId());
        patch.setStatus(AnalysisRunStatus.RUNNING.name());
        patch.setStartedAt(startedAt);
        patch.setCurrentStep("VALIDATING");
        patch.setProgress(5);
        patch.setLlmStatus(Boolean.TRUE.equals(enableLlm)
                ? LlmStatus.PENDING.name() : LlmStatus.NOT_REQUESTED.name());
        runMapper.updateById(patch);

        run.setStatus(patch.getStatus());
        run.setStartedAt(startedAt);
        run.setCurrentStep(patch.getCurrentStep());
        run.setProgress(patch.getProgress());
        run.setLlmStatus(patch.getLlmStatus());
    }

    /** {@code PENDING → FAILED}（发起失败补偿；{@code error_message} 为对外脱敏文案）。 */
    private void markFailed(AnalysisRunEntity run, String errorCode, String errorMessage) {
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        AnalysisRunEntity patch = new AnalysisRunEntity();
        patch.setId(run.getId());
        patch.setStatus(AnalysisRunStatus.FAILED.name());
        patch.setErrorCode(errorCode);
        patch.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
        patch.setFinishedAt(finishedAt);
        runMapper.updateById(patch);

        run.setStatus(patch.getStatus());
        run.setErrorCode(errorCode);
        run.setErrorMessage(patch.getErrorMessage());
        run.setFinishedAt(finishedAt);
    }

    /** 审计 {@code ANALYSIS_RUN_CREATE}（{@code #15}，{@code biz_type=ANALYSIS_RUN}，{@code SUCCESS}）。 */
    private void auditCreate(TeamContext.Snapshot snapshot, AnalysisRunEntity run, AnalysisScope scope) {
        String summary = "创建分析任务：" + scope.scopeType().name()
                + "，计划 SKU " + run.getTotalSkus() + " 个";
        auditWriteService.record(AuditEvent.builder()
                .teamId(snapshot.teamId())
                .action(AuditAction.ANALYSIS_RUN_CREATE)
                .bizType(AUDIT_BIZ_TYPE)
                .bizId(run.getId())
                .bizNo(run.getRunNo())
                .operatorId(snapshot.userId())
                .operatorName(snapshot.username())
                .operatorRole(primaryRole(snapshot))
                .result(AuditResult.SUCCESS)
                .changeSummary(summary)
                .build());
    }

    /** 审计 {@code ANALYSIS_RUN_FINISH}（{@code #16}；发起失败记 {@code FAILURE}）。 */
    private void auditFinish(TeamContext.Snapshot snapshot, AnalysisRunEntity run,
                             AuditResult result, String summary, String errorCode) {
        auditWriteService.record(AuditEvent.builder()
                .teamId(snapshot.teamId())
                .action(AuditAction.ANALYSIS_RUN_FINISH)
                .bizType(AUDIT_BIZ_TYPE)
                .bizId(run.getId())
                .bizNo(run.getRunNo())
                .operatorId(snapshot.userId())
                .operatorName(snapshot.username())
                .operatorRole(primaryRole(snapshot))
                .result(result)
                .errorCode(errorCode)
                .changeSummary(summary)
                .build());
    }

    // =====================================================================
    // 内部：内部契约请求构造与工具
    // =====================================================================

    /** 领域模型 → 内部契约请求（{@code items} 按裁定②恒空数组）。 */
    private static AiRunCreateRequest toAiRequest(AnalysisRunEntity run,
                                                  AnalysisScope scope,
                                                  AnalysisParams params,
                                                  CreateAnalysisRunRequest request) {
        return new AiRunCreateRequest(
                run.getId(),
                run.getTeamId(),
                run.getWarehouseId(),
                AiScope.from(scope),
                request.dateFrom(),
                request.dateTo(),
                AiParams.from(params),
                run.getAlgorithmVersion(),
                run.getPromptVersion(),
                run.getModelName(),
                List.of());
    }

    private static String primaryRole(TeamContext.Snapshot snapshot) {
        return snapshot.roles().isEmpty() ? null : snapshot.roles().get(0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
