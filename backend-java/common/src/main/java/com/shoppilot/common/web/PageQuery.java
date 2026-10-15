package com.shoppilot.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分页请求对象（基础设施）。
 *
 * <p>仅承载分页参数，不承载任何业务字段。页码从 1 开始；每页大小限制在 [1, 200]。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageQuery {

    private int page = 1;
    private int size = 20;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = Math.max(1, page);
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = Math.max(1, Math.min(200, size));
    }
}
