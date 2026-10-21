package com.shoppilot.purchasing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.purchasing.application.dto.PageQuery;
import com.shoppilot.purchasing.application.dto.PurchaseOrderDetail;
import com.shoppilot.purchasing.application.dto.PurchaseOrderFilter;
import com.shoppilot.purchasing.application.dto.PurchaseOrderSummary;
import com.shoppilot.purchasing.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.shoppilot.purchasing.infrastructure.persistence.entity.PurchaseOrderItemEntity;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.PurchaseOrderItemMapper;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.PurchaseOrderMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采购单查询服务（M6-T03；{@code MODULE_BOUNDARIES §2.9} 明列）。
 *
 * <h2>★ 签名由项目负责人写死（{@code DESIGN_REVIEW §5.70.2}），M6-T04 按此调用</h2>
 * <pre>
 * public PurchaseOrderDetail getOrder(long poId);                       // 不存在/跨团队 ⇒ PO-9004
 * public PageResult&lt;PurchaseOrderSummary&gt; listOrders(PurchaseOrderFilter filter, PageQuery page);
 * </pre>
 *
 * <h2>团队隔离</h2>
 * <p>走租户线（{@code TeamContext} 注入 {@code team_id}）：<b>跨团队查不到</b>，
 * 与「不存在」同表现为 {@code PO-9004}（404）——不泄漏他团队是否存在。
 *
 * <h2>只读</h2>
 * <p>本类<b>不写任何表</b>（不含审计）：查询路径无写操作（{@code C3} 通用口径）。
 */
@Service
public class PurchaseOrderQueryService {

    /** 采购单状态白名单（{@code DESIGN_BASIS §9.13} 六值；列表过滤入参校验用）。 */
    private static final Set<String> PO_STATUSES = Set.of(
            "DRAFT", "PENDING", "ORDERED", "PARTIALLY_RECEIVED", "RECEIVED", "CANCELLED");

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper orderItemMapper;

