package com.shoppilot.common.context;

import java.util.List;

/**
 * 团队 / 角色上下文（基础设施）。
 *
 * <p>仅承载"当前团队 + 角色"的存取；<b>不含 JWT 解析逻辑</b>（解析在 M2-T06 由 identity 模块完成，
 * 解析结果通过 {@link #set} 注入）。
 *
 * <p>本类为 MODULE_BOUNDARIES §3.1 白名单明确允许的"团队上下文"基础设施，属技术能力，
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

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 当前请求上下文快照：团队标识 + 角色代码集合。
     *
     * @param teamId 当前团队 ID（绝对不可来自客户端，由服务端从 JWT 解析）
     * @param roles  当前用户在团队内的角色代码集合（见 USER_ROLES.md §2.2）
     */
    public record Snapshot(Long teamId, List<String> roles) {

        public static Snapshot of(Long teamId, List<String> roles) {
            return new Snapshot(teamId, roles);
        }
    }
}
