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
 * <p>AI 域错误码（AI-xxxx）属 Java↔Python 内部通信，完整清单见 {@code ERROR_CODES.md §2.11}
 * （19 条，HTTP 映射已在 {@link ErrorCodeHttpStatus} 固化）。本类<b>只固化 Java 侧会实际产出/断言的
 * 5 条</b>（M4-B13 起 3 条，B13.1 按 R95 增至 5 条）：{@code AI-0001} / {@code AI-1001} /
 * {@code AI-1003} / {@code AI-1004} / {@code AI-9001}。
 * 其余 14 条仍只经 {@link ErrorCodeHttpStatus#resolve} 查表，不在本类重复定义 ——
 * 避免「常量表」与「映射表」各存一份、后续改一处漏一处。
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
    /** 分页或排序参数不合法（400）—— page < 1、pageSize 不在 1..200、sort 字段不在白名单（API_CONTRACT §2.2）。 */
    public static final String COM_0007 = "COM-0007";
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
    /** 跨团队越权（403）—— 资源 team_id != JWT.team_id，或路径 {teamId} 与 JWT 不符；**先于角色判定**。 */
    public static final String TEAM_2001 = "TEAM-2001";
    /** 团队已停用（403）—— teams.status = DISABLED。 */
    public static final String TEAM_2002 = "TEAM-2002";
    /** 当前角色无权执行该操作（403）—— 角色不在允许集合（USER_ROLES §2.2）；或成员非 ACTIVE。 */
    public static final String TEAM_2003 = "TEAM-2003";
    /** 团队成员不存在（404）—— 目标成员记录不存在（防枚举：先按 team_id 过滤，查不到即 404）。 */
    public static final String TEAM_2004 = "TEAM-2004";
    /** 用户已是团队成员（409）—— uk_tm_team_user 冲突。 */
    public static final String TEAM_2005 = "TEAM-2005";
    /** 不能移除团队所有者（403）—— ADMIN 试图移除/降级 OWNER（USER_ROLES §2.2 #12）。 */
    public static final String TEAM_2006 = "TEAM-2006";
    /** 每团队仅允许一名所有者（409）—— 试图新增第二个 OWNER（DESIGN_BASIS §5）。 */
    public static final String TEAM_2007 = "TEAM-2007";
    /** 团队编码已存在（409）—— uk_teams_code 冲突。 */
    public static final String TEAM_2008 = "TEAM-2008";
    /** 不能移除自己（403）—— 操作者移出自己（含所有者本人）。 */
    public static final String TEAM_2009 = "TEAM-2009";

    // CAT —— 商品 / SKU / 供应商 / 仓库（3001–3099）
    /** 商品/SKU/供应商不存在（404）—— 主数据按 team_id 过滤后查不到。 */
    public static final String CAT_3001 = "CAT-3001";
    /** 编码在该团队内已存在（409）—— uk_*_team_code 冲突（同团队内唯一，跨团队可重复）。 */
    public static final String CAT_3002 = "CAT-3002";
    /** 仓库不存在（404）—— warehouse_id 无效。 */
    public static final String CAT_3003 = "CAT-3003";
    /** SKU 不属于该商品或该团队（422）—— sku.product_id != {id}，或商品/SKU 跨团队不匹配。 */
    public static final String CAT_3004 = "CAT-3004";
    /** 关联对象不属于同一团队（403）—— supplier/warehouse.team_id != 当前 team_id（DESIGN_BASIS §6 规则 4）。 */
    public static final String CAT_3005 = "CAT-3005";
    /** 主数据已停用（422）—— 对 INACTIVE 对象执行需启用的操作（ERROR_CODES §2.4）。 */
    public static final String CAT_3006 = "CAT-3006";
    /** 存在关联数据不可删除/停用（409）—— 停用被在途采购/启用 SKU 引用的对象（ERROR_CODES §2.4）。 */
    public static final String CAT_3007 = "CAT-3007";
    /** 商品下存在启用 SKU，不可停用（422）—— 停用商品但含 ACTIVE SKU（ERROR_CODES §2.4）。 */
    public static final String CAT_3009 = "CAT-3009";
    /** 供应商交期/MOQ 配置非法（400）—— lead_time_days 为负数、order_multiple <= 0（ERROR_CODES §2.4）。 */
    public static final String CAT_3011 = "CAT-3011";

    // IMP —— 数据导入（4001–4099）
    public static final String IMP_4001 = "IMP-4001";
    public static final String IMP_4002 = "IMP-4002";
    public static final String IMP_4003 = "IMP-4003";
    public static final String IMP_4004 = "IMP-4004";
    public static final String IMP_4005 = "IMP-4005";
    public static final String IMP_4006 = "IMP-4006";
    public static final String IMP_4007 = "IMP-4007";
    public static final String IMP_4008 = "IMP-4008";
    public static final String IMP_4009 = "IMP-4009";
    public static final String IMP_4010 = "IMP-4010";
    public static final String IMP_4011 = "IMP-4011";
    public static final String IMP_4012 = "IMP-4012";
    public static final String IMP_4013 = "IMP-4013";
    public static final String IMP_4014 = "IMP-4014";

    // INV —— 库存（5001–5099）
    public static final String INV_5001 = "INV-5001";
    public static final String INV_5002 = "INV-5002";
    public static final String INV_5003 = "INV-5003";
    public static final String INV_5004 = "INV-5004";
    public static final String INV_5005 = "INV-5005";
    public static final String INV_5006 = "INV-5006";
    public static final String INV_5007 = "INV-5007";
    public static final String INV_5008 = "INV-5008";
    public static final String INV_5009 = "INV-5009";
    public static final String INV_5010 = "INV-5010";
    public static final String INV_5011 = "INV-5011";

    // SAL —— 销售（6001–6099）
    public static final String SAL_6002 = "SAL-6002";
    public static final String SAL_6003 = "SAL-6003";
    public static final String SAL_6004 = "SAL-6004";
    public static final String SAL_6005 = "SAL-6005";

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

    // AI —— Java ↔ Python 内部通信（ERROR_CODES.md §2.11）
    // ★ 只固化 Java 侧会实际产出/断言的 5 条；其余 14 条只在 ErrorCodeHttpStatus 查表（见类 javadoc）。
    /** 内部令牌缺失或不匹配（401）—— Python 侧 {@code require_internal_token} 产出。 */
    public static final String AI_0001 = "AI-0001";
    /** 请求体 Schema 校验失败（400）—— Python 侧 Pydantic 校验失败（非 FastAPI 默认的 422）。 */
    public static final String AI_1001 = "AI-1001";
    /** 内部路径不存在（404）—— 请求了契约 §3 未定义的内部路径（Java 路径常量写错 / 两端版本不匹配）。 */
    public static final String AI_1003 = "AI-1003";
    /** 内部方法不允许（405）—— 路径存在但 HTTP 方法不匹配（如对 {@code analysis:validate} 发 GET）。 */
    public static final String AI_1004 = "AI-1004";
    /** AI 服务未预期错误（500）—— Python 侧未捕获异常。 */
    public static final String AI_9001 = "AI-9001";
}