    public PurchaseOrderQueryService(PurchaseOrderMapper orderMapper,
                                     PurchaseOrderItemMapper orderItemMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    /**
     * 采购单详情（含明细与到货进度）。
     *
     * @param poId 采购单 ID
     * @return 详情（{@code items[]} 按 {@code line_no} 升序）
     * @throws BizException {@code PO-9004}（404）不存在 / 跨团队
     */
    @Transactional(readOnly = true)
    public PurchaseOrderDetail getOrder(long poId) {
        PurchaseOrderEntity order = orderMapper.selectById(poId);
        if (order == null) {
            // 租户线使「跨团队」与「不存在」同表现（不泄漏他团队是否存在）
            throw new BizException(ErrorCodes.PO_9004, "采购单不存在");
        }
        List<PurchaseOrderItemEntity> lines = orderItemMapper.selectList(
                new LambdaQueryWrapper<PurchaseOrderItemEntity>()
                        .eq(PurchaseOrderItemEntity::getPurchaseOrderId, order.getId())
                        .orderByAsc(PurchaseOrderItemEntity::getLineNo));
        return toDetail(order, lines);
    }

    /**
     * 采购单列表（分页；排序固定 {@code created_at DESC, id DESC}）。
     *
     * <p>★ 分页响应字段名是 {@code list}（{@code common/web/PageResult}，契约 §2.2）——
     * <b>不是</b> {@code items}。
     *
     * @param filter 过滤条件（各字段可空 = 不参与过滤）
     * @param page   分页参数（{@code page} ≥ 1、{@code pageSize} 1..200，越界 ⇒ {@code COM-0007}）
     */
    @Transactional(readOnly = true)
    public PageResult<PurchaseOrderSummary> listOrders(PurchaseOrderFilter filter, PageQuery page) {
        PageQuery query = page == null ? new PageQuery() : page;
        // 越界在此统一拦（契约 §2：非法分页参数 ⇒ COM-0007）
        PageQuery.of(query.getPage(), query.getPageSize());

        LambdaQueryWrapper<PurchaseOrderEntity> wrapper = new LambdaQueryWrapper<>();
        if (filter != null) {
            if (filter.getStatus() != null) {
                if (!PO_STATUSES.contains(filter.getStatus())) {
                    throw BizException.withDetails(ErrorCodes.COM_0007, "请求参数校验失败",
                            List.of(com.shoppilot.common.web.ApiErrorDetail.of(
                                    "status", "status 不在允许取值内")));
                }
                wrapper.eq(PurchaseOrderEntity::getStatus, filter.getStatus());
            }
            if (filter.getSupplierId() != null) {
                wrapper.eq(PurchaseOrderEntity::getSupplierId, filter.getSupplierId());
            }
            if (filter.getWarehouseId() != null) {
                wrapper.eq(PurchaseOrderEntity::getWarehouseId, filter.getWarehouseId());
            }
            if (filter.getPoNoLike() != null && !filter.getPoNoLike().isBlank()) {
                wrapper.like(PurchaseOrderEntity::getPoNo, filter.getPoNoLike().trim());
            }
            if (filter.getCreatedFrom() != null) {
                wrapper.ge(PurchaseOrderEntity::getCreatedAt, filter.getCreatedFrom());
            }
            if (filter.getCreatedTo() != null) {
                wrapper.le(PurchaseOrderEntity::getCreatedAt, filter.getCreatedTo());
            }
        }
        wrapper.orderByDesc(PurchaseOrderEntity::getCreatedAt)
                .orderByDesc(PurchaseOrderEntity::getId);

        Page<PurchaseOrderEntity> result = orderMapper.selectPage(
                Page.of(query.getPage(), query.getPageSize()), wrapper);
        List<PurchaseOrderSummary> summaries = new ArrayList<>();
        for (PurchaseOrderEntity entity : result.getRecords()) {
            summaries.add(toSummary(entity));
        }
        return PageResult.of(result.getTotal(), query.getPage(), query.getPageSize(), summaries);
    }

    // =====================================================================
    // 内部装配
    // =====================================================================

    private PurchaseOrderSummary toSummary(PurchaseOrderEntity e) {
        return new PurchaseOrderSummary(
                e.getId(), e.getPoNo(), e.getStatus(), e.getSource(), e.getSupplierId(),
                e.getWarehouseId(), e.getExpectedArrivalDate(),
                PurchaseOrderDetail.qtyText(e.getTotalQty()),
                PurchaseOrderDetail.qtyText(e.getReceivedQty()),
                PurchaseOrderDetail.amountText(e.getTotalAmount()),
                e.getRemark(), e.getVersion(), e.getCreatedAt());
    }

    private PurchaseOrderDetail toDetail(PurchaseOrderEntity e,
                                         List<PurchaseOrderItemEntity> lines) {
        List<PurchaseOrderDetail.LineItem> items = new ArrayList<>();
        for (PurchaseOrderItemEntity line : lines) {
            items.add(new PurchaseOrderDetail.LineItem(
                    line.getId(), line.getLineNo(), line.getSkuId(), line.getProductId(),
                    line.getReplenishmentItemId(),
                    PurchaseOrderDetail.qtyText(line.getOrderedQty()),
                    PurchaseOrderDetail.qtyText(line.getReceivedQty()),
                    PurchaseOrderDetail.priceText(line.getUnitPrice()),
                    PurchaseOrderDetail.amountText(line.getAmount()),
                    line.getExpectedArrivalDate()));
        }
        return new PurchaseOrderDetail(
                e.getId(), e.getPoNo(), e.getStatus(), e.getSource(), e.getSupplierId(),
                e.getWarehouseId(), e.getExpectedArrivalDate(),
                PurchaseOrderDetail.qtyText(e.getTotalQty()),
                PurchaseOrderDetail.amountText(e.getTotalAmount()),
                PurchaseOrderDetail.qtyText(e.getReceivedQty()),
                e.getRemark(), e.getConfirmedBy(), e.getConfirmedAt(), e.getOrderedAt(),
                e.getCancelledAt(), e.getCancelReason(), e.getClosedAt(),
                e.getIdempotencyKey(), e.getVersion(), e.getCreatedAt(), items);
    }
}
