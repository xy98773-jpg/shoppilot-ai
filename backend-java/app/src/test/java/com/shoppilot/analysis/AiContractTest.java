package com.shoppilot.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shoppilot.analysis.core.AiContract;
import com.shoppilot.analysis.core.AiOutboundHeaders;
import com.shoppilot.analysis.core.AiProperties;
import com.shoppilot.common.error.ErrorCodeHttpStatus;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.RequestIdContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M4-B13 §A/§C3：Java 侧 AI 契约常量与出站头的<b>纯单元</b>校验（不启动 Spring）。
 *
 * <p>本类只做「契约字符串逐字对齐」的机械核对 —— 一旦有人把路径写成子路径斜杠、
 * 或改了头名大小写、或挪了超时值，这里立刻红。不涉及任何 HTTP 调用。
 *
 * <p>权威来源：{@code AI_SERVICE_CONTRACT.md §2:74-75}（头）、{@code §3:100}（路径）、
 * {@code DESIGN_BASIS.md §13.4}（超时）、{@code ERROR_CODES.md:241-257}（错误码 HTTP 语义）。
 */
class AiContractTest {

    @Test
    @DisplayName("§C3-1 六个路径常量与 AI_SERVICE_CONTRACT §3 逐字一致（含冒号动作后缀）")
    void pathConstantsMatchContract() {
        assertEquals("/internal/v1", AiContract.PATH_PREFIX);
        assertEquals("/internal/v1/analysis:validate", AiContract.PATH_VALIDATE);
        assertEquals("/internal/v1/analysis/runs", AiContract.PATH_RUNS);
        assertEquals("/internal/v1/analysis/runs/{analysisRunId}", AiContract.PATH_RUN);
        assertEquals("/internal/v1/analysis/runs/{analysisRunId}/result", AiContract.PATH_RUN_RESULT);
        assertEquals("/internal/v1/analysis/runs/{analysisRunId}:cancel", AiContract.PATH_RUN_CANCEL);

        // R93：两个动作端点必须是【冒号】后缀，不是子路径斜杠
        assertTrue(AiContract.PATH_VALIDATE.contains("analysis:validate"),
                "validate 必须是冒号动作后缀（R93）");
        assertTrue(AiContract.PATH_RUN_CANCEL.contains("}:cancel"),
                "cancel 必须是冒号动作后缀（R93）");
        assertFalse(AiContract.PATH_VALIDATE.contains("/analysis/validate"),
                "不得写成子路径 /analysis/validate（R93）");
        assertFalse(AiContract.PATH_RUN_CANCEL.contains("/cancel"),
                "不得写成子路径 /cancel（R93）");
    }

    @Test
    @DisplayName("§C3-2 两个请求头名与契约逐字一致（大小写敏感）")
    void headerNamesMatchContract() {
        assertEquals("X-Internal-Token", AiContract.HEADER_INTERNAL_TOKEN);
        assertEquals("X-Request-Id", AiContract.HEADER_REQUEST_ID);
        // 大小写敏感：契约原文是连字符大驼峰，不能被改成小写形态
        assertNotEquals("x-internal-token", AiContract.HEADER_INTERNAL_TOKEN);
        assertNotEquals("x-request-id", AiContract.HEADER_REQUEST_ID);
        // 与 Java 既有链路追踪头必须是同一个字符串（否则同一次调用会出现两个头名）
        assertEquals(RequestIdContext.HEADER, AiContract.HEADER_REQUEST_ID);
    }

    @Test
    @DisplayName("§C3-3 三个超时值与 DESIGN_BASIS §13.4 一致：5000 / 10000 / 3000")
    void timeoutConstantsMatchBasis() {
        assertEquals(5000, AiContract.TIMEOUT_VALIDATE_MS);
        assertEquals(10000, AiContract.TIMEOUT_CREATE_MS);
        assertEquals(3000, AiContract.TIMEOUT_STATUS_MS);
    }

    @Test
    @DisplayName("§C3-4 三个 AI 错误码的 HTTP 语义：AI-0001=401 / AI-1001=400 / AI-9001=500")
    void errorCodeHttpStatusMapping() {
        assertEquals(401, ErrorCodeHttpStatus.resolve(ErrorCodes.AI_0001));
        assertEquals(400, ErrorCodeHttpStatus.resolve(ErrorCodes.AI_1001));
        assertEquals(500, ErrorCodeHttpStatus.resolve(ErrorCodes.AI_9001));

        // 常量值本身也要与契约表逐字一致（前缀 + 4 位）
        assertEquals("AI-0001", ErrorCodes.AI_0001);
        assertEquals("AI-1001", ErrorCodes.AI_1001);
        assertEquals("AI-9001", ErrorCodes.AI_9001);
    }

    @Test
    @DisplayName("§J3-1 AI-1003（内部路径不存在）映射 404")
    void errorCodeHttpStatusMapping1003() {
        assertEquals("AI-1003", ErrorCodes.AI_1003);
        assertEquals(404, ErrorCodeHttpStatus.resolve(ErrorCodes.AI_1003));
    }

    @Test
    @DisplayName("§J3-2 AI-1004（内部方法不允许）映射 405")
    void errorCodeHttpStatusMapping1004() {
        assertEquals("AI-1004", ErrorCodes.AI_1004);
        assertEquals(405, ErrorCodeHttpStatus.resolve(ErrorCodes.AI_1004));
    }

    @Test
    @DisplayName("§C3-5 forInternalCall：空令牌不放该键；非空令牌放入且值相等")
    void outboundHeadersOmitBlankToken() {
        Map<String, String> withoutToken = AiOutboundHeaders.forInternalCall("");
        assertFalse(withoutToken.containsKey(AiContract.HEADER_INTERNAL_TOKEN),
                "空令牌不得放入 X-Internal-Token（放空串会被当成「提供了错误令牌」）");

        Map<String, String> nullToken = AiOutboundHeaders.forInternalCall(null);
        assertFalse(nullToken.containsKey(AiContract.HEADER_INTERNAL_TOKEN));

        Map<String, String> blankToken = AiOutboundHeaders.forInternalCall("   ");
        assertFalse(blankToken.containsKey(AiContract.HEADER_INTERNAL_TOKEN),
                "空白令牌等同未配置");

        Map<String, String> withToken = AiOutboundHeaders.forInternalCall("tk");
        assertTrue(withToken.containsKey(AiContract.HEADER_INTERNAL_TOKEN));
        assertEquals("tk", withToken.get(AiContract.HEADER_INTERNAL_TOKEN));
    }

    @Test
    @DisplayName("§C3-6 AiProperties.isConfigured：空白 false、非空 true（且不做启动期校验）")
    void propertiesIsConfigured() {
        AiProperties props = new AiProperties();
        assertEquals("", props.getInternalToken(), "默认令牌为空串（未配置）");
        assertEquals("http://service-ai:8000", props.getBaseUrl(), "默认基址取 Compose 服务名");
        assertFalse(props.isConfigured());

        props.setInternalToken("   ");
        assertFalse(props.isConfigured(), "空白不算已配置");

        props.setInternalToken(null);
        assertFalse(props.isConfigured(), "null 不算已配置");

        props.setInternalToken("real-token");
        assertTrue(props.isConfigured());
    }
}
