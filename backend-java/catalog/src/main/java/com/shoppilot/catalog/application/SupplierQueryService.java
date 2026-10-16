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
import java.util.List;
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
}
