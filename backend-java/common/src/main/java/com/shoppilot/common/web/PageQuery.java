package com.shoppilot.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分页请求对象（基础设施）。
 *
 * <p>参数名严格对齐 {@code API_CONTRACT.md §2.2} 分页规范：{@code page}（默认 1，≥1，从 1 开始）、
 * {@code pageSize}（默认 20，1..200，超过 200 由业务层返回 {@code COM-0007}）。
 * 仅承载分页参数，不承载任何业务字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageQuery {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    private int page = 1;
    private int pageSize = DEFAULT_PAGE_SIZE;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = Math.max(1, page);
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
    }
}
