package com.shoppilot.inventory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code replenishment_policies} 表映射（{@code DATABASE_DESIGN §5.8}；V1.0.6 创建，owner = inventory）。
 *
 * <p>SKU×仓库维度的补货参数；审计字段集合 A + {@code version}（{@code §7.4} 表 A：参数读-改-写 + 多入口并发编辑）。
 * 全部参数列可空（可空即「未配置」）。{@code team_id}/{@code sku_id}/{@code warehouse_id} 不可变；
 * 停用走 {@code is_enabled = 0}，不物理删除。
 *
 * <p>普通 CRUD 走 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper}，租户线自动追加
 * {@code AND team_id = <当前团队>} 并补 {@code team_id} 列——<b>本实体不使用</b> {@code @InterceptorIgnore}
 * （与 {@code InventoryBalanceMapper#insertIfAbsent} 不同：后者有 {@code ON DUPLICATE KEY UPDATE} 的
 * JSqlParser 兼容问题，本实体仅常规 MyBatis-Plus 方法，插件可正常处理）。
 */
@Data
@TableName("replenishment_policies")
public class ReplenishmentPolicyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    private Long skuId;
    private Long warehouseId;

    private Integer leadTimeDays;
    private Integer coverageDays;
    private BigDecimal safetyStockQty;
    private BigDecimal safetyRatio;
    private BigDecimal moq;
    private BigDecimal orderMultiple;
    private BigDecimal qtyStep;
    private Integer windowDays;
    private Integer minDays;
    private BigDecimal cvHigh;
    private Integer slowMovingDays;
    private Integer overstockDays;

    /** 是否启用（1=启用，0=停用）。 */
    private Integer isEnabled;

    private String remark;

    // ---- 审计字段（集合 A）+ version ----
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    /** 乐观锁版本（{@code R62}，M4-B23）：由 {@code OptimisticLockerInnerInterceptor} 在 {@code updateById} 时自增。 */
    @Version
    private Integer version;
}
