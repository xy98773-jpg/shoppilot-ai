package com.shoppilot.sales.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 日销售写入 / 覆盖请求体（{@code POST /api/v1/sales/daily}，契约 {@code API_CONTRACT §10.5}）。
 *
 * <p>{@code source} 固定为 {@code MANUAL}（手工写入），由服务端强制，客户端传值被忽略。
 * 数量一律以<b>字符串</b>传输（{@code §9.4}），金额同理。
 *
 * <p>★ {@code sale_date} 以<b>字符串</b>接收（{@code yyyy-MM-dd}），由控制器显式解析：解析失败统一
 * {@code SAL-6003}（{@code 400}）——若用 {@code LocalDate} 直接反序列化，Jackson 会在进入业务层前
 * 抛 {@code HttpMessageNotReadableException}，无法落到 {@code SAL-6003}。
 *
 * <p>★ 幂等键来自请求头 {@code Idempotency-Key}（可选；缺失时按业务键派生），本 DTO 不含。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SalesDailyRequest {

    private Long skuId;
    private Long warehouseId;

    /** 业务日期字符串（{@code yyyy-MM-dd}）；非法 / 缺失 → {@code SAL-6003}。 */
    private String saleDate;

    /** 净销售数量（默认 0；为负 → {@code SAL-6002}）。 */
    private BigDecimal qty;

    /** 退货数量（默认 0；为负 → {@code COM-0001}，DB CHECK 兜底）。 */
    private BigDecimal returnQty;

    /** 销售金额（可空；为负 → {@code COM-0001}，DB CHECK 兜底）。 */
    private BigDecimal salesAmount;

    /** 订单数（可空；为负 → {@code COM-0001}，{@code INT UNSIGNED} 兜底）。 */
    private Integer orderCount;

    /** 来源导入批次（可空，逻辑关联）。 */
    private Long batchId;
}
