package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.audit.application.AuditBufferScope;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.catalog.application.CatalogWriteService;
import com.shoppilot.catalog.infrastructure.persistence.entity.ProductEntity;
import com.shoppilot.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.shoppilot.common.context.TeamContext;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>分层计时</b>（{@code B4-PERF} 轮 1 的达标判定与归因取证）。
 *
 * <h2>为什么需要它</h2>
 * {@code DESIGN_REVIEW §5.2} 的教训 L2-4：**不得以下减法残差冒充实测分解**。端到端 88–115 s 只能
 * 说明「整体不达标」，无法回答「慢在哪一层」。本类把 confirm 的执行路径拆成可独立计时的段，
 * 在<b>同一 JVM / 同一数据库会话</b>内测量，避免跨运行波动污染结论。
 *
 * <h2>测的分段（与 {@code ImportConfirmService} 的实际路径一一对应）</h2>
 * <table border="1">
 *   <caption>分段</caption>
 *   <tr><th>段</th><th>被测代码</th><th>对应线上位置</th></tr>
 *   <tr><td>{@code body-build}</td><td>构造 20×500 个 {@code ObjectNode}（映射列 → 值）</td>
 *       <td>{@code ImportConfirmService.buildRowBody}</td></tr>
 *   <tr><td>{@code pass1-create}</td><td>20 × {@code catalog.upsertProductsByCode(500 条)}
 *       包在 {@code TransactionTemplate} + {@code AuditBufferScope} 内</td>
 *       <td>Pass 1 落库（新建）+ 提交后审计批量写</td></tr>
 *   <tr><td>{@code pass1-update}</td><td>同上，但 10000 个编码<b>已存在</b>（走 update 分支）</td>
 *       <td>Pass 1 落库（更新）+ 导入更新审计（{@code R48}）</td></tr>
 *   <tr><td>{@code audit-write}</td><td>20 × {@code audit.recordAll(500)}（实体组装 + 脱敏 + insert + 提交）</td>
 *       <td>批次事务提交后的审计批量写</td></tr>
 *   <tr><td>{@code read-by-code}</td><td>10000 × {@code ProductMapper.selectIdByCode}</td>
 *       <td>upsert 的「按编码解析 id」</td></tr>
 *   <tr><td>{@code read-by-id}</td><td>10000 × {@code ProductMapper.selectById}</td>
 *       <td>写后重查视图（{@code XxxView.of(selectById)}）</td></tr>
 * </table>
 *
 * <p>{@code pass1-create} − {@code audit-write} ≈ 业务新建落库；再减去
 * {@code read-by-code} + {@code read-by-id} ≈ 单条 {@code INSERT} 及实体映射开销。
 *
 * <h2>运行方式（不进默认 {@code verify}）</h2>
 * <pre>
 *   cd backend-java &amp;&amp; ./mvnw -B -pl app -am -Dtest=ImportPerfProfileBenchmark \
 *       -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 * 读数写入 {@code target/import-perf-profile.txt}。
 */
