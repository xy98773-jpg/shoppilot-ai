package com.shoppilot.analysis.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 内部契约「数据质量预检」响应信封（{@code AI_SERVICE_CONTRACT.md §3.1 响应体 200}）。
 *
 * @param data      预检结果（{@code code=OK} 时）
 * @param requestId 回显的 {@code X-Request-Id}
 */
public record AiValidateEnvelope(String code, AiValidateResult data,
                                 @JsonProperty("request_id") String requestId) {

    /**
     * 预检结果（{@code §3.1} 响应字段子集——Java 侧落库/提示需要的部分）。
     *
     * <p>{@code analyzable=false} ⇒ 全部 SKU 不可分析 ⇒ 客户端按 {@code ANL-7008} 处置
     * （任务书 §四.5 口径）。{@code per_sku} 明细不进入本 DTO：按「只传必需数据」的逆向原则，
     * Java 当前只消费汇总字段。
     *
     * @param analyzable       是否至少有一个 SKU 可分析
     * @param windowDays       预检所用窗口
     * @param totalSkus        SKU 总数
     * @param analyzableSkus   可分析 SKU 数
     * @param insufficientSkus 数据不足 SKU 数
     * @param warnings         中文提示（{@code ANL-7008} 时取首条作对外文案）
     */
    public record AiValidateResult(
            boolean analyzable,
            @JsonProperty("window_days") int windowDays,
            @JsonProperty("total_skus") int totalSkus,
            @JsonProperty("analyzable_skus") int analyzableSkus,
            @JsonProperty("insufficient_skus") int insufficientSkus,
            List<String> warnings) {
    }
}
