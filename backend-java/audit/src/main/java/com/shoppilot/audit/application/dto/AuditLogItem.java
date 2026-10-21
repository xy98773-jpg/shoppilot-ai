package com.shoppilot.audit.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.shoppilot.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.shoppilot.common.web.serialize.UtcDateTimeSerializer;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 审计日志响应元素（{@code GET /api/v1/audit/logs}，{@code API_CONTRACT §10.10}，M6-T09）。
 *
 * <h2>★ 字段集合（B0-5，逐字段照抄，不得增删）</h2>
 * <pre>
 * audit_log_id, action, biz_type, biz_id, biz_no, operator_id, operator_name, operator_role,
 * result, error_code, change_summary, request_id, ip, occurred_at, before_json, after_json
 * </pre>
 *
 * <p><b>不返回</b> {@code team_id} 与 {@code created_by}：{@code team_id} 只用于服务端过滤，
 * 不外泄；{@code created_by} 属内部公共审计字段、与 {@code operator_id} 重复。
 *
 * <p>{@code before_json}/{@code after_json} 以<b>JSON 字符串</b>原样返回（写入侧已由
 * {@code AuditSanitizer} 脱敏）；本类<b>不</b>二次解析，避免改变字段顺序或丢失 {@code ***} 掩码。
 * {@code occurred_at} 为业务发生时间（UTC），排序与筛选均以它为准（不是 {@code created_at}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AuditLogItem {

    /** 审计记录 ID（{@code audit_logs.id}）。 */
    private Long auditLogId;

    /** 动作常量（{@code AuditAction} 枚举名，35 条之一）。 */
    private String action;

    /** 业务对象类型（{@code WAREHOUSE}/{@code TEAM_MEMBER}/{@code USER} 等）。 */
    private String bizType;

    /** 业务对象 ID（多态，逻辑关联）。 */
    private Long bizId;

    /** 业务编号快照。 */
    private String bizNo;

    /** 操作人 ID（系统任务为 {@code null}）。 */
    private Long operatorId;

    /** 操作人显示名快照。 */
    private String operatorName;

    /** 操作时角色快照。 */
    private String operatorRole;

    /** {@code SUCCESS} / {@code FAILURE}。 */
    private String result;

    /** 失败错误码（成功时为 {@code null}）。 */
    private String errorCode;

    /** 人类可读变更摘要。 */
    private String changeSummary;

    /** 链路追踪 ID（{@code X-Request-Id}）。 */
    private String requestId;

    /** 客户端 IP。 */
    private String ip;

    /** 操作发生时间（UTC，毫秒精度 ISO-8601 字面量）。 */
    @JsonSerialize(using = UtcDateTimeSerializer.class)
    private LocalDateTime occurredAt;

    /** 变更前关键字段（JSON 字符串，已脱敏）。 */
    private String beforeJson;

    /** 变更后关键字段（JSON 字符串，已脱敏）。 */
    private String afterJson;

    /** 实体 → 响应元素（只搬运 B0-5 列出的字段）。 */
    public static AuditLogItem of(AuditLogEntity e) {
        AuditLogItem item = new AuditLogItem();
        item.setAuditLogId(e.getId());
        item.setAction(e.getAction());
        item.setBizType(e.getBizType());
        item.setBizId(e.getBizId());
        item.setBizNo(e.getBizNo());
        item.setOperatorId(e.getOperatorId());
        item.setOperatorName(e.getOperatorName());
        item.setOperatorRole(e.getOperatorRole());
        item.setResult(e.getResult());
        item.setErrorCode(e.getErrorCode());
        item.setChangeSummary(e.getChangeSummary());
        item.setRequestId(e.getRequestId());
        item.setIp(e.getIp());
        item.setOccurredAt(e.getOccurredAt());
        item.setBeforeJson(e.getBeforeJson());
        item.setAfterJson(e.getAfterJson());
        return item;
    }
}
