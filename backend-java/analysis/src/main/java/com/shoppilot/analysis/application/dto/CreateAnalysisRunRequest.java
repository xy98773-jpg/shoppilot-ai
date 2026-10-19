package com.shoppilot.analysis.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /api/v1/analysis/runs} 请求体（{@code API_CONTRACT.md §11.5}）。
 *
 * <p><b>★ 不含 {@code team_id}</b>：团队一律由服务端从 JWT 解析（{@code API_CONTRACT §5.3} /
 * {@code DESIGN_BASIS §6} 规则 3）。即便客户端在 JSON 里塞 {@code team_id}，也会因本 record
 * 无该组件而被 Jackson 忽略（{@code FAIL_ON_UNKNOWN_PROPERTIES=false}），不可能进入落库路径。
 *
 * <p>字段级校验分两层（{@code ERROR_CODES §2}）：
 * <ul>
 *   <li><b>结构/必填</b>（本类的 Bean Validation）→ {@code COM-0001}（400）；
 *       控制器入口由 {@code GlobalExceptionHandler#handleValidation} 统一转 {@code details[]}；</li>
 *   <li><b>业务语义</b>（服务层）：范围明细缺失 → {@code COM-0001}；SKU 超 2000 → {@code ANL-7007}；
 *       参数越界 → {@code ANL-7009}；{@code date_from > date_to} → {@code ANL-7013}。</li>
 * </ul>
 *
 * @param scopeType        范围类型（{@code ALL_ACTIVE} / {@code SELECTED_SKUS} / {@code CATEGORY}）
 * @param scopeJson        范围明细（{@code {"sku_ids":[...]}} 或 {@code {"category":"..."}}；可空）
 * @param warehouseId      限定仓库（可空 = 默认仓）
 * @param dateFrom         数据窗口起
 * @param dateTo           数据窗口止
 * @param params           运行参数（可空 = 全取默认值）
 * @param algorithmVersion 算法版本（如 {@code avg-ma-3sigma-v1}）
 * @param promptVersion    Prompt 版本（{@code enable_llm=true} 时必填，服务层校验）
 * @param modelName        LLM 模型名（可空）
 */
public record CreateAnalysisRunRequest(
        @JsonProperty("scope_type")
        @NotBlank(message = "scope_type 不能为空")
        String scopeType,

        @JsonProperty("scope_json")
        JsonNode scopeJson,

        @JsonProperty("warehouse_id")
        Long warehouseId,

        @JsonProperty("date_from")
        @NotNull(message = "date_from 不能为空")
        LocalDate dateFrom,

        @JsonProperty("date_to")
        @NotNull(message = "date_to 不能为空")
        LocalDate dateTo,

        @JsonProperty("params")
        JsonNode params,

        @JsonProperty("algorithm_version")
        @NotBlank(message = "algorithm_version 不能为空")
        @Size(max = 64, message = "algorithm_version 长度不能超过 64")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$",
                message = "algorithm_version 只能包含字母、数字、点、下划线、连字符，且不得以符号开头")
        String algorithmVersion,

        @JsonProperty("prompt_version")
        @Size(max = 64, message = "prompt_version 长度不能超过 64")
        String promptVersion,

        @JsonProperty("model_name")
        @Size(max = 128, message = "model_name 长度不能超过 128")
        String modelName) {
}
