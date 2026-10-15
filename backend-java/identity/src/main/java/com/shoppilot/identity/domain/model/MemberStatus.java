package com.shoppilot.identity.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 团队成员状态（{@code DESIGN_BASIS §9.3}，取值与 {@code team_members.status} 的 CHECK 约束一致）。
 *
 * <p>{@code REMOVED} 表示已移出团队（**保留历史行，禁止物理删除**；
 * 重新加入时复活同一行，见 {@code ER_DIAGRAM §4.1}）。
 * 非 ACTIVE 成员在授权判定第 6 步被拒 → {@code TEAM-2003}（403）。
 */
public enum MemberStatus {

    /** 在团队内。 */
    ACTIVE,
    /** 已移出（历史保留）。 */
    REMOVED;

    /** 按数据库字符串取值解析。 */
    public static Optional<MemberStatus> from(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(v -> v.name().equals(code)).findFirst();
    }
}
