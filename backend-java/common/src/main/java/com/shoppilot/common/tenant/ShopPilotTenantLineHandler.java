package com.shoppilot.common.tenant;

import com.shoppilot.common.context.TeamContext;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

/**
 * 租户列处理器骨架：从 {@code TeamContext} 取 team_id。
 *
 * <p>TODO M2-T06：上下文未建立时不应执行带租户表的查询；当前返回占位 {@code 0L} 仅为编译期骨架，
 * 届时须改为"上下文缺失即拒绝查询"的强校验。{@link #ignoreTable} 当前对全部表返回 false（即全部生效）
 * （所有业务表均带 team_id），M2-T06 可补充忽略表白名单（如不使用租户的表）。
 */
public class ShopPilotTenantLineHandler implements TenantLineHandler {

    @Override
    public Expression getTenantId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        Long teamId = snapshot == null ? null : snapshot.teamId();
        if (teamId == null) {
            return new LongValue(0L);
        }
        return new LongValue(teamId);
    }

    @Override
    public String getTenantIdColumn() {
        return "team_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return false;
    }
}
