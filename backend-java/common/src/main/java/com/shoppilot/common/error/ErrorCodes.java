package com.shoppilot.common.error;

/**
 * 错误码常量类（基础设施）。
 *
 * <p><b>只镜像权威错误码表，绝不在此重新定义数值/语义。</b>
 * 常量取值来源：{@code DESIGN_BASIS §12.3}（首批固化清单，仅定义成员资格）
 * 与 {@code ERROR_CODES.md §2}（逐码权威定义：HTTP 状态码、含义、触发条件）。
 * 二者冲突时以 {@code ERROR_CODES.md §2} 为准（§12.3 原文：「冲突时以该表为准」）。
 * 本类不持有任何解释性文字。
 *
 * <p>AI 域错误码（AI-xxxx）属 Java↔Python 内部通信，定义见 ERROR_CODES.md §2.11，不在本类重复。
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    // COM —— 通用 / 参数校验 / 系统（§12.2: 0001–0999）
    /** 参数校验失败（400）—— Bean Validation 失败；对应响应体 details[] 逐字段说明。 */
    public static final String COM_0001 = "COM-0001";
    /** 系统内部错误（500）—— 未捕获异常、代码缺陷（ERROR_CODES §2 明确禁止用其兜底「可归类」异常）。 */
    public static final String COM_0002 = "COM-0002";
    /** 资源不存在（404）—— 按 team_id 过滤后查不到，不区分「不存在」与「属他人团队」（防枚举）。 */
    public static final String COM_0003 = "COM-0003";
    /** 幂等键冲突（409）—— 同 Idempotency-Key 重复请求但请求体不一致；**不得用于兜底其他异常**。 */
    public static final String COM_0009 = "COM-0009";

    // AUTH —— 认证与登录（1001–1099）
    /** 未登录或 Token 失效（401）—— 缺失/格式错误的 Authorization。 */
    public static final String AUTH_1001 = "AUTH-1001";
    /** 用户名或密码错误（401）—— **不区分**用户是否存在（防枚举，见 API_CONTRACT §11.1）。 */
    public static final String AUTH_1002 = "AUTH-1002";
    /** 账号已禁用（403）—— users.status = DISABLED；授权链第 2 步实时校验。 */
    public static final String AUTH_1003 = "AUTH-1003";
    /** 用户不存在（404）—— 目标 userId 不存在。 */
    public static final String AUTH_1004 = "AUTH-1004";
    /** Access Token 已过期（401）—— exp 已过，需用 Refresh Token 换取。 */
    public static final String AUTH_1005 = "AUTH-1005";
    /** Refresh Token 无效或已过期（401）—— 校验失败、已轮换失效、已撤销或不存在。 */
    public static final String AUTH_1006 = "AUTH-1006";
    /** 用户名已存在（409）—— 创建用户时 username 冲突。 */
    public static final String AUTH_1007 = "AUTH-1007";
    /** 邮箱已被占用（409）—— email 冲突（uk_users_email）。 */
    public static final String AUTH_1008 = "AUTH-1008";
    /** 原密码不正确（400）—— 修改密码时旧密码校验失败。 */
    public static final String AUTH_1009 = "AUTH-1009";
    /** 无权修改该用户状态（403）—— 试图禁用自己或超出角色的用户。 */
    public static final String AUTH_1010 = "AUTH-1010";
    /** 凭证格式非法（401）—— Authorization 非 {@code Bearer <token>}。 */
    public static final String AUTH_1011 = "AUTH-1011";

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
