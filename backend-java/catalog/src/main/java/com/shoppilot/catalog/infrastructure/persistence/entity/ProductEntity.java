package com.shoppilot.catalog.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code products} 表映射（{@code DATABASE_DESIGN §5.6}）。
 *
 * <p>{@code default_supplier_id} 外键 {@code fk_prod_supplier → suppliers(id)}（可空，RESTRICT）；
 * 服务层写入前须校验其属<b>同团队</b>（{@code DESIGN_BASIS §6} 规则 4 → {@code CAT-3005}）。
 */
@Data
@TableName("products")
public class ProductEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    /** 商品编码（团队内唯一，uk_prod_team_code）。 */
    private String productCode;
    private String name;
    private String category;
    private String brand;
    private String unit;
    /** {@code MasterDataStatus}：ACTIVE / INACTIVE。 */
    private String status;

    /** 默认供应商 ID（可空；须同团队）。 */
    private Long defaultSupplierId;
    /** 规格摘要（展示用）。 */
    private String specSummary;
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
