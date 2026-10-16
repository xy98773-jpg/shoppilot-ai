package com.shoppilot.audit.aspect;

import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.annotation.Audited;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.RequestIdContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.CodeSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计埋点切面（M2-T11）：拦截带 {@link Audited} 的方法，把执行结果写入 {@code audit_logs}。
 *
 * <h2>行为（{@code MODULE_BOUNDARIES §2.10} / 任务书 §2-6）</h2>
 * <ul>
 *   <li>方法<b>正常返回</b> → {@code result = SUCCESS}；</li>
 *   <li>方法<b>抛异常</b> → {@code result = FAILURE} + {@code errorCode}
 *       （{@link BizException} 取其错误码；其他 Throwable 记异常类名兜底），
 *       <b>异常必须继续向上抛</b>——切面只记录，不吞异常；</li>
 *   <li>{@code action} / {@code bizType} / {@code desc}（→ {@code change_summary}）取注解值；
 *       {@code bizId} 从 {@link Audited#bizIdParam()} 声明的<b>方法参数名</b>解析
 *       （显式非空却解析不到 → 抛 {@link IllegalStateException}，照 T08 fail-fast 口径，不得静默跳过）；</li>
 *   <li>{@code team} / {@code operator}（含角色<b>快照</b>）从 {@link TeamContext} 取。</li>
 * </ul>
 *
 * <h2>★ 不负责 before/after</h2>
 * 切面拿不到业务对象，故<b>不</b>填 {@code before_json}/{@code after_json}——
 * 需要变更前后值的埋点用<b>显式 {@code AuditWriteService.record()}</b>。
 *
 * <p>写入经 {@link AuditWriteService#record}（独立事务 + 失败只记 ERROR 日志），
 * 故本切面自身不会再抛审计异常；注解 {@code action} 缺失/非法时记 ERROR 并<b>放行业务</b>
 * （埋点配置错误不得反向搞挂业务）。
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditWriteService auditWriteService;

    public AuditAspect(AuditWriteService auditWriteService) {
        this.auditWriteService = auditWriteService;
    }

    /**
     * 拦截带 {@link Audited} 注解的方法。
     *
     * @param pjp     连接点
     * @param audited 方法上的注解（由 {@code @annotation(audited)} 绑定）
     */
    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        AuditAction action = parseAction(audited.action());
        if (action == null) {
            // 埋点配置错误（action 缺失或不在 35 条之内）：记 ERROR 但不阻断业务
            log.error("Audited.action 缺失或非法（须为 DESIGN_BASIS §9.17 的 35 条 AuditAction 之一），"
                            + "本次不记录审计：method={}#{} action='{}' requestId={}",
                    pjp.getSignature().getDeclaringType().getSimpleName(), pjp.getSignature().getName(),
                    audited.action(), RequestIdContext.get());
            return pjp.proceed();
        }

        Long bizId = resolveBizId(pjp, audited.bizIdParam());
        TeamContext.Snapshot snapshot = TeamContext.get();

        try {
            Object result = pjp.proceed();
            auditWriteService.record(baseEvent(snapshot, action, audited, bizId)
                    .result(AuditResult.SUCCESS)
                    .build());
            return result;
        } catch (BizException ex) {
            auditWriteService.record(baseEvent(snapshot, action, audited, bizId)
                    .result(AuditResult.FAILURE)
                    .errorCode(ex.getErrorCode())
                    .build());
            throw ex;
        } catch (Throwable t) {
            // 非 BizException（未预期异常）：errorCode 记异常类名兜底（写入时截断至 32），保留排查线索
            auditWriteService.record(baseEvent(snapshot, action, audited, bizId)
                    .result(AuditResult.FAILURE)
                    .errorCode(t.getClass().getSimpleName())
                    .build());
            throw t;
        }
    }

    /** 组装事件的公共部分（团队 / 操作者快照 / 动作 / 业务对象 / 摘要）。 */
    private AuditEvent.Builder baseEvent(TeamContext.Snapshot snapshot, AuditAction action,
                                         Audited audited, Long bizId) {
        AuditEvent.Builder builder = AuditEvent.builder()
                .teamId(snapshot == null ? 0L : snapshot.teamId())
                .action(action)
                .bizType(audited.bizType())
                .bizId(bizId)
                .changeSummary(audited.desc());
        if (snapshot != null) {
            builder.operatorId(snapshot.userId())
                    .operatorName(snapshot.username())
                    .operatorRole(snapshot.roles().isEmpty() ? null : snapshot.roles().get(0));
        }
        return builder;
    }

    /**
     * 从方法参数解析业务对象 ID。
     *
     * <ul>
     *   <li>{@code bizIdParam} <b>为空</b>（如创建类端点没有 id 参数）→ 返回 {@code null}
     *       （{@code biz_id} 可空，合法场景）；</li>
     *   <li><b>非空</b>（作者显式声明要记业务对象）→ 必须解析到同名 {@code Number} 参数；
     *       解析不到（参数名拼错 / 被改名 / 类型非 Number）→ 抛 {@link IllegalStateException}
     *       （配置错误快速暴露，<b>不得静默跳过</b>——静默丢失 biz_id 比显式报错更危险）。</li>
     * </ul>
     */
    private Long resolveBizId(ProceedingJoinPoint pjp, String bizIdParam) {
        if (bizIdParam == null || bizIdParam.isEmpty()) {
            return null;
        }
        CodeSignature signature = (CodeSignature) pjp.getSignature();
        String[] names = signature.getParameterNames();
        Object[] args = pjp.getArgs();
        if (names != null && args != null) {
            for (int i = 0; i < names.length; i++) {
                if (bizIdParam.equals(names[i]) && i < args.length && args[i] instanceof Number number) {
                    return number.longValue();
                }
            }
        }
        throw new IllegalStateException(
                "Audited(bizIdParam='" + bizIdParam + "') 解析失败：方法 ["
                        + signature.getDeclaringType().getSimpleName() + "#" + signature.getName()
                        + "] 不存在该名称的 Number 参数，审计 biz_id 无法记录");
    }

    /** 注解 action 字符串 → 枚举；缺失或不在 35 条之内返回 {@code null}（由调用方记 ERROR 并放行）。 */
    private static AuditAction parseAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        try {
            return AuditAction.valueOf(action);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
