package com.shoppilot.audit.application.dto;

import java.util.List;

/**
 * 审计日志查询参数（{@code GET /api/v1/audit/logs}，{@code API_CONTRACT §10.10}，M6-T09 / F-AUD-02）。
 *
 * <h2>分页口径（{@code API_CONTRACT §2.2}，与 purchasing/inventory/catalog/importdata 一致）</h2>
 * {@code page} 默认 1、{@code pageSize} 默认 20；<b>越界不夹取</b>：
 * {@code page < 1} 或 {@code pageSize ∉ 1..200} ⇒ {@code 400 COM-0007}
 * （由 {@link com.shoppilot.audit.application.AuditLogQueryService} 统一校验）。
 * 本类<b>刻意不继承</b> {@code common.web.PageQuery}——后者的 setter 会把越界值静默夹到边界，
 * 与本端点「越界必须报错」的口径冲突（{@code common.PageQuery} javadoc 也写明
 * 「超过 200 由业务层返回 COM-0007」，夹取只是实现细节）。
 *
 * <h2>筛选口径（B0-4）</h2>
 * <ul>
 *   <li>{@code biz_type} / {@code action}：等值、可选；空白视为未传；</li>
 *   <li>{@code biz_id}：可选，但<b>给出时必须同时给 {@code biz_type}</b>，否则 {@code COM-0001}；</li>
 *   <li>{@code result}：{@code SUCCESS}/{@code FAILURE}，非法值 {@code COM-0001}；</li>
 *   <li>{@code operator_id}：可选，等值；</li>
 *   <li>{@code date_from} / {@code date_to}：{@code YYYY-MM-DD}，作用于 {@code occurred_at}（UTC），
 *       区间为 {@code [date_from 00:00, date_to + 1d 00:00)}；格式非法 {@code COM-0001}，
 *       {@code date_from > date_to} 属<b>分页/排序参数不合法</b> ⇒ {@code COM-0007}
 *       （与 {@code InventoryQueryService} 同口径）；</li>
 *   <li>{@code sort}：只接受 {@code occurred_at,asc|desc}（缺省 {@code occurred_at,desc}），
 *       其余 {@code COM-0001}。<b>方向不进 SQL 拼接</b>——由 Mapper 的
 *       {@code <choose>} 在两个固定片段间选择（白名单且不可注入）。</li>
 * </ul>
 *
 * <p>本类只承载参数，<b>不做</b>数据库访问；校验与查询在
 * {@link com.shoppilot.audit.application.AuditLogQueryService}。
 */
public class AuditLogQuery {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    private int page = 1;
    private int pageSize = DEFAULT_PAGE_SIZE;
    private String bizType;
    private Long bizId;
    private String action;
    private String result;
    private Long operatorId;
    private String dateFrom;
    private String dateTo;
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

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Long getBizId() {
        return bizId;
    }

    public void setBizId(Long bizId) {
        this.bizId = bizId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(String dateFrom) {
        this.dateFrom = dateFrom;
    }

    public String getDateTo() {
        return dateTo;
    }

    public void setDateTo(String dateTo) {
        this.dateTo = dateTo;
    }

    public List<String> getSort() {
        return sort;
    }

    public void setSort(List<String> sort) {
        this.sort = sort == null ? List.of() : sort;
    }
}
