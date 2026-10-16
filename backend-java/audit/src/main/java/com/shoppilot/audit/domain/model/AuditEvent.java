package com.shoppilot.audit.domain.model;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 一条审计记录的值对象（{@code audit_logs} 一行的应用层视图）。
 *
 * <p>字段与 {@code DATABASE_DESIGN.md §5.22} / {@code §6-J.2} 一一对应：
 * 操作者（{@code operator*}，<b>快照</b>语义——角色可变，审计记"当时角色"）、
 * 团队（{@code teamId}，**只来自服务端**）、业务对象（{@code bizType}+{@code bizId} 多态 +
 * {@code bizNo} 编号快照）、结果（{@code result}+{@code errorCode}）、
 * 变更摘要（{@code changeSummary}+{@code before}/{@code after}，<b>已脱敏</b>）。
 *
 * <p><b>HTTP 上下文不在本对象中</b>：{@code http_method}/{@code request_uri}/{@code request_id}/
 * {@code ip}/{@code user_agent} 由 audit 模块在写入时从 {@code RequestIdContext} 与
 * {@code RequestContextHolder} <b>自动采集</b>（业务方不必逐项传；取不到时置 NULL——系统任务场景）。
 *
 * <p>{@code occurredAt} 缺省为当前 UTC 时间（业务发生时间）；{@code createdAt} 由数据库
 * {@code CURRENT_TIMESTAMP(3)} 填充（用于排查审计写入延迟），应用层不传。
 */
public final class AuditEvent {

    private final Long teamId;
    private final AuditAction action;
    private final String bizType;
    private final Long bizId;
    private final String bizNo;
    private final Long operatorId;
    private final String operatorName;
    private final String operatorRole;
    private final AuditResult result;
    private final String errorCode;
    private final String changeSummary;
    private final Object before;
    private final Object after;
    private final LocalDateTime occurredAt;

    private AuditEvent(Builder builder) {
        this.teamId = builder.teamId;
        this.action = builder.action;
        this.bizType = builder.bizType;
        this.bizId = builder.bizId;
        this.bizNo = builder.bizNo;
        this.operatorId = builder.operatorId;
        this.operatorName = builder.operatorName;
        this.operatorRole = builder.operatorRole;
        this.result = builder.result == null ? AuditResult.SUCCESS : builder.result;
        this.errorCode = builder.errorCode;
        this.changeSummary = builder.changeSummary;
        this.before = builder.before;
        this.after = builder.after;
        this.occurredAt = builder.occurredAt == null
                ? LocalDateTime.now(ZoneOffset.UTC)
                : builder.occurredAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getTeamId() {
        return teamId;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getBizType() {
        return bizType;
    }

    public Long getBizId() {
        return bizId;
    }

    public String getBizNo() {
        return bizNo;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public String getOperatorRole() {
        return operatorRole;
    }

    public AuditResult getResult() {
        return result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public Object getBefore() {
        return before;
    }

    public Object getAfter() {
        return after;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    /** 建造者（字段多为可选；{@code action} 与 {@code teamId} 必填，见 {@link Builder#build()}）。 */
    public static final class Builder {

        private Long teamId;
        private AuditAction action;
        private String bizType;
        private Long bizId;
        private String bizNo;
        private Long operatorId;
        private String operatorName;
        private String operatorRole;
        private AuditResult result;
        private String errorCode;
        private String changeSummary;
        private Object before;
        private Object after;
        private LocalDateTime occurredAt;

        private Builder() {
        }

        /** 团队 ID：<b>只允许服务端来源</b>（{@code TeamContext} 或 {@code 0} 系统域）。 */
        public Builder teamId(Long teamId) {
            this.teamId = teamId;
            return this;
        }

        public Builder action(AuditAction action) {
            this.action = action;
            return this;
        }

        public Builder bizType(String bizType) {
            this.bizType = bizType;
            return this;
        }

        public Builder bizId(Long bizId) {
            this.bizId = bizId;
            return this;
        }

        public Builder bizNo(String bizNo) {
            this.bizNo = bizNo;
            return this;
        }

        public Builder operatorId(Long operatorId) {
            this.operatorId = operatorId;
            return this;
        }

        public Builder operatorName(String operatorName) {
            this.operatorName = operatorName;
            return this;
        }

        public Builder operatorRole(String operatorRole) {
            this.operatorRole = operatorRole;
            return this;
        }

        public Builder result(AuditResult result) {
            this.result = result;
            return this;
        }

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder changeSummary(String changeSummary) {
            this.changeSummary = changeSummary;
            return this;
        }

        public Builder before(Object before) {
            this.before = before;
            return this;
        }

        public Builder after(Object after) {
            this.after = after;
            return this;
        }

        public Builder occurredAt(LocalDateTime occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        /**
         * 构造事件；{@code action} / {@code teamId} 缺失即抛 {@link IllegalStateException}
         * （fail-fast：缺这两项的审计记录没有意义，静默写入会制造不可用的审计行）。
         */
        public AuditEvent build() {
            if (action == null) {
                throw new IllegalStateException("AuditEvent.action 缺失：action 为 35 条 AuditAction 之一，必填");
            }
            if (teamId == null) {
                throw new IllegalStateException(
                        "AuditEvent.teamId 缺失：team_id 只来自服务端（TeamContext 或 0 系统域），必填");
            }
            return new AuditEvent(this);
        }
    }
}
