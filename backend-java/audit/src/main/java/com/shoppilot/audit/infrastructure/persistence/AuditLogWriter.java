package com.shoppilot.audit.infrastructure.persistence;

import com.shoppilot.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.shoppilot.audit.infrastructure.persistence.mapper.AuditLogMapper;
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
 * <p>本 Bean 只做一件事：在新事务中 insert。<b>不</b>做脱敏 / 组装 / 异常兜底
 * （那些在 {@code AuditWriteService}，保证"审计失败永不冒泡到业务"）。
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
}
