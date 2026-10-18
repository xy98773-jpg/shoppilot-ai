package com.shoppilot.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.analysis.core.AiContract;
import com.shoppilot.app.ShopPilotApplication;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M4-B13 §C4：入站 {@code X-Request-Id} 到 {@code RequestIdContext} 的贯通验证。
 *
 * <h2>观测点为什么是响应体 {@code requestId}</h2>
 * {@code RequestIdContext} 是 {@code ThreadLocal}，且 {@code RequestIdFilter} 在 {@code finally}
 * 里 {@code clear()} —— 请求返回后读它必然是 {@code null}，断言不到。
 * 而 {@code ApiResponse#success(...)} 正是用 {@code RequestIdContext.getOrGenerate()} 填
 * {@code requestId}（{@code ApiResponse.java:114}），所以响应体的 {@code requestId}
 * <b>就是</b>请求线程内 {@code RequestIdContext.get()} 的值。本类用 {@code GET /api/v1/health}
 * （免认证、走统一响应体）作为观测端点，不额外造测试专用 Controller。
 *
 * <h2>为什么自带 MySQL</h2>
 * {@code @SpringBootTest} 会拉起完整上下文（Flyway + 数据源），故沿用本仓既有 34 个测试类的写法：
 * Testcontainers MySQL + {@code registry.add} 注入数据源与 {@code shoppilot.jwt.secret}。
 * ★ 本批<b>不新增任何启动期必填属性</b>（{@code F15}），故这里只需照抄既有两行属性。
 */
@SpringBootTest(classes = ShopPilotApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class AiRequestIdPropagationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 契约 §7 的服务端 ID 形态：{@code req_} + 32 位小写十六进制。 */
    private static final String REQ_ID_PATTERN = "^req_[0-9a-f]{32}$";

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("§C4-1 入站带 X-Request-Id → 响应头与响应体 requestId（= 上下文值）逐字相等")
    void inboundRequestIdReachesContext() throws Exception {
        String incoming = "req_0123456789abcdef0123456789abcdef";

        MvcResult result = mockMvc.perform(get("/api/v1/health")
                        .header(AiContract.HEADER_REQUEST_ID, incoming))
                .andExpect(status().isOk())
                .andReturn();

        String headerValue = result.getResponse().getHeader(AiContract.HEADER_REQUEST_ID);
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertEquals(incoming, headerValue, "响应头必须原样回显入站 ID");
        assertEquals(incoming, body.path("requestId").asText(),
                "响应体 requestId 取自 RequestIdContext，必须与入站 ID 相等");
    }

    @Test
    @DisplayName("§C4-2 入站不带 → 上下文值非空且匹配 ^req_[0-9a-f]{32}$，且与响应头一致")
    void missingInboundRequestIdIsGenerated() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andReturn();

        String headerValue = result.getResponse().getHeader(AiContract.HEADER_REQUEST_ID);
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String bodyRequestId = body.path("requestId").asText();

        assertTrue(headerValue != null && headerValue.matches(REQ_ID_PATTERN),
                "未传时服务端必须生成 req_<32hex>，实际=" + headerValue);
        assertEquals(headerValue, bodyRequestId,
                "响应体 requestId 与响应头必须同源（同一个 RequestIdContext 值）");
    }
}
