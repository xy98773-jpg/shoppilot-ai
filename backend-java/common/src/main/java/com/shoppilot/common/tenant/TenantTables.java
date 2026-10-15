package com.shoppilot.common.tenant;

import java.util.Locale;
import java.util.Set;

/**
 * 租户表清单（基础设施，{@code DESIGN_BASIS §6 规则 1} 的可执行视图）。
 *
 * <p>{@code DESIGN_BASIS §6} 规则 1：所有团队归属的业务表必须含
 * {@code team_id BIGINT UNSIGNED NOT NULL}；<b>显式例外仅两表</b>：
 * <ul>
 *   <li>{@code users} —— 跨团队全局账号；</li>
 *   <li>{@code teams} —— 自身即团队。</li>
 * </ul>
 *
 * <p>故 {@link ShopPilotTenantLineHandler#ignoreTable(String)} 采用<b>例外黑名单</b>口径：
 * 仅上表被忽略，其余表一律视为"含 team_id"并追加租户条件——这与 §6 规则 1 的
 * 「例外只有这两张表」表述等价，可避免新增业务表时忘记登记。
 *
 * <p>同时排除框架表（{@code flyway_schema_history} 等）：它们不由业务 Mapper 访问，
 * 若被访问也不应追加租户条件。
 */
public final class TenantTables {

    /** 无 {@code team_id} 列的表（§6 规则 1 显式例外 + 框架表）。 */
    private static final Set<String> WITHOUT_TEAM_ID = Set.of(
            "users",
            "teams",
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
