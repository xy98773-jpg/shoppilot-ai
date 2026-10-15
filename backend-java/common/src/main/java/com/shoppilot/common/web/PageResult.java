package com.shoppilot.common.web;

import java.util.Collections;
import java.util.List;

/**
 * 分页结果对象（基础设施）。
 *
 * <p><b>字段严格对齐 {@code API_CONTRACT.md §2.2}</b>：分页响应把列表与分页元信息一起放进 {@code data}，
 * 字段名为 {@code list} / {@code page} / {@code pageSize} / {@code total}
 * （契约原文示例：{@code data: { list: [...], page: 1, pageSize: 20, total: 123 }}）。
 * 不承载任何业务字段。
 */
@lombok.Data
public class PageResult<T> {

    /** 当前页数据列表（契约字段名 {@code list}）。 */
    private List<T> list = Collections.emptyList();
    /** 满足筛选条件的总记录数（int64）。 */
    private long total = 0;
    /** 当前页码，从 1 开始（回显请求值）。 */
    private int page = 1;
    /** 每页大小，1..200（回显请求值）。 */
    private int pageSize = 20;

    public static <T> PageResult<T> of(long total, int page, int pageSize, List<T> list) {
        PageResult<T> r = new PageResult<>();
        r.setTotal(total);
        r.setPage(page);
        r.setPageSize(pageSize);
        r.setList(list == null ? Collections.emptyList() : list);
        return r;
    }
}
