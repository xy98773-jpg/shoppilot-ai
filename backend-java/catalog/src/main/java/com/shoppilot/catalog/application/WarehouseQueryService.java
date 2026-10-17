package com.shoppilot.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.catalog.application.dto.CatalogPageQuery;
import com.shoppilot.catalog.application.dto.WarehouseView;
import com.shoppilot.catalog.application.support.CatalogGuards;
import com.shoppilot.catalog.application.support.CatalogSorts;
import com.shoppilot.catalog.infrastructure.persistence.entity.WarehouseEntity;
import com.shoppilot.catalog.infrastructure.persistence.mapper.WarehouseMapper;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.PageResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 仓库只读查询服务（{@code MODULE_BOUNDARIES §2.3}：catalog 对外只读入口之一）。
 *
 * <p>读端点对「任何已登录成员」开放（{@code API_CONTRACT §10.3}），故此处不做角色判定；团队隔离由
 * 租户线兜底，跨团队 403 / 不存在 404 由 {@link CatalogGuards} 区分。
 */
@Service
public class WarehouseQueryService {

    /** 允许排序的物理列白名单（{@code §2.2}：白名单外 → COM-0007）。 */
    private static final Set<String> SORT_WHITELIST =
            Set.of("id", "code", "name", "status", "is_default", "created_at", "updated_at");

    /** 单次 {@code IN} 查询的编码上限（避免超大 IN 列表把 SQL 语句撑爆）。 */
    private static final int CODE_CHUNK_SIZE = 1000;

    private final WarehouseMapper warehouseMapper;

    public WarehouseQueryService(WarehouseMapper warehouseMapper) {
        this.warehouseMapper = warehouseMapper;
    }

    /** 仓库列表（{@code GET /api/v1/warehouses}，分页）。 */
    public PageResult<WarehouseView> list(CatalogPageQuery query) {
        List<CatalogPageQuery.SortSpec> sort = query.validatedSort(SORT_WHITELIST);
        QueryWrapper<WarehouseEntity> wrapper = new QueryWrapper<>();
        CatalogSorts.apply(wrapper, sort, "created_at", true);

        Page<WarehouseEntity> page = warehouseMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        List<WarehouseView> list = page.getRecords().stream().map(WarehouseView::of).toList();
        return PageResult.of(page.getTotal(), query.getPage(), query.getPageSize(), list);
    }

    /** 仓库详情（{@code GET /api/v1/warehouses/{id}}）：异团队 → 403，不存在 → 404 CAT-3003。 */
    public WarehouseView get(Long id) {
        return WarehouseView.of(requireInTeam(id));
    }

    /**
     * 取当前团队的默认仓（{@code MODULE_BOUNDARIES §2.3}，供 inventory/analysis 等下游使用）。
     *
     * @return 默认仓实体；无则 {@code null}
     */
    public WarehouseEntity getDefaultWarehouse() {
        return warehouseMapper.selectOne(new QueryWrapper<WarehouseEntity>()
                .eq("is_default", true)
                .eq("status", "ACTIVE")
                .last("LIMIT 1"));
    }

    /** 取当前团队全部启用仓库（{@code MODULE_BOUNDARIES §2.3}）。 */
    public List<WarehouseEntity> listActive() {
        return warehouseMapper.selectList(new QueryWrapper<WarehouseEntity>()
                .eq("status", "ACTIVE")
                .orderByAsc("code"));
    }

    /** 归属判定（受租户线约束查不到时，用受控探测区分 403/404）。 */
    public WarehouseEntity requireInTeam(Long id) {
        return CatalogGuards.requireInTeam(
                warehouseMapper.selectById(id),
                () -> warehouseMapper.selectTeamIdById(id),
                ErrorCodes.CAT_3003, "仓库不存在");
    }

    /**
     * <b>按业务编码批量解析 {@code code → id}</b>（M3-B10.5 / R78；同 {@code SkuQueryService#getIdsByCodes}）。
     *
     * <p>一次 {@code IN} 查询（超长按 {@value #CODE_CHUNK_SIZE} 分片）代替逐行解析，
     * 理由与 {@code CatalogWriteService#preloadWarehouseIdsByCode} 一致（逐行 1.91 ms/次）。
     *
     * <p><b>口径</b>：只判「存在且同团队」（{@code IMPORT_WORKFLOW §7.5}），<b>不</b>按 {@code status} 过滤；
     * 跨团队同编码由租户线过滤后自然查不到，由调用方按 {@code CAT-3003} 处理。
     *
     * @param codes 仓库编码集合（{@code null}/空白元素自动跳过；全空 → 空 {@code Map}）
     * @return 命中的 {@code code → id}；未命中的编码<b>不出现</b>
     */
    public Map<String, Long> getIdsByCodes(Collection<String> codes) {
        Set<String> distinct = new LinkedHashSet<>();
        if (codes != null) {
            for (String code : codes) {
                if (code != null && !code.isBlank()) {
                    distinct.add(code.trim());
                }
            }
        }
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>(distinct.size() * 2);
        List<String> all = new ArrayList<>(distinct);
        for (int from = 0; from < all.size(); from += CODE_CHUNK_SIZE) {
            List<String> chunk = all.subList(from, Math.min(from + CODE_CHUNK_SIZE, all.size()));
            List<WarehouseEntity> rows = warehouseMapper.selectList(new QueryWrapper<WarehouseEntity>()
                    .select("id", "code")
                    .in("code", chunk));
            for (WarehouseEntity row : rows) {
                ids.put(row.getCode(), row.getId());
            }
        }
        return ids;
    }
}
