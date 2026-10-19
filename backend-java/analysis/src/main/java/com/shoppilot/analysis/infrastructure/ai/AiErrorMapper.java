package com.shoppilot.analysis.infrastructure.ai;

import com.shoppilot.common.error.ErrorCodes;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code AI-xxxx} → {@code ANL-xxxx} 错误码映射表（19 条全覆盖）。
 *
 * <p>权威来源：{@code ERROR_CODES.md §2.11}「Java 映射（对外/状态）」列；本类<b>只搬运</b>该列口径，
 * 不新造语义。两条不变量（{@code §12}）：
 * <ol>
 *   <li><b>{@code AI-xxxx} 一律不得透传前端</b>——必须转成 {@code ANL-xxxx} 或落 run 状态；</li>
 *   <li><b>未知码统一 {@code ANL-7002} 兜底并保留原码进日志</b>（任务书 §四.5）。</li>
 * </ol>
 *
 * <h2>★ 四条「§2.11 未给对外码」条目的归并口径（申报项）</h2>
 * <ul>
 *   <li>{@code AI-2001}（SKU 必需数据缺失）：§2.11 只标 {@code run FAILED}、未给对外码。
 *       与 {@code AI-1001}/{@code AI-1002} 同族（均为「Python 不接受 Java 发出的请求」），
 *       归并 {@code ANL-7004}（422）；</li>
 *   <li>{@code AI-5001/5002/5003/5004}（LLM 类）：§2.11 只规定 {@code llm_status} → {@code DEGRADED}
 *       的<b>状态</b>语义、不给对外码。本批 client 的「预检 / 发起」路径不会收到它们；
 *       万一声明为映射项并按兜底 {@code ANL-7002} 处理（LLM 降级的正确消费在 B16+ 的状态/结果端点，
 *       不经本 client）；</li>
 *   <li>{@code AI-6003}（任务已取消）：§2.11 只标「run 保持 CANCELLED，丢弃结果」状态语义，
 *       无对外码；同上按兜底 {@code ANL-7002} 声明；</li>
 *   <li>{@code AI-6002}（任务状态不允许）：§2.11 给「{@code ANL-7012}/{@code ANL-7010}」二选一
 *       （「结果未就绪」/「已终态」），取首者 {@code ANL-7012}。</li>
 * </ul>
 *
 * <p>线程安全：不可变静态表。
 */
public final class AiErrorMapper {

    private static final Map<String, String> AI_TO_ANL = buildMapping();

    private AiErrorMapper() {
    }

    /**
     * 内部错误码 → 对外错误码。
     *
     * @param aiCode Python 返回的 {@code AI-xxxx}（可为 {@code null}——非契约错误体）
     * @return 对外 {@code ANL-xxxx}；未命中（含 {@code null}）→ {@code ANL-7002}
     */
    public static String toExternalCode(String aiCode) {
        if (aiCode == null || aiCode.isBlank()) {
            return ErrorCodes.ANL_7002;
        }
        return AI_TO_ANL.getOrDefault(aiCode, ErrorCodes.ANL_7002);
    }

    /** 是否为 §2.11 已登记的 19 条内部码（未知码用于日志标注「兜底」而非「已知」）。 */
    public static boolean isKnown(String aiCode) {
        return aiCode != null && AI_TO_ANL.containsKey(aiCode);
    }

    /** 表大小（测试断言「19 条全覆盖」用）。 */
    public static int size() {
        return AI_TO_ANL.size();
    }

    private static Map<String, String> buildMapping() {
        Map<String, String> map = new LinkedHashMap<>(); // 保序 = ERROR_CODES §2.11 行序
        // §2.11 明列对外码的条目
        map.put("AI-0001", ErrorCodes.ANL_7002); // 401 内部令牌缺失/无效 → AI 服务不可用
        map.put("AI-1001", ErrorCodes.ANL_7004); // 400 请求 Schema 非法 → 422 校验未过
        map.put("AI-1002", ErrorCodes.ANL_7004); // 400 analysis_run_id 缺失/非法 → 同上同族
        map.put("AI-1003", ErrorCodes.ANL_7002); // 404 内部路径不存在（编程错误；不可重试）
        map.put("AI-1004", ErrorCodes.ANL_7002); // 405 内部方法不允许（编程错误）
        map.put("AI-2001", ErrorCodes.ANL_7004); // 422 SKU 必需数据缺失（§2.11 未给对外码，同族归并）
        map.put("AI-2002", ErrorCodes.ANL_7008); // 422 窗口内销售数据不足 → 数据质量预检未过
        map.put("AI-3001", ErrorCodes.ANL_7002); // 500 规则引擎执行失败 → 服务不可用
        map.put("AI-4001", ErrorCodes.ANL_7002); // 500 补货计算失败 → 服务不可用
        map.put("AI-5001", ErrorCodes.ANL_7002); // 502 LLM 不可用（状态语义 DEGRADED；本批路径不出现）
        map.put("AI-5002", ErrorCodes.ANL_7002); // 504 LLM 超时（同上）
        map.put("AI-5003", ErrorCodes.ANL_7002); // 422 LLM 输出非法 JSON（同上）
        map.put("AI-5004", ErrorCodes.ANL_7002); // 422 LLM 输出未过 Schema（同上）
        map.put("AI-6001", ErrorCodes.ANL_7005); // 404 任务不存在（注册延迟）→ 分析任务不存在
        map.put("AI-6002", ErrorCodes.ANL_7012); // 409 任务状态不允许（取「结果未就绪」义）
        map.put("AI-6003", ErrorCodes.ANL_7002); // 409 任务已取消（状态语义；本批路径不出现）
        map.put("AI-7001", ErrorCodes.ANL_7001); // 429 并发超限 → 已有运行中任务
        map.put("AI-7002", ErrorCodes.ANL_7007); // 400 SKU 数超上限 → SKU 数量过多
        map.put("AI-9001", ErrorCodes.ANL_7002); // 500 未预期内部错误 → 服务不可用
        return Map.copyOf(map);
    }
}