@Tag("perf")
@SpringBootTest(classes = ShopPilotApplication.class)
@Testcontainers
class ImportPerfProfileBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ImportPerfProfileBenchmark.class);

    private static final int ROWS = 10_000;
    private static final int BATCH = 500;
    private static final long TEAM_ID = 7L;
    private static final long USER_ID = 1001L;
    private static final String USERNAME = "operator01";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogWriteService catalogWriteService;

    @Autowired
    private AuditWriteService auditWriteService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");
        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) "
                + "VALUES (?, 'T-7', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                USER_ID, USERNAME, USERNAME + "@example.com", TestCredentials.PASSWORD_HASH, USERNAME);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) "
                + "VALUES (?, ?, 'OPERATOR', 'ACTIVE')", TEAM_ID, USER_ID);
        // 服务层直调：补上过滤链本该提供的 TeamContext（含操作者——审计要取）
        TeamContext.set(TeamContext.Snapshot.of(TEAM_ID, List.of("OPERATOR"), USER_ID, USERNAME));
    }

    @Test
    @DisplayName("分层计时：body-build / pass1-create / pass1-update / audit-write / read-by-code / read-by-id")
    void profileLayers() throws Exception {
        List<String> codes = new ArrayList<>(ROWS);
        for (int i = 1; i <= ROWS; i++) {
            codes.add(String.format("P-PROF-%05d", i));
        }

        // ---------- 1) body-build（构造结果留给后续各段复用）----------
        long t = System.nanoTime();
        List<List<JsonNode>> chunks = chunksOfBodies(codes);
        long bodyBuild = ms(t);

        // ---------- 2) pass1-create（新建：3 条语句/行 + 提交后审计批量写）----------
        t = System.nanoTime();
        long worstCreateBatch = runBatch(chunks);
        long pass1Create = ms(t);

        // ---------- 3) audit-write（20 × recordAll(500)，独立测）----------
        List<List<AuditEvent>> auditChunks = chunksOfAudits(codes);
        t = System.nanoTime();
        for (List<AuditEvent> chunk : auditChunks) {
            auditWriteService.recordAll(chunk);
        }
        long auditWrite = ms(t);
        jdbc.execute("DELETE FROM audit_logs");

        // ---------- 4) pass1-update（10000 行已存在 → 走 update 分支 + R48 审计）----------
        t = System.nanoTime();
        long worstUpdateBatch = runBatch(chunks);
        long pass1Update = ms(t);

        // ---------- 5) read-by-code ----------
        t = System.nanoTime();
        txTemplate.executeWithoutResult(tx -> {
            for (String code : codes) {
                productMapper.selectIdByCode(code);
            }
        });
        long readByCode = ms(t);

        // ---------- 6) read-by-id（写后重查视图）----------
        List<Long> ids = jdbc.queryForList("SELECT id FROM products WHERE team_id = ? ORDER BY id",
                Long.class, TEAM_ID);
        t = System.nanoTime();
        txTemplate.executeWithoutResult(tx -> {
            for (Long id : ids) {
                productMapper.selectById(id);
            }
        });
        long readById = ms(t);

        // =================================================================
        // 7) / 8) INSERT 语句级开销：MyBatis 单条 INSERT vs 裸 JDBC 单条 INSERT
        //    （轮 2 定向优化的判据：业务新建 = SELECT 解析 + INSERT 解析 + 反射/参数绑定；
        //      这两项把「INSERT 本身」单独剥离出来，避免把 selectIdByCode 的成本算到 INSERT 头上）
        // =================================================================
        jdbc.execute("DELETE FROM products");
        t = System.nanoTime();
        for (int from = 0; from < ROWS; from += BATCH) {
            final int start = from;
            final int end = Math.min(from + BATCH, ROWS);
            txTemplate.executeWithoutResult(tx -> {
                for (int i = start; i < end; i++) {
                    ProductEntity entity = new ProductEntity();
                    entity.setTeamId(TEAM_ID);
                    entity.setProductCode(codes.get(i));
                    entity.setName("插入计时");
                    entity.setStatus("ACTIVE");
                    productMapper.insert(entity);
                }
            });
        }
        long insertOnly = ms(t);

        jdbc.execute("DELETE FROM products");
        t = System.nanoTime();
        rawInsertOnlyLoop(codes);
        long rawInsertOnly = ms(t);
        int insertOnlyRows = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE team_id = ?",
                Integer.class, TEAM_ID);

        // =================================================================
        // 裸 JDBC 对照（同一 run、同一容器）——区分「MyBatis/拦截器开销」与「机器/DB 本身慢」
        // 口径对齐 backend-java/tools/perf-probe/PerfProbe.java 的 A 行（含按编码回查 id）
        // =================================================================
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM products");

        t = System.nanoTime();
        rawBusinessLoop(codes);
        long rawBusiness = ms(t);

        t = System.nanoTime();
        rawAuditLoop(codes);
        long rawAudit = ms(t);

        int rawProducts = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE team_id = ?",
                Integer.class, TEAM_ID);
        int rawAuditRows = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class);

        int products = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE team_id = ?",
                Integer.class, TEAM_ID);

        String report = "M3-B4-PERF 分层计时（同一 JVM / 同一 DB 会话，mysql:8.0.36 容器）\n"
                + "数据量        : " + ROWS + " 行 PRODUCT，批大小 " + BATCH + "（=20 个批次事务）\n"
                + "body-build    : " + bodyBuild + " ms（构造 " + ROWS + " 个 ObjectNode）\n"
                + "pass1-create  : " + pass1Create + " ms（20 批新建，含提交后审计批量写）\n"
                + "  audit-write : " + auditWrite + " ms（20 × recordAll(500)，独立测）\n"
                + "  ⇒ 业务新建   : " + (pass1Create - auditWrite) + " ms（create 路径，3 条语句/行）\n"
                + "pass1-update  : " + pass1Update + " ms（20 批更新，含导入更新审计）\n"
                + "  ⇒ 业务更新   : " + (pass1Update - auditWrite) + " ms（update 路径）\n"
                + "read-by-code  : " + readByCode + " ms（" + ROWS + " 次 selectIdByCode，"
                + String.format("%.3f", readByCode / (double) ROWS) + " ms/次）\n"
                + "read-by-id    : " + readById + " ms（" + ROWS + " 次 selectById，"
                + String.format("%.3f", readById / (double) ROWS) + " ms/次）\n"
                + "insert-only   : " + insertOnly + " ms（MyBatis 单条 INSERT ×" + ROWS
                + "，20 批，无 SELECT），" + String.format("%.3f", insertOnly / (double) ROWS)
                + " ms/行；落库 " + insertOnlyRows + " 行\n"
                + "raw-insert    : " + rawInsertOnly + " ms（裸 JDBC 单条 INSERT ×" + ROWS
                + "，20 批），" + String.format("%.3f", rawInsertOnly / (double) ROWS) + " ms/行\n"
                + "  ⇒ INSERT 相对开销 = insert-only(" + insertOnly + ") − raw-insert("
                + rawInsertOnly + ") = " + (insertOnly - rawInsertOnly) + " ms\n"
                + "create 每行   : " + String.format("%.3f", pass1Create / (double) ROWS) + " ms/行\n"
                + "update 每行   : " + String.format("%.3f", pass1Update / (double) ROWS) + " ms/行\n"
                + "审计写入每行  : " + String.format("%.3f", auditWrite / (double) ROWS)
                + " ms/行（recordAll 口径：实体组装 + 脱敏 + insert + 提交）\n"
                + "最慢单批耗时  : create=" + worstCreateBatch + " ms, update=" + worstUpdateBatch + " ms\n"
                + "落库行数      : products=" + products + "（新建 10000 + 更新复用同一批 id）\n"
                + "--- 裸 JDBC 对照（同一 run、同一容器；口径对齐 tools/perf-probe 的 A/B 行）---\n"
                + "raw-business  : " + rawBusiness + " ms（10000×(INSERT + 按编码 SELECT)，20 批事务）"
                + "，" + String.format("%.3f", rawBusiness / (double) ROWS) + " ms/行\n"
                + "raw-audit     : " + rawAudit + " ms（10000 条 audit_logs INSERT，20 批事务）"
                + "，" + String.format("%.3f", rawAudit / (double) ROWS) + " ms/行\n"
                + "raw 落库校验  : products=" + rawProducts + ", audit_logs=" + rawAuditRows + "\n"
                + "--- 结论口径 ---\n"
                + "MyBatis 相对开销 = 业务新建(" + (pass1Create - auditWrite) + ") − raw-business("
                + rawBusiness + ") = " + ((pass1Create - auditWrite) - rawBusiness) + " ms\n"
                + "审计相对开销     = audit-write(" + auditWrite + ") − raw-audit(" + rawAudit + ") = "
                + (auditWrite - rawAudit) + " ms\n";
        log.info("\n{}", report);
        try {
            Files.writeString(Path.of("target", "import-perf-profile.txt"), report, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("无法写入 profile 报告：{}", ex.getMessage());
        }

        assertEquals(ROWS, products, "10000 行应全部落库");
    }

    /**
     * 裸 JDBC 业务落库对照：10000 行 {@code INSERT + 按编码 SELECT}（2 条语句/行），20 批事务。
     * 口径对齐 {@code tools/perf-probe/PerfProbe.java} 的 A 行（{@code business only}）。
     */
    private void rawBusinessLoop(List<String> codes) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            for (int from = 0; from < codes.size(); from += BATCH) {
                int to = Math.min(from + BATCH, codes.size());
                c.setAutoCommit(false);
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO products (team_id, product_code, name, unit, status, created_by, updated_by) "
                                + "VALUES (7, ?, ?, '件', 'ACTIVE', 1001, 1001)");
                     PreparedStatement sel = c.prepareStatement(
                             "SELECT id FROM products WHERE team_id = 7 AND product_code = ?")) {
                    for (int i = from; i < to; i++) {
                        String code = codes.get(i);
                        ins.setString(1, code);
                        ins.setString(2, "压测商品");
                        ins.executeUpdate();
                        sel.setString(1, code);
                        try (ResultSet rs = sel.executeQuery()) {
                            rs.next();
                        }
                    }
                }
                c.commit();
            }
        }
    }

    /**
     * 裸 JDBC 纯插入对照：10000 行 {@code INSERT}（<b>无</b> SELECT），20 批事务。
     *
     * <p>与 {@link #rawBusinessLoop} 的差 = 10000 次按编码 SELECT 的成本；
     * 与 {@code insert-only} 的差 = MyBatis/租户线在 INSERT 语句上的相对开销。
     */
    private void rawInsertOnlyLoop(List<String> codes) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            for (int from = 0; from < codes.size(); from += BATCH) {
                int to = Math.min(from + BATCH, codes.size());
                c.setAutoCommit(false);
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO products (team_id, product_code, name, status) VALUES (7, ?, '插入计时', 'ACTIVE')")) {
                    for (int i = from; i < to; i++) {
                        ins.setString(1, codes.get(i));
                        ins.executeUpdate();
                    }
                }
                c.commit();
            }
        }
    }

    /** 裸 JDBC 审计写对照：10000 条 {@code audit_logs} 行，20 批事务（每批一次提交）。 */    private void rawAuditLoop(List<String> codes) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            for (int from = 0; from < codes.size(); from += BATCH) {
                int to = Math.min(from + BATCH, codes.size());
                c.setAutoCommit(false);
                try (PreparedStatement a = c.prepareStatement(
                        "INSERT INTO audit_logs (team_id, action, biz_type, biz_id, biz_no, "
                                + "operator_id, operator_name, operator_role, http_method, request_uri, "
                                + "result, change_summary, after_json, occurred_at, created_by) "
                                + "VALUES (7,'MASTER_DATA_STATUS_CHANGE','PRODUCT',?,?,'1001','operator01',"
                                + "'OPERATOR','POST','/api/v1/import/batches/1/confirm','SUCCESS',"
                                + "'创建商品',?, '2026-10-17 03:00:00.000',1001)")) {
                    for (int i = from; i < to; i++) {
                        a.setLong(1, i + 1);
                        a.setString(2, codes.get(i));
                        a.setString(3, "{\"status\":{\"before\":null,\"after\":\"ACTIVE\"}}");
                        a.executeUpdate();
                    }
                }
                c.commit();
            }
        }
    }

    /** 20 批「事务 + 审计作用域」，返回最慢批次耗时（发现长尾）。 */    private long runBatch(List<List<JsonNode>> chunks) {
        long worst = 0;
        for (List<JsonNode> chunk : chunks) {
            long t = System.nanoTime();
            try (AuditBufferScope ignored = new AuditBufferScope(auditWriteService)) {
                txTemplate.executeWithoutResult(tx -> catalogWriteService.upsertProductsByCode(chunk));
            }
            long one = ms(t);
            if (one > worst) {
                worst = one;
            }
        }
        return worst;
    }

    private List<List<JsonNode>> chunksOfBodies(List<String> codes) {
        List<List<JsonNode>> chunks = new ArrayList<>();
        for (int from = 0; from < codes.size(); from += BATCH) {
            int to = Math.min(from + BATCH, codes.size());
            List<JsonNode> chunk = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                chunk.add(buildBody(codes.get(i)));
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<List<AuditEvent>> chunksOfAudits(List<String> codes) {
        List<List<AuditEvent>> chunks = new ArrayList<>();
        for (int from = 0; from < codes.size(); from += BATCH) {
            int to = Math.min(from + BATCH, codes.size());
            List<AuditEvent> chunk = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                chunk.add(AuditEvent.builder()
                        .teamId(TEAM_ID)
                        .action(AuditAction.MASTER_DATA_STATUS_CHANGE)
                        .bizType("PRODUCT")
                        .bizId((long) i + 1)
                        .bizNo(codes.get(i))
                        .operatorId(USER_ID)
                        .operatorName(USERNAME)
                        .operatorRole("OPERATOR")
                        .changeSummary("创建商品")
                        .build());
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    private ObjectNode buildBody(String code) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("product_code", code);
        node.put("name", "压测商品");
        node.put("unit", "件");
        return node;
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
