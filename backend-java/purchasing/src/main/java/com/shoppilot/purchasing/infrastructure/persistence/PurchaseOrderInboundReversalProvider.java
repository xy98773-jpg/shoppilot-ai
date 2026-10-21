package com.shoppilot.purchasing.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.inventory.application.PurchaseInboundReversalPort;
import com.shoppilot.purchasing.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.shoppilot.purchasing.infrastructure.persistence.entity.PurchaseOrderItemEntity;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.PurchaseOrderItemMapper;
import com.shoppilot.purchasing.infrastructure.persistence.mapper.PurchaseOrderMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PurchaseInboundReversalPort} 的采购侧实现（M8-T05 / {@code R185}）。
 *
 * <h2>为什么是端口反转 + 同事务</h2>
 * <p>{@code inventory} 是库存余额表的<b>唯一写入口</b>，但冲销 {@code PURCHASE_INBOUND}（写反向流水扣
 * {@code on_hand}）必须<b>同事务</b>回退 {@code purchase_order_items.received_qty} 并重算
 * {@code purchase_orders.status} —— 否则派生在途（{@code ordered_qty − received_qty}）会<b>少计</b>
 * （{@code PURCHASING_WORKFLOW §8.3}，{@code R185}）。回退 {@code received_qty} 属 <b>purchasing</b> 写职责，
 * {@code inventory} 不得直写采购表（与 {@code InTransitFactProvider} 同型：声明在 {@code inventory}、
 * 实现在 {@code purchasing}、由 {@code app} 组合根经组件扫描自动装配）。
 *
 * <p>★ <b>同事务</b>：调用方（{@code InventoryWriteService#attemptReversal}）把本调用放在
 * {@code runWithRetry} 的 {@code NESTED} 单元内，本方法 {@code REQUIRED} 加入，随冲销写流水
 * 一同成功/回滚（整体原子）。
 *
 * <h2>口径（与 {@code PurchaseOrderAppService#applyReceivedQuantities} ④⑤ 严格对称）</h2>
 * <ul>
 *   <li><b>④</b> 悲观锁 {@code FOR UPDATE} 锁定本明细行（单行走锁，无交叉死锁风险），
 *       累加口径取反：{@code received_qty −= qty}（只减不加；守卫 {@code next ≥ 0}）；</li>
 *   <li><b>⑤</b> 重算 {@code purchase_orders.received_qty = Σ} 全单明细 {@code received_qty}，
 *       状态推导（乐观锁 {@code version} + 状态守卫）：
 *       {@code Σ received ≥ Σ ordered ⇒ RECEIVED}（终态，写 {@code closed_at}）；
 *       否则 {@code Σ received > 0 ⇒ PARTIALLY_RECEIVED}；否则 {@code RECEIVED ⇒ ORDERED}
 *       （全部回退，清除 {@code closed_at}）——这是确认路径<b>不会</b>产生、但冲销路径<b>必须</b>
 *       退回的状态（派生在途须重新计入全额 {@code ordered_qty}）。</li>
 * </ul>
 *
 * <h2>定位明细行</h2>
 * <p>{@code idempotencyKey} 即原 {@code PURCHASE_INBOUND} 幂等键，末段 {@code :<purchase_order_item_id>}
 * （{@code R180}），实现方据此直接定位明细行；{@code goodsReceiptId} 仅作契约透传，本实现不依赖。
 *
 * <h2>租户隔离</h2>
 * <p>{@code team_id} 取自 {@link TeamContext} 并显式 {@code eq(team_id)} 双保险（租户线自动追加同名条件）。
 */
@Component
public class PurchaseOrderInboundReversalProvider implements PurchaseInboundReversalPort {

    private static final String PO_STATUS_ORDERED = "ORDERED";
    private static final String PO_STATUS_PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED";
    private static final String PO_STATUS_RECEIVED = "RECEIVED";

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper orderItemMapper;

    public PurchaseOrderInboundReversalProvider(PurchaseOrderMapper orderMapper,
                                                PurchaseOrderItemMapper orderItemMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void reversePurchaseInbound(long goodsReceiptId, String idempotencyKey, BigDecimal qty) {
        long teamId = currentTeamId();
        Long operatorId = currentUserId();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        long purchaseOrderItemId = parsePurchaseOrderItemId(idempotencyKey);

        // ④ 悲观锁：单行走锁（FOR UPDATE）定位明细行并取当前 received_qty 做「读-改-写」扣减
        //   （租户线自动追加 team_id；此处仍显式 eq 作双保险；行不存在 ⇒ 锁定查询返回 null ⇒ 抛错）
        PurchaseOrderItemEntity locked = orderItemMapper.selectOne(
                Wrappers.<PurchaseOrderItemEntity>lambdaQuery()
                        .eq(PurchaseOrderItemEntity::getTeamId, teamId)
                        .eq(PurchaseOrderItemEntity::getId, purchaseOrderItemId)
                        .last("FOR UPDATE"));
        if (locked == null) {
            throw new BizException(ErrorCodes.PO_9006, "采购明细不存在或不属于当前团队");
        }
        PurchaseOrderEntity order = requireOrder(teamId, locked.getPurchaseOrderId());
        BigDecimal current = locked.getReceivedQty() == null
                ? BigDecimal.ZERO.setScale(3) : locked.getReceivedQty();
        BigDecimal next = current.subtract(qty);
        // 守卫：回退量不得超过已收量（合法冲销下不会触发；双冲销由库存侧幂等拦截，此处兜底）
        if (next.signum() < 0) {
            throw new BizException(ErrorCodes.PO_9003, "回退数量超过已收数量");
        }

        int affectedLine = orderItemMapper.update(null,
                Wrappers.<PurchaseOrderItemEntity>lambdaUpdate()
                        .eq(PurchaseOrderItemEntity::getId, purchaseOrderItemId)
                        .eq(PurchaseOrderItemEntity::getPurchaseOrderId, order.getId())
                        .eq(PurchaseOrderItemEntity::getVersion, locked.getVersion())
                        .set(PurchaseOrderItemEntity::getReceivedQty, next)
                        .set(PurchaseOrderItemEntity::getVersion, locked.getVersion() + 1)
                        .set(PurchaseOrderItemEntity::getUpdatedBy, operatorId));
        if (affectedLine == 0) {
            throw new BizException(ErrorCodes.PO_9003, "采购明细已被同时修改，请刷新后重试");
        }

        // ⑤ 重算采购单 received_qty（Σ 全单明细）与状态推导（乐观锁 + 状态守卫）
        BigDecimal sumOrdered = BigDecimal.ZERO.setScale(3);
        BigDecimal sumReceived = BigDecimal.ZERO.setScale(3);
        for (PurchaseOrderItemEntity item : orderItemMapper.selectList(
                Wrappers.<PurchaseOrderItemEntity>lambdaQuery()
                        .eq(PurchaseOrderItemEntity::getTeamId, teamId)
                        .eq(PurchaseOrderItemEntity::getPurchaseOrderId, order.getId())
                        .select(PurchaseOrderItemEntity::getOrderedQty,
                                PurchaseOrderItemEntity::getReceivedQty))) {
            sumOrdered = sumOrdered.add(item.getOrderedQty() == null
                    ? BigDecimal.ZERO : item.getOrderedQty());
            sumReceived = sumReceived.add(item.getReceivedQty() == null
                    ? BigDecimal.ZERO : item.getReceivedQty());
        }

        String fromStatus = order.getStatus();
        String newStatus = deriveStatus(sumReceived, sumOrdered);
        LambdaUpdateWrapper<PurchaseOrderEntity> updater =
                Wrappers.<PurchaseOrderEntity>lambdaUpdate()
                        .eq(PurchaseOrderEntity::getId, order.getId())
                        .eq(PurchaseOrderEntity::getTeamId, teamId)
                        // ★ 状态守卫：仅 RECEIVED / PARTIALLY_RECEIVED 可回退（ORDERED 不可能已收，DRAFT 等更不可能）
                        .in(PurchaseOrderEntity::getStatus,
                                PO_STATUS_RECEIVED, PO_STATUS_PARTIALLY_RECEIVED)
                        .eq(PurchaseOrderEntity::getVersion, order.getVersion())
                        .set(PurchaseOrderEntity::getReceivedQty, sumReceived)
                        .set(PurchaseOrderEntity::getStatus, newStatus)
                        .set(PurchaseOrderEntity::getVersion, order.getVersion() + 1)
                        .set(PurchaseOrderEntity::getUpdatedBy, operatorId);
        if (PO_STATUS_RECEIVED.equals(newStatus)) {
            // 重新进入终态 ⇒ 刷新 closed_at
            updater.set(PurchaseOrderEntity::getClosedAt, now);
        } else {
            // 不再「全部到货」⇒ 清除 closed_at（含退回 ORDERED）
            updater.set(PurchaseOrderEntity::getClosedAt, (LocalDateTime) null);
        }
        int affectedOrder = orderMapper.update(null, updater);
        if (affectedOrder == 0) {
            throw new BizException(ErrorCodes.PO_9003, "采购单状态已变化，请刷新后重试");
        }
        logReversal(purchaseOrderItemId, fromStatus, newStatus, qty);
    }

    /** 状态推导（与确认路径对称，但允许退回 {@code ORDERED}）。 */
    private static String deriveStatus(BigDecimal sumReceived, BigDecimal sumOrdered) {
        if (sumReceived.compareTo(sumOrdered) >= 0) {
            return PO_STATUS_RECEIVED;
        }
        if (sumReceived.signum() > 0) {
            return PO_STATUS_PARTIALLY_RECEIVED;
        }
        return PO_STATUS_ORDERED;
    }

    /** 从 {@code PURCHASE_INBOUND} 幂等键末段解析采购明细 ID（{@code R180}：{@code :<purchaseOrderItemId>}）。 */
    private static long parsePurchaseOrderItemId(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCodes.PO_9006, "幂等键为空，无法定位采购明细");
        }
        String[] parts = idempotencyKey.split(":");
        if (parts.length < 5) {
            throw new BizException(ErrorCodes.PO_9006, "幂等键格式不合法，缺少采购明细片段");
        }
        try {
            return Long.parseLong(parts[parts.length - 1].trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCodes.PO_9006, "幂等键末段不是合法的采购明细 ID");
        }
    }

    private PurchaseOrderEntity requireOrder(long teamId, long orderId) {
        PurchaseOrderEntity order = orderMapper.selectOne(
                Wrappers.<PurchaseOrderEntity>lambdaQuery()
                        .eq(PurchaseOrderEntity::getTeamId, teamId)
                        .eq(PurchaseOrderEntity::getId, orderId));
        if (order == null) {
            throw new BizException(ErrorCodes.PO_9004, "采购单不存在或不属于当前团队");
        }
        return order;
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

    private static void logReversal(long lineId, String fromStatus, String newStatus, BigDecimal qty) {
        org.slf4j.LoggerFactory.getLogger(PurchaseOrderInboundReversalProvider.class)
                .info("采购到货入库冲销回退 poItemId={} {}→{} 回退量={}", lineId, fromStatus, newStatus, qty);
    }
}
