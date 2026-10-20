package com.shoppilot.replenishment.api;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.replenishment.application.ReplenishmentItemService;
import com.shoppilot.replenishment.application.dto.ApprovalCommandRequest;
import com.shoppilot.replenishment.application.dto.ReplenishmentItemResponse;
import com.shoppilot.replenishment.application.dto.ReplenishmentReviewActionResponse;
import com.shoppilot.replenishment.application.dto.ReviewCommandRequest;
import com.shoppilot.replenishment.application.dto.UpdateReplenishmentItemRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 补货建议详情与人工改量（M5-T03；{@code API_CONTRACT §10.8}，F-RPL-01 / F-RPL-02）。
 *
 * <h2>本批端点清单</h2>
 * <ul>
 *   <li>{@code GET /api/v1/replenishment/items/{id}} —— 详情（<b>任何已登录成员，含 VIEWER</b>，200；
 *       返回表列全集且<b>含 {@code version}</b>——它是 {@code PATCH} 的并发令牌）；</li>
 *   <li>{@code PATCH /api/v1/replenishment/items/{id}} —— 人工改量（{@code OPERATOR+}，200；
 *       可改 {@code qty}/{@code supplier_id}/{@code expected_arrival_date}/{@code remark}）。</li>
 *   <li>{@code POST /api/v1/replenishment/items/{id}/submit} —— 提交审核（{@code OPERATOR+}，200；
 *       {SUGGESTED → PENDING_REVIEW}）；</li>
 *   <li>{@code POST /api/v1/replenishment/items/{id}/withdraw} —— 撤回提交（{@code OPERATOR+}，200；
 *       {PENDING_REVIEW → SUGGESTED}）。</li>
 * </ul>
 *
 * <p><b>不做</b>（后续批次）：列表、approve/reject（M5-T05）、cancel/SUPERSEDED（M5-T06）、
 * 审核记录时间线（F-RPL-06）。
 *
 * <p>★ 详情<b>不加</b> {@code @RequireRole}：只读、隔离由租户线保证
 * （与 {@code GET /analysis/runs/{id}} 同口径）。写操作按 {@code §10.8} 要求 {@code OPERATOR+}。
 *
 * <p>★ {@code PATCH} 的并发语义：客户端须回传详情里的 {@code version}；不匹配 ⇒
 * 409 {@code RPL-8003}（可重试），客户端刷新后重试即可。
 */
@RestController
@RequestMapping("/api/v1/replenishment")
public class ReplenishmentItemController {

    private final ReplenishmentItemService itemService;

    public ReplenishmentItemController(ReplenishmentItemService itemService) {
        this.itemService = itemService;
    }

    /**
     * 建议详情（{@code GET /items/{id}}）。
     *
     * @param id 建议 ID（当前团队）
     * @return 200 + 详情（含 {@code version}）；不存在/跨团队 ⇒ 404 {@code RPL-8004}
     */
    @GetMapping("/items/{id}")
    public ResponseEntity<ApiResponse<ReplenishmentItemResponse>> getItem(@PathVariable("id") long id) {
        return ResponseEntity.ok(ApiResponse.success(itemService.get(id)));
    }

    /**
     * 人工改量（{@code PATCH /items/{id}}）。
     *
     * <p>部分更新：未传/显式 {@code null} 均表示「不修改」；{@code remark=""} 落空串。
     * 校验与错误码见 {@link ReplenishmentItemService#update}。
     *
     * @param id      建议 ID（当前团队）
     * @param request 请求体（{@code version} 必填；至少一个可改字段）
     * @return 200 + 更新后详情（{@code version} 为新值）
     */
    @PatchMapping("/items/{id}")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<ReplenishmentItemResponse>> updateItem(
            @PathVariable("id") long id,
            @RequestBody UpdateReplenishmentItemRequest request) {

        return ResponseEntity.ok(ApiResponse.success(itemService.update(id, request)));
    }

