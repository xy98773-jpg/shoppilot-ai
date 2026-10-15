package com.shoppilot.identity.domain.model;

/**
 * 权限位（capability）——{@code USER_ROLES.md §2.2} 权限矩阵的 14 个功能点。
 *
 * <p>本枚举是矩阵「行」的稳定标识，用于 {@code GET /api/v1/auth/me} 返回的 {@code permissions} 对象：
 * 前端据布尔位做按钮显隐（<b>仅是体验优化</b>，安全边界仍在服务端——{@code USER_ROLES §4}）。
 * 枚举常量名即 JSON key（小写，见 {@link #key()}）。
 *
 * <p><b>与矩阵逐行对应</b>（行号取自 {@code USER_ROLES.md §2.2} 的 {@code #} 列）：
 * <pre>
 *  #1  数据导入（CSV/Excel）           → IMPORT_DATA
 *  #2  商品/SKU/供应商/仓库管理         → MASTER_DATA_MANAGE
 *  #3  库存调整（盘点/手工）            → INVENTORY_ADJUST
 *  #4  创建分析任务                    → CREATE_ANALYSIS
 *  #5  查看分析（结果与建议）           → VIEW_ANALYSIS
 *  #6  修改补货建议（数量）             → EDIT_REPLENISHMENT_QTY
 *  #7  提交审核                        → SUBMIT_REVIEW
 *  #8  审批（通过/驳回）               → APPROVE
 *  #9  创建采购单                      → CREATE_PURCHASE_ORDER
 *  #10 到货登记                        → REGISTER_RECEIPT
 *  #11 查看审计                        → VIEW_AUDIT
 *  #12 成员管理                        → MANAGE_MEMBERS
 *  #13 团队配置                        → MANAGE_TEAM_CONFIG
 *  #14 团队所有者转移                   → TRANSFER_OWNERSHIP
 * </pre>
 *
 * <p>注意 {@code ⚠️（需二次确认）} 在矩阵中表示「可执行 + 服务端额外前置校验」，
 * 属**能力存在**，故本枚举按 ✅ 处理；额外的前置校验由对应业务模块落地（{@code USER_ROLES §4.4}）。
 */
public enum Permission {

    IMPORT_DATA,
    MASTER_DATA_MANAGE,
    INVENTORY_ADJUST,
    CREATE_ANALYSIS,
    VIEW_ANALYSIS,
    EDIT_REPLENISHMENT_QTY,
    SUBMIT_REVIEW,
    APPROVE,
    CREATE_PURCHASE_ORDER,
    REGISTER_RECEIPT,
    VIEW_AUDIT,
    MANAGE_MEMBERS,
    MANAGE_TEAM_CONFIG,
    TRANSFER_OWNERSHIP;

    /** JSON key（小写下划线，与前端约定的能力位命名一致）。 */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
