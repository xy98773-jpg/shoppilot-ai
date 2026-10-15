package com.shoppilot.common.error;

import java.util.HashMap;
import java.util.Map;

/**
 * 错误码 → HTTP 状态码映射（基础设施，只读查表）。
 *
 * <p>权威来源：{@code ERROR_CODES.md §2} 各条目「HTTP」列，语义分组见 {@code API_CONTRACT.md §1.3}
 * 「HTTP 状态码使用表」。本类只做<b>查表</b>，不重新定义任何错误码语义；
 * 若与 {@code ERROR_CODES.md §2} 不一致，以该表为准（改本表）。
 *
 * <p>查表未命中时返回 {@code 500}：未知码意味着「未归类缺陷」，按 {@code COM-0002} 的 HTTP 语义处理
 * （{@code ERROR_CODES.md §2 使用规范}：未分类异常统一映射 {@code COM-0002}）。
 */
public final class ErrorCodeHttpStatus {

    /** 查表未命中时的保守缺省：500（等同 COM-0002 的 HTTP 语义）。 */
    public static final int UNKNOWN = 500;

    private static final Map<String, Integer> STATUS = new HashMap<>();

    static {
        // 成功（非错误码，但统一出口可能传入）
        put("OK", 200);

        // COM —— 通用（ERROR_CODES §2.1）
        put("COM-0001", 400);
        put("COM-0002", 500);
        put("COM-0003", 404);
        put("COM-0004", 405);
        put("COM-0005", 400);
        put("COM-0006", 415);
        put("COM-0007", 400);
        put("COM-0008", 413);
        put("COM-0009", 409);
        put("COM-0010", 400);
        put("COM-0011", 429);
        put("COM-0012", 503);
        put("COM-0013", 500);
        put("COM-0014", 502);

        // AUTH —— 认证与登录
        put("AUTH-1001", 401);
        put("AUTH-1002", 401);
        put("AUTH-1003", 403);
        put("AUTH-1004", 404);
        put("AUTH-1005", 401);
        put("AUTH-1006", 401);
        put("AUTH-1007", 409);
        put("AUTH-1008", 409);
        put("AUTH-1009", 400);
        put("AUTH-1010", 403);
        put("AUTH-1011", 401);

        // TEAM —— 团队与权限隔离
        put("TEAM-2001", 403);
        put("TEAM-2002", 403);
        put("TEAM-2003", 403);
        put("TEAM-2004", 404);
        put("TEAM-2005", 409);
        put("TEAM-2006", 403);
        put("TEAM-2007", 409);
        put("TEAM-2008", 409);
        put("TEAM-2009", 403);

        // CAT —— 商品 / SKU / 供应商 / 仓库
        put("CAT-3001", 404);
        put("CAT-3002", 409);
        put("CAT-3003", 404);
        put("CAT-3004", 422);
        put("CAT-3005", 403);
        put("CAT-3006", 422);
        put("CAT-3007", 409);
        put("CAT-3008", 409);
        put("CAT-3009", 422);
        put("CAT-3010", 400);
        put("CAT-3011", 400);
        put("CAT-3012", 409);

        // IMP —— 数据导入
        put("IMP-4001", 400);
        put("IMP-4002", 400);
        put("IMP-4003", 400);
        put("IMP-4004", 409);
        put("IMP-4005", 422);
        put("IMP-4006", 200);
        put("IMP-4007", 404);
        put("IMP-4008", 400);
        put("IMP-4009", 422);
        put("IMP-4010", 422);
        put("IMP-4011", 410);
        put("IMP-4012", 400);
        put("IMP-4013", 400);
        put("IMP-4014", 200);

        // INV —— 库存
        put("INV-5001", 409);
        put("INV-5002", 409);
        put("INV-5003", 409);
        put("INV-5004", 404);
        put("INV-5005", 400);
        put("INV-5006", 409);
        put("INV-5007", 403);
        put("INV-5008", 404);
        put("INV-5009", 409);
        put("INV-5010", 422);
        put("INV-5011", 400);

        // SAL —— 销售
        put("SAL-6001", 404);
        put("SAL-6002", 400);
        put("SAL-6003", 400);
        put("SAL-6004", 409);
        put("SAL-6005", 422);
        put("SAL-6006", 400);

        // ANL —— 分析任务
        put("ANL-7001", 409);
        put("ANL-7002", 502);
        put("ANL-7003", 504);
        put("ANL-7004", 422);
        put("ANL-7005", 404);
        put("ANL-7006", 422);
        put("ANL-7007", 400);
        put("ANL-7008", 422);
        put("ANL-7009", 400);
        put("ANL-7010", 409);
        put("ANL-7011", 422);
        put("ANL-7012", 409);
        put("ANL-7013", 400);

        // RPL —— 补货建议与审核
        put("RPL-8001", 422);
        put("RPL-8002", 409);
        put("RPL-8003", 409);
        put("RPL-8004", 404);
        put("RPL-8005", 400);
        put("RPL-8006", 400);
        put("RPL-8007", 403);
        put("RPL-8008", 409);
        put("RPL-8009", 403);
        put("RPL-8010", 400);
        put("RPL-8011", 400);
        put("RPL-8012", 409);

        // PO —— 采购与到货
        put("PO-9001", 409);
        put("PO-9002", 409);
        put("PO-9003", 409);
        put("PO-9004", 404);
        put("PO-9005", 404);
        put("PO-9006", 400);
        put("PO-9007", 403);
        put("PO-9008", 409);
        put("PO-9009", 409);
        put("PO-9010", 403);
        put("PO-9011", 400);
        put("PO-9012", 400);

        // AI —— Java ↔ Python 内部通信（ERROR_CODES §2.11）
        put("AI-0001", 401);
        put("AI-1001", 400);
        put("AI-1002", 400);
        put("AI-2001", 422);
        put("AI-2002", 422);
        put("AI-3001", 500);
        put("AI-4001", 500);
        put("AI-5001", 502);
        put("AI-5002", 504);
        put("AI-5003", 422);
        put("AI-5004", 422);
        put("AI-6001", 404);
        put("AI-6002", 409);
        put("AI-6003", 409);
        put("AI-7001", 429);
        put("AI-7002", 400);
        put("AI-9001", 500);
    }

    private ErrorCodeHttpStatus() {
    }

    private static void put(String code, int status) {
        STATUS.put(code, status);
    }

    /**
     * 解析错误码对应的 HTTP 状态码。
     *
     * @param code 错误码常量（如 {@code "TEAM-2001"}）；{@code null} 或未命中返回 {@link #UNKNOWN}
     * @return HTTP 状态码
     */
    public static int resolve(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        Integer status = STATUS.get(code);
        return status == null ? UNKNOWN : status;
    }
}
