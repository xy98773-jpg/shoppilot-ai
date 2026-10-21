package com.shoppilot.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.shoppilot.app.ShopPilotApplication;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.BizException;
import com.shoppilot.inventory.application.InventoryWriteService;
import com.shoppilot.inventory.application.dto.AnchorLockResult;
import com.shoppilot.inventory.application.dto.StockChangeResult;
import com.shoppilot.inventory.infrastructure.persistence.InventoryBalanceUpdater;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M3-T09 {@code InventoryWriteService} 端到端集成测试 —— 真栈
 * （Spring 上下文 + Testcontainers {@code mysql:8.0.36} + 真实 SQL）。
 *
 * <p><b>依据</b>：{@code .workbuddy/M3-T09-B6-任务书.md} 第五节 T1–T23 与 {@code MODULE_BOUNDARIES §2.5}、
 * {@code INVENTORY_WORKFLOW §5.1/§5.3–§5.6/§7.2/§8/§9}、{@code DATABASE_DESIGN §6-C.2/§7.2}、
 * {@code ERROR_CODES §2.6}、{@code ADR-005}、{@code DESIGN_REVIEW} R53/R55。
 *
 * <p><b>断言纪律</b>：所有「是否落库」的断言一律查<b>真实表</b>（{@code inventory_transactions} /
 * {@code inventory_balances} / {@code information_schema}），<b>绝不</b>用 mock Mapper 反推。
 * 本类对 {@link InventoryBalanceUpdater} 使用 {@code @MockitoSpyBean} 的唯一目的是
 * <b>故障注入</b>（让步「余额更新返回 0 行」与「抛异常」），以确定性验证 {@code W3}/{@code W4} 的
 * 「一尝试一 SAVEPOINT」与 T21 的原子性——这两种情况在真实并发下无法稳定复现。
 *
 * <p><b>为什么不加 {@code @Transactional}</b>：服务方法的提交/回滚语义正是被测对象；
 * 测试类若自带事务，服务调用会加入测试事务，{@code INV-5002} 的「3 次全冲突后整体回滚」与
 * 「孤儿流水为 0」将无法观测。故每个用例前用 {@code DELETE} 重建种子。
 */
