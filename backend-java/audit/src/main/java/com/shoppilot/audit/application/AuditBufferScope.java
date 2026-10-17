package com.shoppilot.audit.application;

import com.shoppilot.audit.domain.model.AuditEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 审计<b>延迟写入作用域</b>（{@code ADR-004 D2}，{@code B4-PERF} 轮 1 落地）。
 *
 * <h2>解决什么</h2>
 * 现状 {@code AuditWriteService.record} → {@code AuditLogWriter.insert}（{@code REQUIRES_NEW}）
 * <b>每条审计一次独立提交</b>：1 万行导入 = 1 万次提交（实测约 85 s 边际成本，见
 * {@code DESIGN_REVIEW §5.1}）。本作用域把作用域内产生的审计<b>先入线程级缓冲</b>，
 * 等业务事务<b>提交成功后</b>一次性 {@link AuditWriteService#recordAll}（{@code ADR-004 D1}，
 * 一个 {@code REQUIRES_NEW} 事务一次提交）。
 *
 * <h2>三个语义要点（都是红线）</h2>
 * <ol>
 *   <li><b>审计条数不减少</b>：缓冲不合并、不采样、不汇总——{@code n} 条事件写出 {@code n} 行
 *       （{@code ADR-004 D3}）。</li>
 *   <li><b>不回滚已提交的事实</b>：{@code afterCommit} 才写，{@code STATUS_ROLLED_BACK} 时
 *       {@link #clearBuffer() 丢弃}——从根上消除 <b>{@code R45} 孤儿审计</b>
 *       （旧实现：业务批未提交就先 {@code REQUIRES_NEW} 提交了 {@code SUCCESS} 审计）。</li>
 *   <li><b>不静默丢审计</b>：{@link #close()} 时缓冲非空（事务既未提交也未回滚的编程错误）→ 记
 *       {@code ERROR} 并<b>立即补写</b>兜底。</li>
 * </ol>
 *
 * <h2>★ 与任务书字面的一处偏差（已申报）：同步回调是「首个入缓冲时」注册</h2>
 * 任务书写「打开时若 {@code isSynchronizationActive()} 则注册 {@code TransactionSynchronization}」，
 * 同时又要求「每个<b>批次事务</b>各开一次作用域（try-with-resources）」——两者在 Spring 语义下
 * 无法同时成立：作用域若包住事务（try-with-resources 写在 {@code TransactionTemplate} 外层），
 * <b>打开瞬间事务尚未开始</b>，{@code isSynchronizationActive()} 必为 {@code false}，注册不会发生
 * ⇒ 缓冲永不落库；作用域若开在事务回调<b>内部</b>，则 {@code close()} 发生在提交之前，缓冲必然非空
 * ⇒ 反被兜底分支「立即补写」，等于把审计又写回业务提交之前（孤儿审计复现）。
 *
 * <p>故实现为：<b>打开时先尝试注册；若此刻无事务则推迟到「首次入缓冲」时注册</b>（那一刻必在事务内，
 * {@code isSynchronizationActive()} 为真）。回调触发时机、缓冲语义、{@code close()} 兜底行为
 * 与任务书完全一致，只是注册时点后移。{@code TransactionSynchronization} 的同步是按<b>物理事务</b>
 * 登记的，{@code NESTED} 子事务（{@code SAVEPOINT}）不会产生独立同步 ⇒ 回调仍落在批次事务的提交/
 * 回滚上，符合「每批一次」。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * try (AuditBufferScope ignored = new AuditBufferScope(auditWriteService)) {
 *     txTemplate.executeWithoutResult(tx -> { ...业务写 + 审计... });
 * }   // 关闭时缓冲应为空：提交成功已在 afterCommit 中批量写出，回滚则已丢弃
 * </pre>
 *
 * 作用域<b>不是线程安全的共享对象</b>：一个实例只属于开启它的那个线程（{@link ThreadLocal} 绑定）；
 * {@code close()} 必然解绑（{@code try/finally} 语义由 {@link AutoCloseable} 保证），
 * 防止 Tomcat 线程复用造成跨请求污染。
 */
public final class AuditBufferScope implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditBufferScope.class);

    /**
     * 当前线程生效的作用域（{@code null} = 未开启延迟写入 ⇒ {@code record} 保持立即写）。
     *
     * <p>用 {@code remove()} 而非 {@code set(null)} 解绑：后者在线程池里会留下 {@code null} 值条目。
     */
    private static final ThreadLocal<AuditBufferScope> CURRENT = new ThreadLocal<>();

    private final AuditWriteService writeService;

    /** 本作用域已收集但未写出的审计事件（顺序＝事实发生顺序）。 */
    private final List<AuditEvent> buffer = new ArrayList<>();

    /** 外层作用域（正常为 {@code null}；嵌套开启时保存，关闭时恢复）。 */
    private final AuditBufferScope previous;

    private boolean synchronizationRegistered;
    private boolean closed;

    /**
     * 开启作用域（立即绑定当前线程）。
     *
     * @param writeService 批量写入口（{@code AuditWriteService.recordAll}）——由业务方注入，避免本类变成 Bean
     */
    public AuditBufferScope(AuditWriteService writeService) {
        if (writeService == null) {
            throw new IllegalArgumentException("AuditBufferScope 需要 AuditWriteService");
        }
        this.writeService = writeService;
        this.previous = CURRENT.get();
        CURRENT.set(this);
        // 若调用方恰好已在事务内开启作用域，此刻就能注册（见类级 javadoc 的偏差说明）
        registerSynchronizationIfPossible();
    }

    /**
     * 尝试把一个审计事件放入当前线程的缓冲（{@link AuditWriteService#record} 的唯一改动点）。
     *
     * @return {@code true} 表示已入缓冲且<b>不要</b>立即写库；{@code false} 表示无作用域 /
     *         当前无实际事务 ⇒ 调用方按原逻辑立即写（REST / AOP 路径逐字节不变）
     */
    static boolean enqueue(AuditEvent event) {
        AuditBufferScope scope = CURRENT.get();
        if (scope == null || scope.closed) {
            return false;
        }
        // 无实际事务时立即写：迟延写入的唯一目的是「等事务提交」，没有事务就没有等待对象
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return false;
        }
        scope.buffer.add(event);
        scope.registerSynchronizationIfPossible();
        return true;
    }

    /** 当前线程是否处于延迟写入作用域内（观测/测试用）。 */
    static boolean isActive() {
        AuditBufferScope scope = CURRENT.get();
        return scope != null && !scope.closed;
    }

    /**
     * 注册事务同步回调（幂等；无同步活动时静默跳过，等下一次入缓冲再试）。
     *
     * <ul>
     *   <li>{@code afterCommit} → {@link #flush()}：业务事务已提交，把本批审计一次性写出；</li>
     *   <li>{@code afterCompletion(STATUS_ROLLED_BACK)} → {@link #clearBuffer()}：业务回滚 ⇒
     *       丢弃缓冲（审计随事实一起消失，不留孤儿）。</li>
     * </ul>
     */
    private void registerSynchronizationIfPossible() {
        if (synchronizationRegistered || closed) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                flush();
            }

            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    clearBuffer();
                }
            }
        });
        synchronizationRegistered = true;
    }

    /** 把缓冲一次性写出（{@code recordAll} 自身永不抛异常；失败只记 ERROR）。 */
    private void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<AuditEvent> batch = drain();
        writeService.recordAll(batch);
    }

    private void clearBuffer() {
        if (!buffer.isEmpty()) {
            log.debug("审计缓冲随业务事务回滚丢弃：{} 条（action 分布={}）", buffer.size(), actionDistribution(buffer));
            buffer.clear();
        }
    }

    private List<AuditEvent> drain() {
        List<AuditEvent> copy = new ArrayList<>(buffer);
        buffer.clear();
        return copy;
    }

    /**
     * 关闭作用域：<b>必然</b>解绑当前线程，并处理「事务既未提交也未回滚」的残留缓冲。
     *
     * <p>正常路径下缓冲已被 {@code afterCommit} / {@code afterCompletion} 清空 ⇒ 此处无动作。
     * 非空即编程错误（作用域被开在没有事务的路径上，或回调未触发），按 {@code ADR-004 D2}
     * <b>记 {@code ERROR} 并立即补写</b>，不得静默丢弃。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 解绑：先恢复外层（支持嵌套），否则彻底移除，避免线程复用污染
        if (CURRENT.get() == this) {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
        if (buffer.isEmpty()) {
            return;
        }
        List<AuditEvent> leftover = drain();
        log.error("审计缓冲作用域关闭时仍有 {} 条未写审计（事务既未提交也未回滚，疑似编程错误），"
                + "已立即补写兜底：action 分布={}", leftover.size(), actionDistribution(leftover));
        writeService.recordAll(leftover);
    }

    /** {@code action → 条数}（{@code TreeMap} 保证日志稳定可 diff）。 */
    static Map<String, Integer> actionDistribution(List<AuditEvent> events) {
        Map<String, Integer> dist = new TreeMap<>();
        for (AuditEvent event : events) {
            String key = event == null || event.getAction() == null ? "<null>" : event.getAction().name();
            dist.merge(key, 1, Integer::sum);
        }
        return dist;
    }
}
