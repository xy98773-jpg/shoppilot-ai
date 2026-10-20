package com.shoppilot.purchasing.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.catalog.application.SupplierQueryService;
import com.shoppilot.catalog.application.WarehouseQueryService;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.inventory.application.InventoryWriteService;
import com.shoppilot.inventory.application.dto.AnchorLockResult;
import com.shoppilot.purchasing.application.dto.CreatePurchaseOrderFromReplenishmentRequest;
import com.shoppilot.purchasing.application.dto.PurchaseOrderFromReplenishmentResponse;
import com.shoppilot.purchasing.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.shoppilot.purchasing.infrastructure.persistence.entity.PurchaseOrderItemEntity;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.PurchaseOrderItemMapper;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.PurchaseOrderMapper;
import com.shoppilot.replenishment.application.ReplenishmentAppService;
import com.shoppilot.replenishment.application.ReplenishmentQueryService;
import com.shoppilot.replenishment.application.dto.ReplenishmentItemBrief;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采购单应用服务（M6-T02：由已审批建议生成采购单；{@code DATABASE_DESIGN §7.2 #8} 步骤 ⓪–⑩）。
 *
 * <h2>★ 步骤序不可调序</h2>
 * <p>{@code §7.2 #8} 的 ⓪–⑩ 是权威设计：<b>② 先加锁 → ③ 再检测 → ⑦ 再写入</b>。
 * 调序即产生 TOCTOU（检测到「无冲突」之后、写入之前，另一事务可能刚好转走同 SKU 的建议）。
 * 本类每个方法体段落都标注了步号，便于逐条比对。
 *
 * <h2>★ 跨模块只经服务接口</h2>
 * <p>{@code MODULE_BOUNDARIES §4.1} 规则 1：本类<b>不注入</b> {@code inventory} /
 * {@code replenishment} / {@code catalog} 的任何 Mapper，只调它们的服务接口。
 *
 * <h2>★ 本端点不写库存（{@code INV-03}）</h2>
 * <p>只建采购单，<b>不触达</b> {@code inventory_balances} / {@code inventory_transactions}；
 * {@code effective_in_transit_qty} 换口径在 M6-T03 的 {@code :order} 之后。
 */
@Service
public class PurchaseOrderAppService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderAppService.class);

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int REMARK_MAX_LENGTH = 500;
    private static final int PO_NO_MAX_RETRIES = 3;

    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_CONVERTED = "CONVERTED";

    private static final String PO_STATUS_DRAFT = "DRAFT";
    private static final String PO_SOURCE_REPLENISHMENT = "REPLENISHMENT";
    private static final String BIZ_TYPE = "PURCHASE_ORDER";

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper orderItemMapper;
    private final InventoryWriteService inventoryWriteService;
    private final ReplenishmentQueryService replenishmentQueryService;
    private final ReplenishmentAppService replenishmentAppService;
    private final WarehouseQueryService warehouseQueryService;
    private final SupplierQueryService supplierQueryService;
    private final AuditWriteService auditWriteService;

    public PurchaseOrderAppService(PurchaseOrderMapper orderMapper,
                                   PurchaseOrderItemMapper orderItemMapper,
                                   InventoryWriteService inventoryWriteService,
                                   ReplenishmentQueryService replenishmentQueryService,
                                   ReplenishmentAppService replenishmentAppService,
                                   WarehouseQueryService warehouseQueryService,
                                   SupplierQueryService supplierQueryService,
                                   AuditWriteService auditWriteService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.inventoryWriteService = inventoryWriteService;
        this.replenishmentQueryService = replenishmentQueryService;
        this.replenishmentAppService = replenishmentAppService;
        this.warehouseQueryService = warehouseQueryService;
        this.supplierQueryService = supplierQueryService;
        this.auditWriteService = auditWriteService;
    }

    // =====================================================================
    // 主流程：§7.2 #8 步骤 ⓪–⑩
    // =====================================================================

    /**
     * 由已审批建议生成采购单（草稿 DRAFT）。
     *
     * @param request        请求体（数量/单价/供应商一律取自建议，不接受客户端覆盖）
     * @param idempotencyKey {@code Idempotency-Key} 请求头（必填）
     * @return 采购单（草稿）详情
     */
    // ★ isolation = READ_COMMITTED（§5.67 P-1 必修）：并发同键抢 uk_po_team_idem 时，
    //   insert 抛 DuplicateKeyException 后的 findByIdempotencyKey 是重查——必须在 RC 下每条
    //   语句刷新 ReadView，才能看见并发行 ⇒ 正常落到 COM-0009(409)；RR 快照读看不见 ⇒
    //   会误判为 po_no 冲突并重试耗尽落 COM-0002(500)。与 InventoryWriteService 6 个写方法同口径。
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PurchaseOrderFromReplenishmentResponse createFromApprovedItems(
            CreatePurchaseOrderFromReplenishmentRequest request, String idempotencyKey) {

        // ── ⓪ 前置：team_id 只从 TeamContext 取（绝不取客户端）；解析幂等键 ──
        long teamId = currentTeamId();
        String key = requireIdempotencyKey(idempotencyKey);

        // ── A3 参数校验（先参数，后业务）──
        validateRequest(request);
        List<Long> itemIds = request.getReplenishmentItemIds().stream()
                .filter(Objects::nonNull).distinct().toList();
        long warehouseId = request.getWarehouseId();
        // 仓库归属：不存在 ⇒ CAT-3003(404)；跨团队 ⇒ TEAM-2001(403)（DESIGN_BASIS §5.1 规则 1：隔离优先）
        warehouseQueryService.requireInTeam(warehouseId);

        // ── ① 入参校验：存在 + 属本团队 + APPROVED ──
        List<ReplenishmentItemBrief> items = replenishmentQueryService.getItems(itemIds);
        if (items.size() != itemIds.size()) {
            // 租户线使跨团队与不存在同表现（不泄漏他团队是否存在）
            throw new BizException(ErrorCodes.RPL_8004, "补货建议不存在");
        }
        for (ReplenishmentItemBrief item : items) {
            if (STATUS_CONVERTED.equals(item.status())) {
                // 幂等重放：同一 Idempotency-Key 的同体请求 ⇒ 返回首次创建的那张采购单
                PurchaseOrderFromReplenishmentResponse replay = tryIdempotentReplay(
                        teamId, key, itemIds, warehouseId);
                if (replay != null) {
                    return replay;
                }
                throw new BizException(ErrorCodes.PO_9001, "该补货建议已生成采购单，不能重复转采购");
            }
            if (!STATUS_APPROVED.equals(item.status())) {
                throw new BizException(ErrorCodes.RPL_8001, "仅已审批通过的补货建议可转为采购单");
            }
            if (item.qty() == null || item.qty().signum() <= 0) {
                // 否则建行会撞 ck_poi_ordered_positive ⇒ 提前给可读错误（R163）
                throw BizException.withDetails(ErrorCodes.RPL_8006, "建议数量不合法",
                        List.of(ApiErrorDetail.of("qty", "建议数量必须大于 0")));
            }
        }
        // 全部建议的仓库必须一致且等于请求仓库
        Set<Long> itemWarehouses = new LinkedHashSet<>();
        for (ReplenishmentItemBrief item : items) {
            itemWarehouses.add(item.warehouseId());
        }
        if (itemWarehouses.size() != 1 || !itemWarehouses.contains(warehouseId)) {
            throw new BizException(ErrorCodes.RPL_8001, "建议仓库与请求仓库不一致");
        }
        // 供应商口径：采购单 supplier_id NOT NULL ⇒ 建议必须给供应商且一致
        Long supplierId = resolveSupplierId(items);
        if (supplierId == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("replenishment_item_ids", "所选建议未指定供应商")));
        }
        // S = (warehouse_id, sku_id) 集合的 sku 部分
        List<Long> skuIdsAsc = items.stream().map(ReplenishmentItemBrief::skuId)
                .filter(Objects::nonNull).distinct().sorted().toList();

        // ── ② 锚点锁（余额行；跨团队已撤销，按 team×warehouse 口径）──
        AnchorLockResult lockResult = inventoryWriteService.lockBalanceAnchors(
                teamId, warehouseId, skuIdsAsc);
        // ── ②b 降级锁（无余额行的 SKU ⇒ 锁「判定的输入」：本表 APPROVED 行）──
        if (!lockResult.missingSkuIds().isEmpty()) {
            replenishmentAppService.lockApprovedAnchors(teamId, warehouseId, lockResult.missingSkuIds());
        }

        // ── ③ 候选集：同 team×warehouse×sku 的 APPROVED 建议（排除本次入参）──
        List<ReplenishmentItemBrief> candidates = replenishmentQueryService.findByTeamWhSkuApproved(
                teamId, warehouseId, skuIdsAsc, itemIds);

        // ── ③b 冲突集 C：候选集里「未转采购」的（NOT EXISTS on purchase_order_items）──
        Set<Long> alreadyConverted = convertedItemIds(candidates);
        List<ReplenishmentItemBrief> conflicts = candidates.stream()
                .filter(c -> !alreadyConverted.contains(c.id()))
                .toList();

        // ── ④ 确认校验（C 非空 且 确认集 ≠ C ⇒ 拒绝）──
        if (!conflicts.isEmpty() && !conflictAcknowledged(request, conflicts)) {
            throw conflictError(warehouseId, conflicts);
        }

        // ── ⑤ 幂等：同键同体 ⇒ 返回首次创建的采购单；异体 ⇒ COM-0009 ──
        PurchaseOrderEntity existing = findByIdempotencyKey(key);
        if (existing != null) {
            if (sameBody(existing, itemIds, warehouseId)) {
                return toResponse(existing);
            }
            throw new BizException(ErrorCodes.COM_0009, "幂等键已被不同请求体占用");
        }

        // ── ⑥ 复核状态（① 之后可能被并发改动）──
        List<ReplenishmentItemBrief> rechecked = replenishmentQueryService.getItems(itemIds);
        for (ReplenishmentItemBrief item : rechecked) {
            if (STATUS_CONVERTED.equals(item.status())) {
                PurchaseOrderFromReplenishmentResponse replay = tryIdempotentReplay(
                        teamId, key, itemIds, warehouseId);
                if (replay != null) {
                    return replay;
                }
                throw new BizException(ErrorCodes.PO_9001, "该补货建议已生成采购单，不能重复转采购");
            }
            if (!STATUS_APPROVED.equals(item.status())) {
                throw new BizException(ErrorCodes.RPL_8001, "仅已审批通过的补货建议可转为采购单");
            }
        }

        // ── ⑦ INSERT：purchase_orders（DRAFT）+ purchase_order_items ──
        List<BigDecimal> orderedQtys = new ArrayList<>();
        List<BigDecimal> amounts = new ArrayList<>();
        for (ReplenishmentItemBrief item : items) {
            orderedQtys.add(item.qty());
            amounts.add(lineAmount(item.qty(), item.purchasePrice()));
        }
        BigDecimal totalQty = sum(orderedQtys, 3);
        BigDecimal totalAmount = sum(amounts, 2);

        PurchaseOrderEntity order = new PurchaseOrderEntity();
        order.setTeamId(teamId);
        order.setSupplierId(supplierId);
        order.setWarehouseId(warehouseId);
        order.setStatus(PO_STATUS_DRAFT);
        order.setSource(PO_SOURCE_REPLENISHMENT);
        order.setExpectedArrivalDate(request.getExpectedArrivalDate());
        order.setRemark(request.getRemark());
        order.setIdempotencyKey(key);
        order.setTotalQty(totalQty);
        order.setTotalAmount(totalAmount);
        order.setReceivedQty(BigDecimal.ZERO);
        order.setCreatedBy(currentUserId());
        order.setUpdatedBy(currentUserId());
        insertOrderWithPoNoRetry(order);

        int lineNo = 1;
        List<PurchaseOrderItemEntity> orderItems = new ArrayList<>();
        for (ReplenishmentItemBrief item : items) {
            PurchaseOrderItemEntity line = new PurchaseOrderItemEntity();
            line.setTeamId(teamId);
            line.setPurchaseOrderId(order.getId());
            line.setLineNo(lineNo++);
            line.setSkuId(item.skuId());
            line.setProductId(item.productId());
            line.setReplenishmentItemId(item.id());
            line.setOrderedQty(item.qty());
            line.setReceivedQty(BigDecimal.ZERO);
            line.setUnitPrice(item.purchasePrice());
            line.setAmount(lineAmount(item.qty(), item.purchasePrice()));
            line.setExpectedArrivalDate(request.getExpectedArrivalDate());
            line.setCreatedBy(currentUserId());
            line.setUpdatedBy(currentUserId());
            orderItemMapper.insert(line);
            orderItems.add(line);
        }

        // ── ⑧ 状态守卫 + 回填：逐条 APPROVED → CONVERTED（审计由 replenishment 写）──
        for (int i = 0; i < items.size(); i++) {
            replenishmentAppService.markConverted(items.get(i).id(), orderItems.get(i).getId());
        }

        // ── ⑨ 冲突确认留痕 ──
        if (!conflicts.isEmpty()) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("warehouse_id", warehouseId);
            after.put("acknowledged_item_ids", conflictIds(conflicts));
            after.put("conflict_count", conflicts.size());
            recordAudit(order.getId(), AuditAction.PURCHASE_ORDER_CONFLICT_ACK, after,
                    "同 SKU×仓库冲突已显式确认：" + conflictIds(conflicts));
        }

        // ── ⑩ 建单留痕 ──
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("po_no", order.getPoNo());
        after.put("supplier_id", order.getSupplierId());
        after.put("warehouse_id", order.getWarehouseId());
        after.put("total_qty", PurchaseOrderFromReplenishmentResponse.qtyText(order.getTotalQty()));
        after.put("total_amount", PurchaseOrderFromReplenishmentResponse.amountText(order.getTotalAmount()));
        after.put("source", order.getSource());
        recordAudit(order.getId(), AuditAction.PURCHASE_ORDER_CREATE, after,
                "采购单已创建：" + order.getPoNo());

        return toResponse(order, orderItems);
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 供应商解析：建议的 supplier_id 去重后必须唯一、存在且属本团队（{@code PO-9007}）。 */
    private Long resolveSupplierId(List<ReplenishmentItemBrief> items) {
        Set<Long> supplierIds = new LinkedHashSet<>();
        for (ReplenishmentItemBrief item : items) {
            if (item.supplierId() != null) {
                supplierIds.add(item.supplierId());
            }
        }
        if (supplierIds.size() > 1) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("replenishment_item_ids", "所选建议的供应商不一致")));
        }
        if (supplierIds.isEmpty()) {
            return null;
        }
        Long supplierId = supplierIds.iterator().next();
        if (!supplierQueryService.existsInTeam(supplierId)) {
            throw new BizException(ErrorCodes.PO_9007, "所选供应商不属于当前团队");
        }
        return supplierId;
    }

    /** 冲突确认：{@code conflict_acknowledged=true} 且确认集与当前冲突集<b>集合相等</b>（忽略顺序）。 */
    private boolean conflictAcknowledged(CreatePurchaseOrderFromReplenishmentRequest request,
                                         List<ReplenishmentItemBrief> conflicts) {
        if (!request.isConflictAcknowledged()) {
            return false;
        }
        Set<Long> acknowledged = new LinkedHashSet<>(request.acknowledgedIds());
        Set<Long> current = new LinkedHashSet<>(conflictIds(conflicts));
        return acknowledged.equals(current);
    }

    /** 冲突错误：{@code RPL-8012}；★ 沿用 R153 限制——冲突清单放 {@code details}（data 通道不实现）。 */
    private BizException conflictError(long warehouseId, List<ReplenishmentItemBrief> conflicts) {
        List<ApiErrorDetail> details = new ArrayList<>();
        for (ReplenishmentItemBrief conflict : conflicts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("replenishment_item_id", conflict.id());
            item.put("sku_id", conflict.skuId());
            item.put("qty", PurchaseOrderFromReplenishmentResponse.qtyText(conflict.qty()));
            item.put("risk_level", conflict.riskLevel());
            item.put("created_at", conflict.createdAt());
            details.add(new ApiErrorDetail("conflict_items", "该 SKU 在本仓库还有其它已审批的补货建议",
                    item));
        }
        return BizException.withDetails(ErrorCodes.RPL_8012,
                "该 SKU 在本仓库还有其它已审批的补货建议，请确认是否继续", details);
    }

    private static List<Long> conflictIds(List<ReplenishmentItemBrief> conflicts) {
        return conflicts.stream().map(ReplenishmentItemBrief::id).toList();
    }

    /** ③b：已生成采购明细的建议 ID（{@code NOT EXISTS} 的等价实现：查出已引用的 ID 集合）。 */
    private Set<Long> convertedItemIds(List<ReplenishmentItemBrief> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        List<Long> candidateIds = candidates.stream().map(ReplenishmentItemBrief::id).toList();
        return new LinkedHashSet<>(orderItemMapper.selectList(
                        Wrappers.<PurchaseOrderItemEntity>lambdaQuery()
                                .in(PurchaseOrderItemEntity::getReplenishmentItemId, candidateIds))
                .stream().map(PurchaseOrderItemEntity::getReplenishmentItemId).toList());
    }

    /** ⑤：按 {@code (team_id, idempotency_key)} 查既有采购单（租户线自动加 team_id）。 */
    private PurchaseOrderEntity findByIdempotencyKey(String key) {
        List<PurchaseOrderEntity> rows = orderMapper.selectList(
                Wrappers.<PurchaseOrderEntity>lambdaQuery()
                        .eq(PurchaseOrderEntity::getIdempotencyKey, key)
                        .orderByAsc(PurchaseOrderEntity::getId)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 同体判定：该采购单的建议集合与仓库与请求一致（忽略顺序）。 */
    private boolean sameBody(PurchaseOrderEntity order, List<Long> itemIds, long warehouseId) {
        if (order.getWarehouseId() == null || order.getWarehouseId() != warehouseId) {
            return false;
        }
        Set<Long> orderItemIds = new LinkedHashSet<>(orderItemMapper.selectList(
                        Wrappers.<PurchaseOrderItemEntity>lambdaQuery()
                                .eq(PurchaseOrderItemEntity::getPurchaseOrderId, order.getId()))
                .stream().map(PurchaseOrderItemEntity::getReplenishmentItemId).toList());
        return orderItemIds.equals(new LinkedHashSet<>(itemIds));
    }

    /** 幂等重放：命中同键同体的既有采购单 ⇒ 返回它（否则 {@code null}）。 */
    private PurchaseOrderFromReplenishmentResponse tryIdempotentReplay(
            long teamId, String key, List<Long> itemIds, long warehouseId) {
        PurchaseOrderEntity existing = findByIdempotencyKey(key);
        if (existing == null || !sameBody(existing, itemIds, warehouseId)) {
            return null;
        }
        return toResponse(existing);
    }

    /** ⑦：插入采购单；{@code po_no} 撞唯一约束时有限重试（≤3 次），不把 1062 抛给用户。 */
    private void insertOrderWithPoNoRetry(PurchaseOrderEntity order) {
        DuplicateKeyException lastError = null;
        for (int attempt = 0; attempt < PO_NO_MAX_RETRIES; attempt++) {
            order.setPoNo(nextPoNo());
            try {
                orderMapper.insert(order);
                return;
            } catch (DuplicateKeyException e) {
                lastError = e;
                if (findByIdempotencyKey(order.getIdempotencyKey()) != null) {
                    // 撞的是 uk_po_team_idem（并发同键）⇒ 异体/并发 ⇒ COM-0009（R162）
                    throw new BizException(ErrorCodes.COM_0009, "幂等键已被并发请求占用");
                }
                log.warn("采购单号撞唯一约束，重试：attempt={} poNo={}", attempt + 1, order.getPoNo());
            }
        }
        log.error("采购单号连续冲突：{}", order.getPoNo(), lastError);
        throw new BizException(ErrorCodes.PO_9013, "采购单号生成冲突，请重试");
    }

    /** {@code po_no = PO-yyyyMMdd-6位序号}（当日 UTC 自然日；序号 = 同团队当日最大序号 + 1）。 */
    private String nextPoNo() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String prefix = "PO-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        List<PurchaseOrderEntity> rows = orderMapper.selectList(
                Wrappers.<PurchaseOrderEntity>lambdaQuery()
                        .likeRight(PurchaseOrderEntity::getPoNo, prefix)
                        .orderByDesc(PurchaseOrderEntity::getPoNo)
                        .last("LIMIT 1"));
        int next = 1;
        if (!rows.isEmpty()) {
            String maxNo = rows.get(0).getPoNo();
            if (maxNo != null && maxNo.length() > prefix.length()) {
                try {
                    next = Integer.parseInt(maxNo.substring(prefix.length())) + 1;
                } catch (NumberFormatException ignored) {
                    next = 1;
                }
            }
        }
        return prefix + String.format("%06d", next);
    }

    private static BigDecimal lineAmount(BigDecimal qty, BigDecimal unitPrice) {
        if (qty == null || unitPrice == null) {
            // 单价为空 ⇒ 行金额为 null（汇总时跳过，不掩盖）
            return null;
        }
        return qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<BigDecimal> values, int scale) {
        BigDecimal total = BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        for (BigDecimal value : values) {
            if (value != null) {
                total = total.add(value);
            }
        }
        return total.setScale(scale, RoundingMode.HALF_UP);
    }

    private PurchaseOrderFromReplenishmentResponse toResponse(PurchaseOrderEntity order) {
        List<PurchaseOrderItemEntity> lines = orderItemMapper.selectList(
                Wrappers.<PurchaseOrderItemEntity>lambdaQuery()
                        .eq(PurchaseOrderItemEntity::getPurchaseOrderId, order.getId())
                        .orderByAsc(PurchaseOrderItemEntity::getLineNo));
        return toResponse(order, lines);
    }

    private PurchaseOrderFromReplenishmentResponse toResponse(PurchaseOrderEntity order,
                                                               List<PurchaseOrderItemEntity> lines) {
        List<PurchaseOrderFromReplenishmentResponse.LineItem> items = new ArrayList<>();
        for (PurchaseOrderItemEntity line : lines) {
            items.add(new PurchaseOrderFromReplenishmentResponse.LineItem(
                    line.getLineNo(), line.getSkuId(), line.getReplenishmentItemId(),
                    PurchaseOrderFromReplenishmentResponse.qtyText(line.getOrderedQty()),
                    PurchaseOrderFromReplenishmentResponse.priceText(line.getUnitPrice()),
                    PurchaseOrderFromReplenishmentResponse.amountText(line.getAmount())));
        }
        return new PurchaseOrderFromReplenishmentResponse(
                order.getId(), order.getPoNo(), order.getStatus(), order.getSource(),
                order.getSupplierId(), order.getWarehouseId(), order.getExpectedArrivalDate(),
                PurchaseOrderFromReplenishmentResponse.qtyText(order.getTotalQty()),
                PurchaseOrderFromReplenishmentResponse.amountText(order.getTotalAmount()),
                items, order.getCreatedAt());
    }

    private void recordAudit(Long bizId, AuditAction action, Map<String, Object> after, String summary) {
        TeamContext.Snapshot snapshot = TeamContext.get();
        auditWriteService.recordInBusinessTx(AuditEvent.builder()
                .teamId(currentTeamId())
                .action(action)
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

    // ---------------------------------------------------------------------
    // 参数与上下文
    // ---------------------------------------------------------------------

    private void validateRequest(CreatePurchaseOrderFromReplenishmentRequest request) {
        if (request == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("body", "请求体必填")));
        }
        if (request.getReplenishmentItemIds() == null || request.getReplenishmentItemIds().isEmpty()) {
            throw BizException.withDetails(ErrorCodes.PO_9006, "采购单至少需要一条明细",
                    List.of(ApiErrorDetail.of("replenishment_item_ids", "至少需要一条补货建议")));
        }
        if (request.getWarehouseId() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("warehouse_id", "warehouse_id 必填")));
        }
        if (request.getRemark() != null && request.getRemark().length() > REMARK_MAX_LENGTH) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("remark", "remark 长度不得超过 500 字符")));
        }
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BizException.withDetails(ErrorCodes.COM_0010, "缺少必要的请求信息",
                    List.of(ApiErrorDetail.of("Idempotency-Key", "Idempotency-Key 请求头必填")));
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw BizException.withDetails(ErrorCodes.COM_0010, "缺少必要的请求信息",
                    List.of(ApiErrorDetail.of("Idempotency-Key", "长度必须在 1..128 之间")));
        }
        return trimmed;
    }

    private static long currentTeamId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        if (snapshot == null || snapshot.teamId() == null) {
            throw new BizException(ErrorCodes.TEAM_2001, "缺少团队上下文");
        }
        return snapshot.teamId();
    }

    private static Long currentUserId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        return snapshot == null ? null : snapshot.userId();
    }
}
