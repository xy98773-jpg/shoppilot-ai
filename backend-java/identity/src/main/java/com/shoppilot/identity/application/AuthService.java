package com.shoppilot.identity.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.tenant.TeamScopeValidator;
import com.shoppilot.common.web.BizException;
import com.shoppilot.identity.application.dto.AuthTokenResponse;
import com.shoppilot.identity.application.dto.MeResponse;
import com.shoppilot.identity.application.dto.TeamSummary;
import com.shoppilot.identity.application.dto.UserSummary;
import com.shoppilot.identity.domain.model.Permission;
import com.shoppilot.identity.domain.model.TeamRole;
import com.shoppilot.identity.domain.model.TeamStatus;
import com.shoppilot.identity.domain.model.UserStatus;
import com.shoppilot.identity.infrastructure.persistence.entity.RefreshTokenEntity;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamEntity;
import com.shoppilot.identity.infrastructure.persistence.entity.TeamMemberEntity;
import com.shoppilot.identity.infrastructure.persistence.entity.UserEntity;
import com.shoppilot.identity.infrastructure.persistence.mapper.RefreshTokenMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.TeamMemberMapper;
import com.shoppilot.identity.infrastructure.persistence.mapper.UserMapper;
import com.shoppilot.identity.infrastructure.security.JwtProperties;
import com.shoppilot.identity.infrastructure.security.JwtTokenProvider;
import com.shoppilot.identity.infrastructure.security.RefreshTokenCodec;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证应用服务（M2-T02）——登录 / 刷新（轮换）/ 登出 / 当前登录态。
 *
 * <h2>实现口径（{@code DESIGN_BASIS §15 D-01}，不得改为 Session）</h2>
 * 无状态 JWT；服务端不存 Session、<b>不引入 Redis</b>。
 * Access Token 2h 不落库；Refresh Token 7d 落 {@code refresh_tokens}（<b>只存 SHA-256 摘要</b>）。
 * 即时失效兜底由 {@code JwtAuthenticationFilter} 每请求实时校验
 * {@code users.status} / {@code team_members.status} 提供。
 *
 * <h2>审计埋点（M2-T11，{@code API_CONTRACT §11.1} + {@code DATABASE_DESIGN §6-J.1} #1/#2）</h2>
 * 登录成功 → {@code AUTH_LOGIN_SUCCESS}（{@code team_id} = 选中团队）；登录失败 →
 * {@code AUTH_LOGIN_FAILED}（{@code result=FAILURE}，{@code team_id = 0} 系统域——
 * 失败路径尚无团队上下文；{@code operator_id = NULL}，{@code operator_name} 记提交的登录名快照）。
 * 两条均为<b>显式</b> {@code record()}：失败路径无注解可用，且需要 {@code error_code}。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 系统域团队 ID（{@code DESIGN_BASIS §15 D-11}：无团队上下文的操作记 0）。 */
    private static final long SYSTEM_DOMAIN_TEAM_ID = 0L;

    /** 审计业务对象类型：用户（{@code DATABASE_DESIGN §6-J.1} #1/#2）。 */
    private static final String BIZ_TYPE_USER = "USER";

    /** 防枚举：用户不存在时用于「等量 BCrypt 比较」的哑哈希（启动时生成，不硬编码口令）。 */
    private final String dummyPasswordHash;

    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;
    private final AuditWriteService auditWriteService;

    public AuthService(UserMapper userMapper,
                       TeamMapper teamMapper,
                       TeamMemberMapper teamMemberMapper,
                       RefreshTokenMapper refreshTokenMapper,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties,
                       PasswordEncoder passwordEncoder,
                       AuditWriteService auditWriteService) {
        this.userMapper = userMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
        this.auditWriteService = auditWriteService;
        // 生成一次哑哈希（代价 = 一次 BCrypt），用于抹平「用户不存在」的时序侧信道
        this.dummyPasswordHash = passwordEncoder.encode("__shoppilot_anti_enumeration__");
    }

    // ---------------------------------------------------------------------
    // 登录
    // ---------------------------------------------------------------------

    /**
     * 登录（{@code POST /api/v1/auth/login}，匿名）。
     *
     * <p><b>防枚举（契约 §11.1 明文要求）</b>：「用户不存在」与「密码错误」
     * 返回<b>同一错误码 {@code AUTH-1002} 与同一提示文案</b>，且对前者执行一次
     * <b>等量 BCrypt 哑比较</b>，避免通过响应耗时判断账号是否存在。
     *
     * <p><b>审计（M2-T11）</b>：成功记 {@code AUTH_LOGIN_SUCCESS}（{@code team_id} = 选中团队）；
     * 任一 {@link BizException} 失败路径记 {@code AUTH_LOGIN_FAILED}
     * （{@code team_id = 0} 系统域、{@code operator_id = NULL}、{@code operator_name} = 登录名快照）。
     *
     * @param username 登录名或邮箱
     * @param password 明文口令
     * @param ipAddr   客户端 IP（写入撤销表，可空）
     * @param userAgent 客户端 UA（写入撤销表，可空）
     */
    public AuthTokenResponse login(String username, String password, String ipAddr, String userAgent) {
        try {
            return doLogin(username, password, ipAddr, userAgent);
        } catch (BizException ex) {
            // #2 登录失败：无团队上下文 → team_id = 0（系统域）；operator_id = NULL（登录名记入 operator_name 快照）
            auditWriteService.record(AuditEvent.builder()
                    .teamId(SYSTEM_DOMAIN_TEAM_ID)
                    .action(AuditAction.AUTH_LOGIN_FAILED)
                    .bizType(BIZ_TYPE_USER)
                    .operatorName(username)
                    .result(AuditResult.FAILURE)
                    .errorCode(ex.getErrorCode())
                    .build());
            throw ex;
        }
    }

    private AuthTokenResponse doLogin(String username, String password, String ipAddr, String userAgent) {
        UserEntity user = findByUsernameOrEmail(username);
        if (user == null) {
            // ★ 等量 BCrypt：耗时与真实比较同量级，但不泄露「用户是否存在」
            passwordEncoder.matches(password, dummyPasswordHash);
            log.warn("登录失败：凭证不匹配（用户不存在或口令错误），username={}", username);
            throw new BizException(ErrorCodes.AUTH_1002, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("登录失败：凭证不匹配，userId={}", user.getId());
            throw new BizException(ErrorCodes.AUTH_1002, "用户名或密码错误");
        }
        // 判定顺序第 2 步：用户状态
        if (!UserStatus.ACTIVE.name().equals(user.getStatus())) {
            throw new BizException(ErrorCodes.AUTH_1003, "账号已被禁用，请联系管理员");
        }

        SelectedTeam selected = selectDefaultActiveTeam(user.getId());
        touchLastLogin(user.getId());

        log.info("登录成功 userId={} teamId={} role={}", user.getId(), selected.team.getId(), selected.role);
        AuthTokenResponse response = issueTokens(user, selected.team, selected.role, ipAddr, userAgent);

        // #1 登录成功：team_id = 选中的会话团队（此时登录流程自行解析，非请求上下文）
        auditWriteService.record(AuditEvent.builder()
                .teamId(selected.team.getId())
                .action(AuditAction.AUTH_LOGIN_SUCCESS)
                .bizType(BIZ_TYPE_USER)
                .bizId(user.getId())
                .bizNo(user.getUsername())
                .operatorId(user.getId())
                .operatorName(user.getUsername())
                .operatorRole(selected.role)
                .result(AuditResult.SUCCESS)
                .build());
        return response;
    }

    // ---------------------------------------------------------------------
    // 刷新（轮换）
    // ---------------------------------------------------------------------

    /**
     * 用 Refresh Token 换新令牌（{@code POST /api/v1/auth/refresh}，匿名）。
     *
     * <p><b>轮换</b>：签发新 Refresh 行，并把旧行标记 {@code revoked_at} + {@code replaced_by}（指向新摘要）。
     * 旧令牌此后使用一律 {@code AUTH-1006}（401）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthTokenResponse refresh(String rawRefreshToken, String ipAddr, String userAgent) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw invalidRefresh();
        }
        RefreshTokenEntity row = findByHash(RefreshTokenCodec.sha256Hex(rawRefreshToken));
        if (row == null || row.getRevokedAt() != null || !isFuture(row.getExpiresAt())) {
            // 不存在 / 已撤销（含轮换后失效）/ 已过期 —— 统一 AUTH-1006，不区分（防信息泄露）
            log.warn("刷新失败：令牌无效、已撤销或已过期");
            throw invalidRefresh();
        }

        UserEntity user = userMapper.selectById(row.getUserId());
        if (user == null) {
            throw invalidRefresh();
        }
        if (!UserStatus.ACTIVE.name().equals(user.getStatus())) {
            throw new BizException(ErrorCodes.AUTH_1003, "账号已被禁用，请联系管理员");
        }

        // 团队绑定：refresh_tokens 表不含 team_id（R15 三条不可让步约束之②），
        // 故按与登录完全一致的确定性规则重新解析会话团队（最早加入的 ACTIVE 团队），
        // 保证同一会话的 team_id 稳定（D-13：单次会话绑定单个团队）。
        SelectedTeam selected = selectDefaultActiveTeam(user.getId());

        String newRaw = RefreshTokenCodec.generate();
        String newHash = RefreshTokenCodec.sha256Hex(newRaw);
        insertRefreshToken(user.getId(), newHash, ipAddr, userAgent);

        // 旧行：撤销 + 轮换链
        RefreshTokenEntity patch = new RefreshTokenEntity();
        patch.setId(row.getId());
        patch.setRevokedAt(nowUtc());
        patch.setReplacedBy(newHash);
        refreshTokenMapper.updateById(patch);

        log.info("刷新成功（轮换） userId={} teamId={} role={}", user.getId(),
                selected.team.getId(), selected.role);
        return buildResponse(user, selected.team, selected.role, newRaw);
    }

    // ---------------------------------------------------------------------
    // 登出
    // ---------------------------------------------------------------------

    /**
     * 登出（{@code POST /api/v1/auth/logout}，需认证）。
     *
     * <p><b>幂等</b>（契约 §10.1 幂等列 ✅）：重复登出不报错。
     * 提供 {@code refreshToken} 时只撤销该令牌（且必须属于当前用户，否则静默忽略——不泄露归属）；
     * 未提供时撤销当前用户的<b>全部</b>未撤销令牌（「登出全部会话」）。
     *
     * <p>Access Token 不在服务端维护黑名单（{@code API_CONTRACT §5.5}），由客户端清除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void logout(Long currentUserId, String rawRefreshToken) {
        LocalDateTime now = nowUtc();
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            RefreshTokenEntity row = findByHash(RefreshTokenCodec.sha256Hex(rawRefreshToken));
            if (row != null && currentUserId.equals(row.getUserId()) && row.getRevokedAt() == null) {
                RefreshTokenEntity patch = new RefreshTokenEntity();
                patch.setId(row.getId());
                patch.setRevokedAt(now);
                refreshTokenMapper.updateById(patch);
                log.info("登出：已撤销指定 Refresh Token userId={}", currentUserId);
            }
            return;
        }
        int revoked = refreshTokenMapper.update(null, Wrappers.<RefreshTokenEntity>lambdaUpdate()
                .eq(RefreshTokenEntity::getUserId, currentUserId)
                .isNull(RefreshTokenEntity::getRevokedAt)
                .set(RefreshTokenEntity::getRevokedAt, now));
        log.info("登出：已撤销该用户全部 Refresh Token userId={} count={}", currentUserId, revoked);
    }

    // ---------------------------------------------------------------------
    // 当前登录态
    // ---------------------------------------------------------------------

    /**
     * 当前登录态（{@code GET /api/v1/auth/me}，任何已登录成员）。
     *
     * <p>团队 / 角色 / 用户 <b>全部取自服务端上下文</b>（由认证过滤器实时查询后装配），
     * 不接受客户端传入（契约 §5.3）。
     */
    public MeResponse me() {
        TeamContext.Snapshot snapshot = TeamScopeValidator.requireContext();
        UserEntity user = userMapper.selectById(snapshot.userId());
        if (user == null) {
            throw new BizException(ErrorCodes.AUTH_1001, "登录状态已失效，请重新登录");
        }
        TeamEntity team = teamMapper.selectById(snapshot.teamId());
        if (team == null) {
            throw new BizException(ErrorCodes.AUTH_1001, "登录状态已失效，请重新登录");
        }
        String role = snapshot.roles().isEmpty() ? null : snapshot.roles().get(0);
        return new MeResponse(toUserSummary(user), toTeamSummary(team), role, permissionBits(role));
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /** 登录名或邮箱查询（{@code users} 为 §6 规则 1 例外表，不受租户线影响）。 */
    private UserEntity findByUsernameOrEmail(String usernameOrEmail) {
        UserEntity byUsername = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, usernameOrEmail));
        if (byUsername != null) {
            return byUsername;
        }
        return userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getEmail, usernameOrEmail));
    }

    /**
     * 选默认团队（契约 §11.1：所属多团队时「首期取最早加入的 ACTIVE 团队」）。
     *
     * <p>成员关系查询绕过租户线（登录时尚无团队上下文），但参数 {@code userId} 来自服务端。
     * 团队状态为 {@code DISABLED} 的候选被跳过；全部不可用 → {@code TEAM-2002}。
     */
    private SelectedTeam selectDefaultActiveTeam(Long userId) {
        List<TeamMemberEntity> memberships = teamMemberMapper.selectActiveMemberships(userId);
        if (memberships.isEmpty()) {
            // 契约未定义「用户无任何有效团队」的场景；取语义最接近的 TEAM-2003（成员非 ACTIVE 类）
            throw new BizException(ErrorCodes.TEAM_2003, "您尚未加入任何团队，请联系管理员");
        }
        for (TeamMemberEntity membership : memberships) {
            TeamEntity team = teamMapper.selectById(membership.getTeamId());
            if (team != null && TeamStatus.ACTIVE.name().equals(team.getStatus())) {
                return new SelectedTeam(team, membership.getRole());
            }
        }
        throw new BizException(ErrorCodes.TEAM_2002, "您所属的团队已停用，请联系管理员");
    }

    private AuthTokenResponse issueTokens(UserEntity user, TeamEntity team, String role,
                                          String ipAddr, String userAgent) {
        String rawRefresh = RefreshTokenCodec.generate();
        insertRefreshToken(user.getId(), RefreshTokenCodec.sha256Hex(rawRefresh), ipAddr, userAgent);
        return buildResponse(user, team, role, rawRefresh);
    }

    private AuthTokenResponse buildResponse(UserEntity user, TeamEntity team, String role, String rawRefreshToken) {
        String accessToken = jwtTokenProvider.issueAccessToken(
                user.getId(), user.getUsername(), team.getId(), role, UUID.randomUUID().toString());
        return new AuthTokenResponse(
                accessToken,
                rawRefreshToken,
                AuthTokenResponse.TOKEN_TYPE_BEARER,
                jwtTokenProvider.accessTtlSeconds(),
                toUserSummary(user),
                toTeamSummary(team),
                role);
    }

    private void insertRefreshToken(Long userId, String tokenHash, String ipAddr, String userAgent) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUserId(userId);
        entity.setTokenHash(tokenHash);
        entity.setExpiresAt(nowUtc().plusSeconds(jwtProperties.getRefreshTtlSeconds()));
        entity.setIpAddr(truncate(ipAddr, 45));
        entity.setUserAgent(truncate(userAgent, 255));
        refreshTokenMapper.insert(entity);
    }

    private RefreshTokenEntity findByHash(String tokenHash) {
        return refreshTokenMapper.selectOne(Wrappers.<RefreshTokenEntity>lambdaQuery()
                .eq(RefreshTokenEntity::getTokenHash, tokenHash));
    }

    private void touchLastLogin(Long userId) {
        UserEntity patch = new UserEntity();
        patch.setId(userId);
        patch.setLastLoginAt(nowUtc());
        userMapper.updateById(patch);
    }

    /** 权限位：{@code USER_ROLES §2.2} 矩阵直查（非继承链推导），key 为小写下划线能力名。 */
    static Map<String, Boolean> permissionBits(String roleName) {
        Optional<TeamRole> role = TeamRole.from(roleName);
        Map<String, Boolean> bits = new LinkedHashMap<>();
        for (Permission permission : Permission.values()) {
            bits.put(permission.key(), role.map(r -> r.grants(permission)).orElse(false));
        }
        return bits;
    }

    private static UserSummary toUserSummary(UserEntity user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getStatus());
    }

    private static TeamSummary toTeamSummary(TeamEntity team) {
        // 对外字段 team_name ← 实体列 teams.name
        return new TeamSummary(team.getId(), team.getName(), team.getDefaultTimezone());
    }

    private static BizException invalidRefresh() {
        return new BizException(ErrorCodes.AUTH_1006, "登录已失效，请重新登录");
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static boolean isFuture(LocalDateTime time) {
        return time != null && time.isAfter(nowUtc());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 选中的会话团队（团队 + 该团队内的角色）。 */
    private record SelectedTeam(TeamEntity team, String role) {
    }
}
