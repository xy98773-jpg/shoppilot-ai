package com.shoppilot.analysis.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shoppilot.analysis.domain.AnalysisRunStatus;
import com.shoppilot.analysis.domain.LlmStatus;
import com.shoppilot.analysis.infrastructure.ai.AiServiceClient;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunResultData;
import com.shoppilot.analysis.infrastructure.ai.dto.AiRunStatusData;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisRunMapper;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 分析运行状态同步服务（M4-T05：轮询 + {@code timeout_at} 兜底）。
 *
 * <h2>★ 为什么本类只管「状态」，不管「结果」</h2>
 * <p>{@code execution_status=COMPLETED} 这一支<b>不能</b>在本类直接置终态：它必须
 * 「拉结果 → 18 项二次校验 → 落 {@code analysis_items} → 置 {@code SUCCEEDED/DEGRADED}」
 * 作为一个<b>原子步骤</b>完成（{@code §5.38} 一节 / {@code R129}）。若这里先置个中间态，
 * 就会出现「已完成但没落库、也没终态」的假 {@code RUNNING} 窗口，直到超时扫描把它误判成
 * {@code TIMEOUT}——用户看到的是「明明算完了，却被判超时」。
 * 故 {@code COMPLETED} 分支<b>委托</b> {@link AnalysisResultWriteService}。
 *
 * <h2>★ {@code execution_status → AnalysisRunStatus} 映射（唯一真源，裁定 #6）</h2>
 * <table border="1">
 *   <caption>映射表</caption>
 *   <tr><th>Python execution_status</th><th>Java 处置</th></tr>
 *   <tr><td>{@code ACCEPTED}</td><td>保持当前状态，仅同步 {@code progress}/{@code current_step}/{@code processed_skus}</td></tr>
 *   <tr><td>{@code RUNNING}</td><td>{@code PENDING→RUNNING}（补 {@code started_at}）；同步进度四列 + {@code llm_status}</td></tr>
 *   <tr><td>{@code COMPLETED}</td><td>走 {@link AnalysisResultWriteService}（<b>不在本类置终态</b>）</td></tr>
 *   <tr><td>{@code FAILED}</td><td>{@code FAILED}，{@code error_code} 经 AiErrorMapper 映射，<b>不落库</b></td></tr>
 *   <tr><td>{@code CANCELLED}</td><td>{@code CANCELLED}，<b>不落库</b></td></tr>
 * </table>
 *
 * <h2>★ {@code progress} 不自算（裁定 #7 / {@code R119}）</h2>
 * <p>直接取 Python 的档位值（{@code VALIDATING=5 / STATS=20 / RISK_RULES=40 /
 * REPLENISHMENT=60 / LLM_EXPLAIN=80 / DONE=100}）。Java 若自己按
 * {@code processed/total} 插值，就会在「Python 正在某一步内推进」时给出与对端不一致的数字，
 * 且两个来源的语义会随步内实现细节漂移。
 *
 * <h2>★ 并发安全：状态守卫代替乐观锁（裁定 #9）</h2>
 * <p>{@code analysis_runs} <b>无 {@code version}</b>（{@code DATABASE_DESIGN §7.4} 表 A 明确用状态守卫）。
 * 所有终态推进走 {@code UPDATE ... WHERE id=? AND status=<原状态>}（CAS）：影响行数 0
 * 说明已被另一条路径（读时同步 / 定时扫描）推进，<b>直接跳过</b>，不覆盖。
 * 多实例重复扫描由该 CAS 吸收（{@code R136}：无分布式锁，ADR-001 禁 Redis，首期单实例部署，
 * <b>多实例前必须重新评估</b>）。
 *
 * <p>无状态、可并发调用；不持有 {@code TeamContext}（HTTP 路径由调用方提供，定时路径用
 * {@code run.getTeamId()}，{@code G8}：禁止哨兵 {@code team_id=0}）。
 */
