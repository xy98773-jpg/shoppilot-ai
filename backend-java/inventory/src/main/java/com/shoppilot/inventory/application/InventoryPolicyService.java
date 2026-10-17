package com.shoppilot.inventory.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.List;
import java.util.Map;
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
 *   <li><b>不</b>在此做审计埋点（归 B9，inventory 不得引入 audit 依赖）。</li>
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

    public InventoryPolicyService(ReplenishmentPolicyMapper policyMapper,
                                  SkuQueryService skuQueryService,
                                  WarehouseQueryService warehouseQueryService) {
        this.policyMapper = policyMapper;
        this.skuQueryService = skuQueryService;
        this.warehouseQueryService = warehouseQueryService;
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
                copyFields(req, race, operatorId);
                policyMapper.updateById(race);
                return toView(race);
            }
            return toView(entity);
        }
        copyFields(req, existing, operatorId);
        policyMapper.updateById(existing);
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
        entity.setIsEnabled(0);
        entity.setUpdatedBy(currentOperatorId());
        policyMapper.updateById(entity);
        return toView(entity);
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
