package com.shoppilot.common.context;

import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import java.util.Collection;
import java.util.List;

/**
 * 团队 / 角色 / 用户上下文（基础设施）。
 *
 * <p>仅承载"当前团队 + 角色 + 当前用户"的存取；<b>不含任何 JWT 解析逻辑</b>
 * （解析在 M2-T02 由 identity 模块完成，解析结果通过 {@link #set} 注入）。
 *
 * <p><b>契约依据</b>：{@code API_CONTRACT §5.3 / DESIGN_BASIS §6 规则 3}——
 * {@code team_id} 绝不接受客户端传入，一律由服务端从 JWT 的 {@code team_id} claim 解析；
 * {@link Snapshot} 是服务端内部真源，业务代码只应通过本类读取当前团队。
 *
 * <p>本类为 {@code MODULE_BOUNDARIES §3.1} 白名单明确允许的"团队上下文"基础设施，属技术能力，
 * 非业务实体（§3.3 口诀的"业务名词"指各业务域的实体类，不含本容器）。
 */
public final class TeamContext {

    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

    private TeamContext() {
    }

    public static void set(Snapshot snapshot) {
        HOLDER.set(snapshot);
    }

    public static Snapshot get() {
        return HOLDER.get();
    }

    /**
     * 取当前上下文；缺失即视为"未认证"（{@code AUTH-1001}，{@code API_CONTRACT §5.4} 判定顺序第 1 步）。
     *
     * <p>用于"必须存在团队上下文"的路径（如租户线拦截器改写 SQL 前）。
     */
    public static Snapshot require() {
        Snapshot snapshot = HOLDER.get();
        if (snapshot == null || snapshot.teamId() == null) {
            throw new BizException(ErrorCodes.AUTH_1001, "登录状态已失效，请重新登录");
        }
        return snapshot;
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 当前请求上下文快照。
     *
     * @param teamId   当前团队 ID（绝对不可来自客户端，由服务端从 JWT 解析）
     * @param roles    当前用户在团队内的角色代码集合（见 {@code USER_ROLES.md §2.2}）
     * @param userId   当前用户 ID（{@code users.id}，JWT {@code sub}）
     * @param username 当前登录名（用于审计 {@code created_by}/{@code updated_by} 与日志）
     */
    public record Snapshot(Long teamId, List<String> roles, Long userId, String username) {

        public Snapshot {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        /** 兼容 M1 既有签名（仅团队 + 角色）。 */
        public static Snapshot of(Long teamId, List<String> roles) {
            return new Snapshot(teamId, roles, null, null);
        }

        public static Snapshot of(Long teamId, List<String> roles, Long userId, String username) {
            return new Snapshot(teamId, roles, userId, username);
        }

        /** 是否具备任一指定角色（角色按名称匹配，见 {@code USER_ROLES §2.2}）。 */
        public boolean hasAnyRole(Collection<String> candidates) {
            if (candidates == null || candidates.isEmpty()) {
                return false;
            }
            return roles.stream().anyMatch(candidates::contains);
        }
    }
}
