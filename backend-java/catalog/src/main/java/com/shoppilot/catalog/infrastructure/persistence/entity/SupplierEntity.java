package com.shoppilot.catalog.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code suppliers} 表映射（{@code DATABASE_DESIGN §5.5}）。
 *
 * <p>{@code lead_time_days} / {@code moq} / {@code order_multiple} <b>可空 = 未配置</b>，
 * 触发向下一级回退（{@code §10.2}）；禁用 0 表示未配置（0 是合法交期）。
 * {@code moq} / {@code order_multiple} 为数量 → {@code DECIMAL(18,3)}。
 */
@Data
@TableName("suppliers")
public class SupplierEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    /** 供应商编码（团队内唯一，uk_sup_team_code）。 */
    private String code;
    private String name;
    /** {@code MasterDataStatus}：ACTIVE / INACTIVE。 */
    private String status;

    private String contactName;
    private String contactPhone;
    private String email;
    private String address;

    /** 供应商级交期 L（天；可空=未配置）。 */
    private Integer leadTimeDays;
    /** 供应商级最小起订量（数量，可空=未配置）。 */
    private BigDecimal moq;
    /** 供应商级采购倍数（数量，可空=未配置）。 */
    private BigDecimal orderMultiple;
    private String paymentTerms;
    private String remark;

    // ---- 审计字段（集合 A）+ version ----
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    /** 乐观锁版本（{@code R62}，M4-B23）；本类更新走 {@code update(null, wrapper)}，由写服务手写 CAS。 */
    @Version
    private Integer version;
}
