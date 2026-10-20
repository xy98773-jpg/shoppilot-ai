package com.shoppilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.catalog.application.CatalogWriteService;
import com.shoppilot.catalog.infrastructure.persistence.entity.ProductEntity;
import com.shoppilot.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodeHttpStatus;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.inventory.application.InventoryPolicyService;
import com.shoppilot.inventory.application.dto.PolicyRequest;
import com.shoppilot.inventory.infrastructure.persistence.entity.ReplenishmentPolicyEntity;
import com.shoppilot.inventory.infrastructure.persistence.mapper.ReplenishmentPolicyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code R62} 回归闸（M4-B23）：证明 {@code version} 乐观锁<b>真的生效</b>，而非「建了列没人用」。
 *
 * <h2>覆盖（任务书 §E）</h2>
 * <ol>
 *   <li><b>两次陈旧读（{@code replenishment_policies}）</b>：A/B 同读 {@code version=0}，
 *       A 更新成功且 {@code version} 变 1，B 更新影响 0 行、数据仍为 A 的值；</li>
 *   <li><b>两次陈旧读（{@code products}，catalog 代表表）</b>：同上（手写 CAS 路径）；</li>
 *   <li><b>服务层 COM-0015</b>：用 MySQL {@code REPEATABLE READ} 快照构造确定性陈旧读
 *       （外层事务先读 → 另一连接提交越过版本 → 服务层 CAS 失败），不依赖线程调度；</li>
 *   <li><b>clearOtherDefaults 豁免</b>：批量清默认仓仍生效，且不因缺 version 条件而报错；</li>
 *   <li><b>HTTP 映射</b>：{@code COM-0015} 与 {@code OptimisticLockingFailureException}
 *       经真实安全链 + 全局异常处理器返回 <b>409</b>。</li>
 * </ol>
 *
 * <p>★ 只 Mock 无（DB / Mapper / 安全链全真）；测试端点仅用于触发异常映射，不写任何业务数据。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(r62test.R62FaultController.class)
