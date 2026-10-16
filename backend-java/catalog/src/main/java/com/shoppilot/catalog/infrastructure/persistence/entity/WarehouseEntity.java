package com.shoppilot.catalog.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code warehouses} 表映射（{@code DATABASE_DESIGN §5.4}）。
 *
 * <p>含 {@code team_id} → 被 {@code TenantLineInnerInterceptor} 自动追加
 * {@code AND team_id = <当前团队>}（SELECT/UPDATE/DELETE），INSERT 由插件补齐 team_id 列
 * （本层仍在服务端显式 set 双保险）。审计字段（集合 A）与 {@code version} 由 DB 默认值维护，
 * 应用层不显式覆盖 {@code created_at} / {@code updated_at}。
 */
@Data
@TableName("warehouses")
public class WarehouseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID（隔离边界，不可变）。 */
    private Long teamId;

    /** 仓库编码（团队内唯一，uk_wh_team_code）。 */
    private String code;

    /** 仓库名称。 */
    private String name;

    /** {@code MasterDataStatus}：ACTIVE / INACTIVE（状态流转属 M2-T10，本批不写）。 */
    private String status;

    /** 是否默认仓（同团队至多一行为 1，由服务层事务内保证）。 */
    private Boolean isDefault;

    private String address;
    private String contactName;
    private String contactPhone;
    private String remark;

    // ---- 审计字段（集合 A）+ version ----
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private Integer version;
}
