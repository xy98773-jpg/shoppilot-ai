package com.shoppilot.common.tenant;

import java.util.Locale;
import java.util.Set;

/**
 * 租户表清单（基础设施，{@code DESIGN_BASIS §6 规则 1} 的可执行视图）。
 *
 * <p>{@code DESIGN_BASIS §6} 规则 1：所有团队归属的业务表必须含
 * {@code team_id BIGINT UNSIGNED NOT NULL}；<b>显式例外共三表</b>：
 * <ul>
 *   <li>{@code users} —— 跨团队全局账号；</li>
 *   <li>{@code teams} —— 自身即团队；</li>
 *   <li>{@code refresh_tokens} —— 跨团队全局账号体系（按 {@code user_id} 组织，R15 闭环）。</li>
 * </ul>
 *
 * <p>故 {@link ShopPilotTenantLineHandler#ignoreTable(String)} 采用<b>例外黑名单</b>口径：
 * 仅上表被忽略，其余表一律视为"含 team_id"并追加租户条件——这与 §6 规则 1 的
 * 「例外只有这三张表」表述等价，可避免新增业务表时忘记登记。
 *
 * <p><b>⚠️ 新增任何无 {@code team_id} 的表都必须登记进 {@link #WITHOUT_TEAM_ID}</b>，
 * 否则租户线会对其追加 {@code team_id = ?} 而<b>永远查不到行</b>——典型如
 * {@code refresh_tokens} 漏登记会导致<b>登出静默失效</b>（不报错、只是不生效，最坏的一类缺陷）。
 *
 * <p>同时排除框架表（{@code flyway_schema_history} 等）：它们不由业务 Mapper 访问，
 * 若被访问也不应追加租户条件。
 */
public final class TenantTables {

    /** 无 {@code team_id} 列的表（§6 规则 1 显式例外 + 框架表）。 */
    private static final Set<String> WITHOUT_TEAM_ID = Set.of(
            "users",
            "teams",
            "refresh_tokens",   // R15：按 user_id 组织，跨团队全局；漏登记会导致登出静默失效
            "flyway_schema_history"
    );

    private TenantTables() {
    }

    /**
     * 该表是否含 {@code team_id} 列（即是否需要追加租户条件）。
     *
     * <p>表名做归一化：去反引号、去 schema 前缀（{@code db.table} → {@code table}）、转小写。
     */
    public static boolean hasTeamIdColumn(String tableName) {
        return !WITHOUT_TEAM_ID.contains(normalize(tableName));
    }

    static String normalize(String tableName) {
        if (tableName == null) {
            return "";
        }
        String name = tableName.replace("`", "").replace("\"", "").trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
