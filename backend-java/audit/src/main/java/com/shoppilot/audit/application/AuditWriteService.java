package com.shoppilot.audit.application;

import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.audit.infrastructure.persistence.AuditLogWriter;
import com.shoppilot.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.shoppilot.common.web.RequestIdContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计写入服务（{@code MODULE_BOUNDARIES §2.10} 命名，<b>不得改名</b>）——审计的<b>唯一写入口</b>。
 *
 * <p>职责（{@code §2.10} 对外暴露的服务接口）：脱敏（{@link AuditSanitizer}）→ 组装实体
 * → 经 {@link AuditLogWriter} 以<b>独立事务</b>（{@code REQUIRES_NEW}）insert。
 *
 * <h2>★ 两条通道：解耦（默认）与同事务（高风险操作，{@code M3-B9.5}）</h2>
 * <ul>
 *   <li>{@link #record} / {@link #recordAll}：<b>独立事务</b>（{@code REQUIRES_NEW}）、
 *       <b>永不抛异常</b>。适用于 §2.10 高风险清单<b>之外</b>的动作。</li>
 *   <li>{@link #recordInBusinessTx}：<b>加入调用方业务事务</b>（{@code MANDATORY}）、
 *       <b>失败即抛</b>。仅用于 §2.10 列举的<b>高风险操作</b>
 *       （库存调整、审批、确认到货、取消采购单）——目的是消除「审计成功但业务未生效」
 *       与「业务成功但审计缺失」两个中间态。<b>两者语义不同是有意的，不要"统一"掉</b>。</li>
 * </ul>
 *
 * <h2>★ 事务与失败兜底（M2-T11 裁定，仅适用于 {@link #record} / {@link #recordAll}）</h2>
 * <ol>
 *   <li><b>独立事务</b>：按 {@code §2.10}「审计写入默认与业务事务解耦」，审计插入在
 *       {@code REQUIRES_NEW} 新事务中进行（实现见 {@link AuditLogWriter}）。理由：
 *       M2 埋点的操作（登录 / 成员 / 主数据）<b>均非</b> §2.10 列举的高风险操作
 *       （库存调整 / 审批 / 确认到货 / 取消采购单——那些走 {@link #recordInBusinessTx}，见 {@code §8.2 E-3}）。</li>
 *   <li><b>永不阻塞业务</b>：审计写入失败必须 catch 并记 <b>ERROR</b> 日志
 *       （含 {@code action}/{@code bizType}/{@code requestId}，作为补偿线索），
 *       ❌ 不得因审计失败抛异常导致业务 500。</li>
 * </ol>
 *
 * <h2>HTTP 上下文自动采集</h2>
 * {@code http_method}/{@code request_uri}/{@code request_id}/{@code ip}/{@code user_agent}
 * 由本类从 {@link RequestIdContext} 与 {@link RequestContextHolder} 采集，
 * 业务方<b>不必</b>逐项传；取不到时置 {@code NULL}（系统任务场景）。
 * {@code request_uri} 剥离查询串（防 {@code ?token=...}），各字符串字段按列宽截断
 * （防 MySQL 严格模式报 {@code 1406 Data too long}）。
 *
 * <h2>★ 批量与延迟写入（{@code ADR-004} D1/D2，{@code B4-PERF}）</h2>
 * <ul>
 *   <li>{@link #recordAll}：一个 {@code REQUIRES_NEW} 事务批量写，<b>一次提交</b>；条数不变；</li>
 *   <li>{@link #record} 的<b>唯一</b>行为扩展：当前线程处于 {@link AuditBufferScope} 内且事务活跃时
 *       改为入缓冲，事务提交后批量写出。其余情况（无作用域 / 无事务）与旧实现<b>逐字节一致</b>——
 *       REST 与 {@code @Audited} 切面路径完全不受影响。</li>
 * </ul>
 */
@Service
public class AuditWriteService {

    private static final Logger log = LoggerFactory.getLogger(AuditWriteService.class);

    private final AuditLogWriter auditLogWriter;

    public AuditWriteService(AuditLogWriter auditLogWriter) {
        this.auditLogWriter = auditLogWriter;
    }

    /**
     * 记录一条审计（唯一写入口）。
     *
     * <p><b>永不抛异常</b>：任何失败（含 DB 不可用）都只记 ERROR 日志后返回——
     * 审计是旁路，不能反过来拖垮业务。调用方无需 try/catch。
     *
     * @param event 审计事件（{@code action} / {@code teamId} 必填，见 {@link AuditEvent.Builder#build()}）
     */
    public void record(AuditEvent event) {
        if (event == null) {
            return;
        }
        if (event.getAction() == null || event.getBizType() == null || event.getBizType().isBlank()) {
            // 配置类错误（漏传 action/bizType）：记 ERROR 但不写库、不抛异常——
            // 缺这两项的审计行没有意义，且不能因埋点疏漏搞挂业务。
            log.error("审计事件缺少 action/bizType，已跳过写入：action={} bizType={} requestId={}",
                    event.getAction(), event.getBizType(), RequestIdContext.get());
            return;
        }
        // ★ ADR-004 D2（B4-PERF）：当前线程处于 AuditBufferScope 内且事务活跃 → 只入缓冲，等业务
        //   事务提交后由 afterCommit 一次性写出（每批一次提交）。无作用域 / 无事务时走下面原路径，
        //   REST / AOP 的逐条写入行为逐字节不变。
        //   occurred_at 已由 AuditEvent 在构造（=事实发生）时刻确定，延迟写入不改变其时间语义。
        if (AuditBufferScope.enqueue(event)) {
            return;
        }
        try {
            auditLogWriter.insert(toEntity(event));
        } catch (Exception ex) {
            // ★ 解耦兜底：审计失败不影响业务；ERROR 日志含 action/bizType/requestId 作为补偿线索
            log.error("审计写入失败（业务不受影响）：action={} bizType={} teamId={} requestId={} err={}",
                    event.getAction(), event.getBizType(), event.getTeamId(), RequestIdContext.get(),
                    ex.toString());
        }
    }

    /**
     * 记录一条审计，<b>与调用方业务事务同生共死</b>（{@code MODULE_BOUNDARIES §2.10} 高风险操作，
     * {@code M3-B9.5}）。库存调整（{@code #11}/{@code #12}）、冲销（{@code #13}）等「事后无法用
     * 业务数据反推发生过什么」的操作必须走本方法。
     *
     * <h2>与 {@link #record} 的三点差异（★ 有意的差异，不要"统一"）</h2>
     * <ol>
     *   <li><b>事务</b>：加入业务事务（{@link org.springframework.transaction.annotation.Propagation#MANDATORY}），
     *       不再 {@code REQUIRES_NEW}。业务回滚 ⇒ 审计一并撤销；业务提交 ⇒ 审计一并提交。
     *       遂<b>不存在</b>「审计成功但业务未生效」（审计谎报）与「业务成功但审计缺失」（留痕丢失）。</li>
     *   <li><b>失败</b>：<b>直接抛出</b>（不 catch）。审计写不进就必须让业务回滚——
     *       这是 §2.10 对高风险操作的取舍：<b>宁可业务失败，不可留痕缺失</b>。</li>
     *   <li><b>入参错误</b>（{@code null} 事件 / 缺 {@code action} / 缺 {@code bizType}）：抛
     *       {@link IllegalArgumentException}，让埋点错误在测试期暴露，而不是像 {@link #record} 那样
     *       记 ERROR 后静默跳过（那会让"埋点写错了"伪装成"审计没写"）。</li>
     * </ol>
     *
     * <p>❌ 不走 {@link AuditBufferScope}：那是导入批量场景的延迟写优化（{@code ADR-004 D2}），
     * 与本通道「立刻、且与业务同事务」的诉求直接冲突。
     *
     * @param event 审计事件（{@code action} / {@code bizType} / {@code teamId} 必填）
     * @throws IllegalArgumentException 事件为 {@code null} 或缺 {@code action}/{@code bizType}
     */
    public void recordInBusinessTx(AuditEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("审计事件不能为 null（recordInBusinessTx 为高风险操作通道，不接受空事件）");
        }
        if (event.getAction() == null || event.getBizType() == null || event.getBizType().isBlank()) {
            // 与 record 的「记 ERROR 后跳过」刻意不同：高风险操作少一条留痕就是缺陷，必须炸出来
            throw new IllegalArgumentException("审计事件缺少 action/bizType：action=" + event.getAction()
                    + " bizType=" + event.getBizType() + " requestId=" + RequestIdContext.get());
        }
        // ★ 不 try/catch：写不进 ⇒ 异常冒泡 ⇒ 业务事务回滚（§2.10）
        auditLogWriter.insertInTx(toEntity(event));
    }

    /**
     * 批量记录审计（{@code ADR-004 D1}，{@code B4-PERF}）——<b>一个独立事务、一次提交</b>。
     *
     * <p>与 {@link #record} 的语义对齐：<b>永不抛异常</b>（失败只记 ERROR 后返回），
     * 调用方无需 try/catch。差异只在提交粒度，<b>审计条数不变</b>：{@code n} 条有效事件
     * 写出 {@code n} 行（{@code ADR-004 D3} 禁止抑制/采样/合并/汇总）。
     *
     * <h2>逐条跳过规则（与 {@code record} 同口径）</h2>
     * {@code null} 元素、缺 {@code action} / 缺 {@code bizType} 的事件<b>逐条跳过</b>并记 ERROR
     * （含定位信息），不影响同批其余事件——避免一条埋点疏漏拖垮整批审计。
     *
     * @param events 待写事件（{@code null} / 空 直接返回，不开启事务）
     */
    public void recordAll(List<AuditEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        List<AuditLogEntity> entities = new ArrayList<>(events.size());
        int skipped = 0;
        for (AuditEvent event : events) {
            if (event == null) {
                skipped++;
                continue;
            }
            if (event.getAction() == null || event.getBizType() == null || event.getBizType().isBlank()) {
                skipped++;
                log.error("审计事件缺少 action/bizType，已跳过写入：action={} bizType={} requestId={}",
                        event.getAction(), event.getBizType(), RequestIdContext.get());
                continue;
            }
            try {
                entities.add(toEntity(event));
            } catch (Exception ex) {
                skipped++;
                log.error("审计事件组装失败，已跳过写入：action={} bizType={} teamId={} err={}",
                        event.getAction(), event.getBizType(), event.getTeamId(), ex.toString());
            }
        }
        if (entities.isEmpty()) {
            if (skipped > 0) {
                log.error("审计批量写入跳过全部 {} 条事件（缺 action/bizType 或组装失败），本批未写库", skipped);
            }
            return;
        }
        try {
            auditLogWriter.insertAll(entities);
        } catch (Exception ex) {
            // ★ 解耦兜底：审计失败不影响业务。ERROR 日志带【条数 + action 分布】作为补偿线索
            //   （ADR-004 D6：recordAll 写入失败 → 业务不受影响，记 ERROR 含条数与 action 分布）
            log.error("审计批量写入失败（业务不受影响）：条数={} action 分布={} teamId={} requestId={} err={}",
                    entities.size(), actionDistribution(events), entities.get(0).getTeamId(),
                    RequestIdContext.get(), ex.toString());
        }
    }

    /** {@code action → 条数}（失败日志与补偿线索用；条目顺序稳定便于比对）。 */
    private static Map<String, Integer> actionDistribution(List<AuditEvent> events) {
        Map<String, Integer> dist = new TreeMap<>();
        for (AuditEvent event : events) {
            String key = event == null || event.getAction() == null ? "<null>" : event.getAction().name();
            dist.merge(key, 1, Integer::sum);
        }
        return dist;
    }

    // ---------------------------------------------------------------------
    // 内部：事件 → 实体（脱敏 + HTTP 上下文 + 列宽截断）
    // ---------------------------------------------------------------------

    private AuditLogEntity toEntity(AuditEvent event) {
        AuditLogEntity entity = new AuditLogEntity();

        // 团队：只来自服务端（TeamContext 或 0 系统域）——租户线已绕过，必须显式携带
        entity.setTeamId(event.getTeamId());
        entity.setAction(AuditSanitizer.truncate(event.getAction().name(), 64));
        entity.setBizType(AuditSanitizer.truncate(event.getBizType(), 64));
        entity.setBizId(event.getBizId());
        entity.setBizNo(AuditSanitizer.truncate(event.getBizNo(), 64));
        entity.setOperatorId(event.getOperatorId());
        entity.setOperatorName(AuditSanitizer.truncate(event.getOperatorName(), 64));
        entity.setOperatorRole(AuditSanitizer.truncate(event.getOperatorRole(), 32));
        entity.setResult(event.getResult() == null ? AuditResult.SUCCESS.name() : event.getResult().name());
        entity.setErrorCode(AuditSanitizer.truncate(event.getErrorCode(), 32));
        entity.setChangeSummary(
                AuditSanitizer.truncate(AuditSanitizer.sanitizeText(event.getChangeSummary()), 1000));

        // before/after：统一序列化 + 脱敏（黑名单 + 手机号/邮箱掩码）
        AuditSanitizer.Sanitized sanitized = AuditSanitizer.sanitize(event.getBefore(), event.getAfter());
        entity.setBeforeJson(sanitized.beforeJson());
        entity.setAfterJson(sanitized.afterJson());

        entity.setOccurredAt(event.getOccurredAt() == null
                ? java.time.LocalDateTime.now(ZoneOffset.UTC)
                : event.getOccurredAt());
        // created_by = 操作人快照（系统任务为 NULL）；created_at 由 DB 默认值填充，不传
        entity.setCreatedBy(event.getOperatorId());

        fillHttpContext(entity);
        return entity;
    }

    /**
     * 采集 HTTP 上下文（无请求上下文——系统任务 / 异步线程——时全部置 {@code NULL}）。
     *
     * <p>{@code request_uri} 剥离查询串；{@code user_agent} 截断至 255（列宽）。
     */
    private void fillHttpContext(AuditLogEntity entity) {
        entity.setRequestId(AuditSanitizer.truncate(RequestIdContext.get(), 64));
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        HttpServletRequest request = attrs.getRequest();
        entity.setHttpMethod(AuditSanitizer.truncate(request.getMethod(), 8));
        entity.setRequestUri(AuditSanitizer.truncate(AuditSanitizer.stripQuery(request.getRequestURI()), 255));
        entity.setIp(AuditSanitizer.truncate(clientIp(request), 64));
        entity.setUserAgent(AuditSanitizer.truncate(request.getHeader("User-Agent"), 255));
    }

    /** 客户端 IP：优先 {@code X-Forwarded-For} 首段（反向代理场景），否则 {@code remoteAddr}。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
