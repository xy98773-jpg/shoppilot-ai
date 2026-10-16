package com.shoppilot.identity.infrastructure.security;

import com.shoppilot.identity.domain.model.MemberStatus;
import com.shoppilot.identity.domain.model.TeamStatus;
import com.shoppilot.identity.domain.model.UserStatus;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamEntity;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamMemberEntity;
import com.shoppilot.identity.infrastructure.persistence.entity.UserEntity;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMemberMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.UserMapper;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器（M2-T02）——落地 {@code USER_ROLES.md §4.1} 授权判定顺序的第 1、2、3、6 步。
 *
 * <h2>每请求执行的实时校验（★ 本类最关键的职责）</h2>
 * <ol>
 *   <li><b>第 1 步 认证</b>：解析并校验 Access Token 签名/有效期；
 *       缺失 → 放行为匿名（后续由 {@code anyRequest().authenticated()} + 入口点给 401 {@code AUTH-1001}）；
 *       格式非 {@code Bearer} → {@code AUTH-1011}；已过期 → {@code AUTH-1005}；非法/签名不符 → {@code AUTH-1001}。</li>
 *   <li><b>第 2 步 用户状态</b>：<b>实时</b>查 {@code users.status}，非 {@code ACTIVE} → 403 {@code AUTH-1003}。
 *       这就是「禁用用户后，其旧 Access Token 下一个请求即被拒」的实现——
 *       不依赖吊销 Access Token，也无需 jti 黑名单（{@code API_CONTRACT §5.5} 第 3 条）。</li>
 *   <li><b>第 5 步 团队状态</b>（M2-T13.5 新增）：<b>实时</b>查 {@code teams.status}，非 {@code ACTIVE}
 *       → 403 {@code TEAM-2002}。这是<b>会话级前置</b>，执行于 {@code @RequireRole} 切面（角色判定，第 7 步）
 *       与资源级团队归属判定（第 4 步）之前——故「自身团队停用」对任何角色、任何业务端点
 *       （含 catalog 全部端点）一律 {@code TEAM-2002}（修复 R20 / R21，裁定依据见 {@code DESIGN_REVIEW.md} R22）。
 *       {@code teams} 属 {@code TenantTables.WITHOUT_TEAM_ID} 例外表，{@code selectById} 不受租户线影响。</li>
 *   <li><b>第 3 步 团队解析</b>：{@code team_id} <b>只</b>取自 JWT claim，绝不接受客户端传入。</li>
 *   <li><b>第 6 步 成员状态</b>：<b>实时</b>查 {@code team_members.status}，非 {@code ACTIVE} → 403 {@code TEAM-2003}。</li>
 * </ol>
 *
 * <h2>角色以数据库为准</h2>
 * 授权所需的角色取自 {@code team_members.role}（实时），而非 JWT 的 {@code role} claim——
 * 以满足 {@code USER_ROLES §6.3}「角色变更即时生效」。JWT 中仍保留 {@code role} claim
 * （{@code API_CONTRACT §5.2} 要求），作为签发时的快照供审计使用。
 *
 * <h2>上下文生命周期</h2>
 * 请求进入时装配 {@link TeamContext}，{@code finally} 中<b>必定清理</b>
 * （ThreadLocal + SecurityContext），防止线程池复用导致的跨请求串用。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /** 免认证路径：不尝试解析令牌（避免「持过期令牌访问登录接口被拒」的联调陷阱）。 */
    private static final Set<String> ANONYMOUS_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/health");

    private final JwtTokenProvider jwtTokenProvider;
    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final SecurityErrorWriter errorWriter;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   UserMapper userMapper,
                                   TeamMapper teamMapper,
                                   TeamMemberMapper teamMemberMapper,
                                   SecurityErrorWriter errorWriter) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && (ANONYMOUS_PATHS.contains(path)
                || path.startsWith("/actuator/")
                || path.startsWith("/internal/")
                || path.equals("/error"))) {
            return true;
        }
        String header = request.getHeader(AUTH_HEADER);
        return header == null || header.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // shouldNotFilter 已拦住「无头」；这里是「有头但非 Bearer」——按契约 AUTH-1011 拒绝
            errorWriter.write(response, HttpStatus.UNAUTHORIZED.value(),
                    ErrorCodes.AUTH_1011, "登录凭证格式不正确");
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();

        // ---- 第 1 步：认证（签名 / 有效期）----
        Claims claims;
        try {
            claims = jwtTokenProvider.parseClaims(token);
        } catch (ExpiredJwtException e) {
            errorWriter.write(response, HttpStatus.UNAUTHORIZED.value(),
                    ErrorCodes.AUTH_1005, "登录状态已过期，请重新登录或刷新令牌");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Access Token 非法：{}", e.getMessage());
            errorWriter.write(response, HttpStatus.UNAUTHORIZED.value(),
                    ErrorCodes.AUTH_1001, "登录状态已失效，请重新登录");
            return;
        }

        Long userId = parseUserId(claims);
        Long teamId = parseTeamId(claims);
        if (userId == null || teamId == null) {
            errorWriter.write(response, HttpStatus.UNAUTHORIZED.value(),
                    ErrorCodes.AUTH_1001, "登录状态已失效，请重新登录");
            return;
        }

        try {
            // ---- 第 2 步：用户状态（实时）----
            UserEntity user = userMapper.selectById(userId);
            if (user == null) {
                errorWriter.write(response, HttpStatus.UNAUTHORIZED.value(),
                        ErrorCodes.AUTH_1001, "登录状态已失效，请重新登录");
                return;
            }
            if (!UserStatus.ACTIVE.name().equals(user.getStatus())) {
                log.warn("用户已被禁用，拒绝请求 userId={}", userId);
                errorWriter.write(response, HttpStatus.FORBIDDEN.value(),
                        ErrorCodes.AUTH_1003, "账号已被禁用，请联系管理员");
                return;
            }

            // ---- 第 5 步：团队状态（实时，会话级前置）----
            // teams 属 TenantTables.WITHOUT_TEAM_ID 例外表，teamMapper.selectById 不受租户线影响。
            TeamEntity team = teamMapper.selectById(teamId);
            if (team == null || !TeamStatus.ACTIVE.name().equals(team.getStatus())) {
                log.warn("团队已停用，拒绝请求 userId={} teamId={}", userId, teamId);
                errorWriter.write(response, HttpStatus.FORBIDDEN.value(),
                        ErrorCodes.TEAM_2002, "您所属的团队已停用，请联系管理员");
                return;
            }

            // ---- 第 6 步：成员状态（实时）----
            TeamMemberEntity member = teamMemberMapper.selectByTeamAndUserNoTenant(teamId, userId);
            if (member == null || !MemberStatus.ACTIVE.name().equals(member.getStatus())) {
                log.warn("成员非 ACTIVE，拒绝请求 userId={} teamId={}", userId, teamId);
                errorWriter.write(response, HttpStatus.FORBIDDEN.value(),
                        ErrorCodes.TEAM_2003, "当前角色无权执行该操作");
                return;
            }

            // ---- 第 3 步：装配团队上下文（team_id 只来自 JWT claim）----
            String role = member.getRole();
            TeamContext.set(TeamContext.Snapshot.of(teamId, List.of(role), userId, user.getUsername()));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));

            filterChain.doFilter(request, response);
        } finally {
            TeamContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static Long parseUserId(Claims claims) {
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseTeamId(Claims claims) {
        Object raw = claims.get(JwtTokenProviderImpl.CLAIM_TEAM_ID);
        return raw instanceof Number number ? number.longValue() : null;
    }
}
