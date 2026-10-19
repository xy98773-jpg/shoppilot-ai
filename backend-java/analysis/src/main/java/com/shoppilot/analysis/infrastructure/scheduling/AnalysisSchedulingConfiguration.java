package com.shoppilot.analysis.infrastructure.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度开关（M4-T05；与 {@link AnalysisRunPollTask} <b>同一个开关</b>）。
 *
 * <h2>★ 为什么单独一个类而不是在任务类上加 {@code @EnableScheduling}</h2>
 * <p>{@code @EnableScheduling} 是<b>全局</b>开关：一旦打开，本仓<b>任何</b>带
 * {@code @Scheduled} 的 Bean 都会开始调度。把它与具体任务类绑在一起，
 * 关闭任务时调度基础设施也随之关闭——不会出现「任务没了但调度器还在跑」的悬空状态。
 *
 * <p>★ 与任务类同用 {@code shoppilot.analysis.poll.enabled} 且
 * {@code matchIfMissing = false}（裁定 #8）：本仓 34+ 个 {@code @SpringBootTest}
 * 都会加载 main 的 {@code application.yml}，默认开启会让它们在后台真打 Python。
 *
 * <p>★ <b>不动</b> {@code app} 模块：{@code @SpringBootApplication} 上是否已有
 * {@code @EnableScheduling} 属全局基建口径，若已存在则本配置类只是<b>幂等重申</b>
 * （Spring 允许多次声明）；若不存在，也只在此条件下生效。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "shoppilot.analysis.poll.enabled", matchIfMissing = false)
public class AnalysisSchedulingConfiguration {
}