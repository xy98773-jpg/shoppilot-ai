package com.shoppilot.audit.infrastructure.persistence;

import com.shoppilot.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.shoppilot.audit.infrastructure.persistence.mapper.AuditLogMapper;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计行的<b>独立事务</b>写入器（{@code MODULE_BOUNDARIES §2.10}「审计写入默认与业务事务解耦」）。
 *
 * <h2>为什么单独一个 Bean</h2>
 * 若把 {@code REQUIRES_NEW} 直接标在 {@code AuditWriteService.record} 上、又在同一方法内
 * try/catch 吞掉异常，一旦方法内部将来出现<b>嵌套的事务边界</b>，Spring 会把外层事务标记为
 * {@code rollback-only}，随后的 commit 抛 {@code UnexpectedRollbackException}——catch 形同虚设。
 * 拆成独立 Bean 后：审计插入在<b>自己的新事务</b>里，失败只回滚该新事务，
 * 业务事务完全不受影响（挂起 → 恢复），语义显式且可测。
 *
 * <p>本 Bean 只做一件事：insert。<b>不</b>做脱敏 / 组装 / 异常兜底
 * （那些在 {@code AuditWriteService}）。
 *
 * <h2>★ 两条事务通道（{@code MODULE_BOUNDARIES §2.10}，{@code M3-B9.5}）</h2>
 * <ul>
 *   <li><b>默认（解耦）</b>：{@link #insert} / {@link #insertAll} —— {@code REQUIRES_NEW}，
 *       审计独立提交，业务回滚不带走审计，审计失败也不污染业务事务。
 *       适用于 §2.10 高风险清单<b>之外</b>的动作（登录 / 成员 / 主数据 / 导入批次 / 销售 / 补货策略变更）。</li>
 *   <li><b>加入调用方事务</b>：{@link #insertInTx} —— {@code MANDATORY}，
 *       与业务同生共死。适用于 §2.10 <b>高风险操作</b>
 *       （库存调整、审批、确认到货、取消采购单）：宁可业务失败，不可留痕缺失。</li>
 * </ul>
 */
@Component
public class AuditLogWriter {

    private final AuditLogMapper auditLogMapper;

    public AuditLogWriter(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 在<b>独立事务</b>中追加一行审计。
     *
     * <p>{@code REQUIRES_NEW}：挂起调用方（业务）事务，开启新事务；业务事务在本方法返回后恢复。
     * 失败时仅本新事务回滚，异常上抛给 {@code AuditWriteService} 兜底（记 ERROR 日志，不冒泡到业务）。
     *
     * @param entity 已组装、已脱敏的审计行（{@code team_id} 已由调用方显式设置）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void insert(AuditLogEntity entity) {
        auditLogMapper.insert(entity);
    }

    /**
     * 在<b>调用方事务</b>内写入一条审计（高风险操作用，{@code MODULE_BOUNDARIES §2.10}）。
     *
     * <p>{@code MANDATORY}：<b>没有外层事务直接抛异常</b>——防止本方法被误用到无事务场景。
     * 若在无事务场景下"静默降级成自动提交"，等于又变回独立提交，恰好抹掉本通道存在的全部意义；
     * 让它在开发/测试期就炸出来，比线上出现「业务已提交、审计缺失」要便宜得多。
     *
     * <p>与 {@link #insert} 的差异只有事务传播：语义上
     * 「业务提交 ⇒ 审计一并提交」「业务回滚 ⇒ 审计一并撤销」，不再有中间态的留痕缺失或残留。
     * 异常一律上抛给 {@code AuditWriteService}（那条通道<b>不</b>做兜底）。
     *
     * @param entity 已组装、已脱敏的审计行（{@code team_id} 已由调用方显式设置）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void insertInTx(AuditLogEntity entity) {
        auditLogMapper.insert(entity);
    }

    /**
     * 在<b>一个</b>独立事务中追加多行审计（{@code ADR-004 D1}，{@code B4-PERF}）。
     *
     * <p><b>提交粒度：每批一次</b>——{@link Propagation#REQUIRES_NEW} 只作用在<b>方法</b>上，
     * 方法内循环 {@code insert} 共享同一个事务，方法返回时才 {@code commit} 一次。
     * 与 {@link #insert(AuditLogEntity)}（每条一次提交）相比，审计<b>条数完全不变</b>
     * （{@code ADR-004 D3} 红线：禁止抑制/采样/合并/汇总），变的只是提交次数。
     *
     * <p><b>为什么不能用 {@code REQUIRED}</b>：{@code REQUIRED} 会加入调用方的业务事务，
     * 业务回滚时把审计一并带走（{@code MODULE_BOUNDARIES §2.10}「避免审计失败阻塞业务」的另一面：
     * 审计也不能被业务顺手抹掉），且会因审计写失败而污染业务事务的 {@code rollback-only}。
     *
     * <p>空 / {@code null} 入参直接返回（不开启事务）。
     *
     * @param entities 已组装、已脱敏的审计行（非空元素；{@code team_id} 已由调用方显式设置）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void insertAll(List<AuditLogEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        // 多行 INSERT 分块：一条语句最多写 STATEMENT_ROWS 行。
        // 目的有二：① 语句体积与参数个数有界（22 列 × 500 = 11000 个占位符，远离 max_allowed_packet）；
        // ② 整批仍在本方法的【同一个】REQUIRES_NEW 事务内，方法返回时才 commit 一次。
        for (int from = 0; from < entities.size(); from += STATEMENT_ROWS) {
            int to = Math.min(from + STATEMENT_ROWS, entities.size());
            auditLogMapper.insertBatch(entities.subList(from, to));
        }
    }

    /**
     * 一条多行 {@code INSERT} 最多包含的行数（分块上限，与导入批大小无关）。
     *
     * <p>22 列 × 500 行 = 11000 个占位符，语句体积 < 1 MB，远离 {@code max_allowed_packet}；
     * 同时避免一次性构造超长 SQL 字符串。审计<b>条数</b>不受此常量影响（只影响语句条数）。
     */
    private static final int STATEMENT_ROWS = 500;
}
