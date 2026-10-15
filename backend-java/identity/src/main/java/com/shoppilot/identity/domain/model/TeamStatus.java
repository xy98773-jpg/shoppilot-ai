package com.shoppilot.identity.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 团队状态（{@code DESIGN_BASIS §9.2}，取值与 {@code teams.status} 的 CHECK 约束一致）。
 */
public enum TeamStatus {

    /** 正常可用。 */
    ACTIVE,
    /** 已停用（软停用）；非 ACTIVE → {@code TEAM-2002}（403）。 */
    DISABLED;

    /** 按数据库字符串取值解析。 */
    public static Optional<TeamStatus> from(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(v -> v.name().equals(code)).findFirst();
    }
}
