package com.shoppilot.replenishment.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import org.springframework.transaction.annotation.Propagation;
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
     * 降级锚点行锁（{@code §7.2 #8} 步骤 ②b；契约 {@code MODULE_BOUNDARIES.md:245–246}）。
     *
     * <p><b>何时用</b>：步骤 ② 的 {@code inventory.InventoryWriteService#lockBalanceAnchors}
     * 返回的 {@code missingSkuIds} 非空时（该 SKU 在本团队本仓库没有余额行，无行可锁）。
     * 此时改为锁「冲突判定的输入」——本表 {@code APPROVED} 行。
     *
     * <p>★ <b>签名与 SQL 按契约一字不改</b>：
     * {@code WHERE team_id=? AND warehouse_id=? AND sku_id IN (...) AND status='APPROVED'
     * ORDER BY sku_id ASC FOR UPDATE}
     * —— 索引最左列 {@code team_id} 必须在条件里（{@code idx_repl_team_sku_time (team_id, sku_id, created_at)}），
     * 否则退化为 filesort + 全表行锁。
     *
     * <p>★ {@code ORDER BY sku_id ASC} 是固定加锁序（{@code §7.2 #8} ② / {@code DATABASE_CONSTRAINTS §7.3}）：
     * <b>不得</b>写 {@code ORDER BY id}（{@code id} 不在索引前缀里，filesort 后实际加锁序仍是
     * {@code (sku_id, created_at)}，与声明自相矛盾）。
     *
     * <p>★ 只加锁、返回 {@code void}、不写任何数据、不改任何状态。
     *
     * <p>★ {@code @Transactional(REQUIRED)}：{@code FOR UPDATE} 的锁在事务结束时释放；
     * 调用方（{@code purchasing}）必须自带外层事务，否则锁在方法返回瞬间释放 ⇒ 静默退化为无锁。
     *
     * @param teamId      团队（须与 {@code TeamContext} 一致，否则 {@code TEAM-2001}）
     * @param warehouseId 仓库
     * @param skuIds      SKU 集合（内部去重并按升序）
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void lockApprovedAnchors(long teamId, long warehouseId, List<Long> skuIds) {
        if (teamId != currentTeamId()) {
            throw new BizException(ErrorCodes.TEAM_2001, "跨团队越权");
        }
        if (skuIds == null || skuIds.isEmpty()) {
            return;
        }
        List<Long> ordered = skuIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ordered.isEmpty()) {
            return;
        }
        itemMapper.lockApprovedAnchorItemIds(teamId, warehouseId, ordered);
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
     *   <li>★ <b>同语句状态守卫</b>：{@code WHERE id=? AND status='APPROVED' AND version=?}，
     *       影响 0 行 ⇒ 409 {@code PO-9001}（已被并发转单，调用方整个事务回滚）。</li>
     * </ul>
     *
     * <p>★ 写路径<b>故意</b>用 {@code update(null, lambdaUpdate())} 而非 {@code updateById(entity)}：
     * MyBatis-Plus 的 {@code updateById} 只生成 {@code WHERE id=? AND version=?}（缺 {@code status}
     * 条件）。注意：{@code version=?} <b>本身已是完备的 CAS</b>——任何并发写（无论改什么字段）都会
     * bump {@code version}，因此 {@code updateById} 的乐观锁并未失效；补
     * {@code status='APPROVED'} 的价值是<b>纵深防御 + 语义自解释</b>：把「状态前提」显式
     * 写进 SQL，让守卫在语句层自证，而非只依赖版本号间接推断。本处把
     * {@code id} + {@code status} + {@code version} 三条件并进同一条 UPDATE，并手动
     * {@code set(version, version + 1)}（插件对 {@code update(null, wrapper)} 不触发，
     * 见 {@code common/MultiTenantConfig} 权威口径；{@code entity == null} 时既无重复自增
     * 也无漏自增），保持版本号语义连续——<b>这是对「禁止 update(null, wrapper)」常规的、
     * 有据可依的例外</b>（常规禁令针对的是「绕过 @Version 的覆盖写」，本处显式把 version
     * 当作等值条件 + 自增，等价于 CAS，不构成绕过）。
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

        // ★ 同语句状态守卫（R3 / 契约 MODULE_BOUNDARIES:238–239）：
        //   WHERE id=? AND status='APPROVED' AND version=?
        //   —— 读到的 version 与状态一并作为条件。注意口径（与方法级 Javadoc 一致）：
        //   version=? 本身已是完备 CAS；补 status='APPROVED' 是纵深防御 + 语义自解释。
        //   本处**故意**不用 updateById：MP 生成的 WHERE 只有 id + version，缺 status 条件。
        //   同时手动 set(version, version + 1)（插件对 update(null, wrapper) 不触发——
        //   与 MultiTenantConfig 权威口径一致：插件只改写实体形态的更新；entity == null 时
        //   既无重复自增也无漏自增，与插件的 WHERE version=? SET version=version+1 逐字等价），
        //   保持「乐观锁版本」语义连续（下次读到的 version 是本行 +1）。
        String appendedJson = appendPoItemId(entity.getConvertedPoItemIds(), purchaseOrderItemId);
        int affected = itemMapper.update(null, Wrappers.<ReplenishmentItemEntity>lambdaUpdate()
                .eq(ReplenishmentItemEntity::getId, itemId)
                .eq(ReplenishmentItemEntity::getStatus, STATUS_APPROVED)
                .eq(ReplenishmentItemEntity::getVersion, entity.getVersion())
                .set(ReplenishmentItemEntity::getStatus, STATUS_CONVERTED)
                .set(ReplenishmentItemEntity::getConvertedAt, LocalDateTime.now(ZoneOffset.UTC))
                .set(ReplenishmentItemEntity::getConvertedPoItemIds, appendedJson)
                .set(ReplenishmentItemEntity::getVersion, entity.getVersion() + 1)
                .set(ReplenishmentItemEntity::getUpdatedBy, currentUserId()));
        if (affected == 0) {
            // 0 行 = 已被并发转单（状态或版本变了）⇒ 整事务回滚
            throw new BizException(ErrorCodes.PO_9001, "该补货建议已生成采购单，不能重复转采购");
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

    /** 当前团队 ID；无上下文 ⇒ {@code TEAM-2001}（与 {@code InventoryWriteService} 同款守卫）。 */
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
