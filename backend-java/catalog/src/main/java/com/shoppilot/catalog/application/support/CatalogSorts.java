package com.shoppilot.catalog.application.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shoppilot.catalog.application.dto.CatalogPageQuery.SortSpec;
import java.util.List;

/**
 * 列表排序装配：把已校验的 {@link SortSpec} 落到 {@code ORDER BY}。
 *
 * <p>列名来自各资源白名单（snake_case 物理列），{@link CatalogPageQuery#validatedSort} 已保证非白名单列不会进入
 * 本类——白名单即防注入边界，绝不用客户端原始输入拼接 SQL。
 */
public final class CatalogSorts {

    private CatalogSorts() {
    }

    /**
     * 应用排序；无显式 {@code sort} 时套用默认排序（保证列表分页结果稳定、可复现）。
     *
     * @param wrapper       查询包装器
     * @param specs         已校验的排序片段（保持请求顺序）；空表示使用默认排序
     * @param defaultColumn 默认排序列
     * @param defaultDesc   默认是否倒序
     */
    public static <T> void apply(QueryWrapper<T> wrapper, List<SortSpec> specs,
                                 String defaultColumn, boolean defaultDesc) {
        if (specs == null || specs.isEmpty()) {
            if (defaultDesc) {
                wrapper.orderByDesc(defaultColumn);
            } else {
                wrapper.orderByAsc(defaultColumn);
            }
            return;
        }
        for (SortSpec spec : specs) {
            if (spec.ascending()) {
                wrapper.orderByAsc(spec.column());
            } else {
                wrapper.orderByDesc(spec.column());
            }
        }
    }
}
