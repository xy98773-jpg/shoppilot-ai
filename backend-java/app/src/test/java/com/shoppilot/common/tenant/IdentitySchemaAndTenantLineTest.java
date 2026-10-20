package com.shoppilot.common.tenant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
// MyBatis-Plus 3.5.17：MybatisSqlSessionFactoryBean 由 extension.spring 迁至 com.baomidou.mybatisplus.spring
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.shoppilot.common.context.TeamContext;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * identity 建表迁移 + 租户线隔离 —— 端到端集成测试（M2-Foundation / F-B3 + F-B2）。
 *
 * <p>用真实 MySQL 8.0.36（Testcontainers）执行真实迁移与<b>真实 SQL 改写</b>，
 * 断言 {@code DESIGN_BASIS §6} 的数据库级与插件级保障：
 * <ul>
 *   <li>Flyway 应用 {@code V1.0.1__identity.sql} + {@code V1.0.2__refresh_tokens.sql}
 *       + {@code V1.0.3__catalog.sql}（M2-T08）等共十二条迁移（{@code V1.0.0} → {@code V1.0.11}），
 *       表与唯一约束/CHECK 确实生效（含负向用例）；</li>
 *   <li>对含 {@code team_id} 的表发起查询，SQL <b>被自动追加 {@code team_id = <当前团队>}</b>；</li>
 *   <li>INSERT 自动补齐 {@code team_id}；</li>
 *   <li>不含 {@code team_id} 的表（{@code users} / {@code teams} / {@code refresh_tokens}，
 *       §6 规则 1 例外）<b>不被改写</b>；</li>
 *   <li>{@code refresh_tokens}：{@code uk_rt_token_hash} 唯一约束负向 + {@code fk_rt_user}
 *       级联删除负向（R15 闭环）；</li>
 *   <li>上下文缺失时<b>拒绝查询</b>（不再退化为哨兵 {@code 0}）。</li>
 * </ul>
 *
 * <p>不启动 Spring 上下文：手工装配 MyBatis-Plus（{@link MybatisSqlSessionFactoryBean}）+
 * {@link TenantLineInnerInterceptor}，这样断言的是**最终下发的 SQL 文本**，证据强度高于仅断言结果集。
 */
