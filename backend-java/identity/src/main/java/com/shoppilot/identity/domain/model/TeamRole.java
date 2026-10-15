package com.shoppilot.identity.domain.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 团队内角色（{@code DESIGN_BASIS §5 / §9.3}，取值与 {@code team_members.role} 的 CHECK 约束一致）。
 *
 * <h2>授权判定口径（★ 关键约束，勿改）</h2>
 * {@code USER_ROLES.md §1.1} 明文要求：{@code OWNER ⊃ ADMIN ⊃ APPROVER ⊃ OPERATOR ⊃ VIEWER}
 * <b>只是能力包含关系</b>，<b>授权判定必须「权威矩阵直查」，不得用「继承链 + 覆盖」实现</b>。
 * 因此本枚举<b>显式写出</b>每个角色在 {@code USER_ROLES §2.2} 矩阵 14 行上的取值，
 * 并用 {@link #grants(Permission)} 做逐格查表——刻意<b>不</b>写
 * {@code role.ordinal() >= X} 之类的序数比较（那正是 F-40 教训里的「按列位置推断」变体）。
 *
 * <p>例外说明：矩阵 #3（库存调整）对 {@code OPERATOR}/{@code APPROVER} 标 ⚠️，
 * 但按 {@code USER_ROLES §2.3}「手工调整需 {@code ADMIN}+」属**流水类型级收窄**，
 * 不改变角色能力位（前置校验由库存模块落地）。
 */
public enum TeamRole {

    /** 团队所有者：全部权限，每团队恰好 1 名。 */
    OWNER,
    /** 管理员：除「所有者转移」外全部权限。 */
    ADMIN,
    /** 运营：日常执行，**不能审批**。 */
    OPERATOR,
    /** 审批人（老板）：运营权限 + 审批 + 创建采购单。 */
    APPROVER,
    /** 只读。 */
    VIEWER;

    /** 矩阵直查表（角色 → 允许的权限位集合）。静态不可变，构造时逐角色显式声明。 */
    private static final Map<TeamRole, Set<Permission>> MATRIX = Map.of(
            OWNER, EnumSet.allOf(Permission.class),
            ADMIN, EnumSet.of(
                    Permission.IMPORT_DATA,
                    Permission.MASTER_DATA_MANAGE,
                    Permission.INVENTORY_ADJUST,
                    Permission.CREATE_ANALYSIS,
                    Permission.VIEW_ANALYSIS,
                    Permission.EDIT_REPLENISHMENT_QTY,
                    Permission.SUBMIT_REVIEW,
                    Permission.APPROVE,
                    Permission.CREATE_PURCHASE_ORDER,
                    Permission.REGISTER_RECEIPT,
                    Permission.VIEW_AUDIT,
                    Permission.MANAGE_MEMBERS,
                    Permission.MANAGE_TEAM_CONFIG),
            // 矩阵 #8 审批 = ❌；#11 查看审计 = ❌；#12 成员管理 = ❌；#13 团队配置 = ❌；#14 转移 = ❌
            OPERATOR, EnumSet.of(
                    Permission.IMPORT_DATA,
                    Permission.MASTER_DATA_MANAGE,
                    Permission.INVENTORY_ADJUST,
                    Permission.CREATE_ANALYSIS,
                    Permission.VIEW_ANALYSIS,
                    Permission.EDIT_REPLENISHMENT_QTY,
                    Permission.SUBMIT_REVIEW,
                    Permission.CREATE_PURCHASE_ORDER,
                    Permission.REGISTER_RECEIPT),
            APPROVER, EnumSet.of(
                    Permission.IMPORT_DATA,
                    Permission.MASTER_DATA_MANAGE,
                    Permission.INVENTORY_ADJUST,
                    Permission.CREATE_ANALYSIS,
                    Permission.VIEW_ANALYSIS,
                    Permission.EDIT_REPLENISHMENT_QTY,
                    Permission.SUBMIT_REVIEW,
                    Permission.APPROVE,
                    Permission.CREATE_PURCHASE_ORDER,
                    Permission.REGISTER_RECEIPT),
            // 矩阵 #5 查看分析 = ✅（VIEWER 唯一的能力）；其余全部 ❌
            VIEWER, EnumSet.of(Permission.VIEW_ANALYSIS));

    /**
     * 该角色在 {@code USER_ROLES §2.2} 矩阵上是否具备某能力（**矩阵直查**，非继承链推导）。
     */
    public boolean grants(Permission permission) {
        return MATRIX.getOrDefault(this, Set.of()).contains(permission);
    }

    /** 该角色的全部权限位（按枚举声明顺序，保证响应稳定）。 */
    public Set<Permission> permissions() {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        for (Permission p : Permission.values()) {
            if (grants(p)) {
                set.add(p);
            }
        }
        return set;
    }

    /** 按数据库字符串取值解析（与 CHECK 约束取值完全一致）。 */
    public static Optional<TeamRole> from(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(v -> v.name().equals(code)).findFirst();
    }
}
