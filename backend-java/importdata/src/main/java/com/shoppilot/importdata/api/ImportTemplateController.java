package com.shoppilot.importdata.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.importdata.application.ImportQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导入模板清单端点（{@code GET /api/v1/import/templates}，{@code API_CONTRACT §10.6} F-IMP-02；
 * M3-B4 裁定 A5）。
 *
 * <h2>为何独立成类</h2>
 * 路径不落在 {@code /import/batches} 之下，与 {@link ImportBatchController} 的
 * {@code @RequestMapping} 前缀不兼容，故单列一个控制器（避免用相对路径 {@code ../} 之类的技巧）。
 *
 * <h2>权限</h2>
 * 契约要求「OPERATOR+」→ {@code @RequireRole({"OWNER","ADMIN","OPERATOR","APPROVER"})}
 * （{@code USER_ROLES §2.2} #1/#2；{@code APPROVER} 属 OPERATOR+ 集合，漏它就是最典型的错误）。
 * {@code teamIdParam=""}——路径无 {@code {teamId}}，团队来自 JWT；{@code VIEWER} → {@code 403 TEAM-2003}。
 */
@RestController
@RequestMapping("/api/v1/import/templates")
public class ImportTemplateController {

    private final ImportQueryService queryService;

    public ImportTemplateController(ImportQueryService queryService) {
        this.queryService = queryService;
    }

    /** 6 类 {@code bizType} 的字段 / 必填 / 别名清单（{@code ImportTemplateRegistry} 为唯一数据源）。 */
    @GetMapping
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<?>> templates() {
        return ResponseEntity.ok(ApiResponse.success(queryService.templates()));
    }
}
