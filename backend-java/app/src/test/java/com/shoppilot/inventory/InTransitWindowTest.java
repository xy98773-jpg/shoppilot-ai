package com.shoppilot.inventory;

import com.shoppilot.inventory.application.InTransitFactProvider;
import com.shoppilot.inventory.application.InTransitFactProvider.InTransitFact;
import com.shoppilot.inventory.application.InventoryInTransitQueryService;
import com.shoppilot.inventory.application.InventoryInTransitQueryServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code M6-T10} · 在途窗口口径纯单测（{@code DESIGN_BASIS §10.4}）。
 *
 * <p><b>不启 Spring、不连库</b>：{@link InventoryInTransitQueryServiceImpl} 只依赖
 * {@link InTransitFactProvider} 端口，用 lambda 假实现即可把「窗口判定」这一纯函数逻辑钉死。
 * 真实 SQL / 状态过滤由 {@code InTransitDerivationIntegrationTest} 覆盖。
 *
 * <p>窗口末日由<b>调用方</b>传入（{@code today + L + R}），故本类用固定日期即可确定性断言，
 * 不受系统时钟影响。
 */
class InTransitWindowTest {

    private static final long TEAM = 7L;
    private static final long WH = 9004L;
    /** 窗口末日（由 analysis 侧算好的 {@code today + L + R}）。 */
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 11, 4);
    private static final long SKU_A = 9002L;
    private static final long SKU_B = 9003L;

    private static InventoryInTransitQueryService serviceOf(List<InTransitFact> facts) {
        // 端口语义：只按 skuIds 过滤，不做任何窗口判断
        InTransitFactProvider provider = (teamId, warehouseId, skuIds) -> facts.stream()
                .filter(f -> skuIds.contains(f.skuId()))
                .toList();
        return new InventoryInTransitQueryServiceImpl(provider);
    }

    private static Map<Long, LocalDate> windowOf(long skuId, LocalDate windowEnd) {
        return Map.of(skuId, windowEnd);
    }

    @Test
    @DisplayName("W1 到期日为 NULL ⇒ 保守计入（§10.4）")
    void nullArrivalIsCounted() {
        var service = serviceOf(List.of(new InTransitFact(SKU_A, new BigDecimal("10.000"), null)));

        assertEquals(new BigDecimal("10.000"),
                service.batchGetEffectiveInTransit(TEAM, WH, windowOf(SKU_A, WINDOW_END)).get(SKU_A));
    }

    @Test
    @DisplayName("W2 到期日 == 窗口末日 ⇒ 计入（含等号）")
    void arrivalOnWindowEndIsCounted() {
        var service = serviceOf(List.of(new InTransitFact(SKU_A, new BigDecimal("12.000"), WINDOW_END)));

        assertEquals(new BigDecimal("12.000"),
                service.batchGetEffectiveInTransit(TEAM, WH, windowOf(SKU_A, WINDOW_END)).get(SKU_A));
    }

    @Test
    @DisplayName("W3 到期日 == 窗口末日 + 1 ⇒ 不计入（远期在途不虚增 IP）")
    void arrivalAfterWindowEndIsExcluded() {
        var service = serviceOf(List.of(
                new InTransitFact(SKU_A, new BigDecimal("12.000"), WINDOW_END.plusDays(1))));

        Map<Long, BigDecimal> effective =
                service.batchGetEffectiveInTransit(TEAM, WH, windowOf(SKU_A, WINDOW_END));

        assertFalse(effective.containsKey(SKU_A), "窗口外不得出现在返回 map（map 只含 > 0 的项）");
        assertEquals(Map.of(), effective);
    }

    @Test
    @DisplayName("W4 到期日远早于窗口（历史逾期未到）⇒ 仍计入")
    void pastDueArrivalIsCounted() {
        var service = serviceOf(List.of(
                new InTransitFact(SKU_A, new BigDecimal("7.000"), WINDOW_END.minusDays(30))));

        assertEquals(new BigDecimal("7.000"),
                service.batchGetEffectiveInTransit(TEAM, WH, windowOf(SKU_A, WINDOW_END)).get(SKU_A));
    }

    @Test
    @DisplayName("W5 同 SKU 多行 ⇒ 求和；不同 SKU 的窗口末日互不干扰（per-SKU 窗口）")
    void multipleLinesAreSummedPerSku() {
        var service = serviceOf(List.of(
                new InTransitFact(SKU_A, new BigDecimal("10.000"), null),
                new InTransitFact(SKU_A, new BigDecimal("5.500"), WINDOW_END),
                // SKU_B 的窗口更宽（L 更大），同一到期日对它仍在窗口内
                new InTransitFact(SKU_B, new BigDecimal("3.000"), WINDOW_END.plusDays(10))));

        Map<Long, BigDecimal> effective = service.batchGetEffectiveInTransit(TEAM, WH,
                Map.of(SKU_A, WINDOW_END, SKU_B, WINDOW_END.plusDays(20)));

        assertEquals(new BigDecimal("15.500"), effective.get(SKU_A));
        assertEquals(new BigDecimal("3.000"), effective.get(SKU_B),
                "★ 窗口必须逐 SKU：SKU_B 的 L 更大，其窗口末日更晚，故 10 天后到期仍计入");
    }

    @Test
    @DisplayName("W6 skuWindowEnds 缺该 SKU ⇒ 不计入，且返回 map 不含它（口径不可判定时不猜）")
    void missingWindowIsSkipped() {
        var service = serviceOf(List.of(
                new InTransitFact(SKU_A, new BigDecimal("10.000"), null),
                new InTransitFact(SKU_B, new BigDecimal("20.000"), null)));

        Map<Long, BigDecimal> effective =
                service.batchGetEffectiveInTransit(TEAM, WH, windowOf(SKU_A, WINDOW_END));

        assertEquals(new BigDecimal("10.000"), effective.get(SKU_A));
        assertFalse(effective.containsKey(SKU_B), "缺窗口末日的 SKU 不得计入");
        assertTrue(effective.size() == 1);
    }

    @Test
    @DisplayName("W7 batchGetInTransit 不应用窗口：同一批事实返回全量合计（≥ 有效在途）")
    void unfilteredNeverLessThanEffective() {
        List<InTransitFact> facts = List.of(
                new InTransitFact(SKU_A, new BigDecimal("10.000"), null),
                new InTransitFact(SKU_A, new BigDecimal("40.000"), WINDOW_END.plusDays(100)));
        var service = serviceOf(facts);

        Map<Long, BigDecimal> raw = service.batchGetInTransit(TEAM, WH, List.of(SKU_A));
        Map<Long, BigDecimal> effective =
                service.batchGetEffectiveInTransit(TEAM, WH, windowOf(SKU_A, WINDOW_END));

        assertEquals(new BigDecimal("50.000"), raw.get(SKU_A), "原始在途含窗口外的 40");
        assertEquals(new BigDecimal("10.000"), effective.get(SKU_A));
        assertTrue(raw.get(SKU_A).compareTo(effective.get(SKU_A)) >= 0);
    }

    @Test
    @DisplayName("W8 空入参 ⇒ 空 map，且不调端口（不发 IN ()）")
    void emptyInputShortCircuits() {
        InTransitFactProvider exploding = (teamId, warehouseId, skuIds) -> {
            throw new IllegalStateException("空入参不得调用端口");
        };
        var service = new InventoryInTransitQueryServiceImpl(exploding);

        assertEquals(Map.of(), service.batchGetEffectiveInTransit(TEAM, WH, Map.of()));
        assertEquals(Map.of(), service.batchGetInTransit(TEAM, WH, List.of()));
    }
}
