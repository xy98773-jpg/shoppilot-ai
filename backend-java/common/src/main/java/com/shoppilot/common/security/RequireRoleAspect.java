package com.shoppilot.common.security;

import com.shoppilot.common.annotation.RequireRole;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.tenant.TeamScopeValidator;
import com.shoppilot.common.web.BizException;
import java.util.Arrays;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.CodeSignature;
import org.springframework.stereotype.Component;

/**
 * 统一鉴权切面（M2-T07）：拦截带 {@link RequireRole} 的方法，执行
 * 「团队归属(TEAM-2001) → 角色授权(TEAM-2003)」两步前置校验。
 *
 * <h2>判定顺序（{@code USER_ROLES §4.1}，固定不得调换）</h2>
 * <pre>
 * 认证(AUTH-1001) → 用户状态(AUTH-1003) → 团队归属(TEAM-2001)
 * → 团队状态(TEAM-2002) → 角色授权(TEAM-2003) → 业务规则
 * </pre>
 * 切面负责其中<b>第 4 步（团队归属）</b>与<b>第 7 步（角色授权）</b>：
 * <ul>
 *   <li>第 4 步：从 {@link RequireRole#teamIdParam()} 指定的方法参数取 {@code {teamId}}，
 *       调 {@link TeamScopeValidator#assertPathTeam} → 不符 {@code TEAM-2001}；</li>
 *   <li>第 7 步：以注解 {@link RequireRole#value()} 与 {@link TeamContext.Snapshot#hasAnyRole}
 *       <b>按角色名</b>比对，无交集 → {@code TEAM-2003}（禁止 ordinal / 继承链）。</li>
 * </ul>
 *
 * <p><b>团队状态（TEAM-2002）</b>由业务服务层判定（切面不直接访问业务库，避免 common 依赖业务模块），
 * 但切面保证 <b>TEAM-2001 先于 TEAM-2003</b>，杜绝「跨团队请求被误判为无权限」的逆向顺序错误。
 *
 * <p><b>无 {@link RequireRole} 注解的方法</b>：切面零介入（硬门槛 §5-4）。
 *
 * <p><b>角色数据来源</b>：{@code TeamContext.require().roles()}，由身份模块在每次请求从 JWT 解析，
 * 绝不接受客户端传入（{@code API_CONTRACT §5.3}）。
 */
@Aspect
@Component
public class RequireRoleAspect {

    /** 默认路径团队参数名（{@code teamIdParam} 为空时回退）。 */
    private static final String DEFAULT_TEAM_ID_PARAM = "teamId";

    /**
     * 拦截带 {@link RequireRole} 的方法，先校验团队归属、再校验角色。
     *
     * @param pjp        连接点（被拦截的 Controller 方法）
     * @param requireRole 方法上的注解（由 {@code @annotation(requireRole)} 绑定）
     */
    @Around("@annotation(requireRole)")
    public Object around(ProceedingJoinPoint pjp, RequireRole requireRole) throws Throwable {
        // 第 4 步：团队归属（TEAM-2001）—— 必须先于角色判定
        Long pathTeamId = resolveTeamId(pjp, requireRole);
        if (pathTeamId != null) {
            TeamScopeValidator.assertPathTeam(pathTeamId);
        }

        // 第 7 步：角色授权（TEAM-2003）—— 按角色名直查，禁止 ordinal / 继承链
        if (!TeamContext.require().hasAnyRole(Arrays.asList(requireRole.value()))) {
            throw new BizException(ErrorCodes.TEAM_2003, "当前角色无权执行该操作");
        }

        return pjp.proceed();
    }

    /**
     * 从方法参数中解析路径 {@code {teamId}}（资源定位符）。
     *
     * <p>优先用 {@link RequireRole#teamIdParam()} 指定的参数名；为空则用 {@link #DEFAULT_TEAM_ID_PARAM}。
     * 找不到该参数时返回 {@code null}，调用方据此跳过归属步。
     */
    private Long resolveTeamId(ProceedingJoinPoint pjp, RequireRole requireRole) {
        String paramName = requireRole.teamIdParam().isEmpty() ? DEFAULT_TEAM_ID_PARAM : requireRole.teamIdParam();
        CodeSignature signature = (CodeSignature) pjp.getSignature();
        String[] names = signature.getParameterNames();
        Object[] args = pjp.getArgs();
        if (names == null || args == null) {
            return null;
        }
        for (int i = 0; i < names.length; i++) {
            if (paramName.equals(names[i]) && i < args.length && args[i] instanceof Number number) {
                return number.longValue();
            }
        }
        return null;
    }
}
