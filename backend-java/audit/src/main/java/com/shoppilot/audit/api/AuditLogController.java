package com.shoppilot.audit.api;

import com.shoppilot.audit.application.AuditLogQueryService;
import com.shoppilot.audit.application.dto.AuditLogItem;
import com.shoppilot.audit.application.dto.AuditLogQuery;
import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.common.web.PageResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志端点（{@code M6-T09} / {@code F-AUD-02}，{@code API_CONTRACT §10.10}）。
 *
 * <h2>端点</h2>
 * {@code GET /api/v1/audit/logs} —— 审计日志查询（分页 + 6 类筛选），{@code OWNER}/{@code ADMIN} 可读。
 *
 * <p><b>路径口径</b>：{@code /audit/logs}（契约 §10.10 与 §13.1 裁定 #8）。旧文档中的
 * {@code /audit-logs} 是历史写法，已裁定废弃（{@code API_CONTRACT:2279}）。
 *
 * <h2>角色与团队隔离</h2>
 * <ul>
 *   <li>{@link RequireRole}{@code ({"OWNER","ADMIN"}, teamIdParam = "")}：无路径 {@code {teamId}}，
 *       只做角色授权 → 越权 {@code TEAM-2003}（403）；未登录由安全链先返回 {@code AUTH-1001}（401）；</li>
 *   <li><b>团队隔离不在此层</b>：查询 SQL 由租户线自动追加 {@code team_id = TeamContext}，
 *       Controller/Service 都不接受客户端团队参数。</li>
 * </ul>
 *
 * <p>本批<b>只读</b>：不提供导出（{@code GET /audit/logs/export}，{@code F-AUD-03}，登记 {@code R181}，排 M7）。
 * 参数校验在 {@link AuditLogQueryService}：分页/日期区间越界 → {@code 400 COM-0007}
 * （{@code API_CONTRACT §2.2}）；筛选值/日期格式/排序非法 → {@code 400 COM-0001}（{@code B0-4}）。
 * 本层不做 SQL、不做分页计算。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditLogController {

    private final AuditLogQueryService queryService;

    public AuditLogController(AuditLogQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 审计日志查询（{@code F-AUD-02}）。
     *
     * @param page       页码（默认 1，≥1；越界 → {@code COM-0007}）
     * @param pageSize   每页条数（默认 20，1..200；越界 → {@code COM-0007}，不静默截断）
     * @param sort       排序：仅 {@code occurred_at,asc}/{@code occurred_at,desc}，缺省 DESC
     * @param bizType    业务对象类型（等值，可选）
     * @param bizId      业务对象 ID（可选；提供时必须同时提供 {@code biz_type}）
     * @param action     动作（等值，可选）
     * @param result     {@code SUCCESS}/{@code FAILURE}（可选）
     * @param operatorId 操作人 ID（可选）
     * @param dateFrom   起始日 {@code YYYY-MM-DD}（UTC，含当天，可选）
     * @param dateTo     结束日 {@code YYYY-MM-DD}（UTC，含当天，可选）
     */
    @GetMapping("/logs")
    @RequireRole(value = {"OWNER", "ADMIN"}, teamIdParam = "")
    public ApiResponse<PageResult<AuditLogItem>> logs(
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize,
            @RequestParam(name = "sort", required = false) List<String> sort,
            @RequestParam(name = "biz_type", required = false) String bizType,
            @RequestParam(name = "biz_id", required = false) Long bizId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "result", required = false) String result,
            @RequestParam(name = "operator_id", required = false) Long operatorId,
            @RequestParam(name = "date_from", required = false) String dateFrom,
            @RequestParam(name = "date_to", required = false) String dateTo) {
        AuditLogQuery query = new AuditLogQuery();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setSort(sort);
        query.setBizType(bizType);
        query.setBizId(bizId);
        query.setAction(action);
        query.setResult(result);
        query.setOperatorId(operatorId);
        query.setDateFrom(dateFrom);
        query.setDateTo(dateTo);
        return ApiResponse.success(queryService.listAuditLogs(query));
    }
}
