package com.shoppilot.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * {@code teams} 表映射（自身即团队边界；{@code DESIGN_BASIS §6} 规则 1 的显式例外表，<b>无 {@code team_id}</b>）。
 */
@Data
@TableName("teams")
public class TeamEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队编码（对外标识，不可变，{@code uk_teams_code}）。 */
    private String code;

    /** 团队名称。 */
    private String name;

    /** {@code TeamStatus}：ACTIVE / DISABLED。 */
    private String status;

    /** 团队默认时区（登录响应 {@code team.default_timezone} 取值）。 */
    private String defaultTimezone;

    /** 默认仓库 ID（逻辑关联 {@code warehouses.id}，不建外键防环形）。 */
    private Long defaultWarehouseId;
}
