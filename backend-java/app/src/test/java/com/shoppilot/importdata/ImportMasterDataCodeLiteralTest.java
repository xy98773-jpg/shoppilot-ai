package com.shoppilot.importdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.TestCredentials;
import com.shoppilot.app.ShopPilotApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>M3-B10.9（R86）：主数据导入不得改写库内编码字面量</b>端到端集成测试
 * ——真栈（Spring 上下文 + Testcontainers MySQL 8.0.36 + 真实 HTTP）。
 *
 * <p><b>缺陷背景</b>（评审真栈实测 {@code tools/rb-b108}）：四类主数据全部同构。SKU 侧证据链：
 * <pre>
 *   1) GET /api/v1/skus/9002                       → "sku_code":"SKU-A"
 *   2) 导入单行 sku-a（库里 SKU-A）→ validate error_rows=0、confirm COMPLETED
 *   3) GET /api/v1/skus/9002                       → "sku_code":"sku-a"   ← 对外可见
 *   4) audit_logs.biz_no                           → "sku-a"             ← 无法还原改写前编码
 *   5) 再导入 SKU-A                                → 库内改回大写         ← 最后写入者赢
 * </pre>
 * 行数不变、id 不变、无 {@code CAT-3002}、无重复行 ⇒ 静默篡改。</p>
 *
 * <p><b>根因链</b>：{@code CatalogWriteService#doUpsertSkuByCode} / {@code doUpsertWarehouseByCode} /
 * {@code doUpsertSupplierByCode} / {@code doUpsertProductByCode}
 * → {@code selectIdByCode} 的 {@code @Select} 走 {@code utf8mb4_0900_ai_ci}（ci 命中既有行）
 * → {@code view = updateSku/updateWarehouse/...}，而该 update 方法内的
 * {@code setString(w, body, "<code>", "<code>")} <b>无条件覆盖 code 列</b>
 * ⇒ 判 UPDATE 不判 INSERT，故不报错、不新增行，改为静默篡改。</p>
 *
 * <p><b>口径</b>（{@code IMPORT_WORKFLOW §7.1–§7.4}，已由项目负责人裁定并写入 SSOT）：
 * by-code upsert 的 {@code code}/{@code product_code}/{@code sku_code} 是<b>定位键、不可更新</b>，
 * 命中既有行后<b>保留库内既有字面量</b>；判定仍按 {@code §8.6} 末行（编码大小写不敏感等价）命中
 * ——<b>只是命中后不改写</b>。create 分支行为不变（{@code code} 照写）。</p>
 *
 * <p><b>REST 侧的裁定</b>：{@code API_CONTRACT §10.3} 未禁止 REST 改 {@code code}，
 * {@code DATABASE_DESIGN §5.4–§5.7} 四张主数据表的「更新规则」也<b>未</b>标 {@code 不可变}
 * （对比同文件 {@code teams.code} §5.2 与 {@code skus.product_id} §5.7 都明确标了），
 * 且 code 有 PATCH 接口且原实现允许改 ⇒ 判定 <b>REST 允许改 code</b>，
 * 实现上把 {@code set code} 从共用的 update 内核移出、由调用方显式传 {@code writableCode}，
 * <b>不做</b>口径收紧（见第 h 项回归）。</p>
 *
 * <p><b>本类覆盖</b>（任务书【4】a–h）：
 * <ol>
 *   <li><b>R86-a</b> SKU：库里 {@code SKU-A}，导入单行 {@code sku-a} → {@code COMPLETED}，
 *       库内 code 仍为 {@code SKU-A}（不变）；</li>
 *   <li><b>R86-b</b> WAREHOUSE：库里 {@code WH-MAIN}，导入 {@code wh-main} → code 仍为 {@code WH-MAIN}；</li>
 *   <li><b>R86-c</b> SUPPLIER：库里 {@code SUP-001}，导入 {@code sup-001} → code 仍为 {@code SUP-001}；</li>
 *   <li><b>R86-d</b> PRODUCT：库里 {@code P-BAG-001}，导入 {@code p-bag-001} → code 仍为 {@code P-BAG-001}；</li>
 *   <li><b>R86-e</b> 回归：库里无该编码时，导入<b>小写新编码</b>必须<b>真的新建</b>且写入小写原样
 *       （不得因折叠而统一变大写，也不得因命中失败而报 {@code CAT-3002}）；</li>
 *   <li><b>R86-f</b> 回归：完全同大小写的既有编码导入仍走命中更新，且 {@code name} 等可变字段确实被更新；</li>
 *   <li><b>R86-g</b> 审计：命中分支的 {@code biz_no == } 库内既有写法（不是文件里的写法）；</li>
 *   <li><b>R86-h</b> REST 回归：REST PATCH 改 code 的能力<b>未</b>因本批而失效（四类各一次）。</li>
 * </ol>
 *
 * <p>★ 第 1–4 项每条都<b>同时</b>断言两件事：① 库内 {@code code} 字面量；②
 * {@code GET /api/v1/skus|warehouses|suppliers|products/{id}} 的对外返回（缺陷当时正是从这条路径可见的）。</p>
 *
 * <p>★ 探针有效性已做<b>负向验证</b>：把四类 by-code 命中分支的 {@code writableCode} 临时改回
 * {@code true} 后，a–d 与 g 立即失败（现象与缺陷报告一字不差：库内被改成小写、{@code biz_no} 被改写）。
 * 见 {@code tools/b10.9/README.md} 第四节。</p>
 *
 * <p>★ <b>批量出口（Pass 1）的等价性</b>：{@code R86-i} 专门验证
 * {@code preloadXxxIdsByCode} 的预取表键与查找键都折叠后，一批里「大写命中 + 小写命中」
 * 两行走的是<b>同一个批量出口</b>且都命中更新（折叠前：小写那行落空 ⇒ 走 create 撞
 * {@code uk_*_team_code} ⇒ Pass 1 整批回滚退化到 Pass 2 逐行重放）。</p>
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportMasterDataCodeLiteralTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final String STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("shoppilot-import-code-literal-test").toString();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final long TEAM_ID = 7L;
    private static final long OWNER_ID = 1004L;
    private static final long OPERATOR_ID = 1001L;

    private static final long PRODUCT_ID = 9001L;
    private static final long SKU_ID = 9002L;
    private static final long SUPPLIER_ID = 9003L;
    private static final long WAREHOUSE_ID = 9004L;

    private static final String OPERATOR = "operator01";

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
        registry.add("app.import.storage-dir", () -> STORAGE_DIR);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM import_row_errors");
        jdbc.execute("DELETE FROM import_batches");
        jdbc.execute("DELETE FROM audit_logs");
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM sales_daily");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM team_members");
        jdbc.execute("DELETE FROM users");
        jdbc.execute("DELETE FROM teams");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM suppliers");
        jdbc.execute("DELETE FROM warehouses");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) "
                + "VALUES (?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID);
        insertUser(OWNER_ID, "owner01");
        insertUser(OPERATOR_ID, OPERATOR);
        jdbc.update("INSERT INTO team_members (team_id, user_id, role, status) "
                        + "VALUES (?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'OPERATOR', 'ACTIVE')",
                TEAM_ID, OWNER_ID, TEAM_ID, OPERATOR_ID);

        jdbc.update("INSERT INTO suppliers (id, team_id, code, name, status) "
                + "VALUES (?, ?, 'SUP-X', '星海供应链', 'ACTIVE')", SUPPLIER_ID, TEAM_ID);
        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) "
                + "VALUES (?, ?, 'P-BAG-001', '轻旅行双肩包', 'ACTIVE')", PRODUCT_ID, TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) "
                + "VALUES (?, ?, ?, 'SKU-A', '阿尔法', 'ACTIVE')", SKU_ID, TEAM_ID, PRODUCT_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) "
                + "VALUES (?, ?, 'WH-MAIN', '主仓', 'ACTIVE')", WAREHOUSE_ID, TEAM_ID);
    }

    // =====================================================================
    // R86-a：SKU 命中不改写库内字面量
    // =====================================================================

    @Test
    @DisplayName("R86-a SKU：库里 SKU-A，导入 sku-a → COMPLETED，库内 code 与 GET 均仍为 SKU-A")
    void skuImportDoesNotRewriteCodeLiteral() throws Exception {
        assertEquals("SKU-A", skuCodeInDb(SKU_ID), "前置：库内是 SKU-A");
        assertEquals("SKU-A", getSku(SKU_ID).get("sku_code").asText(), "前置：对外也是 SKU-A");

        long batchId = validatedBatch("sku-lower.csv", csv("sku_code,product_code,name\n"
                + "sku-a,P-BAG-001,改后的名字\n"), "SKU", 0);
        ResponseEntity<String> resp = confirm(batchId, "key-r86-a", Map.of());

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText(),
                "命中既有编码不得报错（R86 口径是「命中并保留库内写法」，不是新增 CAT-3002）：" + resp.getBody());
        assertEquals(1, data.get("success_rows").asInt());
        assertEquals(0, data.get("failed_rows").asInt());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors WHERE batch_id = ?",
                Integer.class, batchId), "一行错误都不该有");

        // ① 库内字面量
        assertEquals("SKU-A", skuCodeInDb(SKU_ID), "R86：命中分支不得把库内 code 改写成文件里的写法");
        assertEquals(1, count("skus"), "行数不变（是 UPDATE，不是新增）");
        // ② 对外返回
        assertEquals("SKU-A", getSku(SKU_ID).get("sku_code").asText(), "对外可见字段同样不得被改写");
        // 命中更新本身仍然生效（可变字段被更新）
        assertEquals("改后的名字", jdbc.queryForObject("SELECT name FROM skus WHERE id = ?",
                String.class, SKU_ID), "定位键不可更新 ≠ 整行不更新：name 等可变字段必须照常更新");
    }

    // =====================================================================
    // R86-b：WAREHOUSE
    // =====================================================================

    @Test
    @DisplayName("R86-b WAREHOUSE：库里 WH-MAIN，导入 wh-main → code 仍为 WH-MAIN")
    void warehouseImportDoesNotRewriteCodeLiteral() throws Exception {
        assertEquals("WH-MAIN", warehouseCodeInDb(WAREHOUSE_ID), "前置：库内是 WH-MAIN");

        long batchId = validatedBatch("wh-lower.csv", csv("code,name\n"
                + "wh-main,改名后的主仓\n"), "WAREHOUSE", 0);
        ResponseEntity<String> resp = confirm(batchId, "key-r86-b", Map.of());

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText(), resp.getBody());

        assertEquals("WH-MAIN", warehouseCodeInDb(WAREHOUSE_ID), "R86：仓库 code 不得被改写");
        assertEquals(1, count("warehouses"), "行数不变");
        assertEquals("WH-MAIN", getJson("/api/v1/warehouses/" + WAREHOUSE_ID).get("code").asText(),
                "对外返回不得被改写");
        assertEquals("改名后的主仓", jdbc.queryForObject("SELECT name FROM warehouses WHERE id = ?",
                String.class, WAREHOUSE_ID), "可变字段照常更新");
    }

    // =====================================================================
    // R86-c：SUPPLIER
    // =====================================================================

    @Test
    @DisplayName("R86-c SUPPLIER：库里 SUP-X，导入 sup-x → code 仍为 SUP-X")
    void supplierImportDoesNotRewriteCodeLiteral() throws Exception {
        assertEquals("SUP-X", supplierCodeInDb(SUPPLIER_ID), "前置：库内是 SUP-X");

        long batchId = validatedBatch("sup-lower.csv", csv("code,name\n"
                + "sup-x,改名后的供应商\n"), "SUPPLIER", 0);
        ResponseEntity<String> resp = confirm(batchId, "key-r86-c", Map.of());

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText(), resp.getBody());

        assertEquals("SUP-X", supplierCodeInDb(SUPPLIER_ID), "R86：供应商 code 不得被改写");
        assertEquals(1, count("suppliers"), "行数不变");
        assertEquals("SUP-X", getJson("/api/v1/suppliers/" + SUPPLIER_ID).get("code").asText(),
                "对外返回不得被改写");
        assertEquals("改名后的供应商", jdbc.queryForObject("SELECT name FROM suppliers WHERE id = ?",
                String.class, SUPPLIER_ID), "可变字段照常更新");
    }

    // =====================================================================
    // R86-d：PRODUCT（§7.1 的 product_code 已是同样语义，实现一并修）
    // =====================================================================

    @Test
    @DisplayName("R86-d PRODUCT：库里 P-BAG-001，导入 p-bag-001 → code 仍为 P-BAG-001")
    void productImportDoesNotRewriteCodeLiteral() throws Exception {
        assertEquals("P-BAG-001", productCodeInDb(PRODUCT_ID), "前置：库内是 P-BAG-001");

        long batchId = validatedBatch("prod-lower.csv", csv("product_code,name\n"
                + "p-bag-001,改名后的商品\n"), "PRODUCT", 0);
        ResponseEntity<String> resp = confirm(batchId, "key-r86-d", Map.of());

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText(), resp.getBody());

        assertEquals("P-BAG-001", productCodeInDb(PRODUCT_ID),
                "R86：§7.1 的 product_code 同样是定位键，实现必须一并修（只改三类不算完成）");
        assertEquals(1, count("products"), "行数不变");
        assertEquals("P-BAG-001", getJson("/api/v1/products/" + PRODUCT_ID).get("product_code").asText(),
                "对外返回不得被改写");
        assertEquals("改名后的商品", jdbc.queryForObject("SELECT name FROM products WHERE id = ?",
                String.class, PRODUCT_ID), "可变字段照常更新");
    }

    // =====================================================================
    // R86-e：库里没有该编码 → 必须真的新建，且写入小写原样
    // =====================================================================

    @Test
    @DisplayName("R86-e 回归：库里无该编码时导入小写新编码 → 真的新建且写入小写原样（不报 CAT-3002）")
    void lowercaseNewCodeIsActuallyCreated() throws Exception {
        // 库里只有 SKU-A / WH-MAIN / SUP-X / P-BAG-001；下面全部是「不存在」的小写新编码
        long skuBatch = validatedBatch("sku-new-lower.csv", csv("sku_code,product_code,name\n"
                + "sku-brand-new,p-bag-001,全新SKU\n"), "SKU", 0);
        ResponseEntity<String> skuResp = confirm(skuBatch, "key-r86-e-sku", Map.of());
        assertEquals(HttpStatus.OK, skuResp.getStatusCode(), skuResp.getBody());
        assertEquals("COMPLETED", data(skuResp).get("status").asText(),
                "库里没有该编码 ⇒ 必须真的新建，不得报 CAT-3002：" + skuResp.getBody());
        assertEquals(2, count("skus"), "应新增 1 行");
        assertEquals("sku-brand-new", jdbc.queryForObject("SELECT sku_code FROM skus WHERE id <> ?",
                String.class, SKU_ID),
                "★ 折叠只用于「判定」，不得用于「写入」：库里必须原样保存文件里的小写写法");

        long whBatch = validatedBatch("wh-new-lower.csv", csv("code,name\n"
                + "wh-branch,分仓\n"), "WAREHOUSE", 0);
        ResponseEntity<String> whResp = confirm(whBatch, "key-r86-e-wh", Map.of());
        assertEquals("COMPLETED", data(whResp).get("status").asText(), whResp.getBody());
        assertEquals(2, count("warehouses"));
        assertEquals("wh-branch", jdbc.queryForObject("SELECT code FROM warehouses WHERE id <> ?",
                String.class, WAREHOUSE_ID), "仓库同理：小写原样写入");

        long supBatch = validatedBatch("sup-new-lower.csv", csv("code,name\n"
                + "sup-new,新供应商\n"), "SUPPLIER", 0);
        ResponseEntity<String> supResp = confirm(supBatch, "key-r86-e-sup", Map.of());
        assertEquals("COMPLETED", data(supResp).get("status").asText(), supResp.getBody());
        assertEquals(2, count("suppliers"));
        assertEquals("sup-new", jdbc.queryForObject("SELECT code FROM suppliers WHERE id <> ?",
                String.class, SUPPLIER_ID), "供应商同理：小写原样写入");

        long prodBatch = validatedBatch("prod-new-lower.csv", csv("product_code,name\n"
                + "p-tote-001,手提袋\n"), "PRODUCT", 0);
        ResponseEntity<String> prodResp = confirm(prodBatch, "key-r86-e-prod", Map.of());
        assertEquals("COMPLETED", data(prodResp).get("status").asText(), prodResp.getBody());
        assertEquals(2, count("products"));
        assertEquals("p-tote-001", jdbc.queryForObject("SELECT product_code FROM products WHERE id <> ?",
                String.class, PRODUCT_ID), "商品同理：小写原样写入");
    }

    // =====================================================================
    // R86-f：完全同大小写仍走命中更新，且可变字段确实被更新
    // =====================================================================

    @Test
    @DisplayName("R86-f 回归：完全同大小写既有编码导入仍走命中更新，可变字段确实被更新")
    void exactCaseExistingCodeStillUpserts() throws Exception {
        long batchId = validatedBatch("sku-exact.csv", csv("sku_code,product_code,barcode\n"
                + "SKU-A,P-BAG-001,6901234567890\n"), "SKU", 0);
        ResponseEntity<String> resp = confirm(batchId, "key-r86-f", Map.of());

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        assertEquals("COMPLETED", data(resp).get("status").asText(), resp.getBody());
        assertEquals(1, count("skus"), "行数不变：仍是命中更新而非新增");
        assertEquals("SKU-A", skuCodeInDb(SKU_ID));
        assertEquals("6901234567890", jdbc.queryForObject("SELECT barcode FROM skus WHERE id = ?",
                String.class, SKU_ID), "可变字段确实被更新（R86 不得把命中分支变成「什么都不改」）");
    }

    // =====================================================================
    // R86-g：命中分支的审计 biz_no 必须是库内既有写法
    // =====================================================================

    @Test
    @DisplayName("R86-g 审计：命中分支 biz_no == 库内既有写法（不是文件里的写法）")
    void importUpdateAuditUsesInDbCodeLiteral() throws Exception {
        long batchId = validatedBatch("sku-audit.csv", csv("sku_code,product_code,name\n"
                + "sku-a,P-BAG-001,审计用名字\n"), "SKU", 0);
        assertEquals(HttpStatus.OK, confirm(batchId, "key-r86-g", Map.of()).getStatusCode());

        List<Map<String, Object>> audits = jdbc.queryForList("SELECT biz_id, biz_no, change_summary FROM audit_logs "
                + "WHERE action = 'MASTER_DATA_STATUS_CHANGE' AND biz_type = 'SKU' AND biz_id = ?",
                SKU_ID);
        assertEquals(1, audits.size(), "命中更新须写且只写一条导入更新审计");
        assertEquals("SKU-A", audits.get(0).get("biz_no"),
                "★ R86-c：biz_no 必须是【库内既有写法】——记文件里的小写会让审计无法还原改写前的编码");
        assertNotEquals("sku-a", audits.get(0).get("biz_no"), "缺陷当时审计里就是这个被改写的值");
        assertEquals("导入更新 SKU", audits.get(0).get("change_summary"));
        assertEquals(SKU_ID, ((Number) audits.get(0).get("biz_id")).longValue());

        // 三类同理：审计 biz_no 一律取库内写法
        long whBatch = validatedBatch("wh-audit.csv", csv("code,name\nwh-main,审计仓库\n"), "WAREHOUSE", 0);
        assertEquals(HttpStatus.OK, confirm(whBatch, "key-r86-g-wh", Map.of()).getStatusCode());
        assertEquals("WH-MAIN", masterDataBizNo("WAREHOUSE", WAREHOUSE_ID), "仓库审计 biz_no 不得被改写");

        long supBatch = validatedBatch("sup-audit.csv", csv("code,name\nsup-x,审计供应商\n"), "SUPPLIER", 0);
        assertEquals(HttpStatus.OK, confirm(supBatch, "key-r86-g-sup", Map.of()).getStatusCode());
        assertEquals("SUP-X", masterDataBizNo("SUPPLIER", SUPPLIER_ID), "供应商审计 biz_no 不得被改写");

        long prodBatch = validatedBatch("prod-audit.csv", csv("product_code,name\np-bag-001,审计商品\n"),
                "PRODUCT", 0);
        assertEquals(HttpStatus.OK, confirm(prodBatch, "key-r86-g-prod", Map.of()).getStatusCode());
        assertEquals("P-BAG-001", masterDataBizNo("PRODUCT", PRODUCT_ID), "商品审计 biz_no 不得被改写");
    }

    // =====================================================================
    // R86-h：REST 改 code 的能力不得因本批而失效
    // =====================================================================

    @Test
    @DisplayName("R86-h REST 回归：PATCH 改 code 仍然生效（四类全测），PUT 创建亦不受影响")
    void restPatchCanStillChangeCode() throws Exception {
        // 裁定依据见类注释：SSOT 未禁止 REST 改 code ⇒ 该能力必须保留
        assertEquals(HttpStatus.OK, patch("/api/v1/skus/" + SKU_ID,
                "{\"sku_code\":\"SKU-A-RENAMED\",\"name\":\"REST改名\"}").getStatusCode());
        assertEquals("SKU-A-RENAMED", skuCodeInDb(SKU_ID), "REST PATCH 改 sku_code 必须仍然生效");
        assertEquals("SKU-A-RENAMED", getSku(SKU_ID).get("sku_code").asText());

        assertEquals(HttpStatus.OK, patch("/api/v1/warehouses/" + WAREHOUSE_ID,
                "{\"code\":\"WH-RENAMED\"}").getStatusCode());
        assertEquals("WH-RENAMED", warehouseCodeInDb(WAREHOUSE_ID), "REST PATCH 改 code 必须仍然生效");

        assertEquals(HttpStatus.OK, patch("/api/v1/suppliers/" + SUPPLIER_ID,
                "{\"code\":\"SUP-RENAMED\"}").getStatusCode());
        assertEquals("SUP-RENAMED", supplierCodeInDb(SUPPLIER_ID), "REST PATCH 改 code 必须仍然生效");

        assertEquals(HttpStatus.OK, patch("/api/v1/products/" + PRODUCT_ID,
                "{\"product_code\":\"P-RENAMED\"}").getStatusCode());
        assertEquals("P-RENAMED", productCodeInDb(PRODUCT_ID), "REST PATCH 改 product_code 必须仍然生效");

        // create 分支不受影响：REST POST 建的行 code 就是请求里的写法
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token());
        ResponseEntity<String> created = rest.exchange("/api/v1/products", HttpMethod.POST,
                new HttpEntity<>("{\"product_code\":\"p-post-001\",\"name\":\"REST建的\"}", headers),
                String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("p-post-001", jdbc.queryForObject("SELECT product_code FROM products WHERE id <> ?",
                String.class, PRODUCT_ID), "REST POST 小写编码须原样落库（create 分支行为不变）");
    }

    // =====================================================================
    // R86-i：批量出口（Pass 1）的命中判定与单行出口严格等价
    // =====================================================================

    @Test
    @DisplayName("R86-i 批量出口：一批内「大写命中 + 小写命中」两行都命中更新，不退化为整批回滚")
    void batchPathHitDetectionIsCaseInsensitiveToo() throws Exception {
        // 折叠前：preload 的 Map 键是【库里写法】而查找用【文件写法】⇒ 小写那行落空
        //        ⇒ 被判「不存在」⇒ 走 create 撞 uk_sku_team_code ⇒ Pass 1 整批回滚 → Pass 2 逐行重放。
        //        本项通过「两行都被更新 + 行数不变 + 无行级错误」证明批量出口确实命中了。
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) "
                + "VALUES (9005, ?, ?, 'SKU-B', '贝塔', 'ACTIVE')", TEAM_ID, PRODUCT_ID);        byte[] csv = csv("sku_code,product_code,name\n"
                + "SKU-A,P-BAG-001,大写命中\n"
                + "sku-b,P-BAG-001,小写命中\n");
        long batchId = validatedBatch("sku-batch-case.csv", csv, "SKU", 0);
        ResponseEntity<String> resp = confirm(batchId, "key-r86-i", Map.of());

        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
        JsonNode data = data(resp);
        assertEquals("COMPLETED", data.get("status").asText(), resp.getBody());
        assertEquals(2, data.get("success_rows").asInt(), "两行都应成功（命中更新，不是新增）");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM import_row_errors WHERE batch_id = ?",
                Integer.class, batchId), "小写那行不得因预取表落空而报 CAT-3002");
        assertEquals(2, count("skus"), "行数不变：两行都是 UPDATE");
        assertEquals("SKU-A", skuCodeInDb(SKU_ID));
        assertEquals("大写命中", jdbc.queryForObject("SELECT name FROM skus WHERE id = ?", String.class, SKU_ID));
        assertEquals("SKU-B", skuCodeInDb(9005L), "小写命中的那行也必须保留库内写法");
        assertEquals("小写命中", jdbc.queryForObject("SELECT name FROM skus WHERE id = ?", String.class, 9005L),
                "★ 证明这行走的是「命中更新」而不是「新增后又失败」");
    }

    // =====================================================================
    // 辅助：构造「已验证（VALIDATED）」的批次
    // =====================================================================

    private long validatedBatch(String fileName, byte[] content, String bizType, int expectedErrorRows)
            throws Exception {
        long batchId = uploadOk(fileName, content, bizType);
        preview(batchId);
        mapping(batchId, suggestedMapping(batchId));
        ResponseEntity<String> resp = validate(batchId);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "校验应成功：" + resp.getBody());
        assertEquals("VALIDATED", data(resp).get("status").asText(), resp.getBody());
        assertEquals(expectedErrorRows, data(resp).get("error_rows").asInt(), "校验错误行数：" + resp.getBody());
        return batchId;
    }

    // =====================================================================
    // 辅助：HTTP
    // =====================================================================

    private ResponseEntity<String> upload(String fileName, byte[] content, String bizType) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedResource(content, fileName));
        body.add("bizType", bizType);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token());
        return rest.exchange("/api/v1/import/batches", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private long uploadOk(String fileName, byte[] content, String bizType) throws Exception {
        ResponseEntity<String> response = upload(fileName, content, bizType);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return data(response).get("batch_id").asLong();
    }

    private ResponseEntity<String> preview(long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token());
        return rest.exchange("/api/v1/import/batches/" + batchId + "/preview", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private Map<String, String> suggestedMapping(long batchId) throws Exception {
        JsonNode suggested = data(preview(batchId)).get("suggested_mapping");
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.convertValue(suggested, Map.class);
        return map;
    }

    private ResponseEntity<String> mapping(long batchId, Map<String, String> fieldMapping) throws Exception {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token());
        Map<String, Object> body = Map.of("field_mapping_json", fieldMapping, "parse_options_json", Map.of());
        return rest.exchange("/api/v1/import/batches/" + batchId + "/mapping", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> validate(long batchId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token());
        return rest.exchange("/api/v1/import/batches/" + batchId + "/validate", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> confirm(long batchId, String idempotencyKey, Map<String, Object> body)
            throws Exception {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token());
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange("/api/v1/import/batches/" + batchId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> patch(String path, String jsonBody) throws Exception {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token());
        return rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(jsonBody, headers), String.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode getSku(long id) throws Exception {
        return getJson("/api/v1/skus/" + id);
    }

    private JsonNode getJson(String path) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token());
        ResponseEntity<String> resp = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), path + " → " + resp.getBody());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("OK", body.get("code").asText(), path + " → " + resp.getBody());
        return body.get("data");
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private String token() throws Exception {
        HttpHeaders headers = jsonHeaders();
        String body = "{\"username\":\"" + OPERATOR + "\",\"password\":\"" + TestCredentials.RAW_PASSWORD + "\"}";
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "登录应成功：" + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("accessToken").asText();
    }

    // =====================================================================
    // 辅助：断言
    // =====================================================================

    private String skuCodeInDb(long id) {
        return jdbc.queryForObject("SELECT sku_code FROM skus WHERE id = ?", String.class, id);
    }

    private String productCodeInDb(long id) {
        return jdbc.queryForObject("SELECT product_code FROM products WHERE id = ?", String.class, id);
    }

    private String supplierCodeInDb(long id) {
        return jdbc.queryForObject("SELECT code FROM suppliers WHERE id = ?", String.class, id);
    }

    private String warehouseCodeInDb(long id) {
        return jdbc.queryForObject("SELECT code FROM warehouses WHERE id = ?", String.class, id);
    }

    private String masterDataBizNo(String bizType, long bizId) {
        List<String> rows = jdbc.queryForList("SELECT biz_no FROM audit_logs "
                + "WHERE action = 'MASTER_DATA_STATUS_CHANGE' AND biz_type = ? AND biz_id = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, bizType, bizId);
        assertTrue(!rows.isEmpty(), bizType + " 应有导入更新审计");
        return rows.get(0);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE team_id = ?", Integer.class, TEAM_ID);
    }

    private void insertUser(long id, String username) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                id, username, username + "@example.com", TestCredentials.PASSWORD_HASH, username);
    }

    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class NamedResource extends org.springframework.core.io.ByteArrayResource {
        private final String filename;

        NamedResource(byte[] data, String filename) {
            super(data);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