@Service
public class AnalysisRunSyncService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunSyncService.class);

    /** 审计业务类型（{@code DATABASE_DESIGN §6-J.1}）。 */
    private static final String AUDIT_BIZ_TYPE = "ANALYSIS_RUN";

    /** 失败原因列宽（{@code analysis_runs.error_message VARCHAR(500)}）。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 超时对外文案（{@code §13.4}：整体分析执行 300s）。 */
    private static final String TIMEOUT_MESSAGE = "分析任务执行超时（300s）";

    /** Python 的执行状态取值（{@code AI_SERVICE_CONTRACT §3.4} 的表；<b>非</b> Java 的 AnalysisRunStatus）。 */
    private static final String PY_ACCEPTED = "ACCEPTED";
    private static final String PY_RUNNING = "RUNNING";
    private static final String PY_COMPLETED = "COMPLETED";
    private static final String PY_FAILED = "FAILED";
    private static final String PY_CANCELLED = "CANCELLED";

    private final AnalysisRunMapper runMapper;
    private final AiServiceClient aiServiceClient;
    private final AnalysisResultWriteService resultWriteService;
    private final AuditWriteService auditWriteService;

    public AnalysisRunSyncService(AnalysisRunMapper runMapper,
                                  AiServiceClient aiServiceClient,
                                  AnalysisResultWriteService resultWriteService,
                                  AuditWriteService auditWriteService) {
        this.runMapper = runMapper;
        this.aiServiceClient = aiServiceClient;
        this.resultWriteService = resultWriteService;
        this.auditWriteService = auditWriteService;
    }

    // =====================================================================
    // 对外入口
    // =====================================================================

    /**
     * 从 Python 同步一次状态（{@code §5.38} 裁定 #2 的<b>双通道</b>之一）。
     *
     * <p>调用方：① 定时扫描（{@code AnalysisRunPollTask}）；② {@code GET /api/v1/analysis/runs/{id}}
     * 的读时同步。
     *
     * <p><b>不抛异常</b>（读时同步口径 {@code R134}）：Python 超时 / 5xx / 未配置时只记 WARN，
     * 由调用方返回库内现状。批量扫描因此也不会被单条失败打断。
     *
     * @param run 库内 run 快照（调用方已确认其处于 {@code PENDING/RUNNING}）
     * @return 同步是否成功（{@code false} ⇒ 库内状态未被改变）
     */
    public boolean syncFromPython(AnalysisRunEntity run) {
        if (run == null || run.getId() == null) {
            return false;
        }
        AnalysisRunStatus current = parseStatus(run.getStatus());
        if (current == null || current.isTerminal()) {
            // 终态不再向 Python 追问：Java 是 AnalysisRunStatus 权威（DESIGN_BASIS §13.2）
            return false;
        }
        AiRunStatusData status;
        try {
            status = aiServiceClient.fetchStatus(run.getId());
        } catch (BizException ex) {
            log.warn("同步分析任务状态失败（静默降级，库内状态不变）：runId={} code={}",
                    run.getId(), ex.getErrorCode());
            return false;
        }
        try {
            applyRemoteStatus(run, current, status);
            return true;
        } catch (BizException ex) {
            // 契约违例（如未知 execution_status）：同样按「静默降级」处理，不让读接口 500
            log.warn("Python 返回的分析状态无法处理（静默降级）：runId={} code={}",
                    run.getId(), ex.getErrorCode());
            return false;
        }
    }

    /**
     * 超时兜底：把已过 {@code timeout_at} 的进行中 run 置 {@code TIMEOUT}（裁定 #5）。
     *
     * <p>处置：{@code status='TIMEOUT'}、{@code error_code='ANL-7003'}、{@code finished_at=now}、
     * 写 {@code ANALYSIS_RUN_FINISH}（{@code FAILURE}），并<b>尽力</b>调一次 Python cancel
     * （失败只 WARN、不回滚本地状态——本地 {@code TIMEOUT} 才是权威）。
     *
     * <p>★ 用状态守卫 CAS：并发时只有一条能成功推进。
     *
     * @return 是否成功推进（{@code false} ⇒ 已被别人推进 / 非进行态）
     */
    public boolean markTimeout(AnalysisRunEntity run) {
        if (run == null || run.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = runMapper.update(null, Wrappers.<AnalysisRunEntity>lambdaUpdate()
                .eq(AnalysisRunEntity::getId, run.getId())
                // ★ CAS 守卫：只从「进行中」推进，且不带 team_id 条件（本方法可能被定时任务跨团队调用，
                //   租户线在 @InterceptorIgnore 之外由调用方保证——本方法用的是普通 update，
                //   租户线会追加当前上下文 team_id；定时任务须在 TeamContext 中运行）。
                .in(AnalysisRunEntity::getStatus,
                        AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name())
                .set(AnalysisRunEntity::getStatus, AnalysisRunStatus.TIMEOUT.name())
                .set(AnalysisRunEntity::getErrorCode, ErrorCodes.ANL_7003)
                .set(AnalysisRunEntity::getErrorMessage, TIMEOUT_MESSAGE)
                .set(AnalysisRunEntity::getFinishedAt, now)
                .set(AnalysisRunEntity::getUpdatedAt, now));
        if (updated == 0) {
            return false;
        }
        auditFinish(run, AuditResult.FAILURE, TIMEOUT_MESSAGE, ErrorCodes.ANL_7003);
        // 尽力取消：远端任务若还在跑会白占额度；失败只 WARN（本地终态已是事实）
        if (!aiServiceClient.cancelBestEffort(run.getId())) {
            log.warn("超时兜底已置 TIMEOUT，但远端取消未成功：runId={}（不影响本地终态）", run.getId());
        }
        log.info("分析任务超时兜底：runId={} teamId={} timeoutAt={}", run.getId(), run.getTeamId(), run.getTimeoutAt());
        return true;
    }

    // =====================================================================
    // 状态映射（唯一真源）
    // =====================================================================

    /** 按映射表处理 Python 的执行状态。 */
    private void applyRemoteStatus(AnalysisRunEntity run, AnalysisRunStatus current, AiRunStatusData status) {
        String remote = status.executionStatus() == null
                ? null : status.executionStatus().trim().toUpperCase(Locale.ROOT);
        if (remote == null) {
            throw new BizException(ErrorCodes.ANL_7004, "分析结果校验未通过，请稍后重试");
        }
        switch (remote) {
            case PY_ACCEPTED -> syncProgressOnly(run, current, status);
            case PY_RUNNING -> advanceToRunning(run, current, status);
            case PY_COMPLETED -> writeCompleted(run);
            case PY_FAILED -> advanceToFailed(run, status);
            case PY_CANCELLED -> advanceToCancelled(run);
            default -> throw new BizException(ErrorCodes.ANL_7004, "分析结果校验未通过，请稍后重试");
        }
    }

    /**
     * {@code COMPLETED}：取结果 → 落库 → 置终态（{@code §5.38} 一节：三者必须原子完成）。
     *
     * <p>★ <b>跨 Bean 调用</b> {@link AnalysisResultWriteService#writeResult}：
     * 在同一个类里直接调 {@code writeResult} 会绕过 Spring 的事务代理（自调用），
     * 事务根本不开启，{@code recordInBusinessTx}（MANDATORY）随即抛
     * {@code IllegalTransactionStateException}。这是本方法存在的唯一理由。
     */
    private void writeCompleted(AnalysisRunEntity run) {
        AiRunResultData result = resultWriteService.fetchResultOrNull(run);
        if (result == null) {
            return;
        }
        resultWriteService.writeResult(run, result);
    }

    /**
     * {@code ACCEPTED}：Python 已受理但还没开始跑 ⇒ <b>只同步进度</b>，状态不动
     * （若本地已是 {@code RUNNING}，保持 {@code RUNNING}；{@code DESIGN_BASIS §9.7} 不允许回退）。
     */
    private void syncProgressOnly(AnalysisRunEntity run, AnalysisRunStatus current, AiRunStatusData status) {
        if (current == AnalysisRunStatus.PENDING) {
            return;
        }
        updateProgress(run, status, status.progress(), status.currentStep(),
                status.processedSkus(), null, null);
    }

    /** {@code RUNNING}：{@code PENDING→RUNNING} 并补 {@code started_at}；已 RUNNING 则只同步。 */
    private void advanceToRunning(AnalysisRunEntity run, AnalysisRunStatus current, AiRunStatusData status) {
        boolean pendingToRunning = current == AnalysisRunStatus.PENDING;
        LocalDateTime startedAt = parseTimestamp(status.startedAt());
        if (pendingToRunning && startedAt == null) {
            startedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
        LlmStatus llm = parseLlmStatus(status.llmStatus());
        updateProgress(run, status, status.progress(), status.currentStep(),
                status.processedSkus(), llm == null ? null : llm.name(),
                pendingToRunning ? startedAt : null);
    }

    /** {@code FAILED}：置 {@code FAILED} + 映射后的 {@code error_code}，<b>不落库</b>。 */
    private void advanceToFailed(AnalysisRunEntity run, AiRunStatusData status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String externalCode = mapRemoteErrorCode(status);
        String message = userMessage(externalCode);
        int updated = runMapper.update(null, Wrappers.<AnalysisRunEntity>lambdaUpdate()
                .eq(AnalysisRunEntity::getId, run.getId())
                .in(AnalysisRunEntity::getStatus,
                        AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name())
                .set(AnalysisRunEntity::getStatus, AnalysisRunStatus.FAILED.name())
                .set(AnalysisRunEntity::getErrorCode, externalCode)
                .set(AnalysisRunEntity::getErrorMessage, message)
                .set(AnalysisRunEntity::getFinishedAt, now)
                .set(AnalysisRunEntity::getUpdatedAt, now));
        if (updated == 0) {
            return;
        }
        auditFinish(run, AuditResult.FAILURE, "分析任务执行失败：" + externalCode, externalCode);
        log.info("分析任务失败（Python FAILED）：runId={} externalCode={} remoteCode={}",
                run.getId(), externalCode, status.error() == null ? null : status.error().code());
    }

    /** {@code CANCELLED}：置 {@code CANCELLED}，<b>不落库</b>。 */
    private void advanceToCancelled(AnalysisRunEntity run) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = runMapper.update(null, Wrappers.<AnalysisRunEntity>lambdaUpdate()
                .eq(AnalysisRunEntity::getId, run.getId())
                .in(AnalysisRunEntity::getStatus,
                        AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name())
                .set(AnalysisRunEntity::getStatus, AnalysisRunStatus.CANCELLED.name())
                .set(AnalysisRunEntity::getFinishedAt, now)
                .set(AnalysisRunEntity::getUpdatedAt, now));
        if (updated == 0) {
            return;
        }
        auditFinish(run, AuditResult.SUCCESS, "分析任务已取消", null);
        log.info("分析任务已取消（Python CANCELLED）：runId={}", run.getId());
    }

    /** 进度四列同步（{@code progress} / {@code current_step} / {@code processed_skus} / 可选 {@code llm_status}）。 */
    private void updateProgress(AnalysisRunEntity run,
                                AiRunStatusData status,
                                Integer progress,
                                String currentStep,
                                Integer processedSkus,
                                String llmStatus,
                                LocalDateTime startedAt) {
        LambdaUpdateWrapper<AnalysisRunEntity> update = Wrappers.<AnalysisRunEntity>lambdaUpdate()
                .eq(AnalysisRunEntity::getId, run.getId())
                .in(AnalysisRunEntity::getStatus,
                        AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name())
                .set(AnalysisRunEntity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC));
        if (run.getStatus() != null && AnalysisRunStatus.PENDING.name().equals(run.getStatus())) {
            // ★ PENDING→RUNNING 必须显式推进状态：否则库里会一直停在 PENDING，
            //   读接口与超时扫描看到的都是「还没开始」，而 Python 其实已在跑。
            update.set(AnalysisRunEntity::getStatus, AnalysisRunStatus.RUNNING.name());
        }
        if (progress != null) {
            update.set(AnalysisRunEntity::getProgress, progress);
        }
        if (currentStep != null && !currentStep.isBlank()) {
            update.set(AnalysisRunEntity::getCurrentStep, currentStep);
        }
        if (processedSkus != null) {
            update.set(AnalysisRunEntity::getProcessedSkus, processedSkus);
        }
        if (status.totalSkus() != null && status.totalSkus() > 0) {
            update.set(AnalysisRunEntity::getTotalSkus, status.totalSkus());
        }
        if (llmStatus != null) {
            update.set(AnalysisRunEntity::getLlmStatus, llmStatus);
        }
        if (startedAt != null) {
            update.set(AnalysisRunEntity::getStartedAt, startedAt);
        }
        runMapper.update(null, update);
    }

    // =====================================================================
    // 内部：解析与映射
    // =====================================================================

    /**
     * Python 的 {@code error.code} → 对外码（{@link com.shoppilot.analysis.infrastructure.ai.AiErrorMapper}）。
     *
     * <p>★ 原 AI 码<b>只进日志</b>，绝不进 {@code error_code} 列或响应体
     * （{@code AI_SERVICE_CONTRACT §12} 不变量：{@code AI-xxxx} 一律不得外泄）。
     */
    private String mapRemoteErrorCode(AiRunStatusData status) {
        String aiCode = status.error() == null ? null : status.error().code();
        String external = com.shoppilot.analysis.infrastructure.ai.AiErrorMapper.toExternalCode(aiCode);
        log.warn("Python 返回失败：runId={} aiCode={} externalCode={}",
                status.analysisRunId(), aiCode, external);
        return external;
    }

    /** 对外中文提示（与 {@code AiServiceClient} 同口径，逐字取 {@code ERROR_CODES §2} 的用户可见提示）。 */
    private static String userMessage(String code) {
        return switch (code) {
            case ErrorCodes.ANL_7002 -> "AI 分析服务暂时不可用，请稍后重试";
            case ErrorCodes.ANL_7003 -> "AI 分析服务响应超时，请稍后重试";
            case ErrorCodes.ANL_7004 -> "分析结果校验未通过，请稍后重试";
            case ErrorCodes.ANL_7005 -> "分析任务不存在";
            case ErrorCodes.ANL_7012 -> "分析结果尚未生成，请等待任务完成";
            default -> "分析任务执行失败，请稍后重试";
        };
    }

    private static AnalysisRunStatus parseStatus(String value) {
        if (value == null) {
            return null;
        }
        for (AnalysisRunStatus status : AnalysisRunStatus.values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    private static LlmStatus parseLlmStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (LlmStatus status : LlmStatus.values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** ISO-8601 → {@link LocalDateTime}（UTC）；解析失败返回 {@code null}（不猜）。 */
    private static LocalDateTime parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(iso.length() > 6 && iso.charAt(iso.length() - 1) == 'Z'
                    ? iso.substring(0, iso.length() - 1) : iso);
        } catch (RuntimeException ex) {
            log.warn("Python 返回的时间戳无法解析，按空处理：value={}", iso);
            return null;
        }
    }

    /**
     * 审计 {@code ANALYSIS_RUN_FINISH}（{@code #16}）。
     *
     * <p>★ 定时任务路径<b>没有</b> {@code TeamContext}：{@code teamId} 取
     * {@code run.getTeamId()}、{@code operatorId} 取 {@code run.getTriggeredBy()}，
     * {@code operatorName}/{@code operatorRole} 留 {@code null}（{@code AuditEvent} 允许「系统任务场景」，
     * 裁定 #21）。<b>禁止</b>用哨兵 {@code team_id=0}、也禁止伪造上下文。
     *
     * <p>走 {@link AuditWriteService#record}（独立事务、旁路）：本类不持有业务事务
     * （各步 UPDATE 均自动提交），用 {@code recordInBusinessTx} 会因「无事务」直接抛
     * {@code MANDATORY} 异常。
     */
    private void auditFinish(AnalysisRunEntity run, AuditResult result, String summary, String errorCode) {
        auditWriteService.record(AuditEvent.builder()
                .teamId(run.getTeamId())
                .action(AuditAction.ANALYSIS_RUN_FINISH)
                .bizType(AUDIT_BIZ_TYPE)
                .bizId(run.getId())
                .bizNo(run.getRunNo())
                .operatorId(run.getTriggeredBy())
                .operatorName(null)
                .operatorRole(null)
                .result(result)
                .errorCode(errorCode)
                .changeSummary(summary)
                .build());
    }

    /** 当前扫描批次中处于进行态的行数（供日志与测试断言）。 */
    static List<String> inFlightStatuses() {
        return List.of(AnalysisRunStatus.PENDING.name(), AnalysisRunStatus.RUNNING.name());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}