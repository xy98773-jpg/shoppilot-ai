package com.shoppilot.analysis.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 契约相关 bean 的装配入口。
 *
 * <p>本批（B13）只装配 {@link AiProperties}；HTTP client 与业务编排在 B15 起引入，
 * 届时新增的 bean 也注册在这里，保持「一个模块一个装配入口」。
 *
 * <p>被 {@code app} 的 {@code @SpringBootApplication(scanBasePackages = "com.shoppilot")} 扫描装配，
 * 不需要在任何 {@code pom.xml} 或 {@code @Import} 里额外声明。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiContractConfiguration {
}
