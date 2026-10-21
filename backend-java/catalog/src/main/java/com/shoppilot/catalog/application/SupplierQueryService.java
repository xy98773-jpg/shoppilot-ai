package com.shoppilot.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.catalog.application.dto.CatalogPageQuery;
import com.shoppilot.catalog.application.dto.SupplierView;
import com.shoppilot.catalog.application.support.CatalogGuards;
import com.shoppilot.catalog.application.support.CatalogSorts;
import com.shoppilot.catalog.infrastructure.persistence.entity.SupplierEntity;
import com.shoppilot.catalog.infrastructure.persistence.mapper.SupplierMapper;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.PageResult;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 供应商只读查询服务（{@code MODULE_BOUNDARIES §2.3} 对外只读入口之一）。
 */
@Service
public class SupplierQueryService {

    private static final Set<String> SORT_WHITELIST =
            Set.of("id", "code", "name", "status", "lead_time_days", "created_at", "updated_at");

    private final SupplierMapper supplierMapper;

    public SupplierQueryService(SupplierMapper supplierMapper) {
        this.supplierMapper = supplierMapper;
    }

    /** 供应商列表（{@code GET /api/v1/suppliers}，分页）。 */
    public PageResult<SupplierView> list(CatalogPageQuery query) {
        List<CatalogPageQuery.SortSpec> sort = query.validatedSort(SORT_WHITELIST);
        QueryWrapper<SupplierEntity> wrapper = new QueryWrapper<>();
        CatalogSorts.apply(wrapper, sort, "created_at", true);

        Page<SupplierEntity> page = supplierMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        List<SupplierView> list = page.getRecords().stream().map(SupplierView::of).toList();
        return PageResult.of(page.getTotal(), query.getPage(), query.getPageSize(), list);
    }

    /** 供应商详情（{@code GET /api/v1/suppliers/{id}}）：异团队 → 403，不存在 → 404 CAT-3001。 */
    public SupplierView get(Long id) {
        return SupplierView.of(requireInTeam(id));
    }

    /** 取供应商实体（{@code MODULE_BOUNDARIES §2.3}，供下游读取）。 */
    public SupplierEntity getById(Long id) {
        return supplierMapper.selectById(id);
    }

    /** 取供应商级交期 L（{@code MODULE_BOUNDARIES §2.3}）；不存在或未配置 → {@code null}。 */
    public Integer getLeadTimeDays(Long id) {
        SupplierEntity e = supplierMapper.selectById(id);
        return e == null ? null : e.getLeadTimeDays();
    }

    /** 归属判定（受控探测区分 403/404）。 */
    public SupplierEntity requireInTeam(Long id) {
        return CatalogGuards.requireInTeam(
                supplierMapper.selectById(id),
                () -> supplierMapper.selectTeamIdById(id),
                ErrorCodes.CAT_3001, "供应商不存在");
    }

    /**
     * 供应商是否<b>属于当前团队</b>（M5-T03 跨模块只读入口）。
     *
     * <p>★ 为什么需要它：{@code replenishment} 的 {@code PATCH /items/{id}} 要校验
     * {@code supplier_id} 同团队（{@code RPL-8009}），但 {@code MODULE_BOUNDARIES §2.3}
     * 禁止它直接注入 {@code SupplierMapper}。故由 catalog 提供这一个只读判定。
     *
     * <p>★ 入参<b>不含 teamId</b>：团队来自 {@code TeamContext}，查询走租户线
     * （跨团队行查不到 ⇒ 返回 {@code false}，与「不存在」同表现，不泄漏他团队是否存在）。
     *
     * <p>★ 只判存在性，不区分「不存在」与「跨团队」——调用方统一映射
     * {@code RPL-8009}（403，{@code ERROR_CODES §2.9} 的语义就是「指定供应商不属于该团队」）。
     *
     * @param supplierId 供应商主键（{@code null} ⇒ {@code false}）
     * @return 当前团队内存在该供应商 ⇒ {@code true}
     */
    public boolean existsInTeam(Long supplierId) {
        return supplierId != null && supplierMapper.selectById(supplierId) != null;
    }

    // =====================================================================
    // 分析装配批量只读（M8-T06 / R126：analysis 经本门面而非直连 SupplierMapper）
    // =====================================================================

    /**
     * 按 {@code id} 批量取供应商实体（供 {@code analysis} 装配交期 {@code SUPPLIER} 来源与采购参数）。
     *
     * <p>★ 租户线约束：跨团队 / 不存在的 {@code id} 不出现在结果中（调用方按「缺失即无」处理）。
     *
     * @return {@code supplier_id -> 实体}；入参可空/空/含 {@code null} ⇒ 空 {@code Map}
     */
    public Map<Long, SupplierEntity> getByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, SupplierEntity> byId = new LinkedHashMap<>();
        for (SupplierEntity supplier : supplierMapper.selectBatchIds(distinct)) {
            byId.put(supplier.getId(), supplier);
        }
        return byId;
    }
}
