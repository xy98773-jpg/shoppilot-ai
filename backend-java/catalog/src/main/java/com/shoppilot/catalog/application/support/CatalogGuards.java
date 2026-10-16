package com.shoppilot.catalog.application.support;

import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.tenant.TeamScopeValidator;
import com.shoppilot.common.web.BizException;
import java.util.function.Supplier;

/**
 * catalog 资源归属判定工具——落实「先归属（{@code 403 TEAM-2001}）、后不存在（{@code 404}）」的跨团队口径。
 *
 * <h2>口径来源</h2>
 * {@code 任务书 §3.2}（项目负责人裁定，见 {@code DESIGN_REVIEW.md} 冲突登记）：
 * <ul>
 *   <li>资源<b>真实存在但属他人团队</b> → {@code 403 TEAM-2001}；</li>
 *   <li>资源 <b>id 不存在</b> → {@code 404}（商品/SKU/供应商 {@code CAT-3001}，仓库 {@code CAT-3003}）。</li>
 * </ul>
 * 由于租户线会把「异团队资源」过滤成「查不到」，须借助一次<b>受控的归属探测</b>
 * （{@code @InterceptorIgnore(tenantLine="true")} 仅取 {@code team_id} 一列）来区分这两种情况。
 *
 * <p><b>为何不泄露存在性</b>：调用方仅在「异团队」时返回 403（同 {@code DESIGN_BASIS §5.1}：隔离优先于角色），
 * 探测结果不返回任何业务字段，不构成资源枚举面。
 */
public final class CatalogGuards {

    private CatalogGuards() {
    }

    /**
     * 断言资源在当前团队内可见；查不到时用受控探测区分 403 与 404。
     *
     * @param entity      常规（受租户线约束）查询结果；非 {@code null} 即命中并直接返回
     * @param teamIdProbe 受控归属探测：返回该行真实 {@code team_id}（异团队/存在），或 {@code null}（不存在）
     * @param notFoundCode 不存在时的域错误码（如 {@code CAT-3001}/{@code CAT-3003}）
     * @param notFoundMessage 不存在时的提示
     */
    public static <T> T requireInTeam(T entity, Supplier<Long> teamIdProbe,
                                      String notFoundCode, String notFoundMessage) {
        if (entity != null) {
            return entity;
        }
        Long ownerTeamId = teamIdProbe.get();
        if (ownerTeamId != null) {
            // 资源真实存在但属他人团队 → 隔离优先（TEAM-2001，403）；复用 TeamScopeValidator 保持口径唯一
            TeamScopeValidator.assertResourceTeam(ownerTeamId);
        }
        throw new BizException(notFoundCode, notFoundMessage);
    }
}
