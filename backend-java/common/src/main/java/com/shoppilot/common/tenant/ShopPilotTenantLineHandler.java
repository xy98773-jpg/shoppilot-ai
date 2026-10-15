package com.shoppilot.common.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.shoppilot.common.context.TeamContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

/**
 * 租户列处理器：从 {@link TeamContext} 取 {@code team_id}，由
 * {@code TenantLineInnerInterceptor} 自动改写 SQL。
 *
 * <p><b>契约依据</b>：{@code DESIGN_BASIS §6} 规则 4/5——MyBatis-Plus 租户插件自动追加
 * {@code team_id} 条件作为<b>兜底</b>（业务层仍需显式校验，双保险）；
 * {@code API_CONTRACT §5.3}——租户插件自动追加条件作为兜底。
 *
 * <p><b>强校验（M2-T06）</b>：上下文缺失时<b>不再退化为占位 {@code 0}</b>，而是直接拒绝查询
 * （抛 {@code AUTH-1001}）。否则会静默把 {@code team_id = 0} 写进 SQL——
 * 而 {@code 0} 是「系统域哨兵」且**不是合法团队**（{@code DESIGN_BASIS §6} 规则 1），
 * 这会同时造成「查不到数据」与「越权写入」两类隐患。
 *
 * <p>改写行为（由 MyBatis-Plus 框架保证）：SELECT / UPDATE / DELETE 追加
 * {@code AND team_id = <当前团队>}；INSERT 自动补齐 {@code team_id} 列。
 */
public class ShopPilotTenantLineHandler implements TenantLineHandler {

    @Override
    public Expression getTenantId() {
        // require() 在上下文缺失时抛 AUTH-1001（见 TeamContext）
        Long teamId = TeamContext.require().teamId();
        return new LongValue(teamId);
    }

    @Override
    public String getTenantIdColumn() {
        return "team_id";
    }

    /**
     * 忽略不含 {@code team_id} 列的表（{@code DESIGN_BASIS §6} 规则 1 的显式例外：
     * {@code users}、{@code teams}，以及 Flyway 框架表），见 {@link TenantTables}。
     */
    @Override
    public boolean ignoreTable(String tableName) {
        return !TenantTables.hasTeamIdColumn(tableName);
    }
}
