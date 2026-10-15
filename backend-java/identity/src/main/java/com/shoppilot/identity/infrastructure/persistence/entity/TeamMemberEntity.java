package com.shoppilot.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code team_members} 表映射（users × teams 多对多 + 团队内角色）。
 *
 * <p><b>本表含 {@code team_id}</b>，因此会被 {@code TenantLineInnerInterceptor} 自动追加
 * {@code team_id = <当前团队>}。认证流程中存在「尚无团队上下文」的查询（登录选团队、
 * 每请求成员状态校验），这些查询必须绕过租户线，见 {@code TeamMemberMapper} 上的
 * {@code @InterceptorIgnore(tenantLine = "true")}。
 */
@Data
@TableName("team_members")
public class TeamMemberEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID（隔离边界，不可变）。 */
    private Long teamId;

    /** 用户 ID（不可变）。 */
    private Long userId;

    /** {@code TeamRole}：OWNER/ADMIN/OPERATOR/APPROVER/VIEWER。 */
    private String role;

    /** {@code MemberStatus}：ACTIVE / REMOVED。 */
    private String status;

    /** 加入时间（UTC，用于「取最早加入的 ACTIVE 团队」排序）。 */
    private LocalDateTime joinedAt;

    /** 移出时间（UTC，可空）。 */
    private LocalDateTime removedAt;
}
