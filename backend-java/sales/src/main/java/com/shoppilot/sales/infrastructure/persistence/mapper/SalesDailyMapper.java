package com.shoppilot.sales.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.sales.infrastructure.persistence.entity.SalesDailyEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code sales_daily} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}
 * （SELECT/UPDATE/DELETE）并在 INSERT 时补 {@code team_id} 列（从 {@code TeamContext} 取）。
 * <b>本 Mapper 不含任何 {@code @InterceptorIgnore}</b>——upsert 的「冲突吸收」由应用层
 * 「先查后写」+ {@code uk_sales_team_wh_sku_date} 唯一约束在并发时兜底
 * （见 {@code SalesWriteService}），无需绕过租户插件。B7 的 {@code InventoryPolicyService}
 * 已按同一范式实践（R46 正确实践）。
 *
 * <p>唯一自定义语句 {@link #groupAggregate} 用于 {@code GET /sales/daily?groupBy=sku|warehouse|date}
 * 的维度聚合：用 {@code <choose>} 选择<b>字面</b>分组列（sku_id / warehouse_id / sale_date），
 * 不拼接客户端输入（{@code groupBy} 仅作分支判定，不作 SQL 片段注入）——白名单即防注入边界。
 */
@Mapper
public interface SalesDailyMapper extends BaseMapper<SalesDailyEntity> {

    /**
     * 按维度聚合（{@code groupBy = sku|warehouse|date}）。
     *
     * <p>返回列为 {@code group_key}（Long / LocalDate）、{@code qty}、{@code return_qty}、
     * {@code sales_amount}、{@code order_count}（后四者为 {@code SUM}）。分组列由
     * {@code <choose>} 落到字面 SQL，<b>不</b>使用 {@code ${}}。租户线自动追加
     * {@code AND team_id = <当前团队>}。
     *
     * @param groupBy     分组维度（{@code sku} / {@code warehouse} / 其他一律按 {@code sale_date}）
     * @param skuId       SKU 筛选，可空
     * @param warehouseId 仓库筛选，可空
     * @param dateFrom    起始日期（含），可空
     * @param dateTo      结束日期（含），可空
     */
    @Select("<script>"
            + "SELECT "
            + "<choose>"
            + "  <when test=\"groupBy == 'sku'\">sku_id</when>"
            + "  <when test=\"groupBy == 'warehouse'\">warehouse_id</when>"
            + "  <otherwise>sale_date</otherwise>"
            + "</choose> AS group_key, "
            + "COALESCE(SUM(qty), 0) AS qty, "
            + "COALESCE(SUM(return_qty), 0) AS return_qty, "
            + "SUM(sales_amount) AS sales_amount, "
            + "SUM(order_count) AS order_count "
            + "FROM sales_daily "
            + "WHERE 1 = 1 "
            + "<if test='skuId != null'>AND sku_id = #{skuId} </if>"
            + "<if test='warehouseId != null'>AND warehouse_id = #{warehouseId} </if>"
            + "<if test='dateFrom != null'>AND sale_date &gt;= #{dateFrom} </if>"
            + "<if test='dateTo != null'>AND sale_date &lt;= #{dateTo} </if>"
            + "GROUP BY group_key ORDER BY group_key"
            + "</script>")
    List<Map<String, Object>> groupAggregate(@Param("groupBy") String groupBy,
                                             @Param("skuId") Long skuId,
                                             @Param("warehouseId") Long warehouseId,
                                             @Param("dateFrom") LocalDate dateFrom,
                                             @Param("dateTo") LocalDate dateTo);
}
