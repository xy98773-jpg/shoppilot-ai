package com.shoppilot.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 业务指标埋点（M8-T02 / {@code R195}：{@code NFR-OBS-02}）。
 *
 * <h2>指标口径（为什么是这 4 个）</h2>
 * <p>{@code NFR-OBS-02} 原文要求「导入成功率 / 分析成功率 / 降级率 / 耗时」可见。落地为：
 * <ul>
 *   <li>{@code shoppilot.import.batch.total{status, biz_type}} —— 导入批次终态计数；
 *       <b>成功率由查询层算</b>（{@code rate(...[5m])}），不在代码里做除法
 *       （ Micrometer 惯例：埋点只给可加的原始量，比率交给 PromQL）；</li>
 *   <li>{@code shoppilot.analysis.run.total{status, llm_status}} —— 分析运行终态计数；
 *       <b>降级率</b> = {@code status="DEGRADED"} 与总数之比，同样由查询层算；</li>
 *   <li>{@code shoppilot.import.confirm.seconds} —— 导入确认耗时（Timer，自带 count/sum/max）；</li>
 *   <li>{@code shoppilot.analysis.run.seconds} —— 分析运行端到端耗时（Timer）。</li>
 * </ul>
 *
 * <h2>★ 为什么用 {@link ObjectProvider} 而不是直接注入 {@link MeterRegistry}</h2>
 * <p>{@code common} 模块不引入 actuator/micrometer 依赖（保持「只依赖框架、不依赖观测栈」），
 * 注册表由 {@code app}（组合根）提供。用 {@code ObjectProvider} 取：
 * <ol>
 *   <li>没有注册表时（纯单元测试、未启用 actuator 的环境）埋点静默降级为 no-op，
 *       <b>绝不</b>因为观测缺失让业务失败；</li>
 *   <li>避免给 {@code common} 加 micrometer 依赖，也避免业务模块直接依赖 Micrometer API。</li>
 * </ol>
 *
 * <h2>为什么指标名带 {@code shoppilot.} 前缀</h2>
 * <p>同一个 Prometheus 可能被多个应用共用（dev 栈里还有 MySQL/nginx exporter），
 * 前缀保证抓取与告警规则不会误配到别的应用上。
 */
@Component
public final class AppMetrics {

    /** 指标名前缀（应用名 + 点号，便于 PromQL 里 {@code shoppilot_.*} 一把抓全）。 */
    private static final String PREFIX = "shoppilot.";

    private final ObjectProvider<MeterRegistry> registryProvider;

    public AppMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = Objects.requireNonNull(registryProvider, "registryProvider");
    }

    /**
     * 记录一次导入批次的终态。
     *
     * @param status   终态（{@code COMPLETED} / {@code PARTIAL_SUCCESS} / {@code FAILED}）
     * @param bizType  业务类型（{@code SKU} / {@code INVENTORY_SNAPSHOT} / …）
     */
    public void recordImportBatch(String status, String bizType) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(PREFIX + "import.batch.total")
                .description("导入批次终态计数（成功率/失败率由查询层聚合）")
                .tag("status", nullSafe(status))
                .tag("biz_type", nullSafe(bizType))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次导入确认的耗时。
     *
     * @param millis 耗时（毫秒）；负值按 0 记（调用方时钟异常不应污染指标）
     */
    public void recordImportConfirm(long millis) {
        recordDuration(PREFIX + "import.confirm.seconds", "导入确认耗时", millis);
    }

    /**
     * 记录一次分析运行的终态。
     *
     * @param status    终态（{@code SUCCEEDED} / {@code DEGRADED} / {@code FAILED} / {@code TIMEOUT}）
     * @param llmStatus LLM 状态（{@code SUCCEEDED} / {@code SKIPPED} / {@code FAILED}），可空
     */
    public void recordAnalysisRun(String status, String llmStatus) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(PREFIX + "analysis.run.total")
                .description("分析运行终态计数（降级率 = DEGRADED / 总数，由查询层算）")
                .tag("status", nullSafe(status))
                .tag("llm_status", nullSafe(llmStatus))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次分析运行的端到端耗时（提交到终态）。
     *
     * @param millis 耗时（毫秒）；负值按 0 记
     */
    public void recordAnalysisRunDuration(long millis) {
        recordDuration(PREFIX + "analysis.run.seconds", "分析运行端到端耗时（提交→终态）", millis);
    }

    private void recordDuration(String name, String description, long millis) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Timer.builder(name)
                .description(description)
                .register(registry)
                .record(Math.max(millis, 0L), TimeUnit.MILLISECONDS);
    }

    /** 标签值兜底：Micrometer 拒绝 null 标签值，统一落到 {@code unknown} 而不是让埋点炸掉业务。 */
    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
