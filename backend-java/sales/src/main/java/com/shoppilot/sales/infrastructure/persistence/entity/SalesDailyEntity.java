package com.shoppilot.sales.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code sales_daily} 表映射（{@code DATABASE_DESIGN §5.11}）。
 *
 * <p>团队 × 仓库 × SKU × 自然日 一行（{@code uk_sales_team_wh_sku_date}），是「重复导入 / 手工覆盖」
 * 的 <b>upsert 落点</b>（{@code §6-D.2 U-6}）：冲突时<b>不报错、覆盖</b>，以「最后一次写入为准」。
 *
 * <p><b>不可变业务键</b>：{@code teamId} / {@code warehouseId} / {@code skuId} / {@code saleDate} /
 * {@code productId}；<b>可变（覆盖）</b>：{@code qty} / {@code returnQty} / {@code salesAmount} /
 * {@code orderCount} / {@code source} / {@code batchId}。
 *
 * <p>审计字段集合 A，<b>无 {@code version}</b>（{@code §7.4} 表 B：upsert 覆盖语义本身即「最后写入者胜」，
 * 不存在「丢失更新」概念，{@code §7.4} 已裁定）。
 */
@Data
@TableName("sales_daily")
public class SalesDailyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;
    private Long warehouseId;
    private Long skuId;
    /** 冗余商品 ID（由不可变 {@code skus.product_id} 保证安全；免 JOIN 类目/商品汇总）。 */
    private Long productId;

    /** 业务日期（团队时区自然日）。 */
    private LocalDate saleDate;

    /** 净销售数量（已扣退货；{@code CHECK (qty >= 0)}）。 */
    private BigDecimal qty;
    /** 退货数量（{@code CHECK (return_qty >= 0)}）。 */
    private BigDecimal returnQty;
    /** 销售金额（可空；{@code CHECK (sales_amount IS NULL OR sales_amount >= 0)}）。 */
    private BigDecimal salesAmount;
    /** 订单数（可空，{@code INT UNSIGNED}）。 */
    private Integer orderCount;
    /** 数据来源：{@code IMPORT} / {@code MANUAL} / {@code PLATFORM}（{@code SalesSource §9.17}）。 */
    private String source;
    /** 来源导入批次（逻辑关联 {@code import_batches.id}，<b>不建外键</b>）。 */
    private Long batchId;

    // ---- 审计字段（集合 A）+ 无 version ----
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
}
