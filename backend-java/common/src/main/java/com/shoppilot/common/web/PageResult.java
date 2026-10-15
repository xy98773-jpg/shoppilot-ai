package com.shoppilot.common.web;

import java.util.Collections;
import java.util.List;

/**
 * 分页结果对象（基础设施）。
 *
 * <p>与 {@link PageQuery} 配对，承载一页记录与总量。不承载任何业务字段。
 */
@lombok.Data
public class PageResult<T> {

    private List<T> records = Collections.emptyList();
    private long total = 0;
    private int page = 1;
    private int size = 20;

    public static <T> PageResult<T> of(long total, int page, int size, List<T> records) {
        PageResult<T> r = new PageResult<>();
        r.setTotal(total);
        r.setPage(page);
        r.setSize(size);
        r.setRecords(records == null ? Collections.emptyList() : records);
        return r;
    }
}
