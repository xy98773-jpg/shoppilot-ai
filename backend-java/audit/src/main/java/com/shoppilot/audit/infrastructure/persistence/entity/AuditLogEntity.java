package com.shoppilot.audit.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code audit_logs} 表映射（{@code DATABASE_DESIGN.md §5.22}）。
 *
 * <p><b>仅追加表</b>（集合 B）：无 {@code updated_at}/{@code updated_by}、无 {@code version}；
 * 应用层禁止 UPDATE / DELETE（{@code §6-J.4}）——故本实体只被 INSERT 使用。
 *
 * <p><b>租户线口径</b>：本表<b>含</b> {@code team_id}（NOT NULL，{@code 0} = 系统域），
 * 因此 {@link com.shoppilot.common.tenant.TenantTables} <b>不</b>登记它；
 * 但 {@code ShopPilotTenantLineHandler} 在团队上下文缺失时抛 {@code AUTH-1001}
 * （M2-T06 既定防线）→ 登录失败 / 系统任务写不进去。故 Mapper 的写方法显式
 * {@code @InterceptorIgnore(tenantLine="true")}，由本实体<b>显式</b>携带 {@code teamId}
 * （有上下文取其值，无上下文传 {@code 0}）。这是"绕过条件追加"而非放弃隔离。
 *
 * <p>{@code beforeJson}/{@code afterJson} 为 JSON 列（入库前已经 {@code AuditSanitizer} 脱敏）；
 * {@code createdAt} 由数据库 {@code CURRENT_TIMESTAMP(3)} 填充，应用层不传。
 */
@Data
@TableName("audit_logs")
public class AuditLogEntity {

    private Long id;

    /** 团队 ID（{@code 0} = 系统域；服务端注入，绝不接受客户端传入）。 */
    private Long teamId;

    /** 动作常量（{@code AuditAction} 枚举名，35 条之一）。 */
    private String action;

    /** 业务对象类型（表名或领域名，如 {@code WAREHOUSE}/{@code TEAM_MEMBER}/{@code USER}）。 */
    private String bizType;

    /** 业务对象 ID（多态，逻辑关联）。 */
    private Long bizId;

    /** 业务编号快照（对象可能已软停用）。 */
    private String bizNo;

    /** 操作人（系统任务为 {@code NULL}）。 */
    private Long operatorId;

    /** 操作人显示名快照。 */
    private String operatorName;

    /** 操作时角色快照。 */
    private String operatorRole;

    private String httpMethod;

    /** 请求路径（<b>已剥离查询串</b>）。 */
    private String requestUri;

    /** 链路追踪 ID（{@code X-Request-Id}）。 */
    private String requestId;

    private String ip;

    /** 客户端 UA（截断至 255）。 */
    private String userAgent;

    /** {@code SUCCESS} / {@code FAILURE}。 */
    private String result;

    /** 失败错误码。 */
    private String errorCode;

    /** 人类可读变更摘要（截断至 1000）。 */
    private String changeSummary;

    /** 变更前关键字段（JSON 文本，<b>已脱敏</b>）。 */
    private String beforeJson;

    /** 变更后关键字段（JSON 文本，<b>已脱敏</b>）。 */
    private String afterJson;

    /** 操作发生时间（UTC）。 */
    private LocalDateTime occurredAt;

    /** 创建时间（UTC，DB 默认值 {@code CURRENT_TIMESTAMP(3)} 填充；应用层不传，insert 排除）。 */
    @TableField(insertStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 创建人（逻辑关联 {@code users.id}；系统任务为 {@code NULL}）。 */
    private Long createdBy;
}
