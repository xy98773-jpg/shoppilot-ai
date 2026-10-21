package com.shoppilot.purchasing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.purchasing.application.dto.GoodsReceiptDetail;
import com.shoppilot.purchasing.application.dto.GoodsReceiptFilter;
import com.shoppilot.purchasing.application.dto.GoodsReceiptSummary;
import com.shoppilot.purchasing.application.dto.PageQuery;
import com.shoppilot.purchasing.infrastructure.persistence.entity.GoodsReceiptEntity;
import com.shoppilot.purchasing.infrastructure.persistence.entity.GoodsReceiptItemEntity;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.GoodsReceiptItemMapper;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.GoodsReceiptMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 到货单查询服务（M6-T04 A4；{@code MODULE_BOUNDARIES §2.9} 明列）。
 *
 * <p>★ <b>只读</b>：本服务不写任何表（登记 / 编辑 / 作废在 {@code GoodsReceiptAppService}）。
 *
 * <p>★ <b>团队隔离走租户线</b>：{@code goods_receipts} / {@code goods_receipt_items} 均带
 * {@code team_id} 且不在 {@code TenantTables.WITHOUT_TEAM_ID} 黑名单 ⇒ 租户线自动追加团队条件
 * ⇒ <b>跨团队与不存在同表现</b>（404 {@code PO-9005}，不区分存在性，不泄漏他团队数据）。
 *
 * <p>★ <b>分页响应字段名是 {@code list}（不是 {@code items}）</b>——{@code common/web/PageResult}，
 * 契约 §2.2。
 */
@Service
public class GoodsReceiptQueryService {

    private final GoodsReceiptMapper receiptMapper;
    private final GoodsReceiptItemMapper receiptItemMapper;

    public GoodsReceiptQueryService(GoodsReceiptMapper receiptMapper,
                                    GoodsReceiptItemMapper receiptItemMapper) {
        this.receiptMapper = receiptMapper;
        this.receiptItemMapper = receiptItemMapper;
    }

    /**
     * 到货单详情（含明细行）。
     *
     * @param receiptId 到货单 ID
     * @return 到货单详情（数量 scale=3 / 单价 scale=4 / 金额 scale=2，字符串输出）
     * @throws BizException {@code PO-9005}（404）不存在 / 跨团队
     */
    @Transactional(readOnly = true)
    public GoodsReceiptDetail getReceipt(long receiptId) {
        GoodsReceiptEntity entity = receiptMapper.selectById(receiptId);
        if (entity == null) {
            // 租户线使「跨团队」与「不存在」同表现（不泄漏他团队是否存在）
            throw new BizException(ErrorCodes.PO_9005, "到货单不存在");
        }
        List<GoodsReceiptItemEntity> items = receiptItemMapper.selectList(
                new LambdaQueryWrapper<GoodsReceiptItemEntity>()
                        .eq(GoodsReceiptItemEntity::getGoodsReceiptId, entity.getId())
                        .orderByAsc(GoodsReceiptItemEntity::getId));
        return toDetail(entity, items);
    }

    /**
     * 到货单列表（分页；排序固定 {@code created_at DESC, id DESC}）。
     *
     * @param filter 过滤条件（各字段可空 = 不参与过滤）
     * @param page   分页参数（{@code page} ≥ 1、{@code pageSize} 1..200，越界 ⇒ {@code COM-0007}）
     * @return 分页结果（字段名 {@code list}）
     */
    @Transactional(readOnly = true)
    public PageResult<GoodsReceiptSummary> listReceipts(GoodsReceiptFilter filter, PageQuery page) {
        PageQuery query = page == null ? new PageQuery() : page;
        // 越界在此统一拦（契约 §2：非法分页参数 ⇒ COM-0007）
        PageQuery.of(query.getPage(), query.getPageSize());

        LambdaQueryWrapper<GoodsReceiptEntity> wrapper = new LambdaQueryWrapper<>();
        if (filter != null) {
            if (filter.getPurchaseOrderId() != null) {
                wrapper.eq(GoodsReceiptEntity::getPurchaseOrderId, filter.getPurchaseOrderId());
            }
            if (filter.getStatus() != null && !filter.getStatus().isBlank()) {
                wrapper.eq(GoodsReceiptEntity::getStatus, filter.getStatus());
            }
            if (filter.getWarehouseId() != null) {
                wrapper.eq(GoodsReceiptEntity::getWarehouseId, filter.getWarehouseId());
            }
            if (filter.getReceiptNoLike() != null && !filter.getReceiptNoLike().isBlank()) {
                wrapper.like(GoodsReceiptEntity::getReceiptNo, filter.getReceiptNoLike());
            }
            if (filter.getCreatedFrom() != null) {
                wrapper.ge(GoodsReceiptEntity::getCreatedAt, filter.getCreatedFrom());
            }
            if (filter.getCreatedTo() != null) {
                wrapper.le(GoodsReceiptEntity::getCreatedAt, filter.getCreatedTo());
            }
        }
        wrapper.orderByDesc(GoodsReceiptEntity::getCreatedAt)
                .orderByDesc(GoodsReceiptEntity::getId);

        Page<GoodsReceiptEntity> result = receiptMapper.selectPage(
                Page.of(query.getPage(), query.getPageSize()), wrapper);
        List<GoodsReceiptSummary> list = new ArrayList<>();
        for (GoodsReceiptEntity entity : result.getRecords()) {
            list.add(toSummary(entity));
        }
        return PageResult.of(result.getTotal(), (int) result.getCurrent(), (int) result.getSize(), list);
    }

    private GoodsReceiptSummary toSummary(GoodsReceiptEntity e) {
        return new GoodsReceiptSummary(
                e.getId(), e.getReceiptNo(), e.getPurchaseOrderId(), e.getWarehouseId(),
                e.getStatus(), e.getReceiptDate(),
                GoodsReceiptDetail.qtyText(e.getTotalQty()),
                GoodsReceiptDetail.amountText(e.getTotalAmount()),
                e.getRemark(), e.getVersion(), e.getCreatedAt());
    }

    private GoodsReceiptDetail toDetail(GoodsReceiptEntity e, List<GoodsReceiptItemEntity> items) {
        List<GoodsReceiptDetail.Item> itemViews = new ArrayList<>();
        for (GoodsReceiptItemEntity item : items) {
            itemViews.add(new GoodsReceiptDetail.Item(
                    item.getId(), item.getPurchaseOrderItemId(), item.getSkuId(),
                    GoodsReceiptDetail.qtyText(item.getReceivedQty()),
                    GoodsReceiptDetail.priceText(item.getUnitPrice()),
                    GoodsReceiptDetail.amountText(item.getAmount()),
                    item.getBatchNo(), item.getRemark()));
        }
        return new GoodsReceiptDetail(
                e.getId(), e.getReceiptNo(), e.getPurchaseOrderId(), e.getWarehouseId(),
                e.getStatus(), e.getReceiptDate(),
                GoodsReceiptDetail.qtyText(e.getTotalQty()),
                GoodsReceiptDetail.amountText(e.getTotalAmount()),
                e.getRemark(), e.getVersion(), itemViews,
                e.getCreatedAt(), e.getUpdatedAt(), e.getCancelledAt(),
                e.getConfirmedAt(), e.getConfirmedBy());
    }
}
