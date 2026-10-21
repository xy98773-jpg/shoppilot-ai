package com.shoppilot.analysis.infrastructure.scheduling;

import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import com.shoppilot.analysis.infrastructure.persistence.mapper.AnalysisRunMapper;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.web.RequestIdContext;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 分析运行轮询任务（M4-T05 的<b>定时扫描通道</b>；{@code §5.38} 裁定 #2 的双通道之一）。
 *
 * <h2>★ 默认关闭是硬要求（裁定 #8）</h2>
 * <p>{@code @ConditionalOnProperty(matchIfMissing = false)}：本仓 34+ 个 {@code @SpringBootTest}
 * 都会加载 main 的 {@code application.yml}。若默认开启，它们会在后台<b>真打 Python</b>，
 * 表现为随机超时、端口占用与「测试红但本地复现不了」。真栈复核由项目负责人用环境变量
 * {@code ANALYSIS_POLL_ENABLED=true} 打开。
 *
 * <h2>★ 每轮顺序：先超时、再同步（裁定 #4）</h2>
 * <p>超时行优先处理，避免给已死任务再发一次无谓的状态请求；SQL 的
 * {@code ORDER BY (timeout_at <= ?) DESC} 已把超时行排在前面，本方法只负责分派。
 *
 * <h2>★ 逐条独立：单条失败只 WARN（裁定 #4）</h2>
 * <p>一条 run 的 Python 超时不该让同轮其它 run 失去被同步的机会，故每行独立
 * {@code try/catch}。
 *
 * <h2>★ 租户线与上下文（{@code G8} / {@code R133}）</h2>
 * <p>扫描本身跨团队（{@link AnalysisRunMapper#selectInFlightAcrossTeams} 是全仓第 14 处
 * 受控例外），但<b>拿到行之后</b>会用 {@link #withTeamContext} 为每条 run 建立
 * {@link TeamContext}（{@code team_id = 该 run 的 team_id}，来自库内而非客户端），
 * 使后续所有 {@code UPDATE} 都带上正确的租户条件。
 * <b>禁止</b>用哨兵 {@code team_id=0}（铁律：上下文缺失时拒绝查询，不退化）。
 *
 * <h2>★ 无分布式锁（{@code R136}）</h2>
 * <p>ADR-001 禁 Redis ⇒ 首期单实例部署，不做分布式锁。多实例重复扫描由
 * 「状态守卫 CAS + 落库事务入口 {@code SELECT ... FOR UPDATE}」吸收。
 * <b>多实例部署前必须重新评估</b>（重复拉结果、白跑计算）。
 *
 * <p>无状态；不持有跨轮次上下文。
 */
@Component
@ConditionalOnProperty(name = "shoppilot.analysis.poll.enabled", matchIfMissing = false)
public class AnalysisRunPollTask {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunPollTask.class);

    /** 定时任务无用户：{@code operatorId} 取 {@code run.triggered_by}，角色/姓名留空。 */
    private static final List<String> SYSTEM_ROLES = List.of("SYSTEM");

    private final AnalysisRunMapper runMapper;
    private final com.shoppilot.analysis.application.AnalysisRunSyncService syncService;

    private final int scanLimit;

    public AnalysisRunPollTask(AnalysisRunMapper runMapper,
                               com.shoppilot.analysis.application.AnalysisRunSyncService syncService,
                               @Value("${shoppilot.analysis.poll.scan-limit:50}") int scanLimit) {
        this.runMapper = runMapper;
        this.syncService = syncService;
        this.scanLimit = scanLimit <= 0 ? 50 : scanLimit;
    }

    /**
     * 一轮扫描：超时行优先，其余按 {@code timeout_at} 升序。
     *
     * <p>调度间隔由 {@code shoppilot.analysis.poll.fixed-delay-ms} 给出（默认 5000ms），
     * 首次延迟 {@code initial-delay-ms}（默认 10000ms，给 Python 容器留启动时间）。
     */
    @Scheduled(fixedDelayString = "${shoppilot.analysis.poll.fixed-delay-ms:5000}",
            initialDelayString = "${shoppilot.analysis.poll.initial-delay-ms:10000}")
    public void pollOnce() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<AnalysisRunMapper.ScanRow> rows;
        try {
            rows = runMapper.selectInFlightAcrossTeams(scanLimit, now);
        } catch (RuntimeException ex) {
            log.error("分析任务扫描失败（不影响下一轮）：err={}", ex.toString());
            return;
        }
        if (rows.isEmpty()) {
            return;
        }
        int timeouts = 0;
        int syncs = 0;
        for (AnalysisRunMapper.ScanRow row : rows) {
            boolean expired = row.timeoutAt() != null && !row.timeoutAt().isAfter(now);
            // ★ 恢复上一轮/调用方可能残留的上下文：定时线程复用，必须逐条重建而不是继承
            TeamContext.Snapshot previous = TeamContext.get();
            String previousRequestId = MDC.get(RequestIdContext.MDC_KEY);
            try {
                applyTeamContext(row);
                applyRequestId(row);
                AnalysisRunEntity run = reload(row.id());
                if (run == null) {
                    continue;
                }
                if (expired) {
                    // 超时优先：本地终态已是事实，远端取消只是资源回收的善意尝试
                    if (syncService.markTimeout(run)) {
                        timeouts++;
                    }
                } else if (syncService.syncFromPython(run)) {
                    syncs++;
                }
            } catch (RuntimeException ex) {
                // 单条失败只 WARN：本轮其它行照常处理（裁定 #4）
                log.warn("分析任务处理失败（不影响同轮其它行）：runId={} err={}", row.id(), ex.toString());
            } finally {
                restore(previous);
                restoreRequestId(previousRequestId);
            }
        }
        if (timeouts > 0 || syncs > 0) {
            log.info("分析任务扫描完成：scanned={} timeout={} synced={}", rows.size(), timeouts, syncs);
        }
    }

    /**
     * 为本条 run 建立租户上下文（{@code team_id} 来自库内行，非客户端）。
     *
     * <p>★ <b>不用哨兵 0</b>：租户线在上下文缺失时直接拒绝查询（{@code AUTH-1001}），
     * 退化会静默产出「跨团队可写」的假象。
     */
    private void applyTeamContext(AnalysisRunMapper.ScanRow row) {
        if (row.teamId() == null) {
            throw new IllegalStateException("扫描行的 team_id 为空，拒绝建立上下文：runId=" + row.id());
        }
        TeamContext.set(TeamContext.Snapshot.of(row.teamId(), SYSTEM_ROLES, null, null));
    }

    /** 恢复原上下文：定时线程可能被复用，残留上下文会让下一条 run 带着别人的 team_id。 */
    private void restore(TeamContext.Snapshot previous) {
        if (previous == null) {
            TeamContext.clear();
        } else {
            TeamContext.set(previous);
        }
    }

    /**
     * 把「触发这次运行的那个请求」的 {@code request_id} 放回 MDC（{@code R191}）。
     *
     * <p>异步线程与 HTTP 线程不是同一条，SLF4J MDC 基于 ThreadLocal 不会跨线程传递；
     * 不显式重建的话，轮询线程打的每一行日志都不带 {@code request_id}，
     * 用户在报障时给出 requestId 也无法在 Java 侧检索到对应处理过程
     * （NFR-OBS-01「全链路串联」只剩 nginx / Python / 审计三段，Java 段断链）。
     *
     * <p>取值来自库内 {@code analysis_runs.request_id}（{@code API_CONTRACT §7} 落库值），
     * 缺失时清空而不是留空串，避免日志出现 {@code request_id=} 的空壳占位。
     */
    private void applyRequestId(AnalysisRunMapper.ScanRow row) {
        String requestId = row.requestId();
        if (requestId == null || requestId.isBlank()) {
            MDC.remove(RequestIdContext.MDC_KEY);
        } else {
            MDC.put(RequestIdContext.MDC_KEY, requestId);
        }
    }

    /** 复位 MDC：同一条定时线程上的下一条 run 不能继承上一条的 {@code request_id}。 */
    private void restoreRequestId(String previous) {
        if (previous == null) {
            MDC.remove(RequestIdContext.MDC_KEY);
        } else {
            MDC.put(RequestIdContext.MDC_KEY, previous);
        }
    }

    /** 在该 run 的租户上下文内重新读回完整行（扫描投影只带四列）。 */
    private AnalysisRunEntity reload(Long runId) {
        return runMapper.selectById(runId);
    }
}