package com.shoppilot.purchasing.application.dto;

import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import java.util.List;

/**
 * 采购域分页查询参数（M6-T03）。
 *
 * <p>★ <b>本地一份，不复用 {@code replenishment}/{@code catalog} 的同类对象</b>：
 * 跨域复用会把别域的白名单/约束带进采购域（M5-T07 同款取舍）。
 *
 * <p>口径与 {@code API_CONTRACT §2} 一致：{@code page} 从 1 开始；{@code pageSize} 默认 20，
 * 上限 200；越界 ⇒ {@code COM-0007}（由调用方在装配过滤条件前校验）。
 */
public class PageQuery {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    private int page = DEFAULT_PAGE;
    private int pageSize = DEFAULT_PAGE_SIZE;

    public PageQuery() {
    }

    public PageQuery(int page, int pageSize) {
        this.page = page;
        this.pageSize = pageSize;
    }

    /** 校验并归一化（越界 ⇒ {@code COM-0007}）。 */
    public static PageQuery of(int page, int pageSize) {
        if (page < 1) {
            throw BizException.withDetails(ErrorCodes.COM_0007, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("page", "page 必须 >= 1")));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw BizException.withDetails(ErrorCodes.COM_0007, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("pageSize", "pageSize 必须在 1..200 之间")));
        }
        return new PageQuery(page, pageSize);
    }

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

    /** {@code selectPage} 的偏移量。 */
    public long offset() {
        return (long) (page - 1) * pageSize;
    }
}
