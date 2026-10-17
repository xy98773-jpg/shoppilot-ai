package com.shoppilot.common.tenant;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.app.ShopPilotApplication;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-T13 §1-C：DB 层团队隔离（{@code DATABASE_CONSTRAINTS.md §8.4} 的 T-3.1 / T-5.1）
 * —— 端到端集成测试（真实 MySQL 8.0.36 + 真实 Flyway 迁移）。
 *
 * <ul>
 *   <li><b>T-3.1</b>「应用层不注入 {@code team_id} 直接 INSERT」→ <b>失败</b>
 *       （{@code NOT NULL} 无默认值）：本类对 M2 全部含 {@code team_id} 的业务表逐表验证；</li>
 *   <li><b>T-5.1</b>「不带 {@code team_id} 的查询方法」→ <b>编译期不存在</b>：以两条机械断言落地
 *       ——① 真实库表结构与 {@code TenantTables} 黑名单<b>交叉验证</b>（防「表无 {@code team_id} 却漏登记」）；
 *       ② 反射扫描全部 {@code BaseMapper} 接口，{@code @InterceptorIgnore(tenantLine="true")} 的
 *       例外方法必须<b>恰为白名单</b>且每个都自带显式 SQL（防绕过租户线的裸 {@code BaseMapper} 调用）。</li>
 * </ul>
 *
 * <h2>与既有用例的关系（不重复声明）</h2>
 * {@code IdentitySchemaAndTenantLineTest} 已覆盖：迁移应用、{@code team_members} 查询被追加 {@code team_id}、
 * INSERT 自动补齐、例外表不改写、{@code refresh_tokens} 唯一约束与级联。
 * 本类<b>不重复</b>上述内容，只补 T-3.1（裸 INSERT 失败）与 T-5.1（签名/白名单机械断言）。
 */
