package com.shoppilot.inventory.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.catalog.application.SkuQueryService;
import com.shoppilot.catalog.application.WarehouseQueryService;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.inventory.application.dto.InventoryPageQuery;
import com.shoppilot.inventory.application.dto.PolicyRequest;
import com.shoppilot.inventory.application.dto.PolicyView;
import com.shoppilot.inventory.application.support.InventorySorts;
import com.shoppilot.inventory.infrastructure.persistence.entity.ReplenishmentPolicyEntity;
import com.shoppilot.inventory.infrastructure.persistence.mapper.ReplenishmentPolicyMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 补货策略服务（{@code API_CONTRACT §10.4}：replenishment-policies 列表/详情/upsert/停用）。
 *
 * <h2>边界（M3-T11/T14，B7）</h2>
 * <ul>
 *   <li>本服务只操作 inventory 自己的 {@code replenishment_policies}（owner = inventory，{@code DATABASE_DESIGN §5.8}）；</li>
 *   <li>upsert 用 {@code uk_pol_team_sku_wh (team_id, sku_id, warehouse_id)} 吸收并发冲突
 *       （先查后写 + 唯一约束兜底），<b>不</b>使用 {@code @InterceptorIgnore}（任务书「零新增 @InterceptorIgnore」）；</li>
 *   <li>停用走 {@code is_enabled = 0}，<b>不物理删除</b>（{@code §5.8}）；</li>
     *   <li>校验：{@code sku_id}/{@code warehouse_id} 必填（{@code COM-0001}）、二者须同团队
     *       （{@code CAT-3004}）、参数不得为负（{@code COM-0001}；注：{@code CAT-3010} 未固化，本批降级使用）；</li>
 *   <li>团队间资源隔离由租户线自动追加 {@code team_id} 保证，跨团队行查不到；</li>
 *   <li><b>审计（M3-T17 / B9）</b>：upsert 与 disable <b>同用</b> {@code #7 REPLENISHMENT_POLICY_UPSERT}
 *       （{@code §6-J.1 #7}「补货策略变更」覆盖创建/更新/停用），{@code biz_type=REPLENISHMENT_POLICY}、
 *       {@code biz_id=} 策略行 id；一次 upsert <b>只写一条</b>（不在内部再调 disable，避免双写）；
 *       写点在 {@code @Transactional} 方法体内、业务写库之后（{@code AuditWriteService} 走独立事务，
 *       失败只记 ERROR 不抛，故无需 try/catch）；无 {@code TeamContext} 团队上下文时不写。</li>
 * </ul>
 */
@Service
public class InventoryPolicyService {

    private static final Set<String> SORT_WHITELIST = Set.of(
            "id", "sku_id", "warehouse_id", "lead_time_days", "coverage_days",
            "safety_stock_qty", "moq", "is_enabled", "created_at", "updated_at", "version");

    private final ReplenishmentPolicyMapper policyMapper;
    private final SkuQueryService skuQueryService;
    private final WarehouseQueryService warehouseQueryService;
    private final AuditWriteService auditWriteService;

    public InventoryPolicyService(ReplenishmentPolicyMapper policyMapper,
                                  SkuQueryService skuQueryService,
                                  WarehouseQueryService warehouseQueryService,
                                  AuditWriteService auditWriteService) {
        this.policyMapper = policyMapper;
        this.skuQueryService = skuQueryService;
        this.warehouseQueryService = warehouseQueryService;
        this.auditWriteService = auditWriteService;
    }

    // =====================================================================
    // 列表（F-CAT-06）
    // =====================================================================

    @Transactional(readOnly = true)
    public PageResult<PolicyView> list(InventoryPageQuery query, Long skuId, Long warehouseId) {
        List<InventoryPageQuery.SortSpec> sort = query.validatedSort(SORT_WHITELIST);
        QueryWrapper<ReplenishmentPolicyEntity> wrapper = new QueryWrapper<>();
        if (skuId != null) {
            wrapper.eq("sku_id", skuId);
        }
        if (warehouseId != null) {
            wrapper.eq("warehouse_id", warehouseId);
        }
        InventorySorts.apply(wrapper, sort, "id", true);

        Page<ReplenishmentPolicyEntity> page = policyMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        Map<Long, String> skuCodes = skuCodesOf(page.getRecords().stream()
                .map(ReplenishmentPolicyEntity::getSkuId).collect(Collectors.toSet()));
        List<PolicyView> list = page.getRecords().stream()
                .map(e -> PolicyView.of(e, skuCodes.getOrDefault(e.getSkuId(), null)))
                .toList();
        return PageResult.of(page.getTotal(), query.getPage(), query.getPageSize(), list);
    }

    // =====================================================================
    // 详情（F-CAT-06）：不存在 / 异团队 → CAT-3001（404）
    // =====================================================================

    @Transactional(readOnly = true)
    public PolicyView get(long id) {
        ReplenishmentPolicyEntity entity = policyMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCodes.CAT_3001, "补货策略不存在");
        }
        SkuEntity sku = skuQueryService.getSku(entity.getSkuId());
        return PolicyView.of(entity, sku != null ? sku.getSkuCode() : null);
    }

    // =====================================================================
    // upsert（F-CAT-06，OPERATOR+）：按 (sku_id, warehouse_id) 全量替换
    // =====================================================================

    @Transactional
    public PolicyView upsert(PolicyRequest req) {
        if (req.getSkuId() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("sku_id", "sku_id 必填")));
        }
        if (req.getWarehouseId() == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("warehouse_id", "warehouse_id 必填")));
        }
        // 参数合法性（CAT-3010）
        assertNonNegative(req);

        // 团队归属：sku / warehouse 必须同团队（CAT-3004）
        if (skuQueryService.getSku(req.getSkuId()) == null) {
            throw new BizException(ErrorCodes.CAT_3004, "SKU 不属于当前团队或不存在");
        }
        try {
            warehouseQueryService.get(req.getWarehouseId());
        } catch (BizException be) {
            // 跨团队 → TEAM-2001；不存在 → CAT-3001；统一收敛为 CAT-3004（SKU/仓库须同团队）
            throw new BizException(ErrorCodes.CAT_3004, "仓库不属于当前团队或不存在");
        }

        Long operatorId = currentOperatorId();
        ReplenishmentPolicyEntity existing = selectByAnchor(req.getSkuId(), req.getWarehouseId());
        if (existing == null) {
            ReplenishmentPolicyEntity entity = toEntity(req);
            entity.setCreatedBy(operatorId);
            entity.setUpdatedBy(operatorId);
            try {
                policyMapper.insert(entity);
            } catch (DuplicateKeyException dke) {
                // 并发首次 upsert 冲突：唯一约束兜底，转更新
                ReplenishmentPolicyEntity race = selectByAnchor(req.getSkuId(), req.getWarehouseId());
                if (race == null) {
                    throw dke;
                }
                return applyExisting(req, race, operatorId);
            }
            // #7：创建也走「策略变更」（§6-J.1 #7 覆盖创建/更新/停用/启用），biz_id = 策略行 id；
            // before 传 null ⇒ 新建的字段在摘要里显示为「无 → 值」，即 §6-J.3 的前后值口径
            PolicyChange created = policyChange(entity.getId(), entity.getSkuId(), entity.getWarehouseId(),
                    null, req);
            recordPolicyAudit(new PolicyAudit(entity.getId(), created.summary(),
                    created.before(), created.after()));
            return toView(entity);
        }
        return applyExisting(req, existing, operatorId);
    }

    /**
     * 更新既有策略（含并发竞态兜底路径）：<b>先</b>在 before 快照上算差异 → 覆盖字段 → 写库 → 写审计。
     *
     * <p>顺序不可颠倒：{@link #copyFields} 会把 before 快照就地改掉，之后再算差异永远是「无变化」。
     */
    private PolicyView applyExisting(PolicyRequest req, ReplenishmentPolicyEntity existing, Long operatorId) {
        PolicyChange change = policyChange(existing.getId(), existing.getSkuId(), existing.getWarehouseId(),
                existing, req);
        copyFields(req, existing, operatorId);
        policyMapper.updateById(existing);
        recordPolicyAudit(new PolicyAudit(existing.getId(), change.summary(), change.before(), change.after()));
        return toView(existing);
    }

    // =====================================================================
    // 停用（F-CAT-06，OPERATOR+）：is_enabled = 0，不物理删除
    // =====================================================================

    @Transactional
    public PolicyView disable(long id) {
        ReplenishmentPolicyEntity entity = policyMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCodes.CAT_3001, "补货策略不存在");
        }
        Integer beforeEnabled = entity.getIsEnabled();
        entity.setIsEnabled(0);
        entity.setUpdatedBy(currentOperatorId());
        policyMapper.updateById(entity);
        recordPolicyAudit(new PolicyAudit(id, "停用策略 " + id,
                Map.of("is_enabled", beforeEnabled == null ? 0 : beforeEnabled),
                Map.of("is_enabled", 0)));
        return toView(entity);
    }

    // =====================================================================
    // 审计（M3-T17 / B9，§6-J.1 #7）
    // =====================================================================

    /**
     * 算「策略变更」的前后差异（<b>必须在 {@link #copyFields} 之前调用</b>）。
     *
     * <p>只记<b>实际变化</b>的字段（{@code §6-J.3} 前后值口径）：{@code null → 值} 记 {@code 无 → 值}，
     * {@code 值 → null} 记 {@code 值 → 无}（{@code copyFields} 是<b>全量替换</b>，未传的字段会被清空，
     * 属真实变更，不能漏记）。
     */
    private PolicyChange policyChange(Long policyId, Long skuId, Long warehouseId,
                                      ReplenishmentPolicyEntity before, PolicyRequest req) {
        List<String> changes = new ArrayList<>();
        Map<String, Object> beforeFields = new LinkedHashMap<>();
        Map<String, Object> afterFields = new LinkedHashMap<>();

        diff(changes, beforeFields, afterFields, "安全库存", "safety_stock_qty",
                before == null ? null : before.getSafetyStockQty(), req.getSafetyStockQty());
        diff(changes, beforeFields, afterFields, "提前期天数", "lead_time_days",
                before == null ? null : before.getLeadTimeDays(), req.getLeadTimeDays());
        diff(changes, beforeFields, afterFields, "补货周期天数", "coverage_days",
                before == null ? null : before.getCoverageDays(), req.getCoverageDays());
        diff(changes, beforeFields, afterFields, "安全系数", "safety_ratio",
                before == null ? null : before.getSafetyRatio(), req.getSafetyRatio());
        diff(changes, beforeFields, afterFields, "最小起订量", "moq",
                before == null ? null : before.getMoq(), req.getMoq());
        diff(changes, beforeFields, afterFields, "订购倍数", "order_multiple",
                before == null ? null : before.getOrderMultiple(), req.getOrderMultiple());
        diff(changes, beforeFields, afterFields, "数量步长", "qty_step",
                before == null ? null : before.getQtyStep(), req.getQtyStep());
        diff(changes, beforeFields, afterFields, "需求窗口天数", "window_days",
                before == null ? null : before.getWindowDays(), req.getWindowDays());
        diff(changes, beforeFields, afterFields, "最小覆盖天数", "min_days",
                before == null ? null : before.getMinDays(), req.getMinDays());
        diff(changes, beforeFields, afterFields, "高波动阈值", "cv_high",
                before == null ? null : before.getCvHigh(), req.getCvHigh());
        diff(changes, beforeFields, afterFields, "滞销判定天数", "slow_moving_days",
                before == null ? null : before.getSlowMovingDays(), req.getSlowMovingDays());
        diff(changes, beforeFields, afterFields, "超储判定天数", "overstock_days",
                before == null ? null : before.getOverstockDays(), req.getOverstockDays());
        // is_enabled：实体是 Integer，请求是 Boolean（null 与 copyFields 同口径视为 0）
        diff(changes, beforeFields, afterFields, "启用状态", "is_enabled",
                before == null ? null : before.getIsEnabled(),
                req.getIsEnabled() != null && req.getIsEnabled() ? 1 : 0);
        diff(changes, beforeFields, afterFields, "备注", "remark",
                before == null ? null : before.getRemark(), req.getRemark());

        String summary = "策略 " + policyId + " 变更：sku=" + skuId + " 仓库=" + warehouseId
                + (changes.isEmpty() ? "（无字段变化）" : " " + String.join(" / ", changes));
        return new PolicyChange(summary, beforeFields, afterFields);
    }

    private static void diff(List<String> changes, Map<String, Object> beforeFields,
                             Map<String, Object> afterFields, String label, String column,
                             Object oldValue, Object newValue) {
        if (sameValue(oldValue, newValue)) {
            return;
        }
        changes.add(label + " " + valueText(oldValue) + " → " + valueText(newValue));
        beforeFields.put(column, oldValue);
        afterFields.put(column, newValue);
    }

    /** 数值按 {@code compareTo} 判等（{@code 8.0} 与 {@code 8.000} 是同一值，不能记成变更）。 */
    private static boolean sameValue(Object oldValue, Object newValue) {
        if (oldValue instanceof BigDecimal a && newValue instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        return Objects.equals(oldValue, newValue);
    }

    private static String valueText(Object value) {
        if (value == null) {
            return "无";
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
    }

    /**
     * 写策略审计（{@code #7}）：{@code biz_id} = 策略行 id，{@code biz_type=REPLENISHMENT_POLICY}。
     *
     * <p>{@code AuditWriteService.record} 自带失败兜底（只记 ERROR 不抛），故不 try/catch；
     * 缺团队上下文即跳过，禁止造无主审计行。
     */
    private void recordPolicyAudit(PolicyAudit audit) {
        TeamContext.Snapshot snap = TeamContext.get();
        if (snap == null || snap.teamId() == null) {
            return;
        }
        auditWriteService.record(AuditEvent.builder()
                .teamId(snap.teamId())
                .action(AuditAction.REPLENISHMENT_POLICY_UPSERT)
                .bizType("REPLENISHMENT_POLICY")
                .bizId(audit.bizId())
                .operatorId(snap.userId())
                .operatorName(snap.username())
                .operatorRole(snap.roles().isEmpty() ? null : snap.roles().get(0))
                .result(AuditResult.SUCCESS)
                .changeSummary(audit.summary())
                .before(audit.beforeFields().isEmpty() ? null : audit.beforeFields())
                .after(audit.afterFields().isEmpty() ? null : audit.afterFields())
                .build());
    }

    /** 一条策略审计的载荷（摘要 + 前后字段；记录类型避免可变状态外泄）。 */
    private record PolicyAudit(Long bizId, String summary, Map<String, Object> beforeFields,
                               Map<String, Object> afterFields) {
    }

    /** {@link #policyChange} 的产物。 */
    private record PolicyChange(String summary, Map<String, Object> before,
                                Map<String, Object> after) {
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private ReplenishmentPolicyEntity selectByAnchor(long skuId, long warehouseId) {
        return policyMapper.selectOne(new LambdaQueryWrapper<ReplenishmentPolicyEntity>()
                .eq(ReplenishmentPolicyEntity::getSkuId, skuId)
                .eq(ReplenishmentPolicyEntity::getWarehouseId, warehouseId)
                .last("LIMIT 1"));
    }

    private void copyFields(PolicyRequest req, ReplenishmentPolicyEntity target, Long operatorId) {
        target.setLeadTimeDays(req.getLeadTimeDays());
        target.setCoverageDays(req.getCoverageDays());
        target.setSafetyStockQty(req.getSafetyStockQty());
        target.setSafetyRatio(req.getSafetyRatio());
        target.setMoq(req.getMoq());
        target.setOrderMultiple(req.getOrderMultiple());
        target.setQtyStep(req.getQtyStep());
        target.setWindowDays(req.getWindowDays());
        target.setMinDays(req.getMinDays());
        target.setCvHigh(req.getCvHigh());
        target.setSlowMovingDays(req.getSlowMovingDays());
        target.setOverstockDays(req.getOverstockDays());
        target.setIsEnabled(req.getIsEnabled() != null && req.getIsEnabled() ? 1 : 0);
        target.setRemark(req.getRemark());
        target.setUpdatedBy(operatorId);
    }

    private ReplenishmentPolicyEntity toEntity(PolicyRequest req) {
        ReplenishmentPolicyEntity e = new ReplenishmentPolicyEntity();
        e.setSkuId(req.getSkuId());
        e.setWarehouseId(req.getWarehouseId());
        e.setIsEnabled(req.getIsEnabled() != null && req.getIsEnabled() ? 1 : 0);
        copyFields(req, e, null);
        return e;
    }

    private PolicyView toView(ReplenishmentPolicyEntity e) {
        ReplenishmentPolicyEntity fresh = policyMapper.selectById(e.getId());
        SkuEntity sku = skuQueryService.getSku(fresh.getSkuId());
        return PolicyView.of(fresh, sku != null ? sku.getSkuCode() : null);
    }

    private void assertNonNegative(PolicyRequest req) {
        List<ApiErrorDetail> bad = new ArrayList<>();
        if (isNeg(req.getLeadTimeDays())) bad.add(new ApiErrorDetail("lead_time_days", "不可为负", req.getLeadTimeDays()));
        if (isNeg(req.getCoverageDays())) bad.add(new ApiErrorDetail("coverage_days", "不可为负", req.getCoverageDays()));
        if (isNeg(req.getWindowDays())) bad.add(new ApiErrorDetail("window_days", "不可为负", req.getWindowDays()));
        if (isNeg(req.getMinDays())) bad.add(new ApiErrorDetail("min_days", "不可为负", req.getMinDays()));
        if (isNeg(req.getSlowMovingDays())) bad.add(new ApiErrorDetail("slow_moving_days", "不可为负", req.getSlowMovingDays()));
        if (isNeg(req.getOverstockDays())) bad.add(new ApiErrorDetail("overstock_days", "不可为负", req.getOverstockDays()));
        if (isNeg(req.getSafetyStockQty())) bad.add(new ApiErrorDetail("safety_stock_qty", "不可为负", req.getSafetyStockQty()));
        if (isNeg(req.getSafetyRatio())) bad.add(new ApiErrorDetail("safety_ratio", "不可为负", req.getSafetyRatio()));
        if (isNeg(req.getMoq())) bad.add(new ApiErrorDetail("moq", "不可为负", req.getMoq()));
        if (isNeg(req.getOrderMultiple())) bad.add(new ApiErrorDetail("order_multiple", "不可为负", req.getOrderMultiple()));
        if (isNeg(req.getQtyStep())) bad.add(new ApiErrorDetail("qty_step", "不可为负", req.getQtyStep()));
        if (isNeg(req.getCvHigh())) bad.add(new ApiErrorDetail("cv_high", "不可为负", req.getCvHigh()));
        if (!bad.isEmpty()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "补货策略参数不合法", bad);
        }
    }

    private static boolean isNeg(Integer v) {
        return v != null && v < 0;
    }

    private static boolean isNeg(BigDecimal v) {
        return v != null && v.signum() < 0;
    }

    private Long currentOperatorId() {
        com.shoppilot.common.context.TeamContext.Snapshot snapshot = TeamContext.get();
        return snapshot == null ? null : snapshot.userId();
    }

    private Map<Long, String> skuCodesOf(Set<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return skuQueryService.getCodesByIds(new ArrayList<>(skuIds)).stream()
                .collect(Collectors.toMap(SkuEntity::getId, SkuEntity::getSkuCode, (a, b) -> a));
    }
}
