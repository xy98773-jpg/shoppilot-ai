package com.shoppilot.catalog.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code skus} 表映射（{@code DATABASE_DESIGN §5.7}）。
 *
 * <p>外键 {@code fk_sku_product → products(id)}、{@code fk_sku_supplier → suppliers(id)}（可空，RESTRICT）。
 * {@code product_id} <b>不可变</b>（换商品须新建 SKU，{@code §6-B}）；{@code supplier_id} 须同团队
 * （否则 {@code CAT-3005}）。
 *
 * <p>精度：{@code moq} / {@code order_multiple} / {@code qty_step} / {@code weight_gram} 是<b>数量</b>
 * → {@code DECIMAL(18,3)}；{@code purchase_price} / {@code sale_price} 是<b>单价</b> → {@code DECIMAL(18,4)}。
 * {@code spec_json} 为 JSON 列，本层以字符串承载（写入前由服务层用 Jackson 规范化）。
 */
@Data
@TableName("skus")
public class SkuEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    /** 所属商品（不可变）。 */
    private Long productId;
    /** SKU 编码（团队内唯一，uk_sku_team_code）。 */
    private String skuCode;
    private String name;
    private String barcode;
    /** 规格键值（颜色/尺码等），JSON 文本。 */
    private String specJson;
    /** {@code MasterDataStatus}：ACTIVE / INACTIVE。 */
    private String status;

    /** SKU 级供应商（可空；须同团队）。 */
    private Long supplierId;
    /** SKU 级交期 L（天；可空=未配置）。 */
    private Integer defaultLeadTimeDays;
    private BigDecimal moq;
    private BigDecimal orderMultiple;
    private BigDecimal qtyStep;
    /** 参考采购单价（DECIMAL(18,4)）。 */
    private BigDecimal purchasePrice;
    /** 参考售价（DECIMAL(18,4)）。 */
    private BigDecimal salePrice;
    /** 重量（克）。 */
    private BigDecimal weightGram;
    /** 上架日期。 */
    private LocalDate listedDate;

    // ---- 审计字段（集合 A）+ version ----
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    /** 乐观锁版本（{@code R62}，M4-B23）；本类更新走 {@code update(null, wrapper)}，由写服务手写 CAS。 */
    @Version
    private Integer version;
}
