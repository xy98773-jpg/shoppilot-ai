package com.shoppilot.inventory.application.dto;

import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * inventory 列表分页 / 排序查询参数（契约 {@code API_CONTRACT §2.2}）。
 *
 * <p>与 {@code catalog.CatalogPageQuery} 同口径：本类<b>不做静默夹取</b>——{@code pageSize > 200}
 * 时契约要求返回 {@code COM-0007}（{@code §2.2}），而不是悄悄截断成 200。校验在
 * {@link #validatedSort(Set)} 中显式抛 {@link BizException}（带 {@code details[]}）。
 *
 * <p>{@code sort} 语法（{@code §2.2}）：形如 {@code sort=created_at,desc}；多字段用重复参数
 * {@code sort=status,asc&sort=created_at,desc}。字段须落在各资源白名单内，方向为 {@code asc|desc}，
 * 否则 {@code COM-0007}。白名单列均为数据库物理列名（snake_case），可直接用于 ORDER BY（白名单即防注入）。
 */
public class InventoryPageQuery {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    private int page = 1;
    private int pageSize = DEFAULT_PAGE_SIZE;
    private List<String> sort = List.of();

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public List<String> getSort() {
        return sort;
    }

    public void setSort(List<String> sort) {
        this.sort = sort == null ? List.of() : sort;
    }

    /** 排序片段（已校验的物理列名 + 方向）。 */
    public record SortSpec(String column, boolean ascending) {
    }

    /** 由控制器查询参数装配（{@code sort} 可为 {@code null}）。 */
    public static InventoryPageQuery of(int page, int pageSize, List<String> sort) {
        InventoryPageQuery q = new InventoryPageQuery();
        q.setPage(page);
        q.setPageSize(pageSize);
        q.setSort(sort);
        return q;
    }

    /**
     * 校验分页 / 排序参数并解析排序片段；非法即 {@code 400 COM-0007}（携带 {@code details[]}）。
     *
     * @param allowedSortColumns 该资源允许排序的物理列白名单（snake_case）
     * @return 解析后的排序片段（保持请求顺序）；无 {@code sort} 时返回空列表（由服务层套用默认排序）
     */
    public List<SortSpec> validatedSort(Set<String> allowedSortColumns) {
        List<ApiErrorDetail> details = new ArrayList<>();
        if (page < 1) {
            details.add(new ApiErrorDetail("page", "page 必须 ≥ 1", page));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            details.add(new ApiErrorDetail("pageSize", "pageSize 必须在 1.." + MAX_PAGE_SIZE + " 之间", pageSize));
        }

        // ★ Spring 会把 `sort=code,asc` 按逗号拆成 ["code","asc"] 两个元素（多值参数绑定），
        //   故先把所有片段摊平，再按「列[,方向]」成对解析（契约 §2.2 的 `sort=字段,方向`）。
        List<String> tokens = new ArrayList<>();
        for (String raw : sort) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    tokens.add(part.trim());
                }
            }
        }

        List<SortSpec> specs = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            String column = tokens.get(i);
            if (!allowedSortColumns.contains(column)) {
                details.add(new ApiErrorDetail("sort", "排序字段不在白名单内", column));
                i++;
                continue;
            }
            boolean ascending = true;
            if (i + 1 < tokens.size()) {
                String dir = tokens.get(i + 1).toLowerCase();
                if (dir.equals("asc") || dir.equals("desc")) {
                    ascending = dir.equals("asc");
                    i += 2;
                    specs.add(new SortSpec(column, ascending));
                    continue;
                }
            }
            specs.add(new SortSpec(column, ascending));
            i++;
        }

        if (!details.isEmpty()) {
            throw BizException.withDetails(ErrorCodes.COM_0007, "分页或排序参数不合法", details);
        }
        return specs;
    }
}