@Testcontainers
class IdentitySchemaAndTenantLineTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    /** 本测试捕获到的最终 SQL（改写后）。 */
    private static final List<String> EXECUTED_SQL = new CopyOnWriteArrayList<>();

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());

        // ---- 1) 真实执行 Flyway 迁移链（V1.0.0 baseline + V1.0.1 identity + V1.0.2 refresh_tokens + V1.0.3 catalog）----
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load()
                .migrate();

        // ---- 2) 手工装配 MyBatis-Plus + 租户线拦截器 ----
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        // 开 SQL 日志（取证用；同时 SqlCaptureInterceptor 会把最终 SQL 收集到 EXECUTED_SQL）
        configuration.setLogImpl(StdOutImpl.class);
        configuration.addMapper(TeamMemberMapper.class);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(RefreshTokenMapper.class);
        factoryBean.setConfiguration(configuration);

        MybatisPlusInterceptor mybatisPlusInterceptor = new MybatisPlusInterceptor();
        mybatisPlusInterceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new ShopPilotTenantLineHandler()));
        // 插件顺序：MyBatis 按数组顺序 wrap，后者在外层先执行。
        // 故把捕获器放在【前】，使其成为内层——MybatisPlusInterceptor 的 beforePrepare
        // （内含租户 SQL 改写）先执行，捕获器随后即可读到【改写后】的最终 SQL。
        factoryBean.setPlugins(new SqlCaptureInterceptor(EXECUTED_SQL), mybatisPlusInterceptor);

        sqlSessionFactory = factoryBean.getObject();
    }

    @BeforeEach
    void cleanDataAndContext() throws Exception {
        TeamContext.clear();
        EXECUTED_SQL.clear();
        // 同一容器跨测试共享：先清表保证基数确定（删除顺序：先子表，后父表，避免外键约束）
        execute("DELETE FROM refresh_tokens");
        execute("DELETE FROM team_members");
        execute("DELETE FROM users");
        execute("DELETE FROM teams");
    }

    @AfterEach
    void clearContextAndLog() {
        TeamContext.clear();
        EXECUTED_SQL.clear();
    }

    // ---------------------------------------------------------------------
    // F-B3：迁移与约束
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("F-B3：迁移应用成功，三表存在且唯一约束/检查约束确实生效")
    void migrationCreatesIdentityTablesWithConstraints() throws Exception {
        List<String> tables = queryStrings(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name");
        assertTrue(tables.contains("users"), "users 表应存在，实际：" + tables);
        assertTrue(tables.contains("teams"), "teams 表应存在");
        assertTrue(tables.contains("team_members"), "team_members 表应存在");
        assertTrue(tables.contains("refresh_tokens"), "refresh_tokens 表应存在（V1.0.2）");
        assertTrue(tables.contains("flyway_schema_history"), "Flyway 历史表应存在");

        List<String> versions = queryStrings(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
        assertEquals(List.of("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5", "1.0.6", "1.0.7", "1.0.8", "1.0.9", "1.0.10", "1.0.11"),
                versions,
                "应应用 V1.0.0 → V1.0.11 十二条迁移"
                        + "（V1.0.3 为 M2-T08 catalog，V1.0.4 为 M2-T11 audit，V1.0.5 为 M3-T01 importdata，"
                        + "V1.0.6 为 M3-T08 inventory，V1.0.7 为 M3-T09 inventory R53 修正，"
                        + "V1.0.8 为 M3-T12 sales，V1.0.9 为 M4-T02 analysis，V1.0.10 为 M5-T01 replenishment，V1.0.11 为 M6-T01 purchasing）");

        // CHECK 约束负向验证（MySQL 8.0.16+ 才生效，版本守卫已保证）
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO teams (code, name) VALUES ('T-OK', '正常团队')");
        }
        assertThrows(Exception.class,
                () -> execute("INSERT INTO teams (code, name, status) VALUES ('T-BAD', '非法状态', 'HACKED')"),
                "ck_teams_status 应拒绝非法状态值");

        // uk_tm_team_user 负向验证：同一 (team_id, user_id) 不得重复
        execute("INSERT INTO users (username, password_hash) VALUES ('u1', 'x')");
        long userId = queryLong("SELECT id FROM users WHERE username = 'u1'");
        long teamId = queryLong("SELECT id FROM teams WHERE code = 'T-OK'");
        execute("INSERT INTO team_members (team_id, user_id, role, status) VALUES ("
                + teamId + ", " + userId + ", 'OWNER', 'ACTIVE')");
        assertThrows(Exception.class,
                () -> execute("INSERT INTO team_members (team_id, user_id, role, status) VALUES ("
                        + teamId + ", " + userId + ", 'VIEWER', 'ACTIVE')"),
                "uk_tm_team_user 应拒绝同团队重复成员");

        // ck_tm_role
        assertThrows(Exception.class,
                () -> execute("INSERT INTO team_members (team_id, user_id, role, status) VALUES ("
                        + teamId + ", " + userId + ", 'SUPERUSER', 'ACTIVE')"),
                "ck_tm_role 应拒绝非法角色");
    }

    // ---------------------------------------------------------------------
    // F-B2：租户线改写（SQL 文本级取证）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("F-B2：查询含 team_id 的表时，SQL 被自动追加 team_id = <当前团队>")
    void queryIsRewrittenWithCurrentTeam() throws Exception {
        seedTwoTeams();

        TeamContext.set(TeamContext.Snapshot.of(1L, List.of("OWNER"), 1L, "u1"));
        EXECUTED_SQL.clear();

        List<TeamMemberRow> rows;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            rows = session.getMapper(TeamMemberMapper.class).selectList(null);
        }

        assertHasSql("from team_members", "team_id = 1");
        // 库内该表共 2 行（team 1 / team 2 各一），当前团队只应看到 1 行
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM team_members"), "前置：库内应有 2 行");
        assertEquals(1, rows.size(), "跨团队数据必须被隔离；实际返回 " + rows.size() + " 行");
        assertEquals(1L, rows.get(0).getTeamId().longValue());
    }

    @Test
    @DisplayName("F-B2：不含 team_id 的表（users/teams）不被改写")
    void tablesWithoutTeamIdAreNotRewritten() throws Exception {
        seedTwoTeams();
        TeamContext.set(TeamContext.Snapshot.of(1L, List.of("OWNER"), 1L, "u1"));
        EXECUTED_SQL.clear();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(UserMapper.class).selectList(null);
        }

        assertTrue(EXECUTED_SQL.stream().anyMatch(s -> s.toLowerCase().contains("from users")),
                "应捕获到 users 查询，实际：" + EXECUTED_SQL);
        assertTrue(EXECUTED_SQL.stream().noneMatch(s -> s.contains("team_id")),
                "users（§6 规则 1 例外表）不得被追加 team_id 条件，实际 SQL：" + EXECUTED_SQL);
    }

    @Test
    @DisplayName("F-B2：INSERT 自动补齐 team_id（无需业务代码显式赋值）")
    void insertAutoFillsTeamId() throws Exception {
        seedTwoTeams();
        // fk_tm_user 是真实外键，先备好被引用的用户行
        execute("INSERT IGNORE INTO users (id, username, password_hash) VALUES (99, 'u99', 'x')");
        TeamContext.set(TeamContext.Snapshot.of(1L, List.of("OWNER"), 1L, "u1"));
        EXECUTED_SQL.clear();

        TeamMemberRow row = new TeamMemberRow();
        row.setUserId(99L); // 不含 teamId
        row.setRole("VIEWER");
        row.setStatus("ACTIVE");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(TeamMemberMapper.class).insert(row);
        }

        assertTrue(EXECUTED_SQL.stream().anyMatch(s -> {
                    String lower = s.toLowerCase();
                    return lower.contains("insert into team_members") && s.contains("team_id");
                }),
                "INSERT 应自动补齐 team_id 列，实际 SQL：" + EXECUTED_SQL);
        long actualTeamId = queryLong("SELECT team_id FROM team_members WHERE user_id = 99");
        assertEquals(1L, actualTeamId, "INSERT 落库的 team_id 应取自服务端上下文");
    }

    @Test
    @DisplayName("F-B2：上下文缺失时拒绝查询（AUTH-1001），不再退化为哨兵 team_id=0")
    void missingContextRejectsQuery() throws Exception {
        seedTwoTeams();
        TeamContext.clear();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            Exception ex = assertThrows(Exception.class,
                    () -> session.getMapper(TeamMemberMapper.class).selectList(null));
            assertTrue(ex.getMessage() == null || !ex.getMessage().contains("team_id = 0"),
                    "不得以 team_id = 0 兜底查询");
        }
    }

    // ---------------------------------------------------------------------
    // F-B4（R15 闭环）：refresh_tokens —— 无 team_id 表 + 唯一约束 + 级联
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("F-B4：refresh_tokens（无 team_id）不被追加 team_id 条件——漏登记将导致登出静默失效")
    void refreshTokensIsNotRewrittenWithTeamId() throws Exception {
        execute("INSERT INTO users (id, username, password_hash) VALUES (77, 'u77', 'x')");
        execute("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
                + "VALUES (77, REPEAT('a', 64), '2099-01-01 00:00:00.000')");
        TeamContext.set(TeamContext.Snapshot.of(1L, List.of("OWNER"), 77L, "u77"));
        EXECUTED_SQL.clear();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(RefreshTokenMapper.class).selectList(null);
        }

        assertTrue(EXECUTED_SQL.stream().anyMatch(s -> s.toLowerCase().contains("from refresh_tokens")),
                "应捕获到 refresh_tokens 查询，实际：" + EXECUTED_SQL);
        assertTrue(EXECUTED_SQL.stream().noneMatch(s -> s.toLowerCase().contains("team_id")),
                "refresh_tokens 无 team_id，不得被追加租户条件；否则撤销查询永远返回空行"
                        + "（登出静默失效——不报错、只是不生效）。实际 SQL：" + EXECUTED_SQL);
    }

    @Test
    @DisplayName("F-B4：uk_rt_token_hash 唯一约束生效——同一摘要重复插入被拒绝")
    void refreshTokenHashIsUnique() throws Exception {
        execute("INSERT INTO users (id, username, password_hash) VALUES (78, 'u78', 'x')");
        List<String> uniques = queryStrings(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'refresh_tokens' "
                        + "AND constraint_type = 'UNIQUE'");
        assertTrue(uniques.contains("uk_rt_token_hash"),
                "refresh_tokens 应存在 uk_rt_token_hash，实际：" + uniques);

        String hash = "b".repeat(64);
        execute("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
                + "VALUES (78, '" + hash + "', '2099-01-01 00:00:00.000')");
        assertThrows(Exception.class,
                () -> execute("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
                        + "VALUES (78, '" + hash + "', '2099-01-02 00:00:00.000')"),
                "uk_rt_token_hash 应拒绝重复的 token_hash（防重放）");
    }

    @Test
    @DisplayName("F-B4：fk_rt_user 级联生效——删除用户后其令牌行一并消失（防悬挂凭据）")
    void deletingUserCascadesRefreshTokens() throws Exception {
        execute("INSERT INTO users (id, username, password_hash) VALUES (79, 'u79', 'x')");
        execute("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
                + "VALUES (79, REPEAT('c', 64), '2099-01-01 00:00:00.000'), "
                + "        (79, REPEAT('d', 64), '2099-01-01 00:00:00.000')");
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = 79"),
                "前置：用户 79 名下应有 2 条令牌行");

        execute("DELETE FROM users WHERE id = 79");

        assertEquals(0L, queryLong("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = 79"),
                "ON DELETE CASCADE 应删除该用户的全部令牌行（全库唯一级联）");
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private static void seedTwoTeams() throws Exception {
        execute("INSERT IGNORE INTO teams (id, code, name) VALUES (1, 'T1', '团队一'), (2, 'T2', '团队二')");
        execute("INSERT IGNORE INTO users (id, username, password_hash) VALUES (11, 'u11', 'x'), (12, 'u12', 'x')");
        execute("INSERT IGNORE INTO team_members (team_id, user_id, role, status) VALUES "
                + "(1, 11, 'OWNER', 'ACTIVE'), (2, 12, 'OWNER', 'ACTIVE')");
    }

    /** 断言捕获到的 SQL 中「存在一条同时包含全部片段」的语句（改写前后可能各出现一次，故按内容匹配）。 */
    private static void assertHasSql(String... fragments) {
        boolean hit = EXECUTED_SQL.stream().anyMatch(sql -> {
            String lower = sql.toLowerCase();
            for (String fragment : fragments) {
                if (!lower.contains(fragment.toLowerCase())) {
                    return false;
                }
            }
            return true;
        });
        assertTrue(hit, "未捕获到同时包含 " + String.join(" + ", fragments) + " 的 SQL；实际捕获："
                + EXECUTED_SQL);
    }

    private static void execute(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private static List<String> queryStrings(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static long queryLong(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 捕获最终下发 SQL（TenantLineInnerInterceptor 改写后的文本）。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    public static class SqlCaptureInterceptor implements Interceptor {

        private final List<String> sink;

        SqlCaptureInterceptor(List<String> sink) {
            this.sink = sink;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            sink.add(handler.getBoundSql().getSql());
            return invocation.proceed();
        }
    }

    /** 含 team_id 的表（team_members）。 */
    public interface TeamMemberMapper extends BaseMapper<TeamMemberRow> {
    }

    @TableName("team_members")
    public static class TeamMemberRow {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long teamId;
        private Long userId;
        private String role;
        private String status;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getTeamId() {
            return teamId;
        }

        public void setTeamId(Long teamId) {
            this.teamId = teamId;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /** 不含 team_id 的表（users，§6 规则 1 例外）。 */
    public interface UserMapper extends BaseMapper<UserRow> {
    }

    @TableName("users")
    public static class UserRow {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String username;
        private String passwordHash;
        private String status;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /** 无 {@code team_id} 的表（refresh_tokens；R15 闭环，§6 规则 1 第 3 张例外表）。 */
    public interface RefreshTokenMapper extends BaseMapper<RefreshTokenRow> {
    }

    @TableName("refresh_tokens")
    public static class RefreshTokenRow {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long userId;
        private String tokenHash;
        private java.time.LocalDateTime expiresAt;
        private java.time.LocalDateTime revokedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getTokenHash() {
            return tokenHash;
        }

        public void setTokenHash(String tokenHash) {
            this.tokenHash = tokenHash;
        }

        public java.time.LocalDateTime getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(java.time.LocalDateTime expiresAt) {
            this.expiresAt = expiresAt;
        }

        public java.time.LocalDateTime getRevokedAt() {
            return revokedAt;
        }

        public void setRevokedAt(java.time.LocalDateTime revokedAt) {
            this.revokedAt = revokedAt;
        }
    }
}
