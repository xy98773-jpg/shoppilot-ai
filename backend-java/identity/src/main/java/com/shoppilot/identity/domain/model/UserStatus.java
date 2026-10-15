package com.shoppilot.identity.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 用户状态（{@code DESIGN_BASIS §9.1}，取值与 {@code users.status} 的 CHECK 约束一致）。
 *
 * <p>禁用后由认证过滤链在<b>每个请求</b>的授权判定第 2 步实时校验，立即返回
 * {@code AUTH-1003}（403），无需吊销 Access Token（{@code API_CONTRACT §5.5} 第 3 条）。
 */
public enum UserStatus {

    /** 正常可用。 */
    ACTIVE,
    /** 已禁用（软停用；禁止物理删除，见 {@code DESIGN_BASIS §6}）。 */
    DISABLED;

    /** 按数据库字符串取值解析（大小写敏感，与 CHECK 约束取值完全一致）。 */
    public static Optional<UserStatus> from(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(v -> v.name().equals(code)).findFirst();
    }
}
