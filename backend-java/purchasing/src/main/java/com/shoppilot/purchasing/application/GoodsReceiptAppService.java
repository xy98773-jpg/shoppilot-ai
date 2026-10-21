package com.shoppilot.purchasing.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.purchasing.application.dto.CreateGoodsReceiptRequest;
import com.shoppilot.purchasing.application.dto.GoodsReceiptCancelResponse;
import com.shoppilot.purchasing.application.dto.GoodsReceiptDetail;
import com.shoppilot.purchasing.application.dto.GoodsReceiptItemRequest;
import com.shoppilot.purchasing.application.dto.PurchaseOrderDetail;
import com.shoppilot.purchasing.application.dto.UpdateGoodsReceiptRequest;
import com.shoppilot.purchasing.infrastructure.persistence.entity.GoodsReceiptEntity;
import com.shoppilot.purchasing.infrastructure.persistence.entity.GoodsReceiptItemEntity;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.GoodsReceiptItemMapper;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.GoodsReceiptMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 到货单服务（M6-T04 A1–A3：登记 / 编辑 / 作废）。
 *
 * <p>★ <b>草稿绝不写库存（INV-03）</b>：本服务<b>不注入</b> {@code inventory} 的任何 Mapper /
 * Service，<b>不产生</b>任何 {@code inventory_transactions} 记录，<b>不触达</b>
 * {@code inventory_balances}。真正的入库只发生在 <b>M6-T05</b> 的
 * {@code POST /receipts/{id}/confirm}（本批不实现）。
 *
 * <p>★ <b>不写审计（R172）</b>：审计 35 条清单（{@code DATABASE_DESIGN §9.17}）<b>无</b>
 * 「到货单创建」「到货单编辑」动作 ⇒ 沿用 SSOT 现状，<b>不新增 {@code AuditAction} 枚举值</b>。
 * 唯一例外是 <b>作废</b>（A3）——{@code GOODS_RECEIPT_CANCEL}（§9.17 #27）已存在于清单中。
 *
 * <p>★ <b>与 M6-T03 的唯一依赖</b>：读采购单一律经 {@link PurchaseOrderQueryService#getOrder(long)}，
 * <b>不注入</b> {@code PurchaseOrderMapper} / {@code PurchaseOrderItemMapper}
 * （跨类 Mapper 红线；{@code MODULE_BOUNDARIES §2.9}）。
 */
@Service
public class GoodsReceiptAppService {

    /** 到货单号最大重试次数（撞 {@code uk_gr_team_no} 时的有限重试；不把 1062 抛给用户）。 */
    private static final int RECEIPT_NO_MAX_RETRIES = 3;

    /** 备注长度上限（{@code remark VARCHAR(500)}）。 */
    private static final int REMARK_MAX_LENGTH = 500;

    /** 行备注长度上限（{@code goods_receipt_items.remark VARCHAR(255)}）。 */
    private static final int ITEM_REMARK_MAX_LENGTH = 255;

    /** 批号长度上限（{@code batch_no VARCHAR(64)}）。 */
    private static final int BATCH_NO_MAX_LENGTH = 64;

    /** 单价小数位上限（{@code unit_price DECIMAL(18,4)}）。 */
    private static final int UNIT_PRICE_MAX_SCALE = 4;

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_CANCELLED = "CANCELLED";

    /** 允许登记到货的采购单源态（{@code DESIGN_BASIS §9.13} 补充规则 3）。 */
    private static final String PO_STATUS_ORDERED = "ORDERED";
    private static final String PO_STATUS_PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED";

    private static final String BIZ_TYPE = "GOODS_RECEIPT";

    private final GoodsReceiptMapper receiptMapper;
    private final GoodsReceiptItemMapper receiptItemMapper;
    private final PurchaseOrderQueryService purchaseOrderQueryService;
    private final GoodsReceiptQueryService goodsReceiptQueryService;
    private final AuditWriteService auditWriteService;

    public GoodsReceiptAppService(GoodsReceiptMapper receiptMapper,
                                  GoodsReceiptItemMapper receiptItemMapper,
                                  PurchaseOrderQueryService purchaseOrderQueryService,
                                  GoodsReceiptQueryService goodsReceiptQueryService,
                                  AuditWriteService auditWriteService) {
        this.receiptMapper = receiptMapper;
        this.receiptItemMapper = receiptItemMapper;
        this.purchaseOrderQueryService = purchaseOrderQueryService;
        this.goodsReceiptQueryService = goodsReceiptQueryService;
        this.auditWriteService = auditWriteService;
    }

    // =====================================================================
    // A1 登记到货（草稿）
    // =====================================================================

    /**
     * A1 登记到货（草稿）：{@code POST /api/v1/purchasing/receipts}。
     *
     * <p>角色 {@code OPERATOR}+（契约 §11.9）。★ <b>不写库存（INV-03）</b>；
     * ★ <b>不写审计（R172）</b>。
     *
     * <p><b>校验顺序</b>（令 A1 1→6）：
     * <ol>
     *   <li>采购单存在且属本团队 ⇒ 否则 {@code PO-9004}（404）；</li>
     *   <li>采购单状态须为 {@code ORDERED} / {@code PARTIALLY_RECEIVED}
     *       ⇒ 否则 {@code PO-9003}（409）；</li>
     *   <li>{@code warehouse_id} 必须等于采购单的 {@code warehouse_id} ⇒ 否则 {@code COM-0001}（400）；</li>
     *   <li>{@code receipt_date} 必填 ⇒ 否则 {@code COM-0001}（400）；</li>
     *   <li>{@code items} 非空 ⇒ 否则 {@code PO-9006}（400）；</li>
     *   <li>逐条明细：归属 / 数量 / 单价 / <b>超额守卫</b>（⇒ {@code PO-9002} 409，带 details）。</li>
     * </ol>
     *
     * <p><b>幂等</b>：同键同体 ⇒ 返回首次创建的那张到货单（{@code receipt_no}/{@code id} 不变）；
     * 异体 ⇒ {@code COM-0009}（409）；并发撞 {@code uk_gr_team_idem} ⇒ {@code COM-0009}。
     *
     * @param request        请求体
     * @param idempotencyKey {@code Idempotency-Key} 请求头（必填，缺失 ⇒ {@code COM-0010}）
     * @return 新建到货单（草稿）详情
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public GoodsReceiptDetail create(CreateGoodsReceiptRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BizException.withDetails(ErrorCodes.COM_0010, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("Idempotency-Key", "Idempotency-Key 请求头必填")));
        }
        if (request == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("body", "请求体必填")));
        }
        long teamId = currentTeamId();

        // ① 采购单存在且属本团队（跨团队 ⇒ PO-9004 404，隔离优先）
        if (request.getPurchaseOrderId() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("purchase_order_id", "purchase_order_id 必填")));
        }
        GoodsReceiptPoView po = readPurchaseOrder(request.getPurchaseOrderId());

        // ② 采购单状态守卫
        if (!PO_STATUS_ORDERED.equals(po.status()) && !PO_STATUS_PARTIALLY_RECEIVED.equals(po.status())) {
            throw new BizException(ErrorCodes.PO_9003, "当前采购单状态不允许登记到货");
        }

        // ③ 入库仓库必须等于采购单的 warehouse_id
        if (request.getWarehouseId() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("warehouse_id", "warehouse_id 必填")));
        }
        if (!request.getWarehouseId().equals(po.warehouseId())) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("warehouse_id",
                            "warehouse_id 必须等于采购单的入库仓库")));
        }

        // ④ 到货业务日期必填
        if (request.getReceiptDate() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("receipt_date", "receipt_date 必填")));
        }

        // ⑤ 明细非空
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw BizException.withDetails(ErrorCodes.PO_9006, "到货单至少需要一条明细",
                    List.of(ApiErrorDetail.of("items", "明细行不能为空")));
        }

        // ⑥ 逐条明细校验（含超额守卫）
        List<PreparedItem> prepared = prepareItems(request.getItems(), po, 0);

        // 幂等：同键同体 ⇒ 返回首次那张；异体 ⇒ COM-0009
        GoodsReceiptEntity existing = findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            if (!sameBody(existing, request, prepared)) {
                throw new BizException(ErrorCodes.COM_0009, "幂等键已用于不同的请求内容");
            }
            return goodsReceiptQueryService.getReceipt(existing.getId());
        }

        // 写入：单头
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long operatorId = currentUserId();
        GoodsReceiptEntity receipt = new GoodsReceiptEntity();
        receipt.setPurchaseOrderId(request.getPurchaseOrderId());
        receipt.setWarehouseId(request.getWarehouseId());
        receipt.setStatus(STATUS_DRAFT);
        receipt.setReceiptDate(request.getReceiptDate());
        receipt.setIdempotencyKey(idempotencyKey);
        receipt.setRemark(request.getRemark());
        receipt.setOperatorId(operatorId);
        receipt.setTotalQty(sumQty(prepared));
        receipt.setTotalAmount(sumAmount(prepared));
        receipt.setCreatedBy(operatorId);
        receipt.setUpdatedBy(operatorId);
        insertReceiptWithNoRetry(receipt);

        // 写入：明细
        for (PreparedItem item : prepared) {
            GoodsReceiptItemEntity line = new GoodsReceiptItemEntity();
            line.setGoodsReceiptId(receipt.getId());
            line.setPurchaseOrderItemId(item.purchaseOrderItemId());
            line.setSkuId(item.skuId());
            line.setReceivedQty(item.receivedQty());
            line.setUnitPrice(item.unitPrice());
            line.setAmount(item.amount());
            line.setBatchNo(item.batchNo());
            line.setRemark(item.remark());
            line.setCreatedBy(operatorId);
            receiptItemMapper.insert(line);
        }
        return goodsReceiptQueryService.getReceipt(receipt.getId());
    }

    // =====================================================================
    // A2 编辑到货草稿
    // =====================================================================

    /**
     * A2 编辑到货草稿：{@code PATCH /api/v1/purchasing/receipts/{id}}。
     *
     * <p>角色 {@code OPERATOR}+。★ <b>非 {@code DRAFT} ⇒ {@code PO-9008}（409）</b>。
     * ★ <b>不写库存（INV-03）</b>；★ <b>不写审计（R172）</b>。
     *
     * <p><b>可改</b>：{@code items[]}、{@code remark}；
     * <b>不可改</b>：{@code purchase_order_id} / {@code warehouse_id} / {@code receipt_date} /
     * {@code idempotency_key} / {@code status} / {@code total_*}（服务端同事务重算）。
     *
     * <p>★ <b>明细编辑语义＝增量 upsert（不是「整组替换」）</b>，依据 SSOT
     * {@code DATABASE_DESIGN §5.21}：{@code goods_receipt_items}「不物理删除」且
     * {@code received_qty}/{@code unit_price}/{@code amount}/{@code batch_no}/{@code remark}
     * 「仅到货单 DRAFT 时可改」⇒ 请求中已存在的行 <b>UPDATE</b>、本单尚无的行 <b>INSERT</b>、
     * 请求未提及的旧行 <b>保留</b>（不能删行）。汇总按<b>本单最终全部行</b>重算。
     *
     * <p><b>乐观锁</b>：{@code expected_version} 与库内不一致 ⇒ {@code PO-9008}（409）；
     * 不传则用读到的版本做同语句 CAS。
     *
     * @param receiptId 到货单 ID
     * @param request   请求体
     * @return 更新后的到货单详情
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public GoodsReceiptDetail update(long receiptId, UpdateGoodsReceiptRequest request) {
        GoodsReceiptEntity receipt = requireDraft(receiptId);
        if (request == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("body", "请求体必填")));
        }
        if (request.getExpectedVersion() != null
                && !request.getExpectedVersion().equals(receipt.getVersion())) {
            throw new BizException(ErrorCodes.PO_9008, "到货单已被修改，请重试");
        }
        if (request.getRemark() == null && request.getItems() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("body", "至少提供一个可修改字段")));
        }
        if (request.getRemark() != null && request.getRemark().length() > REMARK_MAX_LENGTH) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("remark", "remark 长度不得超过 500 字符")));
        }

        // 明细：整组替换（校验同登记 A1 第 5–6 条）
        if (request.getItems() != null) {
            if (request.getItems().isEmpty()) {
                throw BizException.withDetails(ErrorCodes.PO_9006, "到货单至少需要一条明细",
                        List.of(ApiErrorDetail.of("items", "明细行不能为空")));
            }
            GoodsReceiptPoView po = readPurchaseOrder(receipt.getPurchaseOrderId());
            List<PreparedItem> prepared = prepareItems(request.getItems(), po, 0);
            replaceItems(receipt, prepared);
        } else {
            // 未传 items ⇒ 明细不动，但汇总仍按现有明细重算（幂等无副作用）
            List<GoodsReceiptItemEntity> current = receiptItemMapper.selectList(
                    Wrappers.<GoodsReceiptItemEntity>lambdaQuery()
                            .eq(GoodsReceiptItemEntity::getGoodsReceiptId, receipt.getId())
                            .orderByAsc(GoodsReceiptItemEntity::getId));
            recalcTotals(receipt, current);
        }

        // 单头更新（状态守卫 + 乐观锁：eq(id).eq(status,源态).eq(version,读到的)）
        Long operatorId = currentUserId();
        LambdaUpdateWrapper<GoodsReceiptEntity> updater =
                Wrappers.<GoodsReceiptEntity>lambdaUpdate()
                        .eq(GoodsReceiptEntity::getId, receipt.getId())
                        .eq(GoodsReceiptEntity::getStatus, STATUS_DRAFT)
                        .eq(GoodsReceiptEntity::getVersion, receipt.getVersion())
                        .set(GoodsReceiptEntity::getTotalQty, receipt.getTotalQty())
                        .set(GoodsReceiptEntity::getTotalAmount, receipt.getTotalAmount())
                        .set(GoodsReceiptEntity::getVersion, receipt.getVersion() + 1)
                        .set(GoodsReceiptEntity::getUpdatedBy, operatorId);
        if (request.getRemark() != null) {
            updater.set(GoodsReceiptEntity::getRemark, request.getRemark());
        }
        int affected = receiptMapper.update(null, updater);
        if (affected == 0) {
            throw new BizException(ErrorCodes.PO_9008, "到货单已被修改，请重试");
        }
        return goodsReceiptQueryService.getReceipt(receipt.getId());
    }

    // =====================================================================
    // A3 作废到货草稿
    // =====================================================================

    /**
     * A3 作废到货草稿：{@code POST /api/v1/purchasing/receipts/{id}/cancel}。
     *
     * <p>角色 {@code OPERATOR}+。★ <b>仅 {@code DRAFT} 可作废</b>；非 {@code DRAFT}
     * ⇒ {@code PO-9008}（409）——{@code CONFIRMED} 的到货单<b>不得</b>直接作废
     * （必须走冲销 {@code REVERSAL}，属 M6-T08，本批不做）。
     *
     * <p>★ 写审计 {@code GOODS_RECEIPT_CANCEL}（§9.17 #27，{@code recordInBusinessTx}）。
     * ★ <b>不写库存（INV-03）</b>。
     *
     * @param receiptId 到货单 ID
     * @return 作废结果
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public GoodsReceiptCancelResponse cancel(long receiptId) {
        GoodsReceiptEntity receipt = requireDraft(receiptId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long operatorId = currentUserId();
        int affected = receiptMapper.update(null,
                Wrappers.<GoodsReceiptEntity>lambdaUpdate()
                        .eq(GoodsReceiptEntity::getId, receipt.getId())
                        .eq(GoodsReceiptEntity::getStatus, STATUS_DRAFT)
                        .eq(GoodsReceiptEntity::getVersion, receipt.getVersion())
                        .set(GoodsReceiptEntity::getStatus, STATUS_CANCELLED)
                        .set(GoodsReceiptEntity::getCancelledAt, now)
                        .set(GoodsReceiptEntity::getVersion, receipt.getVersion() + 1)
                        .set(GoodsReceiptEntity::getUpdatedBy, operatorId));
        if (affected == 0) {
            throw new BizException(ErrorCodes.PO_9008, "到货单已被修改，请重试");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", STATUS_CANCELLED);
        after.put("cancelled_at", now.toString());
        recordAudit(receipt.getId(), after, "到货单已作废：" + receipt.getReceiptNo());
        return new GoodsReceiptCancelResponse(
                receipt.getId(), receipt.getReceiptNo(), STATUS_CANCELLED, now,
                receipt.getVersion() + 1);
    }

    // =====================================================================
    // 内部：校验与写入
    // =====================================================================

    /** 读采购单视图（跨团队 ⇒ PO-9004）。 */
    private GoodsReceiptPoView readPurchaseOrder(long purchaseOrderId) {
        PurchaseOrderDetail po = purchaseOrderQueryService.getOrder(purchaseOrderId);
        Map<Long, PurchaseOrderDetail.LineItem> lines = new LinkedHashMap<>();
        for (PurchaseOrderDetail.LineItem line : po.items()) {
            lines.put(line.id(), line);
        }
        return new GoodsReceiptPoView(po.status(), po.warehouseId(), lines);
    }

    /** 逐条明细校验 + 归一（含超额守卫）。 */
    private List<PreparedItem> prepareItems(List<GoodsReceiptItemRequest> inputs,
                                           GoodsReceiptPoView po, int baseIndex) {
        List<PreparedItem> prepared = new ArrayList<>();
        Set<Long> seenPoItemIds = new LinkedHashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            GoodsReceiptItemRequest input = inputs.get(i);
            int index = baseIndex + i;
            if (input.getPurchaseOrderItemId() == null) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("items[" + index + "].purchase_order_item_id",
                                "purchase_order_item_id 必填")));
            }
            if (!seenPoItemIds.add(input.getPurchaseOrderItemId())) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("items[" + index + "].purchase_order_item_id",
                                "同一采购明细行在本次请求中重复出现")));
            }
            PurchaseOrderDetail.LineItem poLine = po.lines().get(input.getPurchaseOrderItemId());
            if (poLine == null) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("items[" + index + "].purchase_order_item_id",
                                "明细行必须属于该采购单")));
            }
            if (input.getReceivedQty() == null) {
                throw BizException.withDetails(ErrorCodes.PO_9012, "到货数量必须为正",
                        List.of(ApiErrorDetail.of("items[" + index + "].received_qty",
                                "received_qty 必填")));
            }
            BigDecimal receivedQty = input.getReceivedQty().setScale(3, RoundingMode.HALF_UP);
            if (receivedQty.signum() <= 0) {
                throw BizException.withDetails(ErrorCodes.PO_9012, "到货数量必须为正",
                        List.of(ApiErrorDetail.of("items[" + index + "].received_qty",
                                "received_qty 必须大于 0")));
            }
            BigDecimal unitPrice = input.getUnitPrice();
            if (unitPrice != null) {
                if (unitPrice.signum() < 0) {
                    throw BizException.withDetails(ErrorCodes.PO_9011, "单价非法",
                            List.of(ApiErrorDetail.of("items[" + index + "].unit_price",
                                    "单价不得为负")));
                }
                if (unitPrice.stripTrailingZeros().scale() > UNIT_PRICE_MAX_SCALE) {
                    throw BizException.withDetails(ErrorCodes.PO_9011, "单价非法",
                            List.of(ApiErrorDetail.of("items[" + index + "].unit_price",
                                    "单价小数位不得超过 4")));
                }
                unitPrice = unitPrice.setScale(UNIT_PRICE_MAX_SCALE, RoundingMode.HALF_UP);
            }
            if (input.getBatchNo() != null && input.getBatchNo().length() > BATCH_NO_MAX_LENGTH) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("items[" + index + "].batch_no",
                                "batch_no 长度不得超过 64 字符")));
            }
            if (input.getRemark() != null && input.getRemark().length() > ITEM_REMARK_MAX_LENGTH) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(ApiErrorDetail.of("items[" + index + "].remark",
                                "行备注长度不得超过 255 字符")));
            }

            // ★ 超额守卫：本次登记量 + 该明细已收量（仅 CONFIRMED 到货单计入）> ordered_qty
            BigDecimal orderedQty = new BigDecimal(poLine.orderedQty());
            BigDecimal alreadyReceived = new BigDecimal(poLine.receivedQty());
            BigDecimal pending = orderedQty.subtract(alreadyReceived);
            if (pending.signum() < 0) {
                pending = BigDecimal.ZERO.setScale(3);
            }
            if (receivedQty.compareTo(pending) > 0) {
                throw BizException.withDetails(ErrorCodes.PO_9002, "到货数量超过待收数量",
                        List.of(new ApiErrorDetail(
                                "items[" + index + "].received_qty",
                                "待收数量为 " + pending.setScale(3, RoundingMode.HALF_UP).toPlainString()
                                        + "，本次登记 " + receivedQty.toPlainString(),
                                receivedQty.toPlainString())));
            }
            BigDecimal amount = (unitPrice == null) ? null
                    : receivedQty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            prepared.add(new PreparedItem(input.getPurchaseOrderItemId(), poLine.skuId(),
                    receivedQty, unitPrice, amount, input.getBatchNo(), input.getRemark()));
        }
        return prepared;
    }

    /**
     * 明细编辑：<b>增量 upsert</b>（不是「整组替换」）。
     *
     * <p>★ <b>口径依据 SSOT</b>（{@code DATABASE_DESIGN §5.21} 更新规则）：
     * {@code goods_receipt_items} 的更新规则是「到货单 {@code CONFIRMED} 后不可变；<b>不物理删除</b>」，
     * 且字段级规则明写 {@code received_qty}/{@code unit_price}/{@code amount}/{@code batch_no}/
     * {@code remark} 「<b>仅到货单 DRAFT 时可改</b>」⇒
     * <ol>
     *   <li>请求中已存在于本单的 {@code purchase_order_item_id} ⇒ <b>UPDATE</b> 该行（DRAFT 可改）；</li>
     *   <li>请求中本单尚不存在的 {@code purchase_order_item_id} ⇒ <b>INSERT</b> 新行
     *       （唯一约束 {@code uk_gri_receipt_poi} 允许一张到货单含多条不同采购明细）；</li>
     *   <li>本单已有但请求<b>未提及</b>的行 ⇒ <b>保留不动</b>（不物理删除 ⇒ 不能「删行」）。</li>
     * </ol>
     *
     * <p>⇒ 汇总 {@code total_qty}/{@code total_amount} 按<b>本单最终全部行</b>重算
     * （= 保留行 + 更新行 + 新增行），不是只按请求里的行。
     */
    private void replaceItems(GoodsReceiptEntity receipt, List<PreparedItem> prepared) {
        List<GoodsReceiptItemEntity> current = receiptItemMapper.selectList(
                Wrappers.<GoodsReceiptItemEntity>lambdaQuery()
                        .eq(GoodsReceiptItemEntity::getGoodsReceiptId, receipt.getId())
                        .orderByAsc(GoodsReceiptItemEntity::getId));
        Map<Long, GoodsReceiptItemEntity> currentByPoItem = new LinkedHashMap<>();
        for (GoodsReceiptItemEntity line : current) {
            currentByPoItem.put(line.getPurchaseOrderItemId(), line);
        }
        Long operatorId = currentUserId();
        for (PreparedItem item : prepared) {
            GoodsReceiptItemEntity existing = currentByPoItem.get(item.purchaseOrderItemId());
            if (existing != null) {
                // ★ DRAFT 可改（§5.21）：本表无 version 列（集合 B）⇒ 无行级乐观锁；
                //   并发保护由父单 goods_receipts 的 version CAS 提供（同事务，父单 CAS 失败整体回滚）
                receiptItemMapper.update(null, Wrappers.<GoodsReceiptItemEntity>lambdaUpdate()
                        .eq(GoodsReceiptItemEntity::getId, existing.getId())
                        .eq(GoodsReceiptItemEntity::getGoodsReceiptId, receipt.getId())
                        .set(GoodsReceiptItemEntity::getReceivedQty, item.receivedQty())
                        .set(GoodsReceiptItemEntity::getUnitPrice, item.unitPrice())
                        .set(GoodsReceiptItemEntity::getAmount, item.amount())
                        .set(GoodsReceiptItemEntity::getBatchNo, item.batchNo())
                        .set(GoodsReceiptItemEntity::getRemark, item.remark()));
            } else {
                GoodsReceiptItemEntity line = new GoodsReceiptItemEntity();
                line.setGoodsReceiptId(receipt.getId());
                line.setPurchaseOrderItemId(item.purchaseOrderItemId());
                line.setSkuId(item.skuId());
                line.setReceivedQty(item.receivedQty());
                line.setUnitPrice(item.unitPrice());
                line.setAmount(item.amount());
                line.setBatchNo(item.batchNo());
                line.setRemark(item.remark());
                line.setCreatedBy(operatorId);
                receiptItemMapper.insert(line);
            }
        }
        // 汇总按本单最终全部行重算（未被请求提及的旧行也计入）
        List<GoodsReceiptItemEntity> finalLines = receiptItemMapper.selectList(
                Wrappers.<GoodsReceiptItemEntity>lambdaQuery()
                        .eq(GoodsReceiptItemEntity::getGoodsReceiptId, receipt.getId())
                        .orderByAsc(GoodsReceiptItemEntity::getId));
        recalcTotals(receipt, finalLines);
    }

    private void recalcTotals(GoodsReceiptEntity receipt, List<GoodsReceiptItemEntity> lines) {
        BigDecimal qty = BigDecimal.ZERO.setScale(3);
        BigDecimal amount = BigDecimal.ZERO.setScale(2);
        for (GoodsReceiptItemEntity line : lines) {
            if (line.getReceivedQty() != null) {
                qty = qty.add(line.getReceivedQty());
            }
            if (line.getAmount() != null) {
                amount = amount.add(line.getAmount());
            }
        }
        receipt.setTotalQty(qty);
        receipt.setTotalAmount(amount);
    }

    private BigDecimal sumQty(List<PreparedItem> items) {
        BigDecimal qty = BigDecimal.ZERO.setScale(3);
        for (PreparedItem item : items) {
            qty = qty.add(item.receivedQty());
        }
        return qty;
    }

    private BigDecimal sumAmount(List<PreparedItem> items) {
        BigDecimal amount = BigDecimal.ZERO.setScale(2);
        for (PreparedItem item : items) {
            if (item.amount() != null) {
                amount = amount.add(item.amount());
            }
        }
        return amount;
    }

    /** 读到货单并校验其为 {@code DRAFT}（非 DRAFT ⇒ PO-9008 409）。 */
    private GoodsReceiptEntity requireDraft(long receiptId) {
        GoodsReceiptEntity receipt = receiptMapper.selectById(receiptId);
        if (receipt == null) {
            throw new BizException(ErrorCodes.PO_9005, "到货单不存在");
        }
        if (!STATUS_DRAFT.equals(receipt.getStatus())) {
            throw new BizException(ErrorCodes.PO_9008, "到货单已确认，不可修改");
        }
        return receipt;
    }

    /** 幂等：同键同体判定（比采购单主体 + 明细集合 + 仓库 + 日期）。 */
    private boolean sameBody(GoodsReceiptEntity existing, CreateGoodsReceiptRequest request,
                              List<PreparedItem> prepared) {
        if (!request.getPurchaseOrderId().equals(existing.getPurchaseOrderId())
                || !request.getWarehouseId().equals(existing.getWarehouseId())
                || !request.getReceiptDate().equals(existing.getReceiptDate())) {
            return false;
        }
        List<GoodsReceiptItemEntity> lines = receiptItemMapper.selectList(
                Wrappers.<GoodsReceiptItemEntity>lambdaQuery()
                        .eq(GoodsReceiptItemEntity::getGoodsReceiptId, existing.getId()));
        if (lines.size() != prepared.size()) {
            return false;
        }
        Map<Long, BigDecimal> existingByPoItem = new LinkedHashMap<>();
        for (GoodsReceiptItemEntity line : lines) {
            existingByPoItem.put(line.getPurchaseOrderItemId(), line.getReceivedQty());
        }
        for (PreparedItem item : prepared) {
            BigDecimal old = existingByPoItem.get(item.purchaseOrderItemId());
            if (old == null || old.compareTo(item.receivedQty()) != 0) {
                return false;
            }
        }
        return true;
    }

    private GoodsReceiptEntity findByIdempotencyKey(String key) {
        return receiptMapper.selectOne(Wrappers.<GoodsReceiptEntity>lambdaQuery()
                .eq(GoodsReceiptEntity::getIdempotencyKey, key)
                .last("LIMIT 1"));
    }

    /** 插入单头；撞 {@code uk_gr_team_no} 时有限重试（≤3），撞 {@code uk_gr_team_idem} ⇒ COM-0009。 */
    private void insertReceiptWithNoRetry(GoodsReceiptEntity receipt) {
        DuplicateKeyException lastError = null;
        for (int attempt = 0; attempt < RECEIPT_NO_MAX_RETRIES; attempt++) {
            receipt.setReceiptNo(nextReceiptNo());
            try {
                receiptMapper.insert(receipt);
                return;
            } catch (DuplicateKeyException e) {
                lastError = e;
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (message.contains("uk_gr_team_idem")) {
                    // 并发同键 ⇒ 幂等语义 ⇒ COM-0009
                    throw new BizException(ErrorCodes.COM_0009, "幂等键已用于不同的请求内容");
                }
            }
        }
        throw new BizException(ErrorCodes.PO_9013,
                "到货单号生成冲突，请重试", lastError);
    }

    /** 生成 {@code GR-yyyyMMdd-6位序号}（当日 UTC；同团队当日最大序号 + 1）。 */
    private String nextReceiptNo() {
        String prefix = "GR-" + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "") + "-";
        List<GoodsReceiptEntity> today = receiptMapper.selectList(
                Wrappers.<GoodsReceiptEntity>lambdaQuery()
                        .likeRight(GoodsReceiptEntity::getReceiptNo, prefix)
                        .orderByDesc(GoodsReceiptEntity::getReceiptNo)
                        .last("LIMIT 1"));
        long next = 1L;
        if (!today.isEmpty() && today.get(0).getReceiptNo() != null) {
            String tail = today.get(0).getReceiptNo().substring(prefix.length());
            try {
                next = Long.parseLong(tail) + 1L;
            } catch (NumberFormatException ignored) {
                next = 1L;
            }
        }
        return prefix + String.format("%06d", next);
    }

    private void recordAudit(Long bizId, Map<String, Object> after, String summary) {
        TeamContext.Snapshot snapshot = TeamContext.get();
        auditWriteService.recordInBusinessTx(AuditEvent.builder()
                .teamId(currentTeamId())
                .action(AuditAction.GOODS_RECEIPT_CANCEL)
                .bizType(BIZ_TYPE)
                .bizId(bizId)
                .operatorId(snapshot == null ? null : snapshot.userId())
                .operatorName(snapshot == null ? null : snapshot.username())
                .operatorRole(snapshot == null || snapshot.roles().isEmpty()
                        ? null : snapshot.roles().get(0))
                .result(AuditResult.SUCCESS)
                .changeSummary(summary)
                .after(after)
                .build());
    }

    private static Long currentUserId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        return snapshot == null ? null : snapshot.userId();
    }

    private static long currentTeamId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        if (snapshot == null || snapshot.teamId() == null) {
            throw new BizException(ErrorCodes.TEAM_2001, "缺少团队上下文");
        }
        return snapshot.teamId();
    }

    // =====================================================================
    // 内部视图
    // =====================================================================

    /** 采购单视图（只取到货校验需要的字段）。 */
    private record GoodsReceiptPoView(String status, Long warehouseId,
                                      Map<Long, PurchaseOrderDetail.LineItem> lines) {
    }

    /** 校验通过、已归一的明细行。 */
    private record PreparedItem(Long purchaseOrderItemId, Long skuId, BigDecimal receivedQty,
                                 BigDecimal unitPrice, BigDecimal amount,
                                 String batchNo, String remark) {
    }
}
