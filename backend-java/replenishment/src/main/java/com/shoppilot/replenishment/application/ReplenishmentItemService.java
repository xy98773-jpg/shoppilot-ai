package com.shoppilot.replenishment.application;

import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.catalog.application.SupplierQueryService;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.replenishment.application.dto.ReplenishmentItemResponse;
import com.shoppilot.replenishment.application.dto.UpdateReplenishmentItemRequest;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentItemEntity;
import com.shoppilot.replenishment.infrastructure.persistence.mapper.ReplenishmentItemMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 补货建议详情与人工改量（M5-T03，{@code API_CONTRACT §10.8} / F-RPL-02）。
 *
 * <h2>本批范围</h2>
 * <p>仅两个操作：{@code GET /items/{id}}（详情，{@code version} 的唯一来源）与
 * {@code PATCH /items/{id}}（id 可改 {@code qty}/{@code supplier_id}/
 * {@code expected_arrival_date}/{@code remark}）。
 * <b>不做</b> submit/withdraw（T04）、approve/reject（T05）、cancel/SUPERSEDED（T06）、列表。
 *
 * <h2>★ 乐观锁：版本从<b>请求</b>取，不从库取</h2>
 * <p>{@code PATCH} 的 {@code version} 是客户端回传的并发令牌。本方法虽然会
 * {@code selectById}（用于存在性/状态校验与审计前值），但<b>绝不</b>用库里读到的 version
 * 覆盖请求值——那等于关掉 CAS（{@code R62} 同型陷阱）。{@code updateById} 返回 0 行：
 * <ul>
 *   <li>版本不匹配 ⇒ {@code RPL-8003}（409，可重试）；</li>
 *   <li>★ 不用 {@code LambdaUpdateWrapper.set(col, null)}：它会绕过 {@code @Version}。</li>
 * </ul>
 *
 * <h2>★ 部分更新语义</h2>
 * <p>只把<b>非 null</b> 字段 set 到实体，MyBatis-Plus 默认 {@code NOT_NULL} 策略天然实现 PATCH。
 * {@code remark=""} 是「非 null」⇒ 落库空串（不置 {@code NULL}）。
 * 四个字段全 null ⇒ 400（否则 {@code updateById} 会白白把 {@code version} +1）。
 *
 * <h2>★ 审计（{@code §9.17} 35 条内，不新增动作）</h2>
 * <p>{@code REPLENISHMENT_QTY_UPDATE} / {@code bizType=REPLENISHMENT_ITEM} / {@code bizId=itemId}；
 * {@code before}/{@code after} <b>只含本次变更字段</b>；同事务走
 * {@link AuditWriteService#recordInBusinessTx}（{@code MANDATORY}，写不进即回滚）。
 *
 * <p>★ <b>{@code suggested_qty} 不可变</b>（系统原始建议量），本类不提供任何写它的路径。
 */
@Service
public class ReplenishmentItemService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentItemService.class);

    /** 允许人工改量的状态（{@code §9.11}：{@code APPROVED} 后业务字段冻结）。 */
    private static final Set<String> EDITABLE_STATUS = Set.of("SUGGESTED", "PENDING_REVIEW");

    /** 审计 {@code biz_type}（{@code §9.17}）。 */
    private static final String BIZ_TYPE = "REPLENISHMENT_ITEM";

    /** {@code remark}/{@code reason} 列宽（字符）。 */
    private static final int REMARK_MAX_LENGTH = 500;

    /** 数量精度（{@code §14.2}：{@code DECIMAL(18,3)}）。 */
    private static final int QTY_SCALE = 3;

    private final ReplenishmentItemMapper itemMapper;
    private final SupplierQueryService supplierQueryService;
    private final AuditWriteService auditWriteService;

    public ReplenishmentItemService(ReplenishmentItemMapper itemMapper,
                                    SupplierQueryService supplierQueryService,
                                    AuditWriteService auditWriteService) {
        this.itemMapper = itemMapper;
        this.supplierQueryService = supplierQueryService;
        this.auditWriteService = auditWriteService;
    }

    // =====================================================================
    // 详情（任何已登录成员，含 VIEWER）
    // =====================================================================

    /**
     * 建议详情（{@code GET /api/v1/replenishment/items/{id}}）。
     *
     * <p>不存在 / 跨团队 ⇒ {@code RPL-8004}（404）——租户线使两者同表现，不泄漏他团队是否存在。
     */
    @Transactional(readOnly = true)
    public ReplenishmentItemResponse get(long id) {
        return ReplenishmentItemResponse.of(requireItem(id));
    }

    // =====================================================================
    // 人工改量（OPERATOR+）
    // =====================================================================

    /**
     * 修改建议（{@code PATCH /api/v1/replenishment/items/{id}}）。
     *
     * <p>校验顺序（C1–C7，固定）：
     * <ol>
     *   <li>{@code version} 缺失 ⇒ 400 {@code COM-0001}（field={@code version}）；</li>
     *   <li>四个可改字段全 null ⇒ 400 {@code COM-0001}（field={@code body}）；</li>
     *   <li>不存在/跨团队 ⇒ 404 {@code RPL-8004}；</li>
     *   <li>状态 ∉ {SUGGESTED, PENDING_REVIEW} ⇒ 422 {@code RPL-8001}；</li>
     *   <li>{@code qty <= 0} 或小数位 > 3 ⇒ 400 {@code RPL-8006}；★ <b>无上限</b>；</li>
     *   <li>{@code supplier_id} 非本团队 ⇒ 403 {@code RPL-8009}；</li>
     *   <li>{@code expected_arrival_date < today(UTC)} ⇒ 400 {@code RPL-8010}；</li>
     *   <li>{@code remark} > 500 字符 ⇒ 400 {@code COM-0001}（field={@code remark}；无专用码）。</li>
     * </ol>
     *
     * @return 更新后详情（{@code version} 为新值）
     */
    @Transactional(rollbackFor = Exception.class)
    public ReplenishmentItemResponse update(long id, UpdateReplenishmentItemRequest request) {
        if (request == null || request.getVersion() == null) {
            throw badParam("version", "version 必填（乐观锁令牌）");
        }
        if (request.getQty() == null && request.getSupplierId() == null
                && request.getExpectedArrivalDate() == null && request.getRemark() == null) {
            throw badParam("body", "至少提供一个可修改字段（qty/supplier_id/expected_arrival_date/remark）");
        }

        ReplenishmentItemEntity entity = requireItem(id);
        if (!EDITABLE_STATUS.contains(entity.getStatus())) {
            throw new BizException(ErrorCodes.RPL_8001, "当前状态不允许该操作");
        }

        // ---- 校验与归一化（先判 scale 再 setScale：B20.2 教训） ----
        BigDecimal newQty = null;
        if (request.getQty() != null) {
            if (request.getQty().scale() > QTY_SCALE) {
                throw quantityInvalid("数量小数位不得超过 3 位");
            }
            if (request.getQty().signum() <= 0) {
                throw quantityInvalid("数量必须大于 0");
            }
            newQty = request.getQty().setScale(QTY_SCALE, RoundingMode.UNNECESSARY);
        }
        if (request.getSupplierId() != null && !supplierQueryService.existsInTeam(request.getSupplierId())) {
            throw new BizException(ErrorCodes.RPL_8009, "所选供应商不属于当前团队");
        }
        if (request.getExpectedArrivalDate() != null
                && request.getExpectedArrivalDate().isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new BizException(ErrorCodes.RPL_8010, "期望到货日期不能早于今天");
        }
        if (request.getRemark() != null && request.getRemark().length() > REMARK_MAX_LENGTH) {
            throw badParam("remark", "remark 长度不得超过 500 字符");
        }

        // ---- 只把非 null 字段 set 到实体（B4：NOT_NULL 策略即 PATCH 语义） ----
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        List<String> changes = new ArrayList<>();

        if (newQty != null) {
            String oldText = ReplenishmentItemResponse.qtyText(entity.getQty());
            String newText = ReplenishmentItemResponse.qtyText(newQty);
            before.put("qty", oldText);
            after.put("qty", newText);
            changes.add("qty: " + oldText + " → " + newText);
            entity.setQty(newQty);
        }
        if (request.getSupplierId() != null) {
            before.put("supplier_id", entity.getSupplierId());
            after.put("supplier_id", request.getSupplierId());
            changes.add("supplier_id: " + entity.getSupplierId() + " → " + request.getSupplierId());
            entity.setSupplierId(request.getSupplierId());
        }
        if (request.getExpectedArrivalDate() != null) {
            String oldText = entity.getExpectedArrivalDate() == null
                    ? "null" : entity.getExpectedArrivalDate().toString();
            String newText = request.getExpectedArrivalDate().toString();
            before.put("expected_arrival_date", entity.getExpectedArrivalDate() == null
                    ? null : entity.getExpectedArrivalDate().toString());
            after.put("expected_arrival_date", newText);
            changes.add("expected_arrival_date: " + oldText + " → " + newText);
            entity.setExpectedArrivalDate(request.getExpectedArrivalDate());
        }
        if (request.getRemark() != null) {
            before.put("remark", entity.getRemark());
            after.put("remark", request.getRemark());
            changes.add("remark: " + quote(entity.getRemark()) + " → " + quote(request.getRemark()));
            entity.setRemark(request.getRemark());
        }

        // ★ 版本从请求取（不得用库里的值覆盖）：这是 CAS 的唯一有效来源
        entity.setVersion(request.getVersion());
        entity.setUpdatedBy(currentUserId());
        int affected = itemMapper.updateById(entity);
        if (affected == 0) {
            throw new BizException(ErrorCodes.RPL_8003, "建议已被他人修改，请刷新后重试");
        }

        recordAudit(id, entity.getTeamId(), String.join("; ", changes), before, after);
        log.info("补货建议已修改 id={} changes=[{}]", id, String.join("; ", changes));

        // 重查以返回插件自增后的 version（不是用于 CAS）
        return ReplenishmentItemResponse.of(requireItem(id));
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 当前团队内的建议；不存在/跨团队 ⇒ {@code RPL-8004}（404）。 */
    private ReplenishmentItemEntity requireItem(long id) {
        ReplenishmentItemEntity entity = itemMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCodes.RPL_8004, "补货建议不存在");
        }
        return entity;
    }

    /** 审计：{@code before}/{@code after} 只含本次变更字段（D2）；同事务（{@code MANDATORY}）。 */
    private void recordAudit(long itemId, Long teamId, String summary,
                             Map<String, Object> before, Map<String, Object> after) {
        TeamContext.Snapshot snapshot = TeamContext.get();
        auditWriteService.recordInBusinessTx(AuditEvent.builder()
                .teamId(teamId)
                .action(AuditAction.REPLENISHMENT_QTY_UPDATE)
                .bizType(BIZ_TYPE)
                .bizId(itemId)
                .operatorId(snapshot == null ? null : snapshot.userId())
                .operatorName(snapshot == null ? null : snapshot.username())
                .operatorRole(snapshot == null || snapshot.roles().isEmpty()
                        ? null : snapshot.roles().get(0))
                .result(AuditResult.SUCCESS)
                .changeSummary(summary)
                .before(before.isEmpty() ? null : before)
                .after(after.isEmpty() ? null : after)
                .build());
    }

    private static BizException badParam(String field, String message) {
        return BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                List.of(ApiErrorDetail.of(field, message)));
    }

    private static BizException quantityInvalid(String message) {
        return BizException.withDetails(ErrorCodes.RPL_8006, message,
                List.of(ApiErrorDetail.of("qty", message)));
    }

    /** 审计摘要里的文本展示：{@code null} 不加引号，非 null 加双引号。 */
    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static Long currentUserId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        return snapshot == null ? null : snapshot.userId();
    }
}
