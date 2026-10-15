package com.shoppilot.common.web;

import com.shoppilot.common.error.ErrorCodes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一响应体契约测试（M2-Foundation / F-B1）。
 *
 * <p>断言对象是 {@code API_CONTRACT.md §2.1 / §2.3 / §3 / §1.3} 的<b>对外形态</b>，
 * 经真实 Spring MVC 管线（消息转换 + {@code @RestControllerAdvice}）产出，非手搓字符串：
 * <ul>
 *   <li>成功：{@code code="OK"}、{@code message="success"}、{@code timestamp} 以 {@code Z} 结尾、
 *       {@code requestId} 非空且<b>等于响应头 {@code X-Request-Id}</b>；</li>
 *   <li>字段校验失败：<b>400 + COM-0001 + {@code details[]}</b>（含 {@code field}/{@code message}，
 *       {@code field} 为 snake_case）；</li>
 *   <li>未捕获异常：<b>500 + COM-0002</b>（并显式断言<b>不是</b> {@code COM-0009}）；</li>
 *   <li>无匹配资源：<b>404 + COM-0003</b>；</li>
 *   <li>跨团队：<b>403 + TEAM-2001</b>（{@code API_CONTRACT §1.3} 状态语义化）。</li>
 * </ul>
 *
 * <p>包名沿用 {@code com.shoppilot.common.web}（与 {@code M1-T11} 先例一致：common 的包级测试
 * 放在 app 模块的 {@code src/test}，因 app 依赖 common）。
 */
class ApiResponseContractTest {

    private static final String ISO_UTC_TIMESTAMP = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    @DisplayName("成功体：code=OK / message=success / timestamp 以 Z 结尾 / requestId == X-Request-Id")
    void successEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/ok"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_TIMESTAMP)))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                // 契约 §7：响应体 requestId 必须等于响应头 X-Request-Id
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();

        String headerId = result.getResponse().getHeader("X-Request-Id");
        String body = result.getResponse().getContentAsString();
        assertNotNull(headerId, "响应头 X-Request-Id 必须存在");
        assertTrueContains(body, "\"requestId\":\"" + headerId + "\"");
    }

    @Test
    @DisplayName("透传：客户端传入的 X-Request-Id 被原样回显（响应头与响应体一致）")
    void clientRequestIdIsEchoed() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/ok").header("X-Request-Id", "req_test_0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req_test_0001"))
                .andExpect(jsonPath("$.requestId").value("req_test_0001"))
                .andReturn();
        assertNotNull(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("字段校验失败：400 + COM-0001 + details[] 非空（field 为 snake_case）")
    void validationFailureEnvelope() throws Exception {
        mockMvc.perform(post("/probe/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuCode\":\"\",\"qty\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.COM_0001))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details.length()").value(2))
                .andExpect(jsonPath("$.details[?(@.field=='sku_code')]").exists())
                .andExpect(jsonPath("$.details[?(@.field=='qty')]").exists())
                .andExpect(jsonPath("$.details[0].message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_TIMESTAMP)));
    }

    @Test
    @DisplayName("未捕获异常：500 + COM-0002（显式断言不是 COM-0009）")
    void unexpectedFailureIsCom0002() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCodes.COM_0002))
                .andExpect(jsonPath("$.code").value(not(ErrorCodes.COM_0009)))
                .andExpect(jsonPath("$.message").isNotEmpty())
                // 不泄露技术细节（ERROR_CODES §2 使用规范）
                .andExpect(jsonPath("$.message").value(not(org.hamcrest.Matchers.containsString("boom"))));
    }

    @Test
    @DisplayName("资源不存在：404 + COM-0003")
    void notFoundEnvelope() throws Exception {
        mockMvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.COM_0003));
    }

    @Test
    @DisplayName("跨团队越权：403 + TEAM-2001（HTTP 状态语义化，非 200）")
    void crossTeamIsForbidden() throws Exception {
        mockMvc.perform(get("/probe/cross-team"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TEAM_2001));
    }

    @Test
    @DisplayName("错误体同样满足包络：data=null、requestId 与响应头一致")
    void failureEnvelopeCarriesRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/missing").header("X-Request-Id", "req_err_0001"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "req_err_0001"))
                .andExpect(jsonPath("$.requestId").value("req_err_0001"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_UTC_TIMESTAMP)))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("\"details\""),
                "无字段级细节时不得出现 details 字段");
    }

    private static void assertTrueContains(String haystack, String needle) {
        assertEquals(true, haystack.contains(needle), "期望响应体包含：" + needle + "，实际：" + haystack);
    }

    /** 测试用探针控制器：仅用于触发各类出口，不构成业务端点。 */
    @RestController
    static class ProbeController {

        record ValidatedBody(@NotBlank(message = "不能为空") String skuCode,
                             @Min(value = 1, message = "必须大于 0") int qty) {
        }

        @GetMapping("/probe/ok")
        ApiResponse<Map<String, Object>> ok() {
            return ApiResponse.success(Map.of("id", 1001, "sku_code", "SKU-A-001"));
        }

        @PostMapping("/probe/validated")
        ApiResponse<Void> validated(@Valid @RequestBody ValidatedBody body) {
            return ApiResponse.success();
        }

        @GetMapping("/probe/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException("内部实现细节：不该出现在响应体里");
        }

        @GetMapping("/probe/missing")
        ApiResponse<Void> missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/probe/missing");
        }

        @GetMapping("/probe/cross-team")
        ApiResponse<Void> crossTeam() {
            throw new BizException(ErrorCodes.TEAM_2001, "您无权访问该数据");
        }
    }
}
