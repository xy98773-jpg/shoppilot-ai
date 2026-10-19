package com.shoppilot.analysis.api;

import com.shoppilot.analysis.application.AnalysisAppService;
import com.shoppilot.analysis.application.dto.CreateAnalysisRunRequest;
import com.shoppilot.analysis.application.dto.CreateRunOutcome;
import com.shoppilot.analysis.application.dto.AnalysisRunStatusResponse;
import com.shoppilot.analysis.application.dto.CreateRunResponse;
import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.ApiResponse;
import com.shoppilot.common.web.BizException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分析任务端点（M4-T03；{@code API_CONTRACT.md §11.5}）。
 *
 * <h2>本批端点清单</h2>
 * <ul>
 *   <li>{@code POST /api/v1/analysis/runs} —— 创建分析任务（{@code OPERATOR+}，202）。</li>
 *   <li>{@code GET /api/v1/analysis/runs/{id}} —— 运行状态与进度（<b>任何已登录成员，含 VIEWER</b>，200）。</li>
 * </ul>
 *
 * <p><b>不做</b>（留给 B20.3/B22）：取消（M4-T14）、结果明细（M4-T13）、列表、导出。
 *
 * <h2>★ 三个边界</h2>
 * <ol>
 *   <li><b>角色</b>：{@code @RequireRole({"OWNER","ADMIN","OPERATOR","APPROVER"}, teamIdParam="")}
 *       —— 静态声明式拦截 VIEWER（{@code TEAM-2003}，{@code API_CONTRACT §9} 矩阵）。
 *       路径无 {@code {teamId}}，故 {@code teamIdParam=""} 跳过归属步（团队来自 JWT）。</li>
 *   <li><b>幂等键</b>：缺失/空白/超 128 → {@code COM-0010}（{@code ERROR_CODES §2.1}）；
 *       不在 Controller 做「业务幂等」，只做「必填与长度」的门槛。</li>
 *   <li><b>响应</b>：成功一律 202（含幂等重放）；重放时额外带 {@code Idempotent-Replayed: true}。
 *       错误走 {@code GlobalExceptionHandler}（{@code data=null}，裁定⑥）。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    /** 幂等重放标记头（{@code API_CONTRACT §6}：同键同体重放时标注）。 */
    public static final String HEADER_IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    /** 幂等键请求头名（{@code API_CONTRACT §6}）。 */
    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    /** 幂等键最大长度（{@code API_CONTRACT §6}：1..128）。 */
    private static final int MAX_KEY_LENGTH = 128;

    private final AnalysisAppService analysisAppService;

    public AnalysisController(AnalysisAppService analysisAppService) {
        this.analysisAppService = analysisAppService;
    }

    /**
     * 创建分析任务（{@code POST /api/v1/analysis/runs}，{@code OPERATOR+}）。
     *
     * @param request        请求体（{@code team_id} 不在契约中，客户端即便传入也被忽略）
     * @param idempotencyKey 幂等键（必填，1..128）
     * @return 202 + {@link CreateRunResponse}；重放时附 {@code Idempotent-Replayed: true}
     */
    @PostMapping("/runs")
    @RequireRole(value = {"OWNER", "ADMIN", "OPERATOR", "APPROVER"}, teamIdParam = "")
    public ResponseEntity<ApiResponse<CreateRunResponse>> createRun(
            @Valid @RequestBody CreateAnalysisRunRequest request,
            @RequestHeader(value = HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey) {

        String key = requireIdempotencyKey(idempotencyKey);
        CreateRunOutcome outcome = analysisAppService.createRun(request, key);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (outcome.replayed()) {
            builder.header(HEADER_IDEMPOTENT_REPLAYED, "true");
        }
        return builder.body(ApiResponse.success(outcome.data()));
    }

    /**
     * 查询分析运行状态（{@code GET /api/v1/analysis/runs/{id}}）。
     *
     * <p>★ 权限：<b>任何已登录成员（含 VIEWER）</b>——只读状态，不改任何业务数据
     * （{@code API_CONTRACT §11.5} 的端点表与 {@code §9} 权限矩阵均如此规定）。
     * 因此<b>不加</b> {@code @RequireRole}：那是给「OPERATOR+」的写操作用的，
     * 加了会把 VIEWER 挡在轮询页外面。
     *
     * <p>★ 读时同步：中间态会先向 Python 拉一次状态；同步失败<b>静默降级</b>返回库内现状
     * （{@code R134}），本方法不抛异常。
     *
     * @param runId 运行 ID；不存在或跨团队均取 {@code ANL-7005}（404，不区分两者）
     */
    @GetMapping("/runs/{id}")
    public ResponseEntity<ApiResponse<AnalysisRunStatusResponse>> getRunStatus(@PathVariable("id") Long runId) {
        return ResponseEntity.ok(ApiResponse.success(analysisAppService.getRunStatus(runId)));
    }

    /** 幂等键必填校验（缺失/空白/超长 → {@code COM-0010}，400）。 */
    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCodes.COM_0010, "缺少必要的请求信息");
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > MAX_KEY_LENGTH) {
            throw BizException.withDetails(ErrorCodes.COM_0010, "缺少必要的请求信息",
                    List.of(ApiErrorDetail.of("Idempotency-Key", "长度必须在 1..128 之间")));
        }
        return trimmed;
    }
}