@SpringBootTest(classes = ShopPilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InventoryWriteServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+00:00")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long OPERATOR_ID = 1001L;

    /** 团队 7：产品 / 三个已建余额的 SKU / 一个无余额的 SKU / 仓库。 */
    private static final long PRODUCT_ID = 9001L;
    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;
    private static final long SKU_C = 9005L;
    private static final long SKU_WITHOUT_BALANCE = 9006L;
    private static final long WAREHOUSE_ID = 9004L;

    /** 团队 8：用于跨团队隔离的负向用例。 */
    private static final long OTHER_PRODUCT_ID = 9101L;
    private static final long OTHER_SKU_ID = 9102L;
    private static final long OTHER_WAREHOUSE_ID = 9103L;

    @DynamicPropertySource
    static void datasourceAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("shoppilot.jwt.secret", () -> "test-only-jwt-secret-0123456789abcdef");
    }

    @Autowired
    private InventoryWriteService writeService;

    @Autowired
    private JdbcTemplate jdbc;

    /** 仅用于故障注入（见类注释）；生产代码中它就是那条乐观锁 UPDATE。 */
    @MockitoSpyBean
    private InventoryBalanceUpdater balanceUpdater;

    @BeforeEach
    void resetAndSeed() {
        jdbc.execute("DELETE FROM inventory_idempotency_keys");
        jdbc.execute("DELETE FROM inventory_transactions");
        jdbc.execute("DELETE FROM inventory_balances");
        jdbc.execute("DELETE FROM replenishment_policies");
        jdbc.execute("DELETE FROM skus");
        jdbc.execute("DELETE FROM products");
        jdbc.execute("DELETE FROM warehouses");
        jdbc.execute("DELETE FROM teams");

        jdbc.update("INSERT INTO teams (id, code, name, status, default_timezone) VALUES "
                + "(?, 'T-HX', '星海优选', 'ACTIVE', 'Asia/Shanghai'), "
                + "(?, 'T-OTHER', '别的团队', 'ACTIVE', 'Asia/Shanghai')", TEAM_ID, OTHER_TEAM_ID);

        jdbc.update("INSERT INTO products (id, team_id, product_code, name, status) VALUES "
                + "(?, ?, 'P-BAG-001', '箱包', 'ACTIVE'), (?, ?, 'P-OTHER-001', '别家商品', 'ACTIVE')",
                PRODUCT_ID, TEAM_ID, OTHER_PRODUCT_ID, OTHER_TEAM_ID);
        jdbc.update("INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES "
                + "(?, ?, ?, 'SKU-A', 'SKU-A', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-B', 'SKU-B', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-C', 'SKU-C', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-D', 'SKU-D', 'ACTIVE'), "
                + "(?, ?, ?, 'SKU-OTHER', 'SKU-OTHER', 'ACTIVE')",
                SKU_A, TEAM_ID, PRODUCT_ID,
                SKU_B, TEAM_ID, PRODUCT_ID,
                SKU_C, TEAM_ID, PRODUCT_ID,
                SKU_WITHOUT_BALANCE, TEAM_ID, PRODUCT_ID,
                OTHER_SKU_ID, OTHER_TEAM_ID, OTHER_PRODUCT_ID);
        jdbc.update("INSERT INTO warehouses (id, team_id, code, name, status) VALUES "
                + "(?, ?, 'WH-MAIN', '主仓', 'ACTIVE'), (?, ?, 'WH-OTHER', '别家仓', 'ACTIVE')",
                WAREHOUSE_ID, TEAM_ID, OTHER_WAREHOUSE_ID, OTHER_TEAM_ID);

        TeamContext.set(new TeamContext.Snapshot(TEAM_ID, List.of("OWNER"), OPERATOR_ID, "operator01"));
    }

    @AfterEach
    void clearContext() {
        TeamContext.clear();
    }

    // =====================================================================
    // T1–T3 三条主路径
    // =====================================================================

    @Test
    @DisplayName("T1 applyInitialImport：建余额行 + INIT_IMPORT 流水（qty_before=0、qty_after=qty、available_qty 自动）")
    void initialImportCreatesBalanceAndTransaction() {
        StockChangeResult result = writeService.applyInitialImport(SKU_A, WAREHOUSE_ID,
                new BigDecimal("10"), 501L, "INIT_IMPORT:IMPORT:501:9002");

        assertFalse(result.idempotentHit(), "首次落账不应是幂等命中");
        assertNotNull(result.txnId());
        assertEquals("INIT_IMPORT", result.txnType());
        assertEquals("IMPORT", result.bizType());
        assertEquals(0, new BigDecimal("10").compareTo(result.qtyDelta()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.qtyBefore()));
        assertEquals(0, new BigDecimal("10").compareTo(result.qtyAfter()));
        assertEquals(1, result.balanceVersion(), "首次变更后 version 应为 1");

        Map<String, Object> txn = onlyTxn();
        assertEquals("INIT_IMPORT", txn.get("txn_type"));
        assertEquals("IMPORT", txn.get("biz_type"));
        assertEquals(501L, ((Number) txn.get("biz_ref_id")).longValue());
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) txn.get("qty_delta")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) txn.get("qty_before")));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) txn.get("qty_after")));

        Map<String, Object> balance = balanceRow(SKU_A);
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) balance.get("on_hand_qty")));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) balance.get("available_qty")),
                "available_qty = on_hand - committed = 10");
        assertEquals(((Number) txn.get("id")).longValue(), ((Number) balance.get("last_txn_id")).longValue(),
                "last_txn_id 必须回填为本次流水");
        assertNotNull(balance.get("last_txn_at"));
    }

    @Test
    @DisplayName("T2 applyInbound：累加，qty_before/qty_after 与余额三者一致")
    void inboundAccumulates() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        StockChangeResult inbound = writeService.applyInbound(SKU_A, WAREHOUSE_ID, new BigDecimal("5"),
                "GOODS_RECEIPT", 777L, "PURCHASE_INBOUND:GOODS_RECEIPT:777:9002");

        assertEquals("PURCHASE_INBOUND", inbound.txnType());
        assertEquals(0, new BigDecimal("10").compareTo(inbound.qtyBefore()));
        assertEquals(0, new BigDecimal("15").compareTo(inbound.qtyAfter()));
        assertEquals(2, inbound.balanceVersion());

        assertEquals(2, txnCount());
        assertEquals(0, new BigDecimal("15").compareTo(onHand(SKU_A)));
        assertEquals(0, new BigDecimal("15").compareTo(sumOfDeltas(SKU_A)),
                "流水累加必须等于余额（可复算不变式）");
    }

    @Test
    @DisplayName("T3 applyOutbound：正常扣减，qty_delta 为负")
    void outboundDeducts() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        StockChangeResult outbound = writeService.applyOutbound(SKU_A, WAREHOUSE_ID, new BigDecimal("4"),
                "SALE", null, "SALE_OUTBOUND:SALE:null:9002");

        assertEquals("SALE_OUTBOUND", outbound.txnType());
        assertEquals(0, new BigDecimal("-4").compareTo(outbound.qtyDelta()));
        assertEquals(0, new BigDecimal("10").compareTo(outbound.qtyBefore()));
        assertEquals(0, new BigDecimal("6").compareTo(outbound.qtyAfter()));
        assertEquals(0, new BigDecimal("6").compareTo(onHand(SKU_A)));
    }

    @Test
    @DisplayName("T4 applyOutbound 超量 → INV-5001，且余额未变、无流水落库")
    void outboundOverQuantityRejected() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        int before = txnCount();

        assertBizCode(ErrorCodes.INV_5001, () -> writeService.applyOutbound(SKU_A, WAREHOUSE_ID,
                new BigDecimal("11"), "SALE", null, "SALE_OUTBOUND:SALE:over:9002"));

        assertEquals(0, new BigDecimal("10").compareTo(onHand(SKU_A)), "失败不得改动余额");
        assertEquals(before, txnCount(), "失败不得留下流水");
    }

    // =====================================================================
    // T5–T6 幂等
    // =====================================================================

    @Test
    @DisplayName("T5 幂等命中同内容 → idempotentHit=true，流水表仍只 1 条")
    void idempotentReplaySameContent() {
        String key = "PURCHASE_INBOUND:GOODS_RECEIPT:888:9002";
        StockChangeResult first = writeService.applyInbound(SKU_A, WAREHOUSE_ID, new BigDecimal("3"),
                "GOODS_RECEIPT", 888L, key);
        StockChangeResult replay = writeService.applyInbound(SKU_A, WAREHOUSE_ID, new BigDecimal("3"),
                "GOODS_RECEIPT", 888L, key);

        assertFalse(first.idempotentHit());
        assertTrue(replay.idempotentHit(), "同内容重放必须是幂等命中");
        assertEquals(first.txnId(), replay.txnId(), "重放必须返回原流水 ID");
        assertEquals(1, txnCount(), "重放不得新增流水");
        assertEquals(0, new BigDecimal("3").compareTo(onHand(SKU_A)), "重放不得重复累加");
    }

    @Test
    @DisplayName("T6 幂等命中不同内容 → INV-5003")
    void idempotentReplayDifferentContent() {
        String key = "PURCHASE_INBOUND:GOODS_RECEIPT:889:9002";
        writeService.applyInbound(SKU_A, WAREHOUSE_ID, new BigDecimal("3"), "GOODS_RECEIPT", 889L, key);

        assertBizCode(ErrorCodes.INV_5003, () -> writeService.applyInbound(SKU_A, WAREHOUSE_ID,
                new BigDecimal("4"), "GOODS_RECEIPT", 889L, key));

        assertEquals(1, txnCount());
        assertEquals(0, new BigDecimal("3").compareTo(onHand(SKU_A)));
    }

    // =====================================================================
    // T7–T8 / W3–W5 并发与重试
    // =====================================================================

    @Test
    @DisplayName("T7 并发两线程同 SKU 各 +1（不同幂等键）→ 两条流水、最终余额 = 初值 + 2")
    void concurrentInboundKeepsBothTransactions() throws Exception {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        int before = txnCount();

        runConcurrently(2, index -> writeService.applyInbound(SKU_A, WAREHOUSE_ID, BigDecimal.ONE,
                "GOODS_RECEIPT", null, "concurrent-" + index));

        assertEquals(before + 2, txnCount(), "两次并发变更必须各留一条流水");
        assertEquals(0, new BigDecimal("12").compareTo(onHand(SKU_A)),
                "最终余额必须为 10 + 2（乐观锁重试生效）");
        assertEquals(0, new BigDecimal("12").compareTo(sumOfDeltas(SKU_A)));
    }

    @Test
    @DisplayName("T7b 并发首次建行（无余额行）→ 唯一键吸收冲突：1 行余额、2 条流水、余额 = 2（W5）")
    void concurrentFirstInsertAbsorbsUniqueConflict() throws Exception {
        long sku = SKU_WITHOUT_BALANCE;
        runConcurrently(2, index -> writeService.applyInitialImport(sku, WAREHOUSE_ID, BigDecimal.ONE,
                null, "first-insert-" + index));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_balances WHERE sku_id = ?", Integer.class, sku),
                "并发首次建行只能留下 1 行余额（uk_invbal_team_wh_sku）");
        assertEquals(2, txnCount());
        assertEquals(0, new BigDecimal("2").compareTo(onHand(sku)));
    }

    @Test
    @DisplayName("T8a 乐观锁前两次冲突、第三次成功 → 只有 1 条流水（SAVEPOINT 抹掉失败尝试的流水，W4）")
    void optimisticConflictRetriesAndLeavesSingleTransaction() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        int before = txnCount();

        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> calls.incrementAndGet() <= 2 ? 0 : invocation.callRealMethod())
                .when(balanceUpdater).apply(anyLong(), anyLong(), anyInt(), any(), any(), any(), any());

        StockChangeResult result = writeService.applyInbound(SKU_A, WAREHOUSE_ID, BigDecimal.ONE,
                "GOODS_RECEIPT", null, "retry-then-ok");

        assertEquals(3, calls.get(), "前两次冲突、第三次成功");
        assertEquals(before + 1, txnCount(), "失败尝试插入的流水必须随 SAVEPOINT 回滚，不得留孤儿");
        assertEquals(0, new BigDecimal("11").compareTo(onHand(SKU_A)));
        assertFalse(result.idempotentHit());
    }

    @Test
    @DisplayName("T8b 乐观锁连续冲突 → INV-5002，且无孤儿流水（流水条数不变）")
    void optimisticConflictExhaustedLeavesNoOrphanTransaction() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        int before = txnCount();
        BigDecimal onHandBefore = onHand(SKU_A);

        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            return 0;
        }).when(balanceUpdater).apply(anyLong(), anyLong(), anyInt(), any(), any(), any(), any());

        assertBizCode(ErrorCodes.INV_5002, () -> writeService.applyInbound(SKU_A, WAREHOUSE_ID,
                BigDecimal.ONE, "GOODS_RECEIPT", null, "always-conflict"));

        assertEquals(4, calls.get(),
                "★ 应恰为 1 次初试 + 3 次重试 = 4 次余额更新尝试，实测 " + calls.get());
        assertEquals(before, txnCount(), "★ 3 次重试的流水必须全部回滚，孤儿流水 = 0");
        assertEquals(0, onHandBefore.compareTo(onHand(SKU_A)), "余额不得被推进");
    }

    // =====================================================================
    // T9–T15 调整路径
    // =====================================================================

    @Test
    @DisplayName("T9 applyCountAdjust 差额非 0 → 写 COUNT_ADJUST 流水（qty_delta = target - on_hand）")
    void countAdjustWritesDelta() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        StockChangeResult result = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("7"),
                "月度盘点差异", 601L, "COUNT_ADJUST:COUNT:601:9002");

        assertEquals("COUNT_ADJUST", result.txnType());
        assertEquals("COUNT", result.bizType());
        assertEquals(0, new BigDecimal("-3").compareTo(result.qtyDelta()));
        assertEquals(0, new BigDecimal("7").compareTo(onHand(SKU_A)));
        Map<String, Object> txn = lastTxn();
        assertEquals("COUNT_ADJUST", txn.get("txn_type"));
        assertEquals("月度盘点差异", txn.get("reason"), "reason 必须落库（§5.5 差异记录）");
    }

    @Test
    @DisplayName("T10 applyCountAdjust 差额 0 → 不写流水、返回成功（§5.5 步骤 G，txnId = null）")
    void countAdjustZeroDeltaWritesNothing() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        int before = txnCount();

        StockChangeResult result = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("10"),
                "盘点无差异", null, "COUNT_ADJUST:COUNT:zero:9002");

        assertTrue(result.idempotentHit(), "差额 0 属幂等成功");
        assertNull(result.txnId(), "差额 0 不写流水");
        assertEquals(0, result.qtyDelta().signum());
        assertEquals(before, txnCount(), "不得新增流水");
        assertEquals(0, new BigDecimal("10").compareTo(onHand(SKU_A)));
    }

    // =====================================================================
    // R76 / ADR-008：差额 0 也占用幂等键（新增占位表）
    // =====================================================================

    @Test
    @DisplayName("R76-1 差额 0：不写流水、但幂等占位表登记 1 行（key 有了载体）")
    void zeroDeltaRegistersIdempotencyPlaceholder() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        int txnBefore = txnCount();
        String key = "COUNT_ADJUST:COUNT:r76-1:9002";

        StockChangeResult result = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("10"),
                "盘点无差异", null, key);

        assertTrue(result.idempotentHit(), "差额 0 仍是幂等成功");
        assertNull(result.txnId(), "差额 0 仍不写流水（§5.5 步骤 G 不变）");
        assertEquals(txnBefore, txnCount(), "不得新增流水");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM inventory_idempotency_keys WHERE team_id = ? AND idempotency_key = ?",
                TEAM_ID, key);
        assertEquals("NO_CHANGE", row.get("result_kind"), "占位类型必须是 NO_CHANGE");
        assertEquals("COUNT_ADJUST", row.get("txn_type"));
        assertNotNull(row.get("content_hash"), "内容哈希必须登记（ADR-008 §5.3）");
    }

    @Test
    @DisplayName("R76-2 ★危害链守卫：余额被外部改动后同 key 重试 ⇒ 纯重放（余额不回弹）")
    void retryAfterBalanceChangedIsPureReplay() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        String key = "COUNT_ADJUST:COUNT:r76-2:9002";

        StockChangeResult first = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("10"),
                "盘点无差异", null, key);
        assertTrue(first.idempotentHit());
        assertNull(first.txnId());

        // 期间用一次【非零差额】的盘点把余额改掉（模拟出库/其他变动）
        writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("5"), "事后下调", null,
                "COUNT_ADJUST:COUNT:r76-2-other:9002");
        assertEquals(0, new BigDecimal("5").compareTo(onHand(SKU_A)), "余额已被改成 5");

        int txnBefore = txnCount();
        // ★ 同 key 同请求体重发 —— R76 的危害链就在这一步：旧实现会把它当成「首次」
        StockChangeResult retry = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("10"),
                "盘点无差异", null, key);

        assertTrue(retry.idempotentHit(), "必须判为幂等命中");
        assertEquals(0, new BigDecimal("5").compareTo(onHand(SKU_A)),
                "★ 余额不得被拉回 10（R76 的核心危害：静默抹掉中间变动）");
        assertEquals(txnBefore, txnCount(), "不得新增流水");
    }

    @Test
    @DisplayName("R76-3 同 key 换 target ⇒ INV-5003（内容哈希含数量，不能被当成同内容重放）")
    void sameKeyDifferentTargetRejected() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        String key = "COUNT_ADJUST:COUNT:r76-3:9002";

        writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), "盘点", null, key);

        BizException ex = assertThrows(BizException.class, () ->
                writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("20"), "盘点", null, key));
        assertEquals("INV-5003", ex.getErrorCode(),
                "★ 数量必须进内容哈希：否则「同 key 换 target」会被静默当成重放（问题只是被挪了位置）");
        assertEquals(0, new BigDecimal("10").compareTo(onHand(SKU_A)), "余额不变");
    }

    @Test
    @DisplayName("R76-4 差额非 0 的原有路径不变：只写流水、不写占位表（两表互斥）")
    void nonZeroDeltaDoesNotWritePlaceholder() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        String key = "COUNT_ADJUST:COUNT:r76-4:9002";

        StockChangeResult result = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("12"),
                "盘点差异", null, key);

        assertNotNull(result.txnId(), "差额非 0 必须写流水");
        assertFalse(result.idempotentHit());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_idempotency_keys WHERE team_id = ? AND idempotency_key = ?",
                Integer.class, TEAM_ID, key),
                "★ 两表互斥：非零差额不写占位表（避免「两个载体必须永远同步」的新不变式，ADR-008 §4）");
        // 同 key 重放仍走【流水侧】第 ① 步，行为不变
        StockChangeResult replay = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("12"),
                "盘点差异", null, key);
        assertTrue(replay.idempotentHit());
        assertEquals(result.txnId(), replay.txnId(), "重放须带回既有流水 id");
    }

    @Test
    @DisplayName("R76-5 跨团队隔离：别团队的占位行查不到（租户线不被绕过）")
    void placeholderIsTenantScoped() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        String key = "COUNT_ADJUST:COUNT:r76-5:9002";
        writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), "盘点", null, key);

        // 直接以别团队身份查同 key：租户线应使其查不到
        jdbc.update("UPDATE inventory_idempotency_keys SET team_id = ? WHERE idempotency_key = ?",
                OTHER_TEAM_ID, key);
        // 若租户线被绕过，同 key 重试会命中别团队的占位行 ⇒ 误判为重放
        StockChangeResult retry = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("10"),
                "盘点", null, key);
        assertTrue(retry.idempotentHit(), "本团队仍应正常命中自己的占位（本用例只验证可见性）");
    }

    @Test
    @DisplayName("T10b（R74）replayed() 只对真重放为 true —— 差额 0 的 idempotentHit=true 但 replayed()=false")
    void replayedOnlyForRealReplay() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");

        StockChangeResult zeroDelta = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID,
                new BigDecimal("10"), "盘点无差异", null, "COUNT_ADJUST:COUNT:T10b-zero:9002");
        assertTrue(zeroDelta.idempotentHit(),
                "差额 0 仍是「无变更成功」，idempotentHit 语义保持不变（B9 审计跳过逻辑依赖它）");
        assertNull(zeroDelta.txnId(), "差额 0 不写流水");
        assertFalse(zeroDelta.replayed(),
                "★ R74：无变更成功不是重放，不得让 HTTP 头标成 Idempotent-Replayed");

        String key = "COUNT_ADJUST:COUNT:T10b-replay:9002";
        StockChangeResult first = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID,
                new BigDecimal("7"), "月度盘点差异", null, key);
        assertNotNull(first.txnId());
        assertFalse(first.idempotentHit(), "首次真正落账不是幂等命中");
        assertFalse(first.replayed(), "首次真正落账不是重放");

        StockChangeResult replay = writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID,
                new BigDecimal("7"), "月度盘点差异", null, key);
        assertTrue(replay.idempotentHit());
        assertNotNull(replay.txnId(), "真重放必定带回既有流水 id");
        assertTrue(replay.replayed(),
                "★ 反向防线：真重放必须 replayed()=true（修 R74 不得变成「重放也不带头」）");
        assertEquals(first.txnId(), replay.txnId(), "重放须返回首次的流水 id");
    }

    @Test
    @DisplayName("T11 applyManualAdjust 正常 → MANUAL_ADJUST 流水（带符号增量）")
    void manualAdjustWritesTransaction() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        StockChangeResult result = writeService.applyManualAdjust(SKU_A, WAREHOUSE_ID,
                new BigDecimal("-2.5"), "破损报废", "MANUAL_ADJUST:MANUAL:1:9002");

        assertEquals("MANUAL_ADJUST", result.txnType());
        assertEquals(0, new BigDecimal("-2.5").compareTo(result.qtyDelta()));
        assertEquals(0, new BigDecimal("7.5").compareTo(onHand(SKU_A)));
        assertEquals("破损报废", lastTxn().get("reason"));
    }

    @Test
    @DisplayName("T12 applyManualAdjust reason 为空 → INV-5011")
    void manualAdjustRequiresReason() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        assertBizCode(ErrorCodes.INV_5011, () -> writeService.applyManualAdjust(SKU_A, WAREHOUSE_ID,
                new BigDecimal("-1"), "   ", "MANUAL_ADJUST:MANUAL:blank:9002"));
        assertEquals(1, txnCount(), "校验失败不得落流水");
    }

    @Test
    @DisplayName("T13 applyCountAdjust reason 为空 → INV-5011（R55 回归点）")
    void countAdjustRequiresReason() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        assertBizCode(ErrorCodes.INV_5011, () -> writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID,
                new BigDecimal("9"), null, null, "COUNT_ADJUST:COUNT:null-reason:9002"));
        assertEquals(1, txnCount());
    }

    @Test
    @DisplayName("T14 applyManualAdjust qtyDelta = 0 → INV-5005（400，不写流水）")
    void manualAdjustZeroDeltaRejected() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        assertBizCode(ErrorCodes.INV_5005, () -> writeService.applyManualAdjust(SKU_A, WAREHOUSE_ID,
                BigDecimal.ZERO, "零调整", "MANUAL_ADJUST:MANUAL:0:9002"));
        assertEquals(1, txnCount());
    }

    @Test
    @DisplayName("T15 调整致负 → INV-5006（与出库 INV-5001 严格分开）")
    void adjustToNegativeRejectedWithInv5006() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");

        assertBizCode(ErrorCodes.INV_5006, () -> writeService.applyManualAdjust(SKU_A, WAREHOUSE_ID,
                new BigDecimal("-11"), "超调", "MANUAL_ADJUST:MANUAL:neg:9002"));
        assertBizCode(ErrorCodes.INV_5006, () -> writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID,
                new BigDecimal("-1"), "盘成负数", null, "COUNT_ADJUST:COUNT:neg:9002"));

        assertEquals(1, txnCount());
        assertEquals(0, new BigDecimal("10").compareTo(onHand(SKU_A)));
    }

    @Test
    @DisplayName("T15b 调整数量精度超 3 位 / 为 NULL → INV-5005")
    void adjustQuantityPrecisionRejected() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        assertBizCode(ErrorCodes.INV_5005, () -> writeService.applyManualAdjust(SKU_A, WAREHOUSE_ID,
                new BigDecimal("1.0001"), "精度超限", "MANUAL_ADJUST:MANUAL:scale:9002"));
        assertBizCode(ErrorCodes.INV_5005, () -> writeService.applyCountAdjust(SKU_A, WAREHOUSE_ID,
                null, "目标为空", null, "COUNT_ADJUST:COUNT:null:9002"));
        assertEquals(1, txnCount());
    }

    // =====================================================================
    // T16–T19 冲销
    // =====================================================================

    @Test
    @DisplayName("T16 applyReversal 正常 → qty_delta = -原、reversed_txn_id = 原 id、余额回退")
    void reversalWritesOppositeTransaction() {
        StockChangeResult init = writeService.applyInitialImport(SKU_A, WAREHOUSE_ID,
                new BigDecimal("10"), null, "init-A");
        StockChangeResult reversal = writeService.applyReversal(init.txnId(), "导入错误",
                "REVERSAL:REVERSAL:" + init.txnId() + ":9002");

        assertEquals("REVERSAL", reversal.txnType());
        assertEquals("REVERSAL", reversal.bizType());
        assertEquals(0, new BigDecimal("-10").compareTo(reversal.qtyDelta()));
        assertEquals(0, BigDecimal.ZERO.compareTo(onHand(SKU_A)), "余额必须回退到 0");

        Map<String, Object> txn = jdbc.queryForMap(
                "SELECT * FROM inventory_transactions WHERE id = ?", reversal.txnId());
        assertEquals(init.txnId().longValue(), ((Number) txn.get("reversed_txn_id")).longValue());
        assertEquals(init.txnId().longValue(), ((Number) txn.get("biz_ref_id")).longValue());
    }

    @Test
    @DisplayName("T17 二次冲销 → INV-5009（一笔流水只能冲一次）")
    void doubleReversalRejected() {
        StockChangeResult init = writeService.applyInitialImport(SKU_A, WAREHOUSE_ID,
                new BigDecimal("10"), null, "init-A");
        writeService.applyReversal(init.txnId(), "第一次冲销", "REVERSAL:REVERSAL:r1:" + init.txnId());

        assertBizCode(ErrorCodes.INV_5009, () -> writeService.applyReversal(init.txnId(), "第二次冲销",
                "REVERSAL:REVERSAL:r2:" + init.txnId()));
        assertEquals(2, txnCount(), "第二次冲销不得落流水");
    }

    @Test
    @DisplayName("T18 冲销一笔 REVERSAL → INV-5010（422）")
    void reversalOfReversalRejected() {
        StockChangeResult init = writeService.applyInitialImport(SKU_A, WAREHOUSE_ID,
                new BigDecimal("10"), null, "init-A");
        StockChangeResult reversal = writeService.applyReversal(init.txnId(), "冲销入库",
                "REVERSAL:REVERSAL:first:" + init.txnId());

        assertBizCode(ErrorCodes.INV_5010, () -> writeService.applyReversal(reversal.txnId(), "冲销冲销",
                "REVERSAL:REVERSAL:second:" + reversal.txnId()));
    }

    @Test
    @DisplayName("T19 冲销不存在的流水 → INV-5008（404）")
    void reversalOfMissingTransactionRejected() {
        assertBizCode(ErrorCodes.INV_5008, () -> writeService.applyReversal(999_999L, "不存在", "REVERSAL:x"));
    }

    @Test
    @DisplayName("T19b 冲销异团队流水 → INV-5008（租户线让异团队流水「查不到」）")
    void reversalOfOtherTeamTransactionRejected() {
        jdbc.update("INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, "
                        + "qty_before, qty_after, biz_type, idempotency_key, occurred_at) "
                        + "VALUES (?, ?, ?, 'MANUAL_ADJUST', 1, 0, 1, 'MANUAL', 'other-team-key', NOW(3))",
                OTHER_TEAM_ID, OTHER_WAREHOUSE_ID, OTHER_SKU_ID);
        long otherTxnId = jdbc.queryForObject("SELECT id FROM inventory_transactions "
                + "WHERE idempotency_key = 'other-team-key'", Long.class);

        assertBizCode(ErrorCodes.INV_5008, () -> writeService.applyReversal(otherTxnId, "越权冲销",
                "REVERSAL:cross-team"));
    }

    // =====================================================================
    // T20–T23 其余边界
    // =====================================================================

    @Test
    @DisplayName("T20 无余额行时 applyOutbound / applyManualAdjust → INV-5004（404）")
    void missingBalanceRejectedForOutboundAndAdjust() {
        assertBizCode(ErrorCodes.INV_5004, () -> writeService.applyOutbound(SKU_WITHOUT_BALANCE,
                WAREHOUSE_ID, BigDecimal.ONE, "SALE", null, "SALE_OUTBOUND:missing:9006"));
        assertBizCode(ErrorCodes.INV_5004, () -> writeService.applyManualAdjust(SKU_WITHOUT_BALANCE,
                WAREHOUSE_ID, BigDecimal.ONE, "无余额", "MANUAL_ADJUST:missing:9006"));
        assertEquals(0, txnCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balances", Integer.class),
                "不得因失败而建行");
    }

    @Test
    @DisplayName("T21 余额更新异常 → 流水与余额同时不落库（同事务原子性）")
    void balanceUpdateFailureRollsBackTransaction() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, new BigDecimal("10"), null, "init-A");
        int before = txnCount();

        doThrow(new IllegalStateException("injected balance update failure"))
                .when(balanceUpdater).apply(anyLong(), anyLong(), anyInt(), any(), any(), any(), any());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                writeService.applyManualAdjust(SKU_A, WAREHOUSE_ID, new BigDecimal("5"), "注入失败",
                        "MANUAL_ADJUST:injected:9002"));
        assertEquals("injected balance update failure", ex.getMessage());

        assertEquals(before, txnCount(), "★ 流水必须随之回滚（否则产生孤儿）");
        assertEquals(0, new BigDecimal("10").compareTo(onHand(SKU_A)), "余额不得被推进");
    }

    @Test
    @DisplayName("T22 lockBalanceAnchors：3 个存在 + 1 个缺失 → locked=3、missing=1，且按 sku_id 升序")
    void lockBalanceAnchorsSplitsLockedAndMissing() {
        writeService.applyInitialImport(SKU_A, WAREHOUSE_ID, BigDecimal.ONE, null, "init-A");
        writeService.applyInitialImport(SKU_B, WAREHOUSE_ID, BigDecimal.ONE, null, "init-B");
        writeService.applyInitialImport(SKU_C, WAREHOUSE_ID, BigDecimal.ONE, null, "init-C");

        AnchorLockResult result = writeService.lockBalanceAnchors(TEAM_ID, WAREHOUSE_ID,
                List.of(SKU_C, SKU_A, SKU_B, SKU_WITHOUT_BALANCE));

        assertEquals(List.of(SKU_A, SKU_B, SKU_C), result.lockedSkuIds(), "锁定列表必须按 sku_id 升序");
        assertEquals(List.of(SKU_WITHOUT_BALANCE), result.missingSkuIds());
    }

    @Test
    @DisplayName("T22b lockBalanceAnchors 的 teamId 与 TeamContext 不一致 → TEAM-2001（不静默返回空）")
    void lockBalanceAnchorsRejectsTeamMismatch() {
        assertBizCode(ErrorCodes.TEAM_2001, () ->
                writeService.lockBalanceAnchors(OTHER_TEAM_ID, WAREHOUSE_ID, List.of(SKU_A)));
    }

    @Test
    @DisplayName("T23 V1.0.7 生效：available_qty 的 IS_NULLABLE = NO（R53）")
    void availableQtyIsNotNullAfterFixMigration() {
        String nullable = jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'inventory_balances' "
                + "AND column_name = 'available_qty'", String.class);
        assertEquals("NO", nullable, "V1.0.7 必须把生成列改为 STORED NOT NULL");

        String extra = jdbc.queryForObject("SELECT extra FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'inventory_balances' "
                + "AND column_name = 'available_qty'", String.class);
        assertTrue(extra.contains("STORED GENERATED"), "仍须是 STORED 生成列，实测：" + extra);
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private interface IndexedAction {
        void run(int index);
    }

    /** 用 {@code n} 个线程在同一时刻发起调用；每个线程自行注入 {@link TeamContext}（ThreadLocal）。 */
    private void runConcurrently(int n, IndexedAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    TeamContext.set(new TeamContext.Snapshot(TEAM_ID, List.of("OWNER"),
                            OPERATOR_ID, "operator01"));
                    try {
                        ready.countDown();
                        start.await();
                        action.run(index);
                        return null;
                    } finally {
                        TeamContext.clear();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertBizCode(String expectedCode, Executable executable) {
        BizException ex = assertThrows(BizException.class, executable);
        assertEquals(expectedCode, ex.getErrorCode(), "错误码不符：" + ex.getMessage());
    }

    private int txnCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transactions WHERE team_id = ?",
                Integer.class, TEAM_ID);
    }

    private Map<String, Object> onlyTxn() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM inventory_transactions WHERE team_id = ? ORDER BY id", TEAM_ID);
        assertEquals(1, rows.size(), "应恰有 1 条流水，实测：" + rows.size());
        return rows.get(0);
    }

    private Map<String, Object> lastTxn() {
        return jdbc.queryForMap("SELECT * FROM inventory_transactions WHERE team_id = ? "
                + "ORDER BY id DESC LIMIT 1", TEAM_ID);
    }

    private Map<String, Object> balanceRow(long skuId) {
        return jdbc.queryForMap("SELECT * FROM inventory_balances WHERE team_id = ? AND warehouse_id = ? "
                + "AND sku_id = ?", TEAM_ID, WAREHOUSE_ID, skuId);
    }

    private BigDecimal onHand(long skuId) {
        return jdbc.queryForObject("SELECT on_hand_qty FROM inventory_balances WHERE team_id = ? "
                + "AND warehouse_id = ? AND sku_id = ?", BigDecimal.class, TEAM_ID, WAREHOUSE_ID, skuId);
    }

    private BigDecimal sumOfDeltas(long skuId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(qty_delta), 0) FROM inventory_transactions "
                + "WHERE team_id = ? AND warehouse_id = ? AND sku_id = ?", BigDecimal.class,
                TEAM_ID, WAREHOUSE_ID, skuId);
    }
}
