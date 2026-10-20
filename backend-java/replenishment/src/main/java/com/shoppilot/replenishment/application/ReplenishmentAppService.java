package com.shoppilot.replenishment.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentItemEntity;
import com.shoppilot.replenishment.infrastructure.persistence.mapper.ReplenishmentItemMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 补货建议的<b>应用服务</b>（M6-T02：供 {@code purchasing} 转采购调用；
 * {@code DATABASE_DESIGN §7.2 #8} 步骤 ②b / ⑧）。
 *
 * <h2>为什么单独一个类</h2>
 * <p>{@code §7.2 #8} 明确要求「转采购」这条跨模块链路经
 * {@code replenishment.ReplenishmentAppService} 的 {@code lockApprovedAnchors} /
 * {@code markConverted} 两个方法完成；{@link ReplenishmentItemService} 面向 HTTP 端点
 * （详情/改量/submit/withdraw/approve/reject/cancel），两者职责不同，不混在一起。
 *
 * <h2>★ 写审计的是本模块（不是 {@code purchasing}）</h2>
 * <p>{@code §9.17 #22 REPLENISHMENT_CONVERT} 的 {@code biz_type=REPLENISHMENT_ITEM}，
 * 被改的表在本模块 ⇒ <b>审计由写方自己写</b>，{@code purchasing} 不代写
 * （{@code §5.65} 裁定 4）。
 *
 * <h2>★ {@code converted_po_item_ids} 是「追加」不是「覆盖」</h2>
 * <p>一条建议理论上只转一次采购（{@code uk_po_team_idem} 与 {@code PO-9001} 会挡住第二次），
 * 但列本身是 JSON <b>数组</b>，语义上必须<b>追加</b>既有值而不是整体覆盖——
 * 覆盖会静默丢掉历史关联（这正是本方法最容易被写成 bug 的地方）。
 */
@Service
public class ReplenishmentAppService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentAppService.class);

    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_CONVERTED = "CONVERTED";
    private static final String BIZ_TYPE = "REPLENISHMENT_ITEM";

    private final ReplenishmentItemMapper itemMapper;
    private final AuditWriteService auditWriteService;
    private final ObjectMapper objectMapper;

    public ReplenishmentAppService(ReplenishmentItemMapper itemMapper,
                                   AuditWriteService auditWriteService,
                                   ObjectMapper objectMapper) {
        this.itemMapper = itemMapper;
        this.auditWriteService = auditWriteService;
        this.objectMapper = objectMapper;
    }

    /**
     * 降级锚点行锁（{@code §7.2 #8} 步骤 ②b）：按 {@code sku_id} 升序锁本表 {@code APPROVED} 行。
     *
     * <p><b>何时用</b>：步骤 ② 的 {@code inventory.InventoryWriteService#lockBalanceAnchors}
     * 返回的 {@code missingSkuIds} 非空时（该 SKU 在任何团队都没有余额行，无行可锁）。
     * 此时改为锁「冲突判定的输入」——本表的 {@code APPROVED} 行（走 {@code idx_repl_team_sku_time}）。
     *
     * <p>★ 跨团队（底层 {@code @InterceptorIgnore(tenantLine = "true")}）：与余额锚点锁同源，
     * 已登记 {@code M2_TenantIsolationDbIntegrationTest.TENANT_LINE_EXCEPTIONS}。
     *
     * <p>★ <b>必须由调用方在事务内调用</b>（本方法不开事务）：锁随调用方事务释放。
     *
     * @param skuIds 待锁 SKU
     * @return 实际锁到的建议 {@code id}（仅供日志与断言）
     */
    public List<Long> lockApprovedAnchors(Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> locked = itemMapper.lockApprovedAnchorItemIds(ids);
        log.debug("降级锚点行锁完成：requested={} locked={}", ids.size(), locked.size());
        return locked;
    }

    /**
     * 建议转采购：{@code APPROVED → CONVERTED} + 回填 {@code converted_po_item_ids}
     * + 写审计 {@code REPLENISHMENT_CONVERT}（{@code §7.2 #8} 步骤 ⑧；<b>同事务</b>）。
     *
     * <p><b>状态守卫</b>：
     * <ul>
     *   <li>不存在/跨团队 ⇒ 404 {@code RPL-8004}；</li>
     *   <li>已 {@code CONVERTED} ⇒ 409 {@code PO-9001}（<b>与 {@code RPL-8001} 分开</b>：
     *       「已转采购」与「状态非法」是两种语义）；</li>
     *   <li>其它非 {@code APPROVED} ⇒ 422 {@code RPL-8001}；</li>
     *   <li>{@code updateById} 影响 0 行 ⇒ 409 {@code RPL-8003}（乐观锁冲突，
     *       说明已被并发推进）⇒ 调用方整个事务回滚。</li>
     * </ul>
     *
     * <p>★ 写路径用 {@code updateById(entity)}（{@code @Version} CAS），
     * <b>禁止</b> {@code update(null, wrapper)}。
     *
     * @param itemId              建议 ID
     * @param purchaseOrderItemId 对应采购明细 ID（追加进 {@code converted_po_item_ids}）
     */
    @Transactional(rollbackFor = Exception.class)
    public void markConverted(long itemId, Long purchaseOrderItemId) {
        ReplenishmentItemEntity entity = itemMapper.selectById(itemId);
        if (entity == null) {
            throw new BizException(ErrorCodes.RPL_8004, "补货建议不存在");
        }
        String fromStatus = entity.getStatus();
        if (STATUS_CONVERTED.equals(fromStatus)) {
            throw new BizException(ErrorCodes.PO_9001, "该补货建议已生成采购单，不能重复转采购");
        }
        if (!STATUS_APPROVED.equals(fromStatus)) {
            throw new BizException(ErrorCodes.RPL_8001, "仅已审批通过的补货建议可转为采购单");
        }

        entity.setStatus(STATUS_CONVERTED);
        entity.setConvertedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setConvertedPoItemIds(appendPoItemId(entity.getConvertedPoItemIds(), purchaseOrderItemId));
        entity.setUpdatedBy(currentUserId());
        int affected = itemMapper.updateById(entity);
        if (affected == 0) {
            throw new BizException(ErrorCodes.RPL_8003, "建议已被他人修改，请刷新后重试");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", fromStatus);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", STATUS_CONVERTED);
        after.put("converted_po_item_id", purchaseOrderItemId);
        recordAudit(entity, before, after);
        log.info("建议已转采购 itemId={} poItemId={}", itemId, purchaseOrderItemId);
    }

    /** 追加 poi id 到 JSON 数组（<b>追加而非覆盖</b>；已有值解析失败时从空数组重来并记 warn）。 */
    private String appendPoItemId(String existingJson, Long purchaseOrderItemId) {
        List<Long> ids = new ArrayList<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                List<Long> parsed = objectMapper.readValue(existingJson, new TypeReference<List<Long>>() { });
                if (parsed != null) {
                    ids.addAll(parsed);
                }
            } catch (Exception e) {
                // 历史脏数据不应阻塞转采购：告警后按空数组继续（列语义仍是「追加」）
                log.warn("converted_po_item_json 解析失败，按空数组重新追加：raw={}", existingJson, e);
                ids.clear();
            }
        }
        if (purchaseOrderItemId != null && !ids.contains(purchaseOrderItemId)) {
            ids.add(purchaseOrderItemId);
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalStateException("converted_po_item_ids 序列化失败", e);
        }
    }

    private void recordAudit(ReplenishmentItemEntity entity, Map<String, Object> before,
                             Map<String, Object> after) {
        TeamContext.Snapshot snapshot = TeamContext.get();
        auditWriteService.recordInBusinessTx(AuditEvent.builder()
                .teamId(entity.getTeamId())
                .action(AuditAction.REPLENISHMENT_CONVERT)
                .bizType(BIZ_TYPE)
                .bizId(entity.getId())
                .operatorId(snapshot == null ? null : snapshot.userId())
                .operatorName(snapshot == null ? null : snapshot.username())
                .operatorRole(snapshot == null || snapshot.roles().isEmpty()
                        ? null : snapshot.roles().get(0))
                .result(AuditResult.SUCCESS)
                .changeSummary("status: " + before.get("status") + " → " + after.get("status"))
                .before(before)
                .after(after)
                .build());
    }

    private static Long currentUserId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        return snapshot == null ? null : snapshot.userId();
    }
}