package com.shoppilot.analysis.core;

/**
 * Java ↔ Python 内部契约的<b>常量清单</b>（纯常量类，无任何逻辑）。
 *
 * <p><b>权威来源</b>：
 * <ul>
 *   <li>认证头与链路追踪头 —— {@code AI_SERVICE_CONTRACT.md §2:74-75}（{@code X-Internal-Token} 必填、
 *       {@code X-Request-Id} 必填且必须回显）；</li>
 *   <li>端点路径 —— {@code AI_SERVICE_CONTRACT.md §3:100}、{@code DESIGN_BASIS.md §13.1}；</li>
 *   <li>超时 —— {@code DESIGN_BASIS.md §13.4}（validate 5s / 创建 10s / 状态查询 3s）。</li>
 * </ul>
 *
 * <p><b>★ 冒号动作后缀</b>：{@code analysis:validate} 与 {@code runs/{id}:cancel} 是<b>冒号</b>动作后缀，
 * 不是子路径斜杠（{@code R93} 裁定）。写成 {@code /analysis/validate} 会与 Java 侧调用方 404 对不上。
 *
 * <p>本类不持有任何运行期状态，也不做任何校验 —— 只把契约里的字符串固化成一个可被测试逐字核对的真源。
 */
public final class AiContract {

    /** 内部令牌请求头（{@code AI_SERVICE_CONTRACT.md §2:74}）：必填，缺失或不匹配 → {@code 401 AI-0001}。 */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** 链路追踪请求头（{@code AI_SERVICE_CONTRACT.md §2:75}）：必填，Python 必须回显并记入日志。 */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 内部 API 前缀（{@code DESIGN_BASIS.md §13.1}）：Java 调 Python 的全部端点都在此前缀下。 */
    public static final String PATH_PREFIX = "/internal/v1";

    /** 参数校验端点（{@code AI_SERVICE_CONTRACT.md §3:100}）：<b>冒号</b>动作后缀，不是子路径。 */
    public static final String PATH_VALIDATE = "/internal/v1/analysis:validate";

    /** 创建分析任务（{@code AI_SERVICE_CONTRACT.md §3:100}）。 */
    public static final String PATH_RUNS = "/internal/v1/analysis/runs";

    /** 查询分析任务状态 / 步骤结果（{@code AI_SERVICE_CONTRACT.md §3:100}）。 */
    public static final String PATH_RUN = "/internal/v1/analysis/runs/{analysisRunId}";

    /** 查询分析结果（{@code AI_SERVICE_CONTRACT.md §3:100}）。 */
    public static final String PATH_RUN_RESULT = "/internal/v1/analysis/runs/{analysisRunId}/result";

    /** 取消分析任务（{@code AI_SERVICE_CONTRACT.md §3:100}）：<b>冒号</b>动作后缀，不是子路径。 */
    public static final String PATH_RUN_CANCEL = "/internal/v1/analysis/runs/{analysisRunId}:cancel";

    /** 校验端点读超时（{@code DESIGN_BASIS.md §13.4}：validate 5s）。 */
    public static final int TIMEOUT_VALIDATE_MS = 5000;

    /** 创建端点读超时（{@code DESIGN_BASIS.md §13.4}：创建 10s）。 */
    public static final int TIMEOUT_CREATE_MS = 10000;

    /** 状态查询读超时（{@code DESIGN_BASIS.md §13.4}：状态查询 3s）。 */
    public static final int TIMEOUT_STATUS_MS = 3000;

    /**
     * 结果查询读超时（{@code AI_SERVICE_CONTRACT.md §3.5}：结果可能较大，10s；仅网络错误重试 1 次）。
     *
     * <p>M4-B20.2 新增：{@code B15} 只需预检（5s）与发起（10s）两个超时，
     * 状态/结果两个读端点由本批补齐，故在此固化第三个（第四个）超时常量。
     */
    public static final int TIMEOUT_RESULT_MS = 10000;

    /** 内部调用统一的内容类型（{@code AI_SERVICE_CONTRACT.md §2}：请求与响应均为 JSON）。 */
    public static final String CONTENT_TYPE_JSON = "application/json";

    private AiContract() {
        // 纯常量类，禁止实例化
    }
}