    /**
     * 提交审核（{@code POST /items/{id}/submit}，{@code SUGGESTED → PENDING_REVIEW}）。
     *
     * <p>请求体<b>可空</b>（契约 §11.7 示例即无请求体）；可选 {@code {"comment":"..."}}（≤500 字符）。
     *
     * @param id      建议 ID（当前团队）
     * @param request 可空请求体
     * @return 200 + {@link ReplenishmentReviewActionResponse}（含 {@code review_id}）；
     *         已提交 ⇒ 409 {@code RPL-8002}；其它状态 ⇒ 422 {@code RPL-8001}
     */
    @PostMapping("/items/{id}/submit")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<ReplenishmentReviewActionResponse>> submit(
            @PathVariable("id") long id,
            @RequestBody(required = false) ReviewCommandRequest request) {

        return ResponseEntity.ok(ApiResponse.success(itemService.submit(id, commentOf(request))));
    }

    /**
     * 撤回提交（{@code POST /items/{id}/withdraw}，{@code PENDING_REVIEW → SUGGESTED}）。
     *
     * <p>★ {@code submitted_at} <b>不清空</b>（{@code §5.55} 裁定 4）。请求体同 submit。
     *
     * @param id      建议 ID（当前团队）
     * @param request 可空请求体
     * @return 200 + {@link ReplenishmentReviewActionResponse}；
     *         非 {@code PENDING_REVIEW} ⇒ 422 {@code RPL-8001}
     */
    @PostMapping("/items/{id}/withdraw")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<ReplenishmentReviewActionResponse>> withdraw(
            @PathVariable("id") long id,
            @RequestBody(required = false) ReviewCommandRequest request) {

        return ResponseEntity.ok(ApiResponse.success(itemService.withdraw(id, commentOf(request))));
    }

    private static String commentOf(ReviewCommandRequest request) {
        return request == null ? null : request.getComment();
    }

    /**
     * 审批通过（{@code POST /items/{id}/approve}，{@code PENDING_REVIEW → APPROVED}）。
     *
     * <p>★ 权限<b>不含 OPERATOR</b>（契约 §10.8：APPROVER/ADMIN/OWNER）。
     *
     * @param id      建议 ID（当前团队）
     * @param request 请求体（{@code expected_version} 必填；{@code comment} 可选）
     * @return 200 + 动作结果（含 {@code approved_by}/{@code approved_at}/{@code version}/{@code review_id}）；
     *         已审批 ⇒ 409 {@code RPL-8002}；状态不符 ⇒ 422 {@code RPL-8001}；
     *         版本冲突 ⇒ 409 {@code RPL-8003}（带 {@code details}）
     */
    @PostMapping("/items/{id}/approve")
    @RequireRole(value = {"OWNER", "ADMIN", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<ReplenishmentReviewActionResponse>> approve(
            @PathVariable("id") long id,
            @RequestBody(required = false) ApprovalCommandRequest request) {

        return ResponseEntity.ok(ApiResponse.success(itemService.approve(id, request)));
    }

    /**
     * 驳回（{@code POST /items/{id}/reject}，{@code PENDING_REVIEW → REJECTED}，终态）。
     *
     * <p>★ {@code comment} 必填且非空白，否则 400 {@code RPL-8005}；★ 不写 {@code approved_*}，
     * 写 {@code closed_at}。
     *
     * @param id      建议 ID（当前团队）
     * @param request 请求体（{@code expected_version} 必填；{@code comment} 必填非空白）
     * @return 200 + 动作结果（含 {@code closed_at}/{@code version}/{@code review_id}）
     */
    @PostMapping("/items/{id}/reject")
    @RequireRole(value = {"OWNER", "ADMIN", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<ReplenishmentReviewActionResponse>> reject(
            @PathVariable("id") long id,
            @RequestBody(required = false) ApprovalCommandRequest request) {

        return ResponseEntity.ok(ApiResponse.success(itemService.reject(id, request)));
    }
}