@Testcontainers
class R62OptimisticLockIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String RAW_PASSWORD = TestCredentials.RAW_PASSWORD;
    private static final String PASSWORD_HASH = TestCredentials.PASSWORD_HASH;

    private static final long TEAM_ID = 7L;
    private static final long OPERATOR_ID = 1001L;

    private static final long PRODUCT_ID = 9001L;
    private static final long WAREHOUSE_MAIN = 9004L;
    private static final long WAREHOUSE_SECOND = 9010L;
    private static final long SKU_ID = 9002L;

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
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private ReplenishmentPolicyMapper policyMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private InventoryPolicyService policyService;

    @Autowired
    private CatalogWriteService catalogWriteService;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    private long policyId;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM replenishment_policies");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES (?, 'T-R62', '锁组', 'ACTIVE', 'Asia/Shanghai')",
                TEAM_ID);
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, 'operator01', 'operator01@example.com', ?, '小王', 'ACTIVE')",
                OPERATOR_ID, PASSWORD_HASH);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) VALUES (?, ?, 'OPERATOR', 'ACTIVE')",
                TEAM_ID, OPERATOR_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name, category, status, version) "
                + "VALUES (?, ?, 'P-R62', 'orig-name', 'CAT-01', 'ACTIVE', 0)", PRODUCT_ID, TEAM_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status, is_default, version) VALUES "
                        + "(?, ?, 'WH-MAIN', '主仓', 'ACTIVE', 0, 0), "
                        + "(?, ?, 'WH-SECOND', '第二仓', 'ACTIVE', 0, 0)",
                WAREHOUSE_MAIN, TEAM_ID, WAREHOUSE_SECOND, TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status, version) "
                + "VALUES (?, ?, ?, 'SKU-R62', '规格R62', 'ACTIVE', 0)", SKU_ID, TEAM_ID, PRODUCT_ID);
        jdbc.update("INSERT INTO replenishment_policies (team_id, sku_id, warehouse_id, lead_time_days, "
                        + "is_enabled, created_by, updated_by, version) VALUES (?, ?, ?, 7, 1, ?, ?, 0)",
                TEAM_ID, SKU_ID, WAREHOUSE_MAIN, OPERATOR_ID, OPERATOR_ID);
        policyId = jdbc.queryForObject("SELECT id FROM replenishment_policies WHERE sku_id = ?",
                Long.class, SKU_ID);

        TeamContext.set(new TeamContext.Snapshot(TEAM_ID, List.of("OPERATOR"), OPERATOR_ID, "operator01"));
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    // =====================================================================
    // E1/E2：两次陈旧读 —— version 0→1，第二次必须被拒且不覆盖
    // =====================================================================

    @Test
    @DisplayName("R62-E1/E2 补货策略：A 先写成功（version 0→1），B 用陈旧 version 写 ⇒ 0 行且不覆盖")
    void policyStaleWriteIsRejected() {
        ReplenishmentPolicyEntity a = policyMapper.selectById(policyId);
        ReplenishmentPolicyEntity b = policyMapper.selectById(policyId);
        assertEquals(0, a.getVersion(), "前置：A 读到 version=0");
        assertEquals(0, b.getVersion(), "前置：B 读到 version=0（模拟并发读）");
        System.out.println("[R62-EVIDENCE] policy before   : " + policySnapshot());

        a.setLeadTimeDays(11);
        int aRows = policyMapper.updateById(a);
        System.out.println("[R62-EVIDENCE] policy A update : rows=" + aRows + " " + policySnapshot());
        assertEquals(1, aRows, "A 的 CAS 必须命中 1 行");
        assertEquals(1, versionOfPolicy(), "★ 最小可证伪断言：version 0 → 1");

        b.setLeadTimeDays(99);
        int bRows = policyMapper.updateById(b);
        System.out.println("[R62-EVIDENCE] policy B stale  : rows=" + bRows + " " + policySnapshot());
        assertEquals(0, bRows, "B 的陈旧 version 必须影响 0 行");

        assertEquals(11, leadTimeOfPolicy(), "B 的更新不得覆盖 A 写入的值");
        assertEquals(1, versionOfPolicy(), "B 失败后 version 仍为 1");
    }

    @Test
    @DisplayName("R62-E1/E2 catalog（products）：手写 CAS 路径同样 0→1，陈旧写被拒且不覆盖")
    void productStaleWriteIsRejected() {
        ProductEntity a = productMapper.selectById(PRODUCT_ID);
        ProductEntity b = productMapper.selectById(PRODUCT_ID);
        assertEquals(0, a.getVersion());
        assertEquals(0, b.getVersion());
        System.out.println("[R62-EVIDENCE] product before  : " + productSnapshot());

        a.setName("name-A");
        int aRows = productMapper.updateById(a);
        System.out.println("[R62-EVIDENCE] product A update: rows=" + aRows + " " + productSnapshot());
        assertEquals(1, aRows);
        assertEquals(1, versionOfProduct());

        b.setName("name-B");
        int bRows = productMapper.updateById(b);
        System.out.println("[R62-EVIDENCE] product B stale : rows=" + bRows + " " + productSnapshot());
        assertEquals(0, bRows);

        assertEquals("name-A", nameOfProduct());
        assertEquals(1, versionOfProduct());
    }

    // =====================================================================
    // E1 服务层：确定性陈旧读 ⇒ COM-0015（不依赖线程调度）
    // =====================================================================

    @Test
    @DisplayName("R62-E1 服务层（补货策略 upsert）：陈旧读 ⇒ COM-0015，且不覆盖他人写入")
    void policyServiceRejectsStaleUpdate() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        BizException ex = tx.execute(status -> {
            // ① 外层事务先读，固定 REPEATABLE READ 快照（version=0）
            assertEquals(0, versionOfPolicy());
            // ② 另一连接提交，把版本推进到 1（模拟并发者先落库）
            bumpPolicyVersionOutsideTransaction(5);
            // ③ 服务层在同一事务里仍看到 version=0，CAS 必然 0 行 ⇒ COM-0015
            PolicyRequest req = new PolicyRequest();
            req.setSkuId(SKU_ID);
            req.setWarehouseId(WAREHOUSE_MAIN);
            req.setLeadTimeDays(99);
            BizException thrown = assertThrows(BizException.class, () -> policyService.upsert(req));
            status.setRollbackOnly();
            return thrown;
        });

        assertNotNull(ex);
        assertEquals(ErrorCodes.COM_0015, ex.getErrorCode(), "必须是 409 COM-0015");
        assertEquals("补货策略已被他人修改，请刷新后重试", ex.getMessage());
        assertEquals(5, leadTimeOfPolicy(), "失败方不得覆盖胜者写入的值");
        assertEquals(1, versionOfPolicy());
        System.out.println("[R62-EVIDENCE] policy svc     : code=" + ex.getErrorCode() + " " + policySnapshot());
    }

    @Test
    @DisplayName("R62-E1 服务层（catalog 商品）：陈旧读 ⇒ COM_0015，且不覆盖他人写入")
    void catalogServiceRejectsStaleUpdate() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        BizException ex = tx.execute(status -> {
            assertEquals(0, versionOfProduct());
            bumpProductVersionOutsideTransaction("name-winner");
            ObjectNode body = json.createObjectNode();
            body.put("name", "name-stale");
            BizException thrown = assertThrows(BizException.class,
                    () -> catalogWriteService.upsertProduct(PRODUCT_ID, body));
            status.setRollbackOnly();
            return thrown;
        });

        assertNotNull(ex);
        assertEquals(ErrorCodes.COM_0015, ex.getErrorCode());
        assertEquals("该数据已被他人修改，请刷新后重试", ex.getMessage());
        assertEquals("name-winner", nameOfProduct());
        assertEquals(1, versionOfProduct());
        System.out.println("[R62-EVIDENCE] product svc    : code=" + ex.getErrorCode() + " " + productSnapshot());
    }

    // =====================================================================
    // E3：clearOtherDefaults 豁免（批量非 CAS）
    // =====================================================================

    @Test
    @DisplayName("R62-E3 clearOtherDefaults：批量清默认仓仍生效，且不因缺 version 条件失败")
    void clearOtherDefaultsStaysBatch() {
        jdbc.update("UPDATE warehouses SET is_default = 1 WHERE id IN (?, ?)", WAREHOUSE_MAIN, WAREHOUSE_SECOND);

        ObjectNode body = json.createObjectNode();
        body.put("name", "主仓");
        body.put("is_default", true);
        catalogWriteService.upsertWarehouse(WAREHOUSE_MAIN, body);

        assertEquals(1, jdbc.queryForObject("SELECT is_default FROM warehouses WHERE id = ?",
                Integer.class, WAREHOUSE_MAIN));
        assertEquals(0, jdbc.queryForObject("SELECT is_default FROM warehouses WHERE id = ?",
                Integer.class, WAREHOUSE_SECOND), "其它行的默认标记必须被批量清 0");
        assertEquals(0, jdbc.queryForObject("SELECT version FROM warehouses WHERE id = ?",
                Integer.class, WAREHOUSE_SECOND), "★ 豁免：被清行的 version 不变（非 CAS 批量语义）");
        assertEquals(1, jdbc.queryForObject("SELECT version FROM warehouses WHERE id = ?",
                Integer.class, WAREHOUSE_MAIN), "目标行仍走 CAS（0 → 1）");
    }

    // =====================================================================
    // E/V5：HTTP 409 映射（真实安全链 + 全局异常处理器）
    // =====================================================================

    @Test
    @DisplayName("R62-V5 COM-0015 ⇒ HTTP 409 + code（含 OptimisticLockingFailureException 兜底）")
    void com0015MapsTo409() throws Exception {
        assertEquals(409, ErrorCodeHttpStatus.resolve(ErrorCodes.COM_0015), "错误码 → HTTP 映射");

        String token = loginAccessToken();

        ResponseEntity<String> biz = getWithToken("/api/v1/test/r62/com0015", token);
        assertEquals(HttpStatus.CONFLICT, biz.getStatusCode(), biz.getBody());
        assertEquals("COM-0015", json.readTree(biz.getBody()).get("code").asText());
        assertEquals("该数据已被他人修改，请刷新后重试",
                json.readTree(biz.getBody()).get("message").asText());
        System.out.println("[R62-EVIDENCE] HTTP biz     : " + biz.getStatusCode().value() + " " + biz.getBody());

        ResponseEntity<String> spring = getWithToken("/api/v1/test/r62/optimistic", token);
        assertEquals(HttpStatus.CONFLICT, spring.getStatusCode(), spring.getBody());
        assertEquals("COM-0015", json.readTree(spring.getBody()).get("code").asText());
        System.out.println("[R62-EVIDENCE] HTTP spring  : " + spring.getStatusCode().value()
                + " " + spring.getBody());
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 在<b>独立连接</b>上推进版本并提交（模拟并发者），与外层事务的快照互不影响。 */
    private void bumpPolicyVersionOutsideTransaction(int leadTimeDays) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE replenishment_policies SET lead_time_days = ?, version = version + 1 WHERE id = ?")) {
            conn.setAutoCommit(true);
            ps.setInt(1, leadTimeDays);
            ps.setLong(2, policyId);
            assertEquals(1, ps.executeUpdate(), "外部更新必须命中 1 行");
        } catch (Exception ex) {
            throw new IllegalStateException("外部连接更新失败", ex);
        }
    }

    private void bumpProductVersionOutsideTransaction(String name) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE products SET name = ?, version = version + 1 WHERE id = ?")) {
            conn.setAutoCommit(true);
            ps.setString(1, name);
            ps.setLong(2, PRODUCT_ID);
            assertEquals(1, ps.executeUpdate(), "外部更新必须命中 1 行");
        } catch (Exception ex) {
            throw new IllegalStateException("外部连接更新失败", ex);
        }
    }

    private int versionOfPolicy() {
        return jdbc.queryForObject("SELECT version FROM replenishment_policies WHERE id = ?",
                Integer.class, policyId);
    }

    /** V4 取证：一行式快照（{@code SELECT id, version, ...}）。 */
    private String policySnapshot() {
        return jdbc.queryForObject("SELECT CONCAT('id=', id, ' version=', version, "
                        + "' lead_time_days=', lead_time_days) FROM replenishment_policies WHERE id = ?",
                String.class, policyId);
    }

    /** V4 取证：一行式快照（{@code SELECT id, version, name}）。 */
    private String productSnapshot() {
        return jdbc.queryForObject("SELECT CONCAT('id=', id, ' version=', version, ' name=', name) "
                + "FROM products WHERE id = ?", String.class, PRODUCT_ID);
    }

    private int leadTimeOfPolicy() {
        return jdbc.queryForObject("SELECT lead_time_days FROM replenishment_policies WHERE id = ?",
                Integer.class, policyId);
    }

    private int versionOfProduct() {
        return jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Integer.class, PRODUCT_ID);
    }

    private String nameOfProduct() {
        return jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, PRODUCT_ID);
    }

    private ResponseEntity<String> getWithToken(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String loginAccessToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"operator01\",\"password\":\"" + RAW_PASSWORD + "\"}", headers),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + response.getBody());
        return json.readTree(response.getBody()).get("data").get("accessToken").asText();
    }
}