@SpringBootTest(classes = ShopPilotApplication.class)
@Testcontainers
class M2_TenantIsolationDbIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final long TEAM_ID = 7L;

    /**
     * 允许绕过租户线的<b>受控例外</b>（{@code TeamMapper} 之外的全部登记项）。
     *
     * <p>每一处都满足「SQL 显式携带 {@code team_id} / {@code user_id}，或仅取 {@code team_id} 单列」，
     * 见各 Mapper 的 Javadoc。新增任何一项都必须先在本白名单登记——否则本条断言会失败，
     * 从而把「悄悄绕过租户线」变成一次性可见的失败，而不是后患。
     */
    private static final Set<String> TENANT_LINE_EXCEPTIONS = Set.of(
            "TeamMemberMapper#selectActiveMemberships",     // 登录选团队：尚无上下文，SQL 带 user_id
            "TeamMemberMapper#selectByTeamAndUserNoTenant", // 每请求成员状态校验：SQL 带 team_id + user_id
            "WarehouseMapper#selectTeamIdById",             // 跨团队归属探测：仅取 team_id 单列
            "SupplierMapper#selectTeamIdById",
            "ProductMapper#selectTeamIdById",
            "SkuMapper#selectTeamIdById",
            "WarehouseMapper#selectTeamIdByCode",           // 跨团队归属探测（按编码）：仅取 team_id 单列，导入校验引用存在性/同团队（B3 裁定）
            "SupplierMapper#selectTeamIdByCode",
            "ProductMapper#selectTeamIdByCode",
            "SkuMapper#selectTeamIdByCode",
            "AuditLogMapper#insert",                        // 无上下文（登录失败）也要能写：显式 team_id
            "AuditLogMapper#selectRecentByBiz",             // 显式 team_id 条件
            "InventoryBalanceMapper#insertIfAbsent"         // 并发首次建行：ON DUPLICATE KEY UPDATE
                                                            //   （自带 team_id 列 + 解析兼容性，见其 Javadoc）
    );

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name) VALUES (?, 'T-HX', '星海优选')", TEAM_ID);
        jdbc.update("INSERT INTO users (id, username, password_hash) VALUES (999, 'seed-u', 'x')");
        jdbc.update("INSERT INTO products (team_id, product_code, name) VALUES (?, 'PRD-SEED', '种子商品')", TEAM_ID);
    }

    // =====================================================================
    // T-3.1：应用层不注入 team_id 直接 INSERT → 失败（NOT NULL 无默认值）
    // =====================================================================

    @Test
    @DisplayName("T-3.1 裸 INSERT 不带 team_id：catalog 四表 + team_members 全部失败（NOT NULL 无默认值）")
    void bareInsertWithoutTeamIdFailsOnEveryBusinessTable() {
        // ★ 关键：这些 INSERT 是「应用层忘记注入 team_id」的等价物（绕过 MyBatis 租户线，直连 DB）。
        //   若某张表被写成 NULL 可插入（例如加了 DEFAULT 0），隔离就退化为「默认落 0 号系统域」——
        //   轻则查不到数据，重则跨团队写入。本断言把这种退化钉死在 DB 层。

        assertInsertRejected("warehouses", "INSERT INTO warehouses (code, name) VALUES ('WH-NO-TEAM', '缺团队仓')");
        assertInsertRejected("suppliers", "INSERT INTO suppliers (code, name) VALUES ('SUP-NO-TEAM', '缺团队供应商')");
        assertInsertRejected("products",
                "INSERT INTO products (product_code, name) VALUES ('PRD-NO-TEAM', '缺团队商品')");
        assertInsertRejected("skus",
                "INSERT INTO skus (product_id, sku_code, name) SELECT id, 'SKU-NO-TEAM', '缺团队SKU' "
                        + "FROM products WHERE product_code = 'PRD-SEED'");
        assertInsertRejected("team_members",
                "INSERT INTO team_members (user_id, role, status) VALUES (999, 'VIEWER', 'ACTIVE')");

        // 对照：带上 team_id 则成功（证明失败的唯一原因是缺 team_id，而非其它约束）
        assertEquals(1, jdbc.update("INSERT INTO warehouses (team_id, code, name) VALUES (?, 'WH-OK', '正常仓')",
                TEAM_ID), "带 team_id 的 INSERT 应成功");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE code = 'WH-OK'", Long.class));
    }

    private void assertInsertRejected(String table, String sql) {
        // 断言父类 DataAccessException：MySQL 对「NOT NULL 无默认值」的违反，
        // 经 Spring 异常翻译后可能落为 DataIntegrityViolationException 或
        // UncategorizedSQLException（取决于驱动的 errorCode/SQLState 组合），二者同属 DataAccessException。
        // 关键不在于异常的精确子类，而在于【写入被数据库拒绝】且原因指向 team_id。
        DataAccessException ex = assertThrows(DataAccessException.class,
                () -> jdbc.update(sql),
                table + " 缺少 team_id 的 INSERT 必须失败（NOT NULL 且无默认值）");
        String message = ex.getMostSpecificCause() == null ? String.valueOf(ex.getMessage())
                : ex.getMostSpecificCause().getMessage();
        assertTrue(message != null && message.contains("team_id"),
                table + " 的失败原因应指向 team_id 列，实测：" + message);
        // MySQL 对此报两种措辞：缺列且无默认值 → "Field 'team_id' doesn't have a default value"；
        // 显式传 NULL → "Column 'team_id' cannot be null"。两者都证明该列【无默认值且 NOT NULL】。
        String lower = message == null ? "" : message.toLowerCase();
        assertTrue(lower.contains("default") || lower.contains("null"),
                table + " 的失败原因应为「无默认值 / 不可为 NULL」，实测：" + message);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE team_id = 0", Long.class),
                table + " 不得因此落下 team_id = 0 的行（0 是系统域哨兵，不是合法团队）");
    }

    // =====================================================================
    // T-5.1-a：真实库表结构 ↔ TenantTables 黑名单 交叉验证
    // =====================================================================

    @Test
    @DisplayName("T-5.1-a 全表交叉验证：真实库中「含 team_id 的表」与 TenantTables 口径完全一致（防漏登记）")
    void tenantTablesWhitelistMatchesRealSchema() {
        List<String> businessTables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history' ORDER BY table_name", String.class);

        assertFalse(businessTables.isEmpty(), "应至少有一张业务表（Flyway 迁移已执行）");
        assertTrue(businessTables.containsAll(List.of(
                        "users", "teams", "team_members", "refresh_tokens",
                        "warehouses", "suppliers", "products", "skus", "audit_logs")),
                "M2 应有 9 张业务表，实测：" + businessTables);

        for (String table : businessTables) {
            boolean declaredNoTeamId = !TenantTables.hasTeamIdColumn(table);
            boolean hasTeamIdColumn = columnIsNullable(table, "team_id") != null;

            assertEquals(!declaredNoTeamId, hasTeamIdColumn,
                    "表 " + table + " 的 team_id 实际存在性与 TenantTables 口径不一致："
                            + "TenantTables 认为" + (declaredNoTeamId ? "【无】" : "【有】") + " team_id，"
                            + "实际库中" + (hasTeamIdColumn ? "【有】" : "【无】") + "。"
                            + "★ 若新表无 team_id 却漏登记 WITHOUT_TEAM_ID，租户线会对其追加 team_id = ? 而永远查不到行");

            if (hasTeamIdColumn) {
                // DESIGN_BASIS §6 规则 1：NOT NULL 且【无默认值】（正是 T-3.1 成立的根据）
                assertEquals("NO", columnIsNullable(table, "team_id"),
                        "表 " + table + " 的 team_id 必须 NOT NULL");
                assertNullDefault(table, "team_id");
            }
        }

        // 例外表恰为 3 张（DESIGN_BASIS §6 规则 1 的显式例外；flyway 表已排除）
        List<String> withoutTeamId = businessTables.stream()
                .filter(t -> !TenantTables.hasTeamIdColumn(t)).sorted().toList();
        assertEquals(List.of("refresh_tokens", "teams", "users"), withoutTeamId,
                "含 team_id 的例外表必须恰为 users / teams / refresh_tokens 三张");
    }

    private String columnIsNullable(String table, String column) {
        List<String> rows = jdbc.queryForList(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, table, column);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertNullDefault(String table, String column) {
        List<String> defaults = jdbc.queryForList(
                "SELECT COALESCE(column_default, '') FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, table, column);
        assertEquals(1, defaults.size());
        assertTrue(defaults.get(0).isEmpty(),
                table + "." + column + " 必须【无默认值】——否则 T-3.1 失效（漏注入会被静默补成默认值），实测="
                        + defaults.get(0));
    }

    // =====================================================================
    // T-5.1-b：反射扫描全部 Mapper —— 租户线例外必须恰为白名单且自带显式 SQL
    // =====================================================================

    @Test
    @DisplayName("T-5.1-b 反射扫描全部 BaseMapper：绕过租户线的方法恰为 8 处白名单，且每处都有显式 SQL")
    void tenantLineExceptionsAreExactlyTheWhitelist() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        // Mapper 是接口，默认扫描会跳过接口，这里显式放开
                        return beanDefinition.getMetadata().isInterface();
                    }
                };
        // 两个过滤器取【并集】：① 继承 BaseMapper 的接口；② 标注 @Mapper 的接口。
        //   ★ 必须带上 ②：AuditLogMapper 刻意【不】继承 BaseMapper（只允许新增/查询，
        //     见其类注释与 AuditChannelIntegrationTest §6-5），仅靠 ① 会漏掉它，
        //     使「未登记例外」这条防线出现盲区。
        scanner.addIncludeFilter(new AssignableTypeFilter(BaseMapper.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Mapper.class));

        Set<String> actual = new TreeSet<>();
        int mapperCount = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.shoppilot")) {
            Class<?> mapper = Class.forName(candidate.getBeanClassName(), false,
                    getClass().getClassLoader());
            mapperCount++;
            for (Method method : mapper.getDeclaredMethods()) {
                InterceptorIgnore ignore = method.getAnnotation(InterceptorIgnore.class);
                if (ignore == null || !Boolean.parseBoolean(ignore.tenantLine())) {
                    continue;
                }
                String key = mapper.getSimpleName() + "#" + method.getName();
                actual.add(key);

                // 受控例外必须自带显式 SQL：否则该方法会落到 BaseMapper 的默认实现上，
                // 而默认实现完全依赖租户线——一旦被 ignore，隔离就彻底消失（无 team_id、无条件）。
                assertTrue(hasExplicitSql(method),
                        key + " 绕过租户线却未声明显式 SQL —— 这将导致该查询【完全无团队隔离】");
            }
        }

        assertTrue(mapperCount >= 9,
                "应扫描到 9 个 Mapper 接口（identity 4 + catalog 4 + audit 1），实测 " + mapperCount
                        + "——若显著偏少说明扫描器口径失效，本条断言即形同虚设");
        assertEquals(new TreeSet<>(TENANT_LINE_EXCEPTIONS), actual,
                "绕过租户线的方法集合必须与白名单完全一致；"
                        + "新增例外须先在此登记并写清理由（防『悄悄绕过』）");
    }

    private static boolean hasExplicitSql(Method method) {
        return method.isAnnotationPresent(Select.class)
                || method.isAnnotationPresent(Insert.class)
                || method.isAnnotationPresent(Update.class)
                || method.isAnnotationPresent(Delete.class);
    }

    // =====================================================================
    // T-5.1-c：catalog 归属探测方法只取 team_id 单列（不泄露任何业务字段）
    // =====================================================================

    @Test
    @DisplayName("T-5.1-c 跨团队归属探测 SQL 仅 SELECT team_id 单列（不泄露业务字段，API_CONTRACT §5.6）")
    void teamIdProbeSelectsOnlyTeamIdColumn() {
        List<String> probes = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                        + "AND table_name IN ('warehouses', 'suppliers', 'products', 'skus') ORDER BY table_name",
                String.class);
        assertEquals(List.of("products", "skus", "suppliers", "warehouses"), probes,
                "catalog 四表应齐备（T09/T10 已交付）");

        // 通过反射读回四个 Mapper 的 @Select 文本，断言其投影恰为 team_id
        assertProbeSql("com.shoppilot.catalog.infrastructure.persistence.mapper.WarehouseMapper");
        assertProbeSql("com.shoppilot.catalog.infrastructure.persistence.mapper.SupplierMapper");
        assertProbeSql("com.shoppilot.catalog.infrastructure.persistence.mapper.ProductMapper");
        assertProbeSql("com.shoppilot.catalog.infrastructure.persistence.mapper.SkuMapper");
    }

    private void assertProbeSql(String mapperClassName) {
        try {
            Class<?> mapper = Class.forName(mapperClassName);
            Method probe = mapper.getMethod("selectTeamIdById", Long.class);
            Select select = probe.getAnnotation(Select.class);
            assertTrue(select != null, mapperClassName + " 的归属探测方法应有 @Select");
            String sql = String.join(" ", select.value());
            String projection = sql.replaceAll("(?i)^\\s*SELECT\\s+", "").split("(?i)\\s+FROM\\s+")[0].trim();
            assertEquals("team_id", projection.toLowerCase(),
                    mapperClassName + " 的归属探测只能取 team_id 单列（不得回传业务字段），实测投影=" + projection);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射读取 " + mapperClassName + " 失败：" + e.getMessage(), e);
        }
    }
}
