package com.shoppilot.common.tenant;

import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 团队隔离校验器（基础设施）——把 {@code DESIGN_BASIS §6} 的 6 条硬约束
 * 与 {@code USER_ROLES §4.1} 的判定顺序落成可调用的断言。
 *
 * <h2>与 §6 六条硬规则的对应</h2>
 * <ol>
 *   <li><b>规则 1</b>（团队业务表必须含 {@code team_id}）：DB 层由迁移保证，
 *       代码层见 {@link TenantTables}；</li>
 *   <li><b>规则 2</b>（唯一约束带 {@code team_id} 前缀）：DB 层由迁移保证；</li>
 *   <li><b>规则 3</b>（{@code team_id} 绝不接受客户端传入）：
 *       {@link #requireContext()} 只认服务端上下文，{@link #resolveTeamId(Long)} 显式忽略客户端值；</li>
 *   <li><b>规则 4</b>（跨表关联写入必须同团队校验）：{@link #assertSameTeam(Long, Long)}；</li>
 *   <li><b>规则 5</b>（关联查询必须携带 {@code team_id}）：租户插件兜底（{@link ShopPilotTenantLineHandler}）
 *       + {@link #assertResourceTeam(Long)} 显式校验；</li>
 *   <li><b>规则 6</b>（索引以 {@code team_id} 最左）：DB 层由迁移保证。</li>
 * </ol>
 *
 * <h2>判定顺序（{@code USER_ROLES §4.1}，固定不得调换）</h2>
 * <pre>
 * 认证(AUTH-1001) → 用户状态(AUTH-1003) → 团队解析 → 团队归属(TEAM-2001)
 * → 团队状态(TEAM-2002) → 成员状态/角色授权(TEAM-2003) → 高风险二次校验 → 业务规则
 * </pre>
 *
 * <p><b>防枚举</b>（{@code API_CONTRACT §5.6}）：资源不存在与资源属他人团队<b>不得泄露差异</b>；
 * 实现约定为「先按 {@code team_id} 过滤查询，查不到即 404」——只有<b>路径显式携带 {@code {teamId}}</b>
 * 且与 JWT 不符时才返回 {@code TEAM-2001}。故本类同时提供 {@link #orNotFound(Object)}。
 *
 * <p><b>无状态</b>：本类不注入任何依赖、不访问数据库；团队/成员状态的<b>取值</b>
 * 由 identity（M2-T02）查询后传入字符串，避免 common 依赖业务模块（{@code F-40}）。
 */
public final class TeamScopeValidator {

    private static final Logger log = LoggerFactory.getLogger(TeamScopeValidator.class);

    /** 团队状态取值（{@code DESIGN_BASIS §9.2}）。 */
    public static final String TEAM_ACTIVE = "ACTIVE";
    /** 成员状态取值（{@code DESIGN_BASIS §9.3}）。 */
    public static final String MEMBER_ACTIVE = "ACTIVE";
    /** 用户状态取值（{@code DESIGN_BASIS §9.1}）。 */
    public static final String USER_ACTIVE = "ACTIVE";

    private TeamScopeValidator() {
    }

    // ---------------------------------------------------------------------
    // 规则 3：team_id 只来自服务端上下文
    // ---------------------------------------------------------------------

    /** 断言当前请求具备团队上下文（缺失 → {@code AUTH-1001} / 401，判定顺序第 1 步）。 */
    public static TeamContext.Snapshot requireContext() {
        return TeamContext.require();
    }

    /**
     * 取当前团队 ID；<b>显式忽略</b>客户端传入的 {@code teamId}
     * （{@code API_CONTRACT §5.3}：请求体/查询参数中的 {@code team_id} 一律忽略，不报错）。
     *
     * @param clientSuppliedTeamId 客户端传入值（可为 {@code null}），仅用于记录告警
     * @return 服务端上下文中的 teamId
     */
    public static long resolveTeamId(Long clientSuppliedTeamId) {
        TeamContext.Snapshot snapshot = requireContext();
        long serverTeamId = snapshot.teamId();
        if (clientSuppliedTeamId != null && clientSuppliedTeamId != serverTeamId) {
            log.warn("检测到客户端传入 team_id={} 且与上下文 team_id={} 不一致；按契约 §5.3 忽略客户端值",
                    clientSuppliedTeamId, serverTeamId);
        }
        return serverTeamId;
    }

    // ---------------------------------------------------------------------
    // 判定顺序第 2 步：用户状态
    // ---------------------------------------------------------------------

    /** 用户状态断言（非 ACTIVE → {@code AUTH-1003} / 403，{@code DESIGN_BASIS §9.1}）。 */
    public static void assertUserActive(String userStatus) {
        if (!USER_ACTIVE.equals(userStatus)) {
            throw new BizException(ErrorCodes.AUTH_1003, "账号已被禁用，请联系管理员");
        }
    }

    // ---------------------------------------------------------------------
    // 判定顺序第 4 步 / 规则 4 / §5.3 / §5.6：团队归属
    // ---------------------------------------------------------------------

    /**
     * 路径 {@code {teamId}} 断言：与 JWT 解析出的团队不符 → {@code TEAM-2001}（403）。
     *
     * <p>依据 {@code API_CONTRACT §5.3}：路径中的 {@code {teamId}} 是<b>资源定位符</b>，
     * 服务端必须校验其等于 JWT 的 {@code team_id}（{@code USER_ROLES §6.4} 同旨）。
     */
    public static void assertPathTeam(Long pathTeamId) {
        if (pathTeamId == null) {
            return;
        }
        long current = requireContext().teamId();
        if (pathTeamId != current) {
            throw new BizException(ErrorCodes.TEAM_2001, "您无权访问该数据");
        }
    }

    /**
     * 资源归属断言：资源 {@code team_id} 与当前团队不符 → {@code TEAM-2001}（403）。
     *
     * <p>依据 {@code DESIGN_BASIS §5.1 第 1 条 / §6 规则 4} 与 {@code API_CONTRACT §5.6}：
     * 团队隔离<b>优先于角色</b>（含 OWNER），跨团队一律 {@code TEAM-2001}。
     */
    public static void assertResourceTeam(Long resourceTeamId) {
        if (resourceTeamId == null) {
            // 资源本身无团队维度（如 users）：无跨团队语义可判，交由调用方按业务处理
            return;
        }
        long current = requireContext().teamId();
        if (resourceTeamId != current) {
            throw new BizException(ErrorCodes.TEAM_2001, "您无权访问该数据");
        }
    }

    /**
     * 跨表关联写入的同团队校验（{@code DESIGN_BASIS §6} 规则 4）。
     *
     * <p>例：创建采购明细时必须校验 {@code sku.team_id == po.team_id}；
     * <b>不能只因为参数带了 {@code team_id} 就认为隔离已完成</b>。
     */
    public static void assertSameTeam(Long ownerTeamId, Long relatedTeamId) {
        if (ownerTeamId == null || relatedTeamId == null || !ownerTeamId.equals(relatedTeamId)) {
            throw new BizException(ErrorCodes.TEAM_2001, "您无权访问该数据");
        }
    }

    // ---------------------------------------------------------------------
    // 判定顺序第 5、6、7 步：团队状态 / 成员状态 / 角色
    // ---------------------------------------------------------------------

    /** 团队状态断言（非 ACTIVE → {@code TEAM-2002} / 403，{@code DESIGN_BASIS §9.2}）。 */
    public static void assertTeamActive(String teamStatus) {
        if (!TEAM_ACTIVE.equals(teamStatus)) {
            throw new BizException(ErrorCodes.TEAM_2002, "您所属的团队已停用，请联系管理员");
        }
    }

    /** 成员状态断言（非 ACTIVE → {@code TEAM-2003} / 403，{@code USER_ROLES §4.1} 第 6 步）。 */
    public static void assertMembershipActive(String memberStatus) {
        if (!MEMBER_ACTIVE.equals(memberStatus)) {
            throw new BizException(ErrorCodes.TEAM_2003, "当前角色无权执行该操作");
        }
    }

    /**
     * 角色授权断言（{@code USER_ROLES §4.1} 第 7 步）：当前角色不在允许集合 → {@code TEAM-2003}（403）。
     *
     * <p><b>角色按名称匹配</b>（{@code USER_ROLES §2.2} 的 {@code OWNER/ADMIN/OPERATOR/APPROVER/VIEWER}），
     * <b>禁止按权限表列位置推断</b>（{@code F-40} 教训）。
     */
    public static void assertAnyRole(Collection<String> allowedRoles) {
        TeamContext.Snapshot snapshot = requireContext();
        if (!snapshot.hasAnyRole(allowedRoles)) {
            throw new BizException(ErrorCodes.TEAM_2003, "当前角色无权执行该操作");
        }
    }

    // ---------------------------------------------------------------------
    // 防枚举：查不到即 404（§5.6）
    // ---------------------------------------------------------------------

    /**
     * 资源存在性断言：{@code null} → {@code COM-0003}（404）。
     *
     * <p>用法：<b>先按当前 {@code team_id} 过滤查询</b>，再交给本方法判定——
     * 这样"不存在"与"属于他人团队"对外表现完全一致，不泄露存在性。
     */
    public static <T> T orNotFound(T resource) {
        return orNotFound(resource, ErrorCodes.COM_0003);
    }

    /** 同上，可指定域错误码（如 {@code CAT-3001}、{@code ANL-7005}、{@code PO-9004}）。 */
    public static <T> T orNotFound(T resource, String notFoundCode) {
        if (resource == null) {
            throw new BizException(notFoundCode, "您访问的资源不存在或已被移除");
        }
        return resource;
    }
}
