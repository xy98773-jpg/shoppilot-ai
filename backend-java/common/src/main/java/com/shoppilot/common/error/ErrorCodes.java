package com.shoppilot.common.error;

/**
 * 错误码常量类（基础设施）。
 *
 * <p><b>只镜像 DESIGN_BASIS §12.3 的字符串常量，绝不在此重新定义数值/语义。</b>
 * 每个常量的值即为 §12.3 的权威错误码；逐码含义、HTTP 状态码、触发条件见 {@code ERROR_CODES.md §2}
 * （冲突时以该表为准）。本类不持有任何解释性文字。
 *
 * <p>AI 域错误码（AI-xxxx）属 Java↔Python 内部通信，定义见 ERROR_CODES.md §2.11，不在本类重复。
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    // COM —— 通用 / 参数校验 / 系统（§12.2: 0001–0999）
    public static final String COM_0001 = "COM-0001";
    public static final String COM_0002 = "COM-0002";
    public static final String COM_0009 = "COM-0009";

    // AUTH —— 认证与登录（1001–1099）
    public static final String AUTH_1001 = "AUTH-1001";
    public static final String AUTH_1002 = "AUTH-1002";
    public static final String AUTH_1003 = "AUTH-1003";
    public static final String AUTH_1004 = "AUTH-1004";

    // TEAM —— 团队与权限隔离（2001–2099）
    public static final String TEAM_2001 = "TEAM-2001";
    public static final String TEAM_2002 = "TEAM-2002";
    public static final String TEAM_2003 = "TEAM-2003";

    // CAT —— 商品 / SKU / 供应商 / 仓库（3001–3099）
    public static final String CAT_3001 = "CAT-3001";
    public static final String CAT_3002 = "CAT-3002";

    // IMP —— 数据导入（4001–4099）
    public static final String IMP_4001 = "IMP-4001";
    public static final String IMP_4002 = "IMP-4002";
    public static final String IMP_4003 = "IMP-4003";
    public static final String IMP_4004 = "IMP-4004";
    public static final String IMP_4005 = "IMP-4005";
    public static final String IMP_4006 = "IMP-4006";
    public static final String IMP_4007 = "IMP-4007";
    public static final String IMP_4013 = "IMP-4013";
    public static final String IMP_4014 = "IMP-4014";

    // INV —— 库存（5001–5099）
    public static final String INV_5001 = "INV-5001";
    public static final String INV_5002 = "INV-5002";
    public static final String INV_5003 = "INV-5003";
    public static final String INV_5006 = "INV-5006";
    public static final String INV_5009 = "INV-5009";

    // ANL —— 分析任务（7001–7099）
    public static final String ANL_7001 = "ANL-7001";
    public static final String ANL_7002 = "ANL-7002";
    public static final String ANL_7003 = "ANL-7003";
    public static final String ANL_7004 = "ANL-7004";
    public static final String ANL_7005 = "ANL-7005";

    // RPL —— 补货建议与审核（8001–8099）
    public static final String RPL_8001 = "RPL-8001";
    public static final String RPL_8002 = "RPL-8002";
    public static final String RPL_8003 = "RPL-8003";
    public static final String RPL_8004 = "RPL-8004";
    public static final String RPL_8007 = "RPL-8007";
    public static final String RPL_8012 = "RPL-8012";

    // PO —— 采购与到货（9001–9099）
    public static final String PO_9001 = "PO-9001";
    public static final String PO_9002 = "PO-9002";
    public static final String PO_9003 = "PO-9003";
    public static final String PO_9004 = "PO-9004";
}
